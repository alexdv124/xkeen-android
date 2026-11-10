package com.xkeen.android.data.ssh

enum class RemoteFileReadFailure { MISSING, DENIED, TIMEOUT, COMMAND_FAILED }

class RemoteFileReadException(val path: String, val reason: RemoteFileReadFailure) :
    IllegalStateException("Не удалось прочитать $path: " + when (reason) {
        RemoteFileReadFailure.MISSING -> "файл не найден. Проверьте расположение конфигурации на роутере"
        RemoteFileReadFailure.DENIED -> "нет прав доступа. Проверьте пользователя SSH"
        RemoteFileReadFailure.TIMEOUT -> "команда не завершилась или соединение прервалось. Повторите загрузку"
        RemoteFileReadFailure.COMMAND_FAILED -> "ошибка команды чтения. Проверьте SSH-доступ к файлам Entware"
    })

/** Never feed an empty/partial stdout from a failed command into a config parser. */
internal fun checkedFileContents(path: String, result: SshResult): String {
    if (result.exitCode == 0) return result.stdout
    val reason = when {
        result.exitCode < 0 -> RemoteFileReadFailure.TIMEOUT
        result.stderr.contains("No such file or directory", ignoreCase = true) -> RemoteFileReadFailure.MISSING
        result.stderr.contains("Permission denied", ignoreCase = true) -> RemoteFileReadFailure.DENIED
        else -> RemoteFileReadFailure.COMMAND_FAILED
    }
    // Raw command output can contain credentials/configuration; it is not a user-facing error.
    throw RemoteFileReadException(path, reason)
}
