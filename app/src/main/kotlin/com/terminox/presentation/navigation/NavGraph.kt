package com.terminox.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.terminox.presentation.connections.ConnectionsScreen
import com.terminox.presentation.discovery.ServerDiscoveryScreen
import com.terminox.presentation.keys.KeyManagementScreen
import com.terminox.presentation.pairing.QrPairingScreen
import com.terminox.presentation.security.ConnectionHistoryScreen
import com.terminox.presentation.settings.SettingsScreen
import com.terminox.presentation.terminal.TerminalScreen

sealed class Screen(val route: String) {
    data object Connections : Screen("connections")
    data object Terminal : Screen("terminal/{connectionId}") {
        fun createRoute(connectionId: String) = "terminal/$connectionId"
    }
    data object Keys : Screen("keys")
    data object Settings : Screen("settings")
    data object QrPairing : Screen("qr-pairing")
    data object Discovery : Screen("discovery")
    data object ConnectionHistory : Screen("connection-history")
}

@Composable
fun TerminoxNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Connections.route
    ) {
        composable(Screen.Connections.route) {
            ConnectionsScreen(
                onConnectionClick = { connectionId ->
                    navController.navigate(Screen.Terminal.createRoute(connectionId))
                },
                onNavigateToKeys = {
                    navController.navigate(Screen.Keys.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToQrPairing = {
                    navController.navigate(Screen.QrPairing.route)
                },
                onNavigateToDiscovery = {
                    navController.navigate(Screen.Discovery.route)
                },
                onNavigateToHistory = {
                    navController.navigate(Screen.ConnectionHistory.route)
                }
            )
        }

        composable(
            route = Screen.Terminal.route,
            arguments = listOf(
                navArgument("connectionId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val connectionId = backStackEntry.arguments?.getString("connectionId") ?: return@composable
            TerminalScreen(
                connectionId = connectionId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Keys.route) {
            KeyManagementScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.QrPairing.route) {
            QrPairingScreen(
                onNavigateBack = { navController.popBackStack() },
                onPairingComplete = { connectionId ->
                    navController.popBackStack()
                    navController.navigate(Screen.Terminal.createRoute(connectionId))
                }
            )
        }

        composable(Screen.Discovery.route) {
            ServerDiscoveryScreen(
                onNavigateBack = { navController.popBackStack() },
                onConnect = { connection ->
                    navController.popBackStack()
                    navController.navigate(Screen.Terminal.createRoute(connection.id))
                }
            )
        }

        composable(Screen.ConnectionHistory.route) {
            ConnectionHistoryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
