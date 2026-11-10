package com.xkeen.android.data.remote

import kotlinx.serialization.json.*

internal object XraySubscriptionRouting {
    fun withGeo(rules: List<JsonObject>): List<JsonObject> {
        val clean = rules.filterNot { it["balancerTag"]?.jsonPrimitive?.content == XraySubscriptionRemote.GEO_BALANCER }
        val direct = clean.filter { rule ->
            rule["outboundTag"]?.jsonPrimitive?.content == "direct" &&
                listOf("source", "domain", "ip").any { rule[it] is JsonArray } &&
                listOf("domain", "ip").flatMap { rule[it]?.jsonArray.orEmpty() }.none {
                    val value = it.jsonPrimitive.content
                    value.startsWith("geosite:") || value.startsWith("geoip:") || value.startsWith("ext:") ||
                        value in setOf("domain:ru", "domain:su", "domain:рф")
                }
        }
        val geo = buildJsonObject {
            put("type", "field")
            putJsonArray("domain") { SubscriptionNames.GEO_DOMAINS.forEach { add("domain:$it") } }
            put("balancerTag", XraySubscriptionRemote.GEO_BALANCER)
        }
        return direct + geo + clean.filterNot { it in direct }
    }
}
