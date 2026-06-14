package com.writhdeck.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.writhdeck.app.WrithdeckViewModel

@Composable
fun AppNavigation(vm: WrithdeckViewModel = viewModel(), onRequestPermission: () -> Unit = {}) {
    val nav = rememberNavController()
    val pendingExternalOpen by vm.pendingExternalOpen.collectAsStateWithLifecycle()

    // If MainActivity just loaded an external file (Intent VIEW/EDIT), go straight
    // to the editor. This is a one-shot signal consumed immediately so it doesn't
    // re-fire on later recompositions (e.g. on rotation, after the user has since
    // closed that document and returned to the browser).
    LaunchedEffect(pendingExternalOpen) {
        if (pendingExternalOpen) {
            vm.consumeExternalOpen()
            if (nav.currentDestination?.route == "browser") {
                nav.navigate("editor") { launchSingleTop = true }
            }
        }
    }

    NavHost(navController = nav, startDestination = "browser") {
        composable("browser") {
            BrowserScreen(
                vm = vm,
                onOpenFile = { entry ->
                    vm.openFile(entry)
                    nav.navigate("editor") { launchSingleTop = true }
                },
                onOpenScratchpad = {
                    vm.openScratchpad()
                    nav.navigate("editor") { launchSingleTop = true }
                },
                onOpenExternalFile = {
                    nav.navigate("editor") { launchSingleTop = true }
                },
                onNavigateSchemes = {
                    nav.navigate("schemes") { launchSingleTop = true }
                },
                onNavigateSettings = {
                    nav.navigate("settings") { launchSingleTop = true }
                },
                onRequestPermission = onRequestPermission
            )
        }
        composable("editor") {
            EditorScreen(
                vm = vm,
                onBack = {
                    vm.clearExternalFile()
                    nav.popBackStack()
                },
                onNavigateSettings = {
                    nav.navigate("settings") { launchSingleTop = true }
                }
            )
        }
        composable("schemes") {
            SchemeConfigScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onEditIni = {
                    vm.openIniFile()
                    nav.navigate("editor") { launchSingleTop = true }
                },
                onNavigateSchemes = {
                    nav.navigate("schemes") { launchSingleTop = true }
                }
            )
        }
    }
}
