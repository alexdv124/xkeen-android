package com.xkeen.android.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.xkeen.android.data.ssh.SshClient
import com.xkeen.android.ui.dashboard.DashboardScreen
import com.xkeen.android.ui.logs.LogsScreen
import com.xkeen.android.ui.proxies.ProxiesScreen
import com.xkeen.android.ui.routing.RoutingScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    sshClient: SshClient?,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route,
        modifier = modifier
    ) {
        composable(Screen.Dashboard.route) {
            DashboardScreen(sshClient = sshClient)
        }
        composable(Screen.Proxies.route) {
            ProxiesScreen(sshClient = sshClient)
        }
        composable(Screen.Routing.route) {
            RoutingScreen(sshClient = sshClient)
        }
        composable(Screen.Logs.route) {
            LogsScreen(sshClient = sshClient)
        }
    }
}

@Composable
fun BottomNavBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar {
        Screen.tabs.forEach { screen ->
            NavigationBarItem(
                icon = { Icon(screen.icon, contentDescription = screen.title) },
                label = { Text(screen.title) },
                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}
