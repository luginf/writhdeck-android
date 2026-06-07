package com.writhdeck.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.Typeface
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
    // Re-read when activeScheme changes (e.g., user picked a scheme in SchemeConfigScreen and came back)
    var s by remember(activeScheme) { mutableStateOf(vm.getSettingsData()) }
    var selectedTab by remember { mutableIntStateOf(0) }

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
                    .padding(bottom = 32.dp)
            ) {
                when (selectedTab) {
                    0 -> ProfileTab(s) { s = it }
                    1 -> DisplayTab(s) { s = it }
                    2 -> FontsTab(s) { s = it }
                    3 -> SchemesTab(s, vm, onNavigateSchemes = { vm.applySettings(s); onNavigateSchemes() }) { s = it }
                    4 -> TimerTab(s) { s = it }
                    5 -> MiscTab(s, onEditIni = { vm.applySettings(s); onEditIni() }) { s = it }
                }
            }
        }
    }
}

@Composable
private fun ProfileTab(s: SettingsData, onChange: (SettingsData) -> Unit) {
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
                modifier = Modifier.menuAnchor(type = MenuAnchorType.PrimaryNotEditable),
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
    DropdownSettingRow(
        label = "Scheme",
        selected = s.scheme,
        options = remember { vm.getAllSchemeNames() }
    ) { onChange(s.copy(scheme = it)) }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onNavigateSchemes,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Text("Edit scheme colors", fontFamily = FontFamily.Monospace)
    }
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
private fun MiscTab(s: SettingsData, onEditIni: () -> Unit, onChange: (SettingsData) -> Unit) {
    SwitchSettingRow("Hemingway mode", s.hemingwayMode) { onChange(s.copy(hemingwayMode = it)) }

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
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSettingRow(
    label: String,
    selected: String,
    options: List<String>,
    onChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
                modifier = Modifier.menuAnchor(type = MenuAnchorType.PrimaryNotEditable),
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
