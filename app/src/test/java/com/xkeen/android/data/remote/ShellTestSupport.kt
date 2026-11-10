package com.xkeen.android.data.remote

import java.util.concurrent.TimeUnit

/** Runs only a local fixture shell. Callers supply temporary paths and mock network commands. */
internal fun runTestShell(script: String, environment: Map<String, String> = emptyMap()): Pair<Int, String> {
    val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).apply {
        environment().putAll(environment)
    }.start()
    try {
        check(process.waitFor(15, TimeUnit.SECONDS)) { "Fixture shell timed out" }
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    } finally { process.destroyForcibly() }
}

internal fun scriptResource(name: String): String = requireNotNull(
    object {}.javaClass.getResourceAsStream("/$name")
).bufferedReader().use { it.readText() }
