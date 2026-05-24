# CLAUDE.md — WrithDeck Android

Instructions for Claude Code in this repository.

## Companion project

This repository is the Android frontend for WrithDeck. The Tcl engine and all rules
regarding `state.tcl`, `config.tcl`, `boot-android.tcl`, color schemes, INI,
daily stats and the timer are documented in:

**`../writhdeck/CLAUDE.md`** — general Tcl engine rules  
**`../writhdeck/ANDROID.md`** — Android architecture, JNI init order, Gradle task  
**`../writhdeck/SKILLS.md`** — full technical reference (Android patterns included)

Always consult these files before modifying `boot-android.tcl` or the Kotlin <-> Tcl
interactions. The writhdeck repository is expected at `../writhdeck/` (side by side in
the same parent directory).

---

## Build

```sh
# From writhdeck-android/
./tools/build-tcl-android.sh arm64-v8a   # device
./tools/build-tcl-android.sh x86_64      # emulator
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/writhdeck-debug.apk
```

The `preBuild` Gradle task automatically copies `../writhdeck/src/state.tcl`,
`../writhdeck/src/config.tcl` and `../writhdeck/src/schemes/*.tcl` into assets.
`app/src/main/assets/tcl/lib/tcl8.6/` is committed — no need to regenerate it.

---

## Key files

| File | Role |
|---|---|
| `app/src/main/assets/tcl/boot-android.tcl` | Tcl bootstrap: loads state+config, defines `android-*` procs |
| `app/src/main/cpp/writhdeck_jni.c` | C JNI bridge: `nativeInit`, `nativeEval`, `nativeGetVar`, `nativeSetVar` |
| `app/src/main/java/com/writhdeck/app/WrithdeckEngine.kt` | Kotlin wrapper for the JNI bridge |
| `app/src/main/java/com/writhdeck/app/WrithdeckViewModel.kt` | ViewModel: StateFlows, engine init, timer, config |
| `app/src/main/java/com/writhdeck/app/ui/EditorScreen.kt` | Compose editor: BasicTextField, TOC, command mode, distraction-free |
| `app/src/main/java/com/writhdeck/app/ui/BrowserScreen.kt` | Compose file browser |
| `app/build.gradle.kts` | Gradle config + `copyTclModules` task |
| `app/src/main/cpp/CMakeLists.txt` | NDK CMake config |
| `tools/build-tcl-android.sh` | Cross-compile Tcl 8.6 -> `libtcl8.6.a` |

---

## Critical rules

### JNI init (`writhdeck_jni.c`)

`tcl_library` must be set **before** `Tcl_Init()` — without this, Tcl looks for
`/usr/local/lib/tcl8.6/`, fails silently, and `boot-android.tcl` never runs
(empty ini, no config).

```c
interp = Tcl_CreateInterp();
Tcl_SetVar(interp, "tcl_library", lib_path, TCL_GLOBAL_ONLY);  // BEFORE Tcl_Init
if (Tcl_Init(interp) != TCL_OK) { LOGE(...); }  // non-fatal
Tcl_SetVar(interp, "::ANDROID_FILES_DIR", dir, TCL_GLOBAL_ONLY);
```

### Tcl boot (`boot-android.tcl`)

Mandatory order:
1. Source `state.tcl` + `config.tcl`
2. Source `schemes/*.tcl`
3. `schemes-init` — populates `cfg_schemes` **before** `ini-load`
4. `file mkdir $::DOCS_DIR_DEFAULT` — **before** `ini-load` (otherwise `ini-save` fails)
5. `ini-load` -> `keys-init` -> `state-load`

### BasicTextField and cursor

Use `remember { }` **without a content key**. `remember(content) { }` resets the
cursor to position 0 on every keystroke. `LaunchedEffect(content)` handles
synchronization on external file open.

### VisualTransformation (heading colors)

`HeadingVisualTransformation` applies `SpanStyle(color = headingColor)` to heading
lines without modifying the underlying text. Uses `OffsetMapping.Identity` — no
offset shift. Existing spans are preserved via `buildAnnotatedString { append(text) }`.

### Color theme

Read active colors via a single Tcl call:
```kotlin
val raw = engine.eval("android-get-theme")   // returns "bg fg headingColor"
```
The `android-get-theme` proc (in `boot-android.tcl`) returns the correct values
based on `cfg_dark_mode`. Never read `cfg_bg` / `cfg_fg` separately.

### Timer

The tick is driven by a Kotlin coroutine (`delay(1000)` + `android-timer-tick`).
Never call the native `timer-start` / `timer-tick` procs (they use `after`, which
requires a Tcl event loop that is absent on Android).

`timerLastTick == 0L` in Kotlin <-> `timer_last_tick == 0` in Tcl -> timer never
started or reset (hides the timer in the status bar).

### External file save

Via `contentResolver.openOutputStream()` if `canWrite`. The URI is stored in
`_externalUri` in the ViewModel for subsequent saves without re-picking.

### Config reload (`reloadConfig`)

After saving `writhdeck.ini`: re-run `ini-load + keys-init` in the engine,
then update all affected StateFlows (theme, headingMarker, markdownHeadings...).

---

## Kotlin/Compose patterns

- `collectAsStateWithLifecycle()` for all ViewModel StateFlows
- `remember(key) { ... }` for expensive derived values (parseHexColor, buildToc)
- `ModalBottomSheet` for overlays (commands, TOC)
- `BackHandler` to intercept the system back gesture (e.g. exiting distraction-free mode)
- `imePadding()` on the editor to prevent the soft keyboard from covering text

---

## What not to do

- Do not modify `state.tcl` / `config.tcl` in `assets/tcl/` — they are overwritten by Gradle.
- Do not call `ini-load` directly from Kotlin without calling `schemes-init` first.
- Do not commit `tcl-android/`, `tcl8.6.15/`, `app/src/main/assets/tcl/state.tcl`,
  `app/src/main/assets/tcl/config.tcl`, `app/src/main/assets/tcl/schemes/` (listed in `.gitignore`).
- Never commit on behalf of the user — let the user decide when and how to commit.
