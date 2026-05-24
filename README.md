# WrithDeck Android

Application Android pour [WrithDeck](https://github.com/luginf/writhdeck) — éditeur de texte sans distraction pour écrivains.

Le moteur Tcl de WrithDeck tourne tel quel sur Android via un bridge JNI, assurant une parité complète de la persistance, de la configuration et des statistiques avec les versions desktop/TUI.

---

## Fonctionnalités

- Navigateur de fichiers (dossier Documents/WrithDeck/)
- Éditeur plein texte avec coloration syntaxique des titres
- Table des matières (TOC)
- Mode sans distraction (plein écran)
- Timer compte à rebours / chronomètre
- Statistiques d'écriture journalières
- Occurrences de mots
- Thèmes de couleur (alt01, alt02, gruvbox, nord, solarized, everforest, retro…)
- Édition du fichier `writhdeck.ini` directement dans l'app
- Ouverture de fichiers `.txt` depuis un gestionnaire de fichiers externe
- Config partagée avec les versions desktop via `writhdeck.ini`

---

## Prérequis

- Android Studio (Ladybug ou plus récent)
- NDK installé via SDK Manager (r25c+)
- CMake 3.22+
- Sources Tcl 8.6.15 (pour compiler `libtcl8.6.a`)
- Dépôt [writhdeck](https://github.com/luginf/writhdeck) cloné **à côté** de ce dépôt

Structure attendue :
```
parent/
  writhdeck/          ← dépôt principal (Tcl/Tk)
  writhdeck-android/  ← ce dépôt
```

La tâche Gradle `copyTclModules` lit `../writhdeck/src/` pour synchroniser `state.tcl`,
`config.tcl` et les schémas de couleur à chaque build.

---

## Build depuis zéro

```sh
cd writhdeck-android/

# 1. Télécharger et décompresser les sources Tcl
wget https://prdownloads.sourceforge.net/tcl/tcl8.6.15-src.tar.gz
tar xzf tcl8.6.15-src.tar.gz

# 2. Compiler libtcl8.6.a pour chaque ABI
./tools/build-tcl-android.sh arm64-v8a   # device physique
./tools/build-tcl-android.sh x86_64      # émulateur

# 3. gradle-wrapper.jar (si absent)
gradle wrapper --gradle-version=8.9
# ou : wget https://github.com/gradle/gradle/raw/v8.9.0/gradle/wrapper/gradle-wrapper.jar \
#           -O gradle/wrapper/gradle-wrapper.jar

# 4. Build
./gradlew assembleDebug
# → app/build/outputs/apk/debug/writhdeck-debug.apk
```

Voir `ANDROID.md` dans le dépôt `writhdeck` pour la documentation complète de l'architecture.

---

## Architecture

```
UI Kotlin + Jetpack Compose
        ↕
WrithdeckEngine.kt  (JNI wrapper)
        ↕
writhdeck_jni.c  (bridge C)
        ↕
libtcl8.6.a  (Tcl 8.6 statique, NDK)
        ↕
boot-android.tcl + state.tcl + config.tcl
```

| Côté Tcl | Côté Kotlin |
|---|---|
| Persistance `.writhdeck.json` | UI, navigation, cycle de vie |
| Config `writhdeck.ini` | Comptage de mots en mémoire |
| Timer (état + logique) | Tick timer (coroutine `delay(1000)`) |
| Occurrences de mots | TOC (`buildToc`) |
| Stats journalières | Rendu Compose |
