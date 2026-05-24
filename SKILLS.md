# WrithDeck Android — developer reference

For patterns on the Tcl engine side (`state.tcl`, `config.tcl`, timer, stats, INI...),
see **`../writhdeck/SKILLS.md`** — this file covers only the Android layer
(JNI, Kotlin, Compose).

---

## Tcl / Kotlin architecture

| Responsibility | Layer |
|---|---|
| `.writhdeck.json` persistence (cursors, favorites, recents, stats) | Tcl `state.tcl` |
| `writhdeck.ini` config (ini-load/save, profiles, themes, keys) | Tcl `config.tcl` |
| Timer — state, countdown/stopwatch logic | Tcl `android-timer-*` |
| Timer — tick every second | Kotlin coroutine `delay(1000)` |
| Real-time word count | Kotlin (in-memory content) |
| Word occurrences | Tcl `android-word-occurrences` |
| Daily stats | Tcl `android-get-stats` -> `daily-update` |
| Active theme colors | Tcl `android-get-theme` -> Kotlin |
| TOC (`buildToc`) | Kotlin |
| UI, navigation, lifecycle | Kotlin + Jetpack Compose |

---

## JNI bridge

### Init order (`writhdeck_jni.c`) — critical

```c
// 1. Create the interpreter
interp = Tcl_CreateInterp();
// 2. tcl_library BEFORE Tcl_Init — otherwise Tcl looks for /usr/local/lib/tcl8.6 -> failure
snprintf(lib_path, sizeof(lib_path), "%s/tcl/lib/tcl8.6", dir);
Tcl_SetVar(interp, "tcl_library", lib_path, TCL_GLOBAL_ONLY);
// 3. Tcl_Init — logged but non-fatal if it fails
if (Tcl_Init(interp) != TCL_OK) { LOGE("Tcl_Init: %s", ...); }
// 4. Environment variable for boot-android.tcl
Tcl_SetVar(interp, "::ANDROID_FILES_DIR", dir, TCL_GLOBAL_ONLY);
```

If `tcl_library` is set after `Tcl_Init`, Tcl cannot find `init.tcl`, the interpreter
is unusable, `boot-android.tcl` never runs -> empty ini file.

### Exposed functions

| JNI function | Purpose |
|---|---|
| `nativeInit(filesDir)` | Creates the interpreter, sets `tcl_library`, sources `boot-android.tcl` |
| `nativeEval(script)` | Evaluates Tcl, returns the result as a String |
| `nativeGetVar(varName)` | Reads a global Tcl variable |
| `nativeSetVar(varName, value)` | Sets a global Tcl variable |
| `nativeDestroy()` | Destroys the interpreter |

All JNI calls are made on `Dispatchers.IO` (Tcl thread-safety).

---

## Tcl boot (`boot-android.tcl`)

### Mandatory order

```tcl
# Load engine modules
foreach _mod {state config} { source ... }
# Load color schemes
foreach _sf [glob .../schemes/*.tcl] { catch { source $_sf } }
schemes-init          # <- BEFORE ini-load: populates cfg_schemes for scheme-apply
file mkdir $::DOCS_DIR_DEFAULT  # <- BEFORE ini-load: otherwise ini-save fails (missing dir)
ini-load              # <- calls scheme-apply if scheme != default
keys-init
state-load
```

### Exposed Android procs

| Proc | Return | Purpose |
|---|---|---|
| `android-timer-start/pause/resume/reset` | `"active remaining"` | Timer control |
| `android-timer-tick` | `"active remaining"` | Called by Kotlin coroutine every second |
| `android-timer-state` | `"active remaining lastTick"` | Initial state at ViewModel startup |
| `android-get-theme` | `"bg fg headingColor"` | Hex colors of the current theme |
| `android-word-occurrences {text}` | `"word\tcount\n..."` | Word frequency from in-memory text |
| `android-get-stats {filepath words}` | `"date\twords\n..."` | Daily stats for a file |

---

## Gradle `copyTclModules` task

Defined in `app/build.gradle.kts`, dependency of `preBuild`.

```
../writhdeck/src/state.tcl      -> assets/tcl/state.tcl
../writhdeck/src/config.tcl     -> assets/tcl/config.tcl
../writhdeck/src/schemes/*.tcl  -> assets/tcl/schemes/
tcl8.6.15/library/...          -> assets/tcl/lib/tcl8.6/  (committed, not copied)
```

The `writhdeck` repository must be cloned at `../writhdeck/` (side by side in the same
parent folder). `assets/tcl/lib/tcl8.6/` is committed — the Tcl stdlib is stable
across the entire 8.6.x series and does not need to be regenerated.

---

## Kotlin / Compose patterns

### BasicTextField — cursor

```kotlin
// Correct: remember without a content key
var tfv by remember { mutableStateOf(TextFieldValue(content)) }
// Sync only on external file open
LaunchedEffect(content) {
    if (content != tfv.text) tfv = TextFieldValue(content)
}
```

`remember(content) { ... }` resets the cursor to 0 on every keystroke — do not use.

### VisualTransformation (heading colors)

`HeadingVisualTransformation` colors heading lines without modifying the text.
- `OffsetMapping.Identity` — no offset shift
- `buildAnnotatedString { append(text) }` — preserves existing spans
- `remember(headingMarker, markdownHeadings, hdColor)` to avoid recomputing on every keystroke

```kotlin
visualTransformation = remember(headingMarker, markdownHeadings, hdColor) {
    HeadingVisualTransformation(headingMarker, markdownHeadings, hdColor)
}
```

### Theme colors

Read via a single Tcl call to avoid multiple evals:
```kotlin
val raw = engine.eval("android-get-theme")   // "bg fg headingColor"
val parts = raw.split(Regex("\\s+"))
```

Convert hex -> `Color` with `parseHexColor()`, which returns `Color.Unspecified` on error.
Apply via `remember(themeColors.bg) { parseHexColor(themeColors.bg) }`.

### Config reload

After saving `writhdeck.ini`:
```kotlin
engine.eval("ini-load")
engine.eval("keys-init")
_themeColors.value = loadThemeColors()
_headingMarker.value = engine.getVar("::cfg_heading_marker")
// ... all StateFlows affected by the INI
```

### TOC (`buildToc`)

Heading detection uses string matching (startsWith/endsWith + iterative count loop),
**not regex** — `Regex.escape("=")` produces `\Q=\E` which does not work as a
quantifier in Kotlin/Java.

Guard against crash on a bare marker (e.g. `"="`):
```kotlin
val title = if (end > pos) trimmed.substring(pos, end).trim() else ""
if (level > 0 && title.isNotEmpty()) entries.add(...)
```

### Distraction-free mode

```kotlin
var distractionFree by remember { mutableStateOf(false) }
if (distractionFree) BackHandler { distractionFree = false }
// Scaffold: topBar and bottomBar conditional on !distractionFree
// FullscreenExit button as TopEnd overlay inside the editor Box
```

`BackHandler` intercepts the system back gesture before navigation — without it,
pressing Back exits the editor instead of leaving the mode.

### External files (ACTION_VIEW / ACTION_EDIT intents)

```kotlin
// MainActivity.kt: read intent.data at startup and in onNewIntent
vm.openExternalContent(uri, contentResolver, canWrite)
// Save via ContentResolver (not direct File I/O)
contentResolver.openOutputStream(uri, "wt")?.use { ... }
```

`canWrite` = `intent.action == ACTION_EDIT || checkUriPermission(WRITE)`.
The URI is stored in `_externalUri` for subsequent saves.

---

## Android timer

The Tcl timer has no event loop -> Kotlin tick:

```kotlin
// Start
val result = engine.eval("android-timer-start")   // "1 1500"
// Coroutine tick
while (true) {
    delay(1000)
    val (active, remaining) = engine.eval("android-timer-tick").split(" ")
    _timerActive.value = active == "1"
    _timerRemaining.value = remaining.toInt()
}
```

`timerLastTick == 0L` -> timer never started or reset -> hide in status bar.
`timerLastTick != 0L && !timerActive` -> paused -> show remaining.

Never call the native `timer-start` / `timer-tick` procs (they use `after`, which
requires a Tcl event loop absent on Android).

---

## CMake / NDK

```cmake
set(TCL_ABI_DIR "${CMAKE_SOURCE_DIR}/../../../tcl-android/${ANDROID_ABI}/install")
add_library(writhdeck SHARED writhdeck_jni.c)
target_include_directories(writhdeck PRIVATE ${TCL_ABI_DIR}/include)
target_link_libraries(writhdeck ${TCL_ABI_DIR}/lib/libtcl8.6.a android log m)
```

Active ABIs: `arm64-v8a` (device) + `x86_64` (emulator). `armeabi-v7a` disabled.

Tcl cross-compilation — NDK r26+ critical points:
- `clang --target=...` directly (not `${triple}${api}-clang` wrappers — fail under autoconf)
- `--sysroot=$TOOLCHAIN/sysroot` in both `CFLAGS` and `LDFLAGS`
- `--build=$(uname -m)-linux-gnu` so autoconf correctly detects cross-compilation
- `-fPIC` required — `libtcl8.6.a` is linked into `libwrithdeck.so` (shared lib)

---

## Implemented

- **JNI bridge**: `nativeInit/Eval/GetVar/SetVar/Destroy`
- **Tcl boot**: `boot-android.tcl` with correct order (`schemes-init` before `ini-load`)
- **File browser**: lists `.txt`/`.md`/`.ini` in Documents/WrithDeck/
- **Editor**: monospace `BasicTextField`, `VisualTransformation` for headings
- **TOC**: `buildToc` (WrithDeck `= heading =` + markdown `# heading`)
- **Command mode**: `ModalBottomSheet` with timer, stats, occurrences, TOC, heading
- **Heading toggle**: `applyHeading(tfv, marker)` — toggles markers on the selection
- **Distraction-free mode**: conditional Scaffold + `BackHandler`
- **Timer**: Kotlin coroutine + `android-timer-*` procs
- **Daily stats**: `android-get-stats` -> `AlertDialog`
- **Word occurrences**: `android-word-occurrences` -> scrollable dialog
- **Color themes**: `android-get-theme` -> `parseHexColor` -> `background`/`color`
- **writhdeck.ini editing**: gear button -> `openIniFile()` -> `reloadConfig()`
- **External files**: `ACTION_VIEW`/`ACTION_EDIT` intents + save via ContentResolver
- **Backup**: `android-backup {path}` -> `documents/backups/`

---

## Ideas not yet implemented

- Profile / theme selector in the UI (currently via INI editing)
- Built-in scratchpad (WS2 Android)
- `.md` file support with basic preview
- In-text search (Ctrl+F equivalent)
- File sharing (ACTION_SEND intent)
- Git repository sync (SSH or HTTP)
- Word count home screen widget
- Custom font import (TTF)
