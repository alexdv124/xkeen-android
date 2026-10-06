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
        it["type"] in setOf("select", "url-test", "fallback", "load-balance")
    }.map { name(it) }

    fun proxyNames(raw: String): Set<String> = maps(document(raw)["proxies"])
        .filter { it["type"] !in setOf("direct", "reject", "dns", "pass") }.map { name(it) }.toSet()

    fun removeProxies(raw: String, names: Set<String>): String {
        val config = document(raw)
        val removable = proxyNames(raw)
        require(names.isNotEmpty() && removable.containsAll(names)) {
            "Можно удалить только серверы из текущего конфига. Серверы подписок изменяются в подписке"
        }
        val remaining = maps(config["proxies"]).filter { name(it) !in names }
        val fallback = remaining.firstOrNull { name(it) in removable }?.let { name(it) }
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
        config["rules"] = rules
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
