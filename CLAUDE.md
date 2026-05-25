# CLAUDE.md — WrithDeck Android

Instructions for Claude Code in this repository.

---

## Architecture

Pure Kotlin + Jetpack Compose. No Tcl/JNI engine. All logic is in Kotlin:
- INI parsing/writing: `IniParser` in `AppConfig.kt`
- State persistence: `StateStore` in `StateStore.kt`
- Color schemes: `ColorSchemes.kt` — 8 built-in schemes
- Business logic + UI state: `WrithdeckViewModel.kt`
- UI: Jetpack Compose screens in `ui/`

---

## Build

```sh
# From writhdeck-android/
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/writhdeck-debug.apk
```

---

## Key files

| File | Role |
|---|---|
| `app/src/main/java/com/writhdeck/app/AppConfig.kt` | `AppConfig` data class + `IniParser` (parse/write/patchKeys) + `ThemeColors` |
| `app/src/main/java/com/writhdeck/app/StateStore.kt` | `AppState` + hand-rolled JSON load/save + path migration |
| `app/src/main/java/com/writhdeck/app/ColorSchemes.kt` | `SchemeColors` + `BUILTIN_SCHEMES` map (8 schemes) |
| `app/src/main/java/com/writhdeck/app/WrithdeckViewModel.kt` | ViewModel: StateFlows, timer, docs, favorites, config |
| `app/src/main/java/com/writhdeck/app/MainActivity.kt` | Entry point, system dark mode, storage permission |
| `app/src/main/java/com/writhdeck/app/ui/BrowserScreen.kt` | File browser with keyboard shortcut support |
| `app/src/main/java/com/writhdeck/app/ui/EditorScreen.kt` | Editor: BasicTextField, TOC, command mode |
| `app/build.gradle.kts` | Gradle config (no NDK, no Tcl dependencies) |

---

## Critical patterns

### INI parsing

`IniParser.parse()` is section-aware. `= profile: name =` headers route keys into per-profile maps; `active_profile` in the global section selects which profile's keys override the globals.

`IniParser.write()` generates a complete template with:
- Comment listing all 8 schemes
- Two profile sections: `= profile: default =` and `= profile: novel =`
- `active_profile = default` in the global section

`IniParser.patchKeys()` patches specific key=value pairs in existing INI text, preserving all other content (used by `setDarkModePreference`).

`initApp()` creates `writhdeck.ini` with default content on first launch so it always appears in the documents list.

### State persistence (`StateStore`)

Hand-rolled JSON, compatible with the desktop `.writhdeck.json` format. Path migration on load converts old Tcl-normalized paths:

```kotlin
// Tcl `file normalize` used to convert /storage/emulated/0/ → /data/media/0/ (unreadable)
private fun migratePath(path: String): String =
    if (path.startsWith("/data/media/0/"))
        "/storage/emulated/0/" + path.removePrefix("/data/media/0/")
    else path
```

Applied in `load()` to cursors keys, favorites list, recent list, and daily map keys.

### BasicTextField and cursor

Use `remember { }` **without a content key**. `remember(content) { }` resets the cursor to position 0 on every keystroke. `LaunchedEffect(content)` handles sync on external file open.

### IME / keyboard in BrowserScreen

The invisible `BasicTextField` (1dp, alpha 0) captures hardware keyboard shortcuts. It must never auto-show the IME on navigation return from the editor.

Pattern (`imeAllowed` flag):
```kotlin
var imeAllowed by remember { mutableStateOf(false) }

// Keyboard icon button:
if (imeVisible) {
    imeAllowed = false
    keyboardController?.hide()
} else {
    imeAllowed = true
    focusRequester.requestFocus()
    keyboardController?.show()
}

// BasicTextField modifier:
.onFocusChanged { fs ->
    if (fs.isFocused && !imeAllowed) keyboardController?.hide()
}

// Each onOpenFile call:
imeAllowed = false; onOpenFile(entry)
```

### Color themes

`AppConfig.themeColors(useDark: Boolean): ThemeColors` returns `ThemeColors(bg, fg, headingColor)` from the active scheme. `ThemeColors` is defined in `AppConfig.kt`.

`applyConfig()` calls `config.themeColors(resolveUseDark())` and pushes to `_themeColors`. `updateThemeColors(systemDark)` is called by `MainActivity` when the system dark mode changes.

### Config reload

After saving `writhdeck.ini` from the editor: `reloadConfig()` re-parses the file, updates `config`, calls `applyConfig()` to push all affected StateFlows.

### Read-only files (`fileWritable`)

`_fileWritable: MutableStateFlow<Boolean>` — set in `openFile` (`File.canWrite()`), `openExternalContent` (from intent `canWrite` flag), and `saveFile` (set to `false` on write failure).

EditorScreen behavior when `!fileWritable`:
- Title shows `[read-only]` suffix
- Save button disabled
- AlertDialog on file open
- "Discard changes?" confirmation on back navigation when dirty

**Never use `storagePermissionGranted` to bypass `File.canWrite()`** — that permission doesn't grant access to other apps' private directories (e.g. Termux `/data/data/com.termux/files/`). Files from those paths must be written via `contentResolver.openOutputStream(uri)` when the intent grants `FLAG_GRANT_WRITE_URI_PERMISSION`.

### Margins

Desktop configs may have `margin_width` up to 180. Always `coerceIn(0, 48)` on Android to prevent zero-width text area (enforced in `IniParser.parse()`).

### External file save

Via `contentResolver.openOutputStream()` if `canWrite`. URI stored in `externalUri` for subsequent saves without re-picking.

---

## Kotlin/Compose patterns

- `collectAsStateWithLifecycle()` for all ViewModel StateFlows
- `remember(key) { ... }` for expensive derived values (`parseHexColor`, `buildToc`)
- `BackHandler` to intercept the system back gesture
- `imePadding()` on the editor to prevent soft keyboard from covering text

---

## What not to do

- Do not add Tcl/JNI — the engine is gone, all logic is pure Kotlin.
- Do not use `remember(content) { }` in `BasicTextField` — resets cursor on every keystroke.
- Do not call `focusRequester.requestFocus()` automatically on BrowserScreen open — use the `imeAllowed` pattern.
- Never commit on behalf of the user — let the user decide when and how to commit.
