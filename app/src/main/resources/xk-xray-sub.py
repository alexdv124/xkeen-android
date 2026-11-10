#!/opt/bin/python3
"""XKeen Android: router-owned Xray subscriptions. No phone or cloud converter needed."""
import base64
import concurrent.futures
import ctypes
import contextlib
import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
from urllib.parse import parse_qs, unquote, urlsplit

ROOT = Path('/opt/etc/xkeen-android/xray-subscription')
CONFIGS = Path('/opt/etc/xray/configs')
INIT = '/opt/etc/init.d/S99xkeen'
CRON = Path('/opt/var/spool/cron/crontabs/root')
SCRIPT = ROOT / 'subscription.py'
LOCK = Path('/tmp/xkeen-xray-subscription.lock')
MANAGED = '08_xkeen_subscription.json'
PREFIX = 'proxy-sub-'
GEO_TAG = 'xk-sub-geo'
GEO_PREFIX = PREFIX + 'geo-'
BEST_PREFIX = PREFIX + 'best-'
GEO_DOMAINS = ['elevenlabs.io', 'elevenlabs.com', 'openai.com', 'chatgpt.com', 'oaistatic.com', 'oaiusercontent.com', 'sora.com', 'anthropic.com', 'claude.ai', 'claude.com', 'claudeusercontent.com']
GEO_CHECKS = [('https://elevenlabs.io', '200'), ('https://api.openai.com/v1/models', '401'), ('https://api.anthropic.com/v1/models', '401')]
MAX_BYTES = 8 * 1024 * 1024
MAX_NODES = 500

class Failure(Exception):
    pass

def run(args, timeout=30, **kwargs):
    return subprocess.run(args, timeout=timeout, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)

def atomic(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.' + path.name, dir=str(path.parent))
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data if isinstance(data, bytes) else data.encode())
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name): os.unlink(name)

def save(path, value):
    atomic(path, json.dumps(value, ensure_ascii=False, indent=2))

def read(path, default=None):
    try: return json.loads(Path(path).read_text())
    except FileNotFoundError: return {} if default is None else default

def local_json(raw):
    # Keep strings/escapes intact; never use regexes to strip comments inside URLs.
    chars = list(raw)
    i = 0
    quoted = False
    while i < len(raw):
        if quoted:
            if raw[i] == '\\': i += 2; continue
            if raw[i] == '"': quoted = False
            i += 1; continue
        if raw[i] == '"': quoted = True; i += 1; continue
        if raw[i] == '#' or raw[i:i+2] == '//':
            while i < len(raw) and raw[i] not in '\r\n': chars[i] = ' '; i += 1
        elif raw[i:i+2] == '/*':
            end = raw.find('*/', i + 2)
            if end < 0: raise Failure('Незакрытый комментарий в конфиге')
            while i < end + 2:
                if raw[i] not in '\r\n': chars[i] = ' '
                i += 1
        else: i += 1
    return json.loads(''.join(chars))

def b64decode(text):
    data = ''.join(text.split()).encode('ascii')
    return base64.b64decode(data + b'=' * (-len(data) % 4), altchars=b'-_', validate=True).decode('utf-8-sig')

def parse_link(link):
    uri = urlsplit(link)
    if uri.scheme.lower() != 'vless': raise Failure('Поддерживаются подписки со ссылками VLESS; выберите формат VLESS/Base64 у провайдера')
    q = parse_qs(uri.query, keep_blank_values=True)
    def val(key, default=''): return q.get(key, [default])[0]
    address, port, user = uri.hostname, uri.port, unquote(uri.username or '')
    if not address or not port or not 0 < port < 65536 or not user: raise Failure('Неполная VLESS-ссылка')
    transport = val('type', 'tcp')
    if transport == 'raw': transport = 'tcp'
    if transport not in ('tcp', 'ws', 'grpc', 'xhttp'): raise Failure('Неподдерживаемый транспорт VLESS: ' + transport[:24])
    security = val('security', 'none')
    if security not in ('none', 'tls', 'reality'): raise Failure('Неподдерживаемая защита VLESS')
    if val('fm'): raise Failure('VLESS с fm пока не поддерживается подпиской Xray')
    stream = {'network': transport, 'security': security}
    if security == 'reality':
        if not val('pbk'): raise Failure('В REALITY-ссылке отсутствует публичный ключ')
        stream['realitySettings'] = {'serverName': val('sni', address), 'fingerprint': val('fp', 'chrome'),
                                     'publicKey': val('pbk'), 'shortId': val('sid'), 'spiderX': val('spx', '/')}
    elif security == 'tls':
        tls = {'serverName': val('sni', address), 'fingerprint': val('fp', 'chrome')}
        if val('alpn'): tls['alpn'] = val('alpn').split(',')
        if val('allowInsecure') in ('1', 'true'): tls['allowInsecure'] = True
        stream['tlsSettings'] = tls
    if transport == 'ws':
        stream['wsSettings'] = {'path': val('path', '/')}
        if val('host'): stream['wsSettings']['headers'] = {'Host': val('host')}
    elif transport == 'grpc':
        stream['grpcSettings'] = {'serviceName': val('serviceName'), 'multiMode': val('mode') == 'multi'}
        if val('authority'): stream['grpcSettings']['authority'] = val('authority')
    elif transport == 'xhttp':
        settings = {'path': val('path', '/'), 'mode': val('mode', 'auto')}
        if val('host'): settings['host'] = val('host')
        if val('extra'):
            extra = json.loads(val('extra'))
            if not isinstance(extra, dict): raise Failure('Некорректный XHTTP extra')
            settings['extra'] = whole_floats(extra)
        stream['xhttpSettings'] = settings
    elif val('headerType', 'none') != 'none':
        raise Failure('TCP headerType пока не поддерживается подпиской Xray')
    account = {'id': user, 'encryption': val('encryption', 'none')}
    if val('flow'): account['flow'] = val('flow')
    outbound = {'protocol': 'vless', 'settings': {'vnext': [{'address': address, 'port': port, 'users': [account]}]}, 'streamSettings': stream}
    key = hashlib.sha256(json.dumps(outbound, sort_keys=True).encode()).hexdigest()[:20]
    outbound['tag'] = BEST_PREFIX + key
    return {'name': unquote(uri.fragment) or address, 'key': key, 'outbound': outbound}

def whole_floats(value):
    if isinstance(value, dict): return {k: whole_floats(v) for k, v in value.items()}
    if isinstance(value, list): return [whole_floats(v) for v in value]
    if isinstance(value, float) and value.is_integer(): return int(value)
    return value

def parse_feed(raw):
    if len(raw.encode()) > MAX_BYTES: raise Failure('Подписка слишком большая (лимит 8 МБ)')
    text = raw.lstrip('\ufeff').strip()
    if not text: raise Failure('Провайдер вернул пустую подписку; рабочие серверы сохранены')
    if not text.lower().startswith('vless://'):
        try: text = b64decode(text).strip()
        except Exception: raise Failure('Нужен список VLESS или Base64, получен другой формат подписки')
    nodes = {}
    for line in text.splitlines():
        if not line.strip(): continue
        try: node = parse_link(line.strip())
        except Failure: raise
        except Exception: raise Failure('Некорректная VLESS-ссылка; рабочие серверы сохранены')
        nodes.setdefault(node['key'], node)
        if len(nodes) > MAX_NODES: raise Failure('В подписке больше 500 уникальных серверов')
    if not nodes: raise Failure('В подписке нет серверов')
    return list(nodes.values())

def validate_settings(settings):
    url = settings.get('url', '')
    uri = urlsplit(url)
    if uri.scheme not in ('http', 'https') or not uri.hostname or uri.username or any(ord(c) < 32 for c in url):
        raise Failure('Нужна корректная HTTP(S)-ссылка подписки без логина в адресе')
    top = settings.get('topN', 10)
    if not isinstance(top, int) or isinstance(top, bool) or not 1 <= top <= 30: raise Failure('Количество серверов: от 1 до 30')
    match = re.fullmatch(r'(\d{1,2}):(\d{2})', settings.get('dailyTime', ''))
    if not match or int(match[1]) > 23 or int(match[2]) > 59: raise Failure('Некорректное время обновления')
    return {'url': url, 'topN': top, 'dailyTime': '%02d:%02d' % (int(match[1]), int(match[2])), 'enabled': True}

def download(url):
    with tempfile.TemporaryDirectory(prefix='xk-download-') as directory:
        target = Path(directory) / 'feed'
        proto = '=https' if url.startswith('https:') else '=http,https'
        # URL is data in a private curl config, never shell code or a process-list argument.
        curl_conf = Path(directory) / 'curl.conf'
        atomic(curl_conf, 'url = "' + url.replace('\\', '\\\\').replace('"', '\\"') + '"\n')
        result = run(['curl', '--config', str(curl_conf), '--fail', '--silent', '--show-error', '--location',
                      '--proto', proto, '--proto-redir', proto, '--connect-timeout', '15', '--max-time', '120',
                      '--max-filesize', str(MAX_BYTES), '--output', str(target)], timeout=130)
        if result.returncode: raise Failure('Не удалось скачать подписку (HTTP, TLS или сеть); рабочие серверы сохранены')
        if target.stat().st_size > MAX_BYTES: raise Failure('Подписка превышает 8 МБ')
        return target.read_text(encoding='utf-8-sig')

def core_active():
    try: text = Path(INIT).read_text()
    except OSError: return False
    return bool(re.search(r'^\s*name_client\s*=\s*[\"\']?xray[\"\']?\s*$', text, re.M)) and run(['pidof', 'xray']).returncode == 0

def require_core():
    if not core_active(): raise Failure('Xray не активен; обновление отложено до его запуска')

def stop_with_parent():
    # On the router, even a killed Python worker must not leave a second Xray running.
    if sys.platform.startswith('linux'):
        parent = os.getppid()
        if ctypes.CDLL(None).prctl(1, signal.SIGTERM, 0, 0, 0) != 0:
            raise RuntimeError('Cannot bind probe lifetime to worker')
        if os.getppid() != parent: os.kill(os.getpid(), signal.SIGTERM)

class Probe:
    def __init__(self, nodes): self.nodes = nodes; self.ports = {}; self.process = None
    def __enter__(self):
        self.directory = tempfile.TemporaryDirectory(prefix='xk-xray-probe-')
        config = {'log': {'loglevel': 'none'}, 'inbounds': [], 'outbounds': [], 'routing': {'rules': []}}
        used = set()
        for node in self.nodes:
            while True:
                with socket.socket() as sock:
                    sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
                if port not in used: break
            used.add(port); self.ports[node['key']] = port
            tag = node['outbound']['tag']
            config['inbounds'].append({'tag': tag, 'listen': '127.0.0.1', 'port': port, 'protocol': 'http', 'settings': {}})
            config['outbounds'].append(node['outbound'])
            config['routing']['rules'].append({'type': 'field', 'inboundTag': [tag], 'outboundTag': tag})
        path = Path(self.directory.name) / 'config.json'; save(path, config)
        self.process = subprocess.Popen(['xray', 'run', '-config', str(path)], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, preexec_fn=stop_with_parent)
        time.sleep(1)
        if self.process.poll() is not None:
            self.__exit__(None, None, None)
            raise Failure('Xray не смог запустить проверку серверов; формат транспорта может не поддерживаться установленным ядром')
        return self
    def __exit__(self, *args):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try: self.process.wait(timeout=5)
            except subprocess.TimeoutExpired: self.process.kill(); self.process.wait()
        self.directory.cleanup()
    def curl(self, node, url, extra=(), seconds=8):
        args = ['curl', '--silent', '--proxy', 'http://127.0.0.1:%s' % self.ports[node['key']], '--noproxy', '',
                '--connect-timeout', '4', '--max-time', str(seconds), '--output', '/dev/null',
                '--write-out', '%{http_code} %{time_total} %{speed_download} %{speed_upload}', *extra, url]
        try:
            result = run(args, timeout=seconds+3)
            fields = result.stdout.decode().strip().split()
            if len(fields) == 4: return result.returncode, fields
        except subprocess.TimeoutExpired: pass
        return 1, ['000', '0', '0', '0']
    def health(self, node):
        code, values = self.curl(node, 'https://www.gstatic.com/generate_204')
        return int(float(values[1])*1000) if code == 0 and values[0] == '204' else None
    def speed(self, node):
        _, down = self.curl(node, 'https://speed.cloudflare.com/__down?bytes=20000000', seconds=6)
        # curl may time out after transferring enough data: only count a successful HTTP response.
        down_rate = float(down[2])*8/1e6 if down[0] in ('200', '206') else 0
        upload = Path(self.directory.name) / 'upload.bin'
        if not upload.exists():
            with upload.open('wb') as stream: stream.truncate(8*1024*1024)
        _, up = self.curl(node, 'https://speed.cloudflare.com/__up', ['--data-binary', '@'+str(upload)], seconds=10)
        up_rate = float(up[3])*8/1e6 if up[0] in ('200', '201', '204') else 0
        return down_rate, up_rate
    def geo(self, node):
        for url, expected in GEO_CHECKS:
            code, values = self.curl(node, url)
            if code != 0 or values[0] != expected: return False, urlsplit(url).hostname + ': ' + values[0]
        return True, 'OK'

def measure(nodes, top, previous, geo_only=False, check=False):
    with Probe(nodes) as probe:
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            delays = dict(zip((n['key'] for n in nodes), pool.map(probe.health, nodes)))
        alive = [n for n in nodes if delays[n['key']] is not None]
        alive.sort(key=lambda n: delays[n['key']])
        if not alive: raise Failure('Ни один сервер не прошёл проверку доступности; рабочий конфиг сохранён')
        by_key = {n['key']: n for n in nodes}
        old_best = [by_key[k] for k in previous.get('best', []) if k in by_key and delays[k] is not None]
        old_geo = previous.get('geo_key')
        if check and len(old_best) >= min(3, top) and time.time() - previous.get('last_bench', 0) < 7*86400:
            if old_geo in by_key and probe.geo(by_key[old_geo])[0]: return None
            geo_only = True
        ranked = []
        if geo_only and old_best:
            best = old_best
            ranked = previous.get('servers', [])
        else:
            for node in alive[:max(top*2, 15)]:
                down, up = probe.speed(node)
                ranked.append({'key': node['key'], 'name': node['name'], 'delay': delays[node['key']], 'down': down, 'up': up})
            ranked.sort(key=lambda r: r['down']+2*r['up'], reverse=True)
            chosen = [r for r in ranked if r['down'] >= 5][:top]
            if not chosen: raise Failure('Нет серверов со скоростью от 5 Мбит/с; рабочий конфиг сохранён')
            best = [by_key[r['key']] for r in chosen]
        ordered = [by_key[r['key']] for r in ranked if r['key'] in by_key and delays[r['key']] is not None]
        ordered += [n for n in alive if n not in ordered]
        geo_results = []
        passing = []
        for node in ordered[:30]:
            ok, reason = probe.geo(node)
            geo_results.append({'name': node['name'], 'ok': ok, 'reason': reason})
            if ok: passing.append(node)
            if len(passing) >= 5: break
        # Keep a previously verified GEO node if no new candidate passes, even if the provider removed it.
        geo_nodes = passing or previous.get('geo_nodes') or [best[0]]
        geo = geo_nodes[0]
        best_keys = {n['key'] for n in best}
        for row in ranked: row['chosen'] = row['key'] in best_keys
        return {'best': best, 'geo_nodes': geo_nodes, 'geo_key': geo['key'], 'servers': ranked,
                'geo': {'servers': geo_results}, 'last_bench': previous.get('last_bench', 0) if geo_only and old_best else int(time.time())}

def add_geo_rules(rules):
    rules = [r for r in rules if r.get('balancerTag') != GEO_TAG]
    # ALL_DIRECT has no proxy targets: leave it intact.
    if not any(r.get('balancerTag') or str(r.get('outboundTag', '')).startswith('proxy-') for r in rules): return rules
    geo = {'type': 'field', 'domain': ['domain:'+d for d in GEO_DOMAINS], 'balancerTag': GEO_TAG}
    # Explicit direct exceptions stay before GEO; broad geosite/geoip defaults stay after it.
    direct = [r for r in rules if r.get('outboundTag') == 'direct' and (r.get('source') or r.get('domain') or r.get('ip'))
              and not any(str(v).startswith(('geosite:', 'geoip:', 'ext:')) or v in ('domain:ru', 'domain:su', 'domain:рф')
                          for v in r.get('domain', []) + r.get('ip', []))]
    rest = [r for r in rules if r not in direct]
    return direct + [geo] + rest

def prepare(originals, selected, first):
    generated = {'outbounds': [dict(copy.deepcopy(n['outbound']), remarks=n['name']) for n in selected['best']]}
    for node in selected['geo_nodes']:
        geo = copy.deepcopy(node['outbound']); geo['tag'] = GEO_PREFIX + node['key']; geo['remarks'] = node['name'] + ' · геоблокировки'
        generated['outbounds'].append(geo)
    generated['routing'] = {'balancers': [{'tag': GEO_TAG, 'selector': [GEO_PREFIX], 'strategy': {'type': 'leastPing'}}]}
    updates = {MANAGED: json.dumps(generated, ensure_ascii=False, indent=2).encode()}
    if first:
        routing = local_json(originals['05_routing.json'].decode())
        section = routing.setdefault('routing', {})
        balancers = section.setdefault('balancers', [])
        balancers[:] = [b for b in balancers if b.get('tag') != 'proxy-balancer']
        balancers.append({'tag': 'proxy-balancer', 'selector': [BEST_PREFIX], 'strategy': {'type': 'leastPing'}})
        rules = section.setdefault('rules', [])
        # Keep route conditions and direct/block targets; subscription becomes the proxy pool.
        for rule in rules:
            if str(rule.get('outboundTag', '')).startswith('proxy-'):
                rule.pop('outboundTag'); rule['balancerTag'] = 'proxy-balancer'
        section['rules'] = add_geo_rules(rules)
        updates['05_routing.json'] = json.dumps(routing, ensure_ascii=False, indent=2).encode()
        observer = local_json(originals.get('07_observatory.json', b'{}').decode() or '{}')
        if 'burstObservatory' in observer: obs = observer['burstObservatory']
        elif 'observatory' in observer: obs = observer['observatory']
        else:
            obs = {'probeURL': 'https://www.gstatic.com/generate_204', 'probeInterval': '1m', 'enableConcurrency': True}
            observer['observatory'] = obs
        selectors = obs.setdefault('subjectSelector', [])
        if not any(PREFIX.startswith(prefix) for prefix in selectors): selectors.append(PREFIX)
        updates['07_observatory.json'] = json.dumps(observer, ensure_ascii=False, indent=2).encode()
    return updates

def snapshot():
    return {p.name: p.read_bytes() for p in CONFIGS.glob('*.json')}

def test_config(directory):
    env = os.environ.copy(); env['XRAY_LOCATION_ASSET'] = '/opt/etc/xray/dat'; env['XRAY_LOCATION_CONFDIR'] = str(directory)
    result = run(['xray', 'run', '-test', '-confdir', str(directory)], timeout=40, env=env)
    if result.returncode: raise Failure('Новый конфиг не прошёл проверку Xray; рабочие файлы сохранены')

def restart():
    result = run([INIT, 'restart'], timeout=60)
    if result.returncode: return False
    time.sleep(2)
    for _ in range(10):
        if run(['pidof', 'xray']).returncode == 0: return True
        time.sleep(1)
    return False

def commit(originals, updates):
    CONFIGS.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.xk-sub-stage-', dir=str(CONFIGS.parent)) as directory:
        for name, raw in {**originals, **updates}.items(): atomic(Path(directory)/name, raw)
        test_config(directory)
        require_core()
        if snapshot() != originals: raise Failure('Конфиг изменился во время проверки; обновление отложено')
        backup = ROOT / 'backups' / str(time.time_ns())
        for name, raw in originals.items(): atomic(backup/name, raw)
        try:
            for name, raw in updates.items(): atomic(CONFIGS/name, raw)
            if not restart(): raise Failure('Xray не запустился с новым конфигом')
        except Exception:
            for name, written in updates.items():
                # Never overwrite a newer external edit during recovery.
                current = (CONFIGS/name).read_bytes() if (CONFIGS/name).exists() else None
                if current == originals.get(name): continue # This file was never replaced.
                if current != written: raise Failure('Конфиг изменён извне; требуется восстановление из ' + str(backup))
                if name in originals: atomic(CONFIGS/name, originals[name])
                else: (CONFIGS/name).unlink(missing_ok=True)
            if not restart(): raise Failure('Не удалось подтвердить откат Xray. Копия: ' + str(backup))
            raise Failure('Обновление не применено; прежний конфиг восстановлен')
        for old in sorted((ROOT/'backups').iterdir())[:-5]: shutil.rmtree(old)

def cron_content(old, settings):
    lines = [line for line in old.splitlines() if str(SCRIPT) not in line]
    if settings.get('enabled'):
        hour, minute = map(int, settings['dailyTime'].split(':'))
        lines += ['%d %d * * * /opt/bin/python3 %s daily >/dev/null 2>&1' % (minute, hour, SCRIPT),
                  '*/30 * * * * /opt/bin/python3 %s check >/dev/null 2>&1' % SCRIPT]
    return '\n'.join(lines) + ('\n' if lines else '')

def set_cron(settings):
    old = CRON.read_text() if CRON.exists() else ''
    atomic(CRON, cron_content(old, settings))
    try:
        result = run(['/opt/etc/init.d/S05crond', 'restart'], timeout=20)
        if result.returncode or run(['pidof', 'crond']).returncode: raise Failure('cron')
    except Exception:
        atomic(CRON, old)
        with contextlib.suppress(Exception): run(['/opt/etc/init.d/S05crond', 'restart'], timeout=20)
        raise Failure('Не удалось включить расписание crond; проверьте службу cron')

def worker(mode, request=None):
    settings = read(ROOT/'settings.json')
    previous = read(ROOT/'state.json')
    if mode == 'disable':
        settings['enabled'] = False; set_cron(settings); save(ROOT/'settings.json', settings)
        return
    if mode == 'configure': settings = validate_settings(read(request))
    elif not settings.get('enabled'): return
    require_core()
    originals = snapshot()
    if '05_routing.json' not in originals: raise Failure('Не найден 05_routing.json')
    cached = ROOT/'source.txt'
    raw = cached.read_text() if mode in ('bench', 'geo') and cached.exists() else download(settings['url'])
    nodes = parse_feed(raw)
    digest = hashlib.sha256(raw.encode()).hexdigest()
    selected = measure(nodes, settings['topN'], previous, geo_only=mode == 'geo',
                       check=mode == 'check' and digest == previous.get('source_hash'))
    if selected is None:
        previous['last_check'] = int(time.time()); previous['last_error'] = ''; save(ROOT/'state.json', previous); return
    first = MANAGED not in originals
    updates = prepare(originals, selected, first)
    # Configure cron before committing so a schedule failure never replaces the running config.
    old_cron = CRON.read_bytes() if CRON.exists() else b''
    if mode == 'configure': set_cron(settings)
    try: commit(originals, updates)
    except Exception:
        if mode == 'configure': atomic(CRON, old_cron); run(['/opt/etc/init.d/S05crond', 'restart'], timeout=20)
        raise
    atomic(cached, raw)
    state = dict(selected)
    state['best'] = [n['key'] for n in selected['best']]
    state.update({'source_hash': digest, 'last_update': int(time.time()), 'last_error': '', 'count': len(nodes)})
    save(ROOT/'state.json', state); save(ROOT/'settings.json', settings)

def acquire():
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(LOCK, os.O_CREAT | os.O_RDWR, 0o600)
    try: fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError: os.close(fd); raise Failure('Обновление подписки уже выполняется')
    return fd

def status():
    running = False
    try: fd = acquire(); os.close(fd)
    except Failure: running = True
    settings = read(ROOT/'settings.json'); state = read(ROOT/'state.json')
    cron = CRON.read_text() if CRON.exists() else ''
    return {'enabled': settings.get('enabled', False), 'url': settings.get('url', ''),
            'settings': {'topN': settings.get('topN', 10), 'dailyTime': settings.get('dailyTime', '04:00')},
            'scriptInstalled': True, 'cronInstalled': str(SCRIPT)+' daily ' in cron and str(SCRIPT)+' check ' in cron, 'running': running,
            'last_update': state.get('last_update', 0), 'last_bench': state.get('last_bench', 0),
            'last_error': state.get('last_error', ''), 'result': {'servers': state.get('servers', [])},
            'geo': state.get('geo', {}), 'count': state.get('count', 0), 'geoGroup': (CONFIGS/MANAGED).exists()}

def main(args):
    os.umask(0o077)
    os.environ['PATH'] = '/opt/bin:/opt/sbin:/usr/bin:/usr/sbin:/bin:/sbin'
    ROOT.mkdir(parents=True, exist_ok=True)
    mode = args[0]
    if mode == 'status': print(json.dumps(status(), ensure_ascii=False)); return
    if mode == 'apply':
        staged, manifest = Path(args[1]), read(args[2])
        fd = acquire()
        try:
            originals = snapshot()
            for name, digest in manifest.items():
                if name not in originals or hashlib.sha256(originals[name]).hexdigest() != digest:
                    raise Failure('Конфиг изменился на роутере. Повторите удаление')
            updates = {name: (staged/name).read_bytes() for name in manifest if name in ('04_outbounds.json', '05_routing.json', '07_observatory.json')}
            commit(originals, updates)
        finally: os.close(fd)
        print('OK'); return
    if mode == 'write':
        path, staged, expected = Path(args[1]), Path(args[2]), args[3]
        if path.parent != CONFIGS or path.name not in ('01_log.json', '03_inbounds.json', '04_outbounds.json', '05_routing.json', '07_observatory.json'):
            raise Failure('Неизвестный файл Xray')
        fd = acquire()
        try:
            actual = hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else 'missing'
            if actual != expected: raise Failure('Конфиг изменился на роутере. Обновите экран и повторите действие')
            atomic(path, staged.read_bytes())
        finally: os.close(fd)
        print('OK'); return
    if mode == 'launch':
        action = args[1]
        if action not in ('configure', 'disable', 'daily', 'bench', 'geo'): raise Failure('Неизвестная операция')
        request = args[2] if len(args) > 2 else ''
        if action == 'configure': validate_settings(read(request))
        fd = acquire()
        try:
            subprocess.Popen([sys.executable, str(SCRIPT), 'worker', str(fd), action, request], pass_fds=(fd,),
                             start_new_session=True, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        finally: os.close(fd)
        print('STARTED'); return
    if mode == 'worker': fd = int(args[1]); mode = args[2]; request = args[3] or None
    else: fd = acquire(); request = None
    try:
        # SIGTERM closes temporary probes and rolls back through regular exception handling.
        signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(Failure('Обновление прервано')))
        worker(mode, request)
    except Exception as error:
        state = read(ROOT/'state.json')
        state['last_error'] = str(error) if isinstance(error, Failure) else 'Ошибка обновления; проверьте доступность роутера и свободное место'
        save(ROOT/'state.json', state)
    finally:
        if request: Path(request).unlink(missing_ok=True)
        os.close(fd)

if __name__ == '__main__':
    try: main(sys.argv[1:])
    except Failure as error: print(str(error)); sys.exit(1)
