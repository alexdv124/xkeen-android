package com.xkeen.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.xkeen.android.data.remote.RouterCommands
import com.xkeen.android.data.ssh.SshClient
import com.xkeen.android.domain.model.RouterProfile
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    profiles: List<RouterProfile>,
    activeProfileId: Long?,
    activeSshClient: SshClient?,
    onSelect: (RouterProfile) -> Unit,
    onSave: (RouterProfile) -> Unit,
    onDelete: (RouterProfile) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<RouterProfile?>(null) }
    var showBackups by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = { editingProfile = null; showDialog = true }) {
                    Icon(Icons.Default.Add, "Добавить роутер")
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Закрыть")
                }
            }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        message?.let {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) { Text(it, Modifier.padding(12.dp)) }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Router profiles section
            item {
                Text("Роутеры", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
            }

            items(profiles) { profile ->
                val isActive = profile.id == activeProfileId
                Card(
                    onClick = { onSelect(profile) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Router, null, Modifier.size(32.dp),
                            tint = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.alias, fontWeight = FontWeight.Bold)
                            Text("${profile.host}:${profile.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isActive) {
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary) {
                                Text("ACTIVE", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        IconButton(onClick = { editingProfile = profile; showDialog = true }) {
                            Icon(Icons.Default.Edit, "Редактировать", Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Tools section
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Text("Инструменты", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
            }

            // Backup
            item {
                Card(
                    onClick = {
                        if (activeSshClient == null) { message = "Подключитесь к роутеру"; return@Card }
                        loading = true
                        scope.launch {
                            try {
                                val ts = RouterCommands(activeSshClient).backupConfigs()
                                message = "Бекап создан: $ts"
                            } catch (e: Exception) { message = e.message }
                            finally { loading = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Backup, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Создать бекап", fontWeight = FontWeight.Medium)
                            Text("Сохранить все JSON-конфиги на роутере",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Restore
            item {
                Card(
                    onClick = { showBackups = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Restore, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Восстановить из бекапа", fontWeight = FontWeight.Medium)
                            Text("Откатить конфигурацию к сохранённой версии",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Copy config between routers
            item {
                Card(
                    onClick = { showCopyDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Копировать конфиг", fontWeight = FontWeight.Medium)
                            Text("Перенести серверы и настройки между роутерами",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // === DIALOGS ===

    if (showDialog) {
        RouterProfileDialog(
            profile = editingProfile,
            onSave = { onSave(it); showDialog = false },
            onDelete = { editingProfile?.let { onDelete(it) }; showDialog = false },
            onDismiss = { showDialog = false }
        )
    }

    if (showBackups && activeSshClient != null) {
        BackupsDialog(
            sshClient = activeSshClient,
            onRestore = { timestamp ->
                showBackups = false
                loading = true
                scope.launch {
                    try {
                        val cmds = RouterCommands(activeSshClient)
                        val (ok, msg) = cmds.restoreBackup(timestamp)
                        if (ok) {
                            cmds.restartXkeen()
                            message = "Восстановлено из $timestamp, xray перезапущен"
                        } else {
                            message = msg
                        }
                    } catch (e: Exception) { message = e.message }
                    finally { loading = false }
                }
            },
            onDismiss = { showBackups = false }
        )
    }

    if (showCopyDialog) {
        CopyConfigDialog(
            profiles = profiles,
            activeProfileId = activeProfileId,
            onCopy = { from, to ->
                showCopyDialog = false
                loading = true
                scope.launch {
                    try {
                        val sshFrom = SshClient(from)
                        val sshTo = SshClient(to)
                        val cmdsFrom = RouterCommands(sshFrom)
                        val cmdsTo = RouterCommands(sshTo)

                        // Backup target first
                        cmdsTo.backupConfigs()

                        // Read all from source
                        val configs = cmdsFrom.readAllConfigs()

                        // Write to target
                        cmdsTo.writeAllConfigs(configs)

                        // Test on target
                        val test = cmdsTo.testConfig()
                        if (test.ok) {
                            cmdsTo.restartXkeen()
                            message = "Конфиг скопирован: ${from.alias} → ${to.alias}"
                        } else {
                            message = "Скопировано, но тест провален. Бекап сохранён"
                        }

                        sshFrom.close()
                        sshTo.close()
                    } catch (e: Exception) { message = "Ошибка: ${e.message}" }
                    finally { loading = false }
                }
            },
            onDismiss = { showCopyDialog = false }
        )
    }
}

@Composable
fun BackupsDialog(sshClient: SshClient, onRestore: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var backups by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try { backups = RouterCommands(sshClient).listBackups() }
        catch (_: Exception) {}
        finally { loading = false }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Restore, null) },
        title = { Text("Восстановить бекап") },
        text = {
            if (loading) {
                CircularProgressIndicator()
            } else if (backups.isEmpty()) {
                Text("Нет сохранённых бекапов")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    backups.forEach { ts ->
                        Card(
                            onClick = { onRestore(ts) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(ts.replace("_", " "))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
fun CopyConfigDialog(
    profiles: List<RouterProfile>,
    activeProfileId: Long?,
    onCopy: (RouterProfile, RouterProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var from by remember { mutableStateOf(profiles.find { it.id == activeProfileId }) }
    var to by remember { mutableStateOf(profiles.find { it.id != activeProfileId }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.ContentCopy, null) },
        title = { Text("Копировать конфиг") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Откуда:", fontWeight = FontWeight.Medium)
                profiles.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = from?.id == p.id, onClick = { from = p })
                        Text(p.alias)
                    }
                }

                Text("Куда:", fontWeight = FontWeight.Medium)
                profiles.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = to?.id == p.id, onClick = { to = p })
                        Text(p.alias)
                    }
                }

                if (from != null && to != null && from!!.id == to!!.id) {
                    Text("Выберите разные роутеры", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (from != null && to != null) onCopy(from!!, to!!) },
                enabled = from != null && to != null && from!!.id != to!!.id
            ) { Text("Копировать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun RouterProfileDialog(
    profile: RouterProfile?,
    onSave: (RouterProfile) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var alias by remember { mutableStateOf(profile?.alias ?: "") }
    var host by remember { mutableStateOf(profile?.host ?: "") }
    var port by remember { mutableStateOf(profile?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(profile?.username ?: "root") }
    var password by remember { mutableStateOf(profile?.password ?: "") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) "Добавить роутер" else "Редактировать") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = alias, onValueChange = { alias = it },
                    label = { Text("Название") },
                    placeholder = { Text("Дом / Дача") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("IP адрес") },
                    placeholder = { Text("192.168.1.1") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it },
                    label = { Text("SSH порт") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Пользователь") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Пароль") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                "Показать пароль"
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(RouterProfile(
                        id = profile?.id ?: 0,
                        alias = alias.ifEmpty { host },
                        host = host, username = username, password = password,
                        port = port.toIntOrNull() ?: 22
                    ))
                },
                enabled = host.isNotEmpty() && password.isNotEmpty()
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (profile != null) {
                    TextButton(onClick = onDelete) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }
    )
}
