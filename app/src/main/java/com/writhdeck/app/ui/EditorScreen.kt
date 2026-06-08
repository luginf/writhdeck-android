package com.writhdeck.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent as AKeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.OverScroller
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapVert
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
import com.writhdeck.app.TocEntry
import com.writhdeck.app.WrithdeckViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Marker span classes — used to find and remove old spans without touching unrelated ones.
private class SyntaxHeadingSpan(color: Int) : ForegroundColorSpan(color)
private class SyntaxCommentSpan(color: Int) : ForegroundColorSpan(color)
private class SyntaxMarkupSpan(color: Int) : ForegroundColorSpan(color)
private class SearchBgSpan(color: Int) : BackgroundColorSpan(color)
private class SearchCurrentBgSpan(color: Int) : BackgroundColorSpan(color)
private class SearchCurrentFgSpan(color: Int) : ForegroundColorSpan(color)

/** EditText with fling-based momentum scrolling.
 *
 *  A plain multi-line EditText scrolls 1:1 with the drag gesture and stops dead on
 *  release — no inertia, unlike ScrollView/RecyclerView/most text editors and browsers.
 *  Wrapping it in a ScrollView would fix that, but forces full-content-height
 *  measurement, which defeats DynamicLayout's visible-lines-only rendering and makes
 *  large files (500K+ chars) slow again — so fling is implemented here instead by
 *  driving the EditText's own `scrollY` with an OverScroller, exactly like ScrollView
 *  does internally. */
private class FlingEditText(context: Context) : EditText(context) {
    private val scroller = OverScroller(context)
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (hasSelection()) return false
            val layout = layout ?: return false
            val visibleHeight = height - totalPaddingTop - totalPaddingBottom
            val maxScrollY = (layout.height - visibleHeight).coerceAtLeast(0)
            if (maxScrollY <= 0) return false
            scroller.fling(scrollX, scrollY, 0, -velocityY.toInt(), 0, 0, 0, maxScrollY)
            postInvalidateOnAnimation()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !scroller.isFinished) {
            scroller.abortAnimation()
        }
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scrollX, scroller.currY)
            postInvalidateOnAnimation()
        } else {
            super.computeScroll()
        }
    }
}

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

data class SyntaxSpanData(val start: Int, val end: Int, val isHeading: Boolean, val isMarkup: Boolean = false, val color: Int)

fun computeSyntaxSpans(
    text: String, headingMarker: String, markdownHeadings: Boolean,
    commentMarker: String, boldMarker: String, italicMarker: String,
    underlineMarker: String, strikethroughMarker: String,
    hdColorInt: Int, cmtColorInt: Int, markupColorInt: Int = 0
): List<SyntaxSpanData> {
    if (hdColorInt == 0 && cmtColorInt == 0 && markupColorInt == 0) return emptyList()
    val spans = mutableListOf<SyntaxSpanData>()
    val mLen = headingMarker.length
    val mdRe = if (markdownHeadings) Regex("^#{1,6}\\s") else null
    val markupRe = if (markupColorInt != 0) {
        val parts = listOf(boldMarker, italicMarker, underlineMarker, strikethroughMarker)
            .filter { it.isNotEmpty() }
            .map { "${Regex.escape(it)}.+?${Regex.escape(it)}" }
        if (parts.isEmpty()) null else Regex(parts.joinToString("|"))
    } else null
    var lineStart = 0
    while (lineStart <= text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        var ts = lineStart; while (ts < lineEnd && text[ts] == ' ') ts++
        val end = lineEnd.coerceAtMost(text.length)
        if (end > lineStart) {
            val isComment = commentMarker.isNotEmpty() && ts < lineEnd && text.startsWith(commentMarker, ts)
            val isHeading = !isComment && mLen > 0 && lineEnd - lineStart > mLen &&
                text.startsWith(headingMarker, ts) && run {
                    var te = lineEnd - 1
                    while (te > ts && text[te] == ' ') te--
                    te >= ts + mLen && text.startsWith(headingMarker, te - mLen + 1)
                } || (!isComment && mdRe != null && ts < lineEnd &&
                    mdRe.containsMatchIn(text.substring(lineStart, lineEnd)))
            when {
                isComment && cmtColorInt != 0 -> spans.add(SyntaxSpanData(lineStart, end, false, false, cmtColorInt))
                isHeading && hdColorInt != 0  -> spans.add(SyntaxSpanData(lineStart, end, true,  false, hdColorInt))
                !isComment && !isHeading && markupRe != null -> {
                    val lineText = text.substring(lineStart, lineEnd)
                    for (m in markupRe.findAll(lineText)) {
                        val ms = lineStart + m.range.first
                        val me = (lineStart + m.range.last + 1).coerceAtMost(text.length)
                        if (ms < me) spans.add(SyntaxSpanData(ms, me, false, true, markupColorInt))
                    }
                }
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
    editable.getSpans(0, editable.length, SyntaxMarkupSpan::class.java).forEach { editable.removeSpan(it) }
    for (sd in spans) {
        val s = sd.start.coerceIn(0, editable.length)
        val e = sd.end.coerceIn(0, editable.length)
        if (s < e) {
            val span = when {
                sd.isHeading -> SyntaxHeadingSpan(sd.color)
                sd.isMarkup  -> SyntaxMarkupSpan(sd.color)
                else         -> SyntaxCommentSpan(sd.color)
            }
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

/** A parsed Tk key binding (e.g. "Control-s", "F11", "Control-S") — native Android key code
 *  plus the modifier flags that must (and must not) be held, matched against a raw [AKeyEvent]. */
data class TkBinding(val keyCode: Int, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false) {
    fun matches(code: Int, event: AKeyEvent): Boolean =
        code == keyCode && event.isCtrlPressed == ctrl && event.isAltPressed == alt && event.isShiftPressed == shift
}

private val TK_NAMED_KEYS = mapOf(
    "F1" to AKeyEvent.KEYCODE_F1,   "F2" to AKeyEvent.KEYCODE_F2,
    "F3" to AKeyEvent.KEYCODE_F3,   "F4" to AKeyEvent.KEYCODE_F4,
    "F5" to AKeyEvent.KEYCODE_F5,   "F6" to AKeyEvent.KEYCODE_F6,
    "F7" to AKeyEvent.KEYCODE_F7,   "F8" to AKeyEvent.KEYCODE_F8,
    "F9" to AKeyEvent.KEYCODE_F9,   "F10" to AKeyEvent.KEYCODE_F10,
    "F11" to AKeyEvent.KEYCODE_F11, "F12" to AKeyEvent.KEYCODE_F12,
    "Escape"    to AKeyEvent.KEYCODE_ESCAPE,
    "Return"    to AKeyEvent.KEYCODE_ENTER,
    "Tab"       to AKeyEvent.KEYCODE_TAB,
    "BackSpace" to AKeyEvent.KEYCODE_DEL,
    "Delete"    to AKeyEvent.KEYCODE_FORWARD_DEL,
    "Home"      to AKeyEvent.KEYCODE_MOVE_HOME,
    "End"       to AKeyEvent.KEYCODE_MOVE_END,
    "Prior"     to AKeyEvent.KEYCODE_PAGE_UP,
    "Next"      to AKeyEvent.KEYCODE_PAGE_DOWN
)

/** Parses a Tk key-binding string ("Control-s", "Control-S", "F11", "Alt-Return", ...) into a
 *  [TkBinding]. Modifiers are dash-separated prefixes (Control/Shift/Alt, case-insensitive); a
 *  single uppercase letter implies Shift (Tk convention — "Control-S" == Control+Shift+s). */
fun tkKeyToAndroid(tkName: String): TkBinding? {
    val parts = tkName.trim().split("-").filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val keyPart = parts.last()
    var ctrl = false; var shift = false; var alt = false
    for (mod in parts.dropLast(1)) when (mod.lowercase()) {
        "control", "ctrl"       -> ctrl = true
        "shift"                 -> shift = true
        "alt", "meta", "option" -> alt = true
    }
    val keyCode = TK_NAMED_KEYS[keyPart] ?: run {
        if (keyPart.length != 1) return@run null
        val c = keyPart[0]
        if (c.isUpperCase()) shift = true
        val u = c.uppercaseChar()
        when {
            u in 'A'..'Z' -> AKeyEvent.KEYCODE_A + (u - 'A')
            u in '0'..'9' -> AKeyEvent.KEYCODE_0 + (u - '0')
            else -> null
        }
    } ?: return null
    return TkBinding(keyCode, ctrl, shift, alt)
}

fun formatTimer(seconds: Int, active: Boolean): String {
    val m = seconds / 60
    val s = seconds % 60
    val display = "$m'${s.toString().padStart(2, '0')}\""
    return if (active) "[$display]" else " $display"
}

data class SectionAnalysis(val level: Int, val title: String, val words: Int, val pct: Float)

fun computeSectionAnalysis(toc: List<TocEntry>, content: String): List<SectionAnalysis> {
    if (toc.isEmpty()) return emptyList()
    val total = content.split(Regex("\\s+")).count { it.isNotEmpty() }
    return toc.mapIndexed { i, entry ->
        val start = entry.charOffset.coerceIn(0, content.length)
        val end = (if (i + 1 < toc.size) toc[i + 1].charOffset else content.length).coerceIn(0, content.length)
        val wc = content.substring(start, end).split(Regex("\\s+")).count { it.isNotEmpty() }
        SectionAnalysis(entry.level, entry.title, wc, if (total > 0) wc * 100f / total else 0f)
    }
}

private data class EditorStyle(
    val fgColor: Int, val fontSizeSp: Float, val lineSpacingMult: Float,
    val padX: Int, val padY: Int, val writable: Boolean, val hemingway: Boolean,
    val fontFamily: String, val fontBold: Boolean, val blockCursor: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: WrithdeckViewModel, onBack: () -> Unit, onNavigateSettings: () -> Unit = {}) {
    val content by vm.content.collectAsStateWithLifecycle()
    val wordCount by vm.wordCount.collectAsStateWithLifecycle()
    val currentFile by vm.currentFile.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val headingMarker by vm.headingMarker.collectAsStateWithLifecycle()
    val markdownHeadings by vm.markdownHeadings.collectAsStateWithLifecycle()
    val commentMarker by vm.commentMarker.collectAsStateWithLifecycle()
    val boldMarker by vm.boldMarker.collectAsStateWithLifecycle()
    val italicMarker by vm.italicMarker.collectAsStateWithLifecycle()
    val underlineMarker by vm.underlineMarker.collectAsStateWithLifecycle()
    val strikethroughMarker by vm.strikethroughMarker.collectAsStateWithLifecycle()
    val keySave by vm.keySave.collectAsStateWithLifecycle()
    val keyFind by vm.keyFind.collectAsStateWithLifecycle()
    val keyReplace by vm.keyReplace.collectAsStateWithLifecycle()
    val keyGoto by vm.keyGoto.collectAsStateWithLifecycle()
    val keyClose by vm.keyClose.collectAsStateWithLifecycle()
    val keyToc by vm.keyToc.collectAsStateWithLifecycle()
    val marginWidth by vm.marginWidth.collectAsStateWithLifecycle()
    val marginHeight by vm.marginHeight.collectAsStateWithLifecycle()
    val fontSize by vm.fontSize.collectAsStateWithLifecycle()
    val fontFamily by vm.fontFamily.collectAsStateWithLifecycle()
    val fontBold by vm.fontBold.collectAsStateWithLifecycle()
    val blockCursor by vm.blockCursor.collectAsStateWithLifecycle()
    val lineSpacing by vm.lineSpacing.collectAsStateWithLifecycle()
    val hemingwayMode by vm.hemingwayMode.collectAsStateWithLifecycle()
    val toc by vm.toc.collectAsStateWithLifecycle()
    val saveBinding = remember(keySave) { tkKeyToAndroid(keySave) }
    val findBinding = remember(keyFind) { tkKeyToAndroid(keyFind) }
    val replaceBinding = remember(keyReplace) { tkKeyToAndroid(keyReplace) }
    val gotoBinding = remember(keyGoto) { tkKeyToAndroid(keyGoto) }
    val closeBinding = remember(keyClose) { tkKeyToAndroid(keyClose) }
    val tocBinding = remember(keyToc) { tkKeyToAndroid(keyToc) }

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
    val mkColor = parseHexColor(themeColors.markupColor)
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
    val markupColorInt = if (mkColor != Color.Unspecified) mkColor.toArgb() else 0
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
    val lastStyle = remember { arrayOf(EditorStyle(0, 0f, 0f, -1, -1, true, false, "", false, false)) }
    val hemingwayFilter = remember {
        android.text.InputFilter { _, start, end, dest, dstart, dend ->
            if (dend - dstart > end - start) dest.subSequence(dstart, dend) else null
        }
    }

    var distractionFree by rememberSaveable { mutableStateOf(false) }
    var showReadOnlyAlert by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showSaveConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(currentFile?.path) {
        if (!fileWritable && currentFile != null) showReadOnlyAlert = true
    }

    fun doBack() {
        vm.saveCursor(editorRef.value?.selectionStart ?: 0)
        onBack()
    }

    fun requestClose() {
        when {
            dirty && !fileWritable -> showDiscardConfirm = true
            dirty -> showSaveConfirm = true
            else -> doBack()
        }
    }

    DisposableEffect(Unit) { onDispose { vm.saveCursor(editorRef.value?.selectionStart ?: 0) } }

    if (distractionFree) BackHandler { distractionFree = false }
    if (dirty && !fileWritable && !distractionFree) BackHandler { showDiscardConfirm = true }
    if (dirty && fileWritable && !distractionFree) BackHandler { showSaveConfirm = true }

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
    var tocPinned by rememberSaveable { mutableStateOf(false) }

    var showFind by remember { mutableStateOf(false) }
    var findReplace by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var findMatchIndex by remember { mutableStateOf(0) }
    val findFocusRequester = remember { FocusRequester() }
    val replaceFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showFind) { if (showFind) findFocusRequester.requestFocus() }

    var showMenu by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showWords by remember { mutableStateOf(false) }
    var showAnalyse by remember { mutableStateOf(false) }
    var showGotoLine by remember { mutableStateOf(false) }
    var gotoLineValue by remember { mutableStateOf("") }
    var statsData by remember { mutableStateOf<List<StatEntry>>(emptyList()) }
    var wordsData by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var analyseData by remember { mutableStateOf<List<SectionAnalysis>>(emptyList()) }

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
    LaunchedEffect(
        content, headingMarker, markdownHeadings,
        commentMarker, boldMarker, italicMarker, underlineMarker, strikethroughMarker,
        hdColorInt, cmtColorInt, markupColorInt
    ) {
        val snap = content
        delay(300)
        if (content != snap) return@LaunchedEffect
        val spans = withContext(Dispatchers.Default) {
            computeSyntaxSpans(
                snap, headingMarker, markdownHeadings,
                commentMarker, boldMarker, italicMarker, underlineMarker, strikethroughMarker,
                hdColorInt, cmtColorInt, markupColorInt
            )
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
                    IconButton(onClick = { requestClose() }) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.saveFile() }, enabled = dirty && fileWritable) {
                        Icon(Icons.Filled.Save, contentDescription = "Save")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            // — Edit —
                            DropdownMenuItem(
                                text = { Text("Find", fontFamily = FontFamily.Monospace) },
                                onClick = { showMenu = false; showFind = true },
                                trailingIcon = { Text("⌘F", style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant) }
                            )
                            DropdownMenuItem(
                                text = { Text("Find & Replace", fontFamily = FontFamily.Monospace) },
                                onClick = { showMenu = false; showFind = true; findReplace = true },
                                trailingIcon = { Text("⌘H", style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant) }
                            )
                            DropdownMenuItem(
                                text = { Text("Goto line…", fontFamily = FontFamily.Monospace) },
                                onClick = { showMenu = false; showGotoLine = true; gotoLineValue = "" },
                                trailingIcon = { Text("⌘G", style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant) }
                            )
                            DropdownMenuItem(
                                text = { Text("Toggle heading", fontFamily = FontFamily.Monospace) },
                                enabled = headingMarker.isNotEmpty(),
                                onClick = {
                                    val et = editorRef.value
                                    if (et != null && headingMarker.isNotEmpty()) {
                                        val r = applyHeadingResult(et.text.toString(),
                                            et.selectionStart, et.selectionEnd, headingMarker)
                                        if (r != null) {
                                            val (newText, ns, ne) = r
                                            ignoreTextChange[0] = true
                                            et.setText(newText); et.setSelection(ns, ne)
                                            ignoreTextChange[0] = false
                                            vm.updateContent(newText)
                                        }
                                    }
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            // — View —
                            DropdownMenuItem(
                                text = { Text("Table of contents", fontFamily = FontFamily.Monospace) },
                                enabled = toc.isNotEmpty(),
                                onClick = { showMenu = false; showToc = true },
                                trailingIcon = { Text(keyToc, style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant) }
                            )
                            DropdownMenuItem(
                                text = { Text("Dark: $darkPref", fontFamily = FontFamily.Monospace) },
                                onClick = {
                                    showMenu = false
                                    vm.setDarkModePreference(when (darkPref) { "auto" -> "yes"; "yes" -> "no"; else -> "auto" })
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Distraction-free", fontFamily = FontFamily.Monospace) },
                                onClick = { showMenu = false; distractionFree = true }
                            )
                            DropdownMenuItem(
                                text = { Text(
                                    if (wsDualMode) "Workspace $wsActive → ${if (wsActive == 1) 2 else 1}"
                                    else "Open workspace 2",
                                    fontFamily = FontFamily.Monospace
                                ) },
                                onClick = {
                                    val offset = editorRef.value?.selectionStart ?: 0
                                    showMenu = false; vm.toggleWorkspace(offset)
                                }
                            )
                            HorizontalDivider()
                            // — Analyse —
                            DropdownMenuItem(
                                text = { Text("Writing stats", fontFamily = FontFamily.Monospace) },
                                onClick = { showMenu = false; scope.launch { statsData = vm.getDailyStats(); showStats = true } }
                            )
                            DropdownMenuItem(
                                text = { Text("Word frequency", fontFamily = FontFamily.Monospace) },
                                onClick = { showMenu = false; scope.launch { wordsData = vm.getWordOccurrences(); showWords = true } }
                            )
                            DropdownMenuItem(
                                text = { Text("Structure analysis", fontFamily = FontFamily.Monospace) },
                                enabled = toc.isNotEmpty(),
                                onClick = { showMenu = false; analyseData = computeSectionAnalysis(toc, content); showAnalyse = true }
                            )
                            HorizontalDivider()
                            // — Timer —
                            val timerLabel = when {
                                !timerActive && timerLastTick == 0L -> "Start timer"
                                timerActive -> "Pause  ${formatTimer(timerRemaining, true).trim()}"
                                else -> "Resume  ${formatTimer(timerRemaining, false).trim()}"
                            }
                            DropdownMenuItem(
                                text = { Text(timerLabel, fontFamily = FontFamily.Monospace,
                                    color = if (timerActive) colorScheme.primary else colorScheme.onSurface) },
                                onClick = {
                                    showMenu = false
                                    when {
                                        !timerActive && timerLastTick == 0L -> vm.timerStart()
                                        timerActive -> vm.timerPause()
                                        else -> vm.timerResume()
                                    }
                                }
                            )
                            if (timerLastTick != 0L || timerActive) {
                                DropdownMenuItem(
                                    text = { Text("Reset timer", fontFamily = FontFamily.Monospace) },
                                    onClick = { showMenu = false; vm.timerReset() }
                                )
                            }
                            HorizontalDivider()
                            // — App —
                            DropdownMenuItem(
                                text = { Text("Settings", fontFamily = FontFamily.Monospace) },
                                onClick = { showMenu = false; onNavigateSettings() }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!distractionFree) Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
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
                    FlingEditText(ctx).apply {
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
                        typeface = Typeface.create(fontFamily, if (fontBold) Typeface.BOLD else Typeface.NORMAL)
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
                    val fileKey = "${currentFile?.path}:${wsActive}"
                    if (editText.tag != fileKey) {
                        ignoreTextChange[0] = true
                        editText.setText(content)
                        editText.setSelection(vm.liveCursor.coerceIn(0, content.length))
                        editText.tag = fileKey
                        ignoreTextChange[0] = false
                    }
                    // Guard all layout-invalidating calls — avoids scroll jank from timer recompositions.
                    val newStyle = EditorStyle(editorFgInt, fontSize.toFloat(), lineSpacing,
                                               marginWidthPx, marginHeightPx, fileWritable, hemingwayMode,
                                               fontFamily, fontBold, blockCursor)
                    if (lastStyle[0] != newStyle) {
                        editText.setTextColor(newStyle.fgColor)
                        if (newStyle.fontFamily != lastStyle[0].fontFamily || newStyle.fontBold != lastStyle[0].fontBold) {
                            editText.typeface = Typeface.create(newStyle.fontFamily,
                                if (newStyle.fontBold) Typeface.BOLD else Typeface.NORMAL)
                        }
                        editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, newStyle.fontSizeSp)
                        editText.setLineSpacing(0f, newStyle.lineSpacingMult)
                        editText.setPadding(newStyle.padX, newStyle.padY, newStyle.padX, newStyle.padY)
                        editText.keyListener = if (newStyle.writable) originalKeyListener[0] else null
                        editText.filters = if (newStyle.hemingway && newStyle.writable)
                            arrayOf(hemingwayFilter) else emptyArray()
                        // Block cursor: Android has no native -blockcursor like Tk; emulate it with a
                        // custom cursor drawable sized to one character's width × the line height,
                        // filled with the foreground colour (matches Tk's -insertbackground $fg).
                        // setTextCursorDrawable is API 29+ — older devices keep the default thin caret.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            editText.textCursorDrawable = if (newStyle.blockCursor) {
                                val charWidth = editText.paint.measureText("M").toInt().coerceAtLeast(1)
                                GradientDrawable().apply {
                                    setColor(newStyle.fgColor)
                                    setSize(charWidth, editText.lineHeight)
                                }
                            } else null
                        }
                        lastStyle[0] = newStyle
                    }

                    // Update key handler with current compose state values
                    keyHandlerRef[0] = handler@{ keyCode, event ->
                        if (event.action != AKeyEvent.ACTION_DOWN) return@handler false
                        val ctrl = event.isCtrlPressed
                        when {
                            hemingwayMode && (keyCode == AKeyEvent.KEYCODE_DEL ||
                                keyCode == AKeyEvent.KEYCODE_FORWARD_DEL) -> true
                            tocBinding != null && tocBinding.matches(keyCode, event) && toc.isNotEmpty() -> {
                                showToc = true; true
                            }
                            saveBinding?.matches(keyCode, event) == true -> { if (fileWritable) vm.saveFile(); true }
                            findBinding?.matches(keyCode, event) == true -> { showFind = true; true }
                            replaceBinding?.matches(keyCode, event) == true -> { showFind = true; findReplace = true; true }
                            gotoBinding?.matches(keyCode, event) == true -> { showGotoLine = true; true }
                            closeBinding?.matches(keyCode, event) == true -> { requestClose(); true }
                            !ctrl && keyCode == AKeyEvent.KEYCODE_ESCAPE && showFind -> {
                                showFind = false; findQuery = ""; findReplace = false; true
                            }
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
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        // Find row
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                            Key.Escape -> { showFind = false; findQuery = ""; findReplace = false; true }
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
                            }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous") }
                            IconButton(onClick = {
                                if (searchMatches.isNotEmpty())
                                    findMatchIndex = (findMatchIndex + 1) % searchMatches.size
                            }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next") }
                            IconButton(onClick = { findReplace = !findReplace }) {
                                Icon(
                                    Icons.Filled.SwapVert,
                                    contentDescription = "Toggle replace",
                                    tint = if (findReplace) colorScheme.primary else colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { showFind = false; findQuery = ""; findReplace = false }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close search")
                            }
                        }
                        // Replace row
                        if (findReplace) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicTextField(
                                    value = replaceText,
                                    onValueChange = { replaceText = it },
                                    textStyle = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = fontSize.sp,
                                        color = colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(colorScheme.primary),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                        .focusRequester(replaceFocusRequester)
                                        .onKeyEvent { event ->
                                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                            if (event.key == Key.Escape) { showFind = false; findReplace = false; findQuery = ""; true }
                                            else false
                                        }
                                )
                                TextButton(
                                    onClick = {
                                        if (searchMatches.isNotEmpty() && editorRef.value != null) {
                                            val et = editorRef.value!!
                                            val idx = findMatchIndex.coerceIn(0, searchMatches.lastIndex)
                                            val range = searchMatches[idx]
                                            val text = et.text.toString()
                                            val s = range.first.coerceIn(0, text.length)
                                            val e = (range.last + 1).coerceIn(0, text.length)
                                            val newText = text.substring(0, s) + replaceText + text.substring(e)
                                            ignoreTextChange[0] = true
                                            et.setText(newText)
                                            et.setSelection((s + replaceText.length).coerceAtMost(newText.length))
                                            ignoreTextChange[0] = false
                                            vm.updateContent(newText)
                                        }
                                    },
                                    enabled = searchMatches.isNotEmpty()
                                ) { Text("Replace", style = MaterialTheme.typography.bodySmall) }
                                TextButton(
                                    onClick = {
                                        if (findQuery.length >= 2 && editorRef.value != null) {
                                            val et = editorRef.value!!
                                            val text = et.text.toString()
                                            val newText = text.replace(findQuery, replaceText, ignoreCase = true)
                                            if (newText != text) {
                                                ignoreTextChange[0] = true
                                                et.setText(newText)
                                                et.setSelection(newText.length)
                                                ignoreTextChange[0] = false
                                                vm.updateContent(newText)
                                            }
                                        }
                                    },
                                    enabled = findQuery.length >= 2
                                ) { Text("All", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }

    // TOC sheet
    if (showToc) {
        val tocListState = rememberLazyListState()
        val tocFocusRequester = remember { FocusRequester() }
        var tocFocusedIndex by remember(showToc) { mutableIntStateOf(-1) }
        val tocScope = rememberCoroutineScope()

        fun tocMove(delta: Int) {
            if (toc.isEmpty()) return
            val next = if (tocFocusedIndex < 0) (if (delta > 0) 0 else toc.lastIndex)
                       else ((tocFocusedIndex + delta) % toc.size + toc.size) % toc.size
            tocFocusedIndex = next
            tocScope.launch { tocListState.animateScrollToItem(next) }
        }

        fun tocSelect(entry: TocEntry) {
            editorRef.value?.setSelection(entry.charOffset.coerceIn(0, content.length))
            if (!tocPinned) showToc = false
        }

        LaunchedEffect(showToc) {
            if (showToc) tocFocusRequester.requestFocus()
        }

        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp)) {
                Text("Table of contents", style = MaterialTheme.typography.titleMedium,
                     modifier = Modifier.weight(1f))
                IconButton(onClick = { tocPinned = !tocPinned }) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = if (tocPinned) "Unpin TOC" else "Pin TOC",
                        tint = if (tocPinned) colorScheme.primary else colorScheme.onSurfaceVariant
                    )
                }
            }
            LazyColumn(
                state = tocListState,
                contentPadding = PaddingValues(bottom = 32.dp),
                modifier = Modifier
                    .focusRequester(tocFocusRequester)
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> { tocMove(1); true }
                            Key.DirectionUp -> { tocMove(-1); true }
                            Key.Enter, Key.NumPadEnter -> {
                                if (tocFocusedIndex in toc.indices) tocSelect(toc[tocFocusedIndex])
                                true
                            }
                            Key.Escape -> { showToc = false; true }
                            else -> false
                        }
                    }
            ) {
                itemsIndexed(toc) { index, entry ->
                    val indent = (entry.level - 1) * 16
                    Text(
                        text = entry.title,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (entry.level == 1) FontWeight.Bold else FontWeight.Normal,
                        fontSize = when (entry.level) { 1 -> 16.sp; 2 -> 15.sp; else -> 14.sp },
                        color = if (entry.level == 1) colorScheme.primary else colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                            .background(if (index == tocFocusedIndex) colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { tocFocusedIndex = index; tocSelect(entry) }
                            .padding(start = (20 + indent).dp, end = 20.dp, top = 10.dp, bottom = 10.dp)
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
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

    if (showSaveConfirm) {
        AlertDialog(
            onDismissRequest = { showSaveConfirm = false },
            title = { Text("Save changes?") },
            text = { Text("This document has unsaved changes.") },
            confirmButton = {
                TextButton(onClick = { showSaveConfirm = false; vm.saveFile(); doBack() }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showSaveConfirm = false; doBack() }) { Text("Don't save") }
                    TextButton(onClick = { showSaveConfirm = false }) { Text("Cancel") }
                }
            }
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

    if (showGotoLine) {
        val lineCount = remember(content) { content.lines().size }
        AlertDialog(
            onDismissRequest = { showGotoLine = false },
            title = { Text("Go to line", fontFamily = FontFamily.Monospace) },
            text = {
                OutlinedTextField(
                    value = gotoLineValue,
                    onValueChange = { gotoLineValue = it },
                    label = { Text("Line (1–$lineCount)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val lineNum = gotoLineValue.toIntOrNull()?.coerceIn(1, lineCount) ?: return@TextButton
                    val offset = content.lines().take(lineNum - 1).sumOf { it.length + 1 }
                    editorRef.value?.setSelection(offset.coerceIn(0, content.length))
                    showGotoLine = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { showGotoLine = false }) { Text("Cancel") } }
        )
    }

    if (showAnalyse && analyseData.isNotEmpty()) {
        val totalWords = remember(analyseData) { analyseData.sumOf { it.words } }
        AlertDialog(
            onDismissRequest = { showAnalyse = false },
            title = { Text("Structure", fontFamily = FontFamily.Monospace) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(analyseData) { _, sec ->
                        val indent = (sec.level - 1) * 12
                        Column(modifier = Modifier.fillMaxWidth()
                            .padding(start = indent.dp, top = 6.dp, bottom = 2.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = sec.title,
                                    fontFamily = FontFamily.Monospace,
                                    style = if (sec.level == 1) MaterialTheme.typography.bodyMedium
                                            else MaterialTheme.typography.bodySmall,
                                    fontWeight = if (sec.level == 1) androidx.compose.ui.text.font.FontWeight.Bold
                                                 else androidx.compose.ui.text.font.FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${sec.words} w",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            if (sec.pct > 0f) {
                                LinearProgressIndicator(
                                    progress = { sec.pct / 100f },
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
                                    color = colorScheme.primary.copy(alpha = 0.6f),
                                    trackColor = colorScheme.surfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                    item {
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total", fontFamily = FontFamily.Monospace,
                                 style = MaterialTheme.typography.bodySmall,
                                 color = colorScheme.onSurfaceVariant)
                            Text("$totalWords w", fontFamily = FontFamily.Monospace,
                                 style = MaterialTheme.typography.bodySmall,
                                 color = colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAnalyse = false }) { Text("Close") } }
        )
    }
}
