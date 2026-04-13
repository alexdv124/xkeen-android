package com.xkeen.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Dns
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", "Статус", Icons.Default.Dashboard)
    data object Proxies : Screen("proxies", "Серверы", Icons.Default.Dns)
    data object Routing : Screen("routing", "Роутинг", Icons.Default.AltRoute)
    data object Logs : Screen("logs", "Логи", Icons.Default.Description)

    companion object {
        val tabs = listOf(Dashboard, Proxies, Routing, Logs)
    }
}
