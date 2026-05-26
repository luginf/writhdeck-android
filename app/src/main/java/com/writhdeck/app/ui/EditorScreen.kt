package com.writhdeck.app.ui

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent as AKeyEvent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.writhdeck.app.StatEntry
import com.writhdeck.app.WrithdeckViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Marker span classes — used to find and remove old spans without touching unrelated ones.
private class SyntaxHeadingSpan(color: Int) : ForegroundColorSpan(color)
private class SyntaxCommentSpan(color: Int) : ForegroundColorSpan(color)
private class SearchBgSpan(color: Int) : BackgroundColorSpan(color)
private class SearchCurrentBgSpan(color: Int) : BackgroundColorSpan(color)
private class SearchCurrentFgSpan(color: Int) : ForegroundColorSpan(color)

fun parseHexColorInt(hex: String): Int {
    val h = hex.trimStart('#')
    if (h.length != 6) return 0
    return try {
        val r = h.substring(0, 2).toInt(16)
        val g = h.substring(2, 4).toInt(16)
        val b = h.substring(4, 6).toInt(16)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    } catch (_: Exception) { 0 }
}

fun parseHexColor(hex: String): Color {
    val h = hex.trimStart('#')
    if (h.length != 6) return Color.Unspecified
    return try {
        Color(
            red   = h.substring(0, 2).toInt(16) / 255f,
            green = h.substring(2, 4).toInt(16) / 255f,
            blue  = h.substring(4, 6).toInt(16) / 255f
        )
    } catch (_: Exception) { Color.Unspecified }
}

data class SyntaxSpanData(val start: Int, val end: Int, val isHeading: Boolean, val color: Int)

fun computeSyntaxSpans(
    text: String, headingMarker: String, markdownHeadings: Boolean,
    hdColorInt: Int, cmtColorInt: Int
): List<SyntaxSpanData> {
    if (hdColorInt == 0 && cmtColorInt == 0) return emptyList()
    val spans = mutableListOf<SyntaxSpanData>()
    val mLen = headingMarker.length
    val mdRe = if (markdownHeadings) Regex("^#{1,6}\\s") else null
    var lineStart = 0
    while (lineStart <= text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        var ts = lineStart; while (ts < lineEnd && text[ts] == ' ') ts++
        val end = lineEnd.coerceAtMost(text.length)
        if (end > lineStart) {
            val isComment = ts < lineEnd && (
                text[ts] == '%' || text[ts] == '>' ||
                (text[ts] == '/' && ts + 1 < lineEnd && text[ts + 1] == '/')
            )
            val isHeading = !isComment && mLen > 0 && lineEnd - lineStart > mLen &&
                text.startsWith(headingMarker, ts) && run {
                    var te = lineEnd - 1
                    while (te > ts && text[te] == ' ') te--
                    te >= ts + mLen && text.startsWith(headingMarker, te - mLen + 1)
                } || (!isComment && mdRe != null && ts < lineEnd &&
                    mdRe.containsMatchIn(text.substring(lineStart, lineEnd)))
            when {
                isComment && cmtColorInt != 0 -> spans.add(SyntaxSpanData(lineStart, end, false, cmtColorInt))
                isHeading && hdColorInt != 0  -> spans.add(SyntaxSpanData(lineStart, end, true,  hdColorInt))
            }
        }
        if (lineEnd >= text.length) break
        lineStart = lineEnd + 1
    }
    return spans
}

fun applySyntaxSpansToEditable(editable: Editable, spans: List<SyntaxSpanData>) {
    editable.getSpans(0, editable.length, SyntaxHeadingSpan::class.java).forEach { editable.removeSpan(it) }
    editable.getSpans(0, editable.length, SyntaxCommentSpan::class.java).forEach { editable.removeSpan(it) }
    for (sd in spans) {
        val s = sd.start.coerceIn(0, editable.length)
        val e = sd.end.coerceIn(0, editable.length)
        if (s < e) {
            val span = if (sd.isHeading) SyntaxHeadingSpan(sd.color) else SyntaxCommentSpan(sd.color)
            editable.setSpan(span, s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

// Returns (newText, selStart, selEnd) or null if marker is empty.
fun applyHeadingResult(text: String, selStart: Int, selEnd: Int, marker: String): Triple<String, Int, Int>? {
    if (marker.isEmpty()) return null
    val minSel = minOf(selStart, selEnd)
    val maxSel = maxOf(selStart, selEnd)
    val lineStart = text.lastIndexOf('\n', (minSel - 1).coerceAtLeast(0))
        .let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', maxSel).let { if (it < 0) text.length else it }
    val mLen = marker.length
    val newLines = text.substring(lineStart, lineEnd).split('\n').joinToString("\n") { line ->
        val t = line.trim()
        if (t.isEmpty()) return@joinToString line
        val isH = t.startsWith(marker) && t.endsWith(marker) && t.length > mLen
        if (isH) {
            var s = 0; while (s + mLen <= t.length && t.substring(s, s + mLen) == marker) s += mLen
            var e = t.length; while (e - mLen >= s && t.substring(e - mLen, e) == marker) e -= mLen
            t.substring(s, e).trim()
        } else { "$marker $t $marker" }
    }
    val newText = text.substring(0, lineStart) + newLines + text.substring(lineEnd)
    return Triple(newText, lineStart, lineStart + newLines.length)
}

fun tkKeyToAndroid(tkName: String): Key? = when (tkName.trim()) {
    "F1"  -> Key.F1;  "F2"  -> Key.F2;  "F3"  -> Key.F3;  "F4"  -> Key.F4
    "F5"  -> Key.F5;  "F6"  -> Key.F6;  "F7"  -> Key.F7;  "F8"  -> Key.F8
    "F9"  -> Key.F9;  "F10" -> Key.F10; "F11" -> Key.F11; "F12" -> Key.F12
    "Escape"    -> Key.Escape
    "Return"    -> Key.Enter
    "Tab"       -> Key.Tab
    "BackSpace" -> Key.Backspace
    "Delete"    -> Key.Delete
    "Home"      -> Key.MoveHome
    "End"       -> Key.MoveEnd
    "Prior"     -> Key.PageUp
    "Next"      -> Key.PageDown
    else -> null
}

fun formatTimer(seconds: Int, active: Boolean): String {
    val m = seconds / 60
    val s = seconds % 60
    val display = "$m'${s.toString().padStart(2, '0')}\""
    return if (active) "[$display]" else " $display"
}

data class TocEntry(val level: Int, val title: String, val charOffset: Int)

fun buildToc(text: String, headingMarker: String, markdownHeadings: Boolean): List<TocEntry> {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: WrithdeckViewModel, onBack: () -> Unit) {
    val content by vm.content.collectAsStateWithLifecycle()
    val wordCount by vm.wordCount.collectAsStateWithLifecycle()
    val currentFile by vm.currentFile.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val headingMarker by vm.headingMarker.collectAsStateWithLifecycle()
    val markdownHeadings by vm.markdownHeadings.collectAsStateWithLifecycle()
    val keyToc by vm.keyToc.collectAsStateWithLifecycle()
    val marginWidth by vm.marginWidth.collectAsStateWithLifecycle()
    val marginHeight by vm.marginHeight.collectAsStateWithLifecycle()
    val fontSize by vm.fontSize.collectAsStateWithLifecycle()
    val tocAndroidKey = remember(keyToc) { tkKeyToAndroid(keyToc) }
    val tocKeyCode = remember(tocAndroidKey) { tocAndroidKey?.keyCode?.toInt() }

    val timerActive by vm.timerActive.collectAsStateWithLifecycle()
    val timerRemaining by vm.timerRemaining.collectAsStateWithLifecycle()
    val timerLastTick by vm.timerLastTick.collectAsStateWithLifecycle()
    val timerAlertPending by vm.timerAlertPending.collectAsStateWithLifecycle()

    val wsActive by vm.wsActive.collectAsStateWithLifecycle()
    val wsDualMode by vm.wsDualMode.collectAsStateWithLifecycle()

    val themeColors by vm.themeColors.collectAsStateWithLifecycle()
    val darkPref by vm.darkModePreference.collectAsStateWithLifecycle()
    val statusBar by vm.statusBar.collectAsStateWithLifecycle()
    val fileWritable by vm.fileWritable.collectAsStateWithLifecycle()
    val snackbarMessage by vm.snackbarMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage
        if (msg != null) { snackbarHostState.showSnackbar(msg); vm.dismissSnackbar() }
    }

    val bgColor = parseHexColor(themeColors.bg)
    val fgColor = parseHexColor(themeColors.fg)
    val hdColor = parseHexColor(themeColors.headingColor)
    val cmtColor = parseHexColor(themeColors.commentColor)
    val colorScheme = MaterialTheme.colorScheme
    val matchBgColor = colorScheme.tertiaryContainer
    val currentMatchBgColor = colorScheme.tertiary
    val currentMatchFgColor = colorScheme.onTertiary
    val scope = rememberCoroutineScope()

    val hasTclColors = bgColor != Color.Unspecified && fgColor != Color.Unspecified
    val editorBg = if (hasTclColors) bgColor else colorScheme.background
    val editorFg = if (hasTclColors) fgColor else colorScheme.onSurface
    val editorFgInt = editorFg.toArgb()
    val hdColorInt = if (hdColor != Color.Unspecified) hdColor.toArgb() else 0
    val cmtColorInt = if (cmtColor != Color.Unspecified) cmtColor.toArgb() else 0
    val matchBgInt = matchBgColor.toArgb()
    val currentMatchBgInt = currentMatchBgColor.toArgb()
    val currentMatchFgInt = currentMatchFgColor.toArgb()

    val density = LocalDensity.current
    val marginWidthPx  = remember(marginWidth,  density) { with(density) { marginWidth.dp.roundToPx() } }
    val marginHeightPx = remember(marginHeight, density) { with(density) { marginHeight.dp.roundToPx() } }

    // Native EditText ref + state refs shared with factory closure
    val editorRef = remember { mutableStateOf<android.widget.EditText?>(null) }
    val ignoreTextChange = remember { booleanArrayOf(false) }
    val keyHandlerRef   = remember { arrayOf<(Int, AKeyEvent) -> Boolean>({ _, _ -> false }) }
    @Suppress("UNCHECKED_CAST")
    val originalKeyListener = remember { arrayOfNulls<android.text.method.KeyListener>(1) }

    var distractionFree by rememberSaveable { mutableStateOf(false) }
    var showReadOnlyAlert by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(currentFile?.path) {
        if (!fileWritable && currentFile != null) showReadOnlyAlert = true
    }

    fun doBack() {
        vm.saveCursor(editorRef.value?.selectionStart ?: 0)
        onBack()
    }

    DisposableEffect(Unit) { onDispose { vm.saveCursor(editorRef.value?.selectionStart ?: 0) } }

    if (distractionFree) BackHandler { distractionFree = false }
    if (dirty && !fileWritable && !distractionFree) BackHandler { showDiscardConfirm = true }

    val context = LocalContext.current
    DisposableEffect(distractionFree) {
        var ctx = context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        val activity = ctx as? Activity
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (controller != null) {
            if (distractionFree) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    var showToc by remember { mutableStateOf(false) }
    var toc by remember { mutableStateOf<List<TocEntry>>(emptyList()) }
    LaunchedEffect(content, headingMarker, markdownHeadings) {
        toc = withContext(Dispatchers.Default) { buildToc(content, headingMarker, markdownHeadings) }
    }

    var showFind by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findMatchIndex by remember { mutableStateOf(0) }
    val findFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showFind) { if (showFind) findFocusRequester.requestFocus() }

    var showCmdMode by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showWords by remember { mutableStateOf(false) }
    var statsData by remember { mutableStateOf<List<StatEntry>>(emptyList()) }
    var wordsData by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }

    val searchMatches = remember(findQuery, content) {
        if (findQuery.length < 2) emptyList()
        else {
            val lower = content.lowercase(); val query = findQuery.lowercase()
            val matches = mutableListOf<IntRange>(); var idx = 0
            while (idx < lower.length) {
                val found = lower.indexOf(query, idx); if (found < 0) break
                matches.add(found until found + query.length); idx = found + 1
            }
            matches.toList()
        }
    }

    // Syntax highlighting — computed async with 300ms debounce to avoid blocking on large files.
    LaunchedEffect(content, headingMarker, markdownHeadings, hdColorInt, cmtColorInt) {
        val snap = content
        delay(300)
        if (content != snap) return@LaunchedEffect
        val spans = withContext(Dispatchers.Default) {
            computeSyntaxSpans(snap, headingMarker, markdownHeadings, hdColorInt, cmtColorInt)
        }
        editorRef.value?.editableText?.let { applySyntaxSpansToEditable(it, spans) }
    }

    // Search highlighting + cursor navigation
    LaunchedEffect(searchMatches, findMatchIndex) {
        val editText = editorRef.value ?: return@LaunchedEffect
        val editable = editText.editableText ?: return@LaunchedEffect
        editable.getSpans(0, editable.length, SearchBgSpan::class.java).forEach { editable.removeSpan(it) }
        editable.getSpans(0, editable.length, SearchCurrentBgSpan::class.java).forEach { editable.removeSpan(it) }
        editable.getSpans(0, editable.length, SearchCurrentFgSpan::class.java).forEach { editable.removeSpan(it) }
        searchMatches.forEachIndexed { idx, range ->
            val s = range.first.coerceIn(0, editable.length)
            val e = (range.last + 1).coerceIn(0, editable.length)
            if (s < e) {
                val isCurrent = idx == findMatchIndex.coerceIn(0, searchMatches.lastIndex)
                if (isCurrent) {
                    editable.setSpan(SearchCurrentBgSpan(currentMatchBgInt), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    editable.setSpan(SearchCurrentFgSpan(currentMatchFgInt), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    editable.setSpan(SearchBgSpan(matchBgInt), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        if (searchMatches.isNotEmpty()) {
            val range = searchMatches[findMatchIndex.coerceIn(0, searchMatches.lastIndex)]
            editText.setSelection(range.first, (range.last + 1).coerceAtMost(editable.length))
        }
    }

    val titleText = buildString {
        if (wsDualMode) append("[$wsActive] ")
        append(currentFile?.name ?: "Editor")
        if (!fileWritable) append(" [read-only]") else if (dirty) append(" *")
    }

    Scaffold(
        topBar = {
            if (!distractionFree) TopAppBar(
                title = { Text(titleText, fontFamily = FontFamily.Monospace, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            dirty && !fileWritable -> showDiscardConfirm = true
                            dirty -> { vm.saveFile(); doBack() }
                            else  -> doBack()
                        }
                    }) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val darkModeIcon = when (darkPref) {
                        "yes" -> Icons.Filled.DarkMode; "no" -> Icons.Filled.LightMode
                        else  -> Icons.Outlined.DarkMode
                    }
                    val nextDarkPref = when (darkPref) { "auto" -> "yes"; "yes" -> "no"; else -> "auto" }
                    IconButton(onClick = { vm.setDarkModePreference(nextDarkPref) }) {
                        Icon(darkModeIcon, contentDescription = "Dark mode: $darkPref")
                    }
                    if (toc.isNotEmpty()) {
                        IconButton(onClick = { showToc = true }) {
                            Icon(Icons.Filled.List, contentDescription = "TOC")
                        }
                    }
                    IconButton(onClick = { vm.saveFile() }, enabled = dirty && fileWritable) {
                        Icon(Icons.Filled.Save, contentDescription = "Save")
                    }
                    IconButton(onClick = { showFind = !showFind }) {
                        Icon(Icons.Filled.Search, contentDescription = "Find")
                    }
                    IconButton(onClick = { showCmdMode = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Commands")
                    }
                    IconButton(onClick = { distractionFree = true }) {
                        Icon(Icons.Filled.Fullscreen, contentDescription = "Distraction-free")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!distractionFree) Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .navigationBarsPadding().clickable { showCmdMode = true },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(statusBar.left, fontFamily = FontFamily.Monospace,
                         style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
                    if (statusBar.center.isNotEmpty()) {
                        Text(statusBar.center, fontFamily = FontFamily.Monospace,
                             style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
                    }
                    Text(statusBar.right, fontFamily = FontFamily.Monospace,
                         style = MaterialTheme.typography.bodySmall,
                         color = if (timerActive) colorScheme.primary else colorScheme.onSurfaceVariant)
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(editorBg).padding(padding)) {

            AndroidView(
                factory = { ctx ->
                    android.widget.EditText(ctx).apply {
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
                        typeface = Typeface.MONOSPACE
                        gravity = Gravity.TOP or Gravity.START
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        isSingleLine = false
                        setHorizontallyScrolling(false)
                        // Capture after inputType (which may replace the default key listener)
                        originalKeyListener[0] = keyListener

                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                            override fun afterTextChanged(s: Editable?) {
                                if (ignoreTextChange[0]) return
                                val text = s?.toString() ?: return
                                vm.updateLiveCursor(selectionStart)
                                vm.updateContent(text)
                            }
                        })

                        setOnKeyListener { _, keyCode, event -> keyHandlerRef[0](keyCode, event) }
                    }.also { editorRef.value = it }
                },
                update = { editText ->
                    // Only reset text when switching to a different file or workspace.
                    // Avoids re-setting on every keystroke-triggered recomposition.
                    val fileKey = "${currentFile?.path}:${wsActive}"
                    if (editText.tag != fileKey) {
                        ignoreTextChange[0] = true
                        editText.setText(content)
                        editText.setSelection(vm.liveCursor.coerceIn(0, content.length))
                        editText.tag = fileKey
                        ignoreTextChange[0] = false
                    }
                    editText.setTextColor(editorFgInt)
                    editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
                    editText.setLineSpacing(0f, 1.5f)
                    editText.setPadding(marginWidthPx, marginHeightPx, marginWidthPx, marginHeightPx)
                    editText.keyListener = if (fileWritable) originalKeyListener[0] else null

                    // Update key handler with current compose state values
                    keyHandlerRef[0] = handler@{ keyCode, event ->
                        if (event.action != AKeyEvent.ACTION_DOWN) return@handler false
                        val ctrl = event.isCtrlPressed
                        when {
                            tocKeyCode != null && keyCode == tocKeyCode && toc.isNotEmpty() -> {
                                showToc = true; true
                            }
                            ctrl && keyCode == AKeyEvent.KEYCODE_S -> { if (fileWritable) vm.saveFile(); true }
                            ctrl && keyCode == AKeyEvent.KEYCODE_F -> { showFind = true; true }
                            ctrl && keyCode == AKeyEvent.KEYCODE_Q -> {
                                vm.saveCursor(editText.selectionStart); onBack(); true
                            }
                            !ctrl && keyCode == AKeyEvent.KEYCODE_ESCAPE && showFind -> {
                                showFind = false; findQuery = ""; true
                            }
                            !ctrl && keyCode == AKeyEvent.KEYCODE_ESCAPE -> { showCmdMode = true; true }
                            else -> false
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (distractionFree) {
                IconButton(
                    onClick = { distractionFree = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                ) {
                    Icon(Icons.Filled.FullscreenExit, contentDescription = "Exit distraction-free",
                         tint = editorFg.copy(alpha = 0.35f))
                }
            }

            if (showFind) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                    tonalElevation = 8.dp,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = findQuery,
                            onValueChange = { findQuery = it; findMatchIndex = 0 },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize.sp,
                                color = colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(colorScheme.primary),
                            singleLine = true,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                .focusRequester(findFocusRequester)
                                .onKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                    when (event.key) {
                                        Key.Escape -> { showFind = false; findQuery = ""; true }
                                        Key.Enter  -> {
                                            if (searchMatches.isNotEmpty())
                                                findMatchIndex = (findMatchIndex + 1) % searchMatches.size
                                            true
                                        }
                                        else -> false
                                    }
                                }
                        )
                        Text(
                            text = if (searchMatches.isEmpty()) "0/0"
                                   else "${findMatchIndex + 1}/${searchMatches.size}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        IconButton(onClick = {
                            if (searchMatches.isNotEmpty())
                                findMatchIndex = (findMatchIndex - 1 + searchMatches.size) % searchMatches.size
                        }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match") }
                        IconButton(onClick = {
                            if (searchMatches.isNotEmpty())
                                findMatchIndex = (findMatchIndex + 1) % searchMatches.size
                        }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match") }
                        IconButton(onClick = { showFind = false; findQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                    }
                }
            }
        }
    }

    // TOC sheet
    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            Text("Table of contents", style = MaterialTheme.typography.titleMedium,
                 modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(toc) { _, entry ->
                    val indent = (entry.level - 1) * 16
                    Text(
                        text = entry.title,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (entry.level == 1) FontWeight.Bold else FontWeight.Normal,
                        fontSize = when (entry.level) { 1 -> 16.sp; 2 -> 15.sp; else -> 14.sp },
                        color = if (entry.level == 1) colorScheme.primary else colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable {
                            editorRef.value?.setSelection(entry.charOffset.coerceIn(0, content.length))
                            showToc = false
                        }.padding(start = (20 + indent).dp, end = 20.dp, top = 10.dp, bottom = 10.dp)
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }

    // Command mode sheet
    if (showCmdMode) {
        ModalBottomSheet(onDismissRequest = { showCmdMode = false }) {
            Text("Commands", style = MaterialTheme.typography.titleMedium,
                 modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp))

            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = formatTimer(timerRemaining, timerActive).trim(),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleLarge,
                            color = if (timerActive) colorScheme.primary else colorScheme.onSurface
                        )
                        Row {
                            if (!timerActive && timerLastTick == 0L) {
                                Button(onClick = { vm.timerStart() }) { Text("Start") }
                            } else if (timerActive) {
                                Button(onClick = { vm.timerPause() }) { Text("Pause") }
                            } else {
                                Button(onClick = { vm.timerResume() }) { Text("Resume") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { vm.timerReset() }) { Text("Reset") }
                            }
                            if (timerActive) {
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { vm.timerReset() }) { Text("Reset") }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Workspace $wsActive", style = MaterialTheme.typography.bodyMedium,
                         fontFamily = FontFamily.Monospace)
                    OutlinedButton(onClick = {
                        val offset = editorRef.value?.selectionStart ?: 0
                        showCmdMode = false
                        vm.toggleWorkspace(offset)
                    }) { Text("Switch to WS${if (wsActive == 1) 2 else 1}") }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        showCmdMode = false
                        scope.launch { statsData = vm.getDailyStats(); showStats = true }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Stats") }
                OutlinedButton(
                    onClick = {
                        showCmdMode = false
                        scope.launch { wordsData = vm.getWordOccurrences(); showWords = true }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Words") }
            }

            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { showCmdMode = false; showToc = true },
                    enabled = toc.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("TOC") }
                OutlinedButton(
                    onClick = {
                        val editText = editorRef.value
                        if (editText != null && headingMarker.isNotEmpty()) {
                            val result = applyHeadingResult(
                                editText.text.toString(),
                                editText.selectionStart,
                                editText.selectionEnd,
                                headingMarker
                            )
                            if (result != null) {
                                val (newText, newStart, newEnd) = result
                                ignoreTextChange[0] = true
                                editText.setText(newText)
                                editText.setSelection(newStart, newEnd)
                                ignoreTextChange[0] = false
                                vm.updateContent(newText)
                            }
                        }
                        showCmdMode = false
                    },
                    enabled = headingMarker.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Heading") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showStats) {
        AlertDialog(
            onDismissRequest = { showStats = false },
            title = { Text("Writing stats", fontFamily = FontFamily.Monospace) },
            text = {
                if (statsData.isEmpty()) {
                    Text("No stats yet.", fontFamily = FontFamily.Monospace,
                         color = colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn {
                        itemsIndexed(statsData) { _, entry ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(entry.date, fontFamily = FontFamily.Monospace,
                                     style = MaterialTheme.typography.bodyMedium)
                                Text("${entry.words} w", fontFamily = FontFamily.Monospace,
                                     style = MaterialTheme.typography.bodyMedium,
                                     color = colorScheme.primary)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStats = false }) { Text("Close") } }
        )
    }

    if (timerAlertPending) {
        AlertDialog(
            onDismissRequest = { vm.dismissTimerAlert() },
            title = { Text("Timer finished", fontFamily = FontFamily.Monospace) },
            text = { Text("Your writing session is complete.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { Button(onClick = { vm.dismissTimerAlert() }) { Text("OK") } }
        )
    }

    if (showReadOnlyAlert) {
        AlertDialog(
            onDismissRequest = { showReadOnlyAlert = false },
            title = { Text("Read-only file", fontFamily = FontFamily.Monospace) },
            text = {
                Text("This file cannot be saved from WrithDeck.\n\n" +
                     "You can view and copy the content, but changes will not be persisted.",
                     style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = { TextButton(onClick = { showReadOnlyAlert = false }) { Text("OK") } }
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard changes?") },
            text = { Text("This file is read-only. Changes cannot be saved and will be lost.") },
            confirmButton = {
                TextButton(
                    onClick = { showDiscardConfirm = false; doBack() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { showDiscardConfirm = false }) { Text("Stay") } }
        )
    }

    if (showWords) {
        AlertDialog(
            onDismissRequest = { showWords = false },
            title = { Text("Word frequency", fontFamily = FontFamily.Monospace) },
            text = {
                if (wordsData.isEmpty()) {
                    Text("No words found.", fontFamily = FontFamily.Monospace,
                         color = colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        itemsIndexed(wordsData) { idx, (word, count) ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${idx + 1}. $word", fontFamily = FontFamily.Monospace,
                                     style = MaterialTheme.typography.bodyMedium)
                                Text("$count", fontFamily = FontFamily.Monospace,
                                     style = MaterialTheme.typography.bodyMedium, color = colorScheme.primary)
                            }
                            if (idx < wordsData.lastIndex) HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showWords = false }) { Text("Close") } }
        )
    }
}
