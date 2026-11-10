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

### Router-owned Xray subscriptions (v1.9.0)
`XraySubscriptionRemote` installs the packaged `xk-xray-sub.py` into
`/opt/etc/xkeen-android/xray-subscription/subscription.py` only on connect/save. It checks curl
and S05crond and installs Entware python3 if stdlib dependencies are unavailable. The phone
submits a private JSON request and launches a detached Python child with an inherited flock;
closing SSH does not cancel the job. Cron calls the same job for daily and half-hourly checks.
Jobs refuse to apply when XKeen is not configured for running Xray. Local files are private,
subscription URLs are passed through a private curl config rather than shell interpolation.

The parser accepts VLESS lists or Base64, TCP/WS/gRPC/XHTTP with TLS/REALITY. It rejects unsupported
formats or bad entries as a whole. Probe uses a temporary loopback HTTP listener per outbound
in a separate Xray process; curl traverses the actual node for latency, down/up speed and GEO
HTTP-code checks. Linux parent-death signaling prevents orphan probes after a worker dies.
Score is down + 2*up, minimum 5 Mbit/s download, max(topN*2,15) candidates; select topN.
GEO checks examine up to 30 candidates for up to 5 passing nodes, using the same endpoints and
domains as Mihomo. Previously verified GEO nodes remain if no new ones pass. A healthy top1/2
pool does not re-benchmark every 30 minutes. Changed source or a depleted pool triggers a bench.

Owned servers and GEO balancer live in `08_xkeen_subscription.json`. Initial setup also updates
05 routing and 07 observatory to use the subscription pool, preserving manual 04 outbounds.
Later jobs replace only 08. Native leastPing chooses between best-speed candidates and between
GEO-verified candidates. `XraySubscriptionRouting` keeps GEO on preset changes, except ALL_DIRECT,
and explicit direct exceptions win. The Mihomo rule converter ignores Xray-owned GEO rules.
Managed nodes are visible by provider names, but manual deletion/balancer selection excludes them.
Disabling stops cron and retains the last valid config (deliberately different from Mihomo).

The job snapshots all JSON files, validates staging with real Xray, compares snapshots again,
backs up and rolls back on commit/restart failures. UI writes use the same flock plus a SHA-256
compare. Manual server deletion recognizes external managed tags and delegates its commit to
the same locked job when installed. Five recent config backups are retained. See Python tests
for process detachment, rollback, partial writes, cron, status, parser and measurements.
Gradle runs Python tests before Kotlin tests; release CI downloads pinned Xray 26.11.9 to run
real transport tests over a local VLESS server. A native-only fixture explicitly allows localhost
on its server because current Xray freedom blocks loopback targets by default; production rules
are unaffected. No live user router is changed by development/build verification.

### Config comments and core detection (v1.8.3)
`XrayJson` reads local Xray JSON with //, /* */ and # comments outside strings, preserving
string escapes and line positions. It is shared by configuration reads, server deletion,
diagnostics and Xray-to-Mihomo conversion; API responses and VLESS payloads remain strict JSON.
Malformed JSON and unclosed block comments still fail. Reads never rewrite remote files.
`RouterCommands.getCoreState` gives a sole running process priority over status text, then
uses the configured XKeen client if both/neither run, then an unambiguous core name in status.
Non-numeric process output is not a PID. The server screen no longer treats UNKNOWN as XRAY.
The third-party user reports Mihomo, while their screenshot labels the process Xray and lists
Mihomo in updates. The exact source of that discrepancy is unconfirmed without router output;
these changes are covered by local fixtures, not a claim of diagnosis on that router.

### Empty Xray JSON / optional observatory (v1.8.2)
`SshClient.readFile` checks the command result through `checkedFileContents`; missing files,
permission failures and incomplete commands raise typed `RemoteFileReadException`s rather than
passing an empty/partial stdout to a JSON parser. `XrayConfigRemote.readConfig` reports the path
for empty/invalid required JSON without displaying raw config contents. Only the optional
`07_observatory.json` accepts an empty file or a typed MISSING failure. Permission errors and
timeouts remain errors, and loading the server list never creates or rewrites router files.
`ProxiesScreen` offers a retry and hides the add action while loading has failed.
The empty observatory fixture reproduced the reported 1.8.0 screenshot before the fix; the
specific third-party router's file state was not inspected. SSH host-key policy is unchanged.

### Config safety and routing fixes (v1.8.1)
`MihomoConfigTransaction` performs the compare/install/reload/rollback in a single remote shell.
It uses the same PID-owned `/tmp/xkeen-mihomo-config.lock` as `xk-sub.sh`, via the shared
`xk-config-lock.sh` Java resource. The shell stays alive until the transaction finishes; the
phone does not reserve a lock with a short-lived exec PID. Busy transactions fail without writes,
and a SHA-256 comparison under the lock rejects stale app snapshots. Dead owners can be recovered.
Imports, deletion, routing, subscription changes, full config generation and Mihomo backup restore
all use this transaction. The benchmark selector is not restored by unrelated app operations.

`SubscriptionRemote.installScript` installs both shell resources. After installing this app build,
save subscription settings once to update the router-side scripts; an already running old script
must finish before relying on the shared lock. No router migration happens merely by building the APK.

Explicit direct source/domain/IP routes precede the GEO defaults, while broad country defaults
follow them. ALL_DIRECT omits GEO rules, and switching back reconstructs them from the subscription
groups. The benchmark listener rule stays first. `MIN_ALIVE` is capped at `TOP_N` so choosing one or
two servers does not trigger perpetual re-benchmarking. Removing the last cron entries handles an
empty result. Updates require a successful updater exit and positive version evidence; failed or
missing version checks cannot prove that an installed version is current.

Regression tests execute the actual shell transaction in temporary local directories with mocked
network commands. Tests cover concurrent edits, stale snapshots, failed reloads/rollback/backups,
first config creation, direct routing priority, small TOP_N, empty cron and failed updates.
SSH host-key checking and Mihomo `no-resolve` behavior are deliberately unchanged.

### Direct Mihomo server import
`ProxiesScreen` accepts one or several VLESS links while Mihomo is active. The user selects
target groups (PROXY/AUTO by default). `MihomoConfigBuilder` shares the existing TCP/XHTTP
converter with Xray-to-Mihomo generation; `MihomoConfigEditor` uses SnakeYAML's SafeConstructor
to add proxies and group members while retaining the rest of the current YAML. Automatically
generated names get numeric suffixes on collision; explicit duplicate names are rejected.
Unsupported transports and Xray `fm` fragmentation are rejected before any writes.

`MihomoConfigRemote.addVless` imports the entire batch in one serialized transaction: stage
beside the discovered config, run `mihomo -t`, save a unique `.bak`, atomically install and
reload through the existing controller API, restore selector choices. A failed or uncertain
install/reload restores the original file and reloads it. The transaction completes or rolls
back even if the screen is closed after installation starts. It never reads or writes Xray
config files. `SshConnection` permits deployment failure tests without a router.

In Mihomo mode `RouterCommands.restartXkeen` now updates **only rules** from the routing UI,
preserving native Mihomo proxies/groups/settings. Explicit generation from Xray remains a
full replacement. Unit tests cover batch import, preservation, collisions, converter options,
preflight failures, failed validation, uncertain writes, reload rollback and concurrent edits.

### Subscription mode (Mihomo, v1.7.0)
Router-side and phone-independent. `SubscriptionRemote.enable()` installs `app/src/main/resources/xk-sub.sh`
(shipped as a Java resource, loaded with `getResourceAsStream`, so unit tests see it too) to
`/opt/etc/xkeen-android/xk-sub.sh`, writes `subscription.conf` (TOP_N, CANDIDATES, DAILY_TIME,
MIHOMO_CONFIG) and applies `MihomoConfigEditor.enableSubscription` through the usual staged
validate/backup/reload transaction. The editor adds:
- `proxy-providers.xk-sub` (type http, `./providers/xk-sub.yaml`, health-check) — Mihomo downloads and parses it;
- `SUB-BEST` (url-test, `use: [xk-sub]`, `filter` written by the script), `SUB-ALL` (url-test over everything),
  hidden `SUB-BENCH` (select) — all three named in `SubscriptionNames`;
- loopback listener `xk-bench` (mixed, 127.0.0.1:17899) and rule `IN-NAME,xk-bench,SUB-BENCH`, which must stay
  **first** — `replaceRules` preserves it, otherwise the benchmark measures whatever PROXY selects.
- PROXY gets `SUB-BEST, SUB-ALL` prepended (created if missing).

Cron (`/opt/var/spool/cron/crontabs/root`, crond restarted after edits):
`M H * * * xk-sub.sh daily` (refresh provider via API + bench) and `*/30 * * * * xk-sub.sh check` (re-bench only when
the provider file hash changed, fewer than 3 SUB-BEST members are alive, or the last bench is older than 7 days).

Bench: provider health check → alive servers sorted by delay → top CANDIDATES (deduplicated by server:port when the
cached provider is clash YAML or a base64 link list) → for each, select it in SUB-BENCH via API and measure download
(Hetzner, ranged 20 MB, 6 s) and upload (Cloudflare `__up`, 8 MiB, 10 s) through the listener → score = down + 2·up,
drop < 5 Mbit/s down → filter `^(?:name1|name2…)$` (regex-escaped) → yq edit of a staged copy → `mihomo -t` → reload.
Results in `bench.json`, state in `state`, log in `subscription.log`; `xk-sub.sh status` prints JSON for the app.

Router quirks this relies on / works around: BusyBox `sort -t<TAB> -kN -n` mis-orders (sort on a leading key column);
Entware jq has no regex (`test()` unavailable — use grep); the app starts runs as `(sleep; exec script) & echo $! > lock/pid`
so the lock shows "running" at once and the script accepts a lock holding its own PID.

### Geo-blocked services (v1.8.0)
`SUB-GEO` (url-test over the provider, `filter` written by `xk-sub.sh geo`) carries
`SubscriptionNames.GEO_DOMAINS` (ElevenLabs, OpenAI/ChatGPT, Anthropic/Claude) via
`DOMAIN-SUFFIX,…,SUB-GEO` rules placed after the bench rule and explicit direct overrides, before broad
country defaults. ALL_DIRECT omits these GEO rules; other presets reconstruct them while keeping the
benchmark rule first. `isManagedRule()` identifies both kinds for replacement/removal. The script checks servers through
the bench listener without following redirects: elevenlabs.io must answer 200 (blocked exits 302 to
geo-restriction.elevenlabs.io), api.openai.com and api.anthropic.com must answer 401 (blocked: 403). Mihomo's own
health check cannot do this — it counts blocked exits as healthy. Candidates: last bench ranking first, then alive
servers by delay, deduplicated by server:port, up to GEO_N=5 passing. Runs after every bench, from `check` when
SUB-GEO has no alive member or was never checked, and via `xk-sub.sh geo`. Configs from 1.7.x get SUB-GEO when the
subscription settings are saved (`updateSettings` re-applies `enableSubscription`).

### XKeen / Mihomo updates (v1.7.0)
`UpdatesRemote` reads installed versions (xkeen variable file, `mihomo -v`) and the latest release from the API URLs in
xkeen's own variable file (jsDelivr fallback), then runs `xkeen -uk auto` / `xkeen -um auto` with stdin from /dev/null,
after `backupCoreConfigs()`, and restarts the proxy if it ended up stopped. Requires xkeen with `get_auto_arg` (2.x).

### Routing toggles are stored only in the rules (v1.7.1)
`getRoutingConfig()` reconstructs every toggle (YouTube, Aqara, Zona, custom routes, manual server) from
05_routing.json, and every UI change re-applies `applyPreset()` with what was read back. Anything a preset
does not write is therefore lost on the next change. So: Zona and direct custom routes are written under
ALL_DIRECT too (no-ops there); only the exact `STANDARD_PRIVATE_RANGES` are treated as preset-owned when
reading IP routes; a manually chosen `proxy-*` catch-all is kept by `applyPreset()`; any outbound other than
direct/block counts as a proxy target. `RoutingPresetTest` round-trips all of this through ALL_DIRECT.

### Single and batch server deletion
`ProxiesScreen` has a shared selection mode and one confirmation for a complete batch, plus
single-server delete buttons, in both cores. AUTO and provider-owned Mihomo nodes are excluded
from deletion. Both editors require at least one local proxy to remain and reject deletion of
a node still used as a dialer by a surviving node.

`MihomoConfigEditor.removeProxies` cleans group membership, direct rule targets (including
sub-rules) and provider download proxy references. Empty static groups get a surviving server.
The existing staged validation/reload transaction applies the entire batch once, choosing a
surviving selector member when the previous member was deleted; rollback restores the old
selection as well as the file.

`XrayServerEditor` repairs routing rules, balancer selectors/fallbacks and observatory selectors
using Xray's prefix matching semantics. `XrayServerRemote` stages a copy of all JSON configs,
edits 04/05/07, tests the staged directory with the asset environment, backs up the original
configs and restarts once after installing the batch. Partial writes and failed restarts
restore every affected file and restart the original configuration. Backups remain under
`configs/backups/delete-<uuid>/`. Unit tests cover both editors and transaction failure paths.

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

### Zona "don't proxy" toggle
The reason Zona breaks under the tunnel is **not** bandwidth — it is that Russian services
reject or degrade requests coming from foreign exit IPs. So `RoutingConfig.zonaDirect` covers
exactly the RU-hosted half of Zona's infrastructure and deliberately leaves video alone.

The first cut of this list was wrong because it was built by reading the website (CT logs plus
the site's JS bundle). The apps use entirely different hosts. The current list came from
capturing live connections off a real TV through Mihomo's `/connections` API:

| host | resolves to | |
|---|---|---|
| `mzona.net` (apirn1/apir0/syncr/evr) | 80.77.168.22 | eServer, RU — API |
| `imgzona.video` (imgr1) | 37.143.13.84 | EuroByte, RU — posters |
| `upzona.net`, `zonastat.com` | 185.22.234.0/23 | IHC, RU |
| `zona.pub` and its mirrors | 5.35.170.4 | MEDIABRIDGE, **NL** — the website |

Subdomains vary per function (`apir0`, `apirn1`, `syncr`, `evr`, `imgr1` all appeared within
minutes), so everything is matched on the apex.

**Video is intentionally excluded.** Zona is an aggregator and streams from whichever third
party holds the release: one playback session produced `interkh.com` and `werkecdn.me`
(FDCservers NL/DE), `stloadi.live` (AE), `vkvideo.cloud` and `obrut.show`. That set is
unbounded and content-dependent, and being foreign it works through the tunnel anyway. A user
who wants a particular host direct can add it via the existing custom domain routes.

`ZONA_IPS` (`5.35.170.0/24`) is a backstop for connections opened straight to the website's CDN
address with no SNI. Note it only functions under Xray — see the Mihomo caveat below.

Rules are emitted at step 5c in `applyPreset()`, alongside the other direct rules, after
geoip/geosite and before the catch-all. Ordering is safe because the geo rules never match
these anyway, and it leaves custom **proxy** routes (steps 3/3b) winning so a user can still
override. Skipped under ALL_DIRECT, where the card is hidden too.

### Mihomo: geoip is effectively dead for sniffed connections
`MihomoConfigRemote.ipRules()` appends `no-resolve` to every IP rule, including
`GEOIP,RU,DIRECT`. With the sniffer on, a connection that yields a hostname carries **no**
destination IP, and `no-resolve` forbids resolving one — so the rule cannot match. Measured on
a live router: of 127 connections, `GeoIP` matched 8, every one of them a connection with a
destination IP and no host; **zero** connections with a host ever matched any IP rule. 54 of
the 67 that fell through to `MATCH,PROXY` had a hostname.

The practical effect is that under Mihomo, "Russian traffic goes direct" rests entirely on the
`geosite:category-ru` domain list. Any Russian service missing from that list gets tunnelled —
and by the same logic that motivates the Zona toggle, those services will then reject the
connection. Xray is unaffected: its `ip` field resolves and compares, which is the semantics
`no-resolve` disables.

The fix is to drop `no-resolve` from `GEOIP,<country>` and from user IP routes while keeping it
on the private ranges (nothing resolves into 10/8 or 192.168/16). Not applied yet — it changes
routing for all traffic in Mihomo mode and wants testing on a real router first.

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

### Support for security=tls (v1.3.0)
- `VlessParser`: previously hardcoded `security: "reality"` and `realitySettings` in both `buildVisionOutbound` (TCP) and `buildXhttpOutbound` (XHTTP), ignoring the `security=` parameter from the VLESS link. Links with `security=tls` (standard TLS, no REALITY) failed with xray error "Failed to build REALITY config > empty password" because `realitySettings.publicKey` was empty.
- Now both builders read `security=` and generate `tlsSettings` (with `serverName`, `fingerprint`, `alpn`, `allowInsecure`) for `security=tls`, or `realitySettings` for `security=reality`.
- `buildVisionOutbound` renamed to `buildTcpOutbound` to reflect that it handles all TCP transports, not just Vision.
- Flow defaulting fixed: previously always set `flow = "xtls-rprx-vision"` when empty. Now only defaults to Vision for REALITY links; plain TLS links keep `flow = ""`.
- This enables support for additional VPN providers (e.g. services using standard VLESS+TLS without REALITY).

### ps output format (busybox on Keenetic)
```
PID   USER     VSZ   STAT  COMMAND
10627 root     1330m S     xray run
```
Indices: [0]=PID, [1]=user, [2]=memory(VSZ), [3]=state, [4+]=command. Memory is at index 2, NOT 3.

## Release signing
Every APK published before this was an **unminified debug build**, signed with whatever
`~/.android/debug.keystore` the CI runner generated for that run — a different key each time.
That made in-place updates impossible: users hit `INSTALL_FAILED_UPDATE_INCOMPATIBLE` on every
release and had to uninstall, which wipes the Room profile database (`allowBackup="false"`,
so there is nothing to restore from either).

Releases now build `assembleRelease` against a fixed keystore. `app/build.gradle.kts` reads the
signing material from env vars first (CI, decoded from GitHub Secrets) and falls back to
`keystore.properties` at the repo root for local builds. Both that file and `*.jks` are
gitignored. When neither source yields a usable keystore the release APK is left unsigned rather
than failing the build, so a fresh clone still works with no secrets.

Signing schemes are pinned explicitly instead of inheriting AGP defaults: v1 off (pointless at
minSdk 26), v2 on, **v3 on** — v3 is what allows rotating the key later without forcing every
user to reinstall again.

Required GitHub Secrets: `KEYSTORE_BASE64` (base64 of the .jks), `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. The workflow decodes the keystore into `RUNNER_TEMP`, builds,
asserts the result is not debug-signed (a silent fallthrough would otherwise ship an
unupdatable APK again), and deletes the decoded key with `if: always()`.

**The keystore is irreplaceable.** Lose it and no future build can ever update an installed app
— every user would have to uninstall and lose their router profiles.

`isMinifyEnabled` is deliberately **false**. R8 has never run on this code, so enabling it in the
same change as signing would ship two untested things at once. There are no `@Serializable`
classes (all JSON goes through the dynamic `JsonObject` tree) and JSch already has keep rules,
so turning it on later should be low-risk — but it needs a run on a real device first.

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
