"""#37: owned loopback MySQL, packaged app, immutable formal TEXT fixtures. No shared YAML."""
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
import uuid
import base64
import time
import urllib.error
import urllib.parse
import urllib.request

import pymysql
import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('issue37_bootstrap_fixture', Path(__file__).with_name('执行初始化隔离验收.py'))
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


def run_isolated(mysql_client, browser, *, jar_path=None, navigation_checks=None):
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
    jar = jar_path or ROOT / 'backend/target/issue37-build/agentscope-brain-backend-0.1.0-SNAPSHOT.jar'
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

    with tempfile.TemporaryDirectory(prefix='issue37-web-', dir=ROOT / 'backend/target') as owned:
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
                'brain': {'projection': {'cursor-key': base64.b64encode(bytes(range(32))).decode()}, 'bootstrap': {'reference-catalog': (owned / 'catalog.json').as_uri()}, 'resources': {
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
                log = (ROOT / 'backend/target/issue37-isolated-app.log').open('w', encoding='utf-8')
                process = subprocess.Popen([str(java), '-jar', str(jar), f'--spring.config.location=optional:classpath:/,{config_path.as_uri()}',
                    f'--spring.profiles.active={profile}', f'--server.port={web_port}', '--server.address=127.0.0.1',
                    '--spring.main.banner-mode=off'], cwd=ROOT / 'backend', stdout=log, stderr=subprocess.STDOUT,
                    creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                deadline = time.monotonic() + 45
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise AssertionError('Packaged app exited; inspect backend/target/issue37-isolated-app.log')
                    try:
                        result = Client(origin).request('GET', '/readyz')
                        finished_check = expected_ready or 'Authentication readiness: REFUSED' in (ROOT / 'backend/target/issue37-isolated-app.log').read_text('utf-8', errors='replace')
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
                (ROOT / 'backend/target/issue37-metadata-diff.json').write_text(json.dumps(differences, indent=2), encoding='utf-8')
                raise AssertionError('Readiness mismatch; inspect backend/target/issue37-isolated-app.log and metadata-diff.json')

            def login():
                client = Client(origin)
                result = client.post('/api/auth/login', {'phone': fixture['identity']['phone'], 'password': PASSWORD})
                assert result[0] == 200, result[:2]
                return client

            protocol = '?protocolVersion=web-projection-v1'
            path = '/api/sessions/101/projection'
            stream = uuid.uuid4().bytes
            sql("UPDATE user_credential SET is_password_change_required=0")
            sql("""INSERT INTO business_session(id,user_id,employee_id,employee_release_id,state_session_key,last_message_no,
                title,last_activity_at,gmt_create,gmt_modified,projection_stream_id,last_projection_seq)
                VALUES(101,1,1,1,'issue37-owned-session',4,'受控文字历史',NOW(6),NOW(6),NOW(6),%s,2)""", (stream,))
            # This resource has no second platform account; only a deliberately foreign owner reference.
            sql("""INSERT INTO business_session(id,user_id,employee_id,employee_release_id,state_session_key,title,
                last_activity_at,gmt_create,gmt_modified,projection_stream_id,last_projection_seq)
                VALUES(202,999,1,1,'issue37-foreign-session','不得泄露',NOW(6),NOW(6),NOW(6),%s,0)""", (uuid.uuid4().bytes,))
            content_ids = {}
            bodies = {1: '请保留这段已有文字。', 2: '这是已完成的夹具回复。',
                      3: '<img src=x onerror="window.__executed=true">\n[危险链接](javascript:alert(1))',
                      4: '已有历史不会触发新的模型或会议写。'}

            def seed_pair(pair):
                first = pair * 2 - 1
                # Synthetic formal facts, not evidence of an actual SDK/model execution.
                sql("""INSERT INTO business_message(id,session_id,user_id,employee_id,message_no,input_sequence,sender_kind,
                    submission_key,submission_sha256,initial_run_id,accepted_at,gmt_create,gmt_modified)
                    VALUES(%s,101,1,1,%s,%s,'USER',%s,%s,%s,NOW(6),NOW(6),NOW(6))""",
                    (first,first,pair,uuid.uuid4().bytes,bytes(32),pair))
                sql("""INSERT INTO business_run(id,session_id,input_message_id,actor_user_id,employee_release_id,
                    memory_generation_at_start,memory_revision_at_start,memory_policy_version,run_request_key,request_sha256,
                    status,ended_at,gmt_create,gmt_modified) VALUES(%s,101,%s,1,1,1,1,'synthetic/v1',%s,%s,'SUCCEEDED',NOW(6),NOW(6),NOW(6))""",
                    (pair,first,uuid.uuid4().bytes,bytes(32)))
                sql("""INSERT INTO business_message(id,session_id,user_id,message_no,sender_kind,produced_by_run_id,
                    accepted_at,gmt_create,gmt_modified) VALUES(%s,101,1,%s,'ASSISTANT',%s,NOW(6),NOW(6),NOW(6))""", (first+1,first+1,pair))
                for mid in (first, first+1):
                    body = bodies.get(mid, '后续提交的受控文字 ' + str(mid)).encode()
                    cid = uuid.uuid4().bytes; content_ids[mid] = str(uuid.UUID(bytes=cid))
                    sql("INSERT INTO message_content_block(id,message_id,block_no,block_kind,text_content,gmt_create,gmt_modified) VALUES(%s,%s,1,'TEXT',%s,NOW(6),NOW(6))", (mid,mid,body.decode()))
                    sql("""INSERT INTO projection_content(content_id,owner_user_id,session_id,run_id,content_kind,stream_id,
                        first_seq,first_position,current_version,status,close_reason,gmt_create,gmt_modified)
                        VALUES(%s,1,101,%s,'TEXT',%s,%s,%s,1,'CLOSED','COMPLETED',NOW(6),NOW(6))""", (cid,pair,stream,pair,mid-first+1))
                    sql("""INSERT INTO projection_content_version(content_id,content_version,base_version,change_kind,content_status,close_reason,
                        body_format,payload_storage,byte_count,body_sha256,source_kind,source_id,projection_seq,projection_position,gmt_create,gmt_modified)
                        VALUES(%s,1,0,'CONTENT_SET','CLOSED','COMPLETED',%s,'FIXED_REF',%s,%s,'MESSAGE_BLOCK',%s,%s,%s,NOW(6),NOW(6))""",
                        (cid,'plain-text' if mid%2 else 'markdown-v1',len(body),hashlib.sha256(body).digest(),mid,pair,mid-first+1))
                payload = json.dumps({'protocolVersion':'web-projection-v1','sessionId':'101','streamId':str(uuid.UUID(bytes=stream)),
                    'seq':str(pair),'previousSeq':str(pair-1),'occurredAt':datetime.now(timezone.utc).isoformat(),
                    'changes':[{'type':'MESSAGE_COMMIT','messageId':str(first)},{'type':'MESSAGE_COMMIT','messageId':str(first+1)}]},separators=(',',':')).encode()
                sql("""INSERT INTO session_projection_event(session_id,stream_id,seq,previous_seq,protocol_version,batch_payload,
                    byte_count,batch_sha256,change_count,occurred_at,gmt_create,gmt_modified)
                    VALUES(101,%s,%s,%s,'web-projection-v1',%s,%s,%s,2,NOW(6),NOW(6),NOW(6))""",(stream,pair,pair-1,payload,len(payload),hashlib.sha256(payload).digest()))

            seed_pair(1); seed_pair(2)
            before_hash = sql('SELECT password_hash FROM user_credential')[0]['password_hash']
            start()
            client = login()
            me = client.request('GET','/api/me')
            cap = client.request('GET','/api/employees/1/capabilities')
            checked('14-01-current-trusted-identity-and-release-capability', me[0] == cap[0] == 200 and me[1]['userId']=='1'
                and me[1]['employees'][0]['canStartRun'] and cap[1]['acceptsText'] and not cap[1]['acceptsImages'])
            checked('14-02-client-owner-and-cache-cannot-select-principal', client.request('GET','/api/me?userId=999&owner=999')[1]['userId']=='1')
            snapshot = client.request('GET',path+'/snapshot'+protocol+'&messageLimit=2')
            assert snapshot[0] == 200, snapshot[:2]
            snap = snapshot[1]
            checked('14-03-snapshot-bounded-view-and-string-identifiers', snap['watermark']=='2' and snap['messages']['messageUpperBound']=='4'
                and [x['messageNo'] for x in snap['messages']['items']]==['3','4'])
            old_cursor = snap['messages']['nextPageCursor']
            older_url = path+'/messages'+protocol+'&pageCursor='+urllib.parse.quote(old_cursor)
            older = client.request('GET',older_url)
            checked('14-04-signed-history-page', older[0]==200 and [x['messageNo'] for x in older[1]['items']]==['1','2'])
            content_url = path+'/content/'+content_ids[3]+protocol+'&contentVersion=1'
            content = client.request('GET',content_url)
            checked('14-05-fixed-utf8-text-digest-and-no-store', content[0]==200 and content[1]['body']==bodies[3]
                and content[1]['sha256']==hashlib.sha256(bodies[3].encode()).hexdigest() and 'no-store' in content[2].get('Cache-Control',''))
            sql("UPDATE employee_use_grant SET grant_status='REVOKED',revoked_by=1,revoked_at=NOW(6)")
            cap = client.request('GET','/api/employees/1/capabilities')
            checked('14-06-revocation-keeps-account-and-history-readable', cap[0]==200 and cap[1]['allowedActions']==[]
                and cap[1]['unavailableReason']=='EMPLOYEE_USE_REVOKED' and client.request('GET',content_url)[0]==200
                and client.request('GET','/api/me')[1]['employees'][0]['readOnly'])
            for endpoint in ['/api/submissions','/api/session-intents','/api/runs/1/continue']:
                checked('14-07-unimplemented-writes-remain-server-denied:'+endpoint,client.post(endpoint,{'userId':'1','owner':'1','employeeId':'1','allowedActions':['START_RUN']})[0]==403)
            checked('14-08-cross-user-session-and-unknown-employee-404',client.request('GET','/api/sessions/202/projection/snapshot'+protocol)[0]==404
                and client.request('GET','/api/employees/999/capabilities')[0]==404)
            # Owner mismatch at the content head cannot be repaired from client owner hints.
            sql('UPDATE projection_content SET owner_user_id=999 WHERE content_id=%s',(uuid.UUID(content_ids[3]).bytes,))
            checked('14-09-cross-user-content-404',client.request('GET',content_url+'&owner=1')[0]==404)
            sql('UPDATE projection_content SET owner_user_id=1 WHERE content_id=%s',(uuid.UUID(content_ids[3]).bytes,))
            sql("UPDATE platform_user SET security_version=security_version+1,modified_by_user_id=1,modification_source_kind='USER_ACTION',modification_bootstrap_batch_id=NULL")
            checked('14-10-old-security-context-cannot-read',client.request('GET',content_url)[0]==401)
            client = login()
            sql("UPDATE platform_user SET account_status='DISABLED'")
            checked('14-11-account-disable-stops-reading-and-login',client.request('GET',path+'/snapshot'+protocol)[0]==401
                and Client(origin).post('/api/auth/login',{'phone':fixture['identity']['phone'],'password':PASSWORD})[0]==401)
            start()
            checked('14-12-disable-is-valid-policy-at-startup',Client(origin).request('GET','/readyz')[1]=={'ready':True})
            sql("UPDATE platform_user SET account_status='ACTIVE'")
            start()
            client = login()
            checked('14-13-restart-keeps-revocation-and-password',client.request('GET','/api/me')[1]['employees'][0]['readOnly']
                and sql('SELECT password_hash FROM user_credential')[0]['password_hash']==before_hash
                and client.request('GET',content_url)[0]==200 and client.request('GET',older_url)[0]==200)
            repeated = fixture_module.boot.apply(c,fixture)
            checked('14-14-repeated-bootstrap-does-not-regrant-or-reset',sql('SELECT grant_status FROM employee_use_grant')[0]['grant_status']=='REVOKED'
                and sql('SELECT password_hash,is_password_change_required FROM user_credential')[0]=={'password_hash':before_hash,'is_password_change_required':0})
            sql("UPDATE employee_use_grant SET grant_status='ACTIVE',revoked_by=NULL,revoked_at=NULL")
            sql("UPDATE employee_release SET availability_status='DISABLED',availability_revision=availability_revision+1,availability_changed_by=1,availability_source_kind='USER_ACTION',availability_bootstrap_batch_id=NULL")
            start()
            checked('14-15-release-disable-valid-history-preserved',client.request('GET','/api/employees/1/capabilities')[1]['unavailableReason']=='RELEASE_DISABLED'
                and client.request('GET',content_url)[0]==200)
            sql("UPDATE employee_release SET availability_status='AVAILABLE',availability_revision=availability_revision+1,availability_changed_by=1,availability_source_kind='USER_ACTION',availability_bootstrap_batch_id=NULL")
            # Atomic append after the snapshot must not change its fixed upper bound or older page.
            c.begin()
            seed_pair(3)
            sql('UPDATE business_session SET last_message_no=6,last_projection_seq=3 WHERE id=101')
            c.commit()
            page = client.request('GET',older_url)
            fresh = client.request('GET',path+'/snapshot'+protocol)
            checked('14-16-fixed-history-upper-bound-after-append',page[1]['messageUpperBound']=='4' and [x['messageNo'] for x in page[1]['items']]==['1','2']
                and fresh[1]['messages']['messageUpperBound']=='6' and fresh[1]['watermark']=='3')
            checked('14-17-cursor-tamper-and-protocol-rejected',client.request('GET',older_url+'x')[0]==409
                and client.request('GET',path+'/snapshot?protocolVersion=unknown')[0]==409
                and client.request('GET',path+'/snapshot'+protocol+'&messageLimit=101')[0]==400)
            sql('UPDATE projection_content_version SET body_sha256=%s WHERE content_id=%s',(bytes(32),uuid.UUID(content_ids[3]).bytes))
            checked('14-18-corrupt-fixed-version-not-rebuilt',client.request('GET',content_url)[0]==410)
            sql('UPDATE projection_content_version SET body_sha256=%s WHERE content_id=%s',(hashlib.sha256(bodies[3].encode()).digest(),uuid.UUID(content_ids[3]).bytes))
            checked('14-19-missing-version-not-substituted',client.request('GET',content_url.replace('contentVersion=1','contentVersion=2'))[0]==410)
            if navigation_checks is not None:
                navigation_checks(sql, client, origin, start, login, checked)
            if browser:
                sql("UPDATE employee_use_grant SET grant_status='REVOKED',revoked_by=1,revoked_at=NOW(6)")
                done = ROOT/'backend/target/issue37-browser-done'
                done.unlink(missing_ok=True)
                (ROOT/'backend/target/issue37-browser-ready.json').write_text(json.dumps({'origin':origin,'sessionId':'101','synthetic':True}),encoding='utf-8')
                print('BROWSER_READY '+origin,flush=True)
                deadline=time.monotonic()+1200
                while not done.exists() and time.monotonic()<deadline: time.sleep(1)
                checked('14-22-browser-safe-text-and-revoked-readonly-observed',done.exists())
            sql('DELETE FROM phone_reservation WHERE owner_user_id=1')
            start(expected_ready=False)
            checked('14-20-structural-reference-corruption-refuses-readiness',client.request('GET','/api/me')[0]==503)
            checked('14-21-no-external-service-probes',len(calls)==0)
            checked('14-23-no-new-execution-or-meeting-facts',sql('SELECT COUNT(*) n FROM business_run')[0]['n']==3
                and sql('SELECT COUNT(*) n FROM business_message')[0]['n']==6
                and all(sql('SELECT COUNT(*) n FROM '+table)[0]['n']==0 for table in
                    ['agentscope_store','agentscope_sessions','agentscope_snapshots','tool_operation','runtime_dispatch','runtime_sdk_call']))
            # Independently exercise the exact earlier 35-table login revision in the same owned instance.
            stop()
            sql('CREATE DATABASE issue37_legacy CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
            legacy_client = [x.replace('memory_schema_probe','issue37_legacy') for x in mysql_client]
            for ddl in fixture_module.DDL + sorted((ROOT/'db/vendor').glob('*.sql')):
                base.require_success(base.run(legacy_client,sql=ddl.read_bytes()),'legacy exact structure')
            legacy_connection=pymysql.connect(host='127.0.0.1',port=port,user='root',password='',database='issue37_legacy',charset='utf8mb4',autocommit=False)
            try:
                fixture_module.boot.apply(legacy_connection,fixture)
                legacy_connection.autocommit(True)
                with legacy_connection.cursor() as cur:
                    cur.execute('UPDATE user_credential SET is_password_change_required=0')
                    cur.execute("INSERT INTO business_session(id,user_id,employee_id,employee_release_id,state_session_key,title,last_activity_at,gmt_create,gmt_modified) VALUES(101,1,1,1,'legacy37','旧结构会话',NOW(6),NOW(6),NOW(6))")
                config['spring']['datasource']['url']=config['spring']['datasource']['url'].replace('memory_schema_probe','issue37_legacy')
                config_path.write_text(yaml.safe_dump(config),encoding='utf-8')
                start()
                legacy_user=login()
                checked('14-24-exact-login-baseline-remains-usable',legacy_user.request('GET','/api/me')[1]['employees'][0]['canStartRun'])
                checked('14-25-missing-history-revision-is-controlled-unavailable',legacy_user.request('GET',path+'/snapshot'+protocol)[0]==503)
                with legacy_connection.cursor() as cur:cur.execute('CREATE TABLE projection_content(content_id BINARY(16) PRIMARY KEY)')
                start(expected_ready=False)
                checked('14-26-partial-history-schema-refuses-readiness',Client(origin).request('GET','/api/me')[0]==503)
            finally:legacy_connection.close()
            return {'cases':cases,'upstreamCalls':len(calls),'packagedJarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
                'sharedDatasetUsed':False,'fixtureSessionId':'101','syntheticFormalMessages':6,'syntheticRuns':3,
                'releaseConfigSha256':sql('SELECT HEX(config_sha256) sha FROM employee_release')[0]['sha'],
                'modelCalls':0,'sdkCalls':0,'meetingWrites':0,
                'executionEvidence':'Read graph has no SDK/model/tool dependency; all observed upstream calls and execution ledgers remain zero. Formal rows are synthetic.'}
        finally:
            if process is not None and process.poll() is None:
                process.terminate();process.wait(timeout=20)
            if log is not None: log.close()
            upstream.shutdown();upstream.server_close();c.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-bin',type=Path,default=Path('D:/code environment/mysql/mysql-8.0.45-winx64/bin'))
    parser.add_argument('--browser',action='store_true')
    args=parser.parse_args()
    import re
    ddl=sorted((ROOT/'db/schema').glob('*.sql'))
    count=sum(len(re.findall(r'CREATE TABLE (\w+)',p.read_text('utf-8'))) for p in ddl)
    summary=base.verify(args.mysql_bin.resolve(),ddl_files=ddl,probes=[],expected_table_count=count,
        inspection_check=lambda _:0,concurrency_check=lambda _:0,extra_checks=lambda client:run_isolated(client,args.browser))
    summary.pop('synthetic_rows_rolled_back',None)
    summary.update({'issue':37,'executedAt':datetime.now(timezone.utc).isoformat(),'temporaryInstanceRemoved':True,
        'application_integration_tested':True,'syntheticDataRemovedWithOwnedInstance':True,'platformTables':count,'nativeTables':5,'runtimeTotalTables':count+5})
    destination=ROOT/'verification/issue-37/isolated-workbench.json'
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f"PASS: {len(summary['cases'])} workbench/read-only checks; owned MySQL removed")


if __name__=='__main__':main()
