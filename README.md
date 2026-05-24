# WrithDeck Android

Android app for [WrithDeck](https://github.com/luginf/writhdeck) — a distraction-free text editor for writers.

The WrithDeck Tcl engine runs as-is on Android via a JNI bridge, providing full parity for persistence, configuration and writing statistics with the desktop/TUI versions.

---

## Features

- File browser (Documents/WrithDeck/ folder)
- Full-screen text editor with heading syntax highlighting
- Table of contents (TOC)
- Distraction-free mode (fullscreen)
- Countdown timer / stopwatch
- Daily writing statistics
- Word frequency analysis
- Color themes (alt01, alt02, gruvbox, nord, solarized, everforest, retro...)
- Edit `writhdeck.ini` directly inside the app
- Open `.txt` files from an external file manager
- Config shared with desktop versions via `writhdeck.ini`

---

## Requirements

- Android Studio (Ladybug or newer)
- NDK installed via SDK Manager (r25c+)
- CMake 3.22+
- Tcl 8.6.15 sources (to compile `libtcl8.6.a`)
- [writhdeck](https://github.com/luginf/writhdeck) repository cloned **alongside** this one

Expected layout:
```
parent/
  writhdeck/          <- main repository (Tcl/Tk)
  writhdeck-android/  <- this repository
```

The Gradle task `copyTclModules` reads `../writhdeck/src/` to sync `state.tcl`,
`config.tcl` and color schemes on every build.

---

## Build from scratch

```sh
cd writhdeck-android/

# 1. Download and extract Tcl sources
wget https://prdownloads.sourceforge.net/tcl/tcl8.6.15-src.tar.gz
tar xzf tcl8.6.15-src.tar.gz

# 2. Compile libtcl8.6.a for each ABI
./tools/build-tcl-android.sh arm64-v8a   # physical device
./tools/build-tcl-android.sh x86_64      # emulator

# 3. gradle-wrapper.jar (if missing)
gradle wrapper --gradle-version=8.9
# or: wget https://github.com/gradle/gradle/raw/v8.9.0/gradle/wrapper/gradle-wrapper.jar \
#          -O gradle/wrapper/gradle-wrapper.jar

# 4. Build
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/writhdeck-debug.apk
```

---

## Architecture

```
Kotlin + Jetpack Compose UI
        |
WrithdeckEngine.kt  (JNI wrapper)
        |
writhdeck_jni.c  (C bridge)
        |
libtcl8.6.a  (static Tcl 8.6, NDK)
        |
boot-android.tcl + state.tcl + config.tcl
```

| Tcl side | Kotlin side |
|---|---|
| `.writhdeck.json` persistence | UI, navigation, lifecycle |
| `writhdeck.ini` config | In-memory word count |
| Timer (state + logic) | Timer tick (coroutine `delay(1000)`) |
| Word occurrences | TOC (`buildToc`) |
| Daily stats | Compose rendering |
