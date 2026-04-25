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
2. Source-based routes (LAN device IPs → proxy/direct)
3. Custom IP routes → proxy (e.g. Aqara IPs) — BEFORE geoip:ru
4. Custom domain routes → proxy (e.g. youtube.com) — BEFORE geosite:ru
5. RU domains (geosite) → direct
6. RU IPs (geoip) → direct
7. BitTorrent → direct
8. Custom IP routes → direct
9. Custom domain routes → direct
10. Catch-all → balancer or specific proxy

### Standard inbounds config (03_inbounds.json)
Port 61219 is the xkeen standard. Two inbounds: redirect (TCP) + tproxy (UDP), both dokodemo-door with sniffing enabled.

### Domain-based custom routing
Users can add specific domains (e.g. `youtube.com`) and force them through VPN or direct. In `applyPreset()`, domain proxy routes use `"domain":["domain:example.com"]` and are placed BEFORE geosite:ru rules. Domain direct routes are placed BEFORE the catch-all rule. The `getRoutingConfig()` parser reads domain arrays back, filtering out preset domains (ext:geosite, domain:ru/su/рф) and stripping the `domain:` prefix. `AddCustomRouteDialog` defaults to domain mode and auto-cleans input (strips `https://`, paths, lowercased). `CustomRoute.routeType` = `"domain"` (alongside existing `"ip"` and `"source"`).

### Source-based routing (LAN devices)
Tapping a device in the network list (ARP scan) opens a dialog to route all its traffic through VPN or direct. Uses Xray's `"source"` field in routing rules, placed before geoip/geosite rules so device traffic is captured regardless of destination. `CustomRoute.routeType` = `"source"`.

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

### Bugs fixed (v0.0.7+)
- Manual routing mode chip now correctly switches to first proxy on click
- Progress indicators added to all proxy/routing operations (no more frozen UI)
- Proxy status no longer lost after add/delete — delayed refresh (3s) after xray restart
- Custom IP proxy routes now check for balancer existence instead of hardcoding `balancerTag`

### Bugs fixed (v1.2.4)
- `VlessParser`: now parses the `fm=` query parameter (TLS-fragmentation settings, e.g. `{"fragment":{"length":"50-100","packets":"tlshello","interval":"10-20"}}`). When present, the proxy outbound gets `streamSettings.sockopt.dialerProxy="fragment"` and `XrayConfigRemote.addOutbound`/`initialSetup` ensure a shared `fragment` freedom outbound exists in `04_outbounds.json`. Without this, providers that rely on TLS-hello fragmentation to bypass DPI (e.g. `e0f.network`) silently fail on the router even though the same link works in mobile clients that honour `fm=`.
- `ParsedVless` gained `fragmentSettings: Map<String, Any?>?` so callers can propagate the parsed fragment block to the JSON writer.

### Bugs fixed (v1.2.5)
- `VlessParser.buildXhttpOutbound`: stopped hardcoding `streamSettings.xhttpSettings.mode = "packet-up"`. Now reads `mode=` from the link (e.g. `auto`, `stream-one`, `stream-up`) and falls back to `packet-up` only if absent. Some providers (e.g. `api.e0f.host`) require `mode=auto`; hardcoding `packet-up` made the tunnel handshake but pass zero data — same symptom as before, but for a different reason than fm-fragmentation.

### Bugs fixed (v1.2.6)
- `VlessParser.buildXhttpOutbound`: stopped emitting `downloadSettings: {}` when the link did not carry an `extra=` block. An empty `downloadSettings` object **panics xray-core 26.x** in `transport/internet/splithttp/dialer.go:413` the moment a connection is dialled — the inbound socks5 listener accepts, the dispatcher takes the detour, then the worker goroutine crashes silently (only visible at `loglevel: debug`). Now we only put `downloadSettings` if the link actually has it. Bug surfaced on `e0f.network` links; gofizz links happened to provide non-empty `extra.downloadSettings` so they sidestepped it.

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
