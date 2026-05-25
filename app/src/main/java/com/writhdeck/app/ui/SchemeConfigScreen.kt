package com.writhdeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.writhdeck.app.BUILTIN_SCHEMES
import com.writhdeck.app.SchemeColors
import com.writhdeck.app.WrithdeckViewModel

private val COLOR_LABELS = listOf(
    "Background", "Text", "Bar BG", "Bar FG", "Selection", "Headings", "Comments", "Markup"
)

private fun SchemeColors.darkList() = listOf(bg, fg, bgBar, fgBar, bgSel, heading, comment, markup)
private fun SchemeColors.lightList() = listOf(bgAlt, fgAlt, bgBarAlt, fgBarAlt, bgSelAlt, headingAlt, commentAlt, markupAlt)

private fun schemeFromLists(dark: List<String>, light: List<String>) = SchemeColors(
    bg = dark[0], fg = dark[1], bgBar = dark[2], fgBar = dark[3],
    bgSel = dark[4], heading = dark[5], comment = dark[6], markup = dark[7],
    bgAlt = light[0], fgAlt = light[1], bgBarAlt = light[2], fgBarAlt = light[3],
    bgSelAlt = light[4], headingAlt = light[5], commentAlt = light[6], markupAlt = light[7]
)

private fun parseHex(hex: String): Color? {
    val s = hex.trim().removePrefix("#")
    return try {
        if (s.length != 6) null
        else Color(0xFF000000.toInt() or s.toInt(16))
    } catch (_: Exception) { null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemeConfigScreen(vm: WrithdeckViewModel, onBack: () -> Unit) {
    val customSchemes by vm.customSchemes.collectAsStateWithLifecycle()
    val activeScheme by vm.activeScheme.collectAsStateWithLifecycle()

    // null = list; non-null = Pair(originalName, colors) — originalName="" means new scheme
    var editing by remember { mutableStateOf<Pair<String, SchemeColors>?>(null) }

    val editState = editing
    if (editState != null) {
        val (origName, baseColors) = editState
        val isBuiltin = origName in BUILTIN_SCHEMES && origName !in customSchemes
        SchemeEditor(
            originalName = origName,
            initialColors = baseColors,
            isBuiltin = isBuiltin,
            onSave = { newName, colors ->
                // Rename: delete old custom entry if name changed
                if (origName.isNotEmpty() && origName !in BUILTIN_SCHEMES && origName != newName) {
                    vm.deleteCustomScheme(origName)
                }
                vm.saveCustomScheme(newName, colors)
                editing = null
            },
            onCancel = { editing = null }
        )
    } else {
        SchemeList(
            allSchemes = BUILTIN_SCHEMES + customSchemes,
            customSchemeNames = customSchemes.keys,
            activeScheme = activeScheme,
            onSelect = { vm.setActiveScheme(it) },
            onEdit = { name ->
                val base = customSchemes[name] ?: BUILTIN_SCHEMES[name] ?: BUILTIN_SCHEMES["default"]!!
                editing = name to base
            },
            onNew = { editing = "" to BUILTIN_SCHEMES["default"]!! },
            onDelete = { vm.deleteCustomScheme(it) },
            onBack = onBack
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchemeList(
    allSchemes: Map<String, SchemeColors>,
    customSchemeNames: Set<String>,
    activeScheme: String,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Color schemes", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNew) {
                        Icon(Icons.Default.Add, contentDescription = "New scheme")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(allSchemes.entries.toList(), key = { it.key }) { (name, colors) ->
                SchemeRow(
                    name = name,
                    colors = colors,
                    isActive = name == activeScheme,
                    isCustom = name in customSchemeNames,
                    onSelect = { onSelect(name) },
                    onEdit = { onEdit(name) },
                    onDelete = { onDelete(name) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SchemeRow(
    name: String,
    colors: SchemeColors,
    isActive: Boolean,
    isCustom: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ColorSwatch(colors.bg)
        Spacer(Modifier.width(3.dp))
        ColorSwatch(colors.heading)
        Spacer(Modifier.width(3.dp))
        ColorSwatch(colors.bgAlt)
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium
        )
        if (isActive) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Active",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
        }
        if (isCustom) {
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ColorSwatch(hex: String, size: Dp = 18.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(parseHex(hex) ?: Color.Gray)
            .border(0.5.dp, Color.Gray.copy(alpha = 0.4f))
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchemeEditor(
    originalName: String,
    initialColors: SchemeColors,
    isBuiltin: Boolean,
    onSave: (name: String, colors: SchemeColors) -> Unit,
    onCancel: () -> Unit
) {
    var nameField by remember(originalName) { mutableStateOf(if (isBuiltin) "" else originalName) }
    var darkFields by remember(originalName) { mutableStateOf(initialColors.darkList()) }
    var lightFields by remember(originalName) { mutableStateOf(initialColors.lightList()) }
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (originalName.isEmpty()) "New scheme" else "Edit: $originalName",
                        fontFamily = FontFamily.Monospace
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = nameField,
                onValueChange = { nameField = it },
                label = { Text("Scheme name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace)
            )
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Dark") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Light") }
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                COLOR_LABELS.forEachIndexed { i, label ->
                    val value = if (selectedTab == 0) darkFields[i] else lightFields[i]
                    ColorField(
                        label = label,
                        value = value,
                        onValueChange = { v ->
                            if (selectedTab == 0)
                                darkFields = darkFields.toMutableList().also { it[i] = v }
                            else
                                lightFields = lightFields.toMutableList().also { it[i] = v }
                        }
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                Button(
                    onClick = {
                        onSave(nameField.trim(), schemeFromLists(darkFields, lightFields))
                    },
                    enabled = nameField.isNotBlank()
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun ColorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.width(92.dp),
            style = MaterialTheme.typography.bodySmall
        )
        ColorSwatch(value, size = 24.dp)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            isError = value.isNotEmpty() && parseHex(value) == null
        )
    }
}
