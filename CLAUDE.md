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
| `app/src/main/java/com/writhdeck/app/AppConfig.kt` | `AppConfig` + `IniParser` (parse/write/patchKeys/patchProfileKey) + `ThemeColors` |
| `app/src/main/java/com/writhdeck/app/StateStore.kt` | `AppState` + hand-rolled JSON load/save + path migration |
| `app/src/main/java/com/writhdeck/app/ColorSchemes.kt` | `SchemeColors` + `BUILTIN_SCHEMES` map (8 schemes) |
| `app/src/main/java/com/writhdeck/app/WrithdeckViewModel.kt` | ViewModel: StateFlows, timer, docs, favorites, config, autosave, workspaces |
| `app/src/main/java/com/writhdeck/app/MainActivity.kt` | Entry point, system dark mode, storage permission |
| `app/src/main/java/com/writhdeck/app/ui/BrowserScreen.kt` | File browser: scratchpad item, keyboard shortcuts |
| `app/src/main/java/com/writhdeck/app/ui/EditorScreen.kt` | Editor: AndroidView(EditText), syntax spans, TOC, command mode, workspace toggle |
| `app/src/main/java/com/writhdeck/app/ui/SchemeConfigScreen.kt` | Color scheme selector + custom scheme editor |
| `app/src/main/java/com/writhdeck/app/ui/SettingsScreen.kt` | Settings: font, margins, autosave, timer |
| `app/build.gradle.kts` | Gradle config (no NDK, no Tcl dependencies) |

---

## Critical patterns

### INI parsing

`IniParser.parse()` is section-aware. `= profile: name =` headers route keys into per-profile maps; `active_profile` in the global section selects which profile's keys override the globals.

`IniParser.write()` generates a complete template with:
- Comment listing all 8 schemes
- Two profile sections: `= profile: default =` and `= profile: novel =`
- `active_profile = default` in the global section

`IniParser.patchKeys()` patches key=value pairs anywhere in the INI (global keys like `android_dark_mode`).

`IniParser.patchProfileKey(text, profile, key, value)` patches a key only inside a `= profile: name =` section — use this for profile-level keys (`scheme`, `font_size`, `margin_width`, etc.) to avoid clobbering values in other sections.

`IniParser.writeCustomScheme(text, name, colors)` / `removeSchemeSection(text, name)` manage `= scheme: name =` sections for custom schemes.

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

### Cursor restore

`openFile()` sets `_initialCursorOffset` **before** `_currentFile` so that `remember(currentFile?.path, wsActive)` in EditorScreen reads the correct value during composition. `saveCursor(offset)` converts char offset → (cy, cx) via `textOffsetToLinecol` and persists to `StateStore`.

### AndroidView EditText (editor)

The editor uses `AndroidView { android.widget.EditText }` — not `BasicTextField`. This uses Android's `DynamicLayout` which only renders visible lines, making it handle large files (500K+ chars) efficiently.

**Key state refs** (captured by factory closure, updated by `update {}` block):
- `ignoreTextChange: booleanArrayOf(false)` — prevents TextWatcher feedback loop when `setText()` is called programmatically
- `keyHandlerRef: Array<(Int, AKeyEvent) -> Boolean>` — updated each recomposition to close over current compose state; factory calls `keyHandlerRef[0](keyCode, event)`
- `originalKeyListener: Array<KeyListener?>` — stored after `inputType` is set; restored/nulled in `update {}` for read-only toggle
- `editorRef: MutableState<EditText?>` — set in `factory { }.also { editorRef.value = it }`

**File/workspace change detection** — `editText.tag = "${currentFile?.path}:${wsActive}"`. In `update {}`, if tag differs → `ignoreTextChange[0] = true; setText(content); setSelection(liveCursor); tag = key; ignoreTextChange[0] = false`. This is the ONLY place `setText()` is called; all other recompositions skip it.

**TextWatcher** — `afterTextChanged` calls `vm.updateLiveCursor(selectionStart)` + `vm.updateContent(text)`. Guards with `if (ignoreTextChange[0]) return`.

**Syntax highlighting** — `LaunchedEffect(content, headingMarker, ..., hdColorInt, cmtColorInt)` with `delay(300)` debounce + `withContext(Dispatchers.Default)`. Applies via marker span subclasses (`SyntaxHeadingSpan`, `SyntaxCommentSpan`) on the Editable. `setSpan()` does NOT trigger TextWatcher — no guard needed.

**Search spans** — `LaunchedEffect(searchMatches, findMatchIndex)` runs on main dispatcher, directly calls `editable.setSpan(SearchBgSpan/SearchCurrentBgSpan/SearchCurrentFgSpan, ...)`. Then `editText.setSelection(match)` to scroll.

**Undo/redo** — native Android EditText handles Ctrl+Z automatically (API 23+, minSdk=26). No `ArrayDeque` needed.

**Heading toggle** — `applyHeadingResult(text, selStart, selEnd, marker): Triple<String, Int, Int>?` returns new text + selection. Apply: `ignoreTextChange[0] = true; editText.setText(newText); editText.setSelection(...); ignoreTextChange[0] = false; vm.updateContent(newText)`.

**TOC navigation** — `editorRef.value?.setSelection(entry.charOffset)`.

**Cursor save** — `doBack()` and `DisposableEffect.onDispose` read `editorRef.value?.selectionStart` directly.

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

No upper limit enforced by the parser — `IniParser.parse()` uses `coerceAtLeast(0)` only. Settings UI caps at 200 dp.

### Autosave

`restartAutosave()` cancels/restarts a coroutine loop (`delay(interval * 60_000L)`) that writes `autosave_ws0N.txt` in `docsDir`. Format: `"$name\n$ts\n\n-------------------------\n$content"`. Triggered by `applyConfig()` when autosave settings change.

### Second workspace

`WsSnapshot` captures full workspace state (file, content, dirty, wordCount, writable, extUri, cursorOffset). `toggleWorkspace(cursorOffset)` saves current state to `ws1Snap`/`ws2Snap`, restores the other, sets `_wsDualMode=true`. `_wsActive` (1 or 2) and `_wsDualMode` are exposed as StateFlows. Status bar shows `[1]`/`[2]` prefix when `wsDualMode` is true.

### Scratchpad

`openScratchpad()` opens `docsDir/scratchpad.txt` (creates if missing) with cursor restore. Always writable. BrowserScreen shows it as a permanent first item; `t` key shortcut.

### Color schemes

`SchemeColors` has 16 fields (8 dark + 8 light). `BUILTIN_SCHEMES` has 8 built-in schemes. `AppConfig.customSchemes: Map<String, SchemeColors>` holds INI-parsed `= scheme: name =` sections; `schemeColors()` checks custom first, then builtins. `SchemeConfigScreen` lists all schemes with color swatches, lets user select the active scheme, edit any scheme (builtin or custom), or create new custom schemes. Custom schemes are persisted via `IniParser.writeCustomScheme`.

### Settings screen

`SettingsData` data class mirrors the user-configurable fields of `AppConfig`. `getSettingsData()` reads current config; `applySettings(s)` calls `config.copy(...)`, `applyConfig()`, then `IniParser.patchKeys` to persist all fields. `SettingsScreen` groups controls: Writing, Autosave, Timer, plus "Edit INI directly" button.

### External file save

Via `contentResolver.openOutputStream()` if `canWrite`. URI stored in `externalUri` for subsequent saves without re-picking.

---

## Kotlin/Compose patterns

- `collectAsStateWithLifecycle()` for all ViewModel StateFlows
- `remember(key) { ... }` for expensive derived values (`parseHexColor`, `buildToc`)
- `BackHandler` to intercept the system back gesture
- `AndroidView { factory / update }` for native views inside Compose — `factory` runs once, `update` runs on every recomposition
- Mutable ref arrays (`arrayOf<() -> Unit>({ })`) to pass current compose lambdas into `factory` closures

---

---

## What not to do

- Do not add Tcl/JNI — the engine is gone, all logic is pure Kotlin.
- Do not replace `AndroidView { EditText }` with `BasicTextField` — BasicTextField is not virtualized and becomes unusable on large files.
- Do not call `editText.setText()` from the TextWatcher (feedback loop) — use `ignoreTextChange[0]` guard.
- Do not update `keyHandlerRef[0]` inside the `factory` block — update it in `update {}` to capture current compose state.
- Do not call `focusRequester.requestFocus()` automatically on BrowserScreen open — use the `imeAllowed` pattern.
- Never commit on behalf of the user — let the user decide when and how to commit.
