# CLAUDE.md — WrithDeck Android

Instructions pour Claude Code dans ce dépôt.

## Projet compagnon

Ce dépôt est le frontend Android de WrithDeck. Le moteur Tcl et toutes les règles
concernant `state.tcl`, `config.tcl`, `boot-android.tcl`, les schémas de couleur,
l'INI, les stats journalières et le timer sont documentées dans :

**`../writhdeck/CLAUDE.md`** — règles générales du moteur Tcl  
**`../writhdeck/ANDROID.md`** — architecture Android, ordre d'init JNI, tâche Gradle  
**`../writhdeck/SKILLS.md`** — référence technique complète (patterns Android inclus)

Toujours consulter ces fichiers avant de modifier `boot-android.tcl` ou les interactions
Kotlin ↔ Tcl. Le dépôt writhdeck est attendu à `../writhdeck/` (côte à côte dans le
même répertoire parent).

---

## Build

```sh
# Depuis writhdeck-android/
./tools/build-tcl-android.sh arm64-v8a   # device
./tools/build-tcl-android.sh x86_64      # émulateur
./gradlew assembleDebug
# → app/build/outputs/apk/debug/writhdeck-debug.apk
```

La tâche `preBuild` (Gradle) copie automatiquement `../writhdeck/src/state.tcl`,
`../writhdeck/src/config.tcl` et `../writhdeck/src/schemes/*.tcl` dans les assets.
`app/src/main/assets/tcl/lib/tcl8.6/` est commité — pas besoin de le régénérer.

---

## Fichiers clés

| Fichier | Rôle |
|---|---|
| `app/src/main/assets/tcl/boot-android.tcl` | Bootstrap Tcl : charge state+config, définit les procs `android-*` |
| `app/src/main/cpp/writhdeck_jni.c` | Bridge JNI C : `nativeInit`, `nativeEval`, `nativeGetVar`, `nativeSetVar` |
| `app/src/main/java/com/writhdeck/app/WrithdeckEngine.kt` | Wrapper Kotlin du bridge JNI |
| `app/src/main/java/com/writhdeck/app/WrithdeckViewModel.kt` | ViewModel : StateFlows, init moteur, timer, config |
| `app/src/main/java/com/writhdeck/app/ui/EditorScreen.kt` | Éditeur Compose : BasicTextField, TOC, mode commande, distraction-free |
| `app/src/main/java/com/writhdeck/app/ui/BrowserScreen.kt` | Navigateur de fichiers Compose |
| `app/build.gradle.kts` | Config Gradle + tâche `copyTclModules` |
| `app/src/main/cpp/CMakeLists.txt` | Config CMake NDK |
| `tools/build-tcl-android.sh` | Cross-compilation Tcl 8.6 → `libtcl8.6.a` |

---

## Règles critiques

### JNI init (`writhdeck_jni.c`)

`tcl_library` doit être positionné **avant** `Tcl_Init()` — sans ça, Tcl cherche
`/usr/local/lib/tcl8.6/`, échoue silencieusement, et `boot-android.tcl` ne tourne
jamais (ini vide, pas de config).

```c
interp = Tcl_CreateInterp();
Tcl_SetVar(interp, "tcl_library", lib_path, TCL_GLOBAL_ONLY);  // AVANT Tcl_Init
if (Tcl_Init(interp) != TCL_OK) { LOGE(...); }  // non fatal
Tcl_SetVar(interp, "::ANDROID_FILES_DIR", dir, TCL_GLOBAL_ONLY);
```

### Boot Tcl (`boot-android.tcl`)

Ordre obligatoire :
1. Source `state.tcl` + `config.tcl`
2. Source `schemes/*.tcl`
3. `schemes-init` — peuple `cfg_schemes` **avant** `ini-load`
4. `file mkdir $::DOCS_DIR_DEFAULT` — **avant** `ini-load` (sinon `ini-save` échoue)
5. `ini-load` → `keys-init` → `state-load`

### BasicTextField et curseur

Utiliser `remember { }` **sans clé de contenu**. `remember(content) { }` réinitialise
le curseur à 0 à chaque frappe. `LaunchedEffect(content)` gère les synchronisations
sur ouverture de fichier externe.

### VisualTransformation (coloration titres)

`HeadingVisualTransformation` applique `SpanStyle(color = headingColor)` sur les lignes
de titre sans modifier le texte sous-jacent. Utilise `OffsetMapping.Identity` — pas
de décalage d'offset. Les spans existants sont conservés via `buildAnnotatedString { append(text) }`.

### Thème de couleur

Lire les couleurs actives via un seul appel Tcl :
```kotlin
val raw = engine.eval("android-get-theme")   // retourne "bg fg headingColor"
```
La proc `android-get-theme` (dans `boot-android.tcl`) retourne les valeurs correctes
selon `cfg_dark_mode`. Ne jamais lire `cfg_bg` / `cfg_fg` séparément.

### Timer

Le tick est piloté par une coroutine Kotlin (`delay(1000)` + `android-timer-tick`).
Ne jamais appeler les procs `timer-start` / `timer-tick` natives (elles utilisent
`after`, ce qui requiert une event loop Tcl absente sur Android).

`timerLastTick == 0L` en Kotlin ↔ `timer_last_tick == 0` en Tcl → timer jamais
démarré ou réinitialisé (masque le timer dans la barre de statut).

### Sauvegarde fichier externe

Via `contentResolver.openOutputStream()` si `canWrite`. L'URI est conservé dans
`_externalUri` du ViewModel pour les sauvegardes suivantes sans re-picker.

### Rechargement config (`reloadConfig`)

Après sauvegarde de `writhdeck.ini` : relancer `ini-load + keys-init` dans le moteur,
puis mettre à jour tous les StateFlows (thème, headingMarker, markdownHeadings…).

---

## Patterns Kotlin/Compose

- `collectAsStateWithLifecycle()` pour tous les StateFlows du ViewModel
- `remember(key) { ... }` pour les valeurs dérivées coûteuses (parseHexColor, buildToc)
- `ModalBottomSheet` pour les overlays (commandes, TOC)
- `BackHandler` pour intercepter le retour système (ex. sortie du mode distraction-free)
- `imePadding()` sur l'éditeur pour éviter que le clavier soft masque le texte

---

## Ce qu'il ne faut pas faire

- Ne pas modifier `state.tcl` / `config.tcl` dans `assets/tcl/` — ils sont écrasés par Gradle.
- Ne pas appeler `ini-load` directement depuis Kotlin sans appeler `schemes-init` avant.
- Ne pas commiter `tcl-android/`, `tcl8.6.15/`, `app/src/main/assets/tcl/state.tcl`,
  `app/src/main/assets/tcl/config.tcl`, `app/src/main/assets/tcl/schemes/` (ignorés par `.gitignore`).
- Ne jamais commiter au nom de l'utilisateur — laisser l'utilisateur décider des commits.
