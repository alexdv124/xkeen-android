package com.xkeen.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.xkeen.android.data.local.AppDatabase
import com.xkeen.android.data.local.RouterProfileEntity
import com.xkeen.android.data.ssh.SshClient
import com.xkeen.android.domain.model.RouterProfile
import com.xkeen.android.ui.navigation.AppNavHost
import com.xkeen.android.ui.navigation.BottomNavBar
import com.xkeen.android.ui.settings.SettingsScreen
import com.xkeen.android.ui.theme.XkeenTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            XkeenTheme {
                MainApp(database)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(database: AppDatabase) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    var profiles by remember { mutableStateOf<List<RouterProfile>>(emptyList()) }
    var activeProfile by remember { mutableStateOf<RouterProfile?>(null) }
    var sshClient by remember { mutableStateOf<SshClient?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showRouterMenu by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf("") }

    var connectAttempt by remember { mutableStateOf(0) }

    fun connectTo(profile: RouterProfile) {
        // Screens must never keep using the previous router while the new one connects.
        sshClient?.close()
        sshClient = null
        activeProfile = profile
        connectionStatus = "Подключение..."
        val attempt = ++connectAttempt
        scope.launch {
            val client = SshClient(profile)
            try {
                client.exec("echo ok")
                if (attempt == connectAttempt) {
                    sshClient = client
                    connectionStatus = ""
                } else client.close() // a newer switch superseded this one
            } catch (e: Exception) {
                client.close()
                if (attempt == connectAttempt) connectionStatus = "Ошибка: ${e.message}"
            }
        }
    }

    // Load profiles
    LaunchedEffect(Unit) {
        database.routerProfileDao().getAll().collect { entities ->
            profiles = entities.map { it.toDomain() }
            val current = activeProfile
            when {
                // Auto-connect to the first profile
                current == null && profiles.isNotEmpty() -> connectTo(profiles.first())
                // The active profile was edited (address, port or password): reconnect with new data
                current != null -> profiles.firstOrNull { it.id == current.id }
                    ?.takeIf { it != current }
                    ?.let { connectTo(it) }
            }
        }
    }

    if (showSettings) {
        SettingsScreen(
            profiles = profiles,
            activeProfileId = activeProfile?.id,
            activeSshClient = sshClient,
            onSelect = { connectTo(it); showSettings = false },
            onSave = { profile ->
                scope.launch {
                    val entity = RouterProfileEntity.fromDomain(profile)
                    if (profile.id == 0L) {
                        database.routerProfileDao().insert(entity)
                    } else {
                        database.routerProfileDao().update(entity)
                    }
                }
            },
            onDelete = { profile ->
                scope.launch {
                    if (activeProfile?.id == profile.id) {
                        connectAttempt++
                        sshClient?.close()
                        sshClient = null
                        activeProfile = null
                        connectionStatus = ""
                    }
                    // The profile Flow then auto-connects to the first remaining router.
                    database.routerProfileDao().delete(RouterProfileEntity.fromDomain(profile))
                }
            },
            onDismiss = { showSettings = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("XKeen", fontWeight = FontWeight.Bold)
                        val profile = activeProfile
                        if (profile != null) {
                            Text(
                                profile.alias + if (connectionStatus.isNotEmpty()) " · $connectionStatus" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Router switcher
                    Box {
                        IconButton(onClick = { showRouterMenu = true }) {
                            Icon(Icons.Default.Router, "Роутеры")
                        }
                        DropdownMenu(
                            expanded = showRouterMenu,
                            onDismissRequest = { showRouterMenu = false }
                        ) {
                            profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = {
                                        Row {
                                            Text(profile.alias)
                                            if (profile.id == activeProfile?.id) {
                                                Spacer(Modifier.width(8.dp))
                                                Text("✓", color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    },
                                    onClick = {
                                        connectTo(profile)
                                        showRouterMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Router, null,
                                            tint = if (profile.id == activeProfile?.id)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Настройки") },
                                onClick = { showRouterMenu = false; showSettings = true },
                                leadingIcon = { Icon(Icons.Default.Settings, null) }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = { BottomNavBar(navController) }
    ) { padding ->
        AppNavHost(
            navController = navController,
            sshClient = sshClient,
            modifier = Modifier.padding(padding)
        )
    }
}
