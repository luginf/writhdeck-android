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
import androidx.compose.ui.res.stringResource
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
import com.writhdeck.app.EditorFont
import com.writhdeck.app.FontManager
import com.writhdeck.app.R
import com.writhdeck.app.SettingsData
import com.writhdeck.app.WrithdeckViewModel

// Tab order mirrors the Tcl/Tk desktop config dialog: Profile, Display, Fonts, Schemes, Timer, Misc
@Composable
private fun settingsTabs(): List<String> = listOf(
    stringResource(R.string.settings_tab_profile),
    stringResource(R.string.settings_tab_display),
    stringResource(R.string.settings_tab_fonts),
    stringResource(R.string.settings_tab_schemes),
    stringResource(R.string.settings_tab_timer),
    stringResource(R.string.settings_tab_misc)
)

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
    // Rescan the user fonts folder each time Settings opens, so newly dropped .ttf/.otf
    // files show up in the Fonts tab without an app restart.
    LaunchedEffect(Unit) { vm.refreshUserFonts() }
    val userFonts by vm.userFonts.collectAsStateWithLifecycle()
    val fonts = remember(userFonts) { EDITOR_FONTS + userFonts }
    // Re-read when activeScheme/activeProfile changes (e.g., user picked a scheme in
    // SchemeConfigScreen and came back, or switched profile — profile sections override
    // margins/word goal/scheme/etc., so the whole settings snapshot must be refreshed)
    var s by remember(activeScheme, activeProfile) { mutableStateOf(vm.getSettingsData()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                actions = {
                    TextButton(onClick = { vm.applySettings(s); onBack() }) {
                        Text(stringResource(R.string.settings_save))
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
            val tabLabels = settingsTabs()
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 8.dp) {
                tabLabels.forEachIndexed { i, label ->
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
                    2 -> FontsTab(s, fonts, vm.fontDirs) { s = it }
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

    SettingsSection(stringResource(R.string.settings_profile_active_profile))
    DropdownSettingRow(stringResource(R.string.settings_profile_profile), activeProfile, profileNames, onChange = onSwitchProfile)

    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = { newProfileName = ""; nameError = ""; showNewDialog = true }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.settings_profile_new_profile), fontFamily = FontFamily.Monospace)
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
            Text(stringResource(R.string.settings_profile_delete), fontFamily = FontFamily.Monospace)
        }
    }

    if (showNewDialog) {
        val emptyNameError = stringResource(R.string.settings_profile_error_empty_name)
        val invalidNameError = stringResource(R.string.settings_profile_error_invalid_name)
        val alreadyExistsError = stringResource(R.string.settings_profile_error_already_exists)
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text(stringResource(R.string.settings_profile_new_profile), fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_profile_new_profile_hint),
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
                        n.isEmpty()                    -> nameError = emptyNameError
                        !PROFILE_NAME_RE.matches(n)    -> nameError = invalidNameError
                        profileNames.contains(n)       -> nameError = String.format(alreadyExistsError, n)
                        else -> { onNewProfile(n); showNewDialog = false }
                    }
                }) { Text(stringResource(R.string.settings_profile_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text(stringResource(R.string.settings_profile_cancel)) }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.settings_profile_delete_profile_title), fontFamily = FontFamily.Monospace) },
            text = { Text(String.format(stringResource(R.string.settings_profile_delete_profile_confirm), activeProfile)) },
            confirmButton = {
                TextButton(
                    onClick = { onDeleteProfile(); showDeleteConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.settings_profile_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.settings_profile_cancel)) }
            }
        )
    }

    SettingsSection(stringResource(R.string.settings_profile_settings_section))
    DropdownSettingRow(stringResource(R.string.settings_profile_dark_mode), s.androidDarkMode, listOf("auto", "yes", "no")) {
        onChange(s.copy(androidDarkMode = it))
    }
    IntSettingRow(stringResource(R.string.settings_profile_margin_width), s.marginWidth, 0, 200) { onChange(s.copy(marginWidth = it)) }
    IntSettingRow(stringResource(R.string.settings_profile_margin_height), s.marginHeight, 0, 200) { onChange(s.copy(marginHeight = it)) }
    IntSettingRow(stringResource(R.string.settings_profile_word_goal), s.wordGoal, 0, 99999) { onChange(s.copy(wordGoal = it)) }
    FloatSettingRow(stringResource(R.string.settings_profile_line_spacing), s.lineSpacing, 0.8f, 3.0f, 0.1f) { onChange(s.copy(lineSpacing = it)) }
}

@Composable
private fun DisplayTab(s: SettingsData, onChange: (SettingsData) -> Unit) {
    SettingsSection(stringResource(R.string.settings_display_status_bar))
    StringSettingRow(stringResource(R.string.settings_display_left), s.statusLeft) { onChange(s.copy(statusLeft = it)) }
    StringSettingRow(stringResource(R.string.settings_display_center), s.statusCenter) { onChange(s.copy(statusCenter = it)) }
    StringSettingRow(stringResource(R.string.settings_display_right), s.statusRight) { onChange(s.copy(statusRight = it)) }

    SettingsSection(stringResource(R.string.settings_display_editor))
    StringSettingRow(stringResource(R.string.settings_display_heading_marker), s.headingMarker) { onChange(s.copy(headingMarker = it)) }
    SwitchSettingRow(stringResource(R.string.settings_display_markdown_headings), s.markdownHeadings) { onChange(s.copy(markdownHeadings = it)) }
    SwitchSettingRow(stringResource(R.string.settings_display_block_cursor), s.blockCursor) { onChange(s.copy(blockCursor = it)) }
    SwitchSettingRow(stringResource(R.string.settings_display_blinking_cursor), s.cursorBlink) { onChange(s.copy(cursorBlink = it)) }
    SwitchSettingRow(stringResource(R.string.settings_display_spell_check), s.spellCheckEnabled) { onChange(s.copy(spellCheckEnabled = it)) }
    DropdownSettingRow(stringResource(R.string.settings_display_spell_check_language), s.spellCheckLanguage, spellCheckLanguageOptions()) {
        onChange(s.copy(spellCheckLanguage = it))
    }

    SettingsSection(stringResource(R.string.settings_display_markup))
    StringSettingRow(stringResource(R.string.settings_display_comment_marker), s.commentMarker) { onChange(s.copy(commentMarker = it)) }
    StringSettingRow(stringResource(R.string.settings_display_bold_marker), s.boldMarker) { onChange(s.copy(boldMarker = it)) }
    StringSettingRow(stringResource(R.string.settings_display_italic_marker), s.italicMarker) { onChange(s.copy(italicMarker = it)) }
    StringSettingRow(stringResource(R.string.settings_display_underline_marker), s.underlineMarker) { onChange(s.copy(underlineMarker = it)) }
    StringSettingRow(stringResource(R.string.settings_display_strikethrough_marker), s.strikethroughMarker) { onChange(s.copy(strikethroughMarker = it)) }
}

@Composable
private fun FontsTab(s: SettingsData, fonts: List<EditorFont>, fontDirs: List<File>, onChange: (SettingsData) -> Unit) {
    IntSettingRow(stringResource(R.string.settings_fonts_font_size), s.fontSize, 10, 32) { onChange(s.copy(fontSize = it)) }
    FontFamilySettingRow(s.fontFamily, fonts, fontDirs) { onChange(s.copy(fontFamily = it)) }
    SwitchSettingRow(stringResource(R.string.settings_fonts_bold), s.fontBold) { onChange(s.copy(fontBold = it)) }
    FontPreview(s.fontFamily, s.fontSize, s.fontBold, fontDirs)
    Text(
        text = String.format(
            stringResource(R.string.settings_fonts_custom_fonts_hint),
            fontDirs.joinToString("\n") { it.absolutePath }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** Live sample of the editor font — same family/size/weight the editor will use. */
@Composable
private fun FontPreview(familyName: String, fontSizeSp: Int, bold: Boolean, fontDirs: List<File>) {
    val style = if (bold) Typeface.BOLD else Typeface.NORMAL
    Text(
        text = stringResource(R.string.settings_fonts_preview_text),
        fontFamily = remember(familyName, style) { FontFamily(FontManager.resolveTypeface(fontDirs, familyName, style)) },
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
private fun FontFamilySettingRow(selected: String, fonts: List<EditorFont>, fontDirs: List<File>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = remember(selected, fonts) { fonts.firstOrNull { it.familyName == selected } ?: fonts[0] }
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
                        val i = fonts.indexOf(current)
                        onChange(fonts[(i + 1).mod(fonts.size)].familyName)
                        true
                    }
                    Key.DirectionUp -> {
                        val i = fonts.indexOf(current)
                        onChange(fonts[(i - 1).mod(fonts.size)].familyName)
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
            text = stringResource(R.string.settings_fonts_font_family),
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
                    fontFamily = FontFamily(FontManager.resolveTypeface(fontDirs, current.familyName, Typeface.NORMAL))
                )
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                fonts.forEach { font ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                font.label,
                                fontFamily = FontFamily(FontManager.resolveTypeface(fontDirs, font.familyName, Typeface.NORMAL)),
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
        label = stringResource(R.string.settings_schemes_scheme),
        selected = s.scheme,
        options = remember { vm.getAllSchemeNames() }
    ) { onChange(s.copy(scheme = it)) }

    Row(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.settings_schemes_dark), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Spacer(Modifier.width(4.dp))
        listOf(colors.bg, colors.heading, colors.comment, colors.markup).forEach { SchemePreviewSwatch(it) }
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.settings_schemes_light), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Spacer(Modifier.width(4.dp))
        listOf(colors.bgAlt, colors.headingAlt, colors.commentAlt, colors.markupAlt).forEach { SchemePreviewSwatch(it) }
    }

    OutlinedButton(
        onClick = onNavigateSchemes,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Text(stringResource(R.string.settings_schemes_edit_scheme_colors), fontFamily = FontFamily.Monospace)
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
        label = stringResource(R.string.settings_timer_type),
        selected = s.timerType,
        options = listOf("countdown", "stopwatch")
    ) { onChange(s.copy(timerType = it)) }
    IntSettingRow(stringResource(R.string.settings_timer_duration), s.timerDuration, 1, 240) { onChange(s.copy(timerDuration = it)) }
    SwitchSettingRow(stringResource(R.string.settings_timer_sound_at_end), s.timerSound) { onChange(s.copy(timerSound = it)) }
    SwitchSettingRow(stringResource(R.string.settings_timer_alert_dialog), s.timerAlert) { onChange(s.copy(timerAlert = it)) }
    SwitchSettingRow(stringResource(R.string.settings_timer_show_in_status_bar), s.chronoShow) { onChange(s.copy(chronoShow = it)) }
}

@Composable
private fun MiscTab(s: SettingsData, vm: WrithdeckViewModel, onEditIni: () -> Unit, onChange: (SettingsData) -> Unit) {
    val storagePermissionGranted by vm.storagePermissionGranted.collectAsStateWithLifecycle()
    var showFolderPicker by remember { mutableStateOf(false) }

    SwitchSettingRow(stringResource(R.string.settings_misc_hemingway_mode), s.hemingwayMode) { onChange(s.copy(hemingwayMode = it)) }

    SettingsSection(stringResource(R.string.settings_misc_language_section))
    DropdownSettingRow(stringResource(R.string.settings_misc_app_language), s.appLanguage, listOf("system", "en", "fr", "es", "de", "pt-BR")) {
        onChange(s.copy(appLanguage = it))
    }

    SettingsSection(stringResource(R.string.settings_misc_documents_folder))
    StringSettingRow(stringResource(R.string.settings_misc_custom_folder), s.docsCustomDir, enabled = storagePermissionGranted) {
        onChange(s.copy(docsCustomDir = it))
    }
    OutlinedButton(
        onClick = { showFolderPicker = true },
        enabled = storagePermissionGranted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Text(stringResource(R.string.settings_misc_browse), fontFamily = FontFamily.Monospace)
    }
    if (!storagePermissionGranted) {
        Text(
            stringResource(R.string.settings_misc_requires_storage_permission),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }

    SettingsSection(stringResource(R.string.settings_misc_browser_section))
    StringSettingRow(stringResource(R.string.settings_misc_file_filter), s.browserFilter) { onChange(s.copy(browserFilter = it)) }
    SwitchSettingRow(stringResource(R.string.settings_misc_show_all_files), s.browserShowAll) { onChange(s.copy(browserShowAll = it)) }
    SwitchSettingRow(stringResource(R.string.settings_misc_browse_subfolders), s.browserSubdirs) { onChange(s.copy(browserSubdirs = it)) }

    SettingsSection(stringResource(R.string.settings_misc_autosave_section))
    SwitchSettingRow(stringResource(R.string.settings_misc_enabled), s.autosaveEnabled) { onChange(s.copy(autosaveEnabled = it)) }
    IntSettingRow(stringResource(R.string.settings_misc_interval_min), s.autosaveInterval, 1, 60,
        enabled = s.autosaveEnabled) { onChange(s.copy(autosaveInterval = it)) }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = onEditIni,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Text(stringResource(R.string.settings_misc_edit_ini_directly), fontFamily = FontFamily.Monospace)
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
                Text(stringResource(R.string.settings_misc_use_this_folder))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_misc_cancel))
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
