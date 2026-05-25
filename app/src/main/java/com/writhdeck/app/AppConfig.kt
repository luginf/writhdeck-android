package com.writhdeck.app

data class ThemeColors(
    val bg: String = "#1a1a1a",
    val fg: String = "#e8e8e8",
    val headingColor: String = "#c8a060"
)

data class AppConfig(
    val scheme: String = "default",
    val androidDarkMode: String = "auto",
    val marginWidth: Int = 16,
    val marginHeight: Int = 16,
    val headingMarker: String = "=",
    val markdownHeadings: Boolean = true,
    val timerType: String = "countdown",
    val timerDuration: Int = 25,
    val timerSound: Boolean = false,
    val timerAlert: Boolean = false,
    val chronoShow: Boolean = false,
    val wordGoal: Int = 0,
    val keyToc: String = "F11",
    val docsCustomDir: String = "",
    val activeProfile: String = "default",
    val customSchemes: Map<String, SchemeColors> = emptyMap()
) {
    fun schemeColors(): SchemeColors =
        customSchemes[scheme] ?: BUILTIN_SCHEMES[scheme] ?: BUILTIN_SCHEMES["default"]!!

    fun themeColors(useDark: Boolean): ThemeColors {
        val c = schemeColors()
        return if (useDark) ThemeColors(bg = c.bg, fg = c.fg, headingColor = c.heading)
               else         ThemeColors(bg = c.bgAlt, fg = c.fgAlt, headingColor = c.headingAlt)
    }

    fun timerDurationSecs(): Int = timerDuration * 60
}

object IniParser {

    fun parse(text: String): AppConfig {
        val global = mutableMapOf<String, String>()
        val profiles = mutableMapOf<String, MutableMap<String, String>>()
        val schemes = mutableMapOf<String, MutableMap<String, String>>()
        var currentProfile: String? = null
        var currentScheme: String? = null

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#") || line.startsWith("%") || line.isEmpty()) continue
            // WrithDeck section header:  = title =
            if (line.startsWith("=") && line.endsWith("=") && line.length > 2) {
                val title = line.trim('=').trim()
                currentProfile = if (title.startsWith("profile:")) title.removePrefix("profile:").trim() else null
                currentScheme  = if (title.startsWith("scheme:"))  title.removePrefix("scheme:").trim()  else null
                continue
            }
            // Standard INI section header: [section]
            if (line.startsWith("[") && line.endsWith("]")) {
                currentProfile = null
                currentScheme  = null
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

        val activeProfile = global["active_profile"]?.takeIf { it.isNotBlank() } ?: "default"
        val keys = global.toMutableMap().also { it.putAll(profiles[activeProfile] ?: emptyMap()) }

        fun bool(k: String, def: Boolean): Boolean {
            val v = keys[k]?.lowercase() ?: return def
            return v == "yes" || v == "1" || v == "true" || v == "on"
        }
        fun int(k: String, def: Int): Int = keys[k]?.toIntOrNull() ?: def
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
            marginWidth      = int("margin_width", 16).coerceIn(0, 48),
            marginHeight     = int("margin_height", 16).coerceIn(0, 32),
            headingMarker    = str("heading_marker", "="),
            markdownHeadings = bool("markdown_headings", true),
            timerType        = str("timer_type", "countdown").let {
                if (it == "stopwatch") "stopwatch" else "countdown"
            },
            timerDuration    = int("timer_duration", 25).coerceAtLeast(1),
            timerSound       = bool("timer_sound", false),
            timerAlert       = bool("timer_alert", false),
            chronoShow       = bool("chrono_show", false),
            wordGoal         = int("word_goal", 0).coerceAtLeast(0),
            keyToc           = str("key_toc", "F11"),
            docsCustomDir    = keys["docs_dir"] ?: "",
            activeProfile    = activeProfile,
            customSchemes    = customSchemes
        )
    }

    fun write(config: AppConfig): String {
        fun bool(b: Boolean) = if (b) "yes" else "no"
        return buildString {
            appendLine("% WrithDeck — configuration")
            appendLine("% Schemes: default solarized gruvbox everforest nord alt01 alt02 retro")
            appendLine()
            appendLine("= editor =")
            appendLine("active_profile = ${config.activeProfile}")
            appendLine()
            appendLine("= behaviour =")
            appendLine("cursor_restore = yes")
            appendLine("autosave_enabled = yes")
            appendLine("autosave_interval = 1")
            appendLine()
            appendLine("= timer =")
            appendLine("timer_type = ${config.timerType}")
            appendLine("timer_duration = ${config.timerDuration}")
            appendLine("timer_sound = ${bool(config.timerSound)}")
            appendLine("timer_alert = ${bool(config.timerAlert)}")
            appendLine("chrono_show = ${bool(config.chronoShow)}")
            appendLine()
            appendLine("= misc =")
            appendLine("android_dark_mode = ${config.androidDarkMode}")
            if (config.docsCustomDir.isNotEmpty()) appendLine("docs_dir = ${config.docsCustomDir}")
            appendLine()
            appendLine("= keys =")
            appendLine("key_toc = ${config.keyToc}")
            appendLine()
            appendLine("= profiles =")
            appendLine()
            appendLine("= profile: default =")
            appendLine("scheme = default")
            appendLine("heading_marker = =")
            appendLine("markdown_headings = yes")
            appendLine("margin_width = 16")
            appendLine("margin_height = 16")
            appendLine("word_goal = 0")
            appendLine()
            appendLine("= profile: novel =")
            appendLine("scheme = everforest")
            appendLine("heading_marker = =")
            appendLine("markdown_headings = no")
            appendLine("margin_width = 32")
            appendLine("margin_height = 24")
            append("word_goal = 1000")
        } + "\n"
    }

    /** Patch specific key=value pairs in existing INI text, preserving all other content. */
    fun patchKeys(text: String, vararg pairs: Pair<String, String>): String {
        val toSet = pairs.toMap().toMutableMap()
        val found = mutableSetOf<String>()
        val result = StringBuilder()
        for (raw in text.lines()) {
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
        val missing = toSet.keys - found
        if (missing.isNotEmpty()) {
            result.appendLine()
            for (k in missing) result.appendLine("$k = ${toSet[k]}")
        }
        return result.toString().trimEnd() + "\n"
    }

    /** Patch a key only within a specific profile section. */
    fun patchProfileKey(text: String, profile: String, key: String, value: String): String {
        val targetHeader = "= profile: $profile ="
        val lines = text.lines()
        val result = StringBuilder()
        var inTarget = false
        var patched = false

        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed.startsWith("=") && trimmed.endsWith("=") && trimmed.length > 2) {
                inTarget = (trimmed == targetHeader)
            } else if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inTarget = false
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
        if (!patched) {
            result.appendLine()
            result.appendLine("$key = $value")
        }
        return result.toString().trimEnd() + "\n"
    }

    /** Remove a custom scheme section from INI text. */
    fun removeSchemeSection(text: String, name: String): String {
        val header = "= scheme: $name ="
        val lines = text.lines()
        val result = mutableListOf<String>()
        var skipping = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed == header) { skipping = true; continue }
            if (skipping) {
                val isNewSection = (trimmed.startsWith("=") && trimmed.endsWith("=") && trimmed.length > 2)
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"))
                if (isNewSection) skipping = false else continue
            }
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
        appendLine("= scheme: $name =")
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
