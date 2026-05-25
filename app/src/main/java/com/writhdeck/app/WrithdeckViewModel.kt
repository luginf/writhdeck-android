package com.writhdeck.app

import android.Manifest
import android.app.Application
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DocEntry(val name: String, val path: String)
data class StatEntry(val date: String, val words: Int)
data class StatusBar(val left: String = "", val center: String = "", val right: String = "")

class WrithdeckViewModel(app: Application) : AndroidViewModel(app) {

    private val externalDocsDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "writhdeck"
    )
    private val internalDocsDir = File(app.filesDir, "documents")

    private val _storagePermissionGranted = MutableStateFlow(checkStoragePermission())
    val storagePermissionGranted = _storagePermissionGranted.asStateFlow()

    private val docsDir: File get() =
        if (_storagePermissionGranted.value) externalDocsDir else internalDocsDir

    private var config = AppConfig()
    private var appState = AppState()
    private lateinit var stateFile: File

    private val _engineReady = MutableStateFlow(false)
    val engineReady = _engineReady.asStateFlow()

    private val _docs = MutableStateFlow<List<DocEntry>>(emptyList())
    val docs = _docs.asStateFlow()

    private val _recentDocs = MutableStateFlow<List<DocEntry>>(emptyList())
    val recentDocs = _recentDocs.asStateFlow()

    private val _favoriteDocs = MutableStateFlow<List<DocEntry>>(emptyList())
    val favoriteDocs = _favoriteDocs.asStateFlow()

    private val _content = MutableStateFlow("")
    val content = _content.asStateFlow()

    private val _wordCount = MutableStateFlow(0)
    val wordCount = _wordCount.asStateFlow()

    private val _currentFile = MutableStateFlow<DocEntry?>(null)
    val currentFile = _currentFile.asStateFlow()

    private val _dirty = MutableStateFlow(false)
    val dirty = _dirty.asStateFlow()

    private val _headingMarker = MutableStateFlow("=")
    val headingMarker = _headingMarker.asStateFlow()
    private val _markdownHeadings = MutableStateFlow(true)
    val markdownHeadings = _markdownHeadings.asStateFlow()
    private val _keyToc = MutableStateFlow("F11")
    val keyToc = _keyToc.asStateFlow()
    private val _marginWidth = MutableStateFlow(16)
    val marginWidth = _marginWidth.asStateFlow()
    private val _marginHeight = MutableStateFlow(16)
    val marginHeight = _marginHeight.asStateFlow()

    private val _timerType = MutableStateFlow("countdown")
    val timerType = _timerType.asStateFlow()

    private val _themeColors = MutableStateFlow(ThemeColors())
    val themeColors = _themeColors.asStateFlow()

    private val _darkModePreference = MutableStateFlow("auto")
    val darkModePreference = _darkModePreference.asStateFlow()

    private val _statusBar = MutableStateFlow(StatusBar())
    val statusBar = _statusBar.asStateFlow()
    private val _timerActive = MutableStateFlow(false)
    val timerActive = _timerActive.asStateFlow()
    private val _timerRemaining = MutableStateFlow(0)
    val timerRemaining = _timerRemaining.asStateFlow()
    private val _timerLastTick = MutableStateFlow(0L)
    val timerLastTick = _timerLastTick.asStateFlow()

    private var timerJob: Job? = null
    private var externalUri: Uri? = null
    private var externalWritable = false

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage = _snackbarMessage.asStateFlow()

    init {
        viewModelScope.launch { initApp() }
    }

    private suspend fun initApp() {
        val dd = docsDir.also { it.mkdirs() }
        stateFile = File(dd, ".writhdeck.json")

        config = withContext(Dispatchers.IO) {
            val iniFile = File(dd, "writhdeck.ini")
            if (iniFile.exists()) IniParser.parse(iniFile.readText()) else AppConfig()
        }
        appState = withContext(Dispatchers.IO) { StateStore.load(stateFile) }

        applyConfig()
        _timerRemaining.value = if (config.timerType == "stopwatch") 0 else config.timerDurationSecs()
        _timerLastTick.value = 0L
        _timerActive.value = false

        _engineReady.value = true
        refreshDocs()
        refreshFavorites()
        refreshRecents()
        refreshStatus()
    }

    private fun applyConfig() {
        _headingMarker.value = config.headingMarker
        _markdownHeadings.value = config.markdownHeadings
        _keyToc.value = config.keyToc
        _timerType.value = config.timerType
        _marginWidth.value = config.marginWidth
        _marginHeight.value = config.marginHeight
        _darkModePreference.value = config.androidDarkMode
        _themeColors.value = config.themeColors(resolveUseDark())
    }

    private fun resolveUseDark(): Boolean = when (config.androidDarkMode) {
        "yes" -> true
        "no"  -> false
        else  -> isSystemDarkMode()
    }

    fun onStoragePermissionGranted() {
        if (_storagePermissionGranted.value) return
        _storagePermissionGranted.value = true
        viewModelScope.launch {
            _snackbarMessage.value = "Migrating documents..."
            withContext(Dispatchers.IO) {
                externalDocsDir.mkdirs()
                internalDocsDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                    val dst = File(externalDocsDir, f.name)
                    if (!dst.exists()) f.copyTo(dst)
                    f.delete()
                }
            }
            _engineReady.value = false
            timerJob?.cancel(); timerJob = null
            initApp()
            _snackbarMessage.value = "Documents stored in Documents/writhdeck/"
        }
    }

    fun dismissSnackbar() { _snackbarMessage.value = null }

    // --- Docs ---

    fun refreshDocs() {
        viewModelScope.launch(Dispatchers.IO) {
            docsDir.mkdirs()
            val files = docsDir
                .listFiles { f -> f.isFile && (f.extension == "txt" || f.extension == "md" || f.extension == "ini") }
                ?.sortedByDescending { it.lastModified() }
                ?.map { DocEntry(it.name, it.absolutePath) }
                ?: emptyList()
            _docs.value = files
        }
    }

    fun refreshFavorites() {
        _favoriteDocs.value = appState.favorites.mapNotNull { path ->
            val f = File(path)
            if (f.exists()) DocEntry(f.name, f.absolutePath) else null
        }
    }

    fun setDarkModePreference(pref: String) {
        config = config.copy(androidDarkMode = pref)
        _darkModePreference.value = pref
        _themeColors.value = config.themeColors(resolveUseDark())
        viewModelScope.launch(Dispatchers.IO) {
            val iniFile = File(docsDir, "writhdeck.ini")
            if (iniFile.exists()) {
                iniFile.writeText(IniParser.patchKeys(iniFile.readText(), "android_dark_mode" to pref))
            } else {
                iniFile.writeText(IniParser.write(config))
            }
        }
    }

    fun updateThemeColors(systemDark: Boolean) {
        _themeColors.value = config.themeColors(
            when (config.androidDarkMode) {
                "yes" -> true
                "no"  -> false
                else  -> systemDark
            }
        )
    }

    fun openFile(entry: DocEntry) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { File(entry.path).readText() }
            externalUri = null
            externalWritable = false
            _content.value = text
            _currentFile.value = entry
            _dirty.value = false
            val wc = countWords(text)
            _wordCount.value = wc
            appState = StateStore.pushRecent(appState, entry.path)
            appState = StateStore.updateDaily(appState, entry.path, wc, today())
            withContext(Dispatchers.IO) { StateStore.save(stateFile, appState) }
            refreshRecents()
            refreshStatus()
        }
    }

    fun openIniFile() {
        viewModelScope.launch {
            val iniFile = File(docsDir, "writhdeck.ini")
            docsDir.mkdirs()
            val text = withContext(Dispatchers.IO) {
                if (!iniFile.exists()) {
                    val written = IniParser.write(config)
                    iniFile.writeText(written)
                    written
                } else {
                    iniFile.readText()
                }
            }
            externalUri = null
            externalWritable = false
            _content.value = text
            _currentFile.value = DocEntry("writhdeck.ini", iniFile.absolutePath)
            _dirty.value = false
            _wordCount.value = countWords(text)
            refreshStatus()
        }
    }

    private fun refreshRecents() {
        _recentDocs.value = appState.recent
            .mapNotNull { path ->
                val f = File(path)
                if (f.exists() && f.canRead()) DocEntry(f.name, f.absolutePath) else null
            }
            .take(10)
    }

    fun updateContent(text: String) {
        if (text == _content.value) return
        _content.value = text
        _dirty.value = true
        val wc = countWords(text)
        _wordCount.value = wc
        val path = _currentFile.value?.path
        if (path != null) {
            appState = StateStore.updateDaily(appState, path, wc, today())
        }
        refreshStatus()
    }

    fun createFile(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            docsDir.mkdirs()
            val safeName = if (name.endsWith(".txt") || name.endsWith(".md")) name else "$name.txt"
            File(docsDir, safeName).createNewFile()
            refreshDocs()
        }
    }

    fun renameFile(entry: DocEntry, newName: String) {
        viewModelScope.launch {
            val ext = File(entry.path).extension
            val safeName = if (newName.endsWith(".txt") || newName.endsWith(".md")) newName
                           else if (ext.isNotEmpty()) "$newName.$ext" else "$newName.txt"
            val parent = File(entry.path).parentFile ?: docsDir
            val dst = File(parent, safeName)
            val src = File(entry.path)
            val renamed = withContext(Dispatchers.IO) { !dst.exists() && src.renameTo(dst) }
            if (!renamed) { _snackbarMessage.value = "Could not rename"; return@launch }
            appState = StateStore.renamePath(appState, entry.path, dst.absolutePath)
            withContext(Dispatchers.IO) { StateStore.save(stateFile, appState) }
            if (_currentFile.value?.path == entry.path) {
                _currentFile.value = DocEntry(dst.name, dst.absolutePath)
            }
            refreshDocs()
            refreshFavorites()
            refreshRecents()
        }
    }

    fun deleteFile(entry: DocEntry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { File(entry.path).delete() }
            appState = StateStore.removePath(appState, entry.path)
            withContext(Dispatchers.IO) { StateStore.save(stateFile, appState) }
            if (_currentFile.value?.path == entry.path) {
                _currentFile.value = null
                _content.value = ""
                _dirty.value = false
            }
            refreshDocs()
            refreshFavorites()
            refreshRecents()
        }
    }

    fun backupFile(entry: DocEntry) {
        viewModelScope.launch {
            try {
                val dstName = withContext(Dispatchers.IO) {
                    val src = File(entry.path)
                    val backupsDir = File(docsDir, "backups").also { it.mkdirs() }
                    val ts = SimpleDateFormat("yyyy-MM-dd'T'HH'h'mm'm'ss", Locale.US).format(Date())
                    val ext = src.extension.let { if (it.isEmpty()) "" else ".$it" }
                    val dst = File(backupsDir, "${src.nameWithoutExtension}_$ts$ext")
                    src.copyTo(dst)
                    dst.name
                }
                _snackbarMessage.value = "Backed up: $dstName"
            } catch (_: Exception) {
                _snackbarMessage.value = "Backup failed"
            }
        }
    }

    fun toggleFavorite(entry: DocEntry) {
        appState = StateStore.toggleFavorite(appState, entry.path)
        viewModelScope.launch(Dispatchers.IO) { StateStore.save(stateFile, appState) }
        refreshFavorites()
    }

    fun clearExternalFile() {
        externalUri = null
        externalWritable = false
    }

    fun openExternalContent(uri: Uri, contentResolver: ContentResolver, canWrite: Boolean) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: ""
            }
            val resolvedPath = withContext(Dispatchers.IO) { resolveContentUri(uri, contentResolver) }
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "untitled.txt"
            if (resolvedPath != null && (File(resolvedPath).canWrite() || _storagePermissionGranted.value)) {
                externalUri = null
                externalWritable = false
                _currentFile.value = DocEntry(name, resolvedPath)
                appState = StateStore.pushRecent(appState, resolvedPath)
                withContext(Dispatchers.IO) { StateStore.save(stateFile, appState) }
                refreshRecents()
            } else {
                externalUri = uri
                externalWritable = canWrite
                _currentFile.value = DocEntry(name, uri.toString())
            }
            _content.value = text
            _dirty.value = false
            _wordCount.value = countWords(text)
        }
    }

    private fun resolveContentUri(uri: Uri, cr: ContentResolver): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.authority == "com.android.externalstorage.documents") {
            return try {
                val docId = DocumentsContract.getDocumentId(uri)
                val colon = docId.indexOf(':')
                if (colon < 0) return null
                val storageType = docId.substring(0, colon)
                val path = docId.substring(colon + 1)
                if (storageType.equals("primary", ignoreCase = true))
                    "${Environment.getExternalStorageDirectory().absolutePath}/$path"
                else "/storage/$storageType/$path"
            } catch (_: Exception) { null }
        }
        try {
            val decoded = Uri.decode(uri.toString())
            val fileIdx = decoded.indexOf("file:///")
            if (fileIdx >= 0) {
                val path = decoded.substring(fileIdx + 7)
                val f = File(path)
                if (f.exists()) return f.absolutePath
            }
        } catch (_: Exception) {}
        return try {
            cr.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) { null }
    }

    fun saveFile() {
        val entry = _currentFile.value ?: return
        val uri = externalUri
        if (uri != null && externalWritable) {
            val cr = getApplication<Application>().contentResolver
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    cr.openOutputStream(uri, "wt")?.use {
                        it.writer().use { w -> w.write(_content.value) }
                    }
                }
                _dirty.value = false
            }
        } else if (uri != null) {
            _snackbarMessage.value = "File is read-only"
        } else {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { File(entry.path).writeText(_content.value) }
                _dirty.value = false
                appState = StateStore.updateDaily(appState, entry.path, _wordCount.value, today())
                withContext(Dispatchers.IO) { StateStore.save(stateFile, appState) }
                if (entry.name == "writhdeck.ini") reloadConfig()
            }
            refreshStatus()
        }
    }

    private suspend fun reloadConfig() {
        val iniFile = File(docsDir, "writhdeck.ini")
        val text = withContext(Dispatchers.IO) {
            if (iniFile.exists()) iniFile.readText() else return@withContext null
        } ?: return
        config = IniParser.parse(text)
        applyConfig()
        refreshStatus()
    }

    private fun refreshStatus() {
        val name = _currentFile.value?.name ?: ""
        val left = if (name.isEmpty()) "" else if (_dirty.value) "$name *" else name

        val wc = _wordCount.value
        val goal = config.wordGoal
        val center = if (goal > 0) "$wc / $goal" else "$wc w"

        val showTimer = _timerLastTick.value != 0L || _timerActive.value
        val right = if (showTimer) buildTimerDisplay() else ""

        _statusBar.value = StatusBar(left, center, right)
    }

    private fun buildTimerDisplay(): String {
        val secs = _timerRemaining.value
        val m = secs / 60
        val s = secs % 60
        val display = "$m'${s.toString().padStart(2, '0')}\""
        return if (_timerActive.value) "[$display]" else " $display"
    }

    // --- Timer ---

    fun timerStart() {
        timerJob?.cancel()
        _timerRemaining.value = if (_timerType.value == "stopwatch") 0 else config.timerDurationSecs()
        _timerActive.value = true
        _timerLastTick.value = System.currentTimeMillis()
        startTick()
        refreshStatus()
    }

    fun timerPause() {
        timerJob?.cancel()
        timerJob = null
        _timerActive.value = false
        refreshStatus()
    }

    fun timerResume() {
        if (_timerLastTick.value == 0L) { timerStart(); return }
        _timerActive.value = true
        _timerLastTick.value = System.currentTimeMillis()
        startTick()
        refreshStatus()
    }

    fun timerReset() {
        timerJob?.cancel()
        timerJob = null
        _timerActive.value = false
        _timerLastTick.value = 0L
        _timerRemaining.value = if (_timerType.value == "stopwatch") 0 else config.timerDurationSecs()
        refreshStatus()
    }

    private fun startTick() {
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                if (_timerType.value == "stopwatch") {
                    _timerRemaining.value++
                } else {
                    val next = _timerRemaining.value - 1
                    if (next <= 0) {
                        _timerRemaining.value = 0
                        _timerActive.value = false
                        _timerLastTick.value = System.currentTimeMillis()
                        refreshStatus()
                        break
                    }
                    _timerRemaining.value = next
                }
                _timerLastTick.value = System.currentTimeMillis()
                refreshStatus()
            }
        }
    }

    // --- Stats ---

    suspend fun getDailyStats(): List<StatEntry> {
        val entry = _currentFile.value ?: return emptyList()
        appState = StateStore.updateDaily(appState, entry.path, _wordCount.value, today())
        withContext(Dispatchers.IO) { StateStore.save(stateFile, appState) }
        return (appState.daily[entry.path] ?: return emptyList())
            .entries.sortedByDescending { it.key }
            .map { (date, cnt) -> StatEntry(date, cnt) }
    }

    // --- Word occurrences ---

    suspend fun getWordOccurrences(): List<Pair<String, Int>> =
        withContext(Dispatchers.Default) {
            val text = _content.value
            val counts = mutableMapOf<String, Int>()
            for (m in Regex("[\\p{L}]+").findAll(text)) {
                val word = m.value.lowercase()
                if (word.length >= 3) counts[word] = (counts[word] ?: 0) + 1
            }
            counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
        }

    private fun countWords(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun isSystemDarkMode(): Boolean {
        val flags = getApplication<Application>().resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
        return flags == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCleared() { timerJob?.cancel() }

    private fun checkStoragePermission(): Boolean {
        val app = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                app, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
