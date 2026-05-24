# WrithDeck Android — référence développement

Pour les patterns côté moteur Tcl (`state.tcl`, `config.tcl`, timer, stats, INI…),
consulter **`../writhdeck/SKILLS.md`** — ce fichier couvre uniquement la couche Android
(JNI, Kotlin, Compose).

---

## Architecture Tcl / Kotlin

| Responsabilité | Couche |
|---|---|
| Persistance `.writhdeck.json` (curseurs, favoris, récents, stats) | Tcl `state.tcl` |
| Config `writhdeck.ini` (ini-load/save, profils, thèmes, clés) | Tcl `config.tcl` |
| Timer — état, logique countdown/stopwatch | Tcl `android-timer-*` |
| Timer — tick toutes les secondes | Kotlin coroutine `delay(1000)` |
| Comptage de mots en temps réel | Kotlin (contenu mémoire) |
| Occurrences de mots | Tcl `android-word-occurrences` |
| Stats journalières | Tcl `android-get-stats` → `daily-update` |
| Couleurs du thème actif | Tcl `android-get-theme` → Kotlin |
| TOC (`buildToc`) | Kotlin |
| UI, navigation, cycle de vie | Kotlin + Jetpack Compose |

---

## Bridge JNI

### Ordre d'init (`writhdeck_jni.c`) — critique

```c
// 1. Créer l'interpréteur
interp = Tcl_CreateInterp();
// 2. tcl_library AVANT Tcl_Init — sinon Tcl cherche /usr/local/lib/tcl8.6 → échec
snprintf(lib_path, sizeof(lib_path), "%s/tcl/lib/tcl8.6", dir);
Tcl_SetVar(interp, "tcl_library", lib_path, TCL_GLOBAL_ONLY);
// 3. Tcl_Init — loggué mais non fatal si raté
if (Tcl_Init(interp) != TCL_OK) { LOGE("Tcl_Init: %s", ...); }
// 4. Variable d'environnement pour boot-android.tcl
Tcl_SetVar(interp, "::ANDROID_FILES_DIR", dir, TCL_GLOBAL_ONLY);
```

Si `tcl_library` est positionné après `Tcl_Init`, Tcl ne trouve pas `init.tcl`,
l'interpréteur est inutilisable, `boot-android.tcl` ne tourne pas → ini vide.

### Fonctions exposées

| Fonction JNI | Usage |
|---|---|
| `nativeInit(filesDir)` | Crée l'interpréteur, positionne `tcl_library`, source `boot-android.tcl` |
| `nativeEval(script)` | Évalue du Tcl, retourne le résultat comme String |
| `nativeGetVar(varName)` | Lit une variable globale Tcl |
| `nativeSetVar(varName, value)` | Positionne une variable globale Tcl |
| `nativeDestroy()` | Détruit l'interpréteur |

Tous les appels JNI se font sur `Dispatchers.IO` (thread-safety Tcl).

---

## Boot Tcl (`boot-android.tcl`)

### Ordre obligatoire

```tcl
# Charger les modules moteur
foreach _mod {state config} { source ... }
# Charger les schémas de couleur
foreach _sf [glob .../schemes/*.tcl] { catch { source $_sf } }
schemes-init          # ← AVANT ini-load : peuple cfg_schemes pour scheme-apply
file mkdir $::DOCS_DIR_DEFAULT  # ← AVANT ini-load : sinon ini-save échoue (dossier absent)
ini-load              # ← appelle scheme-apply si scheme != default
keys-init
state-load
```

### Procs Android exposées

| Proc | Retour | Usage |
|---|---|---|
| `android-timer-start/pause/resume/reset` | `"active remaining"` | Contrôle timer |
| `android-timer-tick` | `"active remaining"` | Appelée par coroutine Kotlin toutes les secondes |
| `android-timer-state` | `"active remaining lastTick"` | État initial au démarrage du ViewModel |
| `android-get-theme` | `"bg fg headingColor"` | Couleurs hex du thème courant |
| `android-word-occurrences {text}` | `"mot\tcount\n..."` | Fréquence des mots du texte en mémoire |
| `android-get-stats {filepath words}` | `"date\twords\n..."` | Stats journalières du fichier |

---

## Tâche Gradle `copyTclModules`

Définie dans `app/build.gradle.kts`, dépendance de `preBuild`.

```
../writhdeck/src/state.tcl      → assets/tcl/state.tcl
../writhdeck/src/config.tcl     → assets/tcl/config.tcl
../writhdeck/src/schemes/*.tcl  → assets/tcl/schemes/
tcl8.6.15/library/…            → assets/tcl/lib/tcl8.6/  (commité, pas copié)
```

Le dépôt `writhdeck` doit être cloné à `../writhdeck/` (côte à côte dans le même
dossier parent). `assets/tcl/lib/tcl8.6/` est commité — la stdlib Tcl est stable
sur toute la série 8.6.x et n'a pas besoin d'être régénérée.

---

## Patterns Kotlin / Compose

### BasicTextField — curseur

```kotlin
// Correct : remember sans clé de contenu
var tfv by remember { mutableStateOf(TextFieldValue(content)) }
// Synchroniser sur ouverture de fichier externe uniquement
LaunchedEffect(content) {
    if (content != tfv.text) tfv = TextFieldValue(content)
}
```

`remember(content) { ... }` réinitialise le curseur à 0 à chaque frappe — ne pas faire.

### VisualTransformation (coloration titres)

`HeadingVisualTransformation` colore les lignes de titre sans modifier le texte.
- `OffsetMapping.Identity` — pas de décalage d'offset
- `buildAnnotatedString { append(text) }` — conserve les spans existants
- `remember(headingMarker, markdownHeadings, hdColor)` pour ne pas recalculer à chaque frappe

```kotlin
visualTransformation = remember(headingMarker, markdownHeadings, hdColor) {
    HeadingVisualTransformation(headingMarker, markdownHeadings, hdColor)
}
```

### Couleurs du thème

Lire via un seul appel Tcl pour éviter plusieurs eval :
```kotlin
val raw = engine.eval("android-get-theme")   // "bg fg headingColor"
val parts = raw.split(Regex("\\s+"))
```

Convertir hex → `Color` avec `parseHexColor()` qui gère `Color.Unspecified` sur erreur.
Appliquer via `remember(themeColors.bg) { parseHexColor(themeColors.bg) }`.

### Rechargement config

Après sauvegarde de `writhdeck.ini` :
```kotlin
engine.eval("ini-load")
engine.eval("keys-init")
_themeColors.value = loadThemeColors()
_headingMarker.value = engine.getVar("::cfg_heading_marker")
// ... tous les StateFlows affectés par l'INI
```

### TOC (`buildToc`)

Détection des titres par string (startsWith/endsWith + boucle de comptage itératif),
**pas de regex** — `Regex.escape("=")` produit `\Q=\E` qui ne fonctionne pas comme
quantificateur en Kotlin/Java.

Guard contre crash sur marqueur seul (ex. `"="`) :
```kotlin
val title = if (end > pos) trimmed.substring(pos, end).trim() else ""
if (level > 0 && title.isNotEmpty()) entries.add(...)
```

### Distraction-free mode

```kotlin
var distractionFree by remember { mutableStateOf(false) }
if (distractionFree) BackHandler { distractionFree = false }
// Scaffold : topBar et bottomBar conditionnels sur !distractionFree
// Bouton FullscreenExit en overlay TopEnd dans le Box de l'éditeur
```

`BackHandler` intercepte le geste retour système avant la navigation — sans lui,
appuyer sur Retour quitte l'éditeur au lieu de sortir du mode.

### Fichiers externes (intent ACTION_VIEW / ACTION_EDIT)

```kotlin
// MainActivity.kt : lire intent.data au démarrage et onNewIntent
vm.openExternalContent(uri, contentResolver, canWrite)
// Sauvegarde via ContentResolver (pas File I/O direct)
contentResolver.openOutputStream(uri, "wt")?.use { ... }
```

`canWrite` = `intent.action == ACTION_EDIT || checkUriPermission(WRITE)`.
L'URI est conservé dans `_externalUri` pour les sauvegardes suivantes.

---

## Timer Android

Le timer Tcl n'a pas d'event loop → tick Kotlin :

```kotlin
// Démarrer
val result = engine.eval("android-timer-start")   // "1 1500"
// Coroutine tick
while (true) {
    delay(1000)
    val (active, remaining) = engine.eval("android-timer-tick").split(" ")
    _timerActive.value = active == "1"
    _timerRemaining.value = remaining.toInt()
}
```

`timerLastTick == 0L` → timer jamais démarré ou réinitialisé → masquer dans la barre.
`timerLastTick != 0L && !timerActive` → en pause → afficher remaining.

Ne **jamais** appeler `timer-start` / `timer-tick` natifs (utilisent `after` → requiert
event loop Tcl absente sur Android).

---

## CMake / NDK

```cmake
set(TCL_ABI_DIR "${CMAKE_SOURCE_DIR}/../../../tcl-android/${ANDROID_ABI}/install")
add_library(writhdeck SHARED writhdeck_jni.c)
target_include_directories(writhdeck PRIVATE ${TCL_ABI_DIR}/include)
target_link_libraries(writhdeck ${TCL_ABI_DIR}/lib/libtcl8.6.a android log m)
```

ABIs actives : `arm64-v8a` (device) + `x86_64` (émulateur). `armeabi-v7a` désactivé.

Cross-compilation Tcl — points critiques NDK r26+ :
- `clang --target=...` direct (pas les wrappers `${triple}${api}-clang` — échouent sous autoconf)
- `--sysroot=$TOOLCHAIN/sysroot` dans `CFLAGS` et `LDFLAGS`
- `--build=$(uname -m)-linux-gnu` pour que autoconf détecte la cross-compilation
- `-fPIC` requis — `libtcl8.6.a` est linkée dans `libwrithdeck.so` (shared lib)

---

## Implémenté

- **JNI bridge** : `nativeInit/Eval/GetVar/SetVar/Destroy`
- **Boot Tcl** : `boot-android.tcl` avec ordre correct (`schemes-init` avant `ini-load`)
- **Navigateur de fichiers** : liste `.txt`/`.md`/`.ini` dans Documents/WrithDeck/
- **Éditeur** : `BasicTextField` monospace, `VisualTransformation` pour les titres
- **TOC** : `buildToc` (WrithDeck `= heading =` + markdown `# heading`)
- **Mode commande** : `ModalBottomSheet` avec timer, stats, occurrences, TOC, heading
- **Heading toggle** : `applyHeading(tfv, marker)` — bascule les marqueurs sur la sélection
- **Mode distraction-free** : Scaffold conditionnel + `BackHandler`
- **Timer** : coroutine Kotlin + procs `android-timer-*`
- **Stats journalières** : `android-get-stats` → dialog `AlertDialog`
- **Occurrences de mots** : `android-word-occurrences` → dialog scrollable
- **Thèmes de couleur** : `android-get-theme` → `parseHexColor` → `background`/`color`
- **Édition writhdeck.ini** : bouton engrenage → `openIniFile()` → `reloadConfig()`
- **Fichiers externes** : intent `ACTION_VIEW`/`ACTION_EDIT` + sauvegarde via ContentResolver
- **Backup** : `android-backup {path}` → `documents/backups/`

---

## Idées non encore implémentées

- Sélecteur de profil / thème dans l'UI (actuellement via édition de l'INI)
- Scratchpad intégré (WS2 Android)
- Support des fichiers `.md` avec preview basique
- Recherche dans le texte (Ctrl+F)
- Partage de fichier (intent `ACTION_SEND`)
- Synchronisation avec un dépôt git (SSH ou HTTP)
- Widget de comptage de mots pour l'écran d'accueil
- Police personnalisée (import TTF)
