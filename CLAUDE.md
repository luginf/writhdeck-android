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

### Auto-incrementing version (`app/build.gradle.kts`)

`versionCode`/`versionName` are computed at configuration time as `"YYYY-MM-DDx"` (e.g. `2026-06-07a`, then `2026-06-07b`, …) — `x` is a spreadsheet-column-style letter suffix (`a`…`z`, `aa`, `ab`, …) that increments per build on the same day. State is persisted in `app/version.properties` (`date=`/`letter=`, auto-generated, gitignore-able, do not edit by hand). `versionCode` is the numeric encoding `YYYYMMDD * 100 + (letterIndex + 1)` (e.g. `2026060701`).

The letter only advances when the requested Gradle tasks contain `assemble`/`bundle`/`install` (checked via `gradle.startParameter.taskNames`) — IDE syncs, `lint`, `test`, etc. don't burn through the alphabet.

**Why:** a static `versionCode = 1` made Android's package manager sometimes treat a reinstall as a no-op (binary not actually replaced — "it says updated but nothing changed", requiring an uninstall first to see changes). A unique, monotonically increasing `versionCode` per build forces a real replace. **How to apply:** if you ever see a stale APK behavior again, also try `./gradlew clean assembleDebug` — Gradle's incremental up-to-date check can occasionally report `compileDebugKotlin UP-TO-DATE` right after a source edit (observed 2026-06-07; `--rerun-tasks`/`clean` forces a true recompile).

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
| `app/src/main/java/com/writhdeck/app/ui/EditorScreen.kt` | Editor: AndroidView(FlingEditText), syntax spans, TOC, command mode, workspace toggle |
| `app/src/main/java/com/writhdeck/app/ui/SchemeConfigScreen.kt` | Color scheme selector + custom scheme editor |
| `app/src/main/java/com/writhdeck/app/ui/SettingsScreen.kt` | Settings: tabbed (Profile/Display/Fonts/Schemes/Timer/Misc) |
| `app/build.gradle.kts` | Gradle config (no NDK, no Tcl dependencies) |

---

## Critical patterns

### INI parsing

`IniParser.parse()` is section-aware, format identical to Tcl/JS: `[section]` headers (regex `^\[(\w+)\]$`). `[profiles]`/`[schemes]` are containers followed by named `[<name>]` sub-sections; `TOPLEVEL_SECTIONS` (union of Tcl/web top-level sections + Android's `status_bar`) disambiguates a `[<name>]` seen inside `[profiles]`/`[schemes]` (top-level name → closes the container; otherwise → names a profile/scheme). `profile = <name>` inside `[editor]` selects which profile's keys override the globals.

`IniParser.migrateLegacyFormat(text)` / `migrateProfileScopedKeys(text)` are one-shot, lossless, idempotent migrations run in `initApp()`: the former converts the legacy `= profile: <name> =`/`active_profile` dialect to the `[section]` format above; the latter moves `PROFILE_SCOPED_KEYS` values from global scope into each `[profiles] -> [<name>]` sub-section (without overwriting a profile that already defines the key).

`IniParser.write()` generates a complete template with:
- Comment listing all 8 schemes
- `[profiles]` containing `[default]` and `[novel]` sub-sections (12 per-profile keys each — see below)
- `profile = default` inside `[editor]`

`IniParser.patchKeys()` patches key=value pairs anywhere in the INI (global keys like `font_bold`).

`IniParser.patchProfileKey(text, profile, key, value)` patches a key only inside a `[profiles] -> [<profile>]` sub-section — used for the 12 per-profile keys (`PROFILE_SCOPED_KEYS` ∪ the original 6: `scheme`, `heading_marker`, `markdown_headings`, `margin_width`, `margin_height`, `word_goal`, plus `font_size`, `font_family`, `line_spacing`, `line_numbers`, `android_dark_mode`, `block_cursor`) to avoid clobbering values in other profiles/sections.

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

The editor uses `AndroidView { FlingEditText }` (a private `EditText` subclass — not `BasicTextField`). This uses Android's `DynamicLayout` which only renders visible lines, making it handle large files (500K+ chars) efficiently.

**`FlingEditText`** — adds momentum/fling scrolling. A plain multi-line `EditText` scrolls 1:1 with the drag and stops dead on release (no inertia), unlike `ScrollView`/most text editors and browsers. Wrapping in `ScrollView` would add fling but forces full-content-height measurement, defeating `DynamicLayout`'s visible-lines-only rendering on large files — so fling is implemented by driving the `EditText`'s own `scrollY` with an `OverScroller` + `GestureDetector.onFling`, exactly like `ScrollView` does internally (`onTouchEvent` aborts any running scroll on `ACTION_DOWN`, detects fling, `computeScroll()` advances the animation via `postInvalidateOnAnimation`). Guards `hasSelection()` so flicking after a text-selection drag doesn't trigger a spurious scroll.

**Key state refs** (captured by factory closure, updated by `update {}` block):
- `ignoreTextChange: booleanArrayOf(false)` — prevents TextWatcher feedback loop when `setText()` is called programmatically
- `keyHandlerRef: Array<(Int, AKeyEvent) -> Boolean>` — updated each recomposition to close over current compose state; factory calls `keyHandlerRef[0](keyCode, event)`
- `originalKeyListener: Array<KeyListener?>` — stored after `inputType` is set; restored/nulled in `update {}` for read-only toggle
- `editorRef: MutableState<EditText?>` — set in `factory { }.also { editorRef.value = it }`

**File/workspace change detection** — `editText.tag = "${currentFile?.path}:${wsActive}"`. In `update {}`, if tag differs → `ignoreTextChange[0] = true; setText(content); setSelection(liveCursor); tag = key; ignoreTextChange[0] = false`. This is the ONLY place `setText()` is called; all other recompositions skip it.

**TextWatcher** — `afterTextChanged` calls `vm.updateLiveCursor(selectionStart)` + `vm.updateContent(text)`. Guards with `if (ignoreTextChange[0]) return`.

**Syntax highlighting** — `LaunchedEffect(content, headingMarker, markdownHeadings, commentMarker, boldMarker, italicMarker, underlineMarker, strikethroughMarker, hdColorInt, cmtColorInt, markupColorInt)` with `delay(300)` debounce + `withContext(Dispatchers.Default)`. Applies via marker span subclasses (`SyntaxHeadingSpan`, `SyntaxCommentSpan`, `SyntaxMarkupSpan`) on the Editable. `setSpan()` does NOT trigger TextWatcher — no guard needed.

`computeSyntaxSpans` takes all 5 inline/comment markers as configurable `String` params (mirrors Tcl/web, see "Configurable markup markers" below) — comment detection is `commentMarker.isNotEmpty() && text.startsWith(commentMarker, ts)` (anchored after leading spaces via `ts`), and inline markup is one combined `Regex` built by alternating `Regex.escape(marker) + ".+?" + Regex.escape(marker)` for each non-empty marker among bold/italic/underline/strikethrough — all four map to the single `markupColorInt`/`SyntaxMarkupSpan` (Android only has one `markupColor` per scheme, like the web version's single `hl-markup` CSS class; Tcl's 4-separate-colors model was NOT replicated).

**Search spans** — `LaunchedEffect(searchMatches, findMatchIndex)` runs on main dispatcher, directly calls `editable.setSpan(SearchBgSpan/SearchCurrentBgSpan/SearchCurrentFgSpan, ...)`. Then `editText.setSelection(match)` to scroll.

**Undo/redo** — native Android EditText handles Ctrl+Z automatically (API 23+, minSdk=26). No `ArrayDeque` needed.

**Format menu (≡ → Format)** — three pure functions, ports of `editor.js`'s `applyHeading`/`applyLineMarker`/`applyInlineMarker`, all `(text, selStart, selEnd, marker[, level]): Triple<String, Int, Int>?` (`null` if marker empty):
- `applyHeadingLevel(text, selStart, selEnd, marker, level)` — H1/H2/H3, repeats `marker` ×`level`, detects/replaces existing heading level.
- `applyLineMarkerResult(text, selStart, selEnd, marker)` — Comment, toggles `marker` at the start of each selected line.
- `applyInlineMarkerResult(text, selStart, selEnd, marker)` — Bold/Italic/Underline/Strike, wraps/unwraps the selection with `marker...marker`, or inserts `marker+marker` with cursor between if no selection.

Apply via shared helper `applyEditResult(r: Triple<String, Int, Int>?)`: `ignoreTextChange[0] = true; editText.setText(newText); editText.setSelection(...); ignoreTextChange[0] = false; vm.updateContent(newText)`.

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

### Closing a modified document — `requestClose()`

`requestClose()` (in `EditorScreen`, next to `doBack()`) is the single entry point for "leave this document": `dirty && !fileWritable` → `showDiscardConfirm` ("Discard changes?" — changes can't be saved); `dirty && fileWritable` → `showSaveConfirm` ("Save changes?" with **Save** / **Don't save** / **Cancel** — a 3-way `AlertDialog` using a `Row` of two `TextButton`s in the `dismissButton` slot, since `AlertDialog` only exposes two button slots); otherwise → `doBack()` directly. Wired from the `TopAppBar` back `IconButton`, the system back gesture (`BackHandler { showSaveConfirm = true }` when `dirty && fileWritable && !distractionFree`), and `Ctrl+Q`.

**Why:** previously a dirty+writable document auto-saved silently on exit with no way to discard changes — the user asked for a confirmation with the option not to save ("il faudrait une confirmation pour être sûr (et la possibilité de ne pas le faire)").

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

`SchemeColors` has 16 fields (8 dark + 8 light). `BUILTIN_SCHEMES` has 8 built-in schemes. `AppConfig.customSchemes: Map<String, SchemeColors>` holds INI-parsed `[schemes] -> [<name>]` sub-sections; `schemeColors()` checks custom first, then builtins. `SchemeConfigScreen` lists all schemes with color swatches, lets user select the active scheme, edit any scheme (builtin or custom), or create new custom schemes. Custom schemes are persisted via `IniParser.writeCustomScheme`.

### Settings screen

`SettingsData` data class mirrors the user-configurable fields of `AppConfig`. `getSettingsData()` reads current config; `applySettings(s)` calls `config.copy(...)`, `applyConfig()`, then persists fields via `IniParser.patchKeys` (global, e.g. `font_bold`) and `IniParser.patchProfileKey` for the 12 per-profile keys (`scheme`, `heading_marker`, `markdown_headings`, `margin_width`, `margin_height`, `word_goal`, `font_size`, `font_family`, `line_spacing`, `line_numbers`, `android_dark_mode`, `block_cursor`), scoped to `config.activeProfile`.

`SettingsScreen` is tabbed via `ScrollableTabRow`/`Tab`, mirroring the desktop Tcl/Tk config dialog's tab set and order (`SETTINGS_TABS`): **Profile** (margins, word goal, line spacing), **Display** (heading marker, status bar zones), **Fonts** (font size, font family, bold, live preview), **Schemes** (scheme dropdown + "Edit scheme colors" → `SchemeConfigScreen`), **Timer** (type, duration, sound, alert, status bar display), **Misc** (Hemingway mode, autosave, "Edit INI directly"). Each tab is a private `@Composable` taking `(s: SettingsData, onChange: (SettingsData) -> Unit)`. `selectedTab` is local `mutableIntStateOf` — not persisted.

### Editor font family

Android has no portable font-enumeration API (unlike Tk's `font families`), so the Fonts tab offers a fixed list of **8 built-in generic font-family aliases** (`EDITOR_FONTS` in `AppConfig.kt`) — a mix of monospace, serif and sans-serif faces (`monospace`, `serif-monospace`, `sans-serif`, `sans-serif-condensed`, `sans-serif-light`, `sans-serif-medium`, `serif`, `casual`). These resolve via `Typeface.create(familyName, Typeface.NORMAL)` against the system `fonts.xml` — **no font files are embedded** (avoids APK bloat and font-licensing concerns; guaranteed present on AOSP).

Persisted as `font_family` (+ `font_bold` boolean) in the INI (parsed/validated against `EDITOR_FONTS` in `IniParser.parse()`, patched via `IniParser.patchKeys`). Exposed as `WrithdeckViewModel.fontFamily: StateFlow<String>` / `fontBold: StateFlow<Boolean>`. `EditorScreen` applies `Typeface.create(family, if (bold) BOLD else NORMAL)` to the `EditText` — set once in the `factory` block and re-applied in `update {}` only when family or weight changes (guarded by `EditorStyle.fontFamily`/`fontBold` inside the existing `lastStyle[0]` comparison, avoiding layout jank on unrelated recompositions).

**Live preview** — `FontPreview` in `SettingsScreen.kt` renders a sample sentence with the currently selected family/size/weight (`FontFamily(Typeface.create(...))`), so the user sees the effect of each setting immediately, without leaving the Fonts tab.

### Block cursor

The Display tab's "Editor" section has a "Block cursor" `SwitchSettingRow` (`blockCursor` in `AppConfig`/`SettingsData`/`WrithdeckViewModel`, persisted as `block_cursor` — mirrors Tcl/Tk's `block_cursor_gui` and the web version's `blockCursor`, both using the editor foreground colour as cursor colour, e.g. Tk's `-insertbackground $fg`).

Android has no native block-cursor toggle (unlike Tk's `-blockcursor`). Emulated via `EditText.setTextCursorDrawable()` (**API 29+ only** — `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q`; older devices silently keep the default thin caret) with a `GradientDrawable` rectangle sized to one character's width (`paint.measureText("M")`) × `lineHeight`, filled with `editorFgInt`. Applied/cleared inside the existing `lastStyle[0]`-guarded style block in `EditorScreen`'s `update {}` (extends `EditorStyle` with `blockCursor: Boolean`) — recomputed only when style actually changes, so character-width measurement (which depends on the current typeface/size, set just above in the same block) stays in sync.

### In-document Settings access

`EditorScreen`'s "≡" `DropdownMenu` (the in-document overflow menu) ends with an "App" section: a single "Settings" `DropdownMenuItem` at the very bottom (after a `HorizontalDivider`, below "Reset timer"), calling `onNavigateSettings()` — a new optional param threaded from `AppNavigation.kt` (`nav.navigate("settings")`). Mirrors writhdeck-web, which exposes Settings from its "?" help menu rather than only from the file browser. Lets the user reach Settings without leaving an open document.

### Configurable markup markers

Mirrors the Tcl/Tk and web versions: the Display tab's "Markup" section exposes `commentMarker`/`boldMarker`/`italicMarker`/`underlineMarker`/`strikethroughMarker` (all `String`, default `%`/`**`/`//`/`__`/`--`) plus a "Markdown headings (#)" `SwitchSettingRow` toggle for `markdownHeadings`. Persisted via `IniParser.parse()`'s `marker(k, def)` helper and `IniParser.patchKeys`, exposed as `WrithdeckViewModel` StateFlows, and consumed by `computeSyntaxSpans` in `EditorScreen.kt` (see "Syntax highlighting" above).

`marker(k, def)` replicates the desktop INI convention where a literal `"0"` value means "marker disabled" (round-trips an empty string through hand-edited INI files): `keys[k]?.let { if (it == "0") "" else it } ?: def`.

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
