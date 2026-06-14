package com.writhdeck.app

import android.Manifest
import android.app.Application
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.RingtoneManager
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
data class TocEntry(val level: Int, val title: String, val charOffset: Int)

data class WsSnapshot(
    val file: DocEntry?,
    val content: String,
    val dirty: Boolean,
    val wordCount: Int,
    val writable: Boolean,
    val extUri: Uri?,
    val extWritable: Boolean,
    val cursorOffset: Int
) {
    companion object { fun empty() = WsSnapshot(null, "", false, 0, true, null, false, 0) }
}

data class SettingsData(
    val scheme: String = "default",
    val fontSize: Int = 16,
    val fontFamily: String = "monospace",
    val fontBold: Boolean = false,
    val blockCursor: Boolean = false,
    val marginWidth: Int = 16,
    val marginHeight: Int = 16,
    val wordGoal: Int = 0,
    val headingMarker: String = "=",
    val markdownHeadings: Boolean = true,
    val commentMarker: String = "%",
    val boldMarker: String = "**",
    val italicMarker: String = "//",
    val underlineMarker: String = "__",
    val strikethroughMarker: String = "--",
    val autosaveEnabled: Boolean = true,
    val autosaveInterval: Int = 1,
    val timerType: String = "countdown",
    val timerDuration: Int = 25,
    val timerSound: Boolean = false,
    val timerAlert: Boolean = false,
    val chronoShow: Boolean = false,
    val statusLeft: String = "ws filename dirty",
    val statusCenter: String = "words",
    val statusRight: String = "timer",
    val hemingwayMode: Boolean = false,
    val lineSpacing: Float = 1.5f,
    val browserFilter: String = "*.txt *.t2t *.md *.ini",
    val browserShowAll: Boolean = false,
    val docsCustomDir: String = "",
    val spellCheckEnabled: Boolean = true
)

class WrithdeckViewModel(app: Application) : AndroidViewModel(app) {

    private val externalDocsDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "writhdeck"
    )
    private val internalDocsDir = File(app.filesDir, "documents")

    private val _storagePermissionGranted = MutableStateFlow(checkStoragePermission())
    val storagePermissionGranted = _storagePermissionGranted.asStateFlow()

    // Fixed location for writhdeck.ini, .writhdeck.json and autosave files —
    // independent of docsCustomDir to avoid a bootstrapping problem (the INI
    // can't be loaded from a directory whose path is itself stored in that INI).
    private val configDir: File get() =
        if (_storagePermissionGranted.value) externalDocsDir else internalDocsDir

    // Configurable documents folder (Settings > Misc > Documents folder). Falls back to
    // configDir when unset or when storage permission isn't granted. Mirrors the Tcl
    // desktop's DOCS_DIR vs DOCS_DIR_DEFAULT split.
    private val docsDir: File get() {
        val custom = config.docsCustomDir.trim()
        return if (custom.isNotEmpty() && _storagePermissionGranted.value) File(custom) else configDir
    }

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

    // One-shot signal: an external file (Intent VIEW/EDIT) was just loaded and
    // the UI should navigate to the editor. Consumed by AppNavigation so that
    // a later recomposition (e.g. on rotation, with currentFile still set from
    // a since-closed document) doesn't re-trigger navigation to the editor.
    private val _pendingExternalOpen = MutableStateFlow(false)
    val pendingExternalOpen = _pendingExternalOpen.asStateFlow()
    fun consumeExternalOpen() { _pendingExternalOpen.value = false }

    private val _dirty = MutableStateFlow(false)
    val dirty = _dirty.asStateFlow()

    private val _headingMarker = MutableStateFlow("=")
    val headingMarker = _headingMarker.asStateFlow()
    private val _markdownHeadings = MutableStateFlow(true)
    val markdownHeadings = _markdownHeadings.asStateFlow()
    private val _keySave = MutableStateFlow("Control-s")
    val keySave = _keySave.asStateFlow()
    private val _keyFind = MutableStateFlow("Control-f")
    val keyFind = _keyFind.asStateFlow()
    private val _keyReplace = MutableStateFlow("Control-h")
    val keyReplace = _keyReplace.asStateFlow()
    private val _keyGoto = MutableStateFlow("Control-g")
    val keyGoto = _keyGoto.asStateFlow()
    private val _keyClose = MutableStateFlow("Control-q")
    val keyClose = _keyClose.asStateFlow()
    private val _keyToc = MutableStateFlow("F11")
    val keyToc = _keyToc.asStateFlow()
    private val _keyTypewriter = MutableStateFlow("Control-t")
    val keyTypewriter = _keyTypewriter.asStateFlow()
    private val _keyLineNumbers = MutableStateFlow("Control-l")
    val keyLineNumbers = _keyLineNumbers.asStateFlow()
    private val _keyCmdMode = MutableStateFlow("Escape")
    val keyCmdMode = _keyCmdMode.asStateFlow()
    private val _commentMarker = MutableStateFlow("%")
    val commentMarker = _commentMarker.asStateFlow()
    private val _boldMarker = MutableStateFlow("**")
    val boldMarker = _boldMarker.asStateFlow()
    private val _italicMarker = MutableStateFlow("//")
    val italicMarker = _italicMarker.asStateFlow()
    private val _underlineMarker = MutableStateFlow("__")
    val underlineMarker = _underlineMarker.asStateFlow()
    private val _strikethroughMarker = MutableStateFlow("--")
    val strikethroughMarker = _strikethroughMarker.asStateFlow()
    private val _marginWidth = MutableStateFlow(16)
    val marginWidth = _marginWidth.asStateFlow()
    private val _marginHeight = MutableStateFlow(16)
    val marginHeight = _marginHeight.asStateFlow()
    private val _fontSize = MutableStateFlow(16)
    val fontSize = _fontSize.asStateFlow()
    private val _fontFamily = MutableStateFlow("monospace")
    val fontFamily = _fontFamily.asStateFlow()
    private val _fontBold = MutableStateFlow(false)
    val fontBold = _fontBold.asStateFlow()

    private val _blockCursor = MutableStateFlow(false)
    val blockCursor = _blockCursor.asStateFlow()

    private val _spellCheckEnabled = MutableStateFlow(true)
    val spellCheckEnabled = _spellCheckEnabled.asStateFlow()

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
    private val _timerAlertPending = MutableStateFlow(false)
    val timerAlertPending = _timerAlertPending.asStateFlow()

    private val _fileWritable = MutableStateFlow(true)
    val fileWritable = _fileWritable.asStateFlow()

    private val _customSchemes = MutableStateFlow<Map<String, SchemeColors>>(emptyMap())
    val customSchemes = _customSchemes.asStateFlow()

    private val _activeScheme = MutableStateFlow("default")
    val activeScheme = _activeScheme.asStateFlow()

    private val _activeProfile = MutableStateFlow("default")
    val activeProfile = _activeProfile.asStateFlow()

    private val _profileNames = MutableStateFlow(listOf("default", "novel"))
    val profileNames = _profileNames.asStateFlow()

    // Cursor restore: set before currentFile changes so remember(path, wsActive) picks it up.
    private val _initialCursorOffset = MutableStateFlow(0)
    val initialCursorOffset = _initialCursorOffset.asStateFlow()

    // Live cursor — plain var updated on every selection change; survives rotation without StateFlow overhead.
    var liveCursor: Int = 0
        private set
    fun updateLiveCursor(offset: Int) { liveCursor = offset }

    // Workspace
    private val _wsActive = MutableStateFlow(1)
    val wsActive = _wsActive.asStateFlow()
    private val _wsDualMode = MutableStateFlow(false)
    val wsDualMode = _wsDualMode.asStateFlow()

    private var ws1Snap = WsSnapshot.empty()
    private var ws2Snap = WsSnapshot.empty()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage = _snackbarMessage.asStateFlow()

    private val _hemingwayMode = MutableStateFlow(false)
    val hemingwayMode = _hemingwayMode.asStateFlow()

    private val _lineNumbersEnabled = MutableStateFlow(false)
    val lineNumbersEnabled = _lineNumbersEnabled.asStateFlow()

    private val _lineSpacing = MutableStateFlow(1.5f)
    val lineSpacing = _lineSpacing.asStateFlow()

    private val _toc = MutableStateFlow<List<TocEntry>>(emptyList())
    val toc = _toc.asStateFlow()

    private var timerJob: Job? = null
    private var autosaveJob: Job? = null
    private var wordCountJob: Job? = null
    private var clockJob: Job? = null
    private var tocJob: Job? = null
    private var externalUri: Uri? = null
    private var externalWritable = false

    init {
        viewModelScope.launch { initApp() }
    }

    private suspend fun initApp() {
        val dd = configDir.also { it.mkdirs() }
        stateFile = File(dd, ".writhdeck.json")

        config = withContext(Dispatchers.IO) {
            val iniFile = File(dd, "writhdeck.ini")
            if (iniFile.exists()) IniParser.parse(iniFile.readText())
            else {
                val defaultConfig = AppConfig()
                iniFile.writeText(IniParser.write(defaultConfig))
                defaultConfig
            }
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
        _commentMarker.value = config.commentMarker
        _boldMarker.value = config.boldMarker
        _italicMarker.value = config.italicMarker
        _underlineMarker.value = config.underlineMarker
        _strikethroughMarker.value = config.strikethroughMarker
        _keySave.value = config.keySave
        _keyFind.value = config.keyFind
        _keyReplace.value = config.keyReplace
        _keyGoto.value = config.keyGoto
        _keyClose.value = config.keyClose
        _keyToc.value = config.keyToc
        _keyTypewriter.value = config.keyTypewriter
        _keyLineNumbers.value = config.keyLineNumbers
        _keyCmdMode.value = config.keyCmdMode
        _timerType.value = config.timerType
        _marginWidth.value = config.marginWidth
        _marginHeight.value = config.marginHeight
        _fontSize.value = config.fontSize
        _fontFamily.value = config.fontFamily
        _fontBold.value = config.fontBold
        _blockCursor.value = config.blockCursor
        _spellCheckEnabled.value = config.spellCheckEnabled
        _darkModePreference.value = config.androidDarkMode
        _customSchemes.value = config.customSchemes
        _activeScheme.value = config.scheme
        _activeProfile.value = config.activeProfile
        _profileNames.value = config.profileNames
        _themeColors.value = config.themeColors(resolveUseDark())
        _hemingwayMode.value = config.hemingwayMode
        _lineNumbersEnabled.value = config.lineNumbers
        _lineSpacing.value = config.lineSpacing
        restartAutosave()
        startClockJob()
        scheduleRebuildToc()
    }

    private fun startClockJob() {
        clockJob?.cancel()
        val usesClock = listOf(config.statusLeft, config.statusCenter, config.statusRight)
            .any { it.contains("clock") }
        if (!usesClock) { clockJob = null; return }
        clockJob = viewModelScope.launch {
            while (true) {
                delay(10_000L)
                refreshStatus()
            }
        }
    }

    private fun scheduleRebuildToc() {
        tocJob?.cancel()
        tocJob = viewModelScope.launch {
            delay(300)
            val text = _content.value
            val marker = config.headingMarker
            val md = config.markdownHeadings
            _toc.value = withContext(Dispatchers.Default) { buildTocInternal(text, marker, md) }
        }
    }

    private fun buildTocInternal(text: String, headingMarker: String, markdownHeadings: Boolean): List<TocEntry> {
        val entries = mutableListOf<TocEntry>()
        val mLen = headingMarker.length
        val mdRe = if (markdownHeadings) Regex("^(#{1,6})\\s+(.+)$") else null
        var offset = 0
        for (line in text.lines()) {
            val trimmed = line.trim()
            var added = false
            if (mLen > 0 && trimmed.startsWith(headingMarker) && trimmed.endsWith(headingMarker)) {
                var level = 0; var pos = 0
                while (pos + mLen <= trimmed.length &&
                       trimmed.substring(pos, pos + mLen) == headingMarker) { level++; pos += mLen }
                var end = trimmed.length
                while (end >= mLen && trimmed.substring(end - mLen, end) == headingMarker) { end -= mLen }
                val title = if (end > pos) trimmed.substring(pos, end).trim() else ""
                if (level > 0 && title.isNotEmpty()) { entries.add(TocEntry(level, title, offset)); added = true }
            }
            if (!added && mdRe != null) {
                val m = mdRe.find(trimmed)
                if (m != null) entries.add(TocEntry(m.groupValues[1].length, m.groupValues[2].trim(), offset))
            }
            offset += line.length + 1
        }
        return entries
    }

    private fun resolveUseDark(): Boolean = when (config.androidDarkMode) {
        "yes" -> true
        "no"  -> false
        else  -> isSystemDarkMode()
    }

    // --- Autosave ---

    private fun restartAutosave() {
        autosaveJob?.cancel()
        autosaveJob = null
        if (!config.autosaveEnabled) return
        val intervalMs = config.autosaveInterval.coerceAtLeast(1).toLong() * 60_000L
        autosaveJob = viewModelScope.launch {
            while (true) {
                delay(intervalMs)
                doAutosave()
            }
        }
    }

    private suspend fun doAutosave() {
        if (!_dirty.value) return
        val content = _content.value
        val name = _currentFile.value?.name ?: return
        val ws = _wsActive.value
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = "$name\n$ts\n\n-------------------------\n$content"
        withContext(Dispatchers.IO) {
            try { File(configDir, "autosave_ws0$ws.txt").writeText(text) } catch (_: Exception) {}
        }
    }

    // --- Storage permission ---

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
            autosaveJob?.cancel(); autosaveJob = null
            initApp()
            _snackbarMessage.value = "Documents stored in Documents/writhdeck/"
        }
    }

    fun dismissSnackbar() { _snackbarMessage.value = null }

    // --- Docs ---

    fun refreshDocs() {
        viewModelScope.launch(Dispatchers.IO) {
            docsDir.mkdirs()
            // If a custom docs folder is set, also list configDir (where writhdeck.ini and
            // .writhdeck.json live) so those files don't disappear from the browser —
            // mirrors the Tcl desktop's DOCS_DIR/DOCS_DIR_DEFAULT dual-listing.
            val dirs = if (docsDir.absolutePath != configDir.absolutePath) listOf(docsDir, configDir) else listOf(docsDir)
            val files = dirs
                .flatMap { dir -> dir.listFiles { f -> f.isFile && config.matchesBrowserFilter(f.name) }?.toList() ?: emptyList() }
                .sortedByDescending { it.lastModified() }
                .map { DocEntry(it.name, it.absolutePath) }
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
            val iniFile = File(configDir, "writhdeck.ini")
            if (iniFile.exists()) {
                iniFile.writeText(IniParser.patchKeys(iniFile.readText(), "android_dark_mode" to pref))
            } else {
                iniFile.writeText(IniParser.write(config))
            }
        }
    }

    fun setLineNumbersEnabled(enabled: Boolean) {
        config = config.copy(lineNumbers = enabled)
        _lineNumbersEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            val iniFile = File(configDir, "writhdeck.ini")
            if (iniFile.exists()) {
                iniFile.writeText(IniParser.patchKeys(iniFile.readText(), "line_numbers" to if (enabled) "yes" else "no"))
            } else {
                iniFile.writeText(IniParser.write(config))
            }
        }
    }

    fun setBlockCursor(enabled: Boolean) {
        config = config.copy(blockCursor = enabled)
        _blockCursor.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            val iniFile = File(configDir, "writhdeck.ini")
            if (iniFile.exists()) {
                iniFile.writeText(IniParser.patchKeys(iniFile.readText(), "block_cursor" to if (enabled) "yes" else "no"))
            } else {
                iniFile.writeText(IniParser.write(config))
            }
        }
    }

    fun setSpellCheckEnabled(enabled: Boolean) {
        config = config.copy(spellCheckEnabled = enabled)
        _spellCheckEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            val iniFile = File(configDir, "writhdeck.ini")
            if (iniFile.exists()) {
                iniFile.writeText(IniParser.patchKeys(iniFile.readText(), "spell_check" to if (enabled) "yes" else "no"))
            } else {
                iniFile.writeText(IniParser.write(config))
            }
        }
    }

    /** Switch the active profile (`active_profile` in `[editor]`/`= editor =`). Unlike the
     *  other setters, this re-derives the whole config from disk afterwards, since profile
     *  sections (`= profile: <name> =`) override scheme/margins/word goal/etc. — the new
     *  values can't be computed from the in-memory config alone. */
    fun switchProfile(name: String) {
        if (name == config.activeProfile) return
        viewModelScope.launch(Dispatchers.IO) {
            val iniFile = File(configDir, "writhdeck.ini")
            val text = if (iniFile.exists()) iniFile.readText() else IniParser.write(config)
            val patched = IniParser.patchKeys(text, "active_profile" to name)
            iniFile.writeText(patched)
            config = IniParser.parse(patched)
            applyConfig()
            refreshDocs()
            refreshStatus()
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
            _fileWritable.value = withContext(Dispatchers.IO) { File(entry.path).canWrite() }
            _content.value = text
            // Restore cursor — set before currentFile so remember(path, wsActive) sees it
            val cursor = appState.cursors[entry.path]
            _initialCursorOffset.value = if (cursor != null)
                linecolToOffset(text, cursor.first, cursor.second) else 0
            liveCursor = _initialCursorOffset.value
            _currentFile.value = entry
            _dirty.value = false
            val wc = countWords(text)
            _wordCount.value = wc
            appState = StateStore.pushRecent(appState, entry.path)
            appState = StateStore.updateDaily(appState, entry.path, wc, today())
            withContext(Dispatchers.IO) { StateStore.save(stateFile, appState) }
            refreshRecents()
            refreshStatus()
            scheduleRebuildToc()
        }
    }

    fun openIniFile() {
        viewModelScope.launch {
            val iniFile = File(configDir, "writhdeck.ini")
            configDir.mkdirs()
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
            _fileWritable.value = withContext(Dispatchers.IO) { iniFile.canWrite() }
            _content.value = text
            _initialCursorOffset.value = 0
            _currentFile.value = DocEntry("writhdeck.ini", iniFile.absolutePath)
            _dirty.value = false
            _wordCount.value = countWords(text)
            refreshStatus()
        }
    }

    fun openScratchpad() {
        viewModelScope.launch {
            val scratchFile = File(docsDir, "scratchpad.txt")
            val text = withContext(Dispatchers.IO) {
                if (!scratchFile.exists()) { scratchFile.createNewFile(); "" }
                else scratchFile.readText()
            }
            externalUri = null
            externalWritable = false
            _fileWritable.value = true
            _content.value = text
            val cursor = appState.cursors[scratchFile.absolutePath]
            _initialCursorOffset.value = if (cursor != null)
                linecolToOffset(text, cursor.first, cursor.second) else 0
            liveCursor = _initialCursorOffset.value
            _currentFile.value = DocEntry("scratchpad.txt", scratchFile.absolutePath)
            _dirty.value = false
            _wordCount.value = countWords(text)
            refreshStatus()
            scheduleRebuildToc()
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
        refreshStatus()
        scheduleRebuildToc()
        wordCountJob?.cancel()
        wordCountJob = viewModelScope.launch {
            delay(1000)
            val wc = withContext(Dispatchers.Default) { countWords(text) }
            if (_content.value == text) {
                _wordCount.value = wc
                val path = _currentFile.value?.path
                if (path != null) appState = StateStore.updateDaily(appState, path, wc, today())
                refreshStatus()
            }
        }
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
            val pathWritable = resolvedPath != null && withContext(Dispatchers.IO) { File(resolvedPath).canWrite() }
            if (pathWritable) {
                externalUri = null
                externalWritable = false
                _fileWritable.value = true
                _currentFile.value = DocEntry(name, resolvedPath!!)
                appState = StateStore.pushRecent(appState, resolvedPath)
                withContext(Dispatchers.IO) { StateStore.save(stateFile, appState) }
                refreshRecents()
            } else {
                externalUri = uri
                externalWritable = canWrite
                _fileWritable.value = canWrite
                _currentFile.value = DocEntry(name, uri.toString())
            }
            _content.value = text
            _initialCursorOffset.value = 0
            _dirty.value = false
            _wordCount.value = countWords(text)
            _pendingExternalOpen.value = true
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
                val ok = withContext(Dispatchers.IO) {
                    runCatching { File(entry.path).writeText(_content.value) }.isSuccess
                }
                if (ok) {
                    _dirty.value = false
                    appState = StateStore.updateDaily(appState, entry.path, _wordCount.value, today())
                    withContext(Dispatchers.IO) { StateStore.save(stateFile, appState) }
                    if (entry.name == "writhdeck.ini") reloadConfig()
                } else {
                    _fileWritable.value = false
                    _snackbarMessage.value = "Cannot save: file is not writable"
                }
                refreshStatus()
            }
        }
    }

    private suspend fun reloadConfig() {
        val iniFile = File(configDir, "writhdeck.ini")
        val text = withContext(Dispatchers.IO) {
            if (iniFile.exists()) iniFile.readText() else return@withContext null
        } ?: return
        config = IniParser.parse(text)
        applyConfig()
        refreshStatus()
    }

    // --- Cursor ---

    fun saveCursor(offset: Int) {
        val path = _currentFile.value?.path ?: return
        val text = _content.value
        val (cy, cx) = textOffsetToLinecol(text, offset)
        appState = StateStore.saveCursor(appState, path, cy, cx)
        viewModelScope.launch(Dispatchers.IO) { StateStore.save(stateFile, appState) }
    }

    private fun textOffsetToLinecol(text: String, offset: Int): Pair<Int, Int> {
        val safe = offset.coerceIn(0, text.length)
        val before = text.substring(0, safe)
        val lines = before.split('\n')
        return lines.size to lines.last().length
    }

    private fun linecolToOffset(text: String, cy: Int, cx: Int): Int {
        if (text.isEmpty()) return 0
        val lines = text.split('\n')
        val lineIdx = (cy - 1).coerceIn(0, lines.size - 1)
        val lineStart = lines.take(lineIdx).sumOf { it.length + 1 }
        return lineStart + cx.coerceIn(0, lines[lineIdx].length)
    }

    // --- Workspace ---

    fun toggleWorkspace(cursorOffset: Int) {
        _wsDualMode.value = true
        val current = WsSnapshot(
            file = _currentFile.value, content = _content.value,
            dirty = _dirty.value, wordCount = _wordCount.value,
            writable = _fileWritable.value, extUri = externalUri,
            extWritable = externalWritable, cursorOffset = cursorOffset
        )
        val next: WsSnapshot
        if (_wsActive.value == 1) {
            ws1Snap = current
            next = ws2Snap
            _wsActive.value = 2
        } else {
            ws2Snap = current
            next = ws1Snap
            _wsActive.value = 1
        }
        // Set initialCursorOffset before currentFile so remember(path, wsActive) picks it up.
        _initialCursorOffset.value = next.cursorOffset
        liveCursor = next.cursorOffset
        _content.value = next.content
        _currentFile.value = next.file
        _dirty.value = next.dirty
        _wordCount.value = next.wordCount
        _fileWritable.value = next.writable
        externalUri = next.extUri
        externalWritable = next.extWritable
        refreshStatus()
    }

    // --- Schemes ---

    fun setActiveScheme(name: String) {
        config = config.copy(scheme = name)
        applyConfig()
        viewModelScope.launch(Dispatchers.IO) {
            val iniFile = File(configDir, "writhdeck.ini")
            if (iniFile.exists()) {
                iniFile.writeText(IniParser.patchProfileKey(iniFile.readText(), config.activeProfile, "scheme", name))
            }
        }
    }

    fun saveCustomScheme(name: String, colors: SchemeColors) {
        val updated = config.customSchemes.toMutableMap().also { it[name] = colors }
        config = config.copy(customSchemes = updated)
        applyConfig()
        viewModelScope.launch(Dispatchers.IO) {
            val iniFile = File(configDir, "writhdeck.ini")
            val text = if (iniFile.exists()) iniFile.readText() else IniParser.write(config)
            iniFile.writeText(IniParser.writeCustomScheme(text, name, colors))
        }
    }

    fun deleteCustomScheme(name: String) {
        val wasActive = config.scheme == name
        val updated = config.customSchemes.toMutableMap().also { it.remove(name) }
        val newScheme = if (wasActive) "default" else config.scheme
        config = config.copy(customSchemes = updated, scheme = newScheme)
        applyConfig()
        viewModelScope.launch(Dispatchers.IO) {
            val iniFile = File(configDir, "writhdeck.ini")
            if (iniFile.exists()) {
                var text = IniParser.removeSchemeSection(iniFile.readText(), name)
                if (wasActive) {
                    text = IniParser.patchProfileKey(text, config.activeProfile, "scheme", "default")
                }
                iniFile.writeText(text)
            }
        }
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
        timerJob?.cancel(); timerJob = null
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
        timerJob?.cancel(); timerJob = null
        _timerActive.value = false
        _timerLastTick.value = 0L
        _timerRemaining.value = if (_timerType.value == "stopwatch") 0 else config.timerDurationSecs()
        refreshStatus()
    }

    fun dismissTimerAlert() { _timerAlertPending.value = false }

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
                        if (config.timerSound) playTimerSound()
                        if (config.timerAlert) _timerAlertPending.value = true
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

    private fun playTimerSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(getApplication(), uri)?.play()
        } catch (_: Exception) {}
    }

    // --- Settings ---

    fun getAllSchemeNames(): List<String> =
        (BUILTIN_SCHEMES.keys + config.customSchemes.keys.filter { it !in BUILTIN_SCHEMES }).toList()

    fun getSettingsData() = SettingsData(
        scheme           = config.scheme,
        fontSize         = config.fontSize,
        fontFamily       = config.fontFamily,
        fontBold         = config.fontBold,
        blockCursor      = config.blockCursor,
        marginWidth      = config.marginWidth,
        marginHeight     = config.marginHeight,
        wordGoal         = config.wordGoal,
        headingMarker    = config.headingMarker,
        markdownHeadings = config.markdownHeadings,
        commentMarker    = config.commentMarker,
        boldMarker       = config.boldMarker,
        italicMarker     = config.italicMarker,
        underlineMarker  = config.underlineMarker,
        strikethroughMarker = config.strikethroughMarker,
        autosaveEnabled  = config.autosaveEnabled,
        autosaveInterval = config.autosaveInterval,
        timerType        = config.timerType,
        timerDuration    = config.timerDuration,
        timerSound       = config.timerSound,
        timerAlert       = config.timerAlert,
        chronoShow       = config.chronoShow,
        statusLeft       = config.statusLeft,
        statusCenter     = config.statusCenter,
        statusRight      = config.statusRight,
        hemingwayMode    = config.hemingwayMode,
        lineSpacing      = config.lineSpacing,
        browserFilter    = config.browserFilter,
        browserShowAll   = config.browserShowAll,
        docsCustomDir    = config.docsCustomDir,
        spellCheckEnabled = config.spellCheckEnabled
    )

    fun applySettings(s: SettingsData) {
        config = config.copy(
            scheme           = s.scheme,
            fontSize         = s.fontSize,
            fontFamily       = s.fontFamily,
            fontBold         = s.fontBold,
            blockCursor      = s.blockCursor,
            marginWidth      = s.marginWidth,
            marginHeight     = s.marginHeight,
            wordGoal         = s.wordGoal,
            headingMarker    = s.headingMarker,
            markdownHeadings = s.markdownHeadings,
            commentMarker    = s.commentMarker,
            boldMarker       = s.boldMarker,
            italicMarker     = s.italicMarker,
            underlineMarker  = s.underlineMarker,
            strikethroughMarker = s.strikethroughMarker,
            autosaveEnabled  = s.autosaveEnabled,
            autosaveInterval = s.autosaveInterval,
            timerType        = s.timerType,
            timerDuration    = s.timerDuration,
            timerSound       = s.timerSound,
            timerAlert       = s.timerAlert,
            chronoShow       = s.chronoShow,
            statusLeft       = s.statusLeft,
            statusCenter     = s.statusCenter,
            statusRight      = s.statusRight,
            hemingwayMode    = s.hemingwayMode,
            lineSpacing      = s.lineSpacing,
            browserFilter    = s.browserFilter,
            browserShowAll   = s.browserShowAll,
            docsCustomDir    = s.docsCustomDir,
            spellCheckEnabled = s.spellCheckEnabled
        )
        applyConfig()
        refreshDocs()
        val activeProfile = config.activeProfile
        viewModelScope.launch(Dispatchers.IO) {
            val iniFile = File(configDir, "writhdeck.ini")
            fun b(v: Boolean) = if (v) "yes" else "no"
            var text = if (iniFile.exists()) iniFile.readText() else IniParser.write(config)
            text = IniParser.patchKeys(text,
                "font_size"         to s.fontSize.toString(),
                "font_family"       to s.fontFamily,
                "font_bold"         to b(s.fontBold),
                "block_cursor"      to b(s.blockCursor),
                "margin_width"      to s.marginWidth.toString(),
                "margin_height"     to s.marginHeight.toString(),
                "word_goal"         to s.wordGoal.toString(),
                "heading_marker"    to s.headingMarker,
                "markdown_headings" to b(s.markdownHeadings),
                "comment_marker"    to s.commentMarker,
                "bold_marker"       to s.boldMarker,
                "italic_marker"     to s.italicMarker,
                "underline_marker"  to s.underlineMarker,
                "strikethrough_marker" to s.strikethroughMarker,
                "autosave_enabled"  to b(s.autosaveEnabled),
                "autosave_interval" to s.autosaveInterval.toString(),
                "timer_type"        to s.timerType,
                "timer_duration"    to s.timerDuration.toString(),
                "timer_sound"       to b(s.timerSound),
                "timer_alert"       to b(s.timerAlert),
                "chrono_show"       to b(s.chronoShow),
                "status_left"       to s.statusLeft,
                "status_center"     to s.statusCenter,
                "status_right"      to s.statusRight,
                "hemingway_mode"    to b(s.hemingwayMode),
                "line_spacing"      to s.lineSpacing.toString(),
                "browser_filter"    to s.browserFilter,
                "browser_show_all"  to b(s.browserShowAll),
                "docs_dir"          to s.docsCustomDir,
                "spell_check"       to b(s.spellCheckEnabled)
            )
            text = IniParser.patchProfileKey(text, activeProfile, "scheme", s.scheme)
            // Ensure all built-in scheme sections are present (add missing ones)
            for ((name, colors) in BUILTIN_SCHEMES) {
                if (!text.contains("= scheme: $name =")) {
                    text = IniParser.writeCustomScheme(text, name, colors)
                }
            }
            iniFile.writeText(text)
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

    // --- Helpers ---

    private fun refreshStatus() {
        _statusBar.value = StatusBar(
            buildStatusSegment(config.statusLeft),
            buildStatusSegment(config.statusCenter),
            buildStatusSegment(config.statusRight)
        )
    }

    // Tokens: ws filename dirty words goal timer clock readtime pages chars — unknown tokens are literal text.
    private fun buildStatusSegment(tokens: String): String =
        tokens.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("") { tok ->
            when (tok) {
                "ws"       -> if (_wsDualMode.value) "[${_wsActive.value}] " else ""
                "filename" -> _currentFile.value?.name ?: ""
                "dirty"    -> if (_dirty.value) " *" else ""
                "words"    -> "${_wordCount.value} w"
                "chars"    -> "${_content.value.length} c"
                "goal"     -> { val wc = _wordCount.value; val g = config.wordGoal
                                if (g > 0) "$wc/$g" else "$wc w" }
                "timer"    -> if (_timerLastTick.value != 0L || _timerActive.value) buildTimerDisplay() else ""
                "clock"    -> SimpleDateFormat("HH:mm", Locale.US).format(Date())
                "readtime" -> {
                    val mins = (_wordCount.value / 200).coerceAtLeast(if (_wordCount.value > 0) 1 else 0)
                    if (mins == 0) "" else if (mins >= 60) "${mins/60}h${(mins%60).toString().padStart(2,'0')}'" else "$mins'"
                }
                "pages"    -> {
                    val p = _wordCount.value / 250
                    if (p > 0) "${p}p" else ""
                }
                else       -> tok
            }
        }

    private fun buildTimerDisplay(): String {
        val secs = _timerRemaining.value
        val m = secs / 60
        val s = secs % 60
        val display = "$m'${s.toString().padStart(2, '0')}\""
        return if (_timerActive.value) "[$display]" else " $display"
    }

    private fun countWords(text: String): Int {
        var count = 0; var inWord = false
        for (c in text) {
            if (!c.isWhitespace()) { if (!inWord) count++; inWord = true } else inWord = false
        }
        return count
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun isSystemDarkMode(): Boolean {
        val flags = getApplication<Application>().resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
        return flags == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCleared() {
        timerJob?.cancel()
        autosaveJob?.cancel()
        wordCountJob?.cancel()
        clockJob?.cancel()
        tocJob?.cancel()
    }

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
