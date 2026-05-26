package com.writhdeck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.writhdeck.app.SettingsData
import com.writhdeck.app.WrithdeckViewModel

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            SettingsSection("Writing")
            DropdownSettingRow(
                label = "Scheme",
                selected = s.scheme,
                options = remember { vm.getAllSchemeNames() }
            ) { s = s.copy(scheme = it) }
            IntSettingRow("Font size", s.fontSize, 10, 32) { s = s.copy(fontSize = it) }
            IntSettingRow("Margin width", s.marginWidth, 0, 200) { s = s.copy(marginWidth = it) }
            IntSettingRow("Margin height", s.marginHeight, 0, 200) { s = s.copy(marginHeight = it) }
            IntSettingRow("Word goal", s.wordGoal, 0, 99999) { s = s.copy(wordGoal = it) }
            StringSettingRow("Heading marker", s.headingMarker) { s = s.copy(headingMarker = it) }

            SettingsSection("Autosave")
            SwitchSettingRow("Enabled", s.autosaveEnabled) { s = s.copy(autosaveEnabled = it) }
            IntSettingRow("Interval (min)", s.autosaveInterval, 1, 60,
                enabled = s.autosaveEnabled) { s = s.copy(autosaveInterval = it) }

            SettingsSection("Timer")
            DropdownSettingRow(
                label = "Type",
                selected = s.timerType,
                options = listOf("countdown", "stopwatch")
            ) { s = s.copy(timerType = it) }
            IntSettingRow("Duration (min)", s.timerDuration, 1, 240) { s = s.copy(timerDuration = it) }
            SwitchSettingRow("Sound at end", s.timerSound) { s = s.copy(timerSound = it) }
            SwitchSettingRow("Alert dialog", s.timerAlert) { s = s.copy(timerAlert = it) }
            SwitchSettingRow("Show in status bar", s.chronoShow) { s = s.copy(chronoShow = it) }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { vm.applySettings(s); onNavigateSchemes() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text("Edit scheme colors", fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.applySettings(s); onEditIni() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text("Edit INI directly", fontFamily = FontFamily.Monospace)
            }
        }
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
            modifier = Modifier.width(80.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        )
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
