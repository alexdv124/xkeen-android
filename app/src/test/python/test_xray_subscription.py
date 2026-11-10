import base64
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[2] / 'main/resources/xk-xray-sub.py'
spec = importlib.util.spec_from_file_location('subscription', SOURCE)
s = importlib.util.module_from_spec(spec); spec.loader.exec_module(s)
LINK = 'vless://11111111-1111-1111-1111-111111111111@example.com:443?type=tcp&security=tls&sni=example.com#First'

def node(host='example.com'):
    return s.parse_link(LINK.replace('example.com', host))

def selected():
    one = node()
    return {'best': [one], 'geo_nodes': [one], 'geo_key': one['key'], 'last_bench': 100,
            'servers': [{'name': 'First', 'key': one['key'], 'chosen': True, 'delay': 10, 'down': 50, 'up': 20}],
            'geo': {'servers': [{'name': 'First', 'ok': True, 'reason': 'OK'}]}}

class ParserTests(unittest.TestCase):
    def test_plain_base64_unicode_ipv6_dedup_and_stable_identity(self):
        link = LINK.replace('example.com:443', '[2001:db8::1]:8443').replace('#First', '#%D0%A2%D0%B5%D1%81%D1%82')
        raw = link + '\r\n' + link.replace('#%D0%A2%D0%B5%D1%81%D1%82', '#Renamed')
        a = s.parse_feed(raw); b = s.parse_feed(base64.urlsafe_b64encode(raw.encode()).decode().rstrip('='))
        self.assertEqual(a, b); self.assertEqual(len(a), 1)
        self.assertEqual(a[0]['name'], 'Тест')
        self.assertEqual(a[0]['outbound']['settings']['vnext'][0]['address'], '2001:db8::1')

    def test_reality_ws_grpc_xhttp(self):
        for transport in ('tcp', 'ws', 'grpc', 'xhttp'):
            link = LINK.replace('type=tcp&security=tls', 'type='+transport+'&security=reality&pbk=test&sid=ab&flow=xtls-rprx-vision&path=%2Fa%3Fx%3D1&serviceName=rpc&host=front.example')
            ob = s.parse_link(link)['outbound']
            self.assertEqual(ob['streamSettings']['security'], 'reality')
            self.assertEqual(ob['settings']['vnext'][0]['users'][0]['flow'], 'xtls-rprx-vision')
            self.assertEqual(ob['streamSettings']['realitySettings']['publicKey'], 'test')
        xhttp = s.parse_link(LINK.replace('type=tcp', 'type=xhttp') + '&extra=bad') # fragment, no query effect
        self.assertEqual(xhttp['outbound']['streamSettings']['network'], 'xhttp')

    def test_invalid_or_unsupported_feed_fails_as_whole_without_credentials(self):
        for raw in ('', '<html>denied</html>', 'proxies: []', LINK+'\nvmess://SECRET', LINK.replace('type=tcp', 'type=hysteria'), LINK.replace('443', '70000')):
            with self.assertRaises(s.Failure) as result: s.parse_feed(raw)
            self.assertNotIn('SECRET', str(result.exception)); self.assertNotIn('11111111', str(result.exception))

    def test_comments_do_not_touch_urls_and_escaped_strings(self):
        raw = '// текст\n{"url":"https://a/#b", /* comment */ "v":"quote\\\"//x"} # end'
        self.assertEqual(s.local_json(raw)['url'], 'https://a/#b')
        self.assertEqual(s.local_json(raw)['v'], 'quote"//x')
        for bad in ('{} /* open', '{"v":tr/*x*/ue}'):
            with self.assertRaises(Exception): s.local_json(bad)

    def test_settings_reject_shell_and_header_injection(self):
        settings = {'url': 'https://example.org/sub?x=$(touch%20/tmp/pwn)', 'topN': 1, 'dailyTime': '4:05'}
        self.assertEqual(s.validate_settings(settings)['dailyTime'], '04:05')
        for url in ('file:///etc/passwd', 'https://user:secret@host/', 'https://host/\nheader=bad'):
            with self.assertRaises(s.Failure): s.validate_settings(dict(settings, url=url))

class RouterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = Path(self.temp.name)
        self.patches = []
        for key, value in {'ROOT': self.root/'app', 'CONFIGS': self.root/'configs', 'CRON': self.root/'cron',
                           'LOCK': self.root/'job.lock', 'SCRIPT': self.root/'app/subscription.py'}.items():
            p = patch.object(s, key, value); p.start(); self.patches.append(p)
        s.ROOT.mkdir(); s.CONFIGS.mkdir()
        self.routing = {'routing': {'domainStrategy': 'IPIfNonMatch', 'rules': [
            {'domain': ['domain:local.example'], 'outboundTag': 'direct'},
            {'domain': ['geosite:ru'], 'outboundTag': 'direct'},
            {'network': 'tcp,udp', 'balancerTag': 'proxy-balancer'}]}}
        s.save(s.CONFIGS/'04_outbounds.json', {'outbounds': [{'tag': 'proxy-manual', 'protocol': 'vless'}, {'tag':'direct','protocol':'freedom'}]})
        s.save(s.CONFIGS/'05_routing.json', self.routing)
        self.originals = s.snapshot()

    def tearDown(self):
        for p in reversed(self.patches): p.stop()
        self.temp.cleanup()

    def test_first_install_repairs_balancer_keeps_manual_servers_and_route_conditions(self):
        updates = s.prepare(self.originals, selected(), True)
        self.assertNotIn('04_outbounds.json', updates)
        routing = json.loads(updates['05_routing.json'])['routing']
        self.assertEqual(routing['balancers'][0]['selector'], [s.BEST_PREFIX])
        self.assertEqual(routing['rules'][0]['domain'], ['domain:local.example'])
        self.assertEqual(routing['rules'][1]['balancerTag'], s.GEO_TAG)
        self.assertEqual(routing['rules'][-1], self.routing['routing']['rules'][-1])
        self.assertIn(s.PREFIX, json.loads(updates['07_observatory.json'])['observatory']['subjectSelector'])
        managed = json.loads(updates[s.MANAGED])
        self.assertEqual(managed['routing']['balancers'][0]['tag'], s.GEO_TAG)

    def test_later_updates_only_touch_owned_file(self):
        updates = s.prepare(self.originals, selected(), False)
        self.assertEqual(list(updates), [s.MANAGED])

    def test_all_direct_never_gets_geo_exception(self):
        rules = [{'network':'tcp,udp','outboundTag':'direct'}]
        self.assertEqual(s.add_geo_rules(rules), rules)

    def test_invalid_stage_never_changes_or_restarts_live_config(self):
        with patch.object(s, 'test_config', side_effect=s.Failure('invalid')), patch.object(s, 'restart') as restart:
            with self.assertRaises(s.Failure): s.commit(self.originals, {s.MANAGED: b'{}'})
        self.assertEqual(s.snapshot(), self.originals); restart.assert_not_called()

    def test_conflicting_user_edit_is_preserved(self):
        def edit(_): s.atomic(s.CONFIGS/'05_routing.json', '{"new":"edit"}')
        with patch.object(s, 'test_config', side_effect=edit), patch.object(s, 'require_core'), patch.object(s, 'restart') as restart:
            with self.assertRaisesRegex(s.Failure, 'изменился'): s.commit(self.originals, {s.MANAGED: b'{}'})
        self.assertEqual((s.CONFIGS/'05_routing.json').read_text(), '{"new":"edit"}')
        self.assertFalse((s.CONFIGS/s.MANAGED).exists()); restart.assert_not_called()

    def test_failed_restart_restores_all_files_and_removes_new_file(self):
        updates = s.prepare(self.originals, selected(), True)
        with patch.object(s, 'test_config'), patch.object(s, 'require_core'), patch.object(s, 'restart', side_effect=[False, True]) as restart:
            with self.assertRaisesRegex(s.Failure, 'восстановлен'): s.commit(self.originals, updates)
        self.assertEqual(restart.call_count, 2); self.assertEqual(s.snapshot(), self.originals)
        self.assertTrue(list((s.ROOT/'backups').iterdir()))

    def test_partial_disk_write_rolls_back_already_installed_files(self):
        original_atomic=s.atomic
        failed=False
        def faulty(path,data):
            nonlocal failed
            if Path(path)==s.CONFIGS/'05_routing.json' and not failed:
                failed=True
                raise OSError('disk full')
            original_atomic(path,data)
        with patch.object(s,'test_config'),patch.object(s,'require_core'),patch.object(s,'restart',return_value=True),patch.object(s,'atomic',side_effect=faulty):
            with self.assertRaisesRegex(s.Failure,'восстановлен'): s.commit(self.originals,s.prepare(self.originals,selected(),True))
        self.assertEqual(s.snapshot(),self.originals)

    def test_cron_command_error_restores_previous_schedule(self):
        s.atomic(s.CRON,'unrelated\n')
        with patch.object(s,'run',side_effect=OSError('not found')):
            with self.assertRaises(s.Failure): s.set_cron({'enabled':True,'dailyTime':'04:00'})
        self.assertEqual(s.CRON.read_text(),'unrelated\n')

    def test_successful_commit_and_backup_are_private(self):
        with patch.object(s, 'test_config'), patch.object(s, 'require_core'), patch.object(s, 'restart', return_value=True):
            s.commit(self.originals, s.prepare(self.originals, selected(), True))
        self.assertEqual((s.CONFIGS/s.MANAGED).stat().st_mode & 0o777, 0o600)
        self.assertEqual((s.CONFIGS/'04_outbounds.json').read_bytes(), self.originals['04_outbounds.json'])

    def test_cron_keeps_mihomo_and_other_jobs_and_can_become_empty(self):
        other = '0 1 * * * /opt/etc/xkeen-android/xk-sub.sh daily\n5 2 * * * unrelated\n'
        settings = {'enabled':True,'dailyTime':'04:35'}
        configured = s.cron_content(other, settings)
        self.assertTrue(configured.startswith(other)); self.assertIn('35 4 * * *', configured)
        self.assertIn('*/30 * * * *', configured)
        self.assertEqual(s.cron_content(configured, {'enabled':False}), other)
        self.assertEqual(s.cron_content(s.cron_content('', settings), {'enabled':False}), '')

    def test_disable_keeps_last_working_servers_and_stops_schedule(self):
        s.save(s.ROOT/'settings.json', {'enabled':True,'url':'https://example.org/sub','dailyTime':'04:00'})
        with patch.object(s, 'set_cron') as cron: s.worker('disable')
        self.assertFalse(s.read(s.ROOT/'settings.json')['enabled']); self.assertEqual(s.snapshot(), self.originals)
        self.assertFalse(cron.call_args.args[0]['enabled'])

    def test_bad_download_does_not_apply_empty_config(self):
        request = s.ROOT/'request.json'; s.save(request, {'url':'https://example.org/sub','topN':2,'dailyTime':'04:00'})
        with patch.object(s,'require_core'), patch.object(s,'download', return_value=''), patch.object(s,'commit') as commit:
            with self.assertRaises(s.Failure): s.worker('configure', request)
        commit.assert_not_called(); self.assertEqual(s.snapshot(), self.originals)

    def test_inactive_core_does_not_download_or_restart(self):
        s.save(s.ROOT/'settings.json', {'enabled':True})
        with patch.object(s,'core_active', return_value=False), patch.object(s,'download') as download:
            with self.assertRaisesRegex(s.Failure, 'не активен'): s.worker('daily')
        download.assert_not_called()

    def test_update_failure_rolls_back_schedule(self):
        s.atomic(s.CRON, 'unrelated\n')
        request = s.ROOT/'request.json'; s.save(request, {'url':'https://example.org/sub','topN':2,'dailyTime':'04:00'})
        with patch.object(s,'require_core'), patch.object(s,'download', return_value=LINK), patch.object(s,'measure', return_value=selected()), \
             patch.object(s,'set_cron', side_effect=lambda _: s.atomic(s.CRON,'new\n')), patch.object(s,'commit',side_effect=s.Failure('invalid')), patch.object(s,'run'):
            with self.assertRaises(s.Failure): s.worker('configure',request)
        self.assertEqual(s.CRON.read_text(), 'unrelated\n'); self.assertFalse((s.ROOT/'settings.json').exists())

    def test_router_worker_updates_without_android(self):
        settings = {'url':'https://example.org/sub','topN':2,'dailyTime':'04:00','enabled':True}
        s.save(s.ROOT/'settings.json', settings)
        with patch.object(s,'require_core'), patch.object(s,'download',return_value=LINK), patch.object(s,'measure',return_value=selected()), \
             patch.object(s,'test_config'), patch.object(s,'restart',return_value=True): s.worker('daily')
        self.assertTrue((s.CONFIGS/s.MANAGED).exists()); self.assertEqual(s.status()['last_error'], '')
        self.assertTrue(s.status()['last_update'] > 0)

    def test_lock_rejects_overlap_and_recovers_when_owner_exits(self):
        fd=s.acquire()
        try:
            with self.assertRaises(s.Failure): s.acquire()
            self.assertTrue(s.status()['running'])
        finally: os.close(fd)
        fd=s.acquire(); os.close(fd)
        self.assertFalse(s.status()['running'])

    def test_ui_write_rejects_stale_snapshot_and_running_update(self):
        target=s.CONFIGS/'05_routing.json';stage=s.ROOT/'edit.json';s.atomic(stage,'{"routing":{"rules":[]}}')
        digest=hashlib.sha256(target.read_bytes()).hexdigest()
        with self.assertRaises(s.Failure): s.main(['write',str(target),str(stage),'stale'])
        self.assertEqual(target.read_bytes(),self.originals['05_routing.json'])
        fd=s.acquire()
        try:
            with self.assertRaises(s.Failure): s.main(['write',str(target),str(stage),digest])
        finally: os.close(fd)
        s.main(['write',str(target),str(stage),digest])
        self.assertEqual(target.read_bytes(),stage.read_bytes())

    def test_detached_job_and_lock_survive_launch_process_exit(self):
        # Execute real launch/Popen/pass_fds rather than mocking Android disconnection.
        wrapper = self.root/'wrapper.py'
        wrapper.write_text('import importlib.util,sys,time\nfrom pathlib import Path\n'
            + 'p=importlib.util.spec_from_file_location("sub",'+repr(str(SOURCE))+');s=importlib.util.module_from_spec(p);p.loader.exec_module(s)\n'
            + '\n'.join('s.'+k+'=Path('+repr(str(getattr(s,k)))+')' for k in ('ROOT','CONFIGS','CRON','LOCK'))+'\n'
            + 's.SCRIPT=Path(__file__)\n'
            + 'def work(*a):\n time.sleep(1.5)\n (s.ROOT/"finished").write_text("yes")\n'
            + 's.worker=work\ns.main(sys.argv[1:])\n')
        result = subprocess.run([sys.executable,str(wrapper),'launch','daily'],capture_output=True,text=True,timeout=5)
        self.assertEqual(result.returncode,0,result.stderr); self.assertIn('STARTED',result.stdout)
        self.assertTrue(s.status()['running'])
        deadline=time.monotonic()+6
        while time.monotonic()<deadline and not (s.ROOT/'finished').exists(): time.sleep(.1)
        self.assertTrue((s.ROOT/'finished').exists()); self.assertFalse(s.status()['running'])

class MeasurementTests(unittest.TestCase):
    def test_ranking_geo_and_healthy_small_pool_skip_benchmark(self):
        nodes=[node('one.example'),node('two.example')]
        class FakeProbe:
            speed_calls=0
            def __init__(self,_): pass
            def __enter__(self): return self
            def __exit__(self,*a): pass
            def health(self,n): return 20
            def speed(self,n): FakeProbe.speed_calls+=1; return (20,10) if n==nodes[0] else (10,30)
            def geo(self,n): return (n==nodes[0], 'OK' if n==nodes[0] else 'api.openai.com: 403')
        with patch.object(s,'Probe',FakeProbe):
            result=s.measure(nodes,1,{})
            self.assertEqual(result['best'][0],nodes[1]); self.assertEqual(result['geo_nodes'],[nodes[0]])
            previous=dict(result,best=[nodes[1]['key']],last_bench=time.time())
            count=FakeProbe.speed_calls
            self.assertIsNone(s.measure(nodes,1,previous,check=True)); self.assertEqual(count,FakeProbe.speed_calls)

    def test_no_geo_success_keeps_previous_verified_nodes(self):
        old=node('old.example'); current=node('current.example')
        class FakeProbe:
            def __init__(self,_): pass
            def __enter__(self): return self
            def __exit__(self,*a): pass
            def health(self,n): return 10
            def speed(self,n): return 20,10
            def geo(self,n): return False,'403'
        with patch.object(s,'Probe',FakeProbe):
            result=s.measure([current],1,{'geo_nodes':[old]})
        self.assertEqual(result['geo_nodes'],[old]); self.assertFalse(result['geo']['servers'][0]['ok'])


@unittest.skipUnless(os.environ.get('XRAY_TEST_BINARY'), 'Set XRAY_TEST_BINARY to test real Xray transport')
class NativeXrayTests(unittest.TestCase):
    def test_real_vless_transport_and_generated_config(self):
        import http.server
        import socket
        import threading
        binary = os.environ['XRAY_TEST_BINARY']
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                code = 401 if self.path == '/models' else 302 if self.path == '/redirect' else 200 if self.path == '/download' else 204
                self.send_response(code)
                if code == 302: self.send_header('Location','/models')
                self.end_headers()
                if self.path == '/download': self.wfile.write(b'x'*262144)
            def do_POST(self):
                self.rfile.read(int(self.headers.get('Content-Length',0)))
                self.send_response(200); self.end_headers()
            def log_message(self,*args): pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread = threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with socket.socket() as sock: sock.bind(('127.0.0.1',0)); port=sock.getsockname()[1]
            config={'log':{'loglevel':'none'},'inbounds':[{'listen':'127.0.0.1','port':port,'protocol':'vless',
                    'settings':{'clients':[{'id':'11111111-1111-1111-1111-111111111111'}],'decryption':'none'},
                    'streamSettings':{'network':'tcp','security':'none'}}], 'outbounds':[{'protocol':'freedom','settings':{'finalRules':[{'action':'allow','ip':['127.0.0.1/32']}]}}]}
            s.save(root/'server.json',config)
            process=subprocess.Popen([binary,'run','-config',str(root/'server.json')],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            try:
                time.sleep(.5)
                n=s.parse_link('vless://11111111-1111-1111-1111-111111111111@127.0.0.1:%d?type=tcp&security=none#Local'%port)
                with patch.dict(os.environ,{'PATH':str(Path(binary).parent)+os.pathsep+os.environ['PATH']}):
                    with s.Probe([n]) as probe:
                        code,fields=probe.curl(n,'http://127.0.0.1:%d/'%server.server_port)
                        self.assertEqual(code,0); self.assertEqual(fields[0],'204')
                        base='http://127.0.0.1:%d'%server.server_port
                        code,fields=probe.curl(n,base+'/download')
                        self.assertEqual(code,0); self.assertGreater(float(fields[2]),0)
                        upload=root/'upload';upload.write_bytes(b'x'*262144)
                        code,fields=probe.curl(n,base+'/upload',['--data-binary','@'+str(upload)])
                        self.assertEqual(code,0);self.assertGreater(float(fields[3]),0)
                        with patch.object(s,'GEO_CHECKS',[(base+'/models','401')]): self.assertTrue(probe.geo(n)[0])
                        with patch.object(s,'GEO_CHECKS',[(base+'/redirect','401')]): self.assertFalse(probe.geo(n)[0])
                originals={'04_outbounds.json':b'{"outbounds":[{"protocol":"freedom","tag":"direct"}]}',
                           '05_routing.json':b'{"routing":{"rules":[{"network":"tcp,udp","balancerTag":"proxy-balancer"}]}}'}
                directory_config=root/'configs'; directory_config.mkdir()
                for name,data in {**originals,**s.prepare(originals,{'best':[n],'geo_nodes':[n]},True)}.items(): (directory_config/name).write_bytes(data)
                result=subprocess.run([binary,'run','-test','-confdir',str(directory_config)],capture_output=True,text=True,timeout=20)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            finally:
                process.terminate(); process.wait(timeout=10)
                server.shutdown(); server.server_close()
