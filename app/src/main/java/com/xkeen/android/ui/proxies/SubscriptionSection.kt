package com.xkeen.android.ui.proxies

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xkeen.android.data.remote.SubscriptionSettings
import com.xkeen.android.data.remote.SubscriptionStatus
import com.xkeen.android.data.remote.isValidSubscriptionUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SubscriptionCard(
    status: SubscriptionStatus?,
    busy: Boolean,
    onConnect: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onRebench: () -> Unit,
    onDisable: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudSync, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Подписка", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (status?.running == true) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            when {
                status == null -> Text("Проверяю…", style = MaterialTheme.typography.bodySmall)
                !status.enabled -> {
                    Text(
                        "Вставьте ссылку подписки провайдера. Роутер сам будет обновлять её раз в сутки, " +
                            "замерять скорость серверов и работать через ${SubscriptionSettings().topN} самых быстрых. " +
                            "Если провайдер сменит адреса, конфиг обновится автоматически.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onConnect, enabled = !busy) {
                        Icon(Icons.Default.Link, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Подключить подписку")
                    }
                }
                else -> {
                    Text(maskUrl(status.url), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Обновление каждый день в ${status.settings.dailyTime} · лучших серверов: ${status.settings.topN}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!status.cronInstalled || !status.scriptInstalled) {
                        Text(
                            "Автообновление на роутере не настроено. Откройте «Настройки» и сохраните их.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    val updated = formatTs(status.lastUpdate)
                    val benched = formatTs(status.lastBench)
                    if (updated.isNotEmpty() || benched.isNotEmpty()) {
                        Text(
                            listOfNotNull(
                                updated.takeIf { it.isNotEmpty() }?.let { "подписка: $it" },
                                benched.takeIf { it.isNotEmpty() }?.let { "замер: $it" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (status.running) {
                        Text("Идёт обновление и замер скорости, это несколько минут…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    } else if (status.lastError.isNotBlank()) {
                        Text("Последняя ошибка: ${status.lastError}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    val chosen = status.chosen
                    if (chosen.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text("Работаю через (${chosen.size}):", style = MaterialTheme.typography.labelLarge)
                        chosen.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "↓${s.downMbit.toInt()} ↑${s.upMbit.toInt()} Мбит/с",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (!status.running) {
                        Text("Замер ещё не выполнялся: пока используются все серверы подписки.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRefresh, enabled = !busy && !status.running, modifier = Modifier.weight(1f)) {
                            Text("Обновить")
                        }
                        OutlinedButton(onClick = onRebench, enabled = !busy && !status.running, modifier = Modifier.weight(1f)) {
                            Text("Замерить")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSettings, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text("Настройки")
                        }
                        TextButton(onClick = onDisable, enabled = !busy && !status.running, modifier = Modifier.weight(1f)) {
                            Text("Отключить", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionDialog(
    initialUrl: String,
    initialSettings: SubscriptionSettings,
    editing: Boolean,
    onDismiss: () -> Unit,
    onSave: (url: String, settings: SubscriptionSettings) -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var topN by remember { mutableStateOf(initialSettings.topN.toString()) }
    var time by remember { mutableStateOf(initialSettings.dailyTime) }
    val urlOk = isValidSubscriptionUrl(url)
    val topOk = topN.toIntOrNull()?.let { it in 1..30 } == true
    val timeOk = SubscriptionSettings.parseTime(time) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.CloudSync, null) },
        title = { Text(if (editing) "Настройки подписки" else "Подключить подписку") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text("Ссылка подписки") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    isError = url.isNotEmpty() && !urlOk,
                    supportingText = { if (url.isNotEmpty() && !urlOk) Text("Нужна ссылка http:// или https://") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = topN,
                    onValueChange = { v -> topN = v.filter { it.isDigit() }.take(2) },
                    label = { Text("Лучших серверов") },
                    singleLine = true,
                    isError = !topOk,
                    supportingText = { Text("от 1 до 30, обычно 10") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = time,
                    onValueChange = { v -> time = v.filter { it.isDigit() || it == ':' }.take(5) },
                    label = { Text("Время обновления, ЧЧ:ММ") },
                    singleLine = true,
                    isError = !timeOk,
                    supportingText = { Text("По времени роутера. Ночью замер меньше мешает") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Каждый день роутер скачивает подписку, проверяет доступность всех серверов, " +
                        "замеряет скорость скачивания и отдачи у самых отзывчивых и оставляет лучшие. " +
                        "Каждые 30 минут — быстрая проверка: если из выбранных работает меньше трёх, замер повторяется. " +
                        "Один замер расходует около 0,5 ГБ трафика.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(url.trim(), SubscriptionSettings(topN.toInt(), time.trim())) },
                enabled = urlOk && topOk && timeOk
            ) { Text(if (editing) "Сохранить" else "Подключить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun maskUrl(url: String): String {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
    val path = uri.rawPath.orEmpty()
    val tail = if (path.length > 8) path.take(4) + "…" + path.takeLast(4) else path
    return "${uri.scheme}://${uri.host}$tail"
}

private fun formatTs(seconds: Long): String {
    if (seconds <= 0) return ""
    return SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(seconds * 1000))
}
