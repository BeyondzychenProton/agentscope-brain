"""Signed-in browser -> Runtime -> Java -> controllable model on an owned MySQL instance."""
from __future__ import annotations

import argparse
import base64
import http.server
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

import pymysql
import yaml

if hasattr(sys.stdout, 'reconfigure'): sys.stdout.reconfigure(encoding='utf-8')
if hasattr(sys.stderr, 'reconfigure'): sys.stderr.reconfigure(encoding='utf-8')
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / '.codex-local/issue-42-browser'))
spec = importlib.util.spec_from_file_location('issue46_existing_fixture', Path(__file__).with_name('执行工作台撤权只读隔离验收.py'))
web = importlib.util.module_from_spec(spec)
spec.loader.exec_module(web)
SECRET = 'issue46-synthetic-runtime-secret-0123456789abcdef'


def isolated(client, tool_mode=False, jar_path=None):
    from playwright.sync_api import sync_playwright, expect

    mysql_port = int(next(x.split('=', 1)[1] for x in client if x.startswith('--port=')))
    db = pymysql.connect(host='127.0.0.1', port=mysql_port, user='root', password='', database='memory_schema_probe',
                         charset='utf8mb4', autocommit=False)
    fixture = web.fixture_module.fixture()
    fixture['employee']['model']['parameters'] = {}
    if tool_mode:
        fixture['employee']['tools'][0]['toolName'] = 'identity.resolve'
        fixture['employee']['tools'][0]['binding']['inputSchemaRef'] = 'contract/meeting-mcp/identity.resolve/v1'
    requests = []
    closed_requests = []
    tool_requests = []
    release_second = threading.Event()
    finish = threading.Event()
    model_closed = threading.Event()

    class Model(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get('Content-Length', '0'))
            payload = json.loads(self.rfile.read(length))
            if tool_mode and payload.get('method'):
                method = payload['method']
                if method == 'notifications/initialized':
                    self.send_response(202); self.end_headers(); return
                if method == 'initialize':
                    result = {'protocolVersion':'2025-03-26','capabilities':{},'serverInfo':{'name':'synthetic','version':'1'}}
                elif method == 'tools/call':
                    assert self.headers.get('X-MCP-User-Phone') == fixture['identity']['phone']
                    assert payload['params']['name'] == 'identity.resolve'
                    tool_requests.append(payload['params'])
                    result = {'content':[{'type':'text','text':'Synthetic tool result'}],'isError':False}
                else:
                    self.send_error(400); return
                data = json.dumps({'jsonrpc':'2.0','id':payload['id'],'result':result}).encode()
                self.send_response(200); self.send_header('Content-Type','application/json'); self.end_headers()
                self.wfile.write(data); self.wfile.flush(); return
            if self.path != '/v1/chat/completions':
                self.send_error(404); return
            requests.append(payload)
            request_number = len(requests)
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Cache-Control', 'no-cache')
            self.end_headers()
            def chunk(delta, reason=None):
                data = {'id':'fixture', 'object':'chat.completion.chunk', 'created':1, 'model':'synthetic-model',
                        'choices':[{'index':0, 'delta':delta, 'finish_reason':reason}]}
                self.wfile.write(('data: ' + json.dumps(data, ensure_ascii=False) + '\n\n').encode())
                self.wfile.flush()
            try:
                if tool_mode:
                    has_result = any(m['role']=='tool' for m in payload['messages'])
                    if not has_result:
                        assert any(t['function']['name']=='identity_resolve' for t in payload['tools'])
                        text = '正在查询。<tool_call>{"name":"identity.resolve","arguments":{"name":"合成人员"}}</tool_call>请稍候。'
                        for start in range(0,len(text),3):
                            chunk({'content':text[start:start+3]}); time.sleep(.005)
                        chunk({}, 'stop')
                    else:
                        chunk({'content':'工具返回后第一段'})
                        deadline = time.monotonic()+25
                        while not release_second.wait(.1) and time.monotonic()<deadline:
                            self.wfile.write(b': keepalive\n\n'); self.wfile.flush()
                        chunk({'content':'，最终答复。'}); chunk({},'stop')
                    self.wfile.write(b'data: [DONE]\n\n'); self.wfile.flush(); return
                chunk({'content':'第一段'})
                deadline = time.monotonic() + 25
                while not release_second.wait(.2) and time.monotonic() < deadline:
                    self.wfile.write(b': keepalive\n\n'); self.wfile.flush()
                if release_second.is_set():
                    chunk({'content':'第二段'})
                    if finish.wait(25):
                        chunk({}, 'stop')
                        self.wfile.write(b'data: [DONE]\n\n'); self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            finally:
                closed_requests.append(request_number)
                model_closed.set()
        def log_message(self, *_): pass

    model = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Model)
    model_thread = threading.Thread(target=model.serve_forever, daemon=True)
    model_thread.start()
    model_url = f'http://127.0.0.1:{model.server_port}/v1'
    java = Path('D:/code environment/jdk/jdk-17.0.9/bin/java.exe')
    node = ROOT / '.codex-local/issue-34/runtime/node-v24.22.1-win-x64/node.exe'
    jar = jar_path or ROOT / 'backend/target/issue46-build/agentscope-brain-backend-0.1.0-SNAPSHOT.jar'
    web_port, java_port = web.free_port(), web.free_port()
    origin = f'http://127.0.0.1:{web_port}'
    java_origin = f'http://127.0.0.1:{java_port}'
    processes = []
    logs = []

    def wait_http(url, process):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if process.poll() is not None: raise AssertionError('Process exited; inspect issue46 logs')
            try:
                with urllib.request.urlopen(url, timeout=2) as response:
                    if response.status == 200: return
            except (urllib.error.URLError, TimeoutError): pass
            time.sleep(.3)
        raise AssertionError('Service did not become ready: ' + url)

    with tempfile.TemporaryDirectory(prefix='issue46-owned-', dir=ROOT / 'backend/target') as owned_name:
        owned = Path(owned_name)
        try:
            for ddl in sorted((ROOT / 'db/vendor').glob('*.sql')):
                web.base.require_success(web.base.run(client, sql=ddl.read_bytes()), 'native structure')
            web.fixture_module.boot.apply(db, fixture)
            db.autocommit(True)
            with db.cursor() as cursor:
                cursor.execute('UPDATE user_credential SET is_password_change_required=1')
            model_config = fixture['employee']['model']
            catalog = {}
            def ref(key, kind, content):
                catalog[key] = {'kind':kind, 'content':content, 'sha256':web.fixture_module.boot.digest(content).hex()}
            for tool in fixture['employee']['tools']:
                for key, value in tool['binding'].items():
                    if key.endswith('Ref'):
                        content = {'inputSchema':{'type':'object','properties':{'name':{'type':'string'}},'required':['name']}} if tool_mode and key=='inputSchemaRef' else {}
                        ref(value, 'synthetic-contract', content)
            ref(model_config['configRef'], 'model', model_config)
            ref(model_config['endpointRef'], 'model-endpoint', {'baseUrl':model_url})
            ref(model_config['credentialRef'], 'credential-reference', {'configurationPath':'brain.resources.model.api-key'})
            ref('session-isolated/v1', 'workspace-policy', {})
            ref('effective-generation/v1', 'memory-policy', {})
            ref('synthetic-mcp/v1', 'mcp-target', {'endpoint':model_url})
            catalog_path = owned / 'catalog.json'
            catalog_path.write_text(json.dumps(catalog, ensure_ascii=False), 'utf-8')
            config = {'spring': {'datasource': {'url':f'jdbc:mysql://127.0.0.1:{mysql_port}/memory_schema_probe?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
                'username':'root', 'password':''}, 'sql':{'init':{'mode':'never'}},
                'session':{'jdbc':{'initialize-schema':'never','cleanup-cron':'-','flush-mode':'immediate'}}},
                'brain': {'copilot': {'enabled':True, 'runtime-secret':SECRET},
                    'projection': {'cursor-key':base64.b64encode(bytes(range(32))).decode()},
                    'bootstrap': {'reference-catalog':catalog_path.as_uri()},
                    'resources': {'model':{'api-key':'synthetic','base-url':model_url,'model':model_config['modelId']},
                        'minio':{'endpoint':model_url,'access-key':'synthetic','secret-key':'synthetic'},
                        'meeting-mcp':{'endpoint':model_url,'app-key':'synthetic','secret':'synthetic'}}}}
            config_path = owned / 'application.yaml'
            config_path.write_text(yaml.safe_dump(config), 'utf-8')
            java_log = (ROOT / 'backend/target/issue46-isolated-java.log').open('w', encoding='utf-8')
            logs.append(java_log)
            backend = subprocess.Popen([str(java), '-jar', str(jar),
                f'--spring.config.location=optional:classpath:/,{config_path.as_uri()}', '--spring.profiles.active=dev',
                f'--server.port={java_port}', '--server.address=127.0.0.1', '--spring.main.banner-mode=off'],
                cwd=ROOT / 'backend', stdout=java_log, stderr=subprocess.STDOUT, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            processes.append(backend)
            wait_http(java_origin + '/readyz', backend)
            next_log = (ROOT / 'backend/target/issue46-isolated-web.log').open('w', encoding='utf-8')
            logs.append(next_log)
            env = os.environ.copy()
            env.update({'BRAIN_JAVA_ORIGIN':java_origin, 'BRAIN_COPILOT_RUNTIME_SECRET':SECRET,
                        'COPILOTKIT_TELEMETRY_DISABLED':'true', 'NEXT_TELEMETRY_DISABLED':'1'})
            frontend = subprocess.Popen([str(node), str(ROOT / 'formal-web/node_modules/next/dist/bin/next'),
                'start', '--hostname', '127.0.0.1', '--port', str(web_port)], cwd=ROOT / 'formal-web', env=env,
                stdout=next_log, stderr=subprocess.STDOUT, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            processes.append(frontend)
            wait_http(origin + '/login', frontend)
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(channel='chrome', headless=True)
                try:
                    page = browser.new_page(viewport={'width':1440,'height':960})
                    page.set_default_timeout(15000)
                    activity = []
                    page.on('response', lambda res: activity.append({'status':res.status,'path':urllib.parse.urlsplit(res.url).path})
                            if '/api/' in res.url else None)
                    page.on('pageerror', lambda error: activity.append({'pageerror':str(error)}))
                    page.goto(origin + '/login')
                    page.get_by_label('登录手机号', exact=True).fill(fixture['identity']['phone'])
                    page.get_by_label('密码', exact=True).fill(web.PASSWORD)
                    page.get_by_role('button', name='登录', exact=True).click()
                    page.wait_for_url('**/password/initial')
                    page.goto(origin + '/workbench')
                    page.wait_for_url('**/password/initial')
                    page.get_by_label('当前密码', exact=True).fill(web.PASSWORD)
                    page.get_by_label('新密码', exact=True).fill('Synthetic-New-Password-2026')
                    page.get_by_label('确认新密码', exact=True).fill('Synthetic-New-Password-2026')
                    page.get_by_role('button', name='修改密码并进入工作台').click()
                    page.wait_for_url('**/workbench')
                    composer = page.get_by_role('textbox', name='发送消息')
                    expect(composer).to_be_visible()
                    if tool_mode:
                        composer.fill('查询合成人员')
                        page.get_by_role('button', name='发送', exact=True).click()
                        expect(page.get_by_text('工具返回后第一段',exact=False).last).to_be_visible(timeout=30000)
                        expect(page.locator('.tool-call-card')).to_have_count(1)
                        assert '<tool' not in page.locator('body').inner_text()
                        assert len(tool_requests)==1 and len(requests)==2
                        current_url=page.url
                        page.reload()
                        expect(page.get_by_text('工具返回后第一段',exact=False).last).to_be_visible(timeout=15000)
                        expect(page.locator('.tool-call-card')).to_have_count(1)
                        assert len(tool_requests)==1 and len(requests)==2
                        release_second.set()
                        expect(page.get_by_text('工具返回后第一段，最终答复。',exact=False).last).to_be_visible(timeout=15000)
                        expect(page.get_by_role('button',name='发送',exact=True)).to_be_visible(timeout=15000)
                        page.reload()
                        expect(page.get_by_text('工具返回后第一段，最终答复。',exact=False).last).to_be_visible(timeout=15000)
                        expect(page.locator('.tool-call-card')).to_have_count(1)
                        assert page.url==current_url and len(tool_requests)==1 and len(requests)==2
                        assert '<tool' not in page.locator('body').inner_text()
                        assert all(r['stream'] for r in requests)
                        assert len({r['model'] for r in requests})==1
                        assert not [a for a in activity if 'pageerror' in a], activity
                        with db.cursor() as cursor:
                            cursor.execute('SELECT content,display_json,status FROM copilot_thread_message WHERE role=\'ASSISTANT\'')
                            content,display,status=cursor.fetchone()
                        assert '<tool' not in content and '最终答复' in content and status=='COMPLETE'
                        restored=json.loads(display)
                        assert any(m.get('toolCalls') for m in restored) and any(m['role']=='tool' for m in restored)
                        print('PASS: signed-in browser -> Runtime -> Java -> model -> signed MCP -> streamed final answer; active and completed refresh preserve one tool card and one execution',flush=True)
                        return {'browserExecuted':True,'modelRequests':len(requests),'toolCalls':len(tool_requests)}
                    composer.fill('尚未发送的草稿')
                    page.reload()
                    expect(composer).to_have_value('')
                    assert page.url.endswith('/workbench'), page.url
                    composer.fill('请回复两段')
                    page.get_by_role('button', name='发送', exact=True).click()
                    try:
                        expect(page.get_by_text('第一段', exact=False).last).to_be_visible(timeout=30000)
                    except AssertionError:
                        print('Browser diagnostics:', json.dumps({'url':page.url, 'modelRequests':len(requests),
                            'text':page.locator('body').inner_text(), 'activity':activity}, ensure_ascii=False), flush=True)
                        raise
                    assert 'threadId=' in page.url, page.url
                    assert len(requests) == 1, len(requests)
                    release_second.set()
                    expect(page.get_by_text('第一段第二段', exact=False).last).to_be_visible(timeout=15000)
                    finish.set()
                    expect(page.get_by_role('button', name='发送', exact=True)).to_be_visible(timeout=15000)
                    page.reload()
                    expect(page.get_by_text('第一段第二段', exact=False).last).to_be_visible(timeout=15000)
                    assert len(requests) == 1, len(requests)
                    composer.fill('第二轮')
                    page.get_by_role('button', name='发送', exact=True).click()
                    deadline = time.monotonic() + 20
                    while len(requests) < 2 and time.monotonic() < deadline: time.sleep(.1)
                    assert len(requests) == 2, len(requests)
                    expect(page.get_by_text('第一段第二段', exact=False)).to_have_count(2, timeout=15000)
                    history = [(m['role'], m.get('content')) for m in requests[1]['messages'] if m['role'] != 'system']
                    assert history == [('user','请回复两段'),('assistant','第一段第二段'),('user','第二轮')], history
                    expect(page.get_by_role('button', name='发送', exact=True)).to_be_visible(timeout=15000)
                    deadline = time.monotonic() + 10
                    while 2 not in closed_requests and time.monotonic() < deadline: time.sleep(.1)
                    assert 2 in closed_requests
                    model_closed.clear(); release_second.clear(); finish.clear()
                    composer.fill('需要停止的回复')
                    page.get_by_role('button', name='发送', exact=True).click()
                    deadline = time.monotonic() + 20
                    while len(requests) < 3 and time.monotonic() < deadline: time.sleep(.1)
                    assert len(requests) == 3, len(requests)
                    expect(page.get_by_role('button', name='停止生成')).to_be_visible(timeout=15000)
                    current_url = page.url
                    page.reload()
                    expect(page.get_by_role('button', name='停止生成')).to_be_visible(timeout=15000)
                    assert page.url == current_url and len(requests) == 3
                    page.get_by_role('button', name='停止生成').click()
                    deadline = time.monotonic() + 12
                    while 3 not in closed_requests and time.monotonic() < deadline: time.sleep(.1)
                    assert 3 in closed_requests, 'Stop did not close the model HTTP stream'
                    expect(page.get_by_text('回复未完成', exact=False).first).to_be_visible(timeout=15000)
                    assert len(requests) == 3
                    release_second.set(); finish.set()
                    expect(composer).not_to_have_attribute('readonly', '', timeout=15000)
                    composer.fill('中断后继续')
                    page.get_by_role('button', name='发送', exact=True).click()
                    deadline = time.monotonic() + 20
                    while len(requests) < 4 and time.monotonic() < deadline: time.sleep(.1)
                    assert len(requests) == 4, len(requests)
                    history = [(m['role'], m.get('content')) for m in requests[3]['messages'] if m['role'] != 'system']
                    assert history == [('user','请回复两段'),('assistant','第一段第二段'),('user','第二轮'),
                        ('assistant','第一段第二段'),('user','需要停止的回复'),('user','中断后继续')], history
                    expect(page.get_by_role('button', name='发送', exact=True)).to_be_visible(timeout=15000)
                    with db.cursor() as cursor:
                        cursor.execute("UPDATE employee_use_grant SET grant_status='REVOKED',revoked_by=1,revoked_at=UTC_TIMESTAMP(6)")
                    page.reload()
                    expect(page.get_by_text('使用授权已撤销', exact=False)).to_be_visible(timeout=15000)
                    expect(page.get_by_text('第一段第二段', exact=False).first).to_be_visible()
                    expect(composer).to_have_attribute('readonly', '')
                    assert len(requests) == 4
                    with db.cursor() as cursor:
                        cursor.execute("UPDATE employee_use_grant SET grant_status='ACTIVE',revoked_by=NULL,revoked_at=NULL")
                        cursor.execute("UPDATE employee_release SET availability_status='DISABLED',availability_revision=availability_revision+1,availability_changed_by=1,availability_source_kind='USER_ACTION',availability_bootstrap_batch_id=NULL")
                    page.reload()
                    expect(page.get_by_text('发布版本不可用', exact=False)).to_be_visible(timeout=15000)
                    expect(page.get_by_text('第一段第二段', exact=False).first).to_be_visible()
                    expect(composer).to_have_attribute('readonly', '')
                    assert len(requests) == 4
                    with db.cursor() as cursor:
                        cursor.execute("UPDATE employee_release SET availability_status='AVAILABLE',availability_revision=availability_revision+1,availability_changed_by=1,availability_source_kind='USER_ACTION',availability_bootstrap_batch_id=NULL")
                    page.reload()
                    expect(composer).not_to_have_attribute('readonly', '', timeout=15000)
                    deadline = time.monotonic() + 10
                    while 4 not in closed_requests and time.monotonic() < deadline: time.sleep(.1)
                    assert 4 in closed_requests
                    release_second.clear(); finish.clear()
                    composer.fill('退出时取消')
                    page.get_by_role('button', name='发送', exact=True).click()
                    deadline = time.monotonic() + 20
                    while len(requests) < 5 and time.monotonic() < deadline: time.sleep(.1)
                    assert len(requests) == 5
                    expect(page.get_by_role('button', name='停止生成')).to_be_visible(timeout=15000)
                    page.get_by_role('button', name='退出登录', exact=True).click()
                    page.wait_for_url('**/login')
                    deadline = time.monotonic() + 12
                    while 5 not in closed_requests and time.monotonic() < deadline: time.sleep(.1)
                    assert 5 in closed_requests, 'Logout did not close the model HTTP stream'
                    page.goto(current_url)
                    page.wait_for_url('**/login')
                    print('PASS: password gate, streaming, history, refresh, stop, access changes and logout')
                finally:
                    browser.close()
        finally:
            release_second.set(); finish.set()
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=15)
                    except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
            for log in logs: log.close()
            model.shutdown(); model.server_close(); db.close()
    return {'browserExecuted':True,'modelRequests':len(requests)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-bin', type=Path, default=Path('D:/code environment/mysql/mysql-8.0.45-winx64/bin'))
    parser.add_argument('--tools', action='store_true', help='Validate the signed read-only tool and streaming continuation chain')
    parser.add_argument('--jar', type=Path)
    args = parser.parse_args()
    ddl = sorted((ROOT / 'db/schema').glob('*.sql'))
    count = sum(len(re.findall(r'CREATE TABLE (\w+)', p.read_text('utf-8'))) for p in ddl)
    web.base.verify(args.mysql_bin.resolve(), ddl_files=ddl, probes=[], expected_table_count=count,
        inspection_check=lambda _:0, concurrency_check=lambda _:0, extra_checks=lambda client: isolated(client,args.tools,args.jar.resolve() if args.jar else None))


if __name__ == '__main__': main()
