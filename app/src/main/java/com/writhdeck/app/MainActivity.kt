package com.writhdeck.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.writhdeck.app.ui.AppNavigation
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val vm: WrithdeckViewModel by viewModels()

    private val requestLegacyStorage = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.onStoragePermissionGranted()
    }

    override fun attachBaseContext(newBase: Context) {
        val lang = newBase
            .getSharedPreferences(WrithdeckViewModel.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(WrithdeckViewModel.PREF_LANGUAGE, "system") ?: "system"
        val ctx = if (lang != "system") {
            val locale = Locale.forLanguageTag(lang)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            newBase.createConfigurationContext(config)
        } else newBase
        super.attachBaseContext(ctx)
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
            val themeColors by vm.themeColors.collectAsState()
            MaterialTheme(colorScheme = writhdeckColorScheme(themeColors, useDark)) {
                AppNavigation(vm = vm, onRequestPermission = ::requestStoragePermission)
            }
            LaunchedEffect(Unit) {
                vm.languageChanged.collect { recreate() }
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

private fun hexColor(hex: String): Color {
    val s = hex.trim().removePrefix("#")
    return try { if (s.length == 6) Color(0xFF000000.toInt() or s.toInt(16)) else Color.Gray }
    catch (_: Exception) { Color.Gray }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    red   = (a.red   + (b.red   - a.red)   * t).coerceIn(0f, 1f),
    green = (a.green + (b.green - a.green)  * t).coerceIn(0f, 1f),
    blue  = (a.blue  + (b.blue  - a.blue)   * t).coerceIn(0f, 1f),
)

private fun onColor(bg: Color): Color {
    val lum = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    return if (lum > 0.45f) Color.Black else Color.White
}

private fun writhdeckColorScheme(tc: ThemeColors, useDark: Boolean): ColorScheme {
    val bg     = hexColor(tc.bg)
    val fg     = hexColor(tc.fg)
    val accent = hexColor(tc.headingColor)
    val dim    = hexColor(tc.commentColor)
    val onAcc  = onColor(accent)
    val base   = if (useDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary                  = accent,
        onPrimary                = onAcc,
        primaryContainer         = lerp(bg, accent, 0.25f),
        onPrimaryContainer       = fg,
        secondary                = lerp(accent, dim, 0.40f),
        onSecondary              = onColor(lerp(accent, dim, 0.40f)),
        secondaryContainer       = lerp(bg, dim, 0.25f),
        onSecondaryContainer     = fg,
        tertiary                 = dim,
        onTertiary               = onColor(dim),
        tertiaryContainer        = lerp(bg, dim, 0.20f),
        onTertiaryContainer      = fg,
        background               = bg,
        onBackground             = fg,
        surface                  = bg,
        onSurface                = fg,
        surfaceVariant           = lerp(bg, dim, 0.20f),
        onSurfaceVariant         = lerp(fg, dim, 0.30f),
        surfaceTint              = accent,
        inverseSurface           = fg,
        inverseOnSurface         = bg,
        inversePrimary           = lerp(accent, fg, 0.30f),
        outline                  = lerp(dim, fg, 0.25f),
        outlineVariant           = lerp(bg, dim, 0.50f),
        scrim                    = Color.Black,
        surfaceBright            = lerp(bg, fg, 0.05f),
        surfaceDim               = lerp(bg, dim, 0.12f),
        surfaceContainer         = lerp(bg, dim, 0.10f),
        surfaceContainerHigh     = lerp(bg, dim, 0.15f),
        surfaceContainerHighest  = lerp(bg, dim, 0.22f),
        surfaceContainerLow      = lerp(bg, dim, 0.05f),
    )
}
