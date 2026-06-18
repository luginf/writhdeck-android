package com.writhdeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Context
import android.graphics.Typeface
import android.os.Environment
import android.view.textservice.TextServicesManager
import java.io.File
import com.writhdeck.app.BUILTIN_SCHEMES
import com.writhdeck.app.EDITOR_FONTS
import com.writhdeck.app.SettingsData
import com.writhdeck.app.WrithdeckViewModel

// Tab order mirrors the Tcl/Tk desktop config dialog: Profile, Display, Fonts, Schemes, Timer, Misc
private val SETTINGS_TABS = listOf("Profile", "Display", "Fonts", "Schemes", "Timer", "Misc")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: WrithdeckViewModel,
    onBack: () -> Unit,
    onEditIni: () -> Unit,
    onNavigateSchemes: () -> Unit = {}
) {
    val activeScheme by vm.activeScheme.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    val profileNames by vm.profileNames.collectAsStateWithLifecycle()
    // Re-read when activeScheme/activeProfile changes (e.g., user picked a scheme in
    // SchemeConfigScreen and came back, or switched profile — profile sections override
    // margins/word goal/scheme/etc., so the whole settings snapshot must be refreshed)
    var s by remember(activeScheme, activeProfile) { mutableStateOf(vm.getSettingsData()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { vm.applySettings(s); onBack() }) {
                        Text("Save")
                    }
                }
            )
        }
    ) { padding ->
        // Same fix as EditorScreen: Scaffold's content Box doesn't shrink for the IME, so
        // add extra bottom padding equal to the keyboard's overlap with this Box, letting
        // the scrollable settings list be scrolled clear of the keyboard.
        val extraBottomPadPx = (WindowInsets.ime.getBottom(density) -
            with(density) { padding.calculateBottomPadding().roundToPx() }).coerceAtLeast(0)
        val extraBottomPad = with(density) { extraBottomPadPx.toDp() }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 8.dp) {
                SETTINGS_TABS.forEachIndexed { i, label ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = { Text(label, fontFamily = FontFamily.Monospace) }
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp + extraBottomPad)
            ) {
                when (selectedTab) {
                    0 -> ProfileTab(
                        s, activeProfile, profileNames,
                        onSwitchProfile = vm::switchProfile,
                        onNewProfile    = { name -> vm.newProfile(name, s) },
                        onDeleteProfile = { vm.deleteProfile() }
                    ) { s = it }
                    1 -> DisplayTab(s) { s = it }
                    2 -> FontsTab(s) { s = it }
                    3 -> SchemesTab(s, vm, onNavigateSchemes = { vm.applySettings(s); onNavigateSchemes() }) { s = it }
                    4 -> TimerTab(s) { s = it }
                    5 -> MiscTab(s, vm, onEditIni = { vm.applySettings(s); onEditIni() }) { s = it }
                }
            }
        }
    }
}

private val PROFILE_NAME_RE = Regex("^\\w+$")

@Composable
private fun ProfileTab(
    s: SettingsData,
    activeProfile: String,
    profileNames: List<String>,
    onSwitchProfile: (String) -> Unit,
    onNewProfile: (String) -> Unit,
    onDeleteProfile: () -> Unit,
    onChange: (SettingsData) -> Unit
) {
    var showNewDialog     by remember { mutableStateOf(false) }
    var newProfileName    by remember { mutableStateOf("") }
    var nameError         by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    SettingsSection("Active profile")
    DropdownSettingRow("Profile", activeProfile, profileNames, onChange = onSwitchProfile)

    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = { newProfileName = ""; nameError = ""; showNewDialog = true }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("New profile", fontFamily = FontFamily.Monospace)
        }
        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            enabled = profileNames.size > 1,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            border = ButtonDefaults.outlinedButtonBorder(enabled = profileNames.size > 1).copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    if (profileNames.size > 1) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
            )
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Delete", fontFamily = FontFamily.Monospace)
        }
    }

    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("New profile", fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    Text("Enter a name (letters, digits, _ only).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it; nameError = "" },
                        singleLine = true,
                        isError = nameError.isNotEmpty(),
                        supportingText = if (nameError.isNotEmpty()) {{ Text(nameError) }} else null,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = newProfileName.trim()
                    when {
                        n.isEmpty()                    -> nameError = "Name cannot be empty."
                        !PROFILE_NAME_RE.matches(n)    -> nameError = "Letters, digits and _ only."
                        profileNames.contains(n)       -> nameError = "Profile \"$n\" already exists."
                        else -> { onNewProfile(n); showNewDialog = false }
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete profile", fontFamily = FontFamily.Monospace) },
            text = { Text("Delete profile \"$activeProfile\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { onDeleteProfile(); showDeleteConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    SettingsSection("Profile settings")
    DropdownSettingRow("Dark mode", s.androidDarkMode, listOf("auto", "yes", "no")) {
        onChange(s.copy(androidDarkMode = it))
    }
    IntSettingRow("Margin width", s.marginWidth, 0, 200) { onChange(s.copy(marginWidth = it)) }
    IntSettingRow("Margin height", s.marginHeight, 0, 200) { onChange(s.copy(marginHeight = it)) }
    IntSettingRow("Word goal", s.wordGoal, 0, 99999) { onChange(s.copy(wordGoal = it)) }
    FloatSettingRow("Line spacing", s.lineSpacing, 0.8f, 3.0f, 0.1f) { onChange(s.copy(lineSpacing = it)) }
}

@Composable
private fun DisplayTab(s: SettingsData, onChange: (SettingsData) -> Unit) {
    SettingsSection("Status bar")
    StringSettingRow("Left", s.statusLeft) { onChange(s.copy(statusLeft = it)) }
    StringSettingRow("Center", s.statusCenter) { onChange(s.copy(statusCenter = it)) }
    StringSettingRow("Right", s.statusRight) { onChange(s.copy(statusRight = it)) }

    SettingsSection("Editor")
    StringSettingRow("Heading marker", s.headingMarker) { onChange(s.copy(headingMarker = it)) }
    SwitchSettingRow("Markdown headings (#)", s.markdownHeadings) { onChange(s.copy(markdownHeadings = it)) }
    SwitchSettingRow("Block cursor", s.blockCursor) { onChange(s.copy(blockCursor = it)) }
    SwitchSettingRow("Spell check", s.spellCheckEnabled) { onChange(s.copy(spellCheckEnabled = it)) }
    DropdownSettingRow("Spell check language", s.spellCheckLanguage, spellCheckLanguageOptions()) {
        onChange(s.copy(spellCheckLanguage = it))
    }

    SettingsSection("Markup")
    StringSettingRow("Comment marker", s.commentMarker) { onChange(s.copy(commentMarker = it)) }
    StringSettingRow("Bold marker", s.boldMarker) { onChange(s.copy(boldMarker = it)) }
    StringSettingRow("Italic marker", s.italicMarker) { onChange(s.copy(italicMarker = it)) }
    StringSettingRow("Underline marker", s.underlineMarker) { onChange(s.copy(underlineMarker = it)) }
    StringSettingRow("Strikethrough marker", s.strikethroughMarker) { onChange(s.copy(strikethroughMarker = it)) }
}

@Composable
private fun FontsTab(s: SettingsData, onChange: (SettingsData) -> Unit) {
    IntSettingRow("Font size", s.fontSize, 10, 32) { onChange(s.copy(fontSize = it)) }
    FontFamilySettingRow(s.fontFamily) { onChange(s.copy(fontFamily = it)) }
    SwitchSettingRow("Bold", s.fontBold) { onChange(s.copy(fontBold = it)) }
    FontPreview(s.fontFamily, s.fontSize, s.fontBold)
}

/** Live sample of the editor font — same family/size/weight the editor will use. */
@Composable
private fun FontPreview(familyName: String, fontSizeSp: Int, bold: Boolean) {
    val style = if (bold) Typeface.BOLD else Typeface.NORMAL
    Text(
        text = "The quick brown fox jumps over the lazy dog — 0123456789",
        fontFamily = remember(familyName, style) { FontFamily(Typeface.create(familyName, style)) },
        fontSize = fontSizeSp.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .padding(12.dp)
    )
}

/** Font family picker. Options are Android's built-in generic font-family aliases
 *  (resolved via `Typeface.create`, no embedded font files) — a mix of monospace,
 *  serif and sans-serif faces. Each entry previews in its own typeface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontFamilySettingRow(selected: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = remember(selected) { EDITOR_FONTS.firstOrNull { it.familyName == selected } ?: EDITOR_FONTS[0] }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Spacebar, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        expanded = !expanded
                        true
                    }
                    Key.DirectionDown -> {
                        val i = EDITOR_FONTS.indexOf(current)
                        onChange(EDITOR_FONTS[(i + 1).mod(EDITOR_FONTS.size)].familyName)
                        true
                    }
                    Key.DirectionUp -> {
                        val i = EDITOR_FONTS.indexOf(current)
                        onChange(EDITOR_FONTS[(i - 1).mod(EDITOR_FONTS.size)].familyName)
                        true
                    }
                    Key.Escape -> if (expanded) { expanded = false; true } else false
                    else -> false
                }
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Font family",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.width(190.dp)
        ) {
            OutlinedTextField(
                value = current.label,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                    .focusProperties { canFocus = false },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily(Typeface.create(current.familyName, Typeface.NORMAL))
                )
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                EDITOR_FONTS.forEach { font ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                font.label,
                                fontFamily = FontFamily(Typeface.create(font.familyName, Typeface.NORMAL)),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onClick = { onChange(font.familyName); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun SchemesTab(
    s: SettingsData,
    vm: WrithdeckViewModel,
    onNavigateSchemes: () -> Unit,
    onChange: (SettingsData) -> Unit
) {
    val customSchemes by vm.customSchemes.collectAsStateWithLifecycle()
    val colors = remember(s.scheme, customSchemes) {
        customSchemes[s.scheme] ?: BUILTIN_SCHEMES[s.scheme] ?: BUILTIN_SCHEMES["default"]!!
    }

    DropdownSettingRow(
        label = "Scheme",
        selected = s.scheme,
        options = remember { vm.getAllSchemeNames() }
    ) { onChange(s.copy(scheme = it)) }

    Row(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Dark", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Spacer(Modifier.width(4.dp))
        listOf(colors.bg, colors.heading, colors.comment, colors.markup).forEach { SchemePreviewSwatch(it) }
        Spacer(Modifier.width(10.dp))
        Text("Light", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Spacer(Modifier.width(4.dp))
        listOf(colors.bgAlt, colors.headingAlt, colors.commentAlt, colors.markupAlt).forEach { SchemePreviewSwatch(it) }
    }

    OutlinedButton(
        onClick = onNavigateSchemes,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Text("Edit scheme colors", fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SchemePreviewSwatch(hex: String) {
    val color = remember(hex) {
        val s = hex.trim().removePrefix("#")
        try { if (s.length != 6) Color.Gray else Color(0xFF000000.toInt() or s.toInt(16)) }
        catch (_: Exception) { Color.Gray }
    }
    Box(Modifier.size(18.dp).background(color).border(0.5.dp, Color.Gray.copy(alpha = 0.4f)))
}

@Composable
private fun TimerTab(s: SettingsData, onChange: (SettingsData) -> Unit) {
    DropdownSettingRow(
        label = "Type",
        selected = s.timerType,
        options = listOf("countdown", "stopwatch")
    ) { onChange(s.copy(timerType = it)) }
    IntSettingRow("Duration (min)", s.timerDuration, 1, 240) { onChange(s.copy(timerDuration = it)) }
    SwitchSettingRow("Sound at end", s.timerSound) { onChange(s.copy(timerSound = it)) }
    SwitchSettingRow("Alert dialog", s.timerAlert) { onChange(s.copy(timerAlert = it)) }
    SwitchSettingRow("Show in status bar", s.chronoShow) { onChange(s.copy(chronoShow = it)) }
}

@Composable
private fun MiscTab(s: SettingsData, vm: WrithdeckViewModel, onEditIni: () -> Unit, onChange: (SettingsData) -> Unit) {
    val storagePermissionGranted by vm.storagePermissionGranted.collectAsStateWithLifecycle()
    var showFolderPicker by remember { mutableStateOf(false) }

    SwitchSettingRow("Hemingway mode", s.hemingwayMode) { onChange(s.copy(hemingwayMode = it)) }

    SettingsSection("Documents folder")
    StringSettingRow("Custom folder", s.docsCustomDir, enabled = storagePermissionGranted) {
        onChange(s.copy(docsCustomDir = it))
    }
    OutlinedButton(
        onClick = { showFolderPicker = true },
        enabled = storagePermissionGranted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Text("Browse…", fontFamily = FontFamily.Monospace)
    }
    if (!storagePermissionGranted) {
        Text(
            "Requires storage permission",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }

    SettingsSection("Browser")
    StringSettingRow("File filter", s.browserFilter) { onChange(s.copy(browserFilter = it)) }
    SwitchSettingRow("Show all files (ignore filter)", s.browserShowAll) { onChange(s.copy(browserShowAll = it)) }
    SwitchSettingRow("Browse subfolders", s.browserSubdirs) { onChange(s.copy(browserSubdirs = it)) }

    SettingsSection("Autosave")
    SwitchSettingRow("Enabled", s.autosaveEnabled) { onChange(s.copy(autosaveEnabled = it)) }
    IntSettingRow("Interval (min)", s.autosaveInterval, 1, 60,
        enabled = s.autosaveEnabled) { onChange(s.copy(autosaveInterval = it)) }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = onEditIni,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Text("Edit INI directly", fontFamily = FontFamily.Monospace)
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            initialPath = s.docsCustomDir,
            onDismiss = { showFolderPicker = false },
            onSelect = {
                onChange(s.copy(docsCustomDir = it))
                showFolderPicker = false
            }
        )
    }
}

@Composable
fun FolderPickerDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val storageRoot = Environment.getExternalStorageDirectory()
    var current by remember {
        mutableStateOf(
            initialPath.trim().takeIf { it.isNotEmpty() }
                ?.let { File(it) }
                ?.takeIf { it.isDirectory }
                ?: storageRoot
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                current.absolutePath,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        },
        text = {
            val parent = current.parentFile
            val children = remember(current) {
                current.listFiles { f -> f.isDirectory && !f.isHidden }
                    ?.sortedBy { it.name.lowercase() }
                    ?: emptyList()
            }
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                if (parent != null && current.absolutePath != storageRoot.absolutePath) {
                    item {
                        Text(
                            "..",
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { current = parent }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
                items(children) { dir ->
                    Text(
                        dir.name,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { current = dir }
                            .padding(vertical = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(current.absolutePath) }) {
                Text("Use this folder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
    )
    HorizontalDivider()
}

@Composable
private fun IntSettingRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    enabled: Boolean = true,
    onChange: (Int) -> Unit
) {
    var text by remember { mutableStateOf(value.toString()) }
    // Sync from external changes (+/- buttons) without resetting cursor mid-typing
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value.toString() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        IconButton(
            onClick = { if (value > min) onChange(value - 1) },
            enabled = enabled && value > min,
            modifier = Modifier.size(36.dp)
        ) { Text("-", style = MaterialTheme.typography.titleMedium) }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                raw.toIntOrNull()?.coerceIn(min, max)?.let(onChange)
            },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.width(72.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        IconButton(
            onClick = { if (value < max) onChange(value + 1) },
            enabled = enabled && value < max,
            modifier = Modifier.size(36.dp)
        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun SwitchSettingRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Spacebar, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        onChange(!checked)
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StringSettingRow(
    label: String,
    value: String,
    enabled: Boolean = true,
    onChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(2f),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace
            )
        )
    }
}

@Composable
private fun FloatSettingRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    onChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(
            onClick = { val v = (value - step).coerceAtLeast(min); onChange((v * 10).toInt() / 10f) },
            enabled = value > min,
            modifier = Modifier.size(36.dp)
        ) { Text("-", style = MaterialTheme.typography.titleMedium) }
        Text(
            text = "${"%.1f".format(value)}×",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(52.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        IconButton(
            onClick = { val v = (value + step).coerceAtMost(max); onChange((v * 10).toInt() / 10f) },
            enabled = value < max,
            modifier = Modifier.size(36.dp)
        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}

/** "system" (let the spell checker service pick its own language) plus the BCP-47
 *  language tags of every subtype the device's enabled spell checker supports
 *  (usually one per installed keyboard language). */
@Composable
private fun spellCheckLanguageOptions(): List<String> {
    val context = LocalContext.current
    return remember {
        try {
            val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE)
                as? TextServicesManager ?: return@remember listOf("system")
            val info = tsm.currentSpellCheckerInfo
            val tags = (0 until (info?.subtypeCount ?: 0)).mapNotNull { i ->
                info?.getSubtypeAt(i)?.languageTag?.takeIf { it.isNotBlank() }
            }
            listOf("system") + tags.distinct().sorted()
        } catch (_: Throwable) {
            listOf("system")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSettingRow(
    label: String,
    selected: String,
    options: List<String>,
    onChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Spacebar, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        expanded = !expanded
                        true
                    }
                    Key.DirectionDown -> {
                        val i = options.indexOf(selected)
                        if (i >= 0) onChange(options[(i + 1).mod(options.size)])
                        true
                    }
                    Key.DirectionUp -> {
                        val i = options.indexOf(selected)
                        if (i >= 0) onChange(options[(i - 1).mod(options.size)])
                        true
                    }
                    Key.Escape -> if (expanded) { expanded = false; true } else false
                    else -> false
                }
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.width(140.dp)
        ) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                    .focusProperties { canFocus = false },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Text(opt, fontFamily = FontFamily.Monospace,
                                 style = MaterialTheme.typography.bodyMedium)
                        },
                        onClick = { onChange(opt); expanded = false }
                    )
                }
            }
        }
    }
}
