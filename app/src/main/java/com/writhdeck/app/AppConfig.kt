package com.writhdeck.app

import kotlin.math.roundToInt

/** Editor font choice: a display label paired with an Android generic font-family alias.
 *  These aliases are resolved by `Typeface.create(familyName, style)` against the system's
 *  `fonts.xml` — no font files need to be embedded in the APK. */
data class EditorFont(val label: String, val familyName: String)

val EDITOR_FONTS = listOf(
    EditorFont("Monospace", "monospace"),
    EditorFont("Monospace (serif)", "serif-monospace"),
    EditorFont("Sans serif", "sans-serif"),
    EditorFont("Sans serif condensed", "sans-serif-condensed"),
    EditorFont("Sans serif light", "sans-serif-light"),
    EditorFont("Sans serif medium", "sans-serif-medium"),
    EditorFont("Serif", "serif"),
    EditorFont("Casual", "casual")
)

/** Converts a Tcl `string match`-style glob (`*`, `?`, `[...]`) to a case-insensitive
 *  whole-string [Regex]. Used by [AppConfig.matchesBrowserFilter]. */
private fun globToRegex(pattern: String): Regex {
    val sb = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            '[' -> {
                val close = pattern.indexOf(']', i + 1)
                if (close < 0) {
                    sb.append("\\[")
                } else {
                    sb.append('[').append(pattern.substring(i + 1, close)).append(']')
                    i = close
                }
            }
            '.', '+', '^', '$', '(', ')', '{', '}', '|', '\\' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        i++
    }
    return Regex("^$sb$", RegexOption.IGNORE_CASE)
}

data class ThemeColors(
    val bg: String = "#1a1a1a",
    val fg: String = "#e8e8e8",
    val headingColor: String = "#c8a060",
    val commentColor: String = "#555555",
    val markupColor: String = ""
)

data class AppConfig(
    val scheme: String = "default",
    val androidDarkMode: String = "auto",
    val marginWidth: Int = 16,
    val marginHeight: Int = 16,
    val headingMarker: String = "=",
    val markdownHeadings: Boolean = true,
    val commentMarker: String = "%",
    val boldMarker: String = "**",
    val italicMarker: String = "//",
    val underlineMarker: String = "__",
    val strikethroughMarker: String = "--",
    val timerType: String = "countdown",
    val timerDuration: Int = 25,
    val timerSound: Boolean = false,
    val timerAlert: Boolean = false,
    val chronoShow: Boolean = false,
    val wordGoal: Int = 0,
    val keySave: String = "Control-s",
    val keyFind: String = "Control-f",
    val keyReplace: String = "Control-h",
    val keyGoto: String = "Control-g",
    val keyClose: String = "Control-q",
    val keyToc: String = "F11",
    val keyTypewriter: String = "Control-t",
    val keyLineNumbers: String = "Control-l",
    val keyCmdMode: String = "Escape",
    val docsCustomDir: String = "",
    val activeProfile: String = "default",
    val profileNames: List<String> = listOf("default", "novel"),
    val customSchemes: Map<String, SchemeColors> = emptyMap(),
    val fontSize: Int = 16,
    val fontFamily: String = "monospace",
    val fontBold: Boolean = false,
    val blockCursor: Boolean = false,
    val autosaveEnabled: Boolean = true,
    val autosaveInterval: Int = 1,
    val statusLeft: String = "ws filename dirty",
    val statusCenter: String = "words",
    val statusRight: String = "timer",
    val hemingwayMode: Boolean = false,
    val lineNumbers: Boolean = false,
    val lineSpacing: Float = 1.5f,
    val browserFilter: String = "*.txt *.t2t *.md *.ini",
    val browserShowAll: Boolean = false,
    val browserSubdirs: Boolean = true,
    val spellCheckEnabled: Boolean = true,
    // "system" = let the spell checker service pick its own language (matches the
    // active keyboard/locale); otherwise an explicit BCP-47 tag (e.g. "en-US", "fr-FR")
    // from one of the spell checker's enabled subtypes.
    val spellCheckLanguage: String = "system"
) {
    /** True if [filename] passes [browserFilter] (or [browserShowAll] bypasses it).
     *  Mirrors the Tcl desktop's `list-docs` filtering: an empty filter or
     *  browser_show_all=yes shows everything; otherwise glob-match (case-insensitive,
     *  `*`/`?`/`[...]`) against each space-separated pattern. */
    fun matchesBrowserFilter(filename: String): Boolean {
        if (browserShowAll) return true
        val patterns = browserFilter.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (patterns.isEmpty()) return true
        return patterns.any { globToRegex(it).matches(filename) }
    }

    fun schemeColors(): SchemeColors =
        customSchemes[scheme] ?: BUILTIN_SCHEMES[scheme] ?: BUILTIN_SCHEMES["default"]!!

    fun themeColors(useDark: Boolean): ThemeColors {
        val c = schemeColors()
        return if (useDark) ThemeColors(bg = c.bg, fg = c.fg, headingColor = c.heading, commentColor = c.comment, markupColor = c.markup)
               else         ThemeColors(bg = c.bgAlt, fg = c.fgAlt, headingColor = c.headingAlt, commentColor = c.commentAlt, markupColor = c.markupAlt)
    }

    fun timerDurationSecs(): Int = timerDuration * 60
}

object IniParser {

    /** Top-level section names — disambiguates a `[name]` header seen while inside
     *  `[profiles]`/`[schemes]`: if `name` is one of these, it ends the profiles/schemes
     *  block (a real top-level section) rather than naming a profile/scheme. Union of the
     *  Tcl and web `TOPLEVEL` sets, for cross-version compatibility. */
    private val TOPLEVEL_SECTIONS = setOf(
        "editor", "behaviour", "keys", "timer", "misc", "status_bar",
        "display", "web", "tui_colors"
    )

    private val SECTION_RE = Regex("^\\[([\\w][\\w ]*)\\]$")

    /** Keys that moved from global scope to per-profile scope (`[profiles] -> [<name>]`),
     *  aligning Android with the Tcl desktop's profile model (font, line spacing, line
     *  numbers, dark mode and block cursor are all per-profile there too). Used by
     *  [migrateProfileScopedKeys] to migrate existing `.ini` files written by older
     *  versions of [write], which had these keys in `[behaviour]`/`[misc]`. */
    private val PROFILE_SCOPED_KEYS = setOf(
        "font_size", "font_family", "font_bold", "line_spacing", "line_numbers", "android_dark_mode", "block_cursor"
    )

    /** `line_spacing` uses incompatible units across versions: the Tcl desktop stores a
     *  percentage (100 = normal line height; `extra_px = lineHeight*(v-100)/100`), while
     *  Android/web store a `setLineSpacing`/CSS `line-height` multiplier (default 1.5).
     *  The two conventions differ by a factor of 100. INI values >= 10 are assumed to use
     *  the percentage convention (Tcl, or an already-migrated Android/web file) and are
     *  divided by 100; smaller values are pre-conversion Android/web files and are used
     *  as-is. [write]/[lineSpacingToIni] always emit the percentage convention going
     *  forward, so a stale value self-heals on the next save. */
    fun lineSpacingFromIni(raw: Float): Float = if (raw >= 10f) raw / 100f else raw
    fun lineSpacingToIni(value: Float): Int = (value * 100f).roundToInt()

    /** One-time migration from the legacy Android `.ini` dialect — where `= profile: <name> =`
     *  / `= scheme: <name> =` lines were the REAL section markers and `active_profile`
     *  selected the active profile — to the `[section]`-bracket dialect shared with the Tcl
     *  and web versions: `[profiles]`/`[schemes]` containers holding named `[<name>]`
     *  sub-sections, and `profile = <name>` inside `[editor]`. Returns [text] unchanged if no
     *  legacy markers are found. Called from `parse()` and once from `initApp()` (which
     *  persists the migrated text to disk so later `patchKeys`/`patchProfileKey`/etc. calls
     *  only ever see the new dialect). */
    fun migrateLegacyFormat(text: String): String {
        val legacyHeader = Regex("^=\\s*(profile|scheme):\\s*(\\w+)\\s*=$")
        if (text.lines().none { legacyHeader.matches(it.trim()) }) return text

        val result = mutableListOf<String>()
        var profilesInserted = false
        var schemesInserted = false
        for (raw in text.lines()) {
            val trimmed = raw.trim()
            val m = legacyHeader.find(trimmed)
            if (m != null) {
                val (kind, name) = m.destructured
                if (kind == "profile" && !profilesInserted) {
                    result.add("[profiles]"); result.add(""); profilesInserted = true
                }
                if (kind == "scheme" && !schemesInserted) {
                    result.add("[schemes]"); result.add(""); schemesInserted = true
                }
                result.add("[$name]")
                continue
            }
            val activeProfileKey = Regex("^(\\s*)active_profile(\\s*=.*)$").find(raw)
            if (activeProfileKey != null) {
                result.add("${activeProfileKey.groupValues[1]}profile${activeProfileKey.groupValues[2]}")
                continue
            }
            result.add(raw)
        }
        return result.joinToString("\n").trimEnd() + "\n"
    }

    /** One-time migration for keys in [PROFILE_SCOPED_KEYS]: these used to be global
     *  settings (`[behaviour]`/`[misc]`) but are now per-profile (`[profiles] -> [<name>]`),
     *  matching the Tcl desktop. If any of these keys is found at global scope, its value is
     *  copied into every `[profiles] -> [<name>]` sub-section that doesn't already define it,
     *  and the global line is removed. Returns [text] unchanged if none of these keys exist
     *  at global scope (fresh installs, or files already migrated). Assumes [text] is already
     *  in the `[section]`-bracket dialect (call after [migrateLegacyFormat]). */
    fun migrateProfileScopedKeys(text: String): String {
        var section: String? = null
        var currentProfile: String? = null
        val globalValues = mutableMapOf<String, String>()
        val profileNames = mutableListOf<String>()
        val profileHasKey = mutableMapOf<String, MutableSet<String>>()

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#") || line.startsWith("%") || line.isEmpty()) continue
            if (line.startsWith("=") && line.endsWith("=") && line.length > 2) continue

            val secMatch = SECTION_RE.find(line)
            if (secMatch != null) {
                val hdr = secMatch.groupValues[1]
                when {
                    hdr == "schemes" -> { section = "schemes"; currentProfile = null }
                    hdr == "profiles" -> { section = "profiles"; currentProfile = null }
                    section == "profiles" && hdr !in TOPLEVEL_SECTIONS -> {
                        currentProfile = hdr
                        profileNames.add(hdr)
                    }
                    section == "schemes" && hdr !in TOPLEVEL_SECTIONS -> currentProfile = null
                    else -> { section = hdr; currentProfile = null }
                }
                continue
            }

            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim()
            if (key !in PROFILE_SCOPED_KEYS) continue
            val value = line.substring(eq + 1).trim().replace(Regex("\\s+[#%].*$"), "")
            if (currentProfile != null) {
                profileHasKey.getOrPut(currentProfile!!) { mutableSetOf() }.add(key)
            } else if (section != "schemes") {
                globalValues[key] = value
            }
        }

        if (globalValues.isEmpty()) return text

        // Pass 2: drop the global lines for migrated keys.
        section = null
        currentProfile = null
        val withoutGlobals = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            val isCosmetic = line.startsWith("#") || line.startsWith("%") || line.isEmpty() ||
                (line.startsWith("=") && line.endsWith("=") && line.length > 2)
            if (!isCosmetic) {
                val secMatch = SECTION_RE.find(line)
                if (secMatch != null) {
                    val hdr = secMatch.groupValues[1]
                    when {
                        hdr == "schemes" -> { section = "schemes"; currentProfile = null }
                        hdr == "profiles" -> { section = "profiles"; currentProfile = null }
                        section == "profiles" && hdr !in TOPLEVEL_SECTIONS -> currentProfile = hdr
                        section == "schemes" && hdr !in TOPLEVEL_SECTIONS -> currentProfile = null
                        else -> { section = hdr; currentProfile = null }
                    }
                } else {
                    val eq = line.indexOf('=')
                    if (eq > 0) {
                        val key = line.substring(0, eq).trim()
                        if (key in PROFILE_SCOPED_KEYS && currentProfile == null && section != "schemes") {
                            continue
                        }
                    }
                }
            }
            withoutGlobals.add(raw)
        }

        var out = withoutGlobals.joinToString("\n").trimEnd() + "\n"
        for (name in profileNames) {
            for ((key, value) in globalValues) {
                if (key !in (profileHasKey[name] ?: emptySet())) {
                    out = patchProfileKey(out, name, key, value)
                }
            }
        }
        return out
    }

    fun parse(rawText: String): AppConfig {
        val text = migrateProfileScopedKeys(migrateLegacyFormat(rawText))
        val global = mutableMapOf<String, String>()
        val profiles = mutableMapOf<String, MutableMap<String, String>>()
        val schemes = mutableMapOf<String, MutableMap<String, String>>()
        var section: String? = null
        var currentProfile: String? = null
        var currentScheme: String? = null

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#") || line.startsWith("%") || line.isEmpty()) continue
            // `= title =` decorative headings (written by the Tcl/web versions for the F11
            // TOC) are purely cosmetic — they match neither [section] nor key = value.
            if (line.startsWith("=") && line.endsWith("=") && line.length > 2) continue

            // [section] header — [profiles]/[schemes] are containers; a [name] header seen
            // while inside one of those (and not itself a top-level section name) starts a
            // named profile/scheme sub-section. Any other [section] is a top-level section
            // (parsing of its keys is section-agnostic, same as Tcl/JS).
            val secMatch = SECTION_RE.find(line)
            if (secMatch != null) {
                val hdr = secMatch.groupValues[1]
                when {
                    hdr == "schemes" -> { section = "schemes"; currentScheme = null; currentProfile = null }
                    hdr == "profiles" -> { section = "profiles"; currentProfile = null; currentScheme = null }
                    section == "schemes" && hdr !in TOPLEVEL_SECTIONS -> currentScheme = hdr
                    section == "profiles" && hdr !in TOPLEVEL_SECTIONS -> currentProfile = hdr
                    else -> { section = hdr; currentScheme = null; currentProfile = null }
                }
                continue
            }

            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            value = value.replace(Regex("\\s+[#%].*$"), "")
            if (key.isEmpty()) continue

            when {
                currentScheme  != null -> schemes.getOrPut(currentScheme!!)  { mutableMapOf() }[key] = value
                currentProfile != null -> profiles.getOrPut(currentProfile!!) { mutableMapOf() }[key] = value
                else                   -> global[key] = value
            }
        }

        val activeProfile = global["profile"]?.takeIf { it.isNotBlank() } ?: "default"
        val keys = global.toMutableMap().also { it.putAll(profiles[activeProfile] ?: emptyMap()) }

        fun bool(k: String, def: Boolean): Boolean {
            val v = keys[k]?.lowercase() ?: return def
            return v == "yes" || v == "1" || v == "true" || v == "on"
        }
        fun int(k: String, def: Int): Int = keys[k]?.toIntOrNull() ?: def
        // "0" is the desktop convention for "marker disabled" (round-trips an empty value through hand-edited INI files)
        fun marker(k: String, def: String): String = keys[k]?.let { if (it == "0") "" else it } ?: def
        fun str(k: String, def: String): String = keys[k]?.takeIf { it.isNotBlank() } ?: def

        val customSchemes: Map<String, SchemeColors> = schemes.mapValues { (name, map) ->
            val base = BUILTIN_SCHEMES[name] ?: BUILTIN_SCHEMES["default"]!!
            schemeFromMap(map, base)
        }

        return AppConfig(
            scheme           = str("scheme", "default"),
            androidDarkMode  = str("android_dark_mode", "auto").let {
                if (it == "auto" || it == "yes" || it == "no") it else "auto"
            },
            marginWidth      = int("margin_width", 16).coerceAtLeast(0),
            marginHeight     = int("margin_height", 16).coerceAtLeast(0),
            headingMarker    = str("heading_marker", "="),
            markdownHeadings = bool("markdown_headings", true),
            commentMarker    = marker("comment_marker", "%"),
            boldMarker       = marker("bold_marker", "**"),
            italicMarker     = marker("italic_marker", "//"),
            underlineMarker  = marker("underline_marker", "__"),
            strikethroughMarker = marker("strikethrough_marker", "--"),
            timerType        = str("timer_type", "countdown").let {
                if (it == "stopwatch") "stopwatch" else "countdown"
            },
            timerDuration    = int("timer_duration", 25).coerceAtLeast(1),
            timerSound       = bool("timer_sound", false),
            timerAlert       = bool("timer_alert", false),
            chronoShow       = bool("chrono_show", false),
            wordGoal         = int("word_goal", 0).coerceAtLeast(0),
            keySave          = str("key_save", "Control-s"),
            keyFind          = str("key_find", "Control-f"),
            keyReplace       = str("key_replace", "Control-h"),
            keyGoto          = str("key_goto", "Control-g"),
            keyClose         = str("key_close", "Control-q"),
            keyToc           = str("key_toc", "F11"),
            keyTypewriter    = str("key_typewriter", "Control-t"),
            keyLineNumbers   = str("key_line_numbers", "Control-l"),
            keyCmdMode       = str("key_cmd_mode", "Escape"),
            docsCustomDir    = keys["docs_dir"] ?: "",
            activeProfile    = activeProfile,
            profileNames     = (profiles.keys + activeProfile).toSortedSet().toList(),
            customSchemes    = customSchemes,
            fontSize         = int("font_size", 16).coerceIn(10, 32),
            fontFamily       = str("font_family", "monospace").let {
                if (it in EDITOR_FONTS.map { f -> f.familyName }) it else "monospace"
            },
            fontBold         = bool("font_bold", false),
            blockCursor      = bool("block_cursor", false),
            autosaveEnabled  = bool("autosave_enabled", true),
            autosaveInterval = int("autosave_interval", 1).coerceAtLeast(1),
            statusLeft       = str("status_left",   "ws filename dirty"),
            statusCenter     = str("status_center", "words"),
            statusRight      = str("status_right",  "timer"),
            hemingwayMode    = bool("hemingway_mode", false),
            lineNumbers      = bool("line_numbers", false),
            lineSpacing      = (keys["line_spacing"]?.toFloatOrNull()?.let(::lineSpacingFromIni) ?: 1.5f).coerceIn(0.8f, 3.0f),
            // Not via str(): an empty filter is a valid "show all" state, distinct from default
            browserFilter    = keys["browser_filter"] ?: "*.txt *.t2t *.md *.ini",
            browserShowAll   = bool("browser_show_all", false),
            browserSubdirs   = bool("browser_subdirs", true),
            spellCheckEnabled = bool("spell_check", true),
            spellCheckLanguage = str("spell_check_language", "system")
        )
    }

    fun write(config: AppConfig): String {
        fun bool(b: Boolean) = if (b) "yes" else "no"
        return buildString {
            appendLine("% WrithDeck — configuration")
            appendLine("% Schemes: default solarized gruvbox everforest nord alt01 alt02 retro")
            appendLine()
            appendLine("= Editor =")
            appendLine("[editor]")
            appendLine("profile = ${config.activeProfile}")
            appendLine()
            appendLine("= Behaviour =")
            appendLine("[behaviour]")
            appendLine("cursor_restore = yes")
            appendLine("autosave_enabled = yes")
            appendLine("autosave_interval = 1")
            appendLine("hemingway_mode = ${bool(config.hemingwayMode)}")
            appendLine("% browser_filter: space-separated glob patterns (* ? [...]) for the browser file list")
            appendLine("browser_filter = ${config.browserFilter}")
            appendLine("% browser_show_all: bypass browser_filter and show all files")
            appendLine("browser_show_all = ${bool(config.browserShowAll)}")
            appendLine("% browser_subdirs: scan and browse subfolders inside the documents folder")
            appendLine("browser_subdirs = ${bool(config.browserSubdirs)}")
            appendLine()
            appendLine("= Status bar =")
            appendLine("[status_bar]")
            appendLine("status_left = ${config.statusLeft}")
            appendLine("status_center = ${config.statusCenter}")
            appendLine("status_right = ${config.statusRight}")
            appendLine()
            appendLine("= Timer =")
            appendLine("[timer]")
            appendLine("timer_type = ${config.timerType}")
            appendLine("timer_duration = ${config.timerDuration}")
            appendLine("timer_sound = ${bool(config.timerSound)}")
            appendLine("timer_alert = ${bool(config.timerAlert)}")
            appendLine("chrono_show = ${bool(config.chronoShow)}")
            appendLine()
            appendLine("= Misc =")
            appendLine("[misc]")
            appendLine("% docs_dir: optional absolute path to a custom documents folder; empty = default")
            appendLine("docs_dir = ${config.docsCustomDir}")
            appendLine("spell_check = ${bool(config.spellCheckEnabled)}")
            appendLine("spell_check_language = ${config.spellCheckLanguage}")
            appendLine()
            appendLine("= Keys =")
            appendLine("[keys]")
            appendLine("% Use Tk key names: Control-s, Alt-Return, F11, etc.")
            appendLine("key_save = ${config.keySave}")
            appendLine("key_find = ${config.keyFind}")
            appendLine("key_replace = ${config.keyReplace}")
            appendLine("key_goto = ${config.keyGoto}")
            appendLine("key_close = ${config.keyClose}")
            appendLine("key_toc = ${config.keyToc}")
            appendLine("key_typewriter = ${config.keyTypewriter}")
            appendLine("key_line_numbers = ${config.keyLineNumbers}")
            appendLine("key_cmd_mode = ${config.keyCmdMode}")
            appendLine()
            appendLine("= Profiles =")
            appendLine("[profiles]")
            appendLine()
            appendLine("== default ==")
            appendLine("[default]")
            appendLine("scheme = default")
            appendLine("heading_marker = =")
            appendLine("markdown_headings = yes")
            appendLine("margin_width = 16")
            appendLine("margin_height = 16")
            appendLine("word_goal = 0")
            appendLine("font_size = 16")
            appendLine("font_family = monospace")
            appendLine("font_bold = no")
            appendLine("line_spacing = ${lineSpacingToIni(1.5f)}")
            appendLine("line_numbers = no")
            appendLine("android_dark_mode = auto")
            appendLine("block_cursor = no")
            appendLine()
            appendLine("== novel ==")
            appendLine("[novel]")
            appendLine("scheme = everforest")
            appendLine("heading_marker = =")
            appendLine("markdown_headings = no")
            appendLine("margin_width = 32")
            appendLine("margin_height = 24")
            appendLine("word_goal = 1000")
            appendLine("font_size = 18")
            appendLine("font_family = serif")
            appendLine("font_bold = no")
            appendLine("line_spacing = ${lineSpacingToIni(1.8f)}")
            appendLine("line_numbers = no")
            appendLine("android_dark_mode = auto")
            appendLine("block_cursor = no")
            appendLine()
            appendLine("= Schemes =")
            appendLine("[schemes]")
        } + BUILTIN_SCHEMES.entries.joinToString("") { (name, colors) ->
            schemeToIniSection(name, colors) + "\n"
        } + config.customSchemes.entries
            .filter { it.key !in BUILTIN_SCHEMES }
            .joinToString("") { (name, colors) -> schemeToIniSection(name, colors) + "\n" }
    }

    /** Patch specific key=value pairs in existing INI text, preserving all other content. */
    fun patchKeys(text: String, vararg pairs: Pair<String, String>): String {
        val toSet = pairs.toMap().toMutableMap()
        val found = mutableSetOf<String>()
        val lines = text.lines()

        // Missing global keys must be inserted before [profiles] (or [schemes] if there's
        // no [profiles]) — appending at the very end would land inside the last scheme's
        // [name] sub-section and get silently parsed away as a scheme color key.
        var boundaryIdx = lines.indexOfFirst { it.trim() == "[profiles]" }
        if (boundaryIdx < 0) boundaryIdx = lines.indexOfFirst { it.trim() == "[schemes]" }
        if (boundaryIdx < 0) boundaryIdx = lines.size

        val result = StringBuilder()
        for ((idx, raw) in lines.withIndex()) {
            if (idx == boundaryIdx) {
                val missing = toSet.keys - found
                if (missing.isNotEmpty()) {
                    for (k in missing) result.appendLine("$k = ${toSet[k]}")
                    result.appendLine()
                }
            }
            val line = raw.trim()
            val eq = line.indexOf('=')
            if (eq > 0 && !line.startsWith("#") && !line.startsWith("%")
                       && !line.startsWith("[") && !line.startsWith("=")) {
                val key = line.substring(0, eq).trim()
                if (key in toSet) {
                    result.appendLine("$key = ${toSet[key]}")
                    found.add(key)
                    continue
                }
            }
            result.appendLine(raw)
        }
        if (boundaryIdx == lines.size) {
            val missing = toSet.keys - found
            if (missing.isNotEmpty()) {
                result.appendLine()
                for (k in missing) result.appendLine("$k = ${toSet[k]}")
            }
        }
        return result.toString().trimEnd() + "\n"
    }

    /** Patch a key only within a specific profile's `[profiles] -> [<profile>]` sub-section.
     *  If the section exists but lacks the key, the key is appended at the end of that
     *  section (just before the next `[...]` header or EOF). If the `[<profile>]` section
     *  doesn't exist at all, a new one is inserted right after `[profiles]`. */
    fun patchProfileKey(text: String, profile: String, key: String, value: String): String {
        val lines = text.lines()
        val result = StringBuilder()
        var inProfiles = false
        var inTarget = false
        var patched = false

        fun closeTarget() {
            if (inTarget && !patched) {
                result.appendLine("$key = $value")
                patched = true
            }
        }

        for (raw in lines) {
            val trimmed = raw.trim()
            val m = SECTION_RE.find(trimmed)
            if (m != null) {
                val hdr = m.groupValues[1]
                closeTarget()
                when {
                    hdr == "schemes" -> { inProfiles = false; inTarget = false }
                    hdr == "profiles" -> { inProfiles = true; inTarget = false }
                    inProfiles && hdr !in TOPLEVEL_SECTIONS -> inTarget = (hdr == profile)
                    else -> { inProfiles = false; inTarget = false }
                }
                result.appendLine(raw)
                continue
            }
            if (inTarget && !patched) {
                val eq = trimmed.indexOf('=')
                if (eq > 0 && !trimmed.startsWith("#") && !trimmed.startsWith("%")) {
                    val k = trimmed.substring(0, eq).trim()
                    if (k == key) {
                        result.appendLine("$key = $value")
                        patched = true
                        continue
                    }
                }
            }
            result.appendLine(raw)
        }
        closeTarget()

        if (!patched) {
            // [<profile>] section doesn't exist at all — insert a new one right after [profiles].
            val out = result.toString().lines().toMutableList()
            val profilesIdx = out.indexOfFirst { it.trim() == "[profiles]" }
            if (profilesIdx >= 0) {
                out.addAll(profilesIdx + 1, listOf("[$profile]", "$key = $value", ""))
                return out.joinToString("\n").trimEnd() + "\n"
            }
            result.appendLine()
            result.appendLine("[$profile]")
            result.appendLine("$key = $value")
        }
        return result.toString().trimEnd() + "\n"
    }

    /** True if `[schemes]` contains a `[name]` sub-section. */
    fun hasSchemeSection(text: String, name: String): Boolean {
        var inSchemes = false
        for (line in text.lines()) {
            val hdr = SECTION_RE.find(line.trim())?.groupValues?.get(1) ?: continue
            when {
                hdr == "schemes" -> inSchemes = true
                hdr == "profiles" -> inSchemes = false
                inSchemes && hdr !in TOPLEVEL_SECTIONS -> if (hdr == name) return true
                else -> inSchemes = false
            }
        }
        return false
    }

    /** Remove a profile's `[profiles] -> [name]` sub-section from INI text. */
    fun removeProfileSection(text: String, name: String): String {
        val lines = text.lines()
        val result = mutableListOf<String>()
        var inProfiles = false
        var skipping = false
        for (line in lines) {
            val trimmed = line.trim()
            val hdr = SECTION_RE.find(trimmed)?.groupValues?.get(1)
            if (hdr != null) {
                when {
                    hdr == "profiles" -> { inProfiles = true; skipping = false }
                    hdr == "schemes"  -> { inProfiles = false; skipping = false }
                    inProfiles && hdr !in TOPLEVEL_SECTIONS -> skipping = (hdr == name)
                    else -> { inProfiles = false; skipping = false }
                }
            }
            if (skipping) continue
            result.add(line)
        }
        return result.joinToString("\n").trimEnd() + "\n"
    }

    /** Remove a custom scheme's `[schemes] -> [name]` sub-section from INI text. */
    fun removeSchemeSection(text: String, name: String): String {
        val lines = text.lines()
        val result = mutableListOf<String>()
        var inSchemes = false
        var skipping = false
        for (line in lines) {
            val trimmed = line.trim()
            val hdr = SECTION_RE.find(trimmed)?.groupValues?.get(1)
            if (hdr != null) {
                when {
                    hdr == "schemes" -> { inSchemes = true; skipping = false }
                    hdr == "profiles" -> { inSchemes = false; skipping = false }
                    inSchemes && hdr !in TOPLEVEL_SECTIONS -> skipping = (hdr == name)
                    else -> { inSchemes = false; skipping = false }
                }
            }
            if (skipping) continue
            result.add(line)
        }
        return result.joinToString("\n").trimEnd() + "\n"
    }

    /** Insert or replace a custom scheme section in INI text. */
    fun writeCustomScheme(text: String, name: String, colors: SchemeColors): String =
        removeSchemeSection(text, name).trimEnd() + "\n" + schemeToIniSection(name, colors) + "\n"

    private fun schemeFromMap(map: Map<String, String>, base: SchemeColors) = SchemeColors(
        bg       = map["color_bg"]          ?: base.bg,
        fg       = map["color_fg"]          ?: base.fg,
        bgBar    = map["color_bg_bar"]      ?: base.bgBar,
        fgBar    = map["color_fg_bar"]      ?: base.fgBar,
        bgSel    = map["color_bg_sel"]      ?: base.bgSel,
        heading  = map["color_heading"]     ?: base.heading,
        comment  = map["color_comment"]     ?: base.comment,
        markup   = map["color_markup"]      ?: base.markup,
        bgAlt    = map["color_bg_alt"]      ?: base.bgAlt,
        fgAlt    = map["color_fg_alt"]      ?: base.fgAlt,
        bgBarAlt = map["color_bg_bar_alt"]  ?: base.bgBarAlt,
        fgBarAlt = map["color_fg_bar_alt"]  ?: base.fgBarAlt,
        bgSelAlt = map["color_bg_sel_alt"]  ?: base.bgSelAlt,
        headingAlt  = map["color_heading_alt"]  ?: base.headingAlt,
        commentAlt  = map["color_comment_alt"]  ?: base.commentAlt,
        markupAlt   = map["color_markup_alt"]   ?: base.markupAlt
    )

    private fun schemeToIniSection(name: String, colors: SchemeColors) = buildString {
        appendLine()
        appendLine("[$name]")
        appendLine("color_bg = ${colors.bg}")
        appendLine("color_fg = ${colors.fg}")
        appendLine("color_bg_bar = ${colors.bgBar}")
        appendLine("color_fg_bar = ${colors.fgBar}")
        appendLine("color_bg_sel = ${colors.bgSel}")
        appendLine("color_heading = ${colors.heading}")
        appendLine("color_comment = ${colors.comment}")
        appendLine("color_markup = ${colors.markup}")
        appendLine("color_bg_alt = ${colors.bgAlt}")
        appendLine("color_fg_alt = ${colors.fgAlt}")
        appendLine("color_bg_bar_alt = ${colors.bgBarAlt}")
        appendLine("color_fg_bar_alt = ${colors.fgBarAlt}")
        appendLine("color_bg_sel_alt = ${colors.bgSelAlt}")
        appendLine("color_heading_alt = ${colors.headingAlt}")
        appendLine("color_comment_alt = ${colors.commentAlt}")
        append("color_markup_alt = ${colors.markupAlt}")
    }
}
