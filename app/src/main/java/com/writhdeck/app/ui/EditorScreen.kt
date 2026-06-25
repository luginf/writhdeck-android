package com.writhdeck.app.ui

import android.app.Activity
import com.writhdeck.app.BuildConfig
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.SuggestionSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent as AKeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.widget.EditText
import android.widget.OverScroller
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.writhdeck.app.FontManager
import com.writhdeck.app.R
import com.writhdeck.app.StatEntry
import com.writhdeck.app.TocEntry
import com.writhdeck.app.WrithdeckViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Marker span classes — used to find and remove old spans without touching unrelated ones.
private class SyntaxHeadingSpan(color: Int) : ForegroundColorSpan(color)
private class SyntaxCommentSpan(color: Int) : ForegroundColorSpan(color)
private class SyntaxMarkupSpan(color: Int) : ForegroundColorSpan(color)
private class SearchBgSpan(color: Int) : BackgroundColorSpan(color)
private class SearchCurrentBgSpan(color: Int) : BackgroundColorSpan(color)
private class SearchCurrentFgSpan(color: Int) : ForegroundColorSpan(color)
private class TypewriterDimSpan(color: Int) : ForegroundColorSpan(color)

// Misspelled-word underline, applied by our own viewport-based spell-check pass
// (see "Spell checking" below) — a thin wrapper so getSpans() can find/remove
// only spans we added, regardless of suggestions content.
private class SpellErrorSpan(context: Context, suggestions: Array<String>) :
    SuggestionSpan(context, suggestions, FLAG_MISSPELLED)

/** EditText with fling-based momentum scrolling.
 *
 *  A plain multi-line EditText scrolls 1:1 with the drag gesture and stops dead on
 *  release — no inertia, unlike ScrollView/RecyclerView/most text editors and browsers.
 *  Wrapping it in a ScrollView would fix that, but forces full-content-height
 *  measurement, which defeats DynamicLayout's visible-lines-only rendering and makes
 *  large files (500K+ chars) slow again — so fling is implemented here instead by
 *  driving the EditText's own `scrollY` with an OverScroller, exactly like ScrollView
 *  does internally. */
private const val BLINK_MS = 500L

private class FlingEditText(context: Context) : EditText(context) {
    private val scroller = OverScroller(context)
    var selectionChangeListener: (() -> Unit)? = null

    // Caret drawn ourselves in onDraw rather than relying on the native caret, for two
    // reasons:
    //  - block cursor (terminal-style): sized to the exact width of the glyph under the
    //    caret (proportional fonts) and inverting that glyph (drawn in the background
    //    colour over the filled block), the way Tk's -blockcursor does — a fixed-width
    //    textCursorDrawable couldn't do either (too wide on narrow chars/spaces, opaque
    //    rectangle hiding the letter);
    //  - normal caret: the native one is a ~1px hairline that's almost invisible, so we
    //    draw a thicker (2dp) bar in the foreground colour instead.
    // The caret is confined to the glyph box (baseline + ascent..descent), not the full
    // line height, so it never spills into the inter-line gap (line_spacing). Blinking is
    // opt-in (cursor_blink, off by default) — when off the caret is steady and always on.
    private var blockCursorOn = false
    private var blockCursorFill = 0
    private var blockCursorTextColor = 0
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caretWidthPx = (2f * resources.displayMetrics.density).coerceAtLeast(2f)

    private var blinkEnabled = false
    private var caretShown = true
    private val blinkRunnable = object : Runnable {
        override fun run() {
            caretShown = !caretShown
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    private fun restartBlink() {
        removeCallbacks(blinkRunnable)
        caretShown = true
        if (blinkEnabled && isFocused) postDelayed(blinkRunnable, BLINK_MS)
    }

    fun setBlockCursor(enabled: Boolean, fillColor: Int, textColor: Int) {
        blockCursorOn = enabled
        blockCursorFill = fillColor
        blockCursorTextColor = textColor
        // We always draw the caret ourselves (block or thick bar) — hide the native one.
        isCursorVisible = false
        invalidate()
    }

    fun setCursorBlink(enabled: Boolean) {
        if (blinkEnabled == enabled) return
        blinkEnabled = enabled
        restartBlink()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isFocused) return
        if (blinkEnabled && !caretShown) return
        // Only a collapsed caret is drawn; a range selection uses the normal highlight.
        val pos = selectionStart
        if (pos < 0 || pos != selectionEnd) return
        val l = layout ?: return
        val line = l.getLineForOffset(pos)
        // Confine to the glyph box around the baseline (excludes line_spacing leading).
        val baseline = l.getLineBaseline(line).toFloat()
        val fm = paint.fontMetrics
        val top = baseline + fm.ascent
        val bottom = baseline + fm.descent
        val left = l.getPrimaryHorizontal(pos)

        canvas.save()
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
        if (blockCursorOn) {
            val text = text
            // Width = the glyph under the caret, unless we're at end-of-line / end-of-text /
            // before a newline (no glyph to cover) — then fall back to an em width.
            val hasGlyph = text != null && pos < text.length && text[pos] != '\n' &&
                l.getLineForOffset(pos + 1) == line
            // No glyph to cover (end-of-line/text, before a newline): use a representative
            // average-width char ("e") rather than the widest ("M"), which looked square.
            val right = if (hasGlyph) l.getPrimaryHorizontal(pos + 1) else left + paint.measureText("e")
            cursorPaint.set(paint)
            cursorPaint.color = blockCursorFill
            canvas.drawRect(left, top, right, bottom, cursorPaint)
            if (hasGlyph) {
                // Redraw the covered glyph in the background colour → inverted look.
                cursorPaint.color = blockCursorTextColor
                canvas.drawText(text!!, pos, pos + 1, left, baseline, cursorPaint)
            }
        } else {
            // Normal caret — a thicker, clearly visible bar in the foreground colour.
            cursorPaint.set(paint)
            cursorPaint.color = blockCursorFill
            canvas.drawRect(left, top, left + caretWidthPx, bottom, cursorPaint)
        }
        canvas.restore()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        restartBlink()   // keep the caret solid right after it moves
        invalidate()
        selectionChangeListener?.invoke()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        restartBlink()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blinkRunnable)
    }

    /** Typewriter mode: scroll so the line containing the cursor is vertically centred. */
    fun centerCurrentLine() {
        val l = layout ?: return
        val visibleHeight = height - totalPaddingTop - totalPaddingBottom
        if (visibleHeight <= 0) return
        val line = l.getLineForOffset(selectionStart)
        val lineTop = l.getLineTop(line)
        val lineBottom = l.getLineBottom(line)
        val target = lineTop + (lineBottom - lineTop) / 2 - visibleHeight / 2
        val maxScroll = (l.height - visibleHeight).coerceAtLeast(0)
        scrollTo(scrollX, target.coerceIn(0, maxScroll))
    }
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

/** Typewriter mode: dims every paragraph except the one containing the cursor
 *  (mirrors Tcl's `focus-para-update` — paragraphs are separated by blank lines). */
fun applyTypewriterDimming(editable: Editable, content: String, cursorPos: Int, enabled: Boolean, dimColorInt: Int) {
    editable.getSpans(0, editable.length, TypewriterDimSpan::class.java).forEach { editable.removeSpan(it) }
    if (!enabled || dimColorInt == 0) return
    val pos = cursorPos.coerceIn(0, content.length)
    var paraStart = content.lastIndexOf("\n\n", (pos - 1).coerceAtLeast(0))
    paraStart = if (paraStart < 0) 0 else paraStart + 2
    var paraEnd = content.indexOf("\n\n", pos)
    paraEnd = if (paraEnd < 0) content.length else paraEnd
    val s1 = paraStart.coerceIn(0, editable.length)
    val s2 = paraEnd.coerceIn(0, editable.length)
    if (s1 > 0) editable.setSpan(TypewriterDimSpan(dimColorInt), 0, s1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    if (s2 < editable.length) editable.setSpan(TypewriterDimSpan(dimColorInt), s2, editable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
}

// Returns (newText, selStart, selEnd) or null if marker is empty.
// Mirror of web's applyHeading(level) (writhdeck-web/src/editor.js): repeats `marker`
// `level` times as prefix/suffix on each selected line; if every selected line is
// already at that level, strips the heading back to plain text instead.
fun applyHeadingLevel(text: String, selStart: Int, selEnd: Int, marker: String, level: Int): Triple<String, Int, Int>? {
    if (marker.isEmpty()) return null
    val minSel = minOf(selStart, selEnd)
    val maxSel = maxOf(selStart, selEnd)
    val blockStart = text.lastIndexOf('\n', (minSel - 1).coerceAtLeast(0))
        .let { if (it < 0) 0 else it + 1 }
    val blockEnd = text.indexOf('\n', maxSel).let { if (it < 0) text.length else it }
    val prefix = marker.repeat(level)

    fun headingLevel(line: String): Int {
        if (!line.startsWith(marker)) return 0
        var n = 1
        while (n < 3 && line.startsWith(marker.repeat(n + 1))) n++
        return n
    }
    fun stripHeading(line: String): String {
        var t = line.trim()
        while (t.startsWith(marker)) t = t.removePrefix(marker).trim()
        while (t.endsWith(marker)) t = t.removeSuffix(marker).trim()
        return t
    }

    val lines = text.substring(blockStart, blockEnd).split('\n')
    val allAtLevel = lines.all { headingLevel(it) == level }
    val newLines = lines.map { l ->
        if (allAtLevel) stripHeading(l)
        else {
            val body = if (headingLevel(l) > 0) stripHeading(l) else l.trim()
            if (body.isNotEmpty()) "$prefix $body $prefix" else "$prefix  $prefix"
        }
    }
    val newBlock = newLines.joinToString("\n")
    val newText = text.substring(0, blockStart) + newBlock + text.substring(blockEnd)
    return Triple(newText, blockStart, blockStart + newBlock.length)
}

// Returns (newText, selStart, selEnd) or null if marker is empty.
// Mirror of web's applyLineMarker(marker) (writhdeck-web/src/editor.js): toggles
// `marker` at the start of every selected line (e.g. comment marker).
fun applyLineMarkerResult(text: String, selStart: Int, selEnd: Int, marker: String): Triple<String, Int, Int>? {
    if (marker.isEmpty()) return null
    // Always separate the marker from the line's content with exactly one
    // space (e.g. "% comment"), regardless of whether the configured marker
    // itself already ends with whitespace (e.g. "%" or "% ").
    val trimmed = marker.trimEnd()
    val minSel = minOf(selStart, selEnd)
    val maxSel = maxOf(selStart, selEnd)
    val blockStart = text.lastIndexOf('\n', (minSel - 1).coerceAtLeast(0))
        .let { if (it < 0) 0 else it + 1 }
    val blockEnd = text.indexOf('\n', maxSel).let { if (it < 0) text.length else it }
    val lines = text.substring(blockStart, blockEnd).split('\n')
    val allMarked = lines.all { it.startsWith(trimmed) }
    val newLines = if (allMarked) lines.map {
        val rest = it.removePrefix(trimmed)
        if (rest.startsWith(' ')) rest.substring(1) else rest
    } else lines.map { if (it.isEmpty()) trimmed else "$trimmed $it" }
    val newBlock = newLines.joinToString("\n")
    val newText = text.substring(0, blockStart) + newBlock + text.substring(blockEnd)
    return Triple(newText, blockStart, blockStart + newBlock.length)
}

// Returns (newText, selStart, selEnd) or null if marker is empty.
// Mirror of web's applyInlineMarker(marker) (writhdeck-web/src/editor.js): wraps/unwraps
// the selection with `marker...marker`, or inserts `marker+marker` and places the
// cursor between them if there is no selection (e.g. bold/italic/underline/strike).
fun applyInlineMarkerResult(text: String, selStart: Int, selEnd: Int, marker: String): Triple<String, Int, Int>? {
    if (marker.isEmpty()) return null
    val s = minOf(selStart, selEnd)
    val e = maxOf(selStart, selEnd)
    if (s == e) {
        val newText = text.substring(0, s) + marker + marker + text.substring(s)
        val pos = s + marker.length
        return Triple(newText, pos, pos)
    }
    val sel = text.substring(s, e)
    val isWrapped = sel.startsWith(marker) && sel.endsWith(marker) && sel.length > marker.length * 2
    return if (isWrapped) {
        val inner = sel.substring(marker.length, sel.length - marker.length)
        val newText = text.substring(0, s) + inner + text.substring(e)
        Triple(newText, s, s + inner.length)
    } else {
        val wrapped = marker + sel + marker
        val newText = text.substring(0, s) + wrapped + text.substring(e)
        Triple(newText, s, s + wrapped.length)
    }
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

// Tappable shortcuts shown in the command-mode bar — mirrors the `when` switch
// in performCmdAction(letter), one entry per supported one-letter action.
// Letters are fixed action codes (not translated); labels are resolved via
// stringResource at the call site (see cmdActionLabelRes below).
private val CMD_ACTION_LETTERS = listOf('f', 'r', 'g', 'n', 'd', 'o', 'w', 't', 's', 'a', 'm', 'c', 'q')

private fun cmdActionLabelRes(letter: Char): Int = when (letter) {
    'f' -> R.string.editor_cmd_action_find
    'r' -> R.string.editor_cmd_action_replace
    'g' -> R.string.editor_cmd_action_goto
    'n' -> R.string.editor_cmd_action_line_number
    'd' -> R.string.editor_cmd_action_dark
    'o' -> R.string.editor_cmd_action_toc
    'w' -> R.string.editor_cmd_action_typewriter
    't' -> R.string.editor_cmd_action_timer
    's' -> R.string.editor_cmd_action_stats
    'a' -> R.string.editor_cmd_action_analyse
    'm' -> R.string.editor_cmd_action_menu
    'c' -> R.string.editor_cmd_action_settings
    'q' -> R.string.editor_cmd_action_close
    else -> R.string.editor_cmd_action_find
}

private const val BASE_INPUT_TYPE = android.text.InputType.TYPE_CLASS_TEXT or
    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

// IDs for the formatting items added to the long-press / right-click text
// context menu (mirrors the unified Tcl/web right-click menu: H1-3, comment,
// bold/italic/underline/strike, spell check on/off — alongside the system's
// own Cut/Copy/Paste/Select all).
private const val MENU_ID_H1 = Menu.FIRST + 1
private const val MENU_ID_H2 = Menu.FIRST + 2
private const val MENU_ID_H3 = Menu.FIRST + 3
private const val MENU_ID_COMMENT = Menu.FIRST + 4
private const val MENU_ID_BOLD = Menu.FIRST + 5
private const val MENU_ID_ITALIC = Menu.FIRST + 6
private const val MENU_ID_UNDERLINE = Menu.FIRST + 7
private const val MENU_ID_STRIKE = Menu.FIRST + 8
private const val MENU_ID_SPELLCHECK = Menu.FIRST + 9

private data class EditorStyle(
    val fgColor: Int, val bgColor: Int, val fontSizeSp: Float, val lineSpacingMult: Float,
    val padX: Int, val padY: Int, val padBottom: Int, val writable: Boolean, val hemingway: Boolean,
    val fontFamily: String, val fontBold: Boolean, val blockCursor: Boolean, val cursorBlink: Boolean,
    val spellCheckEnabled: Boolean
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
    val keyTypewriter by vm.keyTypewriter.collectAsStateWithLifecycle()
    val keyLineNumbers by vm.keyLineNumbers.collectAsStateWithLifecycle()
    val keyCmdMode by vm.keyCmdMode.collectAsStateWithLifecycle()
    val marginWidth by vm.marginWidth.collectAsStateWithLifecycle()
    val marginHeight by vm.marginHeight.collectAsStateWithLifecycle()
    val fontSize by vm.fontSize.collectAsStateWithLifecycle()
    val fontFamily by vm.fontFamily.collectAsStateWithLifecycle()
    val fontBold by vm.fontBold.collectAsStateWithLifecycle()
    val blockCursor by vm.blockCursor.collectAsStateWithLifecycle()
    val cursorBlink by vm.cursorBlink.collectAsStateWithLifecycle()
    val spellCheckEnabled by vm.spellCheckEnabled.collectAsStateWithLifecycle()
    val spellCheckLanguage by vm.spellCheckLanguage.collectAsStateWithLifecycle()
    val lineSpacing by vm.lineSpacing.collectAsStateWithLifecycle()
    val hemingwayMode by vm.hemingwayMode.collectAsStateWithLifecycle()
    val lineNumbersEnabled by vm.lineNumbersEnabled.collectAsStateWithLifecycle()
    val toc by vm.toc.collectAsStateWithLifecycle()
    val saveBinding = remember(keySave) { tkKeyToAndroid(keySave) }
    val findBinding = remember(keyFind) { tkKeyToAndroid(keyFind) }
    val replaceBinding = remember(keyReplace) { tkKeyToAndroid(keyReplace) }
    val gotoBinding = remember(keyGoto) { tkKeyToAndroid(keyGoto) }
    val closeBinding = remember(keyClose) { tkKeyToAndroid(keyClose) }
    val tocBinding = remember(keyToc) { tkKeyToAndroid(keyToc) }
    val typewriterBinding = remember(keyTypewriter) { tkKeyToAndroid(keyTypewriter) }
    val lineNumbersBinding = remember(keyLineNumbers) { tkKeyToAndroid(keyLineNumbers) }
    val cmdModeBinding = remember(keyCmdMode) { tkKeyToAndroid(keyCmdMode) }

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
    val isScratchpad by vm.isScratchpad.collectAsStateWithLifecycle()
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
    val editorBgInt = editorBg.toArgb()
    val hdColorInt = if (hdColor != Color.Unspecified) hdColor.toArgb() else 0
    val cmtColorInt = if (cmtColor != Color.Unspecified) cmtColor.toArgb() else 0
    val markupColorInt = if (mkColor != Color.Unspecified) mkColor.toArgb() else 0
    val matchBgInt = matchBgColor.toArgb()
    val currentMatchBgInt = currentMatchBgColor.toArgb()
    val currentMatchFgInt = currentMatchFgColor.toArgb()

    val density = LocalDensity.current
    val marginWidthPx  = remember(marginWidth,  density) { with(density) { marginWidth.dp.roundToPx() } }
    val marginHeightPx = remember(marginHeight, density) { with(density) { marginHeight.dp.roundToPx() } }


    // Modal command mode (mirrors Tcl/Web "Echap"): toggled by `keyCmdMode` (default
    // Escape). While active, the next key performs a one-letter action and exits.
    var cmdMode by remember { mutableStateOf(false) }

    var typewriterMode by remember { mutableStateOf(false) }
    // Hemingway mode is a modifier of typewriter mode: it has no effect at all
    // unless typewriter mode is also active (mirrors Tcl's
    // `$::typewriter_mode && $::cfg_hemingway_mode` gating).
    val hemingwayActive = typewriterMode && hemingwayMode
    val effMarginWidthPx  = if (hemingwayActive) marginWidthPx * 2 else marginWidthPx
    val effMarginHeightPx = if (hemingwayActive) marginHeightPx * 2 else marginHeightPx

    // Line numbers gutter: rebuild the "1\n2\n...\nN" text only when the line count
    // changes (mirrors web's `_lastLineCount` cache), scroll-synced via gutterRef.
    val gutterRef = remember { mutableStateOf<android.widget.TextView?>(null) }
    val lineCount = remember(content) { content.count { it == '\n' } + 1 }
    val lineNumbersText = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val gutterPadX = remember(density) { with(density) { 4.dp.roundToPx() } }

    // Native EditText ref + state refs shared with factory closure
    val editorRef = remember { mutableStateOf<android.widget.EditText?>(null) }
    val ignoreTextChange = remember { booleanArrayOf(false) }
    val keyHandlerRef   = remember { arrayOf<(Int, AKeyEvent) -> Boolean>({ _, _ -> false }) }
    // Long-press / right-click text context menu: populated in update{} with the
    // current markers/settings so it reflects the latest Compose state.
    val contextMenuCreateRef = remember { arrayOf<(Menu) -> Unit>({}) }
    val contextMenuClickRef = remember { arrayOf<(Int) -> Boolean>({ false }) }
    // Spell checking: bumped (debounced via LaunchedEffect's delay) whenever the
    // editor scrolls, so newly-visible text gets checked too.
    var spellCheckTick by remember { mutableIntStateOf(0) }
    val spellCheckCookies = remember { arrayOf(IntArray(0)) }
    @Suppress("UNCHECKED_CAST")
    val originalKeyListener = remember { arrayOfNulls<android.text.method.KeyListener>(1) }
    val lastStyle = remember { arrayOf(EditorStyle(0, 0, 0f, 0f, -1, -1, -1, true, false, "", false, false, false, true)) }
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
        if (!fileWritable && !isScratchpad && currentFile != null) showReadOnlyAlert = true
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

    // Applies the (newText, selStart, selEnd) result of a markup helper
    // (applyHeadingLevel/applyLineMarkerResult/applyInlineMarkerResult) to the editor.
    fun applyEditResult(r: Triple<String, Int, Int>?) {
        val et = editorRef.value ?: return
        if (r == null) return
        val (newText, ns, ne) = r
        ignoreTextChange[0] = true
        et.setText(newText); et.setSelection(ns, ne)
        ignoreTextChange[0] = false
        vm.updateContent(newText)
    }

    DisposableEffect(Unit) { onDispose { vm.saveCursor(editorRef.value?.selectionStart ?: 0) } }

    if (distractionFree) BackHandler { distractionFree = false }
    if (dirty && !fileWritable && !distractionFree) BackHandler { showDiscardConfirm = true }
    if (dirty && fileWritable && !distractionFree) BackHandler { showSaveConfirm = true }

    val context = LocalContext.current

    // Export as .txt/.md (mirrors web's Editor.exportDoc(fmt)): writes the current
    // content to a new document chosen via the system "Save As" picker.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.writer().use { w -> w.write(content) } }
            }
        }
    }
    fun exportFileName(ext: String): String =
        "${(currentFile?.name ?: "Untitled").substringBeforeLast('.')}.$ext"

    // Save As: writes content to a new URI and makes it the active document.
    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { vm.saveAsUri(it, context.contentResolver) }
    }

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
    var showFileInfo by remember { mutableStateOf(false) }
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

    fun doReplaceOne() {
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
    }

    fun doReplaceAll() {
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
    }

    // Runs the one-letter command-mode action (mirrors Tcl/Web "Echap" letters),
    // then exits command mode. Shared by the hardware-key handler (cmdMode branch)
    // and the tappable CMD bar items (CMD_ACTIONS), so both paths stay in sync.
    fun performCmdAction(letter: Char) {
        cmdMode = false
        when (letter) {
            'f' -> { showFind = true; findReplace = false }
            'r' -> { showFind = true; findReplace = true }
            'g' -> { showGotoLine = true; gotoLineValue = "" }
            'n' -> vm.setLineNumbersEnabled(!lineNumbersEnabled)
            'd' -> vm.setDarkModePreference(
                when (darkPref) { "auto" -> "yes"; "yes" -> "no"; else -> "auto" }
            )
            'o' -> if (toc.isNotEmpty()) showToc = true
            'w' -> typewriterMode = !typewriterMode
            't' -> when {
                !timerActive && timerLastTick == 0L -> vm.timerStart()
                timerActive -> vm.timerPause()
                else -> vm.timerResume()
            }
            's' -> if (BuildConfig.FULL_VERSION) scope.launch { statsData = vm.getDailyStats(); showStats = true }
            'a' -> if (BuildConfig.FULL_VERSION && toc.isNotEmpty()) {
                analyseData = computeSectionAnalysis(toc, content); showAnalyse = true
            }
            'm' -> showMenu = true
            'c' -> onNavigateSettings()
            'q' -> requestClose()
            else -> {}
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

    // Spell checking — Android's built-in EditText spellchecker only underlines text
    // around recent edits, not pre-existing content loaded via setText(). We drive our
    // own SpellCheckerSession instead, checking the visible lines (+ a small buffer)
    // and applying SuggestionSpan(FLAG_MISSPELLED) ourselves — same red squiggly the
    // system uses, but covering old and new text alike. Mirrors the Tcl/web
    // viewport-only `spell_highlight`.
    val spellCheckerSession = remember { arrayOfNulls<SpellCheckerSession>(1) }
    // Re-created whenever the user picks a different spell-check language in Settings
    // (Display > Editor) — "system" lets the spell checker service decide based on its
    // own settings; an explicit BCP-47 tag pins the session to that locale.
    DisposableEffect(spellCheckLanguage) {
        val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
        val locale = if (spellCheckLanguage == "system") null else Locale.forLanguageTag(spellCheckLanguage)
        val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
            override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {}
            override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
                if (results == null) return
                val editable = editorRef.value?.editableText ?: return
                val cookies = spellCheckCookies[0]
                for (i in results.indices) {
                    val result = results[i] ?: continue
                    val base = cookies.getOrNull(i) ?: continue
                    for (j in 0 until result.suggestionsCount) {
                        val info = result.getSuggestionsInfoAt(j)
                        if ((info.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) == 0) continue
                        val start = (base + result.getOffsetAt(j)).coerceIn(0, editable.length)
                        val end = (start + result.getLengthAt(j)).coerceIn(0, editable.length)
                        if (start >= end) continue
                        val suggestions = (0 until info.suggestionsCount).map { info.getSuggestionAt(it) }.toTypedArray()
                        editable.setSpan(SpellErrorSpan(context, suggestions), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
        }
        spellCheckerSession[0] = tsm.newSpellCheckerSession(null, locale, listener, locale == null)
        onDispose { spellCheckerSession[0]?.close(); spellCheckerSession[0] = null }
    }

    LaunchedEffect(content, spellCheckEnabled, spellCheckLanguage, spellCheckTick, currentFile?.path, wsActive) {
        val editable = editorRef.value?.editableText ?: return@LaunchedEffect
        if (!spellCheckEnabled) {
            editable.getSpans(0, editable.length, SpellErrorSpan::class.java).forEach { editable.removeSpan(it) }
            return@LaunchedEffect
        }
        val session = spellCheckerSession[0] ?: return@LaunchedEffect
        val snap = content
        delay(400)
        if (content != snap) return@LaunchedEffect

        var layout = editorRef.value?.layout
        var attempts = 0
        while (layout == null && attempts < 10) {
            delay(50)
            layout = editorRef.value?.layout
            attempts++
        }
        val et = editorRef.value ?: return@LaunchedEffect
        layout ?: return@LaunchedEffect

        val buffer = 5
        val firstVisible = layout.getLineForVertical(et.scrollY)
        val lastVisible = layout.getLineForVertical(et.scrollY + et.height)
        val startLine = (firstVisible - buffer).coerceAtLeast(0)
        val endLine = (lastVisible + buffer).coerceAtMost(layout.lineCount - 1)
        if (startLine > endLine) return@LaunchedEffect
        val startOffset = layout.getLineStart(startLine)
        val endOffset = layout.getLineEnd(endLine).coerceAtMost(snap.length)
        if (startOffset >= endOffset) return@LaunchedEffect

        editable.getSpans(startOffset, endOffset, SpellErrorSpan::class.java).forEach { editable.removeSpan(it) }

        val infos = mutableListOf<TextInfo>()
        val cookies = mutableListOf<Int>()
        var pos = startOffset
        while (pos < endOffset) {
            val nl = snap.indexOf('\n', pos).let { if (it < 0 || it > endOffset) endOffset else it }
            if (nl > pos) {
                val line = snap.substring(pos, nl)
                if (line.any { it.isLetter() }) {
                    infos.add(TextInfo(line, 0, line.length, infos.size, infos.size))
                    cookies.add(pos)
                }
            }
            pos = nl + 1
        }
        if (infos.isEmpty()) return@LaunchedEffect
        spellCheckCookies[0] = cookies.toIntArray()
        session.getSentenceSuggestions(infos.toTypedArray(), 3)
    }

    // Typewriter mode — re-dim paragraphs and re-centre on text changes / mode toggle.
    // Pure cursor movement (no text change) is handled by FlingEditText.selectionChangeListener.
    LaunchedEffect(typewriterMode, content, cmtColorInt) {
        val editText = editorRef.value ?: return@LaunchedEffect
        val editable = editText.editableText ?: return@LaunchedEffect
        applyTypewriterDimming(editable, content, editText.selectionStart, typewriterMode, cmtColorInt)
        if (typewriterMode) (editText as? FlingEditText)?.centerCurrentLine()
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

    val defaultTitle = stringResource(R.string.editor_default_title)
    val readOnlySuffix = stringResource(R.string.editor_title_readonly_suffix)
    val titleText = buildString {
        if (wsDualMode) append("[$wsActive] ")
        append(currentFile?.name ?: defaultTitle)
        if (!fileWritable && !isScratchpad) append(" $readOnlySuffix") else if (dirty) append(" *")
    }
    @Composable
    fun MenuItems() {
        // ⌨ Command mode (mirrors Web's top-of-menu entry: Esc / Alt+C)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_command_mode), fontFamily = FontFamily.Monospace) },
            onClick = { showMenu = false; cmdMode = !cmdMode },
            trailingIcon = { Text(stringResource(R.string.editor_menu_command_mode_hint, keyCmdMode), style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_settings), fontFamily = FontFamily.Monospace) },
            onClick = { showMenu = false; onNavigateSettings() }
        )
        HorizontalDivider()
        // — Save —
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_save), fontFamily = FontFamily.Monospace) },
            enabled = dirty && fileWritable,
            onClick = { showMenu = false; vm.saveFile() },
            trailingIcon = { Text(keySave, style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_save_as), fontFamily = FontFamily.Monospace) },
            enabled = currentFile != null,
            onClick = {
                showMenu = false
                val defaultName = if (isScratchpad) "scratchpad.txt"
                    else currentFile?.name ?: "untitled.txt"
                saveAsLauncher.launch(defaultName)
            }
        )
        HorizontalDivider()
        // — View —
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_toc), fontFamily = FontFamily.Monospace) },
            enabled = toc.isNotEmpty(),
            onClick = { showMenu = false; showToc = true },
            trailingIcon = { Text(keyToc, style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_dark, darkPref), fontFamily = FontFamily.Monospace) },
            onClick = {
                showMenu = false
                vm.setDarkModePreference(when (darkPref) { "auto" -> "yes"; "yes" -> "no"; else -> "auto" })
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_line_numbers), fontFamily = FontFamily.Monospace) },
            onClick = { showMenu = false; vm.setLineNumbersEnabled(!lineNumbersEnabled) },
            trailingIcon = { Text(keyLineNumbers, style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_typewriter), fontFamily = FontFamily.Monospace) },
            onClick = { showMenu = false; typewriterMode = !typewriterMode },
            trailingIcon = { Text(keyTypewriter, style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_distraction_free), fontFamily = FontFamily.Monospace) },
            onClick = { showMenu = false; distractionFree = true }
        )
        // The TopAppBar (and its Save button) is hidden in distraction-free mode,
        // so offer Save here instead — only entry point for saving without leaving it.
        if (distractionFree) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_menu_save), fontFamily = FontFamily.Monospace) },
                enabled = dirty && fileWritable,
                onClick = { showMenu = false; vm.saveFile() },
                trailingIcon = { Text(keySave, style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant) }
            )
        }
        DropdownMenuItem(
            text = { Text(
                if (wsDualMode) stringResource(R.string.editor_menu_workspace_switch, wsActive, if (wsActive == 1) 2 else 1)
                else stringResource(R.string.editor_menu_workspace_open),
                fontFamily = FontFamily.Monospace
            ) },
            onClick = {
                val offset = editorRef.value?.selectionStart ?: 0
                showMenu = false; vm.toggleWorkspace(offset)
            }
        )
        HorizontalDivider()
        // — Search —
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_find), fontFamily = FontFamily.Monospace) },
            onClick = { showMenu = false; showFind = true },
            trailingIcon = { Text("⌘F", style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_find_replace), fontFamily = FontFamily.Monospace) },
            onClick = { showMenu = false; showFind = true; findReplace = true },
            trailingIcon = { Text("⌘H", style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_goto_line), fontFamily = FontFamily.Monospace) },
            onClick = { showMenu = false; showGotoLine = true; gotoLineValue = "" },
            trailingIcon = { Text("⌘G", style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant) }
        )
        HorizontalDivider()
        // — Format — (mirrors Web's dynamic Format labels, openMenu() in app.js)
        val h1Label = stringResource(R.string.editor_menu_h1)
        val h2Label = stringResource(R.string.editor_menu_h2)
        val h3Label = stringResource(R.string.editor_menu_h3)
        val commentLabel = stringResource(R.string.editor_menu_comment)
        val boldLabel = stringResource(R.string.editor_menu_bold)
        val italicLabel = stringResource(R.string.editor_menu_italic)
        val underlineLabel = stringResource(R.string.editor_menu_underline)
        val strikeLabel = stringResource(R.string.editor_menu_strike)
        DropdownMenuItem(
            text = { Text(
                if (headingMarker.isNotEmpty()) "$headingMarker $h1Label $headingMarker" else h1Label,
                fontFamily = FontFamily.Monospace
            ) },
            enabled = headingMarker.isNotEmpty(),
            onClick = {
                val et = editorRef.value
                if (et != null) applyEditResult(applyHeadingLevel(
                    et.text.toString(), et.selectionStart, et.selectionEnd, headingMarker, 1))
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text(
                if (headingMarker.isNotEmpty())
                    "${headingMarker.repeat(2)} $h2Label ${headingMarker.repeat(2)}" else h2Label,
                fontFamily = FontFamily.Monospace
            ) },
            enabled = headingMarker.isNotEmpty(),
            onClick = {
                val et = editorRef.value
                if (et != null) applyEditResult(applyHeadingLevel(
                    et.text.toString(), et.selectionStart, et.selectionEnd, headingMarker, 2))
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text(
                if (headingMarker.isNotEmpty())
                    "${headingMarker.repeat(3)} $h3Label ${headingMarker.repeat(3)}" else h3Label,
                fontFamily = FontFamily.Monospace
            ) },
            enabled = headingMarker.isNotEmpty(),
            onClick = {
                val et = editorRef.value
                if (et != null) applyEditResult(applyHeadingLevel(
                    et.text.toString(), et.selectionStart, et.selectionEnd, headingMarker, 3))
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text(
                if (commentMarker.isNotEmpty()) "$commentMarker $commentLabel" else commentLabel,
                fontFamily = FontFamily.Monospace
            ) },
            enabled = commentMarker.isNotEmpty(),
            onClick = {
                val et = editorRef.value
                if (et != null) applyEditResult(applyLineMarkerResult(
                    et.text.toString(), et.selectionStart, et.selectionEnd, commentMarker))
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text(
                if (boldMarker.isNotEmpty()) "$boldMarker $boldLabel" else boldLabel,
                fontFamily = FontFamily.Monospace
            ) },
            enabled = boldMarker.isNotEmpty(),
            onClick = {
                val et = editorRef.value
                if (et != null) applyEditResult(applyInlineMarkerResult(
                    et.text.toString(), et.selectionStart, et.selectionEnd, boldMarker))
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text(
                if (italicMarker.isNotEmpty()) "$italicMarker $italicLabel" else italicLabel,
                fontFamily = FontFamily.Monospace
            ) },
            enabled = italicMarker.isNotEmpty(),
            onClick = {
                val et = editorRef.value
                if (et != null) applyEditResult(applyInlineMarkerResult(
                    et.text.toString(), et.selectionStart, et.selectionEnd, italicMarker))
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text(
                if (underlineMarker.isNotEmpty()) "$underlineMarker $underlineLabel" else underlineLabel,
                fontFamily = FontFamily.Monospace
            ) },
            enabled = underlineMarker.isNotEmpty(),
            onClick = {
                val et = editorRef.value
                if (et != null) applyEditResult(applyInlineMarkerResult(
                    et.text.toString(), et.selectionStart, et.selectionEnd, underlineMarker))
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text(
                if (strikethroughMarker.isNotEmpty()) "$strikethroughMarker $strikeLabel" else strikeLabel,
                fontFamily = FontFamily.Monospace
            ) },
            enabled = strikethroughMarker.isNotEmpty(),
            onClick = {
                val et = editorRef.value
                if (et != null) applyEditResult(applyInlineMarkerResult(
                    et.text.toString(), et.selectionStart, et.selectionEnd, strikethroughMarker))
                showMenu = false
            }
        )
        HorizontalDivider()
        // — Document —
        if (BuildConfig.FULL_VERSION) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_menu_writing_stats), fontFamily = FontFamily.Monospace) },
                onClick = { showMenu = false; scope.launch { statsData = vm.getDailyStats(); showStats = true } }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_menu_structure_analysis), fontFamily = FontFamily.Monospace) },
                enabled = toc.isNotEmpty(),
                onClick = { showMenu = false; analyseData = computeSectionAnalysis(toc, content); showAnalyse = true }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_menu_word_frequency), fontFamily = FontFamily.Monospace) },
                onClick = { showMenu = false; scope.launch { wordsData = vm.getWordOccurrences(); showWords = true } }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_file_info), fontFamily = FontFamily.Monospace) },
            enabled = currentFile != null && !isScratchpad,
            onClick = { showMenu = false; showFileInfo = true }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_export_txt), fontFamily = FontFamily.Monospace) },
            onClick = { showMenu = false; exportLauncher.launch(exportFileName("txt")) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_export_md), fontFamily = FontFamily.Monospace) },
            onClick = { showMenu = false; exportLauncher.launch(exportFileName("md")) }
        )
        HorizontalDivider()
        // — App —
        val timerLabel = when {
            !timerActive && timerLastTick == 0L -> stringResource(R.string.editor_menu_timer_start)
            timerActive -> stringResource(R.string.editor_menu_timer_pause, formatTimer(timerRemaining, true).trim())
            else -> stringResource(R.string.editor_menu_timer_resume, formatTimer(timerRemaining, false).trim())
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
                text = { Text(stringResource(R.string.editor_menu_timer_reset), fontFamily = FontFamily.Monospace) },
                onClick = { showMenu = false; vm.timerReset() }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_menu_block_cursor), fontFamily = FontFamily.Monospace) },
            onClick = { showMenu = false; vm.setBlockCursor(!blockCursor) },
            trailingIcon = { Text(
                if (blockCursor) stringResource(R.string.editor_on) else stringResource(R.string.editor_off),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant) }
        )
        // The menu can be taller than the screen (anchored near the top, near a
        // long item list) — without trailing space, the last item lands flush
        // against the bottom edge and the scrollable area ends right at it,
        // making it hard to reach/tap. Empty disabled items give the scroll
        // room to bring it clear of the edge.
        DropdownMenuItem(text = { Text("") }, onClick = {}, enabled = false)
        DropdownMenuItem(text = { Text("") }, onClick = {}, enabled = false)
    }


    Scaffold(
        topBar = {
            if (!distractionFree) TopAppBar(
                title = { Text(titleText, fontFamily = FontFamily.Monospace, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { requestClose() }) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.saveFile() }, enabled = dirty && fileWritable) {
                        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.editor_cd_save))
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.editor_cd_more_options))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.navigationBarsPadding()) {
                            MenuItems()
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (cmdMode) {
                // Command-mode hint bar (mirrors Tcl's ed_bar_center while gui_cmd_mode is on).
                // Shown whenever command mode is active — including distraction-free
                // mode, where the TopAppBar/status bar are otherwise hidden — and
                // raised above the virtual keyboard via imePadding() when it's shown.
                Surface(
                    tonalElevation = 2.dp,
                    color = colorScheme.primaryContainer,
                    modifier = Modifier.imePadding()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                            .navigationBarsPadding()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.editor_cmd_bar_label), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                        for (letter in CMD_ACTION_LETTERS) {
                            val enabled = (letter != 'o' && letter != 'a') || toc.isNotEmpty()
                            Text(
                                "$letter:${stringResource(cmdActionLabelRes(letter))}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onPrimaryContainer.copy(alpha = if (enabled) 1f else 0.4f),
                                modifier = Modifier
                                    .clickable { performCmdAction(letter) }
                                    .padding(horizontal = 6.dp, vertical = 8.dp)
                            )
                        }
                        Text(
                            stringResource(R.string.editor_cmd_bar_cancel_hint, keyCmdMode),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .clickable { cmdMode = false }
                                .padding(horizontal = 6.dp, vertical = 8.dp)
                        )
                    }
                }
            } else if (!distractionFree && !hemingwayActive) {
                Surface(tonalElevation = 2.dp, color = colorScheme.surface) {
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
        }
    ) { padding ->
        // Virtual keyboard space: the content Box's height doesn't shrink for the IME —
        // Scaffold only ever insets it by `padding` (status bar / nav bar). When the IME
        // opens, the keyboard overlaps the bottom of this Box by (ime height - that
        // existing inset). Add exactly that much extra bottom padding to the EditText so
        // the last line can be scrolled up clear of the keyboard, without leaving a
        // visible blank band (which happens if this overshoots) or being unreachable
        // (if omitted entirely).
        val extraBottomPadPx = (WindowInsets.ime.getBottom(density) -
            with(density) { padding.calculateBottomPadding().roundToPx() }).coerceAtLeast(0)
        Box(modifier = Modifier.fillMaxSize().background(editorBg).padding(padding)) {

            Row(modifier = Modifier.fillMaxSize()) {
            if (lineNumbersEnabled) {
                AndroidView(
                    factory = { ctx ->
                        android.widget.TextView(ctx).apply {
                            gravity = Gravity.END or Gravity.TOP
                            isFocusable = false
                            isClickable = false
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        }.also { gutterRef.value = it }
                    },
                    update = { tv ->
                        tv.text = lineNumbersText
                        tv.setTextColor(cmtColorInt)
                        tv.typeface = FontManager.resolveTypeface(vm.fontDirs, fontFamily, if (fontBold) Typeface.BOLD else Typeface.NORMAL)
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
                        tv.setLineSpacing(0f, lineSpacing)
                        tv.setPadding(gutterPadX, effMarginHeightPx, gutterPadX, effMarginHeightPx)
                    },
                    modifier = Modifier.fillMaxHeight().wrapContentWidth()
                )
            }

            // Long-press / right-click text context menu labels — resolved here (composable
            // scope) since AndroidView's `update` lambda below is not a @Composable context
            // and cannot call stringResource() itself.
            val ctxMenuSpellOn = stringResource(R.string.editor_ctx_menu_spellcheck_on)
            val ctxMenuSpellOff = stringResource(R.string.editor_ctx_menu_spellcheck_off)
            val ctxMenuH1 = stringResource(R.string.editor_menu_h1)
            val ctxMenuH2 = stringResource(R.string.editor_menu_h2)
            val ctxMenuH3 = stringResource(R.string.editor_menu_h3)
            val ctxMenuComment = stringResource(R.string.editor_menu_comment)
            val ctxMenuBold = stringResource(R.string.editor_menu_bold)
            val ctxMenuItalic = stringResource(R.string.editor_menu_italic)
            val ctxMenuUnderline = stringResource(R.string.editor_menu_underline)
            val ctxMenuStrike = stringResource(R.string.editor_menu_strike)

            AndroidView(
                factory = { ctx ->
                    FlingEditText(ctx).apply {
                        inputType = BASE_INPUT_TYPE
                        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
                        typeface = FontManager.resolveTypeface(vm.fontDirs, fontFamily, if (fontBold) Typeface.BOLD else Typeface.NORMAL)
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

                        // Keep the line-numbers gutter's scroll position in sync with the editor,
                        // and re-check spelling for newly-visible lines.
                        setOnScrollChangeListener { _, _, scrollY, _, _ ->
                            gutterRef.value?.scrollTo(0, scrollY)
                            spellCheckTick++
                        }

                        // Long-press (or right-click) text menu: the system pre-populates `menu`
                        // with Cut/Copy/Paste/Select all/etc. before onCreateActionMode is called —
                        // we only add formatting items on top. onActionItemClicked is tried first
                        // for our custom IDs; returning false lets the system handle its own items.
                        val contextMenuCallback = object : ActionMode.Callback {
                            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                                contextMenuCreateRef[0](menu)
                                return true
                            }
                            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
                            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                                val handled = contextMenuClickRef[0](item.itemId)
                                if (handled) mode.finish()
                                return handled
                            }
                            override fun onDestroyActionMode(mode: ActionMode) {}
                        }
                        customSelectionActionModeCallback = contextMenuCallback
                        customInsertionActionModeCallback = contextMenuCallback
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
                    val newStyle = EditorStyle(editorFgInt, editorBgInt, fontSize.toFloat(), lineSpacing,
                                               effMarginWidthPx, effMarginHeightPx, effMarginHeightPx + extraBottomPadPx,
                                               fileWritable || isScratchpad, hemingwayActive,
                                               fontFamily, fontBold, blockCursor, cursorBlink, spellCheckEnabled)
                    if (lastStyle[0] != newStyle) {
                        editText.setTextColor(newStyle.fgColor)
                        if (newStyle.fontFamily != lastStyle[0].fontFamily || newStyle.fontBold != lastStyle[0].fontBold) {
                            editText.typeface = FontManager.resolveTypeface(vm.fontDirs, newStyle.fontFamily,
                                if (newStyle.fontBold) Typeface.BOLD else Typeface.NORMAL)
                        }
                        editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, newStyle.fontSizeSp)
                        editText.setLineSpacing(0f, newStyle.lineSpacingMult)
                        editText.setPadding(newStyle.padX, newStyle.padY, newStyle.padX, newStyle.padBottom)
                        if (newStyle.spellCheckEnabled != lastStyle[0].spellCheckEnabled) {
                            editText.inputType = if (newStyle.spellCheckEnabled) BASE_INPUT_TYPE
                                else BASE_INPUT_TYPE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        }
                        editText.keyListener = if (newStyle.writable) originalKeyListener[0] else null
                        editText.filters = if (newStyle.hemingway && newStyle.writable)
                            arrayOf(hemingwayFilter) else emptyArray()
                        // Block cursor: Android has no native -blockcursor like Tk; FlingEditText
                        // draws it itself in onDraw — sized to the exact glyph under the caret
                        // (correct for proportional fonts) and inverting that glyph (text drawn in
                        // the background colour over a foreground-filled block, like Tk's
                        // -blockcursor + -insertbackground $fg). Works on all API levels.
                        editText.setBlockCursor(newStyle.blockCursor, newStyle.fgColor, newStyle.bgColor)
                        editText.setCursorBlink(newStyle.cursorBlink)
                        lastStyle[0] = newStyle
                    }

                    // Long-press / right-click text menu — formatting items mirror the "≡"
                    // menu's Format section (markers configurable in Settings > Display).
                    contextMenuCreateRef[0] = { menu ->
                        menu.add(Menu.NONE, MENU_ID_SPELLCHECK, 100,
                            if (spellCheckEnabled) ctxMenuSpellOn else ctxMenuSpellOff)
                        if (headingMarker.isNotEmpty()) {
                            menu.add(Menu.NONE, MENU_ID_H1, 101, ctxMenuH1)
                            menu.add(Menu.NONE, MENU_ID_H2, 102, ctxMenuH2)
                            menu.add(Menu.NONE, MENU_ID_H3, 103, ctxMenuH3)
                        }
                        if (commentMarker.isNotEmpty()) menu.add(Menu.NONE, MENU_ID_COMMENT, 104, ctxMenuComment)
                        if (boldMarker.isNotEmpty()) menu.add(Menu.NONE, MENU_ID_BOLD, 105, ctxMenuBold)
                        if (italicMarker.isNotEmpty()) menu.add(Menu.NONE, MENU_ID_ITALIC, 106, ctxMenuItalic)
                        if (underlineMarker.isNotEmpty()) menu.add(Menu.NONE, MENU_ID_UNDERLINE, 107, ctxMenuUnderline)
                        if (strikethroughMarker.isNotEmpty()) menu.add(Menu.NONE, MENU_ID_STRIKE, 108, ctxMenuStrike)
                    }
                    contextMenuClickRef[0] = clickHandler@{ itemId ->
                        val et = editorRef.value ?: return@clickHandler false
                        when (itemId) {
                            MENU_ID_SPELLCHECK -> { vm.setSpellCheckEnabled(!spellCheckEnabled); true }
                            MENU_ID_H1 -> { applyEditResult(applyHeadingLevel(
                                et.text.toString(), et.selectionStart, et.selectionEnd, headingMarker, 1)); true }
                            MENU_ID_H2 -> { applyEditResult(applyHeadingLevel(
                                et.text.toString(), et.selectionStart, et.selectionEnd, headingMarker, 2)); true }
                            MENU_ID_H3 -> { applyEditResult(applyHeadingLevel(
                                et.text.toString(), et.selectionStart, et.selectionEnd, headingMarker, 3)); true }
                            MENU_ID_COMMENT -> { applyEditResult(applyLineMarkerResult(
                                et.text.toString(), et.selectionStart, et.selectionEnd, commentMarker)); true }
                            MENU_ID_BOLD -> { applyEditResult(applyInlineMarkerResult(
                                et.text.toString(), et.selectionStart, et.selectionEnd, boldMarker)); true }
                            MENU_ID_ITALIC -> { applyEditResult(applyInlineMarkerResult(
                                et.text.toString(), et.selectionStart, et.selectionEnd, italicMarker)); true }
                            MENU_ID_UNDERLINE -> { applyEditResult(applyInlineMarkerResult(
                                et.text.toString(), et.selectionStart, et.selectionEnd, underlineMarker)); true }
                            MENU_ID_STRIKE -> { applyEditResult(applyInlineMarkerResult(
                                et.text.toString(), et.selectionStart, et.selectionEnd, strikethroughMarker)); true }
                            else -> false
                        }
                    }

                    // Update key handler with current compose state values
                    keyHandlerRef[0] = handler@{ keyCode, event ->
                        if (event.action != AKeyEvent.ACTION_DOWN) return@handler false
                        val ctrl = event.isCtrlPressed
                        when {
                            // Modal command mode (mirrors Tcl/Web "Echap"): the next key performs
                            // a one-letter action, then cmd mode exits — every key is swallowed.
                            cmdMode -> {
                                performCmdAction(event.unicodeChar.toChar().lowercaseChar())
                                true
                            }
                            // Hemingway restrictions only apply while typewriter mode is active
                            // (mirrors Tcl: `$::typewriter_mode && $::cfg_hemingway_mode`).
                            hemingwayActive && (keyCode == AKeyEvent.KEYCODE_DEL ||
                                keyCode == AKeyEvent.KEYCODE_FORWARD_DEL ||
                                keyCode == AKeyEvent.KEYCODE_DPAD_LEFT ||
                                keyCode == AKeyEvent.KEYCODE_DPAD_RIGHT ||
                                keyCode == AKeyEvent.KEYCODE_DPAD_UP ||
                                keyCode == AKeyEvent.KEYCODE_DPAD_DOWN) -> true
                            hemingwayActive && ctrl && keyCode == AKeyEvent.KEYCODE_Z -> true
                            // Ctrl+T — toggle typewriter mode (mirrors Tcl Ctrl+T)
                            typewriterBinding?.matches(keyCode, event) == true -> {
                                typewriterMode = !typewriterMode; true
                            }
                            // Ctrl+L — toggle line numbers gutter (mirrors Tcl/web Ctrl+L)
                            lineNumbersBinding?.matches(keyCode, event) == true -> {
                                vm.setLineNumbersEnabled(!lineNumbersEnabled); true
                            }
                            tocBinding != null && tocBinding.matches(keyCode, event) && toc.isNotEmpty() -> {
                                showToc = true; true
                            }
                            // Shift+Ctrl+F11 — toggle pinned TOC (mirrors Tcl key_toc_pinned)
                            ctrl && event.isShiftPressed && keyCode == AKeyEvent.KEYCODE_F11 -> {
                                tocPinned = !tocPinned
                                if (tocPinned) showToc = true
                                true
                            }
                            saveBinding?.matches(keyCode, event) == true -> { if (fileWritable) vm.saveFile(); true }
                            findBinding?.matches(keyCode, event) == true -> { showFind = true; true }
                            replaceBinding?.matches(keyCode, event) == true -> { showFind = true; findReplace = true; true }
                            gotoBinding?.matches(keyCode, event) == true -> { showGotoLine = true; true }
                            closeBinding?.matches(keyCode, event) == true -> { requestClose(); true }
                            // Ctrl+D — cycle dark mode preference (auto -> yes -> no -> auto)
                            ctrl && keyCode == AKeyEvent.KEYCODE_D -> {
                                vm.setDarkModePreference(when (darkPref) { "auto" -> "yes"; "yes" -> "no"; else -> "auto" })
                                true
                            }
                            // Alt+T — timer start/pause/resume (mirrors Tcl/web Alt+T)
                            event.isAltPressed && keyCode == AKeyEvent.KEYCODE_T -> {
                                when {
                                    !timerActive && timerLastTick == 0L -> vm.timerStart()
                                    timerActive -> vm.timerPause()
                                    else -> vm.timerResume()
                                }
                                true
                            }
                            // Alt+M — show the "≡" menu (mirrors web's Alt+M)
                            event.isAltPressed && keyCode == AKeyEvent.KEYCODE_M -> {
                                showMenu = true; true
                            }
                            !ctrl && keyCode == AKeyEvent.KEYCODE_ESCAPE && showFind -> {
                                showFind = false; findQuery = ""; findReplace = false; true
                            }
                            // Escape — close distraction-free mode
                            !ctrl && keyCode == AKeyEvent.KEYCODE_ESCAPE && distractionFree -> {
                                distractionFree = false; true
                            }
                            // Activate modal command mode (mirrors Tcl/Web "Echap")
                            cmdModeBinding?.matches(keyCode, event) == true -> { cmdMode = true; true }
                            // Alt+C — hardcoded alternative to enter modal command mode (mirrors
                            // Web, where Alt+C is a fixed alternative to Escape). Useful on
                            // keyboards without an Escape key. Checked last: if a `key_*` binding
                            // above is also configured to Alt-C, that action wins for this combo —
                            // same first-match precedence as any two `key_*` bindings that collide.
                            event.isAltPressed && keyCode == AKeyEvent.KEYCODE_C -> { cmdMode = true; true }
                            else -> false
                        }
                    }

                    // Typewriter mode: re-dim + re-centre on cursor movement alone (no text change).
                    editText.selectionChangeListener = {
                        if (typewriterMode) {
                            editText.editableText?.let {
                                applyTypewriterDimming(it, content, editText.selectionStart, true, cmtColorInt)
                            }
                            editText.centerCurrentLine()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().weight(1f)
            )
            }

            if (distractionFree) {
                IconButton(
                    onClick = { distractionFree = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                ) {
                    Icon(Icons.Filled.FullscreenExit, contentDescription = stringResource(R.string.editor_cd_exit_distraction_free),
                         tint = editorFg.copy(alpha = 0.35f))
                }
                // "≡" menu — also reachable here so cmd-mode `m` / Alt+M still
                // shows the menu while the TopAppBar is hidden.
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 4.dp)) {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.editor_cd_menu),
                             tint = editorFg.copy(alpha = 0.35f))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.navigationBarsPadding()) {
                        MenuItems()
                    }
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
                            }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.editor_cd_previous)) }
                            IconButton(onClick = {
                                if (searchMatches.isNotEmpty())
                                    findMatchIndex = (findMatchIndex + 1) % searchMatches.size
                            }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.editor_cd_next)) }
                            IconButton(onClick = { findReplace = !findReplace }) {
                                Icon(
                                    Icons.Filled.SwapVert,
                                    contentDescription = stringResource(R.string.editor_cd_toggle_replace),
                                    tint = if (findReplace) colorScheme.primary else colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { showFind = false; findQuery = ""; findReplace = false }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_cd_close_search))
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
                                            when {
                                                event.key == Key.Escape -> { showFind = false; findReplace = false; findQuery = ""; true }
                                                event.key == Key.Enter && event.isCtrlPressed -> { doReplaceAll(); true }
                                                event.key == Key.Enter -> { doReplaceOne(); true }
                                                else -> false
                                            }
                                        }
                                )
                                TextButton(
                                    onClick = { doReplaceOne() },
                                    enabled = searchMatches.isNotEmpty()
                                ) { Text(stringResource(R.string.editor_btn_replace), style = MaterialTheme.typography.bodySmall) }
                                TextButton(
                                    onClick = { doReplaceAll() },
                                    enabled = findQuery.length >= 2
                                ) { Text(stringResource(R.string.editor_btn_replace_all), style = MaterialTheme.typography.bodySmall) }
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
                Text(stringResource(R.string.editor_toc_title), style = MaterialTheme.typography.titleMedium,
                     modifier = Modifier.weight(1f))
                IconButton(onClick = { tocPinned = !tocPinned }) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = if (tocPinned) stringResource(R.string.editor_cd_unpin_toc) else stringResource(R.string.editor_cd_pin_toc),
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


    if (BuildConfig.FULL_VERSION && showStats) {
        AlertDialog(
            onDismissRequest = { showStats = false },
            title = { Text(stringResource(R.string.editor_dialog_stats_title), fontFamily = FontFamily.Monospace) },
            text = {
                if (statsData.isEmpty()) {
                    Text(stringResource(R.string.editor_dialog_stats_empty), fontFamily = FontFamily.Monospace,
                         color = colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn {
                        itemsIndexed(statsData) { _, entry ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(entry.date, fontFamily = FontFamily.Monospace,
                                     style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.editor_words_abbrev, entry.words), fontFamily = FontFamily.Monospace,
                                     style = MaterialTheme.typography.bodyMedium,
                                     color = colorScheme.primary)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStats = false }) { Text(stringResource(R.string.editor_btn_close)) } }
        )
    }

    if (showFileInfo && currentFile != null) {
        val entry = currentFile!!
        val f = File(entry.path)
        val sizeStr = when {
            f.length() < 1024L -> "${f.length()} B"
            f.length() < 1024L * 1024 -> "${"%.1f".format(f.length() / 1024.0)} KB"
            else -> "${"%.1f".format(f.length() / (1024.0 * 1024))} MB"
        }
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(f.lastModified()))
        AlertDialog(
            onDismissRequest = { showFileInfo = false },
            title = {
                Text(
                    entry.name,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.editor_dialog_file_info_path), style = MaterialTheme.typography.labelSmall,
                         color = colorScheme.primary)
                    Text(entry.path, fontFamily = FontFamily.Monospace,
                         style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text(stringResource(R.string.editor_dialog_file_info_size, sizeStr), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.editor_dialog_file_info_modified, dateStr), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.editor_dialog_file_info_words, wordCount), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.editor_dialog_file_info_characters, content.length), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showFileInfo = false }) { Text(stringResource(R.string.editor_btn_close)) }
            }
        )
    }

    if (timerAlertPending) {
        AlertDialog(
            onDismissRequest = { vm.dismissTimerAlert() },
            title = { Text(stringResource(R.string.editor_dialog_timer_finished_title), fontFamily = FontFamily.Monospace) },
            text = { Text(stringResource(R.string.editor_dialog_timer_finished_text), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { Button(onClick = { vm.dismissTimerAlert() }) { Text(stringResource(R.string.editor_btn_ok)) } }
        )
    }

    if (showReadOnlyAlert) {
        AlertDialog(
            onDismissRequest = { showReadOnlyAlert = false },
            title = { Text(stringResource(R.string.editor_dialog_readonly_title), fontFamily = FontFamily.Monospace) },
            text = {
                Text(stringResource(R.string.editor_dialog_readonly_text),
                     style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = { TextButton(onClick = { showReadOnlyAlert = false }) { Text(stringResource(R.string.editor_btn_ok)) } }
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.editor_dialog_discard_title)) },
            text = { Text(stringResource(R.string.editor_dialog_discard_text)) },
            confirmButton = {
                TextButton(
                    onClick = { showDiscardConfirm = false; doBack() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.editor_btn_discard)) }
            },
            dismissButton = { TextButton(onClick = { showDiscardConfirm = false }) { Text(stringResource(R.string.editor_btn_stay)) } }
        )
    }

    if (showSaveConfirm) {
        AlertDialog(
            onDismissRequest = { showSaveConfirm = false },
            title = { Text(stringResource(R.string.editor_dialog_save_title)) },
            text = { Text(stringResource(R.string.editor_dialog_save_text)) },
            confirmButton = {
                TextButton(onClick = { showSaveConfirm = false; vm.saveFile(); doBack() }) { Text(stringResource(R.string.editor_btn_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showSaveConfirm = false; doBack() }) { Text(stringResource(R.string.editor_btn_dont_save)) }
                    TextButton(onClick = { showSaveConfirm = false }) { Text(stringResource(R.string.editor_btn_cancel)) }
                }
            }
        )
    }

    if (BuildConfig.FULL_VERSION && showWords) {
        AlertDialog(
            onDismissRequest = { showWords = false },
            title = { Text(stringResource(R.string.editor_dialog_word_freq_title), fontFamily = FontFamily.Monospace) },
            text = {
                if (wordsData.isEmpty()) {
                    Text(stringResource(R.string.editor_dialog_word_freq_empty), fontFamily = FontFamily.Monospace,
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
            confirmButton = { TextButton(onClick = { showWords = false }) { Text(stringResource(R.string.editor_btn_close)) } }
        )
    }

    if (showGotoLine) {
        val lineCount = remember(content) { content.lines().size }
        AlertDialog(
            onDismissRequest = { showGotoLine = false },
            title = { Text(stringResource(R.string.editor_dialog_goto_line_title), fontFamily = FontFamily.Monospace) },
            text = {
                OutlinedTextField(
                    value = gotoLineValue,
                    onValueChange = { gotoLineValue = it },
                    label = { Text(stringResource(R.string.editor_dialog_goto_line_label, lineCount)) },
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
                }) { Text(stringResource(R.string.editor_btn_go)) }
            },
            dismissButton = { TextButton(onClick = { showGotoLine = false }) { Text(stringResource(R.string.editor_btn_cancel)) } }
        )
    }

    if (BuildConfig.FULL_VERSION && showAnalyse && analyseData.isNotEmpty()) {
        val totalWords = remember(analyseData) { analyseData.sumOf { it.words } }
        AlertDialog(
            onDismissRequest = { showAnalyse = false },
            title = { Text(stringResource(R.string.editor_dialog_structure_title), fontFamily = FontFamily.Monospace) },
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
                                    text = stringResource(R.string.editor_words_abbrev, sec.words),
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
                            Text(stringResource(R.string.editor_dialog_structure_total), fontFamily = FontFamily.Monospace,
                                 style = MaterialTheme.typography.bodySmall,
                                 color = colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.editor_words_abbrev, totalWords), fontFamily = FontFamily.Monospace,
                                 style = MaterialTheme.typography.bodySmall,
                                 color = colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAnalyse = false }) { Text(stringResource(R.string.editor_btn_close)) } }
        )
    }
}
