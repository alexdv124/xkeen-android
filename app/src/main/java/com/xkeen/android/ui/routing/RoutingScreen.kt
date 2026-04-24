package com.xkeen.android.ui.routing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xkeen.android.data.remote.RouterCommands
import com.xkeen.android.data.remote.XrayConfigRemote
import com.xkeen.android.data.ssh.SshClient
import com.xkeen.android.domain.model.*
import kotlinx.coroutines.launch

@Composable
fun RoutingScreen(sshClient: SshClient?) {
    val scope = rememberCoroutineScope()
    var routingConfig by remember { mutableStateOf(RoutingConfig()) }
    var proxies by remember { mutableStateOf<List<ProxyInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showCustomRouteDialog by remember { mutableStateOf(false) }
    var deviceToRoute by remember { mutableStateOf<com.xkeen.android.data.remote.NetworkDevice?>(null) }

    fun refresh() {
        if (sshClient == null) return
        loading = true; message = null
        scope.launch {
            try {
                val config = XrayConfigRemote(sshClient)
                routingConfig = config.getRoutingConfig()
                proxies = config.getProxyList()
            } catch (e: Exception) { message = e.message }
            finally { loading = false }
        }
    }

    fun applyPresetAndRestart(preset: RoutingPreset) {
        if (sshClient == null) return
        loading = true
        scope.launch {
            try {
                val config = XrayConfigRemote(sshClient)
                val cmds = RouterCommands(sshClient)
                val (ok, msg) = config.applyPreset(preset, routingConfig.customRoutes, routingConfig.quicBlocked, routingConfig.youtubeUnblock, routingConfig.aqaraEnabled)
                if (!ok) { message = msg; return@launch }
                val test = cmds.testConfig()
                if (!test.ok) {
                    message = "Config test failed: ${test.output.takeLast(200)}"
                    return@launch
                }
                cmds.restartXkeen()
                message = "Применён: ${preset.title}"
                refresh()
            } catch (e: Exception) { message = e.message }
            finally { loading = false }
        }
    }

    LaunchedEffect(sshClient) { refresh() }

    if (sshClient == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Выберите роутер")
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        message?.let {
            Card(colors = CardDefaults.cardColors(
                containerColor = if ("failed" in it.lowercase()) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer
            )) { Text(it, Modifier.padding(16.dp)) }
        }

        // === PRESETS ===
        Text("Режим маршрутизации", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        PresetCard(
            title = "Стандартный",
            description = "Российские сайты напрямую, остальное через VPN",
            icon = Icons.Default.SwapHoriz,
            selected = routingConfig.preset == RoutingPreset.RU_DIRECT,
            onClick = { applyPresetAndRestart(RoutingPreset.RU_DIRECT) }
        )

        PresetCard(
            title = "Всё через VPN",
            description = "Весь трафик через прокси-серверы",
            icon = Icons.Default.VpnLock,
            selected = routingConfig.preset == RoutingPreset.ALL_VPN,
            onClick = { applyPresetAndRestart(RoutingPreset.ALL_VPN) }
        )

        PresetCard(
            title = "Всё напрямую",
            description = "VPN отключён, трафик идёт через провайдера",
            icon = Icons.Default.PublicOff,
            selected = routingConfig.preset == RoutingPreset.ALL_DIRECT,
            onClick = { applyPresetAndRestart(RoutingPreset.ALL_DIRECT) }
        )

        // === QUIC TOGGLE (iptables ICMP reject) ===
        Card(Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Block, null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Блокировать QUIC", fontWeight = FontWeight.Medium)
                    Text("UDP/443 → ICMP reject. Мгновенный fallback на TCP, без задержек",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    enabled = !loading,
                    checked = routingConfig.quicBlocked,
                    onCheckedChange = { checked ->
                        scope.launch {
                            loading = true
                            try {
                                val cmds = RouterCommands(sshClient)
                                val config = XrayConfigRemote(sshClient)
                                val (ok, msg) = cmds.applyQuicReject(checked)
                                if (!ok) { message = msg; return@launch }
                                // Rebuild routing to remove any legacy UDP 443 xray block rule
                                config.applyPreset(routingConfig.preset, routingConfig.customRoutes, checked, routingConfig.youtubeUnblock, routingConfig.aqaraEnabled)
                                if (cmds.testConfig().ok) {
                                    cmds.restartXkeen()
                                    message = if (checked) "QUIC заблокирован (ICMP)" else "QUIC разблокирован"
                                    refresh()
                                } else { message = "Config test failed" }
                            } catch (e: Exception) { message = e.message }
                            finally { loading = false }
                        }
                    }
                )
            }
        }

        // === YOUTUBE UNBLOCK TOGGLE ===
        Card(Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.PlayCircle, null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Проксировать YouTube", fontWeight = FontWeight.Medium)
                    Text("googlevideo/youtube/ytimg через VPN. Решает проблему Shorts с ТСПУ-троттлингом",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    enabled = !loading,
                    checked = routingConfig.youtubeUnblock,
                    onCheckedChange = { checked ->
                        scope.launch {
                            loading = true
                            try {
                                val config = XrayConfigRemote(sshClient)
                                val cmds = RouterCommands(sshClient)
                                config.applyPreset(routingConfig.preset, routingConfig.customRoutes, routingConfig.quicBlocked, checked, routingConfig.aqaraEnabled)
                                if (cmds.testConfig().ok) {
                                    cmds.restartXkeen()
                                    message = if (checked) "YouTube через VPN" else "YouTube через geo-правила"
                                    refresh()
                                } else { message = "Config test failed" }
                            } catch (e: Exception) { message = e.message }
                            finally { loading = false }
                        }
                    }
                )
            }
        }

        // === AQARA TOGGLE (smart home) ===
        Card(Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Videocam, null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Проксировать Aqara (умный дом)", fontWeight = FontWeight.Medium)
                    Text("Kingsoft Cloud IP + домены aqara.com/cn через VPN. Решает ТСПУ-блок хаба и приложения",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    enabled = !loading,
                    checked = routingConfig.aqaraEnabled,
                    onCheckedChange = { checked ->
                        scope.launch {
                            loading = true
                            try {
                                val config = XrayConfigRemote(sshClient)
                                val cmds = RouterCommands(sshClient)
                                // Also strip any legacy Aqara custom routes so they don't duplicate the preset
                                val cleaned = routingConfig.customRoutes.filterNot { it.comment.contains("Aqara") }
                                config.applyPreset(routingConfig.preset, cleaned, routingConfig.quicBlocked, routingConfig.youtubeUnblock, checked)
                                if (cmds.testConfig().ok) {
                                    cmds.restartXkeen()
                                    message = if (checked) "Aqara через VPN" else "Aqara через geo-правила"
                                    refresh()
                                } else { message = "Config test failed" }
                            } catch (e: Exception) { message = e.message }
                            finally { loading = false }
                        }
                    }
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // === BALANCER MODE ===
        if (routingConfig.preset != RoutingPreset.ALL_DIRECT) {
            Text("Балансировка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = routingConfig.mode is RoutingMode.Auto,
                            onClick = {
                                scope.launch {
                                    loading = true
                                    try {
                                        val config = XrayConfigRemote(sshClient)
                                        val cmds = RouterCommands(sshClient)
                                        config.setRoutingMode(RoutingMode.Auto)
                                        val test = cmds.testConfig()
                                        if (test.ok) { cmds.restartXkeen(); message = "Авто-режим"; refresh() }
                                        else { message = "Test failed" }
                                    } catch (e: Exception) { message = e.message }
                                    finally { loading = false }
                                }
                            },
                            label = { Text("Авто") },
                            leadingIcon = { Icon(Icons.Default.AutoMode, null, Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = routingConfig.mode is RoutingMode.Manual,
                            onClick = {
                                // Switch to manual — pick first proxy as default
                                val firstProxy = proxies.firstOrNull() ?: return@FilterChip
                                scope.launch {
                                    loading = true
                                    try {
                                        val config = XrayConfigRemote(sshClient)
                                        val cmds = RouterCommands(sshClient)
                                        config.setRoutingMode(RoutingMode.Manual(firstProxy.tag))
                                        val test = cmds.testConfig()
                                        if (test.ok) { cmds.restartXkeen(); message = "Ручной режим: ${firstProxy.tag}"; refresh() }
                                        else { config.setRoutingMode(RoutingMode.Auto); message = "Test failed" }
                                    } catch (e: Exception) { message = e.message }
                                    finally { loading = false }
                                }
                            },
                            label = { Text("Ручной") },
                            leadingIcon = { Icon(Icons.Default.TouchApp, null, Modifier.size(16.dp)) }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Server selection
                    proxies.forEach { proxy ->
                        val isManualSelected = routingConfig.mode is RoutingMode.Manual &&
                            (routingConfig.mode as RoutingMode.Manual).tag == proxy.tag
                        val isInBalancer = proxy.tag in routingConfig.balancerTags

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (routingConfig.mode is RoutingMode.Auto) {
                                Checkbox(
                                    checked = isInBalancer,
                                    onCheckedChange = { checked ->
                                        val newTags = if (checked) routingConfig.balancerTags + proxy.tag
                                        else routingConfig.balancerTags - proxy.tag
                                        if (newTags.isEmpty()) { message = "Минимум один сервер"; return@Checkbox }
                                        scope.launch {
                                            loading = true
                                            try {
                                                val config = XrayConfigRemote(sshClient)
                                                val cmds = RouterCommands(sshClient)
                                                config.setBalancerTags(newTags)
                                                if (cmds.testConfig().ok) { cmds.restartXkeen(); refresh() }
                                            } catch (e: Exception) { message = e.message }
                                            finally { loading = false }
                                        }
                                    }
                                )
                            } else {
                                RadioButton(
                                    selected = isManualSelected,
                                    onClick = {
                                        scope.launch {
                                            loading = true
                                            try {
                                                val config = XrayConfigRemote(sshClient)
                                                val cmds = RouterCommands(sshClient)
                                                config.setRoutingMode(RoutingMode.Manual(proxy.tag))
                                                if (cmds.testConfig().ok) { cmds.restartXkeen(); message = "Выбран: ${proxy.tag}"; refresh() }
                                                else { config.setRoutingMode(RoutingMode.Auto); message = "Test failed" }
                                            } catch (e: Exception) { message = e.message }
                                            finally { loading = false }
                                        }
                                    }
                                )
                            }
                            Column {
                                Text(proxy.tag, fontWeight = if (isManualSelected) FontWeight.Bold else FontWeight.Normal)
                                Text("${proxy.address} · ${proxy.transport}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // === CUSTOM ROUTES ===
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Особые маршруты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { showCustomRouteDialog = true }, enabled = !loading) {
                    Icon(Icons.Default.Add, "Добавить маршрут")
                }
            }

            if (routingConfig.customRoutes.isEmpty()) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        "Нет особых маршрутов. Добавьте домен или IP для принудительного направления через VPN или напрямую",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                routingConfig.customRoutes.forEach { route ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                when (route.routeType) {
                                    "source" -> Icons.Default.Devices
                                    "domain" -> Icons.Default.Language
                                    else -> if (route.target == "proxy") Icons.Default.VpnKey
                                            else Icons.Default.Public
                                },
                                null, Modifier.size(20.dp),
                                tint = if (route.target == "proxy") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(route.value, fontWeight = FontWeight.Medium)
                                val label = when {
                                    route.routeType == "source" -> "Устройство → весь трафик"
                                    route.routeType == "domain" && route.comment.isNotEmpty() -> route.comment
                                    route.routeType == "domain" -> "Домен"
                                    else -> route.comment
                                }
                                if (label.isNotEmpty()) {
                                    Text(label, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (route.target == "proxy") MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    if (route.target == "proxy") "VPN" else "DIRECT",
                                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            IconButton(
                                onClick = {
                                    val newRoutes = routingConfig.customRoutes - route
                                    scope.launch {
                                        loading = true
                                        try {
                                            val config = XrayConfigRemote(sshClient)
                                            val cmds = RouterCommands(sshClient)
                                            config.applyPreset(routingConfig.preset, newRoutes, routingConfig.quicBlocked, routingConfig.youtubeUnblock, routingConfig.aqaraEnabled)
                                            if (cmds.testConfig().ok) { cmds.restartXkeen(); refresh() }
                                            else { message = "Тест конфига провалился" }
                                        } catch (e: Exception) { message = e.message }
                                        finally { loading = false }
                                    }
                                },
                                enabled = !loading,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, "Удалить", Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // === IoT DEVICES ===
        var showIotSheet by remember { mutableStateOf(false) }
        var devices by remember { mutableStateOf<List<com.xkeen.android.data.remote.NetworkDevice>>(emptyList()) }

        Card(
            onClick = {
                showIotSheet = !showIotSheet
                if (showIotSheet && devices.isEmpty()) {
                    scope.launch {
                        try { devices = RouterCommands(sshClient).getNetworkDevices() }
                        catch (_: Exception) {}
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DeviceHub, null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Устройства в сети", fontWeight = FontWeight.Medium)
                    Text("Добавить IP устройства в особый маршрут",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (showIotSheet) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null
                )
            }
        }

        if (showIotSheet && devices.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    devices.forEach { dev ->
                        val existingRoute = routingConfig.customRoutes.find {
                            it.routeType == "source" && it.value == dev.ip
                        }
                        Surface(
                            onClick = {
                                if (existingRoute == null) deviceToRoute = dev
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Devices, null, Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(dev.ip, style = MaterialTheme.typography.bodyMedium)
                                    Text("${dev.mac} · ${dev.iface}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (existingRoute != null) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = if (existingRoute.target == "proxy") MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            if (existingRoute.target == "proxy") "VPN" else "DIRECT",
                                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                } else {
                                    Icon(Icons.Default.AddCircleOutline, "Добавить маршрут",
                                        Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // === GEOSITE UPDATE ===
        var geoUpdating by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = {
                geoUpdating = true
                scope.launch {
                    try {
                        val (ok, out) = RouterCommands(sshClient).updateGeoFiles()
                        message = if (ok) "Базы обновлены" else "Ошибка: ${out.takeLast(200)}"
                    } catch (e: Exception) { message = e.message }
                    finally { geoUpdating = false }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !geoUpdating
        ) {
            if (geoUpdating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.SystemUpdate, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Обновить geosite/geoip базы")
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // === ACTIONS ===
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        loading = true
                        try {
                            val test = RouterCommands(sshClient).testConfig()
                            message = if (test.ok) "Configuration OK ✓" else "FAILED: ${test.output.takeLast(200)}"
                        } catch (e: Exception) { message = e.message }
                        finally { loading = false }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FactCheck, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Тест")
            }
            Button(
                onClick = {
                    scope.launch {
                        loading = true
                        try {
                            val (running, _) = RouterCommands(sshClient).restartXkeen()
                            message = if (running) "Xray перезапущен" else "Не запустился!"
                        } catch (e: Exception) { message = e.message }
                        finally { loading = false }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Рестарт")
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    // === DIALOGS ===

    if (showCustomRouteDialog) {
        AddCustomRouteDialog(
            onDismiss = { showCustomRouteDialog = false },
            onAdd = { route ->
                showCustomRouteDialog = false
                val newRoutes = routingConfig.customRoutes + route
                scope.launch {
                    loading = true
                    try {
                        val config = XrayConfigRemote(sshClient)
                        val cmds = RouterCommands(sshClient)
                        config.applyPreset(routingConfig.preset, newRoutes, routingConfig.quicBlocked, routingConfig.youtubeUnblock, routingConfig.aqaraEnabled)
                        if (cmds.testConfig().ok) {
                            cmds.restartXkeen()
                            message = "Маршрут добавлен: ${route.value}"
                            refresh()
                        } else { message = "Config test failed" }
                    } catch (e: Exception) { message = e.message }
                    finally { loading = false }
                }
            }
        )
    }

    deviceToRoute?.let { dev ->
        var selectedTarget by remember { mutableStateOf("proxy") }
        AlertDialog(
            onDismissRequest = { deviceToRoute = null },
            icon = { Icon(Icons.Default.Devices, null) },
            title = { Text("Маршрут для устройства") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Весь интернет-трафик от ${dev.ip} будет направлен через выбранный канал.")
                    Text("MAC: ${dev.mac}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("Направление:", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedTarget == "proxy",
                            onClick = { selectedTarget = "proxy" },
                            label = { Text("Через VPN") },
                            leadingIcon = { Icon(Icons.Default.VpnKey, null, Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = selectedTarget == "direct",
                            onClick = { selectedTarget = "direct" },
                            label = { Text("Напрямую") },
                            leadingIcon = { Icon(Icons.Default.Public, null, Modifier.size(16.dp)) }
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val route = CustomRoute(
                        value = dev.ip,
                        target = selectedTarget,
                        comment = "Устройство (${dev.mac})",
                        routeType = "source"
                    )
                    deviceToRoute = null
                    val newRoutes = routingConfig.customRoutes + route
                    scope.launch {
                        loading = true
                        try {
                            val config = XrayConfigRemote(sshClient)
                            val cmds = RouterCommands(sshClient)
                            config.applyPreset(routingConfig.preset, newRoutes, routingConfig.quicBlocked, routingConfig.youtubeUnblock, routingConfig.aqaraEnabled)
                            if (cmds.testConfig().ok) {
                                cmds.restartXkeen()
                                message = "Маршрут добавлен: ${dev.ip} → ${if (selectedTarget == "proxy") "VPN" else "напрямую"}"
                                refresh()
                            } else { message = "Config test failed" }
                        } catch (e: Exception) { message = e.message }
                        finally { loading = false }
                    }
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { deviceToRoute = null }) { Text("Отмена") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetCard(title: String, description: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (selected) CardDefaults.outlinedCardBorder() else null
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon, null, Modifier.size(28.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Icon(Icons.Default.CheckCircle, "Активен",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
fun AddCustomRouteDialog(onDismiss: () -> Unit, onAdd: (CustomRoute) -> Unit) {
    var value by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("proxy") }
    var routeType by remember { mutableStateOf("domain") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить маршрут") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Тип:", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = routeType == "domain",
                        onClick = { routeType = "domain"; value = "" },
                        label = { Text("Домен") },
                        leadingIcon = { Icon(Icons.Default.Language, null, Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = routeType == "ip",
                        onClick = { routeType = "ip"; value = "" },
                        label = { Text("IP / подсеть") },
                        leadingIcon = { Icon(Icons.Default.Lan, null, Modifier.size(16.dp)) }
                    )
                }
                OutlinedTextField(
                    value = value, onValueChange = { value = it },
                    label = { Text(if (routeType == "domain") "Домен" else "IP / подсеть") },
                    placeholder = { Text(if (routeType == "domain") "youtube.com" else "1.2.3.0/24") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it },
                    label = { Text("Комментарий (опционально)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text("Направление:", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = target == "proxy",
                        onClick = { target = "proxy" },
                        label = { Text("Через VPN") },
                        leadingIcon = { Icon(Icons.Default.VpnKey, null, Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = target == "direct",
                        onClick = { target = "direct" },
                        label = { Text("Напрямую") },
                        leadingIcon = { Icon(Icons.Default.Public, null, Modifier.size(16.dp)) }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleaned = if (routeType == "domain") {
                        value.trim()
                            .removePrefix("https://").removePrefix("http://")
                            .removeSuffix("/")
                            .substringBefore("/")
                            .lowercase()
                    } else value.trim()
                    onAdd(CustomRoute(cleaned, target, comment.trim(), routeType = routeType))
                },
                enabled = value.isNotBlank()
            ) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
