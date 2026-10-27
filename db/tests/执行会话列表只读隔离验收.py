"""#42 read-only navigation using an owned MySQL and the packaged application. No shared YAML."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import urllib.parse
import uuid

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('workbench_navigation_fixture', Path(__file__).with_name('执行工作台撤权只读隔离验收.py'))
web = importlib.util.module_from_spec(spec)
spec.loader.exec_module(web)
EVIDENCE = ROOT / 'verification/issue-42'


def check_navigation(sql, client, origin, start, login, checked, browser):
    special = '页外目标100%_! <img src=x onerror="window.__executed=true"> 中文😀'
    for sid in range(1000, 1062):
        sql('''INSERT INTO business_session(id,user_id,employee_id,employee_release_id,state_session_key,title,
            is_archived,last_activity_at,gmt_create,gmt_modified,projection_stream_id,last_projection_seq)
            VALUES(%s,1,1,1,%s,%s,%s,'2026-10-27 03:04:05.123456',NOW(6),NOW(6),%s,0)''',
            (sid, 'navigation-'+str(sid), special if sid == 1000 else '会话夹具 '+str(sid), 1 if sid == 1001 else 0, uuid.uuid4().bytes))

    def listing(q='', cursor=None, limit=50, **extra):
        params = {'employeeId': '1', 'limit': str(limit), **extra}
        if q: params['q'] = q
        if cursor is not None: params['pageCursor'] = cursor
        return client.request('GET', '/api/sessions?' + urllib.parse.urlencode(params))

    try:
        first = listing(limit=7)
        checked('42-01-list-is-authenticated-and-not-cached', first[0] == 200 and first[2].get('Cache-Control') == 'no-store'
                and web.Client(origin).request('GET', '/api/sessions?employeeId=1')[0] == 401)
        expected = [str(s['id']) for s in sql('SELECT id FROM business_session WHERE user_id=1 AND employee_id=1 AND is_archived=0 ORDER BY last_activity_at DESC,id DESC')]
        seen = [s['sessionId'] for s in first[1]['items']]
        cursor = first[1]['nextPageCursor']
        while cursor:
            result = listing(cursor=cursor, limit=7)
            assert result[0] == 200, result[:2]
            seen += [s['sessionId'] for s in result[1]['items']]
            cursor = result[1]['nextPageCursor']
        checked('42-02-exact-keyset-order-no-duplicates-or-foreign-archived-rows', seen == expected and len(seen) == len(set(seen)) == 62)
        checked('42-02b-utc-microsecond-sort-position', listing('页外目标')[1]['items'][0]['updatedAt'] == '2026-10-27T03:04:05.123456Z')
        checked('42-03-search-finds-title-beyond-first-fifty', '1000' not in [s['sessionId'] for s in listing()[1]['items']]
                and [s['sessionId'] for s in listing('页外目标')[1]['items']] == ['1000'])
        for query in ['%', '_', '!', '中文😀']:
            checked('42-04-literal-search:'+query, [s['sessionId'] for s in listing(query)[1]['items']] == ['1000'])
        checked('42-05-parameterized-search-and-owner-hints-do-not-leak', listing("' OR 1=1 --")[1]['items'] == []
                and listing('不得泄露', owner='999', userId='999')[1]['items'] == [])
        original_cursor = first[1]['nextPageCursor']
        checked('42-06-query-binding-tamper-and-bounds', listing('changed', original_cursor)[0] == 409
                and listing(cursor=original_cursor+'x')[0] == 409 and listing(limit=101)[0] == 400
                and listing('中'*201)[0] == 400 and listing(employeeId='999')[0] == 404)
        sql("UPDATE employee_use_grant SET grant_status='REVOKED',revoked_by=1,revoked_at=NOW(6)")
        checked('42-07-revoked-user-can-list-and-read-own-text', listing()[0] == 200
                and client.request('GET','/api/sessions/101/projection/snapshot?protocolVersion=web-projection-v1')[0] == 200)
        start()
        checked('42-08-configured-cursor-survives-restart', listing(cursor=original_cursor,limit=7)[0] == 200)
        if browser:
            browser_navigation(sql, origin, special, checked)
        sql("UPDATE user_credential SET is_password_change_required=1")
        checked('42-09-restricted-user-cannot-list', login().request('GET','/api/sessions?employeeId=1')[0] == 403)
        sql("UPDATE user_credential SET is_password_change_required=0")
        checked('42-10-read-navigation-creates-no-execution', all(sql('SELECT COUNT(*) n FROM '+table)[0]['n'] == 0
                for table in ['runtime_dispatch','runtime_sdk_call','tool_operation','agentscope_store','agentscope_sessions']))
    finally:
        sql("UPDATE user_credential SET is_password_change_required=0")
        sql("UPDATE platform_user SET account_status='ACTIVE'")
        sql("UPDATE employee_use_grant SET grant_status='ACTIVE',revoked_by=NULL,revoked_at=NULL")
        sql('DELETE FROM business_session WHERE id BETWEEN 1000 AND 1061 AND user_id=1 AND state_session_key LIKE %s',('navigation-%',))


def browser_navigation(sql, origin, special, checked):
    from playwright.sync_api import sync_playwright, expect
    observations = []
    phone = web.fixture_module.fixture()['identity']['phone']
    with sync_playwright() as playwright:
        for channel in ['chrome', 'msedge']:
            sql("UPDATE platform_user SET account_status='ACTIVE'")
            browser = playwright.chromium.launch(channel=channel, headless=True)
            try:
                page = browser.new_page(viewport={'width':1440,'height':960})
                page.set_default_timeout(15000)
                writes = []
                page.on('request', lambda req: writes.append(req.url.split('/api/',1)[-1].split('?')[0]) if req.method not in ['GET','HEAD'] and '/api/' in req.url else None)
                page.goto(origin+'/login')
                page.get_by_label('登录手机号',exact=True).fill(phone)
                page.get_by_label('密码',exact=True).fill(web.PASSWORD)
                page.get_by_role('button',name='登录',exact=True).click()
                page.wait_for_url('**/workbench')
                expect(page.locator('.session-item')).to_have_count(50)
                page.get_by_role('button',name='加载更多会话',exact=True).click()
                expect(page.locator('.session-item')).to_have_count(62)
                search = page.get_by_role('searchbox',name='搜索会话标题',exact=True)
                search.fill('页外目标')
                page.get_by_role('button',name='搜索',exact=True).click()
                expect(page.locator('.session-item')).to_have_count(1)
                expect(page.locator('.session-item')).to_have_text(special)
                assert page.locator('.session-item img').count() == 0 and page.evaluate('window.__executed === true') is False
                search.fill('没有这样的会话')
                page.get_by_role('button',name='搜索',exact=True).click()
                expect(page.get_by_text('未找到匹配标题的会话。',exact=True)).to_be_visible()
                search.fill('受控文字历史')
                page.get_by_role('button',name='搜索',exact=True).click()
                page.get_by_role('button',name='受控文字历史',exact=True).click()
                expect(page.get_by_role('heading',name='受控文字历史',exact=True)).to_be_visible()
                expect(page.locator('.formal-message')).to_have_count(6)
                expect(page.locator('.session-item[aria-current="page"]')).to_have_text('受控文字历史')
                expect(page.get_by_text('使用授权已撤销。你仍可查看本人已有的文字历史，无法发起新执行。',exact=True)).to_be_visible()
                page.reload()
                expect(page.locator('.formal-message')).to_have_count(6)
                expect(page.locator('.session-item[aria-current="page"]')).to_have_text('受控文字历史')
                page.screenshot(path=str(EVIDENCE/f'browser-{channel}.png'))
                page.get_by_role('button',name='＋ 新建对话',exact=True).click()
                expect(page.get_by_role('heading',name='新对话',exact=True)).to_be_visible()
                expect(page.locator('.formal-message')).to_have_count(0)
                assert not page.locator('.session-item[aria-current="page"]').count()
                sql("UPDATE platform_user SET account_status='DISABLED'")
                search.fill('会话')
                page.get_by_role('button',name='搜索',exact=True).click()
                page.wait_for_url('**/login')
                expect(page.locator('.session-item')).to_have_count(0)
                assert all(x in ['auth/login','auth/logout'] for x in writes), writes
                observations.append({'channel':channel,'version':browser.version,'mode':'headless PC, installed browser',
                    'cases':['real login','50+12 pagination','unloaded title search','literal HTML title','empty search',
                        'selection and TEXT history','revoked read only','refresh and highlight','local new intent','401 clears navigation'],
                    'businessWrites':0,'result':'PASS'})
                checked('42-browser-'+channel, True)
            finally:
                browser.close()
    sql("UPDATE platform_user SET account_status='ACTIVE'")
    (EVIDENCE/'browser-observations.json').write_text(json.dumps(observations,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-bin',type=Path,default=Path('D:/code environment/mysql/mysql-8.0.45-winx64/bin'))
    parser.add_argument('--browser',action='store_true')
    args = parser.parse_args()
    EVIDENCE.mkdir(parents=True,exist_ok=True)
    if args.browser:
        sys.path.insert(0,str(ROOT/'.codex-local/issue-42-browser'))
    ddl = sorted((ROOT/'db/schema').glob('*.sql'))
    count = sum(len(re.findall(r'CREATE TABLE (\w+)',p.read_text('utf-8'))) for p in ddl)
    jar = ROOT/'backend/target/issue42-build/agentscope-brain-backend-0.1.0-SNAPSHOT.jar'
    summary = web.base.verify(args.mysql_bin.resolve(),ddl_files=ddl,probes=[],expected_table_count=count,
        inspection_check=lambda _:0,concurrency_check=lambda _:0,extra_checks=lambda client:web.run_isolated(client,False,jar_path=jar,
            navigation_checks=lambda *values:check_navigation(*values,args.browser)))
    summary.pop('synthetic_rows_rolled_back',None)
    summary.update({'issue':42,'slice':'read-only session navigation','executedAt':datetime.now(timezone.utc).isoformat(),
        'temporaryInstanceRemoved':True,'sharedDatasetUsed':False,'syntheticNavigationRowsRemoved':True,
        'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'platformTables':count,'nativeTables':5,
        'browserTested':args.browser,'application_integration_tested':True,'realModelAcceptance':False})
    (EVIDENCE/'isolated-navigation.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('PASS: '+str(len(summary['cases']))+' navigation and inherited identity/history checks')


if __name__ == '__main__':main()