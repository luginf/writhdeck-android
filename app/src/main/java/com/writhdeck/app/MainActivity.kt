package com.writhdeck.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.writhdeck.app.ui.AppNavigation

class MainActivity : ComponentActivity() {

    private val vm: WrithdeckViewModel by viewModels()

    private val requestLegacyStorage = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.onStoragePermissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only process the launch intent on a fresh start, not on recreation
        // (e.g. rotation) — otherwise an externally-opened file would be
        // re-read from disk on every rotation, discarding in-memory edits.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val darkPref by vm.darkModePreference.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val useDark = when (darkPref) {
                "yes" -> true
                "no"  -> false
                else  -> systemDark
            }
            LaunchedEffect(systemDark) { vm.updateThemeColors(systemDark) }
            MaterialTheme(colorScheme = if (useDark) darkColorScheme() else lightColorScheme()) {
                AppNavigation(vm = vm, onRequestPermission = ::requestStoragePermission)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager() && !vm.storagePermissionGranted.value) {
                vm.onStoragePermissionGranted()
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestLegacyStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_EDIT) return
        val uri = intent.data ?: return
        val canWrite = action == Intent.ACTION_EDIT &&
                (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
        vm.openExternalContent(uri, contentResolver, canWrite)
    }
}
