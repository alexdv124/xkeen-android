package com.xkeen.android.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xkeen.android.data.remote.DiagStatus
import com.xkeen.android.data.remote.DiagnosticReport
import com.xkeen.android.data.remote.RouterCommands
import com.xkeen.android.data.remote.XrayConfigRemote
import com.xkeen.android.data.ssh.SshClient
import com.xkeen.android.domain.model.ObservatoryState
import com.xkeen.android.domain.model.RouterStatus
import com.xkeen.android.domain.model.SetupState
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(sshClient: SshClient?) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<RouterStatus?>(null) }
    var externalIp by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagReport by remember { mutableStateOf<DiagnosticReport?>(null) }
    var diagLoading by remember { mutableStateOf(false) }
    var setupState by remember { mutableStateOf<SetupState?>(null) }
    var showSetupWizard by remember { mutableStateOf(false) }
    var setupVlessLinks by remember { mutableStateOf("") }
    var setupLoading by remember { mutableStateOf(false) }
    var setupMessage by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (sshClient == null) return
        loading = true; error = null
        scope.launch {
            try {
                val cmds = RouterCommands(sshClient)
                val s = cmds.getStatus()
                val obs = try { cmds.getObservatoryState() } catch (_: Exception) { ObservatoryState() }
                status = s.copy(observatory = obs)
                try { externalIp = cmds.getExternalIp().directIp } catch (_: Exception) {}
                // Check if initial setup needed
                try { setupState = XrayConfigRemote(sshClient).checkSetupState() } catch (_: Exception) {}
            } catch (e: Exception) { error = e.message }
            finally { loading = false }
        }
    }

    LaunchedEffect(sshClient) { refresh() }

    if (sshClient == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Выберите роутер", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Spacer(Modifier.size(20.dp))
                Row {
                    // Diagnostics button
                    FilledTonalButton(onClick = {
                        showDiagnostics = true
                        diagLoading = true
                        scope.launch {
                            try {
                                diagReport = RouterCommands(sshClient).runDiagnostics()
                            } catch (e: Exception) {
                                error = e.message
                            } finally { diagLoading = false }
                        }
                    }) {
                        Icon(Icons.Default.HealthAndSafety, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Диагностика")
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, "Обновить")
                    }
                }
            }

            error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            // Initial setup banner
            if (setupState?.needsSetup == true) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.RocketLaunch, null, Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(8.dp))
                            Text("Первоначальная настройка", fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (setupState?.xkeenInstalled == true)
                                "xkeen установлен, но серверы не настроены. Вставьте VLESS-ссылки для быстрой настройки."
                            else "xkeen не найден на роутере. Сначала установите xkeen.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (setupState?.xkeenInstalled == true) {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { showSetupWizard = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Настроить")
                            }
                        }
                    }
                }
            }

            status?.let { s ->
                // Xray + External IP
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (s.xrayRunning) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shield, null, Modifier.size(24.dp),
                                tint = if (s.xrayRunning) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Xray", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (s.xrayRunning) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            ) {
                                Text(
                                    if (s.xrayRunning) "RUNNING" else "STOPPED",
                                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    color = if (s.xrayRunning) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onError,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        if (s.xrayRunning) {
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("PID: ${s.xrayPid}", style = MaterialTheme.typography.bodySmall)
                                Text("RAM: ${s.xrayMem}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (s.xkeenVersion.isNotEmpty()) {
                            Text("xkeen: ${s.xkeenVersion}", style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                        if (externalIp.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Language, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Внешний IP: ", style = MaterialTheme.typography.bodySmall)
                                Text(externalIp, fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // System stats
                Card(Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Система", fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        // CPU bar
                        Text("CPU: ${s.cpuUsed}%", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = { (s.cpuUsed / 100.0).toFloat() },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            color = when {
                                s.cpuUsed > 80 -> MaterialTheme.colorScheme.error
                                s.cpuUsed > 50 -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                        // RAM bar
                        val ramPercent = if (s.memTotal > 0) (s.memUsed.toDouble() / s.memTotal * 100) else 0.0
                        Text("RAM: ${s.memUsed / 1024} / ${s.memTotal / 1024} МБ (${ramPercent.toInt()}%)",
                            style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = { (ramPercent / 100.0).toFloat() },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            color = when {
                                ramPercent > 85 -> MaterialTheme.colorScheme.error
                                ramPercent > 60 -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Load: ${s.loadAvg}", style = MaterialTheme.typography.bodySmall)
                            Text(s.uptime.substringAfter("up ").substringBefore(",  load"),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // Server Health
                val obs = s.observatory
                Card(Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Dns, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Серверы", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            if (obs.selected != null) {
                                Surface(shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("▸ ${obs.selected}", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        if (obs.usage.isEmpty() && obs.failedProxies.isEmpty()) {
                            Text("Нет данных observatory", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            // All known proxies
                            val allTags = (obs.usage.keys + obs.failedProxies).distinct().sorted()
                            allTags.forEach { tag ->
                                val isFailed = tag in obs.failedProxies
                                val isActive = tag == obs.selected
                                val requests = obs.usage[tag] ?: 0
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Status dot
                                    Surface(
                                        modifier = Modifier.size(8.dp),
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = when {
                                            isFailed -> MaterialTheme.colorScheme.error
                                            isActive -> MaterialTheme.colorScheme.primary
                                            else -> Color(0xFF4CAF50)
                                        }
                                    ) {}
                                    Spacer(Modifier.width(8.dp))
                                    Text(tag,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f))
                                    if (isFailed) {
                                        Text("FAIL", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error)
                                    } else if (requests > 0) {
                                        Text("$requests req", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // Diagnostics Dialog
    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            icon = { Icon(Icons.Default.HealthAndSafety, null) },
            title = { Text("Диагностика") },
            text = {
                if (diagLoading) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Проверяю...")
                    }
                } else {
                    diagReport?.let { report ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            report.checks.forEach { check ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        when (check.status) {
                                            DiagStatus.OK -> Icons.Default.CheckCircle
                                            DiagStatus.WARN -> Icons.Default.Warning
                                            DiagStatus.FAIL -> Icons.Default.Cancel
                                        },
                                        null, Modifier.size(20.dp),
                                        tint = when (check.status) {
                                            DiagStatus.OK -> Color(0xFF4CAF50)
                                            DiagStatus.WARN -> Color(0xFFFFA726)
                                            DiagStatus.FAIL -> MaterialTheme.colorScheme.error
                                        }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(check.name, fontWeight = FontWeight.Medium,
                                            style = MaterialTheme.typography.bodyMedium)
                                        Text(check.detail, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnostics = false }) { Text("Закрыть") }
            }
        )
    }

    // Setup Wizard Dialog
    if (showSetupWizard && sshClient != null) {
        AlertDialog(
            onDismissRequest = { showSetupWizard = false },
            icon = { Icon(Icons.Default.RocketLaunch, null) },
            title = { Text("Настройка xkeen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    setupMessage?.let {
                        Text(it, color = if ("Ошибка" in it) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary)
                    }
                    Text("Вставьте VLESS-ссылки (по одной на строку):", fontWeight = FontWeight.Medium)
                    OutlinedTextField(
                        value = setupVlessLinks,
                        onValueChange = { setupVlessLinks = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4, maxLines = 8,
                        placeholder = { Text("vless://...") }
                    )
                    val linkCount = setupVlessLinks.lines().count { it.trim().startsWith("vless://") }
                    if (linkCount > 0) {
                        Text("Найдено ссылок: $linkCount" +
                            if (linkCount >= 2) " (балансировка будет включена автоматически)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Будет создано: логирование, inbounds, outbounds, роутинг (RU → direct, остальное → VPN), QUIC-блокировка" +
                        if (linkCount >= 2) ", балансировщик leastping, observatory" else "",
                        style = MaterialTheme.typography.bodySmall)

                    if (setupLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val links = setupVlessLinks.lines().filter { it.trim().startsWith("vless://") }
                        if (links.isEmpty()) { setupMessage = "Вставьте хотя бы одну ссылку"; return@Button }
                        setupLoading = true; setupMessage = null
                        scope.launch {
                            try {
                                val config = XrayConfigRemote(sshClient)
                                val cmds = RouterCommands(sshClient)
                                val (ok, msg) = config.initialSetup(links)
                                if (!ok) { setupMessage = "Ошибка: $msg"; return@launch }
                                val test = cmds.testConfig()
                                if (!test.ok) {
                                    setupMessage = "Ошибка теста: ${test.output.lines().lastOrNull { it.isNotBlank() }}"
                                    return@launch
                                }
                                cmds.restartXkeen()
                                setupMessage = "Готово! $msg"
                                showSetupWizard = false
                                refresh()
                            } catch (e: Exception) { setupMessage = "Ошибка: ${e.message}" }
                            finally { setupLoading = false }
                        }
                    },
                    enabled = !setupLoading && setupVlessLinks.contains("vless://")
                ) { Text("Настроить") }
            },
            dismissButton = {
                TextButton(onClick = { showSetupWizard = false }, enabled = !setupLoading) { Text("Отмена") }
            }
        )
    }
}
