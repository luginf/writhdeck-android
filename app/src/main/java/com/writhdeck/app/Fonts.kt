package com.writhdeck.app

import android.graphics.Typeface
import java.io.File

/** User-supplied editor fonts.
 *
 *  WrithDeck embeds no font files (avoids APK bloat + font-licensing concerns — see the
 *  comment on [EDITOR_FONTS]). In addition to the built-in generic aliases, the user can
 *  drop `.ttf`/`.otf` files into a `fonts/` subfolder of the config directory (next to
 *  `writhdeck.ini`) and/or of a custom documents folder (`docs_dir`); they then appear in
 *  the Fonts tab alongside the built-in families. Both locations are scanned — earlier dirs
 *  in the list take precedence on a filename collision.
 *
 *  A user font is identified in the INI's `font_family` value by its bare filename
 *  (e.g. `font_family = JetBrainsMono.ttf`), distinguishable from a generic alias by its
 *  `.ttf`/`.otf` extension ([isUserFont]). */
object FontManager {
    val SUPPORTED_EXTENSIONS = setOf("ttf", "otf")

    /** True if [familyName] names a user font file (ends in a supported extension) rather
     *  than a built-in generic alias like `monospace`. */
    fun isUserFont(familyName: String): Boolean =
        familyName.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS

    /** Lists the `.ttf`/`.otf` files across [fontsDirs] as [EditorFont]s (label = filename
     *  without extension, familyName = filename), sorted case-insensitively by name and
     *  de-duplicated by filename (first matching dir wins). */
    fun listUserFonts(fontsDirs: List<File>): List<EditorFont> =
        fontsDirs.flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.extension.lowercase() in SUPPORTED_EXTENSIONS }?.toList()
                ?: emptyList()
        }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name.lowercase() }
            .map { EditorFont(it.nameWithoutExtension, it.name) }

    // Loaded user-font typefaces, keyed by filename. createFromFile hits the disk, so cache
    // the base (style-independent) typeface; bold/italic is synthesised on top via
    // Typeface.create(base, style). Keeps the per-recomposition gutter update cheap.
    private val cache = HashMap<String, Typeface?>()

    /** Resolves [familyName] to a [Typeface] in the given [style] ([Typeface.NORMAL]/[BOLD]/…).
     *  A user font (see [isUserFont]) is loaded from the first of [fontsDirs] that holds a
     *  file named [familyName] and the style synthesised; anything else (or a missing/invalid
     *  file) falls back to the system's `Typeface.create(familyName, style)`. */
    fun resolveTypeface(fontsDirs: List<File>, familyName: String, style: Int): Typeface {
        if (isUserFont(familyName)) {
            val base = cache.getOrPut(familyName) {
                fontsDirs.asSequence()
                    .map { File(it, familyName) }
                    .firstOrNull { it.isFile }
                    ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
            }
            if (base != null) return if (style == Typeface.NORMAL) base else Typeface.create(base, style)
        }
        return Typeface.create(familyName, style)
    }
}
