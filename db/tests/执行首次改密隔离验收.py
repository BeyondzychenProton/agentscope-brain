"""#35: owned loopback MySQL + packaged Web app; synthetic identity only, no shared YAML.

Run from repository root after frontend build and backend package. --browser keeps the final
synthetic app alive until backend/target/issue35-browser-done exists (maximum 20 minutes).
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import bcrypt
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
spec = importlib.util.spec_from_file_location('issue35_bootstrap_fixture', Path(__file__).with_name('执行初始化隔离验收.py'))
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

    with tempfile.TemporaryDirectory(prefix='issue35-web-', dir=ROOT / 'backend/target') as owned:
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
                log = (ROOT / 'backend/target/issue35-isolated-app.log').open('w', encoding='utf-8')
                process = subprocess.Popen([str(java), '-jar', str(jar), f'--spring.config.location=optional:classpath:/,{config_path.as_uri()}',
                    f'--spring.profiles.active={profile}', f'--server.port={web_port}', '--server.address=127.0.0.1',
                    '--spring.main.banner-mode=off'], cwd=ROOT / 'backend', stdout=log, stderr=subprocess.STDOUT,
                    creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                deadline = time.monotonic() + 45
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise AssertionError('Packaged app exited; inspect backend/target/issue35-isolated-app.log')
                    try:
                        result = Client(origin).request('GET', '/readyz')
                        finished_check = expected_ready or 'Authentication readiness: REFUSED' in (ROOT / 'backend/target/issue35-isolated-app.log').read_text('utf-8', errors='replace')
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
                (ROOT / 'backend/target/issue35-metadata-diff.json').write_text(json.dumps(differences, indent=2), encoding='utf-8')
                raise AssertionError('Readiness mismatch; inspect backend/target/issue35-isolated-app.log and metadata-diff.json')

            initial_hash = fixture['identity']['passwordHash']
            next_password = '中' * 24
            def reset_synthetic():
                # Owned disposable instance only. Never use this reset against shared credentials.
                sql('DELETE FROM SPRING_SESSION_ATTRIBUTES'); sql('DELETE FROM SPRING_SESSION')
                sql("DELETE FROM identity_audit_event WHERE action_code='INITIAL_PASSWORD_CHANGE'")
                sql("UPDATE user_credential SET password_hash=%s,credential_version=1,is_password_change_required=1,password_changed_at=NULL", (initial_hash,))
                sql("UPDATE platform_user SET security_version=1,modified_by_user_id=1,modification_source_kind='USER_ACTION',modification_bootstrap_batch_id=NULL")

            def login(password=PASSWORD):
                client = Client(origin)
                result = client.post('/api/auth/login', {'phone': fixture['identity']['phone'], 'password': password})
                assert result[0] == 200, ('login', result[:2])
                return client, result[1]

            def facts():
                return sql('SELECT u.security_version,c.credential_version,c.is_password_change_required,c.password_hash,c.password_changed_at FROM platform_user u JOIN user_credential c ON c.user_id=u.id WHERE u.id=1')[0]

            def audits():
                return sql("SELECT actor_user_id,target_user_id,source_kind,outcome,request_id FROM identity_audit_event WHERE action_code='INITIAL_PASSWORD_CHANGE'")

            start()
            client, auth = login()
            old, _ = login()
            checked('12-01-csrf-required', client.request('POST', '/api/auth/password/initial', {'currentPassword': PASSWORD, 'newPassword': next_password})[:2] == (403, {'code': 'CSRF_REJECTED'}))
            checked('12-02-current-password-rejected', client.post('/api/auth/password/initial', {'currentPassword': 'incorrect-password', 'newPassword': next_password})[:2] == (422, {'code': 'CURRENT_PASSWORD_INVALID'}))
            for index, value in enumerate(['a' * 11, 'a' * 73, '中' * 25, '😀' * 11, '\ud800' * 12]):
                checked(f'12-03-unicode-boundary-{index}', client.post('/api/auth/password/initial', {'currentPassword': PASSWORD, 'newPassword': value})[:2] == (422, {'code': 'NEW_PASSWORD_INVALID'}))
            checked('12-04-invalid-inputs-did-not-mutate', facts()['credential_version'] == 1 and len(audits()) == 0)
            csrf_before = client.request('GET', '/api/auth/csrf')[1]
            cookie_before = [x.value for x in client.jar]
            result = client.post('/api/auth/password/initial', {'currentPassword': PASSWORD, 'newPassword': next_password, 'userId': '999', 'securityVersion': 999})
            checked('12-05-change-ready-preserves-authentication-and-absolute-deadline', result[0] == 200 and result[1]['authStage'] == 'READY' and result[1]['userId'] == '1' and all(result[1][k] == auth[k] for k in ['authenticatedAt', 'absoluteExpiresAt']))
            checked('12-06-session-id-rotated-and-no-store', cookie_before != [x.value for x in client.jar] and 'no-store' in result[2].get('Cache-Control', ''))
            checked('12-07-old-csrf-rejected', client.request('POST', '/api/auth/password/initial', {'currentPassword': next_password, 'newPassword': 'another-valid-password'}, {csrf_before['headerName']: csrf_before['token']})[:2] == (403, {'code': 'CSRF_REJECTED'}))
            actual = facts()
            checked('12-08-platform-credential-security-audit-atomic', actual['security_version'] == actual['credential_version'] == 2 and actual['is_password_change_required'] == 0 and actual['password_changed_at'] is not None and actual['password_hash'].startswith('{bcrypt}$2a$12$') and bcrypt.checkpw(next_password.encode(), actual['password_hash'][8:].encode()) and len(audits()) == 1 and audits()[0]['source_kind'] == 'USER_ACTION')
            checked('12-09-old-security-context-server-rejected', old.request('GET', '/api/me')[0] == 401)
            checked('12-10-old-password-cannot-login', Client(origin).post('/api/auth/login', {'phone': fixture['identity']['phone'], 'password': PASSWORD})[:2] == (401, {'code': 'AUTHENTICATION_FAILED'}))
            checked('12-11-ready-context-cannot-repeat-initial-change', client.post('/api/auth/password/initial', {'currentPassword': next_password, 'newPassword': 'another-valid-password'})[:2] == (409, {'code': 'INITIAL_PASSWORD_CHANGE_NOT_REQUIRED'}))
            checked('12-12-session-jdbc-context-persisted', sql("SELECT COUNT(*) n FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME='SPRING_SECURITY_CONTEXT'")[0]['n'] == 1)
            attributes = sql('SELECT ATTRIBUTE_BYTES FROM SPRING_SESSION_ATTRIBUTES')
            checked('12-12-passwords-not-in-session-attributes', all(PASSWORD.encode() not in row['ATTRIBUTE_BYTES'] and next_password.encode() not in row['ATTRIBUTE_BYTES'] for row in attributes))
            start()
            restored = client.request('GET', '/api/me')
            checked('12-13-process-restart-retains-ready-and-original-deadline', restored[0] == 200 and restored[1]['authStage'] == 'READY' and all(restored[1][k] == auth[k] for k in ['authenticatedAt', 'absoluteExpiresAt']))
            before_repeat = facts()
            c.autocommit(False)
            fixture_module.boot.apply(c, fixture)
            c.autocommit(True)
            checked('12-14-repeated-bootstrap-does-not-restore-initial-credential', before_repeat == facts() and len(audits()) == 1)
            checked('12-15-ready-still-no-business-running', client.request('GET', '/api/sessions')[0] == 403 and sql('SELECT COUNT(*) n FROM business_run')[0]['n'] == 0)

            reset_synthetic()
            a, _ = login(); b, _ = login()
            with ThreadPoolExecutor(max_workers=2) as pool:
                outcomes = list(pool.map(lambda pair: pair[0].post('/api/auth/password/initial', {'currentPassword': PASSWORD, 'newPassword': pair[1]})[0], [(a, 'concurrent-password-a'), (b, 'concurrent-password-b')]))
            checked('12-16-concurrent-only-one-commit', sorted(outcomes) == [200, 401] and facts()['credential_version'] == 2 and len(audits()) == 1)

            reset_synthetic()
            client, _ = login()
            # Real Session JDBC attribute-save failure only after platform credential commit.
            for operation in ['INSERT', 'UPDATE']:
                sql(f"CREATE TRIGGER issue35_session_{operation.lower()} BEFORE {operation} ON SPRING_SESSION_ATTRIBUTES FOR EACH ROW BEGIN IF NEW.ATTRIBUTE_NAME='SPRING_SECURITY_CONTEXT' AND (SELECT is_password_change_required FROM user_credential WHERE user_id=1)=0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic session save failure'; END IF; END")
            result = client.post('/api/auth/password/initial', {'currentPassword': PASSWORD, 'newPassword': next_password})
            checked('12-17-sql-committed-session-jdbc-failed-distinct-result', result[:2] == (503, {'code': 'PASSWORD_CHANGED_RELOGIN_REQUIRED'}))
            checked('12-18-partial-success-expires-cookie-and-keeps-password', 'Max-Age=0' in ''.join(result[2].get_all('Set-Cookie', [])) and facts()['credential_version'] == 2 and len(audits()) == 1 and client.request('GET', '/api/me')[0] == 401)
            for operation in ['insert', 'update']:
                sql(f'DROP TRIGGER issue35_session_{operation}')
            current, _ = login(next_password)
            checked('12-19-partial-success-can-login-with-new-password', current.request('GET', '/api/me')[1]['authStage'] == 'READY')

            reset_synthetic()
            client, _ = login()
            sql("CREATE TRIGGER issue35_audit BEFORE INSERT ON identity_audit_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic audit unavailable'")
            result = client.post('/api/auth/password/initial', {'currentPassword': PASSWORD, 'newPassword': next_password})
            checked('12-20-sql-failure-not-proven-returns-uncertainty', result[:2] == (503, {'code': 'AUTH_OUTCOME_UNCERTAIN'}))
            checked('12-21-uncertain-safe-exit-no-automatic-repeat', client.request('GET', '/api/me')[0] == 401 and facts()['credential_version'] == facts()['security_version'] == 1 and len(audits()) == 0)
            sql('DROP TRIGGER issue35_audit')

            for encoder in ['{noop}unacceptable', '{unknown}unacceptable']:
                reset_synthetic()
                sql('UPDATE user_credential SET password_hash=%s', (encoder,))
                checked('12-22-disallowed-encoder:' + encoder.split('}')[0] + '}', Client(origin).post('/api/auth/login', {'phone': fixture['identity']['phone'], 'password': PASSWORD})[:2] == (503, {'code': 'AUTH_DATA_UNAVAILABLE'}))
            reset_synthetic()
            if browser:
                done = ROOT / 'backend/target/issue35-browser-done'
                ready_stage = ROOT / 'backend/target/issue35-browser-ready-stage'
                done.unlink(missing_ok=True)
                ready_stage.unlink(missing_ok=True)
                (ROOT / 'backend/target/issue35-browser-ready.json').write_text(json.dumps({'origin': origin, 'synthetic': True}), encoding='utf-8')
                print('BROWSER_READY ' + origin, flush=True)
                deadline = time.monotonic() + 1200
                while not ready_stage.exists() and time.monotonic() < deadline:
                    time.sleep(1)
                checked('12-23-browser-initial-form-observed', ready_stage.exists())
                # Exercise the real HTTP use case, then observe the READY account in the
                # browser. This is explicitly not a claim of browser-driven password entry.
                browser_fixture, _ = login()
                assert browser_fixture.post('/api/auth/password/initial', {'currentPassword': PASSWORD, 'newPassword': next_password})[0] == 200
                print('BROWSER_READY_ACCOUNT ' + origin, flush=True)
                while not done.exists() and time.monotonic() < deadline:
                    time.sleep(1)
                checked('12-23-browser-ready-login-observed', done.exists())
            checked('12-24-no-external-or-business-execution', len(calls) == 0 and sql('SELECT COUNT(*) n FROM business_run')[0]['n'] == 0)
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
    summary.pop('synthetic_rows_rolled_back', None)
    summary.update({'issue': 35, 'executedAt': datetime.now(timezone.utc).isoformat(), 'temporaryInstanceRemoved': True,
                    'platformTables': 30, 'nativeTables': 5, 'runtimeTotalTables': 35,
                    'application_integration_tested': True, 'syntheticDataRemovedWithOwnedInstance': True,
                    'realAcceptance': 'Pending human browser password entry on the shared preset; this run uses synthetic data only'})
    destination = ROOT / 'verification/issue-35/isolated-password.json'
    destination.parent.mkdir(exist_ok=True)
    destination.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"PASS: {len(summary['cases'])} password HTTP/JDBC checks; owned MySQL removed")


if __name__ == '__main__':
    main()
