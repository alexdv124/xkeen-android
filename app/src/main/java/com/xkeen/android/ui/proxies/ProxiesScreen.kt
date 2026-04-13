package com.xkeen.android.ui.proxies

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
import androidx.compose.ui.unit.dp
import com.xkeen.android.data.remote.RouterCommands
import com.xkeen.android.data.remote.VlessParser
import com.xkeen.android.data.remote.XrayConfigRemote
import com.xkeen.android.data.ssh.SshClient
import com.xkeen.android.domain.model.ConfigState
import com.xkeen.android.domain.model.ProxyInfo
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxiesScreen(sshClient: SshClient?) {
    val scope = rememberCoroutineScope()
    var proxies by remember { mutableStateOf<List<ProxyInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var configState by remember { mutableStateOf(ConfigState.Empty) }
    var showFailoverDialog by remember { mutableStateOf(false) }
    var pendingNewTag by remember { mutableStateOf("") }

    fun refresh() {
        if (sshClient == null) return
        loading = true; error = null
        scope.launch {
            try {
                val config = XrayConfigRemote(sshClient)
                val cmds = RouterCommands(sshClient)
                configState = config.detectConfigState()
                val list = config.getProxyList()
                val obs = try { cmds.getObservatoryState() } catch (_: Exception) {
                    com.xkeen.android.domain.model.ObservatoryState()
                }
                proxies = list.map { p ->
                    p.copy(
                        failed = p.tag in obs.failedProxies,
                        requests = obs.usage[p.tag] ?: 0,
                        selected = p.tag == obs.selected
                    )
                }
            } catch (e: Exception) { error = e.message }
            finally { loading = false }
        }
    }

    LaunchedEffect(sshClient) { refresh() }

    Scaffold(
        floatingActionButton = {
            if (sshClient != null) {
                FloatingActionButton(onClick = { showAddSheet = true }) {
                    Icon(Icons.Default.Add, "Добавить сервер")
                }
            }
        }
    ) { padding ->
        if (sshClient == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Выберите роутер")
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding)) {
                // Global progress indicator
                if (loading) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 16.dp)
                    )
                }
                if (loading && proxies.isEmpty()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (error != null) {
                            item {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                    Text(error!!, Modifier.padding(16.dp))
                                }
                            }
                        }
                        actionMessage?.let { msg ->
                            item {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                                    Text(msg, Modifier.padding(16.dp))
                                }
                            }
                        }
                        // Config state banner
                        if (configState.isSingleServer) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("1 сервер без failover", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Добавьте ещё сервер — приложение автоматически настроит балансировку и автопереключение при сбоях",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                        items(proxies) { proxy ->
                            ProxyCard(
                                proxy = proxy,
                                onDelete = {
                                    scope.launch {
                                        loading = true
                                        try {
                                            val config = XrayConfigRemote(sshClient)
                                            val cmds = RouterCommands(sshClient)
                                            val (ok, msg) = config.removeOutbound(proxy.tag)
                                            if (!ok) { actionMessage = msg; return@launch }
                                            val test = cmds.testConfig()
                                            if (!test.ok) { actionMessage = "Config test failed"; return@launch }
                                            cmds.restartXkeen()
                                            actionMessage = "${proxy.tag} удалён"
                                            refresh()
                                        } catch (e: Exception) { actionMessage = e.message }
                                        finally { loading = false }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Failover setup dialog
    if (showFailoverDialog && sshClient != null) {
        AlertDialog(
            onDismissRequest = { showFailoverDialog = false },
            icon = { Icon(Icons.Default.SwapHoriz, null) },
            title = { Text("Включить автопереключение?") },
            text = {
                Text("У вас теперь ${configState.proxyCount + 1} сервер(а). " +
                    "Включить автоматическое переключение? Если текущий сервер упадёт, " +
                    "трафик автоматически пойдёт через другой. " +
                    "Будет использована стратегия leastping — выбирается сервер с минимальной задержкой.")
            },
            confirmButton = {
                Button(onClick = {
                    showFailoverDialog = false
                    scope.launch {
                        loading = true
                        try {
                            val config = XrayConfigRemote(sshClient)
                            val cmds = RouterCommands(sshClient)
                            val state = config.detectConfigState()
                            val allTags = state.proxyTags
                            val (ok, msg) = config.enableFailover(allTags)
                            if (!ok) { actionMessage = msg; return@launch }
                            val test = cmds.testConfig()
                            if (!test.ok) {
                                actionMessage = "Config test failed: ${test.output.takeLast(200)}"
                                return@launch
                            }
                            cmds.restartXkeen()
                            actionMessage = "Failover включён! Балансировка между ${allTags.size} серверами"
                            refresh()
                        } catch (e: Exception) { actionMessage = e.message }
                        finally { loading = false }
                    }
                }) { Text("Включить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFailoverDialog = false
                    // Just test and restart without failover
                    scope.launch {
                        loading = true
                        try {
                            val cmds = RouterCommands(sshClient)
                            val test = cmds.testConfig()
                            if (!test.ok) {
                                val config = XrayConfigRemote(sshClient)
                                config.removeOutbound(pendingNewTag)
                                actionMessage = "Config test failed"
                            } else {
                                cmds.restartXkeen()
                                actionMessage = "Добавлен $pendingNewTag (без failover)"
                            }
                            refresh()
                        } catch (e: Exception) { actionMessage = e.message }
                        finally { loading = false }
                    }
                }) { Text("Нет, просто добавить") }
            }
        )
    }

    if (showAddSheet && sshClient != null) {
        AddProxySheet(
            onDismiss = { showAddSheet = false },
            onDeploy = { vlessLink, customTag ->
                showAddSheet = false
                scope.launch {
                    loading = true
                    try {
                        val parser = VlessParser()
                        val parsed = parser.parse(vlessLink)
                        val outbound = mapToJsonObject(parsed.outbound).let { obj ->
                            if (customTag.isNotEmpty()) {
                                val mutable = obj.toMutableMap()
                                mutable["tag"] = JsonPrimitive(customTag)
                                JsonObject(mutable)
                            } else obj
                        }
                        val config = XrayConfigRemote(sshClient)
                        val cmds = RouterCommands(sshClient)
                        val newTag = outbound["tag"]?.jsonPrimitive?.content ?: run {
                            actionMessage = "No tag in outbound"; return@launch
                        }

                        actionMessage = "Добавляю $newTag..."
                        val (ok, msg) = config.addOutbound(outbound)
                        if (!ok) { actionMessage = msg; return@launch }

                        // Detect if we just went from 1 to 2+ proxies without balancer
                        val state = config.detectConfigState()
                        if (state.proxyCount >= 2 && !state.hasBalancer) {
                            // Need to enable failover
                            pendingNewTag = newTag
                            loading = false
                            showFailoverDialog = true
                            return@launch
                        }

                        // If balancer exists, add new proxy to it
                        if (state.hasBalancer) {
                            config.addToBalancer(newTag)
                        }

                        actionMessage = "Тестирую конфиг..."
                        val test = cmds.testConfig()
                        if (!test.ok) {
                            config.removeOutbound(newTag)
                            actionMessage = "Config test failed: ${test.output.takeLast(200)}"
                            return@launch
                        }
                        actionMessage = "Перезапускаю xray..."
                        cmds.restartXkeen()
                        actionMessage = "Добавлен $newTag"
                        refresh()
                    } catch (e: Exception) { actionMessage = e.message }
                    finally { loading = false }
                }
            }
        )
    }
}

@Composable
fun ProxyCard(proxy: ProxyInfo, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                proxy.selected -> MaterialTheme.colorScheme.primaryContainer
                proxy.failed -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (proxy.failed) Icons.Default.ErrorOutline else Icons.Default.CheckCircleOutline,
                    null, Modifier.size(20.dp),
                    tint = if (proxy.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(proxy.tag, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (proxy.selected) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary) {
                        Text("ACTIVE", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = { showDeleteConfirm = true }, Modifier.size(32.dp)) {
                    Icon(Icons.Default.DeleteOutline, "Удалить", Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("${proxy.address}:${proxy.port}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(proxy.transport, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (proxy.requests > 0) {
                    Text("${proxy.requests} req", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить ${proxy.tag}?") },
            text = { Text("Сервер будет удалён из конфигурации и xray перезапущен") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProxySheet(onDismiss: () -> Unit, onDeploy: (String, String) -> Unit) {
    var vlessLinks by remember { mutableStateOf("") }
    var customTag by remember { mutableStateOf("") }

    val links = vlessLinks.lines().filter { it.trim().startsWith("vless://") }
    val isBulk = links.size > 1

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(24.dp)) {
            Text("Добавить сервер", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = vlessLinks,
                onValueChange = { vlessLinks = it },
                label = { Text(if (isBulk) "VLESS-ссылки (${links.size} шт.)" else "VLESS-ссылка") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3, maxLines = 8,
                supportingText = { Text("Можно вставить несколько ссылок — по одной на строку") }
            )
            if (!isBulk) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customTag,
                    onValueChange = { customTag = it },
                    label = { Text("Тег (опционально)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("proxy-xx1") },
                    singleLine = true
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    // Deploy links sequentially (first triggers failover dialog if needed)
                    links.forEachIndexed { i, link ->
                        onDeploy(link.trim(), if (!isBulk) customTag else "")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = links.isNotEmpty()
            ) {
                Icon(Icons.Default.CloudUpload, null)
                Spacer(Modifier.width(8.dp))
                Text(if (isBulk) "Добавить ${links.size} серверов" else "Развернуть")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun mapToJsonObject(map: Map<String, Any?>): JsonObject {
    return JsonObject(map.mapValues { anyToJsonElement(it.value) })
}

private fun anyToJsonElement(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject((v as Map<String, Any?>).mapValues { anyToJsonElement(it.value) })
    is List<*> -> JsonArray(v.map { anyToJsonElement(it) })
    is JsonElement -> v
    else -> JsonPrimitive(v.toString())
}
