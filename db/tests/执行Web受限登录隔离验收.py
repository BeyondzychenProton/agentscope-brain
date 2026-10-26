"""#34: owned loopback MySQL + packaged Web app; synthetic identity only, no shared YAML.

Run from repository root after frontend build and backend package. --browser keeps the final
synthetic app alive until backend/target/issue34-browser-done exists (maximum 20 minutes).
"""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime, timezone
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
import urllib.parse
import urllib.request

import pymysql
import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('issue34_bootstrap_fixture', Path(__file__).with_name('执行初始化隔离验收.py'))
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

    def sql(statement, args=()):
        with c.cursor(pymysql.cursors.DictCursor) as cursor:
            cursor.execute(statement, args)
            return cursor.fetchall()

    def checked(name, condition):
        assert condition, name
        cases.append({'case': name, 'result': 'PASS'})

    with tempfile.TemporaryDirectory(prefix='issue34-web-', dir=ROOT / 'backend/target') as owned:
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
            config = {
                'spring': {'datasource': {'url': f'jdbc:mysql://127.0.0.1:{port}/memory_schema_probe?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC', 'username': 'root', 'password': ''},
                           'sql': {'init': {'mode': 'never'}}, 'session': {'jdbc': {'initialize-schema': 'never', 'cleanup-cron': '-', 'flush-mode': 'immediate'}}},
                'server': {'servlet': {'session': {'cookie': {'http-only': True, 'same-site': 'lax', 'path': '/'}}}},
                'brain': {'bootstrap': {'reference-catalog': (owned / 'catalog.json').as_uri()}, 'resources': {
                    'model': {'api-key': 'synthetic-not-a-secret', 'base-url': upstream_url, 'model': model['modelId']},
                    'minio': {'endpoint': upstream_url, 'access-key': 'synthetic', 'secret-key': 'synthetic'},
                    'meeting-mcp': {'endpoint': upstream_url, 'app-key': 'synthetic', 'secret': 'synthetic'}}}}
            config_path = owned / 'application.yaml'
            config_path.write_text(yaml.safe_dump(config), encoding='utf-8')
            web_port = free_port()
            origin = f'http://127.0.0.1:{web_port}'

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
                log = (ROOT / 'backend/target/issue34-isolated-app.log').open('w', encoding='utf-8')
                process = subprocess.Popen([str(java), '-jar', str(jar), f'--spring.config.location=optional:classpath:/,{config_path.as_uri()}',
                    f'--spring.profiles.active={profile}', f'--server.port={web_port}', '--server.address=127.0.0.1',
                    '--spring.main.banner-mode=off'], cwd=ROOT / 'backend', stdout=log, stderr=subprocess.STDOUT,
                    creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                deadline = time.monotonic() + 45
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise AssertionError('Packaged app exited; inspect backend/target/issue34-isolated-app.log')
                    try:
                        result = Client(origin).request('GET', '/readyz')
                        finished_check = expected_ready or 'Authentication readiness: REFUSED' in (ROOT / 'backend/target/issue34-isolated-app.log').read_text('utf-8', errors='replace')
                        if result[1] == {'ready': expected_ready} and finished_check:
                            return
                    except (OSError, urllib.error.URLError):
                        pass
                    time.sleep(.25)
                differences = []
                for line in (ROOT / 'backend/src/main/resources/readiness/structure-v1.tsv').read_text('utf-8').splitlines():
                    if not line or line.startswith('#'):
                        continue
                    table, query, expected = line.split('\t', 2)
                    actual = [str(next(iter(row.values()))) for row in sql(query.replace('?', '%s'), (table,))]
                    wanted = expected.split('~') if expected else []
                    if actual != wanted:
                        differences.append({'table': table, 'query': query, 'expected': wanted, 'actual': actual})
                (ROOT / 'backend/target/issue34-metadata-diff.json').write_text(json.dumps(differences, indent=2), encoding='utf-8')
                raise AssertionError('Readiness mismatch; inspect backend/target/issue34-isolated-app.log and metadata-diff.json')

            start()
            client = Client(origin)
            checked('11-01-anonymous-me-denied', client.request('GET', '/api/me')[0] == 401)
            checked('11-02-login-requires-csrf', client.request('POST', '/api/auth/login', {'phone': fixture['identity']['phone'], 'password': PASSWORD})[:2] == (403, {'code': 'CSRF_REJECTED'}))
            unknown = client.post('/api/auth/login', {'phone': '19900000000', 'password': PASSWORD})
            incorrect = client.post('/api/auth/login', {'phone': fixture['identity']['phone'], 'password': 'incorrect-password'})
            checked('11-03-unknown-and-wrong-password-identical', unknown[:2] == incorrect[:2] == (401, {'code': 'AUTHENTICATION_FAILED'}))
            csrf_status, csrf_before, _, _ = client.request('GET', '/api/auth/csrf')
            before_cookie = [x.value for x in client.jar]
            status, auth, headers, _ = client.post('/api/auth/login', {'phone': fixture['identity']['phone'], 'password': PASSWORD, 'userId': '123', 'role': 'ADMIN', 'owner': '123'})
            checked('11-04-real-http-login-server-selected-user', status == 200 and auth['authStage'] == 'PASSWORD_CHANGE_REQUIRED' and auth['userId'] == '1')
            checked('11-05-cookie-no-cache-and-fixation', 'HttpOnly' in headers.get('Set-Cookie', '') and 'SameSite=Lax' in headers.get('Set-Cookie', '') and 'Path=/' in headers.get('Set-Cookie', '') and 'Domain=' not in headers.get('Set-Cookie', '') and 'Secure' not in headers.get('Set-Cookie', '') and 'no-store' in headers.get('Cache-Control', '') and before_cookie != [x.value for x in client.jar])
            checked('11-06-csrf-rotated-after-login', client.request('POST', '/api/auth/logout', headers={csrf_before['headerName']: csrf_before['token']})[:2] == (403, {'code': 'CSRF_REJECTED'}))
            me = client.request('GET', '/api/me?userId=123&phone=19900000000&owner=123')[1]
            checked('11-07-minimal-trusted-me', me['userId'] == '1' and me['employees'] == [] and 'phone' not in me and me['authStage'] == 'PASSWORD_CHANGE_REQUIRED')
            for path in ['/api/sessions', '/api/media/1/content', '/api/sessions/1/events', '/api/employees/1/capabilities']:
                checked('11-08-restricted-read:' + path, client.request('GET', path)[:2] == (403, {'code': 'PASSWORD_CHANGE_REQUIRED'}))
            checked('11-09-restricted-run-create', client.post('/api/session-intents', {'employeeId': '1'})[:2] == (403, {'code': 'PASSWORD_CHANGE_REQUIRED'}))
            rows = sql('SELECT COUNT(*) n FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME=%s', ('SPRING_SECURITY_CONTEXT',))
            checked('11-10-context-persisted-in-mysql', rows[0]['n'] == 1)
            start()
            restored = client.request('GET', '/api/me')
            checked('11-11-jvm-restart-restores-original-session-deadline', restored[0] == 200 and restored[1]['authenticatedAt'] == auth['authenticatedAt'] and restored[1]['absoluteExpiresAt'] == auth['absoluteExpiresAt'])
            for route in ['/login', '/password/initial', '/workbench']:
                checked('11-12-production-html:' + route, client.request('GET', route)[0] == 200)
            sql('RENAME TABLE user_credential TO issue34_credential_unavailable')
            checked('11-13-missing-auth-data-is-unavailable', client.request('GET', '/api/me')[:2] == (503, {'code': 'AUTH_DATA_UNAVAILABLE'}))
            checked('11-14-login-data-failure-is-not-password-error', Client(origin).post('/api/auth/login', {'phone': fixture['identity']['phone'], 'password': PASSWORD})[:2] == (503, {'code': 'AUTH_DATA_UNAVAILABLE'}))
            sql('RENAME TABLE issue34_credential_unavailable TO user_credential')
            sql('RENAME TABLE SPRING_SESSION_ATTRIBUTES TO issue34_session_unavailable')
            checked('11-15-session-infrastructure-failure-controlled', client.request('GET', '/api/me')[:2] == (503, {'code': 'AUTH_DATA_UNAVAILABLE'}))
            sql('RENAME TABLE issue34_session_unavailable TO SPRING_SESSION_ATTRIBUTES')
            sql("UPDATE platform_user SET security_version=security_version+1,modified_by_user_id=1,modification_source_kind='USER_ACTION',modification_bootstrap_batch_id=NULL WHERE id=1")
            checked('11-16-security-version-revokes-old-context', client.request('GET', '/api/me')[0] == 401)
            current = Client(origin)
            current.post('/api/auth/login', {'phone': fixture['identity']['phone'], 'password': PASSWORD})
            checked('11-17-logout-clears-current-context', current.post('/api/auth/logout')[0] == 204 and current.request('GET', '/api/me')[0] == 401)
            mutations = [
                ('platform-index', 'ALTER TABLE user_credential DROP INDEX uk_user_id', 'ALTER TABLE user_credential ADD UNIQUE KEY uk_user_id(user_id)'),
                ('sdk-column', 'ALTER TABLE agentscope_sessions MODIFY state_data TEXT NOT NULL', 'ALTER TABLE agentscope_sessions MODIFY state_data LONGTEXT NOT NULL'),
                ('session-table', 'RENAME TABLE SPRING_SESSION TO issue34_session_missing', 'RENAME TABLE issue34_session_missing TO SPRING_SESSION'),
                ('bootstrap-state', "UPDATE bootstrap_batch SET status='FAILED'", "UPDATE bootstrap_batch SET status='APPLIED'"),
                ('skill-resource', "UPDATE employee_release_skill_resource SET resource_content=CONCAT(resource_content,'corrupt') WHERE id=1", None),
            ]
            original = sql('SELECT resource_content FROM employee_release_skill_resource WHERE id=1')[0]['resource_content']
            for name, mutation, restore in mutations:
                stop(); sql(mutation); start(False)
                checked('11-18-startup-refuses-' + name, Client(origin).request('GET', '/api/auth/csrf')[:2] == (503, {'code': 'APPLICATION_NOT_READY'}))
                stop()
                if restore:
                    sql(restore)
                else:
                    sql('UPDATE employee_release_skill_resource SET resource_content=%s WHERE id=1', (original,))
            sql("UPDATE platform_user SET account_status='DISABLED' WHERE id=1")
            sql("UPDATE employee_use_grant SET grant_status='REVOKED',revoked_by=1,revoked_at=UTC_TIMESTAMP(6)")
            sql("UPDATE employee_release SET availability_status='DISABLED',availability_changed_by=1,availability_source_kind='USER_ACTION',availability_bootstrap_batch_id=NULL")
            start()
            checked('11-19-legal-policy-state-does-not-break-readiness', Client(origin).request('GET', '/readyz')[:2] == (200, {'ready': True}))
            checked('11-20-disabled-user-cannot-login', Client(origin).post('/api/auth/login', {'phone': fixture['identity']['phone'], 'password': PASSWORD})[0] == 401)
            stop()
            sql("UPDATE platform_user SET account_status='ACTIVE' WHERE id=1")
            start(True, 'prod')
            prod = Client(origin).request('GET', '/api/auth/csrf')
            checked('11-21-production-host-cookie', '__Host-ASSESSION=' in prod[2].get('Set-Cookie', '') and 'Secure' in prod[2].get('Set-Cookie', '') and 'HttpOnly' in prod[2].get('Set-Cookie', '') and 'SameSite=Lax' in prod[2].get('Set-Cookie', '') and 'Domain=' not in prod[2].get('Set-Cookie', ''))
            stop()
            sql("UPDATE employee_use_grant SET grant_status='ACTIVE',revoked_by=NULL,revoked_at=NULL")
            sql("UPDATE employee_release SET availability_status='AVAILABLE'")
            start()
            if browser:
                done = ROOT / 'backend/target/issue34-browser-done'
                done.unlink(missing_ok=True)
                (ROOT / 'backend/target/issue34-browser-ready.json').write_text(json.dumps({'origin': origin, 'synthetic': True}), encoding='utf-8')
                print('BROWSER_READY ' + origin, flush=True)
                deadline = time.monotonic() + 1200
                while not done.exists() and time.monotonic() < deadline:
                    time.sleep(1)
                checked('11-22-browser-validation-completed', done.exists())
            checked('11-23-no-model-mcp-minio-probes', len(calls) == 0)
            checked('11-24-no-business-or-credential-mutation', sql('SELECT COUNT(*) n FROM business_run')[0]['n'] == 0 and sql('SELECT is_password_change_required n FROM user_credential')[0]['n'] == 1)
            return {'cases': cases, 'upstreamCalls': len(calls), 'packagedJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'sharedDatasetUsed': False}
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
    # The fixture intentionally commits for process-restart tests; cleanup removes the owned
    # instance rather than rolling its rows back. Do not inherit the SQL-only driver's claim.
    summary.pop('synthetic_rows_rolled_back', None)
    summary.update({'issue': 34, 'executedAt': datetime.now(timezone.utc).isoformat(), 'temporaryInstanceRemoved': True,
                    'platformTables': 30, 'nativeTables': 5, 'runtimeTotalTables': 35,
                    'application_integration_tested': True, 'syntheticDataRemovedWithOwnedInstance': True,
                    'realAcceptance': 'Tracked separately in verification/issue-34/real-login.json; this run uses only synthetic data'})
    destination = ROOT / 'verification/issue-34/isolated-login.json'
    destination.parent.mkdir(exist_ok=True)
    destination.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"PASS: {len(summary['cases'])} Web/JDBC/startup checks; owned MySQL removed")


if __name__ == '__main__':
    main()
