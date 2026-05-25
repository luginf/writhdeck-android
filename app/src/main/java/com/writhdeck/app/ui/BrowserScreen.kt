package com.writhdeck.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.writhdeck.app.DocEntry
import com.writhdeck.app.WrithdeckViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    vm: WrithdeckViewModel,
    onOpenFile: (DocEntry) -> Unit,
    onOpenScratchpad: () -> Unit = {},
    onNavigateSchemes: () -> Unit = {},
    onNavigateSettings: () -> Unit = {},
    onRequestPermission: () -> Unit
) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val recentDocs by vm.recentDocs.collectAsStateWithLifecycle()
    val favoriteDocs by vm.favoriteDocs.collectAsStateWithLifecycle()
    val storageGranted by vm.storagePermissionGranted.collectAsStateWithLifecycle()
    val engineReady by vm.engineReady.collectAsStateWithLifecycle()
    val snackbarMessage by vm.snackbarMessage.collectAsStateWithLifecycle()
    val darkPref by vm.darkModePreference.collectAsStateWithLifecycle()

    var showNewDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var permissionBannerDismissed by remember { mutableStateOf(false) }

    var contextEntry by remember { mutableStateOf<DocEntry?>(null) }
    var showContextMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }

    // Entry highlighted by the last tap — used for keyboard shortcuts
    var selectedEntry by remember { mutableStateOf<DocEntry?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            vm.dismissSnackbar()
        }
    }

    val favPaths by remember { derivedStateOf { favoriteDocs.map { it.path }.toSet() } }
    val recentOnly by remember {
        derivedStateOf { recentDocs.filter { r -> r.path !in favPaths } }
    }

    // Keyboard shortcut support (hardware keyboard + virtual keyboard via icon)
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var imeAllowed by remember { mutableStateOf(false) }
    var shortcutBuffer by remember { mutableStateOf(TextFieldValue("")) }

    fun handleShortcut(ch: Char) {
        shortcutBuffer = TextFieldValue("")
        when (ch.lowercaseChar()) {
            'n' -> showNewDialog = true
            't' -> { imeAllowed = false; onOpenScratchpad() }
            'c' -> onNavigateSettings()
            'f' -> selectedEntry?.let { vm.toggleFavorite(it) }
            'r' -> selectedEntry?.let {
                contextEntry = it
                renameValue = it.name.substringBeforeLast('.')
                showRenameDialog = true
            }
            'd' -> selectedEntry?.let { contextEntry = it; showDeleteConfirm = true }
            'b' -> selectedEntry?.let { vm.backupFile(it) }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WrithDeck", fontFamily = FontFamily.Monospace) },
                actions = {
                    // Dark mode toggle: auto -> yes -> no -> auto
                    val darkModeIcon = when (darkPref) {
                        "yes" -> Icons.Filled.DarkMode
                        "no"  -> Icons.Filled.LightMode
                        else  -> Icons.Outlined.DarkMode
                    }
                    val nextDarkPref = when (darkPref) {
                        "auto" -> "yes"
                        "yes"  -> "no"
                        else   -> "auto"
                    }
                    IconButton(onClick = { vm.setDarkModePreference(nextDarkPref) }) {
                        Icon(darkModeIcon, contentDescription = "Dark mode: $darkPref")
                    }
                    // Virtual keyboard toggle
                    IconButton(onClick = {
                        if (imeVisible) {
                            imeAllowed = false
                            keyboardController?.hide()
                        } else {
                            imeAllowed = true
                            try { focusRequester.requestFocus() } catch (_: Exception) {}
                            keyboardController?.show()
                        }
                    }) {
                        Icon(Icons.Default.Keyboard, contentDescription = "Keyboard shortcuts")
                    }
                    IconButton(onClick = onNavigateSchemes) {
                        Icon(Icons.Default.Palette, contentDescription = "Color schemes")
                    }
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "New")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (!engineReady) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Scratchpad — always-visible quick note
                    item(key = "scratchpad") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { imeAllowed = false; onOpenScratchpad() }
                                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "scratchpad",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                            )
                            Text(
                                text = "t",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                        HorizontalDivider()
                    }

                    // Storage permission banner
                    if (!storageGranted && !permissionBannerDismissed) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        "Storage access",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Without permission: files are stored in private internal storage (not visible in file manager).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = { permissionBannerDismissed = true }) {
                                            Text("Keep private")
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Button(onClick = onRequestPermission) {
                                            Text("Grant access")
                                        }
                                    }
                                    Text(
                                        "Grant access: stores files in Documents/WrithDeck/ and allows opening/saving files anywhere on the device.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    // Favorites section
                    if (favoriteDocs.isNotEmpty()) {
                        item { SectionHeader("Favorites") }
                        items(favoriteDocs, key = { "fav_${it.path}" }) { entry ->
                            DocListItem(
                                entry = entry,
                                isFavorite = true,
                                isSelected = selectedEntry?.path == entry.path,
                                onClick = { selectedEntry = entry; imeAllowed = false; onOpenFile(entry) },
                                onLongClick = { selectedEntry = entry; contextEntry = entry; showContextMenu = true },
                                onToggleFavorite = { selectedEntry = entry; vm.toggleFavorite(entry) }
                            )
                        }
                    }

                    // Documents section
                    item { SectionHeader("Documents") }
                    if (docs.isEmpty()) {
                        item {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    "No documents yet. Tap + to create one.",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(docs, key = { it.path }) { entry ->
                            DocListItem(
                                entry = entry,
                                isFavorite = entry.path in favPaths,
                                isSelected = selectedEntry?.path == entry.path,
                                onClick = { selectedEntry = entry; imeAllowed = false; onOpenFile(entry) },
                                onLongClick = { selectedEntry = entry; contextEntry = entry; showContextMenu = true },
                                onToggleFavorite = { selectedEntry = entry; vm.toggleFavorite(entry) }
                            )
                        }
                    }

                    // Recent section
                    if (recentOnly.isNotEmpty()) {
                        item { SectionHeader("Recent") }
                        items(recentOnly, key = { "recent_${it.path}" }) { entry ->
                            DocListItem(
                                entry = entry,
                                isFavorite = entry.path in favPaths,
                                isSelected = selectedEntry?.path == entry.path,
                                onClick = { selectedEntry = entry; imeAllowed = false; onOpenFile(entry) },
                                onLongClick = { selectedEntry = entry; contextEntry = entry; showContextMenu = true },
                                onToggleFavorite = { selectedEntry = entry; vm.toggleFavorite(entry) }
                            )
                        }
                    }

                    item { Spacer(Modifier.height(80.dp)) }
                }

                // Invisible 1dp field that captures keyboard input for shortcuts.
                // Focus is requested by the keyboard icon button (not auto on open).
                BasicTextField(
                    value = shortcutBuffer,
                    onValueChange = { new ->
                        val ch = new.text.lastOrNull()
                        shortcutBuffer = TextFieldValue("")
                        if (ch != null && ch.isLetter()) handleShortcut(ch)
                    },
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { fs ->
                            if (fs.isFocused && !imeAllowed) keyboardController?.hide()
                        }
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            val ch = event.nativeKeyEvent.unicodeChar.toChar()
                            if (ch.isLetter()) { handleShortcut(ch); true } else false
                        }
                )
            }
        }
    }

    // Context menu
    if (showContextMenu && contextEntry != null) {
        val entry = contextEntry!!
        val isFav = entry.path in favPaths
        AlertDialog(
            onDismissRequest = { showContextMenu = false },
            title = {
                Text(
                    entry.name,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            renameValue = entry.name.substringBeforeLast('.')
                            showContextMenu = false
                            showRenameDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Rename", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                    TextButton(
                        onClick = { showContextMenu = false; showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Delete",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    TextButton(
                        onClick = { vm.backupFile(entry); showContextMenu = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Backup", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                    TextButton(
                        onClick = { vm.toggleFavorite(entry); showContextMenu = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (isFav) "Remove from favorites" else "Add to favorites",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start
                        )
                    }
                    TextButton(
                        onClick = { showContextMenu = false; showInfoDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Info", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showContextMenu = false }) { Text("Cancel") }
            }
        )
    }

    // Info dialog
    if (showInfoDialog && contextEntry != null) {
        val entry = contextEntry!!
        val f = File(entry.path)
        val sizeStr = when {
            f.length() < 1024L -> "${f.length()} B"
            f.length() < 1024L * 1024 -> "${"%.1f".format(f.length() / 1024.0)} KB"
            else -> "${"%.1f".format(f.length() / (1024.0 * 1024))} MB"
        }
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(f.lastModified()))
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = {
                Text(
                    entry.name,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Path", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.primary)
                    Text(entry.path, fontFamily = FontFamily.Monospace,
                         style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("Size: $sizeStr", style = MaterialTheme.typography.bodyMedium)
                    Text("Modified: $dateStr", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text("Close") }
            }
        )
    }

    // Rename dialog
    if (showRenameDialog && contextEntry != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("New name") },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameValue.isNotBlank()) vm.renameFile(contextEntry!!, renameValue)
                    showRenameDialog = false; contextEntry = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Delete confirmation
    if (showDeleteConfirm && contextEntry != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete") },
            text = { Text("Delete \"${contextEntry!!.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteFile(contextEntry!!)
                        showDeleteConfirm = false
                        contextEntry = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // New file dialog
    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false; newFileName = "" },
            title = { Text("New document") },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text("File name") },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFileName.isNotBlank()) vm.createFile(newFileName)
                    showNewDialog = false; newFileName = ""
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false; newFileName = "" }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocListItem(
    entry: DocEntry,
    isFavorite: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
             else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = entry.name,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
        )
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                Icons.Default.Star,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
    HorizontalDivider()
}
