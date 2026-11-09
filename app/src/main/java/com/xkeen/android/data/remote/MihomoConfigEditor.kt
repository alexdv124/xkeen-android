package com.xkeen.android.data.remote

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer

/** Edits the existing document, preserving settings outside the requested sections. */
internal class MihomoConfigEditor {
    private val loader = LoaderOptions().apply { isAllowDuplicateKeys = false }
    private val dumper = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
    }
    private val yaml = Yaml(SafeConstructor(loader), Representer(dumper), dumper, loader)

    fun groups(raw: String): List<String> = maps(document(raw)["proxy-groups"]).filter {
        it["type"] in setOf("select", "url-test", "fallback", "load-balance") && name(it) !in SubscriptionNames.GROUPS
    }.map { name(it) }

    /** URL of the app-managed subscription provider, or null when none is configured. */
    fun subscriptionUrl(raw: String): String? {
        val providers = document(raw)["proxy-providers"] as? Map<*, *> ?: return null
        return ((providers[SubscriptionNames.PROVIDER] as? Map<*, *>)?.get("url") as? String)?.takeIf { it.isNotBlank() }
    }

    /**
     * Adds (or updates) the app-managed subscription: an HTTP proxy-provider that Mihomo refreshes
     * itself, SUB-BEST (url-test over the servers picked by the router-side benchmark, via `filter`),
     * SUB-ALL (url-test over the whole subscription), the hidden SUB-BENCH selector and a loopback
     * listener used only to measure each server. PROXY gets SUB-BEST and SUB-ALL as its first members.
     */
    fun enableSubscription(raw: String, url: String): String {
        val clean = url.trim()
        require(isValidSubscriptionUrl(clean)) { "Ссылка подписки должна начинаться с http:// или https://" }
        val config = document(raw)
        val groups = maps(config["proxy-groups"])
        val proxyNames = maps(config["proxies"]).map { name(it) }.toSet()
        groups.filter { name(it) in SubscriptionNames.GROUPS && !isManagedGroup(it) }.firstOrNull()?.let {
            throw IllegalArgumentException("В конфиге уже есть группа ${name(it)}. Переименуйте её, чтобы подключить подписку")
        }
        SubscriptionNames.GROUPS.firstOrNull { it in proxyNames }?.let {
            throw IllegalArgumentException("В конфиге уже есть сервер с именем $it. Переименуйте его, чтобы подключить подписку")
        }

        val providers = LinkedHashMap<String, Any?>()
        (config["proxy-providers"] as? Map<*, *>)?.forEach { (k, v) -> providers[k.toString()] = v }
        providers[SubscriptionNames.PROVIDER] = linkedMapOf(
            "type" to "http",
            "url" to clean,
            "path" to SubscriptionNames.PROVIDER_PATH,
            // Safety net only: the router cron refreshes daily at the chosen time.
            "interval" to 86400,
            "health-check" to linkedMapOf(
                "enable" to true,
                "url" to SubscriptionNames.TEST_URL,
                "interval" to 600,
                "timeout" to 5000,
                "lazy" to false
            )
        )
        config["proxy-providers"] = providers

        val listeners = maps(config["listeners"]).filter { it["name"] != SubscriptionNames.LISTENER }
        require(listeners.none { (it["port"] as? Number)?.toInt() == SubscriptionNames.BENCH_PORT }) {
            "Порт ${SubscriptionNames.BENCH_PORT} уже занят другим listener в конфиге Mihomo"
        }
        config["listeners"] = listeners + linkedMapOf(
            "name" to SubscriptionNames.LISTENER,
            "type" to "mixed",
            "port" to SubscriptionNames.BENCH_PORT,
            "listen" to "127.0.0.1",
            "udp" to false
        )

        val oldBest = groups.firstOrNull { name(it) == SubscriptionNames.BEST }
        val best = linkedMapOf<String, Any?>(
            "name" to SubscriptionNames.BEST,
            "type" to "url-test",
            "use" to listOf(SubscriptionNames.PROVIDER),
            "url" to SubscriptionNames.TEST_URL,
            "interval" to 300,
            "tolerance" to 50,
            "lazy" to false
        )
        // Keep the filter picked by the last benchmark when the settings are re-applied.
        (oldBest?.get("filter") as? String)?.takeIf { it.isNotBlank() }?.let { best["filter"] = it }
        val all = linkedMapOf<String, Any?>(
            "name" to SubscriptionNames.ALL,
            "type" to "url-test",
            "use" to listOf(SubscriptionNames.PROVIDER),
            "url" to SubscriptionNames.TEST_URL,
            "interval" to 600,
            "tolerance" to 50,
            "lazy" to false
        )
        val bench = linkedMapOf<String, Any?>(
            "name" to SubscriptionNames.BENCH,
            "type" to "select",
            "use" to listOf(SubscriptionNames.PROVIDER),
            "hidden" to true
        )

        val front = listOf(SubscriptionNames.BEST, SubscriptionNames.ALL)
        val kept = groups.filter { name(it) !in SubscriptionNames.GROUPS }
        val updated = if (kept.any { name(it) == "PROXY" }) {
            kept.map { group ->
                if (name(group) != "PROXY") group
                else group.toMutableMap().apply {
                    val members = (group["proxies"] as? List<*>).orEmpty().filterNot { it in front }
                    put("proxies", front + members)
                }
            }
        } else {
            listOf(linkedMapOf<String, Any?>("name" to "PROXY", "type" to "select", "proxies" to front)) + kept
        }
        config["proxy-groups"] = updated + listOf(best, all, bench)

        val rules = (config["rules"] as? List<*>).orEmpty().filterNot { it == SubscriptionNames.BENCH_RULE }
        config["rules"] = listOf(SubscriptionNames.BENCH_RULE) + rules.ifEmpty { listOf("MATCH,PROXY") }
        return yaml.dump(config)
    }

    /** Removes everything [enableSubscription] added. Groups left without servers are rejected. */
    fun disableSubscription(raw: String): String {
        val config = document(raw)
        (config["proxy-providers"] as? Map<*, *>)?.let { providers ->
            val rest = providers.filterKeys { it != SubscriptionNames.PROVIDER }
            if (rest.isEmpty()) config.remove("proxy-providers") else config["proxy-providers"] = rest
        }
        maps(config["listeners"]).filter { it["name"] != SubscriptionNames.LISTENER }.let {
            if (it.isEmpty()) config.remove("listeners") else config["listeners"] = it
        }
        val groups = maps(config["proxy-groups"]).filter { name(it) !in SubscriptionNames.GROUPS }.map { group ->
            val members = group["proxies"] as? List<*> ?: return@map group
            group.toMutableMap().apply { put("proxies", members.filterNot { it in SubscriptionNames.GROUPS }) }
        }
        groups.forEach { group ->
            val members = (group["proxies"] as? List<*>).orEmpty()
            val dynamic = (group["use"] as? List<*>)?.isNotEmpty() == true ||
                group["include-all"] == true || group["include-all-proxies"] == true
            require(members.isNotEmpty() || dynamic) {
                "После отключения подписки в группе ${name(group)} не останется серверов. Сначала добавьте серверы вручную"
            }
        }
        config["proxy-groups"] = groups
        (config["rules"] as? List<*>)?.let { rules ->
            config["rules"] = rules.filterNot { it == SubscriptionNames.BENCH_RULE }
        }
        return yaml.dump(config)
    }

    private fun isManagedGroup(group: Map<String, Any?>): Boolean =
        (group["use"] as? List<*>)?.contains(SubscriptionNames.PROVIDER) == true

    fun proxyNames(raw: String): Set<String> = maps(document(raw)["proxies"])
        .filter { it["type"] !in setOf("direct", "reject", "dns", "pass") }.map { name(it) }.toSet()

    fun removeProxies(raw: String, names: Set<String>): String {
        val config = document(raw)
        val removable = proxyNames(raw)
        require(names.isNotEmpty() && removable.containsAll(names)) {
            "Можно удалить только серверы из текущего конфига. Серверы подписок изменяются в подписке"
        }
        val remaining = maps(config["proxies"]).filter { name(it) !in names }
        // With an app-managed subscription the servers can all come from it, so its group may
        // replace the deleted nodes.
        val hasSubscription = maps(config["proxy-groups"]).any { name(it) == SubscriptionNames.BEST && isManagedGroup(it) }
        val fallback = remaining.firstOrNull { name(it) in removable }?.let { name(it) }
            ?: SubscriptionNames.BEST.takeIf { hasSubscription }
        require(fallback != null) { "Оставьте хотя бы один сервер Mihomo" }
        remaining.forEach { proxy ->
            require(proxy["dialer-proxy"] !in names) {
                "${name(proxy)} использует удаляемый сервер как dialer-proxy. Удалите их вместе"
            }
        }
        config["proxies"] = remaining
        config["proxy-groups"] = maps(config["proxy-groups"]).map { group ->
            val members = group["proxies"] as? List<*> ?: return@map group
            if (members.none { it in names }) return@map group
            val kept = members.filterNot { it in names }
            val dynamic = (group["use"] as? List<*>)?.isNotEmpty() == true ||
                group["include-all"] == true || group["include-all-proxies"] == true
            group.toMutableMap().apply {
                put("proxies", if (kept.isEmpty() && !dynamic) listOf(fallback) else kept)
            }
        }
        fun rewriteRule(value: Any?): Any? {
            if (value !is String) return value
            val parts = value.split(',').toMutableList()
            var target = parts.lastIndex
            while (target > 0 && parts[target].trim() in setOf("no-resolve", "src", "dst")) target--
            if (target > 0 && parts[target].trim() in names) parts[target] = fallback
            return parts.joinToString(",")
        }
        (config["rules"] as? List<*>)?.let { rules -> config["rules"] = rules.map { rewriteRule(it) } }
        (config["sub-rules"] as? Map<*, *>)?.let { subRules ->
            config["sub-rules"] = subRules.mapValues { (_, rules) ->
                (rules as? List<*>)?.map { rewriteRule(it) } ?: rules
            }
        }
        // These fields may refer to a concrete node, outside the ordinary routing rules.
        for (key in listOf("proxy-providers", "rule-providers")) {
            (config[key] as? Map<*, *>)?.let { providers ->
                config[key] = providers.mapValues { (_, provider) ->
                    if (provider is Map<*, *> && provider["proxy"] in names) {
                        provider.toMutableMap().apply { put("proxy", fallback) }
                    } else provider
                }
            }
        }
        return yaml.dump(config)
    }

    fun addVless(raw: String, links: List<String>, customName: String, targetGroups: List<String>): String {
        require(links.isNotEmpty()) { "Вставьте хотя бы одну VLESS-ссылку" }
        val config = document(raw)
        val proxies = maps(config["proxies"]).toMutableList()
        val groups = maps(config["proxy-groups"])
        require(targetGroups.isNotEmpty() && targetGroups.all { it in groups(raw) }) {
            "Выберите группу Mihomo для новых серверов"
        }
        val usedNames = (proxies.map { name(it) } + groups.map { name(it) } +
            listOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL")).toMutableSet()
        val builder = MihomoConfigBuilder()
        val newNames = links.map { link ->
            val explicitName = customName.trim().takeIf { links.size == 1 }.orEmpty()
            val proxy = maps(yaml.load<Any>(builder.fromVless(link.trim(), explicitName))).single().toMutableMap()
            val base = name(proxy)
            require(explicitName.isEmpty() || base !in usedNames) { "Имя $base уже занято" }
            var unique = base
            var suffix = 2
            while (unique in usedNames) unique = "$base-${suffix++}"
            usedNames += unique
            proxy["name"] = unique
            proxies += proxy
            unique
        }
        config["proxies"] = proxies
        config["proxy-groups"] = groups.map { group ->
            if (name(group) !in targetGroups || group["include-all"] == true || group["include-all-proxies"] == true) {
                group
            } else {
                group.toMutableMap().apply {
                    val members = group["proxies"] as? List<*> ?: emptyList<Any>()
                    put("proxies", (members + newNames).distinct())
                }
            }
        }
        return yaml.dump(config)
    }

    fun replaceRules(raw: String, rules: List<String>): String {
        val config = document(raw)
        require(rules.none { it.endsWith(",PROXY") } || maps(config["proxy-groups"]).any { name(it) == "PROXY" }) {
            "Для пресетов маршрутизации нужна группа Mihomo с именем PROXY"
        }
        // The subscription benchmark depends on its listener rule being first; routing presets
        // must not drop it.
        val keepBench = (config["rules"] as? List<*>)?.contains(SubscriptionNames.BENCH_RULE) == true
        config["rules"] = if (keepBench) listOf(SubscriptionNames.BENCH_RULE) + rules.filterNot { it == SubscriptionNames.BENCH_RULE } else rules
        return yaml.dump(config)
    }

    private fun document(raw: String): MutableMap<String, Any?> = map(yaml.load<Any>(raw)).toMutableMap()

    private fun maps(value: Any?): List<Map<String, Any?>> {
        if (value == null) return emptyList()
        require(value is List<*>) { "Ожидался список в конфиге Mihomo" }
        return value.map { map(it) }
    }

    private fun map(value: Any?): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String }) { "Некорректная структура YAML Mihomo" }
        return value.entries.associate { it.key as String to it.value }
    }

    private fun name(value: Map<String, Any?>): String = (value["name"] as? String)
        ?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("У сервера или группы Mihomo отсутствует имя")
}
