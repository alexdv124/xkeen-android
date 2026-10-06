package com.xkeen.android.data.remote

import kotlinx.serialization.json.*

/** Removes a batch and repairs references in the app's standard Xray config files. */
internal class XrayServerEditor {
    private val json = Json { prettyPrint = true }

    fun remove(configs: Map<String, String>, tags: Set<String>): Map<String, String> {
        val outConfig = Json.parseToJsonElement(configs.getValue(Paths.OUTBOUNDS)).jsonObject
        val all = outConfig["outbounds"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        fun tag(outbound: JsonObject) = outbound["tag"]?.jsonPrimitive?.content.orEmpty()
        val proxyTags = all.filter {
            tag(it).startsWith("proxy-") && it["protocol"]?.jsonPrimitive?.content !in setOf("freedom", "blackhole", "dns")
        }.map { tag(it) }.toSet()
        require(tags.isNotEmpty() && proxyTags.containsAll(tags)) { "Выберите существующие прокси-серверы Xray" }
        val fallback = (proxyTags - tags).firstOrNull()
        require(fallback != null) { "Оставьте хотя бы один сервер Xray" }
        val remaining = all.filter { tag(it) !in tags }
        val remainingTags = remaining.map { tag(it) }
        remaining.forEach { outbound ->
            val proxy = outbound["proxySettings"]?.jsonObject?.get("tag")?.jsonPrimitive?.content
            val dialer = outbound["streamSettings"]?.jsonObject?.get("sockopt")?.jsonObject
                ?.get("dialerProxy")?.jsonPrimitive?.content
            require(proxy !in tags && dialer !in tags) {
                "${tag(outbound)} использует удаляемый сервер. Удалите зависимые серверы вместе"
            }
        }
        fun selectors(value: JsonElement?): List<String> = value?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            .filter { prefix -> remainingTags.any { it.startsWith(prefix) } }
        val routeConfig = Json.parseToJsonElement(configs.getValue(Paths.ROUTING)).jsonObject
        val routing = routeConfig["routing"]?.jsonObject ?: error("Не найдена секция routing")
        val removedBalancers = mutableSetOf<String>()
        val balancers = routing["balancers"]?.jsonArray?.mapNotNull { element ->
            val balancer = element.jsonObject
            val selected = selectors(balancer["selector"])
            if (selected.isEmpty()) {
                balancer["tag"]?.jsonPrimitive?.content?.let { removedBalancers += it }
                null
            } else JsonObject(balancer.toMutableMap().apply {
                put("selector", JsonArray(selected.map { JsonPrimitive(it) }))
                if (balancer["fallbackTag"]?.jsonPrimitive?.content in tags) put("fallbackTag", JsonPrimitive(fallback))
            })
        }
        val rules = routing["rules"]?.jsonArray?.map { element ->
            val rule = element.jsonObject
            if (rule["outboundTag"]?.jsonPrimitive?.content in tags ||
                rule["balancerTag"]?.jsonPrimitive?.content in removedBalancers) {
                JsonObject(rule.toMutableMap().apply {
                    remove("balancerTag")
                    put("outboundTag", JsonPrimitive(fallback))
                })
            } else rule
        }
        val updatedRouting = JsonObject(routing.toMutableMap().apply {
            if (balancers != null) put("balancers", JsonArray(balancers))
            if (rules != null) put("rules", JsonArray(rules))
        })
        val result = configs.toMutableMap()
        result[Paths.OUTBOUNDS] = json.encodeToString(JsonObject.serializer(), JsonObject(outConfig.toMutableMap().apply {
            put("outbounds", JsonArray(remaining))
        }))
        result[Paths.ROUTING] = json.encodeToString(JsonObject.serializer(), JsonObject(routeConfig.toMutableMap().apply {
            put("routing", updatedRouting)
        }))
        configs[Paths.OBSERVATORY]?.let { raw ->
            val config = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
            for (key in listOf("observatory", "burstObservatory")) {
                val observer = config[key]?.jsonObject ?: continue
                val selected = selectors(observer["subjectSelector"]).ifEmpty { (proxyTags - tags).toList() }
                config[key] = JsonObject(observer.toMutableMap().apply {
                    put("subjectSelector", JsonArray(selected.map { JsonPrimitive(it) }))
                })
            }
            result[Paths.OBSERVATORY] = json.encodeToString(JsonObject.serializer(), JsonObject(config))
        }
        return result
    }
}
