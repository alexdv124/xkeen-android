#!/bin/sh
# XKeen Android: subscription maintenance for Mihomo.
# Managed by the XKeen Android app; edits are overwritten on the next install.
#
#   xk-sub.sh daily   refresh the subscription now and pick the best servers again
#   xk-sub.sh check   cheap health check (cron, every 30 min): re-pick only when needed
#   xk-sub.sh bench   pick the best servers now
#   xk-sub.sh geo     re-check which servers geo-blocking services (ElevenLabs, OpenAI, Anthropic) accept
#   xk-sub.sh status  print a JSON summary for the app
#
# Mihomo downloads and parses the subscription itself (proxy-provider "xk-sub").
# One bench uses roughly CANDIDATES * (DL_BYTES + UP_KB KiB) of traffic (~0.5 GB by default).
# This script measures real download/upload speed of the candidates through a
# loopback-only listener and writes the winners into the SUB-BEST group filter.

XK_DIR="/opt/etc/xkeen-android"
CONF="$XK_DIR/subscription.conf"
STATE="$XK_DIR/state"
RESULT="$XK_DIR/bench.json"
LOG="$XK_DIR/subscription.log"
LOCK="/tmp/xk-sub.lock"

PROVIDER="xk-sub"
BEST_GROUP="SUB-BEST"
BENCH_GROUP="SUB-BENCH"
GEO_GROUP="SUB-GEO"
GEO_RESULT="$XK_DIR/geo.json"

# Defaults, overridden by subscription.conf (written by the app).
TOP_N=10
CANDIDATES=20
MIN_ALIVE=3
BENCH_PORT=17899
DL_URL="https://fsn1-speed.hetzner.com/100MB.bin"
DL_BYTES=20000000
DL_TIME=6
UP_URL="https://speed.cloudflare.com/__up"
UP_KB=8192
UP_TIME=10
MIN_DOWN_MBIT=5
REBENCH_DAYS=7
# Geo-blocking services: servers in SUB-GEO must pass every check below ("URL|accepted codes").
# Redirects are not followed: ElevenLabs answers 302 -> geo-restriction.elevenlabs.io when it
# blocks an exit, the OpenAI/Anthropic APIs answer 403 instead of 401.
GEO_N=5
GEO_MAX_TESTS=30
GEO_CHECKS="https://elevenlabs.io/|200
https://api.openai.com/v1/models|401
https://api.anthropic.com/v1/models|401"
MIHOMO_CONFIG=""

[ -f "$CONF" ] && . "$CONF"

export PATH="/opt/sbin:/opt/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

log() {
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG"
    # keep the log small
    if [ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 200000 ]; then
        tail -n 400 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
    fi
}

die() { log "ERROR: $*"; echo "ERROR: $*" >&2; exit 1; }

find_config() {
    if [ -n "$MIHOMO_CONFIG" ] && [ -f "$MIHOMO_CONFIG" ]; then echo "$MIHOMO_CONFIG"; return; fi
    for f in /opt/etc/mihomo/config.yaml /opt/etc/mihomo/config.yml; do
        [ -f "$f" ] && { echo "$f"; return; }
    done
}

CFG="$(find_config)"
[ -n "$CFG" ] || die "mihomo config not found"
CFG_DIR="$(dirname "$CFG")"

# external-controller and secret from the live config
CTRL_PORT="$(yq -r '.["external-controller"] // "127.0.0.1:9090"' "$CFG" 2>/dev/null | sed 's/.*://')"
case "$CTRL_PORT" in ''|*[!0-9]*) CTRL_PORT=9090 ;; esac
SECRET="$(yq -r '.secret // ""' "$CFG" 2>/dev/null)"
[ "$SECRET" = "null" ] && SECRET=""
CTRL="127.0.0.1:$CTRL_PORT"

api() { # method path [json-body] [max-time]
    _m="$1"; _p="$2"; _d="$3"; _t="${4:-15}"
    if [ -n "$_d" ]; then
        if [ -n "$SECRET" ]; then
            curl -sS -m "$_t" -X "$_m" -H "Authorization: Bearer $SECRET" -H 'Content-Type: application/json' --data "$_d" "http://$CTRL$_p"
        else
            curl -sS -m "$_t" -X "$_m" -H 'Content-Type: application/json' --data "$_d" "http://$CTRL$_p"
        fi
    else
        if [ -n "$SECRET" ]; then
            curl -sS -m "$_t" -X "$_m" -H "Authorization: Bearer $SECRET" "http://$CTRL$_p"
        else
            curl -sS -m "$_t" -X "$_m" "http://$CTRL$_p"
        fi
    fi
}

api_code() { # method path [json-body] [max-time] -> prints HTTP status only
    _m="$1"; _p="$2"; _d="$3"; _t="${4:-15}"
    set -- -sS -o /dev/null -w '%{http_code}' -m "$_t" -X "$_m"
    [ -n "$SECRET" ] && set -- "$@" -H "Authorization: Bearer $SECRET"
    [ -n "$_d" ] && set -- "$@" -H 'Content-Type: application/json' --data "$_d"
    curl "$@" "http://$CTRL$_p"
}

mihomo_up() { [ -n "$(api GET /version "" 5 2>/dev/null)" ]; }

provider_file() {
    p="$(yq -r ".[\"proxy-providers\"][\"$PROVIDER\"].path // \"\"" "$CFG" 2>/dev/null)"
    [ -z "$p" ] || [ "$p" = "null" ] && p="./providers/$PROVIDER.yaml"
    case "$p" in /*) echo "$p" ;; *) echo "$CFG_DIR/${p#./}" ;; esac
}

provider_hash() {
    f="$(provider_file)"
    [ -f "$f" ] && sha256sum "$f" | cut -d' ' -f1
}

state_get() { [ -f "$STATE" ] && sed -n "s/^$1=//p" "$STATE" | tail -n1; }
state_set() {
    touch "$STATE"
    grep -v "^$1=" "$STATE" > "$STATE.tmp" 2>/dev/null
    printf '%s=%s\n' "$1" "$2" >> "$STATE.tmp"
    mv "$STATE.tmp" "$STATE"
}

lock() {
    if ! mkdir "$LOCK" 2>/dev/null; then
        old="$(cat "$LOCK/pid" 2>/dev/null)"
        if [ -n "$old" ] && { [ "$old" = "$$" ] || [ "$old" = "$PPID" ]; }; then
            : # reserved for this run by the app (it starts us in background)
        elif [ -z "$old" ] || ! kill -0 "$old" 2>/dev/null; then
            # stale lock left by a killed run
            rm -rf "$LOCK"; mkdir "$LOCK" 2>/dev/null || exit 0
        else
            echo "busy"; exit 0
        fi
    fi
    echo $$ > "$LOCK/pid"
    trap 'rm -rf "$LOCK"' EXIT INT TERM
}

json_name() { jq -cn --arg n "$1" '{name:$n}'; }

# Maps provider proxy names to server:port when the cached subscription can be read,
# so that several names pointing at one server are measured and chosen only once.
server_map() {
    f="$(provider_file)"
    [ -f "$f" ] || return 0
    if yq -e '.proxies' "$f" >/dev/null 2>&1; then
        yq -r '.proxies[] | [.name, (.server + ":" + (.port|tostring))] | @tsv' "$f" 2>/dev/null
        return 0
    fi
    # base64 list of share links (vless://, trojan://, ...)
    { base64 -d < "$f" 2>/dev/null || cat "$f"; } | tr -d '\r' | while IFS= read -r line; do
        case "$line" in *://*@*) ;; *) continue ;; esac
        hostport="${line#*@}"; hostport="${hostport%%[/?#]*}"
        name="${line#*#}"; [ "$name" = "$line" ] && continue
        name="$(printf '%b' "$(printf '%s' "$name" | sed 's/+/ /g; s/%\([0-9A-Fa-f][0-9A-Fa-f]\)/\\x\1/g')")"
        printf '%s\t%s\n' "$name" "$hostport"
    done
}

regex_escape() { printf '%s' "$1" | sed 's/[][\\.^$*+?(){}|\/]/\\&/g'; }

names_filter() { # newline-separated names on stdin -> ^(?:a|b)$
    f="$(while IFS= read -r n; do [ -n "$n" ] && { regex_escape "$n"; printf '\n'; }; done \
        | awk 'NF { s = s (s == "" ? "" : "|") $0 } END { print s }')"
    [ -n "$f" ] && printf '^(?:%s)$' "$f"
}

has_group() { yq -e "(.[\"proxy-groups\"][] | select(.name == \"$1\"))" "$CFG" >/dev/null 2>&1; }

set_filter() { set_group_filter "$BEST_GROUP" "$1"; }

set_group_filter() ( # subshell: config lock traps must not replace the benchmark's lock trap
    grp="$1"; want="$2"
    . "$XK_DIR/xk-config-lock.sh" || exit 1
    xk_config_lock || { log "config is busy, retry on the next check"; return 1; }
    if ! has_group "$grp"; then
        log "group $grp is not in the config (re-save the subscription settings in the app)"
        return 0
    fi
    cur="$(yq -r "(.[\"proxy-groups\"][] | select(.name == \"$grp\")).filter // \"\"" "$CFG" 2>/dev/null)"
    [ "$cur" = "null" ] && cur=""
    if [ "$cur" = "$want" ]; then log "$grp filter unchanged"; return 0; fi
    staged="$CFG.xk-sub.tmp"
    cp -p "$CFG" "$staged" || die "cannot stage config"
    if [ -n "$want" ]; then
        XK_FILTER="$want" yq -i "(.[\"proxy-groups\"][] | select(.name == \"$grp\")).filter = strenv(XK_FILTER)" "$staged"
    else
        yq -i "del(.[\"proxy-groups\"][] | select(.name == \"$grp\") | .filter)" "$staged"
    fi || { rm -f "$staged"; die "yq failed"; }
    if ! mihomo -t -d "$CFG_DIR" -f "$staged" >/dev/null 2>&1; then
        rm -f "$staged"; die "new config failed mihomo -t"
    fi
    cp -p "$CFG" "$CFG.xk-sub.bak" || { rm -f "$staged"; die "cannot back up config"; }
    restore_filter() {
        cp -p "$CFG.xk-sub.bak" "$staged.rollback" && mv "$staged.rollback" "$CFG" || return 1
        restored_code="$(api_code PUT '/configs?force=true' "$(jq -cn --arg p "$CFG" '{path:$p}')" 30)"
        case "$restored_code" in 2??) return 0 ;; *) return 1 ;; esac
    }
    trap 'restore_filter; exit 1' HUP INT TERM
    if ! mv "$staged" "$CFG"; then
        restore_filter
        die "cannot install config"
    fi
    code="$(api_code PUT '/configs?force=true' "$(jq -cn --arg p "$CFG" '{path:$p}')" 30)"
    case "$code" in
        2*) trap 'exit 1' HUP INT TERM; log "config reloaded with new $grp filter" ;;
        *)  if restore_filter; then
                die "reload failed (HTTP $code), previous config restored"
            else
                die "reload failed (HTTP $code), rollback unconfirmed; backup: $CFG.xk-sub.bak"
            fi ;;
    esac
)

bench() {
    mihomo_up || die "mihomo API is not reachable on $CTRL"
    log "bench: health check"
    api GET "/providers/proxies/$PROVIDER/healthcheck" "" 90 >/dev/null 2>&1
    list="$(api GET "/providers/proxies/$PROVIDER" "" 20)" || die "provider $PROVIDER not found"
    total="$(printf '%s' "$list" | jq '.proxies | length' 2>/dev/null)"
    [ -n "$total" ] && [ "$total" -gt 0 ] 2>/dev/null || die "subscription has no servers"

    servers="/tmp/xk-sub.servers.$$"
    server_map > "$servers" 2>/dev/null

    cand="/tmp/xk-sub.cand.$$"
    printf '%s' "$list" | jq -r '.proxies[]
        | select((.history | length) > 0 and (.history | last | .delay) > 0)
        | [(.history | last | .delay), .name] | @tsv' | sort -n > "$cand"
    alive="$(wc -l < "$cand" | tr -d ' ')"
    log "bench: $total servers, $alive alive"
    [ "$alive" -gt 0 ] || { rm -f "$cand" "$servers"; die "no alive servers in the subscription"; }

    res="/tmp/xk-sub.res.$$"; : > "$res"
    seen="/tmp/xk-sub.seen.$$"; : > "$seen"
    tested=0
    while IFS="$(printf '\t')" read -r delay name; do
        [ "$tested" -ge "$CANDIDATES" ] && break
        case "$name" in *'`'*) continue ;; esac
        srv="$(awk -F '\t' -v n="$name" '$1 == n { print $2; exit }' "$servers")"
        if [ -n "$srv" ]; then
            grep -qxF "$srv" "$seen" && continue
            echo "$srv" >> "$seen"
        fi
        code="$(api_code PUT "/proxies/$BENCH_GROUP" "$(json_name "$name")" 10)"
        case "$code" in 2*) ;; *) log "bench: cannot select $name (HTTP $code)"; continue ;; esac
        tested=$((tested + 1))
        down="$(curl -sk -o /dev/null -m "$DL_TIME" -r "0-$((DL_BYTES - 1))" \
            -x "socks5h://127.0.0.1:$BENCH_PORT" -w '%{speed_download}' "$DL_URL" 2>/dev/null)"
        up="$(dd if=/dev/zero bs=1024 count="$UP_KB" 2>/dev/null | curl -sk -o /dev/null -m "$UP_TIME" \
            -x "socks5h://127.0.0.1:$BENCH_PORT" --data-binary @- -w '%{speed_upload}' "$UP_URL" 2>/dev/null)"
        down_m="$(awk -v b="${down:-0}" 'BEGIN { printf "%.1f", b * 8 / 1000000 }')"
        up_m="$(awk -v b="${up:-0}" 'BEGIN { printf "%.1f", b * 8 / 1000000 }')"
        printf '%s\t%s\t%s\t%s\n' "$name" "$delay" "$down_m" "$up_m" >> "$res"
        log "bench: $name delay=${delay}ms down=${down_m} up=${up_m} Mbit/s"
    done < "$cand"
    rm -f "$cand" "$servers" "$seen"

    # score = download + 2 * upload (upload stalls hurt calls and file sending most);
    # servers below MIN_DOWN_MBIT are dropped. A slow upload that hits UP_TIME is capped at
    # UP_KB / UP_TIME (about 6.7 Mbit/s), so it can never look fast.
    ranked="/tmp/xk-sub.rank.$$"
    # BusyBox sort mis-orders "-t<TAB> -k5 -n", so sort on a leading score column instead.
    awk -F '\t' -v min="$MIN_DOWN_MBIT" '($3 + 0) >= min { printf "%.1f\t%s\t%s\t%s\t%s\t%.1f\n", $3 + 2 * $4, $1, $2, $3, $4, $3 + 2 * $4 }' "$res" \
        | sort -rn | cut -f2- > "$ranked"
    good="$(wc -l < "$ranked" | tr -d ' ')"

    if [ "$good" -lt 1 ]; then
        log "bench: no server reached ${MIN_DOWN_MBIT} Mbit/s, keeping the previous selection"
        write_result "$res" ""
        rm -f "$res" "$ranked"
        state_set last_bench "$(date +%s)"
        state_set last_error "no fast servers"
        return 1
    fi

    chosen="$(head -n "$TOP_N" "$ranked" | cut -f1)"
    filter="$(printf '%s\n' "$chosen" | names_filter)"

    set_filter "$filter" || die "cannot apply benchmark selection"
    write_result "$ranked" "$chosen"
    api_code PUT "/proxies/$BENCH_GROUP" "$(json_name "$(printf '%s\n' "$chosen" | head -n1)")" 10 >/dev/null
    state_set last_bench "$(date +%s)"
    state_set provider_hash "$(provider_hash)"
    state_set last_error ""
    log "bench: chose $(printf '%s\n' "$chosen" | wc -l | tr -d ' ') servers"
    rm -f "$res" "$ranked"
    geo
}

geo_ok() { # checks the server currently selected in the bench group; prints the failure
    while IFS='|' read -r url want; do
        [ -z "$url" ] && continue
        code="$(curl -sk -m 8 -o /dev/null -w '%{http_code}' -A 'Mozilla/5.0' \
            -x "socks5h://127.0.0.1:$BENCH_PORT" "$url" 2>/dev/null)"
        case " $want " in *" $code "*) ;; *) echo "$url -> $code"; return 1 ;; esac
    done <<XK_GEO_CHECKS
$GEO_CHECKS
XK_GEO_CHECKS
    return 0
}

# Picks GEO_N servers that geo-blocking services accept, fastest by the last bench first.
geo() {
    mihomo_up || { log "geo: mihomo is not reachable"; return 1; }
    has_group "$GEO_GROUP" || { log "geo: $GEO_GROUP is not in the config yet (re-save the subscription settings in the app)"; return 0; }
    alive="/tmp/xk-geo.alive.$$"; order="/tmp/xk-geo.order.$$"; rows="/tmp/xk-geo.rows.$$"
    servers="/tmp/xk-geo.servers.$$"; seen="/tmp/xk-geo.seen.$$"; : > "$seen"
    server_map > "$servers" 2>/dev/null
    api GET "/providers/proxies/$PROVIDER" "" 20 | jq -r '.proxies[]
        | select((.history | length) > 0 and (.history | last | .delay) > 0)
        | [(.history | last | .delay), .name] | @tsv' | sort -n | cut -f2- > "$alive"
    { [ -s "$RESULT" ] && jq -r '.servers | sort_by(-(.down + 2 * .up)) | .[].name' "$RESULT" 2>/dev/null
      cat "$alive"; } | awk '!seen[$0]++' > "$order"
    : > "$rows"; found=0; tested=0
    while IFS= read -r name; do
        { [ "$found" -ge "$GEO_N" ] || [ "$tested" -ge "$GEO_MAX_TESTS" ]; } && break
        grep -qxF "$name" "$alive" || continue
        case "$name" in *'`'*) continue ;; esac
        # Providers list one server under several names; keep each server once.
        srv="$(awk -F '\t' -v n="$name" '$1 == n { print $2; exit }' "$servers")"
        if [ -n "$srv" ]; then
            grep -qxF "$srv" "$seen" && continue
            echo "$srv" >> "$seen"
        fi
        code="$(api_code PUT "/proxies/$BENCH_GROUP" "$(json_name "$name")" 10)"
        case "$code" in 2*) ;; *) continue ;; esac
        tested=$((tested + 1))
        if why="$(geo_ok)"; then
            found=$((found + 1)); printf '%s\t1\t\n' "$name" >> "$rows"; log "geo: ok $name"
        else
            printf '%s\t0\t%s\n' "$name" "$why" >> "$rows"; log "geo: blocked $name ($why)"
        fi
    done < "$order"
    jq -R -s --arg ts "$(date +%s)" '{ ts: ($ts | tonumber), servers: (split("\n") | map(select(length > 0)
        | split("\t") | { name: .[0], ok: (.[1] == "1"), reason: (.[2] // "") })) }' "$rows" > "$GEO_RESULT.tmp" \
        && mv "$GEO_RESULT.tmp" "$GEO_RESULT"
    if [ "$found" -gt 0 ]; then
        set_group_filter "$GEO_GROUP" "$(awk -F '\t' '$2 == "1" { print $1 }' "$rows" | names_filter)" || die "cannot apply geo selection"
        state_set last_geo "$(date +%s)"
        log "geo: $found servers accepted by all services"
    else
        log "geo: no server passed the checks, keeping the previous $GEO_GROUP selection"
    fi
    rm -f "$alive" "$order" "$rows" "$servers" "$seen"
}

geo_alive() {
    members="$(api GET "/proxies/$GEO_GROUP" "" 10 | jq -r '.all[]?' 2>/dev/null)"
    [ -z "$members" ] && { echo 0; return; }
    api GET "/providers/proxies/$PROVIDER" "" 20 | jq --arg m "$members" '
        ($m | split("\n")) as $names
        | [.proxies[] | select(.name as $n | $names | index($n) != null) | select(.alive == true)] | length' 2>/dev/null || echo 0
}

write_result() { # file-with-rows chosen-names
    rows="$1"; chosen="$2"
    chosen_file="/tmp/xk-sub.chosen.$$"; printf '%s\n' "$chosen" > "$chosen_file"
    awk -F '\t' '{ print }' "$rows" | jq -R -s --arg ts "$(date +%s)" --rawfile chosen "$chosen_file" '
        ($chosen | split("\n") | map(select(length > 0))) as $c
        | { ts: ($ts | tonumber),
            servers: (split("\n") | map(select(length > 0) | split("\t")
              | { name: .[0], delay: (.[1] | tonumber), down: (.[2] | tonumber), up: (.[3] | tonumber),
                  chosen: (.[0] as $n | $c | index($n) != null) })) }' > "$RESULT.tmp" \
        && mv "$RESULT.tmp" "$RESULT"
    rm -f "$chosen_file"
}

best_alive() {
    members="$(api GET "/proxies/$BEST_GROUP" "" 10 | jq -r '.all[]?' 2>/dev/null)"
    [ -z "$members" ] && { echo 0; return; }
    api GET "/providers/proxies/$PROVIDER" "" 20 | jq --arg m "$members" '
        ($m | split("\n")) as $names
        | [.proxies[] | select(.name as $n | $names | index($n) != null)
          | select(.alive == true)] | length' 2>/dev/null || echo 0
}

refresh_provider() {
    code="$(api_code PUT "/providers/proxies/$PROVIDER" "" 60)"
    case "$code" in
        2*) log "subscription refreshed" ;;
        *)  log "subscription refresh failed (HTTP $code), using the cached copy" ;;
    esac
}

cmd_daily() {
    has_provider || { log "subscription is not in the Mihomo config, skipping"; exit 0; }
    lock
    mihomo_up || die "mihomo is not running"
    refresh_provider
    state_set last_update "$(date +%s)"
    bench
}

has_provider() { yq -e ".[\"proxy-providers\"][\"$PROVIDER\"]" "$CFG" >/dev/null 2>&1; }

cmd_check() {
    [ -f "$CONF" ] || exit 0
    # Config replaced without the subscription (e.g. a restored backup): nothing to maintain.
    has_provider || exit 0
    mihomo_up || exit 0
    lock
    reason=""
    h="$(provider_hash)"
    [ -n "$h" ] && [ "$h" != "$(state_get provider_hash)" ] && reason="subscription changed"
    if [ -z "$reason" ]; then
        a="$(best_alive)"
        required="$MIN_ALIVE"
        [ "$TOP_N" -lt "$required" ] && required="$TOP_N"
        [ "${a:-0}" -lt "$required" ] && reason="only ${a:-0} chosen servers alive"
    fi
    if [ -z "$reason" ]; then
        last="$(state_get last_bench)"
        now="$(date +%s)"
        [ -z "$last" ] || [ $((now - ${last:-0})) -gt $((REBENCH_DAYS * 86400)) ] && reason="periodic re-check"
    fi
    if [ -z "$reason" ]; then
        # Geo group: re-check (no speed test) when it was never checked or nothing in it answers.
        if has_group "$GEO_GROUP" && { [ -z "$(state_get last_geo)" ] || [ "$(geo_alive)" -lt 1 ]; }; then
            log "check: geo servers need a re-check"
            geo
        fi
        exit 0
    fi
    log "check: $reason"
    bench
}

cmd_status() {
    running=false
    pid="$(cat "$LOCK/pid" 2>/dev/null)"
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && running=true
    jq -n \
        --argjson running "$running" \
        --arg last_update "$(state_get last_update)" \
        --arg last_bench "$(state_get last_bench)" \
        --arg last_error "$(state_get last_error)" \
        --arg log "$(tail -n 15 "$LOG" 2>/dev/null)" \
        --slurpfile result "$( [ -s "$RESULT" ] && echo "$RESULT" || echo /dev/null )" \
        --slurpfile geo "$( [ -s "$GEO_RESULT" ] && echo "$GEO_RESULT" || echo /dev/null )" \
        '{ running: $running, last_update: $last_update, last_bench: $last_bench,
           last_error: $last_error, log: $log, result: ($result[0] // null), geo: ($geo[0] // null) }'
}

mkdir -p "$XK_DIR"
case "$1" in
    daily) cmd_daily ;;
    check) cmd_check ;;
    bench) lock; bench ;;
    geo) has_provider || exit 0; lock; geo ;;
    status) cmd_status ;;
    *) echo "usage: $0 daily|check|bench|geo|status" >&2; exit 2 ;;
esac
