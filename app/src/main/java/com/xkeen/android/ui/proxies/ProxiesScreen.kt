package com.xkeen.android.ui.proxies

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
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
import com.xkeen.android.data.remote.MihomoConfigRemote
import com.xkeen.android.data.remote.MihomoApi
import com.xkeen.android.data.remote.RouterCommands
import com.xkeen.android.data.remote.VlessParser
import com.xkeen.android.data.remote.XrayServerRemote
import com.xkeen.android.data.remote.XrayConfigRemote
import com.xkeen.android.data.ssh.SshClient
import com.xkeen.android.domain.model.ConfigState
import com.xkeen.android.domain.model.ProxyCore
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
    var pendingNewTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var mihomoGroups by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeCore by remember { mutableStateOf(ProxyCore.UNKNOWN) }
    var mihomoSelectable by remember { mutableStateOf(false) }
    var deletableNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeletion by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun refresh() {
        if (sshClient == null) return
        loading = true; error = null
        activeCore = ProxyCore.UNKNOWN
        deletableNames = emptySet()
        try {
            val cmds = RouterCommands(sshClient)
            val core = cmds.getCoreState().activeCore
            activeCore = core
            if (core == ProxyCore.MIHOMO) {
                configState = ConfigState.Empty
                deletableNames = MihomoConfigRemote(sshClient).getProxyNames()
                val api = MihomoApi(sshClient)
                if (!api.isAvailable()) {
                    error = "На роутере нет curl — установите: opkg install curl"
                    proxies = emptyList(); mihomoSelectable = false
                } else {
                    val nodes = api.getProxies()
                    val selector = nodes["PROXY"]?.takeIf { it.isGroup }
                    val now = selector?.now ?: ""
                    mihomoSelectable = selector != null
                    val servers = nodes.values.filter {
                        !it.isGroup && it.type.lowercase() !in setOf("direct", "reject", "rejectdrop", "compatible", "pass")
                    }
                    proxies = buildList {
                        if (selector != null && "AUTO" in selector.all) {
                            add(ProxyInfo("AUTO", "авто по минимальной задержке", 0, "url-test",
                                selected = now == "AUTO"))
                        }
                        servers.forEach { n ->
                            add(ProxyInfo(n.name, "", 0, n.type.lowercase(),
                                selected = n.name == now, delayMs = n.delayMs))
                        }
                    }
                }
            } else {
                val config = XrayConfigRemote(sshClient)
                configState = config.detectConfigState()
                val list = config.getProxyList()
                deletableNames = list.map { it.tag }.toSet()
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
            }
        } catch (e: Exception) { error = e.message }
        finally { loading = false }
    }

    fun deleteServers(names: Set<String>) {
        val client = sshClient ?: return
        val core = activeCore
        pendingDeletion = emptySet()
        loading = true
        scope.launch {
            try {
                val (ok, msg) = when (core) {
                    ProxyCore.MIHOMO -> MihomoConfigRemote(client).removeProxies(names)
                    ProxyCore.XRAY -> XrayServerRemote(client).removeProxies(names)
                    ProxyCore.UNKNOWN -> false to "Активное ядро не определено"
                }
                actionMessage = msg
                if (ok) {
                    selectionMode = false
                    selectedNames = emptySet()
                    proxies = proxies.filterNot { it.tag in names }
                    refresh()
                }
            } catch (e: Exception) { actionMessage = e.message }
            finally { loading = false }
        }
    }

    LaunchedEffect(sshClient) {
        showAddSheet = false
        showFailoverDialog = false
        pendingDeletion = emptySet()
        selectedNames = emptySet()
        selectionMode = false
        refresh()
    }

    val selectableNames = proxies.map { it.tag }.filter { it in deletableNames }.toSet()

    Scaffold(
        floatingActionButton = {
            if (sshClient != null && !loading && !selectionMode && activeCore != ProxyCore.UNKNOWN) {
                FloatingActionButton(onClick = {
                    if (activeCore == ProxyCore.MIHOMO) {
                        loading = true
                        scope.launch {
                            try {
                                mihomoGroups = MihomoConfigRemote(sshClient).getProxyGroups()
                                showAddSheet = true
                            } catch (e: Exception) { actionMessage = e.message }
                            finally { loading = false }
                        }
                    } else showAddSheet = true
                }) {
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
                        if (activeCore == ProxyCore.MIHOMO) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (mihomoSelectable) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.tertiaryContainer
                                    )
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("Ядро: Mihomo", fontWeight = FontWeight.SemiBold)
                                        if (mihomoSelectable) {
                                            Text(
                                                "Нажмите на сервер, чтобы выбрать его вручную. AUTO — выбор по минимальной задержке.",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            OutlinedButton(
                                                onClick = {
                                                    scope.launch {
                                                        loading = true
                                                        try {
                                                            MihomoApi(sshClient).testGroupDelay("PROXY")
                                                            refresh()
                                                        } catch (e: Exception) { actionMessage = e.message }
                                                        finally { loading = false }
                                                    }
                                                },
                                                enabled = !loading
                                            ) { Text("Обновить задержки") }
                                        } else {
                                            Text(
                                                "Ручной выбор здесь доступен для группы PROXY. При добавлении серверов можно выбрать группы текущего конфига.",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
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
                        if (selectableNames.isNotEmpty()) {
                            item {
                                if (selectionMode) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("${selectedNames.size}", modifier = Modifier.weight(1f))
                                            TextButton(onClick = {
                                                selectedNames = if (selectedNames == selectableNames) emptySet() else selectableNames
                                            }, enabled = !loading) {
                                                Text(if (selectedNames == selectableNames) "Снять все" else "Выбрать все")
                                            }
                                            TextButton(onClick = { selectionMode = false; selectedNames = emptySet() }, enabled = !loading) {
                                                Text("Отмена")
                                            }
                                        }
                                        Button(
                                            onClick = { pendingDeletion = selectedNames },
                                            enabled = !loading && selectedNames.isNotEmpty(),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) { Text("Удалить выбранные (${selectedNames.size})") }
                                    }
                                } else {
                                    OutlinedButton(onClick = { selectionMode = true; selectedNames = emptySet() }, enabled = !loading) {
                                        Icon(Icons.Default.Checklist, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Выбрать несколько")
                                    }
                                }
                            }
                        }
                        items(proxies, key = { it.tag }) { proxy ->
                            val canDelete = proxy.tag in deletableNames
                            val toggleSelection: () -> Unit = {
                                selectedNames = if (proxy.tag in selectedNames) selectedNames - proxy.tag else selectedNames + proxy.tag
                            }
                            ProxyCard(
                                proxy = proxy,
                                showDelete = canDelete && !loading && !selectionMode,
                                checked = if (selectionMode && canDelete) proxy.tag in selectedNames else null,
                                onCheckedChange = if (!loading) toggleSelection else null,
                                fromSubscription = activeCore == ProxyCore.MIHOMO && !canDelete && proxy.tag != "AUTO",
                                onClick = if (selectionMode) {
                                    if (canDelete && !loading) toggleSelection else null
                                } else if (activeCore == ProxyCore.MIHOMO && mihomoSelectable && !loading) {
                                    {
                                        scope.launch {
                                            loading = true
                                            try {
                                                val ok = MihomoApi(sshClient).select("PROXY", proxy.tag)
                                                actionMessage = if (ok) "Выбран: ${proxy.tag}" else "Не удалось выбрать ${proxy.tag}"
                                                refresh()
                                            } catch (e: Exception) { actionMessage = e.message }
                                            finally { loading = false }
                                        }
                                    }
                                } else null,
                                onDelete = { pendingDeletion = setOf(proxy.tag) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (pendingDeletion.isNotEmpty()) {
        val keepsServer = (deletableNames - pendingDeletion).isNotEmpty()
        AlertDialog(
            onDismissRequest = { pendingDeletion = emptySet() },
            title = { Text(if (pendingDeletion.size == 1) "Удалить сервер?" else "Удалить ${pendingDeletion.size} серверов?") },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text(pendingDeletion.joinToString("\n"))
                    Spacer(Modifier.height(12.dp))
                    Text(if (keepsServer) "Серверы будут удалены из ${activeCore.title}. Если активный сервер выбран для удаления, будет использован оставшийся."
                        else "Нужно оставить хотя бы один сервер. Снимите выделение с сервера, который хотите сохранить.")
                }
            },
            confirmButton = {
                TextButton(onClick = { deleteServers(pendingDeletion) }, enabled = keepsServer && !loading) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pendingDeletion = emptySet() }) { Text("Отмена") } }
        )
    }

    // Failover setup dialog
    if (showFailoverDialog && sshClient != null) {
        AlertDialog(
            onDismissRequest = { showFailoverDialog = false },
            icon = { Icon(Icons.Default.SwapHoriz, null) },
            title = { Text("Включить автопереключение?") },
            text = {
                Text("У вас теперь ${configState.proxyCount + pendingNewTags.size} сервер(а). " +
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
                                pendingNewTags.forEach { config.removeOutbound(it) }
                                actionMessage = "Config test failed"
                            } else {
                                cmds.restartXkeen()
                                actionMessage = "Добавлено серверов: ${pendingNewTags.size} (без failover)"
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
            mihomoGroups = if (activeCore == ProxyCore.MIHOMO) mihomoGroups else null,
            onDeploy = { vlessLinks, customTag, groups ->
                showAddSheet = false
                loading = true
                scope.launch {
                    try {
                        if (activeCore == ProxyCore.MIHOMO) {
                            actionMessage = "Проверяю и применяю конфиг Mihomo..."
                            val (ok, msg) = MihomoConfigRemote(sshClient).addVless(vlessLinks, customTag, groups)
                            actionMessage = msg
                            if (ok) refresh()
                            return@launch
                        }
                        val config = XrayConfigRemote(sshClient)
                        val cmds = RouterCommands(sshClient)
                        val parser = VlessParser()
                        // Parse the whole batch before writing, then write sequentially.
                        val parsedLinks = vlessLinks.map { parser.parse(it) }
                        val newTags = mutableListOf<String>()
                        for (parsed in parsedLinks) {
                            val outbound = mapToJsonObject(parsed.outbound).let { obj ->
                                if (customTag.isNotBlank() && parsedLinks.size == 1) {
                                    JsonObject(obj.toMutableMap().apply { put("tag", JsonPrimitive(customTag.trim())) })
                                } else obj
                            }
                            val newTag = outbound["tag"]?.jsonPrimitive?.content
                                ?: error("No tag in outbound")
                            actionMessage = "Добавляю $newTag..."
                            val (ok, msg) = config.addOutbound(outbound, parsed.fragmentSettings?.let { mapToJsonObject(it) })
                            if (!ok) { actionMessage = msg; return@launch }
                            newTags += newTag
                        }
                        val state = config.detectConfigState()
                        if (state.proxyCount >= 2 && !state.hasBalancer) {
                            pendingNewTags = newTags
                            showFailoverDialog = true
                            return@launch
                        }
                        if (state.hasBalancer) {
                            newTags.forEach { config.addToBalancer(it) }
                        }
                        actionMessage = "Тестирую конфиг..."
                        val test = cmds.testConfig()
                        if (!test.ok) {
                            newTags.forEach { config.removeOutbound(it) }
                            actionMessage = "Config test failed: ${test.output.takeLast(200)}"
                            return@launch
                        }
                        val (ok, msg) = cmds.restartXkeen()
                        actionMessage = if (ok) "Добавлено серверов: ${newTags.size}" else msg
                        kotlinx.coroutines.delay(3000)
                        refresh()
                    } catch (e: Exception) { actionMessage = e.message }
                    finally { loading = false }
                }
            }
        )
    }
}

@Composable
fun ProxyCard(
    proxy: ProxyInfo,
    showDelete: Boolean = true,
    checked: Boolean? = null,
    onCheckedChange: (() -> Unit)? = null,
    fromSubscription: Boolean = false,
    onClick: (() -> Unit)? = null,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick.invoke() } else Modifier),
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
                if (checked != null) {
                    Checkbox(checked = checked, onCheckedChange = { onCheckedChange?.invoke() }, enabled = onCheckedChange != null)
                }
                Icon(
                    if (proxy.failed) Icons.Default.ErrorOutline else Icons.Default.CheckCircleOutline,
                    null, Modifier.size(20.dp),
                    tint = if (proxy.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(proxy.tag, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (proxy.delayMs > 0) {
                    Text("${proxy.delayMs} ms", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                }
                if (proxy.selected) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary) {
                        Text("ACTIVE", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (showDelete) {
                    IconButton(onClick = onDelete, Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteOutline, "Удалить", Modifier.size(18.dp))
                    }
                }
            }
            if (fromSubscription) {
                Text("Из подписки — удаление в источнике подписки", style = MaterialTheme.typography.bodySmall)
            }
            if (proxy.address.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (proxy.port > 0) "${proxy.address}:${proxy.port}" else proxy.address,
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProxySheet(
    onDismiss: () -> Unit,
    mihomoGroups: List<String>? = null,
    onDeploy: (List<String>, String, List<String>) -> Unit
) {
    var vlessLinks by remember { mutableStateOf("") }
    var customTag by remember { mutableStateOf("") }

    var selectedGroups by remember(mihomoGroups) {
        mutableStateOf(mihomoGroups.orEmpty().filter { it == "PROXY" || it == "AUTO" }
            .ifEmpty { mihomoGroups.orEmpty().take(1) })
    }
    val links = vlessLinks.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val validLinks = links.isNotEmpty() && links.all { it.startsWith("vless://") }
    val isBulk = links.size > 1

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(if (mihomoGroups != null) "Добавить в Mihomo" else "Добавить сервер", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = vlessLinks,
                onValueChange = { vlessLinks = it },
                label = { Text(if (isBulk) "VLESS-ссылки (${links.size} шт.)" else "VLESS-ссылка") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3, maxLines = 8,
                isError = links.isNotEmpty() && !validLinks,
                supportingText = {
                    Text(if (links.isNotEmpty() && !validLinks) "Каждая строка должна начинаться с vless://"
                        else "Можно вставить несколько ссылок — по одной на строку")
                }
            )
            if (!isBulk) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customTag,
                    onValueChange = { customTag = it },
                    label = { Text(if (mihomoGroups != null) "Имя (опционально)" else "Тег (опционально)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("proxy-xx1") },
                    singleLine = true
                )
            }
            if (mihomoGroups != null) {
                Spacer(Modifier.height(16.dp))
                Text("Добавить в группы", style = MaterialTheme.typography.titleSmall)
                if (mihomoGroups.isEmpty()) {
                    Text("В конфиге нет групп для добавления серверов (select, url-test, fallback или load-balance).",
                        color = MaterialTheme.colorScheme.error)
                }
                mihomoGroups.forEach { group ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = group in selectedGroups,
                            onCheckedChange = { checked ->
                                selectedGroups = if (checked) selectedGroups + group else selectedGroups - group
                            }
                        )
                        Text(group)
                    }
                }
                Text("Конфиг будет проверен и применён автоматически.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onDeploy(links, if (!isBulk) customTag else "", selectedGroups) },
                modifier = Modifier.fillMaxWidth(),
                enabled = validLinks && (mihomoGroups == null || selectedGroups.isNotEmpty())
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
