# Shared by the Android config transaction and xk-sub.sh. The owner is a shell
# process that stays alive until install/reload/rollback finishes, not an SSH client PID.
XK_CONFIG_LOCK="${XK_CONFIG_LOCK:-/tmp/xkeen-mihomo-config.lock}"

xk_config_unlock() {
    [ "$(cat "$XK_CONFIG_LOCK/pid" 2>/dev/null)" = "$$" ] || return 0
    rm -f "$XK_CONFIG_LOCK/pid"
    rmdir "$XK_CONFIG_LOCK" 2>/dev/null || true
}

xk_config_lock() {
    if ! mkdir "$XK_CONFIG_LOCK" 2>/dev/null; then
        # Recover a dead owner, serializing recovery too. Never steal a lock whose
        # owner has not finished publishing its PID or is still alive.
        old="$(cat "$XK_CONFIG_LOCK/pid" 2>/dev/null)"
        case "$old" in ''|*[!0-9]*) return 1 ;; esac
        kill -0 "$old" 2>/dev/null && return 1
        mkdir "$XK_CONFIG_LOCK/recover" 2>/dev/null || return 1
        current="$(cat "$XK_CONFIG_LOCK/pid" 2>/dev/null)"
        if [ "$current" != "$old" ] || kill -0 "$current" 2>/dev/null; then
            rmdir "$XK_CONFIG_LOCK/recover" 2>/dev/null
            return 1
        fi
        rm -f "$XK_CONFIG_LOCK/pid"
        rmdir "$XK_CONFIG_LOCK/recover" "$XK_CONFIG_LOCK" 2>/dev/null || return 1
        mkdir "$XK_CONFIG_LOCK" 2>/dev/null || return 1
    fi
    echo $$ > "$XK_CONFIG_LOCK/pid" || { rmdir "$XK_CONFIG_LOCK" 2>/dev/null; return 1; }
    trap 'xk_config_unlock' EXIT
    trap 'exit 1' HUP INT TERM
}
