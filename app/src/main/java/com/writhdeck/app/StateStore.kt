package com.writhdeck.app

import java.io.File

data class AppState(
    val cursors: Map<String, Pair<Int, Int>> = emptyMap(),
    val favorites: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
    val daily: Map<String, Map<String, Int>> = emptyMap()
)

object StateStore {

    fun load(file: File): AppState {
        if (!file.exists()) return AppState()
        val raw = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { return AppState() }

        val cursors = parseCursors(raw).mapKeys { migratePath(it.key) }
        val favorites = parseArray(raw, "favorites").map { migratePath(it) }
        val recent = parseArray(raw, "recent").map { migratePath(it) }

        val daily = mutableMapOf<String, MutableMap<String, Int>>()
        for (entry in parseArray(raw, "daily").map { migratePath(it) }) {
            val parts = entry.split("\t")
            if (parts.size < 3) continue
            val path = parts[0]
            val dateMap = daily.getOrPut(path) { mutableMapOf() }
            var i = 1
            while (i + 1 < parts.size) {
                dateMap[parts[i]] = parts[i + 1].toIntOrNull() ?: 0
                i += 2
            }
        }
        return AppState(cursors, favorites, recent, daily)
    }

    fun save(file: File, state: AppState) {
        val sb = StringBuilder()
        sb.append("{\n")

        sb.append("\"cursors\":{")
        if (state.cursors.isNotEmpty()) {
            sb.append("\n")
            sb.append(state.cursors.entries.joinToString(",\n") { (k, v) ->
                "\"${k.escJ()}\":[${v.first},${v.second}]"
            })
            sb.append("\n")
        }
        sb.append("},\n")

        sb.append("\"favorites\":[")
        if (state.favorites.isNotEmpty()) {
            sb.append("\n")
            sb.append(state.favorites.joinToString(",\n") { "\"${it.escJ()}\"" })
            sb.append("\n")
        }
        sb.append("],\n")

        sb.append("\"recent\":[")
        if (state.recent.isNotEmpty()) {
            sb.append("\n")
            sb.append(state.recent.joinToString(",\n") { "\"${it.escJ()}\"" })
            sb.append("\n")
        }
        sb.append("],\n")

        val dailyEntries = state.daily.entries.map { (path, dates) ->
            val entry = buildString {
                append(path.escJ())
                dates.forEach { (date, cnt) -> append("\\t${date}\\t${cnt}") }
            }
            "\"$entry\""
        }
        sb.append("\"daily\":[")
        if (dailyEntries.isNotEmpty()) {
            sb.append("\n")
            sb.append(dailyEntries.joinToString(",\n"))
            sb.append("\n")
        }
        sb.append("]\n}\n")

        try { file.writeText(sb.toString(), Charsets.UTF_8) } catch (_: Exception) {}
    }

    fun pushRecent(state: AppState, path: String): AppState {
        val list = (listOf(path) + state.recent.filter { it != path }).take(20)
        return state.copy(recent = list)
    }

    fun toggleFavorite(state: AppState, path: String): AppState =
        if (path in state.favorites)
            state.copy(favorites = state.favorites.filter { it != path })
        else
            state.copy(favorites = state.favorites + path)

    fun renamePath(state: AppState, old: String, new: String): AppState = AppState(
        cursors   = state.cursors.mapKeys   { if (it.key == old) new else it.key },
        favorites = state.favorites.map     { if (it == old) new else it },
        recent    = state.recent.map        { if (it == old) new else it },
        daily     = state.daily.mapKeys     { if (it.key == old) new else it.key }
    )

    fun removePath(state: AppState, path: String): AppState = AppState(
        cursors   = state.cursors.filter   { it.key != path },
        favorites = state.favorites.filter { it != path },
        recent    = state.recent.filter    { it != path },
        daily     = state.daily.filter     { it.key != path }
    )

    fun saveCursor(state: AppState, path: String, cy: Int, cx: Int): AppState =
        state.copy(cursors = state.cursors + (path to (cy to cx)))

    fun updateDaily(state: AppState, path: String, wordCount: Int, today: String): AppState {
        val fileData = state.daily.getOrDefault(path, emptyMap()).toMutableMap()
        val existing = fileData[today] ?: 0
        if (wordCount <= existing) return state
        fileData[today] = wordCount
        return state.copy(daily = state.daily + (path to fileData))
    }

    // Convert Tcl-normalized paths (/data/media/0/) back to the user-visible paths.
    private fun migratePath(path: String): String =
        if (path.startsWith("/data/media/0/"))
            "/storage/emulated/0/" + path.removePrefix("/data/media/0/")
        else path

    // --- JSON parsing (hand-rolled, no external lib) ---

    private fun parseCursors(raw: String): Map<String, Pair<Int, Int>> {
        val result = mutableMapOf<String, Pair<Int, Int>>()
        val ci = raw.indexOf("\"cursors\"")
        if (ci < 0) return result
        val ob = raw.indexOf('{', ci)
        val cb = raw.indexOf('}', ob + 1)
        if (ob < 0 || cb < 0) return result
        val sub = raw.substring(ob + 1, cb)
        val re = Regex(""""((?:[^"\\]|\\.)*?)"\s*:\s*\[(\d+)\s*,\s*(\d+)]""")
        for (m in re.findAll(sub)) {
            val key = m.groupValues[1].unescJ()
            val cy  = m.groupValues[2].toIntOrNull() ?: 0
            val cx  = m.groupValues[3].toIntOrNull() ?: 0
            result[key] = cy to cx
        }
        return result
    }

    private fun parseArray(raw: String, key: String): List<String> {
        val ki = raw.indexOf("\"$key\"")
        if (ki < 0) return emptyList()
        val ai = raw.indexOf('[', ki)
        val ae = raw.indexOf(']', ai + 1)
        if (ai < 0 || ae < 0) return emptyList()
        val sub = raw.substring(ai + 1, ae)
        val re = Regex(""""((?:[^"\\]|\\.)*)"""")
        return re.findAll(sub).map { it.groupValues[1].unescJ() }.toList()
    }

    private fun String.escJ(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    private fun String.unescJ(): String =
        replace("\\\"", "\"").replace("\\\\", "\\")
            .replace("\\t", "\t").replace("\\n", "\n")
}
