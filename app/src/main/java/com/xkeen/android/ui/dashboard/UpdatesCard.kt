package com.xkeen.android.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xkeen.android.data.remote.ComponentVersion
import com.xkeen.android.data.remote.UpdatesInfo
import com.xkeen.android.data.remote.UpdatesRemote
import com.xkeen.android.data.ssh.SshClient
import kotlinx.coroutines.launch

@Composable
fun UpdatesCard(sshClient: SshClient, onUpdated: () -> Unit) {
    val scope = rememberCoroutineScope()
    var info by remember(sshClient) { mutableStateOf<UpdatesInfo?>(null) }
    var checking by remember(sshClient) { mutableStateOf(false) }
    var updating by remember(sshClient) { mutableStateOf<String?>(null) }
    var message by remember(sshClient) { mutableStateOf<String?>(null) }
    var confirm by remember(sshClient) { mutableStateOf<ComponentVersion?>(null) }

    fun check() {
        checking = true
        scope.launch {
            try { info = UpdatesRemote(sshClient).check() }
            catch (e: Exception) { message = e.message ?: "Не удалось проверить обновления" }
            finally { checking = false }
        }
    }

    LaunchedEffect(sshClient) { check() }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Обновления", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (checking || updating != null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            val busy = checking || updating != null
            val i = info
            if (i != null) {
                listOf(i.xkeen, i.mihomo).forEach { c ->
                    VersionRow(c, busy) { confirm = c }
                }
                if (!i.autoSupported && i.xkeen.isInstalled) {
                    Text(
                        "Эта версия xkeen не поддерживает обновление без вопросов. Один раз обновите её вручную: xkeen -uk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else if (!checking) {
                Text("Нет данных о версиях", style = MaterialTheme.typography.bodySmall)
            }
            updating?.let {
                Text("Обновляю $it… Это может занять до 5 минут, прокси на это время может перезапуститься.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()))
            }
            OutlinedButton(onClick = { message = null; check() }, enabled = !busy) { Text("Проверить обновления") }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Обновить ${c.title}?") },
            text = {
                Text(
                    "${c.installed} → ${c.latest}\n\nПеред обновлением приложение сохранит резервную копию конфигов. " +
                        "Обновление выполняет сам xkeen; интернет через VPN может пропасть на минуту."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirm = null
                    updating = c.title
                    message = null
                    scope.launch {
                        try {
                            val remote = UpdatesRemote(sshClient)
                            val (_, msg) = if (c.title == "XKeen") remote.updateXkeen() else remote.updateMihomo()
                            message = msg
                            info = runCatching { remote.check() }.getOrNull() ?: info
                            onUpdated()
                        } catch (e: Exception) { message = e.message }
                        finally { updating = null }
                    }
                }) { Text("Обновить") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun VersionRow(c: ComponentVersion, busy: Boolean, onUpdate: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.title, fontWeight = FontWeight.Medium)
            Text(
                when {
                    !c.isInstalled -> "не установлен"
                    c.latest.isBlank() -> "${c.installed} · последняя версия неизвестна (нет связи с GitHub)"
                    c.updateAvailable -> "${c.installed} → доступна ${c.latest}"
                    else -> "${c.installed} · последняя версия"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (c.updateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (c.updateAvailable) {
            Button(onClick = onUpdate, enabled = !busy) { Text("Обновить") }
        }
    }
}
