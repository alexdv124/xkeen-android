package com.xkeen.android.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Local Xray configs accept //, block and # comments outside JSON strings. */
internal object XrayJson {
    fun parseObject(raw: String): JsonObject = Json.parseToJsonElement(withoutComments(raw)).jsonObject

    fun withoutComments(raw: String): String {
        val result = raw.toCharArray()
        var index = 0
        var inString = false
        fun blank(position: Int) {
            if (raw[position] != '\n' && raw[position] != '\r') result[position] = ' '
        }
        while (index < raw.length) {
            val current = raw[index]
            if (inString) {
                when (current) {
                    '\\' -> index++ // Leave both the escape and its following character untouched.
                    '"' -> inString = false
                }
                index++
                continue
            }
            val next = raw.getOrNull(index + 1)
            when {
                current == '"' -> { inString = true; index++ }
                current == '#' || (current == '/' && next == '/') -> {
                    while (index < raw.length && raw[index] != '\n' && raw[index] != '\r') {
                        blank(index++)
                    }
                }
                current == '/' && next == '*' -> {
                    blank(index++)
                    blank(index++)
                    var closed = false
                    while (index < raw.length) {
                        if (raw[index] == '*' && raw.getOrNull(index + 1) == '/') {
                            blank(index++)
                            blank(index++)
                            closed = true
                            break
                        }
                        blank(index++)
                    }
                    require(closed) { "Незакрытый комментарий в конфигурации Xray" }
                }
                else -> index++
            }
        }
        return String(result)
    }
}
