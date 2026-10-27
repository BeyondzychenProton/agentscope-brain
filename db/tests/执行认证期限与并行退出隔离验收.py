"""#36: owned loopback MySQL + packaged Web app; fixed 24h deadline, parallel browsers, current logout.

Run from repository root after frontend build and backend package. Uses brain.auth.clock-offset to
cover absolute deadline boundaries without shortening Duration.ofHours(24). --browser keeps the
final synthetic app alive until backend/target/issue36-browser-done exists (maximum 20 minutes).
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import http.cookiejar
import http.server
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

import pymysql
import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('issue36_bootstrap_fixture', Path(__file__).with_name('执行初始化隔离验收.py'))
fixture_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture_module)
base = fixture_module.base
PASSWORD = 'synthetic-bootstrap-29'


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class Client:
    def __init__(self, origin):
        self.origin = origin
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def cookie_values(self, name_substring='ASSESSION'):
        return [cookie.value for cookie in self.jar if name_substring in cookie.name]

    def request(self, method, path, body=None, headers=None):
        data = None if body is None else json.dumps(body).encode()
        headers = dict(headers or {})
        if data is not None:
            headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(self.origin + path, data=data, headers=headers, method=method)
        try:
            response = self.opener.open(req, timeout=15)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            raw = response.read()
            try:
                value = json.loads(raw) if raw else None
            except (ValueError, UnicodeError):
                value = None
            return response.status, value, response.headers, raw

    def post(self, path, body=None):
        status, token, _, _ = self.request('GET', '/api/auth/csrf')
        assert status == 200, ('csrf', status, token)
        return self.request('POST', path, body, {token['headerName']: token['token']})


def run_isolated(mysql_client, browser):
    port = int(next(x.split('=', 1)[1] for x in mysql_client if x.startswith('--port=')))
    c = pymysql.connect(host='127.0.0.1', port=port, user='root', password='', database='memory_schema_probe',
                        charset='utf8mb4', autocommit=False)
    calls = []

    class UnavailableService(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            calls.append(self.path)
            self.send_response(503)
            self.end_headers()
        do_POST = do_GET
        def log_message(self, *_):
            pass

    upstream = http.server.ThreadingHTTPServer(('127.0.0.1', 0), UnavailableService)
    upstream_thread = threading.Thread(target=upstream.serve_forever, daemon=True)
    upstream_thread.start()
    upstream_url = f'http://127.0.0.1:{upstream.server_port}'
    java = Path('D:/code environment/jdk/jdk-17.0.9/bin/java.exe')
    jar = ROOT / 'backend/target/agentscope-brain-backend-0.1.0-SNAPSHOT.jar'
    cases = []
    process = None
    log = None
    clock_instant = ''
    clock_offset = 'PT0S'

    def sql(statement, args=()):
        with c.cursor(pymysql.cursors.DictCursor) as cursor:
            cursor.execute(statement, args)
            return cursor.fetchall()

    def checked(name, condition):
        assert condition, name
        cases.append({'case': name, 'result': 'PASS'})

    with tempfile.TemporaryDirectory(prefix='issue36-web-', dir=ROOT / 'backend/target') as owned:
        owned = Path(owned)
        try:
            for ddl in sorted((ROOT / 'db/vendor').glob('*.sql')):
                base.require_success(base.run(mysql_client, sql=ddl.read_bytes()), 'native structure')
            fixture = fixture_module.fixture()
            fixture['employee']['model']['parameters'] = {}
            fixture_module.boot.apply(c, fixture)
            c.autocommit(True)
            model = fixture['employee']['model']
            catalog = {}

            def reference(ref, kind, content):
                catalog[ref] = {'kind': kind, 'content': content, 'sha256': fixture_module.boot.digest(content).hex()}

            for tool in fixture['employee']['tools']:
                for key, value in tool['binding'].items():
                    if key.endswith('Ref'):
                        reference(value, 'synthetic-contract', {})
            reference(model['configRef'], 'model', model)
            reference(model['endpointRef'], 'model-endpoint', {'baseUrl': upstream_url})
            reference(model['credentialRef'], 'credential-reference', {'configurationPath': 'brain.resources.model.api-key'})
            reference('session-isolated/v1', 'workspace-policy', {})
            reference('effective-generation/v1', 'memory-policy', {})
            reference('synthetic-mcp/v1', 'mcp-target', {'endpoint': upstream_url})
            (owned / 'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False), encoding='utf-8')
            config_path = owned / 'application.yaml'
            web_port = free_port()
            origin = f'http://127.0.0.1:{web_port}'

            def write_config():
                auth_clock = {'clock-offset': clock_offset}
                if clock_instant:
                    auth_clock['clock-instant'] = clock_instant
                config = {
                    'spring': {'datasource': {'url': f'jdbc:mysql://127.0.0.1:{port}/memory_schema_probe?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC', 'username': 'root', 'password': ''},
                               'sql': {'init': {'mode': 'never'}}, 'session': {'timeout': '24h', 'jdbc': {'initialize-schema': 'never', 'cleanup-cron': '-', 'flush-mode': 'immediate'}}},
                    'server': {'servlet': {'session': {'cookie': {'http-only': True, 'same-site': 'lax', 'path': '/'}}}},
                    'brain': {'auth': auth_clock, 'bootstrap': {'reference-catalog': (owned / 'catalog.json').as_uri()}, 'resources': {
                        'model': {'api-key': 'synthetic-not-a-secret', 'base-url': upstream_url, 'model': model['modelId']},
                        'minio': {'endpoint': upstream_url, 'access-key': 'synthetic', 'secret-key': 'synthetic'},
                        'meeting-mcp': {'endpoint': upstream_url, 'app-key': 'synthetic', 'secret': 'synthetic'}}}}
                config_path.write_text(yaml.safe_dump(config), encoding='utf-8')

            def stop():
                nonlocal process, log
                if process is not None and process.poll() is None:
                    process.terminate()
                    process.wait(timeout=20)
                if log is not None:
                    log.close()

            def start(expected_ready=True, profile='dev'):
                nonlocal process, log
                stop()
                write_config()
                log = (ROOT / 'backend/target/issue36-isolated-app.log').open('w', encoding='utf-8')
                process = subprocess.Popen([str(java), '-jar', str(jar), f'--spring.config.location=optional:classpath:/,{config_path.as_uri()}',
                    f'--spring.profiles.active={profile}', f'--server.port={web_port}', '--server.address=127.0.0.1',
                    '--spring.main.banner-mode=off'], cwd=ROOT / 'backend', stdout=log, stderr=subprocess.STDOUT,
                    creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                deadline = time.monotonic() + 45
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise AssertionError('Packaged app exited; inspect backend/target/issue36-isolated-app.log')
                    try:
                        result = Client(origin).request('GET', '/readyz')
                        if result[1] == {'ready': expected_ready}:
                            return
                    except (OSError, urllib.error.URLError):
                        pass
                    time.sleep(.25)
                raise AssertionError('Readiness mismatch; inspect backend/target/issue36-isolated-app.log')

            def login(password=PASSWORD):
                client = Client(origin)
                result = client.post('/api/auth/login', {'phone': fixture['identity']['phone'], 'password': password})
                assert result[0] == 200, ('login', result[:2])
                return client, result[1]

            def restart_with_instant(instant):
                nonlocal clock_instant, clock_offset
                clock_instant = instant
                clock_offset = 'PT0S'
                start()

            def clear_clock():
                nonlocal clock_instant, clock_offset
                clock_instant = ''
                clock_offset = 'PT0S'

            start()
            client, auth = login()
            checked('13-01-login-records-authenticated-and-absolute-deadline',
                    auth['authenticatedAt'] and auth['absoluteExpiresAt']
                    and auth['absoluteExpiresAt'] > auth['authenticatedAt'])
            original = {k: auth[k] for k in ('authenticatedAt', 'absoluteExpiresAt')}
            for _ in range(3):
                me = client.request('GET', '/api/me')
                csrf = client.request('GET', '/api/auth/csrf')
                checked('13-02-protected-read-and-csrf-keep-absolute-deadline',
                        me[0] == 200 and csrf[0] == 200
                        and me[1]['authenticatedAt'] == original['authenticatedAt']
                        and me[1]['absoluteExpiresAt'] == original['absoluteExpiresAt'])
            cookie_before = client.cookie_values()
            # Restricted sessions may rotate only through trusted writes; password change is out of
            # this ticket. Prove Spring Session idle access does not rewrite the principal deadlines.
            attributes_before = sql("SELECT ATTRIBUTE_BYTES FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME='SPRING_SECURITY_CONTEXT'")
            time.sleep(1)
            after_idle = client.request('GET', '/api/me')
            attributes_after = sql("SELECT ATTRIBUTE_BYTES FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME='SPRING_SECURITY_CONTEXT'")
            checked('13-03-activity-does-not-rewrite-persisted-absolute-deadline',
                    after_idle[0] == 200
                    and after_idle[1]['authenticatedAt'] == original['authenticatedAt']
                    and after_idle[1]['absoluteExpiresAt'] == original['absoluteExpiresAt']
                    and attributes_before[0]['ATTRIBUTE_BYTES'] == attributes_after[0]['ATTRIBUTE_BYTES']
                    and cookie_before == client.cookie_values())

            first, first_auth = login()
            second, second_auth = login()
            checked('13-04-two-independent-browsers-both-valid',
                    first.request('GET', '/api/me')[0] == 200 and second.request('GET', '/api/me')[0] == 200
                    and first.cookie_values() != second.cookie_values()
                    and first_auth['userId'] == second_auth['userId'] == '1')
            same_browser_tab = Client(origin)
            same_browser_tab.jar = first.jar
            same_browser_tab.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(same_browser_tab.jar))
            checked('13-05-same-browser-tabs-share-current-session',
                    same_browser_tab.request('GET', '/api/me')[0] == 200
                    and same_browser_tab.cookie_values() == first.cookie_values())
            logout_csrf = first.request('GET', '/api/auth/csrf')[1]
            checked('13-06-logout-requires-csrf',
                    first.request('POST', '/api/auth/logout', headers={logout_csrf['headerName']: 'forged'})[:2]
                    == (403, {'code': 'CSRF_REJECTED'}))
            checked('13-07-logout-only-current-session',
                    first.post('/api/auth/logout')[0] == 204
                    and first.request('GET', '/api/me')[0] == 401
                    and second.request('GET', '/api/me')[0] == 200
                    and second.request('GET', '/api/me')[1]['absoluteExpiresAt'] == second_auth['absoluteExpiresAt'])
            checked('13-08-logout-does-not-create-run-or-meeting-cancel',
                    sql('SELECT COUNT(*) n FROM business_run')[0]['n'] == 0 and len(calls) == 0)

            sql('DELETE FROM SPRING_SESSION_ATTRIBUTES'); sql('DELETE FROM SPRING_SESSION')
            clear_clock()
            start()
            def pin(instant):
                return instant if instant.endswith('Z') else instant.replace('+00:00', 'Z')

            def shift(instant, seconds):
                value = datetime.fromisoformat(instant.replace('Z', '+00:00')) + timedelta(seconds=seconds)
                return pin(value.isoformat())

            active, active_auth = login()
            restart_with_instant(shift(active_auth['absoluteExpiresAt'], -1))
            still = active.request('GET', '/api/me')
            checked('13-09-controlled-clock-just-before-deadline-still-valid',
                    still[0] == 200
                    and still[1]['authenticatedAt'] == active_auth['authenticatedAt']
                    and still[1]['absoluteExpiresAt'] == active_auth['absoluteExpiresAt'])
            restart_with_instant(pin(active_auth['absoluteExpiresAt']))
            at_expiry = active.request('GET', '/api/me')
            checked('13-10-controlled-clock-exact-deadline-rejects',
                    at_expiry[:2] == (401, {'code': 'AUTHENTICATION_REQUIRED'}))
            expired_sessions = sql('SELECT COUNT(*) n FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME=%s',
                                   ('SPRING_SECURITY_CONTEXT',))
            checked('13-11-expiry-clears-current-security-context', expired_sessions[0]['n'] == 0)
            sql('DELETE FROM SPRING_SESSION_ATTRIBUTES'); sql('DELETE FROM SPRING_SESSION')
            clear_clock()
            start()
            other, other_auth = login()
            restart_with_instant(shift(other_auth['absoluteExpiresAt'], 1))
            checked('13-12-controlled-clock-after-deadline-rejects',
                    other.request('GET', '/api/me')[:2] == (401, {'code': 'AUTHENTICATION_REQUIRED'}))
            checked('13-13-expiry-does-not-create-run-or-meeting-cancel',
                    sql('SELECT COUNT(*) n FROM business_run')[0]['n'] == 0 and len(calls) == 0)

            clear_clock()
            start(True, 'prod')
            # Secure __Host- cookies are asserted from Set-Cookie. Full HTTPS login stays for target deploy.
            prod_csrf = Client(origin).request('GET', '/api/auth/csrf')
            checked('13-14-production-cookie-contract',
                    '__Host-ASSESSION=' in prod_csrf[2].get('Set-Cookie', '')
                    and 'Secure' in prod_csrf[2].get('Set-Cookie', '')
                    and 'HttpOnly' in prod_csrf[2].get('Set-Cookie', '')
                    and 'SameSite=Lax' in prod_csrf[2].get('Set-Cookie', '')
                    and 'Domain=' not in prod_csrf[2].get('Set-Cookie', '')
                    and 'Path=/' in prod_csrf[2].get('Set-Cookie', ''))
            stop()
            start()
            anonymous = Client(origin)
            before_login = anonymous.request('GET', '/api/auth/csrf')
            status, auth, login_headers, _ = anonymous.post('/api/auth/login', {
                'phone': fixture['identity']['phone'], 'password': PASSWORD})
            assert status == 200, ('dev-login', status, auth)
            cookie_header = login_headers.get('Set-Cookie', '') or before_login[2].get('Set-Cookie', '')
            checked('13-15-development-cookie-separated',
                    'ASSESSION_DEV=' in cookie_header
                    and 'Secure' not in cookie_header
                    and 'HttpOnly' in cookie_header
                    and 'SameSite=Lax' in cookie_header
                    and 'Path=/' in cookie_header
                    and 'Domain=' not in cookie_header
                    and '__Host-ASSESSION=' not in cookie_header
                    and any(cookie.name == 'ASSESSION_DEV' for cookie in anonymous.jar))
            packaged_prod = (ROOT / 'backend/src/main/resources/application-prod.yaml').read_text('utf-8')
            packaged_dev = (ROOT / 'backend/src/main/resources/application-dev.yaml').read_text('utf-8')
            checked('13-16-packaged-cookie-profiles-remain-separated',
                    '__Host-ASSESSION' in packaged_prod and 'secure: true' in packaged_prod
                    and 'ASSESSION_DEV' in packaged_dev and 'secure: false' in packaged_dev
                    and '__Host-ASSESSION' not in packaged_dev and 'ASSESSION_DEV' not in packaged_prod)

            if browser:
                done = ROOT / 'backend/target/issue36-browser-done'
                done.unlink(missing_ok=True)
                (ROOT / 'backend/target/issue36-browser-ready.json').write_text(
                    json.dumps({'origin': origin, 'synthetic': True, 'phone': fixture['identity']['phone']}, ensure_ascii=False),
                    encoding='utf-8')
                print('BROWSER_READY ' + origin, flush=True)
                deadline = time.monotonic() + 1200
                while not done.exists() and time.monotonic() < deadline:
                    time.sleep(1)
                checked('13-17-browser-observation-completed', done.exists())
            checked('13-18-no-external-or-business-execution', len(calls) == 0 and sql('SELECT COUNT(*) n FROM business_run')[0]['n'] == 0)
            checked('13-19-production-duration-rule-unchanged',
                    'Duration.ofHours(24)' in (ROOT / 'backend/src/main/java/com/agentscope/brain/identity/internal/application/AuthenticationService.java').read_text('utf-8'))
            return {'cases': cases, 'upstreamCalls': len(calls), 'packagedJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                    'sharedDatasetUsed': False, 'clockInstantsExercised': ['just-before', 'exact', 'after']}
        finally:
            if process is not None and process.poll() is None:
                process.terminate(); process.wait(timeout=20)
            if log is not None:
                log.close()
            upstream.shutdown(); upstream.server_close(); c.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-bin', type=Path, default=Path('D:/code environment/mysql/mysql-8.0.45-winx64/bin'))
    parser.add_argument('--browser', action='store_true')
    args = parser.parse_args()
    summary = base.verify(args.mysql_bin.resolve(), ddl_files=fixture_module.DDL, probes=[], expected_table_count=30,
        inspection_check=lambda _: 0, concurrency_check=lambda _: 0, extra_checks=lambda client: run_isolated(client, args.browser))
    summary.pop('synthetic_rows_rolled_back', None)
    summary.update({'issue': 36, 'executedAt': datetime.now(timezone.utc).isoformat(), 'temporaryInstanceRemoved': True,
                    'platformTables': 30, 'nativeTables': 5, 'runtimeTotalTables': 35,
                    'application_integration_tested': True, 'syntheticDataRemovedWithOwnedInstance': True,
                    'realAcceptance': 'Pending shared-dataset browser parallel login/logout; this run uses synthetic data only'})
    destination = ROOT / 'verification/issue-36/isolated-deadline.json'
    destination.parent.mkdir(exist_ok=True)
    destination.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"PASS: {len(summary['cases'])} deadline/parallel/logout checks; owned MySQL removed")


if __name__ == '__main__':
    main()
