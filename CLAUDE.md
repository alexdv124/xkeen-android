# XKeen Android — Project Context

## What this is
Android app for managing xkeen (Xray proxy) on Keenetic routers via SSH.
No intermediate server — the app connects directly to the router over WiFi.

## Architecture
MVVM + Clean Architecture. Kotlin, Jetpack Compose, Material Design 3.

### Data layer
- `data/ssh/SshClient.kt` — JSch SSH wrapper. Mirrors the Python `backend/ssh_client.py` from the original web panel. Key methods: `exec()`, `readFile()`, `writeFileB64()` (chunked base64 for large files).
- `data/remote/RouterCommands.kt` — Shell commands executed on the router. Status parsing (ps, free, top), observatory state from logs, config test with `XRAY_LOCATION_ASSET` env, xkeen restart, diagnostics, backup/restore, network devices (ARP).
- `data/remote/XrayConfigRemote.kt` — Reads/writes Xray JSON configs via SSH. Manages outbounds, routing rules, balancer, observatory. Contains routing presets (RU_DIRECT, ALL_VPN, ALL_DIRECT), custom routes, QUIC toggle, Aqara IoT preset, initial setup wizard.
- `data/remote/VlessParser.kt` — Parses VLESS:// URIs into Xray outbound JSON. Supports both Vision (TCP) and XHTTP packet-up transports. Contains `intsFromWholeFloats()` fix for Xray's strict int64 parsing.
- `data/local/` — Room database for router profiles (alias, host, user, password, port).

### Domain layer
- `domain/model/Models.kt` — All data classes: RouterProfile, RouterStatus, ProxyInfo, RoutingMode, RoutingPreset, CustomRoute, ConfigState, SetupState, etc.

### UI layer (Jetpack Compose)
- `ui/dashboard/` — Status overview: xray state, CPU/RAM bars, external IP, server health with color dots, diagnostics dialog, initial setup wizard.
- `ui/proxies/` — Proxy list with add (single/bulk VLESS import), delete, auto-failover setup dialog when adding 2nd server.
- `ui/routing/` — Three preset cards, QUIC toggle, balancer mode (auto/manual), server checkboxes, custom routes with Aqara preset, IoT device list from ARP, geosite update button.
- `ui/logs/` — Access/error log viewer with tabs, monospace text.
- `ui/settings/` — Router profile CRUD, backup/restore, config copy between routers.

## Key paths on router (Keenetic + xkeen)
```
/opt/etc/xray/configs/     — Xray JSON configs (01_log through 07_observatory)
/opt/etc/xray/dat/         — geosite/geoip .dat files
/opt/var/log/xray/         — access.log, error.log
/opt/etc/init.d/S99xkeen   — xkeen init script
/opt/etc/xkeen/            — ip_exclude.lst, port_exclude.lst
```

## Critical implementation details

### Config test must include env vars
```
XRAY_LOCATION_ASSET=/opt/etc/xray/dat XRAY_LOCATION_CONFDIR=/opt/etc/xray/configs xray run -test -confdir /opt/etc/xray/configs/
```
Without `XRAY_LOCATION_ASSET`, xray looks for .dat files in `/opt/sbin/` (default) and fails.

### VLESS parser float fix
VLESS links from some providers contain `"hKeepAlivePeriod":0.0` — JSON float. Xray expects int64. The `intsFromWholeFloats()` function recursively converts whole floats to ints.

### Routing rule order matters
1. UDP 135,137-139,443 → block (QUIC + NetBIOS)
2. Custom proxy routes (e.g. Aqara IPs) — BEFORE geoip:ru
3. RU domains (geosite) → direct
4. RU IPs (geoip) → direct
5. BitTorrent → direct
6. Catch-all → balancer or specific proxy

### Standard inbounds config (03_inbounds.json)
Port 61219 is the xkeen standard. Two inbounds: redirect (TCP) + tproxy (UDP), both dokodemo-door with sniffing enabled.

### Aqara IoT workaround
ТСПУ (Russian DPI) blocks direct traffic to Kingsoft Cloud (Aqara's backend). IPs `107.155.52.0/23` and `169.197.117.0/24` must be routed through proxy BEFORE the geoip:ru→direct rule (since these IPs are in Russia).

### Initial setup wizard (fresh xkeen install)
`XrayConfigRemote.initialSetup()` generates configs from scratch:
- `01_log.json` — enables logging (access + error, level warning). Default xkeen install has logging disabled, but the app needs logs for observatory and diagnostics.
- `03_inbounds.json` — standard redirect (TCP) + tproxy (UDP) on port 61219
- `04_outbounds.json` — parsed from VLESS links + direct + block
- `05_routing.json` — standard preset with QUIC block, geosite/geoip RU → direct, catch-all → proxy/balancer
- `07_observatory.json` — burstObservatory with leastping (only if 2+ servers)
- Files 02_dns.json and 06_policy.json are NOT touched — xkeen creates them during installation.

### Auto-failover setup
When user adds 2nd proxy to a single-server config (no balancer), `ProxiesScreen` shows a dialog offering to enable failover. `XrayConfigRemote.enableFailover()` creates the balancer, observatory, and updates routing's catch-all rule from `outboundTag` to `balancerTag`.

### Diagnostics
`RouterCommands.runDiagnostics()` checks: xray process, config test, proxy health (from actual outbounds config, not just logs), DNS resolution, DNS hijacking detection (Instagram CDN → known Russian ISP ranges = DPI), internet connectivity, geosite/geoip file count.

### Bugs fixed (v0.0.4)
- `SshClient.exec()`: replaced `session!!` with safe access; `Thread.sleep(100)` polling loop → `delay(100)` (was blocking IO thread)
- `RouterCommands`: `now!!` NPE on date parse → safe fallback; `Thread.sleep(4000)` → `delay(4000)`
- `VlessParser.parse()`: added validation for missing `@`, missing `:port`, non-numeric port; fixed double-escaped regex `\\.` → `\.`
- `XrayConfigRemote.getRoutingMode()`: `!!.jsonPrimitive` → safe access with fallback
- `ProxiesScreen`: `outbound["tag"]!!` → safe access; bulk import was concurrent (race condition on config files) → now sequential
- `MainActivity`: `activeProfile!!.alias` → smart cast

### ps output format (busybox on Keenetic)
```
PID   USER     VSZ   STAT  COMMAND
10627 root     1330m S     xray run
```
Indices: [0]=PID, [1]=user, [2]=memory(VSZ), [3]=state, [4+]=command. Memory is at index 2, NOT 3.

## Building
```bash
bash setup-build.sh   # Downloads JDK 17 + Android SDK (~1.5 GB)
export JAVA_HOME="build-tools/jdk-17"
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
Or open in Android Studio — it handles everything automatically.

## Companion project
The original web panel (Python/Bottle) is at `C:\Users\admin\Desktop\panel`. The Android app replicates its backend logic in Kotlin. See `panel/HISTORY_of_xkeen.md` for full research history across 5 sessions.
