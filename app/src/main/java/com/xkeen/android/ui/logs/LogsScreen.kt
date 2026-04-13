package com.xkeen.android.ui.logs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xkeen.android.data.remote.RouterCommands
import com.xkeen.android.data.ssh.SshClient
import kotlinx.coroutines.launch

@Composable
fun LogsScreen(sshClient: SshClient?) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var logText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val tabs = listOf("Access", "Error")

    fun refresh() {
        if (sshClient == null) return
        loading = true
        scope.launch {
            try {
                val cmds = RouterCommands(sshClient)
                logText = cmds.getLog(if (selectedTab == 0) "access" else "error", 300)
            } catch (e: Exception) {
                logText = "Error: ${e.message}"
            } finally { loading = false }
        }
    }

    LaunchedEffect(sshClient, selectedTab) { refresh() }

    if (sshClient == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Выберите роутер")
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.weight(1f)
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            IconButton(onClick = { refresh() }) {
                Icon(Icons.Default.Refresh, "Обновить")
            }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = logText.ifEmpty { "Лог пуст" },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
