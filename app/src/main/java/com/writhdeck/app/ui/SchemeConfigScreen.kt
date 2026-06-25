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
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.writhdeck.app.BUILTIN_SCHEMES
import com.writhdeck.app.R
import com.writhdeck.app.SchemeColors
import com.writhdeck.app.WrithdeckViewModel

private val COLOR_LABEL_KEYS = listOf(
    R.string.scheme_color_label_background,
    R.string.scheme_color_label_text,
    R.string.scheme_color_label_bar_bg,
    R.string.scheme_color_label_bar_fg,
    R.string.scheme_color_label_selection,
    R.string.scheme_color_label_headings,
    R.string.scheme_color_label_comments,
    R.string.scheme_color_label_markup,
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
            onDuplicate = { name, colors -> vm.saveCustomScheme(name, colors) },
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
    onDuplicate: (String, SchemeColors) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit
) {
    // Duplicate dialog state: origin name + colors to copy
    var duplicating by remember { mutableStateOf<Pair<String, SchemeColors>?>(null) }
    var dupName     by remember { mutableStateOf("") }
    var dupError    by remember { mutableStateOf("") }

    val nameEmptyError = stringResource(R.string.scheme_duplicate_error_empty)
    val nameExistsErrorTemplate = stringResource(R.string.scheme_duplicate_error_exists)

    duplicating?.let { (origName, origColors) ->
        AlertDialog(
            onDismissRequest = { duplicating = null },
            title = { Text(stringResource(R.string.scheme_duplicate_dialog_title), fontFamily = FontFamily.Monospace) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.scheme_duplicate_dialog_prompt, origName),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = dupName,
                        onValueChange = { dupName = it; dupError = "" },
                        singleLine = true,
                        isError = dupError.isNotEmpty(),
                        supportingText = if (dupError.isNotEmpty()) {{ Text(dupError) }} else null,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = dupName.trim()
                    when {
                        n.isEmpty()               -> dupError = nameEmptyError
                        allSchemes.containsKey(n) -> dupError = nameExistsErrorTemplate.format(n)
                        else -> {
                            onDuplicate(n, origColors)
                            duplicating = null
                        }
                    }
                }) { Text(stringResource(R.string.scheme_duplicate_create_button)) }
            },
            dismissButton = {
                TextButton(onClick = { duplicating = null }) { Text(stringResource(R.string.scheme_cancel_button)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scheme_list_title), fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.scheme_back_content_description))
                    }
                },
                actions = {
                    IconButton(onClick = onNew) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.scheme_new_content_description))
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
                    onDuplicate = { dupName = "${name}_copy"; dupError = ""; duplicating = name to colors },
                    onDelete = { onDelete(name) },
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
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
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
            style = MaterialTheme.typography.bodyMedium,
        )
        if (isActive) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.scheme_active_content_description),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onDuplicate, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.scheme_duplicate_content_description), modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.scheme_edit_content_description), modifier = Modifier.size(18.dp))
        }
        if (isCustom) {
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.scheme_delete_content_description), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ColorSwatch(hex: String, size: Dp = 18.dp, onClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .size(size)
            .background(parseHex(hex) ?: Color.Gray)
            .border(0.5.dp, Color.Gray.copy(alpha = 0.4f))
            .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m }
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
    var nameField by remember(originalName) { mutableStateOf(originalName) }
    var darkFields by remember(originalName) { mutableStateOf(initialColors.darkList()) }
    var lightFields by remember(originalName) { mutableStateOf(initialColors.lightList()) }
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (originalName.isEmpty()) stringResource(R.string.scheme_editor_title_new)
                        else stringResource(R.string.scheme_editor_title_edit, originalName),
                        fontFamily = FontFamily.Monospace
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.scheme_cancel_content_description))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = nameField,
                onValueChange = { nameField = it },
                label = { Text(stringResource(R.string.scheme_name_label)) },
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
                    text = { Text(stringResource(R.string.scheme_tab_dark)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.scheme_tab_light)) }
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                COLOR_LABEL_KEYS.forEachIndexed { i, labelKey ->
                    val value = if (selectedTab == 0) darkFields[i] else lightFields[i]
                    ColorField(
                        label = stringResource(labelKey),
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
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.scheme_cancel_button)) }
                Button(
                    onClick = {
                        onSave(nameField.trim(), schemeFromLists(darkFields, lightFields))
                    },
                    enabled = nameField.isNotBlank()
                ) { Text(stringResource(R.string.scheme_save_button)) }
            }
        }
    }
}

@Composable
private fun ColorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ColorPickerDialog(
            initialHex = value,
            onConfirm = { hex -> onValueChange(hex); showPicker = false },
            onDismiss = { showPicker = false },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.width(92.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        ColorSwatch(value, size = 28.dp, onClick = { showPicker = true })
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            isError = value.isNotEmpty() && parseHex(value) == null,
        )
    }
}
