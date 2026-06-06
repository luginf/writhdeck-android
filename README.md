# WrithDeck Android

Android app for [WrithDeck](https://github.com/luginf/writhdeck) — a distraction-free text editor for writers.

Pure Kotlin + Jetpack Compose. No Tcl/JNI engine — all logic (config parsing, state persistence, color schemes) is implemented directly in Kotlin.

---

## Features

- File browser (`Documents/WrithDeck/` folder)
- Full-screen text editor with heading syntax highlighting
- Table of contents (TOC)
- Distraction-free mode (fullscreen)
- Countdown timer / stopwatch
- Daily writing statistics
- Two independent workspaces
- Scratchpad (persistent quick-note file)
- Color themes: alt01, alt02, gruvbox, nord, solarized, everforest, retro, and custom schemes
- Edit `writhdeck.ini` directly inside the app
- Open `.txt` files from an external file manager
- Config shared with desktop versions via `writhdeck.ini`

---

## Requirements

- Android Studio (Ladybug or newer)
- Android SDK (minSdk 26 / targetSdk 35)
- No NDK, no native dependencies

---

## Build

```sh
cd writhdeck-android/
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/writhdeck-debug.apk
```

---

## Architecture

```
Kotlin + Jetpack Compose UI
        |
WrithdeckViewModel.kt  (StateFlows, business logic)
        |
AppConfig.kt    — IniParser, ThemeColors
StateStore.kt   — hand-rolled JSON persistence (.writhdeck.json)
ColorSchemes.kt — 8 built-in schemes + custom schemes
```

| Module | Role |
|---|---|
| `AppConfig.kt` | INI parse/write, profile support, custom scheme sections |
| `StateStore.kt` | Load/save app state, path migration, cursor restore |
| `ColorSchemes.kt` | `SchemeColors` + 8 built-in color schemes |
| `WrithdeckViewModel.kt` | All business logic: docs, timer, favorites, autosave, workspaces |
| `ui/EditorScreen.kt` | `AndroidView { EditText }` — virtualized for large files |
| `ui/BrowserScreen.kt` | File browser, keyboard shortcuts |
| `ui/SchemeConfigScreen.kt` | Scheme selector + custom scheme editor |
| `ui/SettingsScreen.kt` | Font, margins, autosave, timer settings |
