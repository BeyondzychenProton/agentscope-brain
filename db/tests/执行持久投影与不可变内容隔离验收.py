"""#33：持久投影数据库契约；只建立一次性回环 MySQL，合成业务与短事务，无 SDK/模型/网络工具。"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
TESTS = Path(__file__).resolve().parent
LEGACY_PATH = TESTS / '执行交互响应与确认恢复隔离验收.py'
spec = importlib.util.spec_from_file_location('interaction_contract_probe', LEGACY_PATH)
legacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(legacy)
base = legacy.base
DDL = legacy.DDL + [ROOT / 'db/schema/10_持久投影与不可变内容建表.sql']
PROBE = TESTS / '28_持久投影与不可变内容建表验收.sql'
INSPECTION = TESTS / '29_持久投影与不可变内容关联巡检.sql'
SEED = TESTS / '30_持久投影与不可变内容巡检种子.sql'
DIRECTED = TESTS / '31_持久投影与不可变内容定向异常验收.sql'
CHECK_NAMES = re.findall(r"SELECT '(projection_\w+)'", INSPECTION.read_text(encoding='utf-8'))


def query(client, sql, phase='投影 SQL'):
    return base.require_success(base.run(client, sql=sql.encode('utf-8'), timeout=180), phase)


def baseline():
    return legacy.baseline() + SEED.read_text(encoding='utf-8').split('-- INSPECT_ALL_ZERO')[0]


def bid(number):
    return f"UNHEX('{number:032x}')"


def inspection(client, mutation=''):
    rows = base.tab_rows(query(client, baseline() + mutation + '\n' + INSPECTION.read_text(encoding='utf-8') + 'ROLLBACK;'))
    if [r[0] for r in rows] != CHECK_NAMES:
        raise AssertionError(f'巡检结构不符：{rows}')
    return {name: int(value) for name, value in rows}


def constraints(client):
    sql = PROBE.read_text(encoding='utf-8')
    expected = [(int(c), names.split('|')) for c, names in re.findall(r'EXPECT_ERROR\s+(\d+)\s+([\w|]+)', sql)]
    result = base.run(client + ['--force'], sql=(baseline() + sql + 'ROLLBACK;').encode('utf-8'), timeout=180)
    output = base.require_success(result, '单行约束')
    actual = [(int(m[1]), m[0].decode('utf-8', errors='replace')) for m in re.finditer(rb'ERROR\s+(\d+)[^\n]*', result.stderr)]
    if len(actual) != len(expected) or any(code != got or not any(n in detail for n in names)
            for (code, names), (got, detail) in zip(expected, actual)):
        raise AssertionError(f'约束拒绝不符：expected={expected}; actual={actual}')
    passes = len(re.findall(r'-- EXPECT_PASS', sql))
    if output.splitlines() != ['PASS'] * passes:
        raise AssertionError(f'正向边界失败：{output}')
    return {'constraint_rejections': len(expected), 'constraint_positive_checks': passes}


def directed(client):
    clean = inspection(client)
    if any(clean.values()):
        raise AssertionError(f'合法投影误报：{clean}')
    sql = DIRECTED.read_text(encoding='utf-8')
    markers = list(re.finditer(r'^-- EXPECT_INSPECTION (ANOMALY|CLEAN) (\w+)(?: (\w+))?[\t ]*$', sql, re.M))
    if not markers or len({m[2] for m in markers}) != len(markers):
        raise AssertionError('定向场景缺失或重名')
    results = []
    for i, marker in enumerate(markers):
        mode, name, target = marker.groups()
        mutation = sql[marker.end():markers[i+1].start() if i+1 < len(markers) else len(sql)]
        try:
            counts = inspection(client, mutation)
        except Exception as exc:
            raise AssertionError(f'定向用例 {name} 执行失败') from exc
        if mode == 'CLEAN' and any(counts.values()):
            raise AssertionError(f'{name} 合法场景误报：{counts}')
        if mode == 'ANOMALY' and (not target or counts.get(target, 0) <= 0):
            raise AssertionError(f'{name} 未命中 {target}：{counts}')
        results.append({'case': name, 'expected': mode, 'target': target, 'actual': counts})
    return {'inspection_checks': len(CHECK_NAMES), 'inspection_names': CHECK_NAMES,
            'directed_anomaly_checks': sum(x['expected'] == 'ANOMALY' for x in results),
            'directed_positive_checks': sum(x['expected'] == 'CLEAN' for x in results), 'directed_results': results}


def empty(client):
    legacy.assert_empty(client)


def clear(client):
    # client 只可能来自 base.verify 已核验 datadir 的本轮临时实例。
    tables = query(client, 'SHOW TABLES;').splitlines()
    expected = {name for ddl in DDL for name in re.findall(r'CREATE TABLE (\w+)', ddl.read_text(encoding='utf-8'))}
    if set(tables) != expected or any(not re.fullmatch('[a-z_]+', t) for t in tables):
        raise AssertionError('拒绝清理非本轮平台表')
    query(client, 'START TRANSACTION;\n' + '\n'.join(f'DELETE FROM {t};' for t in tables) + '\nCOMMIT;')
    empty(client)


def reset(client, open_preview=False):
    clear(client)
    sql = baseline()
    if open_preview:
        sql += f"""
DELETE FROM session_projection_event WHERE session_id=950002 AND seq=3;
DELETE FROM projection_content_version WHERE content_id={bid(960001)} AND content_version=3;
DELETE FROM run_progress WHERE id=960003;
DELETE FROM message_content_block WHERE id=951002;
DELETE FROM business_message WHERE id=951002;
UPDATE projection_content SET current_version=2,status='OPEN',close_reason=NULL,replacement_message_id=NULL,row_version=1 WHERE content_id={bid(960001)};
UPDATE business_run SET status='RUNNING',ended_at=NULL,state_version_after=NULL WHERE id=950002;
UPDATE business_session SET last_message_no=2,last_projection_seq=2 WHERE id=950002;
"""
    query(client, sql + 'COMMIT;')


def assert_clean(client):
    rows = base.tab_rows(query(client, INSPECTION.read_text(encoding='utf-8')))
    if [r[0] for r in rows] != CHECK_NAMES or any(int(n) for _, n in rows):
        raise AssertionError(f'已提交结果巡检异常：{rows}')


def insert_batch(session, changes, condition='1'):
    return f"""
SET @occurred=UTC_TIMESTAMP(6);
SET @batch=CONVERT(CAST(JSON_OBJECT('protocolVersion','web-projection-v1','sessionId','{session}',
'streamId',BIN_TO_UUID({bid(session+1000)}),'seq',CAST(@seq AS CHAR),'previousSeq',CAST(@seq-1 AS CHAR),
'cursor',CONCAT('synthetic_{session}_',@seq),'occurredAt',DATE_FORMAT(@occurred,'%Y-%m-%dT%H:%i:%s.%fZ'),
'changes',{changes}) AS CHAR CHARACTER SET utf8mb4) USING binary);
INSERT INTO session_projection_event (session_id,stream_id,seq,previous_seq,protocol_version,batch_payload,byte_count,batch_sha256,change_count,occurred_at,gmt_create,gmt_modified)
SELECT {session},{bid(session+1000)},@seq,@seq-1,'web-projection-v1',@batch,OCTET_LENGTH(@batch),UNHEX(SHA2(@batch,256)),JSON_LENGTH(JSON_EXTRACT(CONVERT(@batch USING utf8mb4),'$.changes')),@occurred,@occurred,@occurred WHERE {condition};
"""


def interleaving(client, rollback):
    """实际观察两个连接的 InnoDB 锁等待及独立连接可见性，而非仅同时启动。"""
    reset(client)
    sid = 950004
    change = "JSON_ARRAY(JSON_OBJECT('type','SESSION_SET','title',@title))"
    def writer(title, label, hold, ending):
        return f"""START TRANSACTION;
SELECT GET_LOCK('{label}',0) INTO @marker;
SELECT last_projection_seq INTO @old FROM business_session WHERE id={sid} FOR UPDATE;
SET @seq=@old+1; SET @title='{title}';
UPDATE business_session SET title=@title,last_projection_seq=@seq WHERE id={sid};
{insert_batch(sid,change)}
DO SLEEP({hold});
{ending};
SELECT @seq;
DO RELEASE_LOCK('{label}');
"""
    # marker 在 B 申请 Session 锁之前取得，因此命名锁存在且 data_lock_waits>0 证明真实交错。
    with ThreadPoolExecutor(max_workers=2) as pool:
        a = pool.submit(query, client, writer('writer-a', 'issue33_a', 4, 'ROLLBACK' if rollback else 'COMMIT'))
        deadline = time.monotonic()+3
        while time.monotonic()<deadline:
            if query(client,"SELECT IS_USED_LOCK('issue33_a') IS NOT NULL;").strip()=='1':
                # 命名锁在行锁前；确认其已取得行锁后再启动 B。
                if int(query(client,"SELECT COUNT(*) FROM performance_schema.data_locks WHERE OBJECT_NAME='business_session' AND LOCK_STATUS='GRANTED';").strip())>0:
                    break
            time.sleep(0.04)
        else:
            raise AssertionError('写者 A 未进入持锁窗口')
        b = pool.submit(query, client, writer('writer-b', 'issue33_b', 0, 'COMMIT'))
        waits = 0
        while time.monotonic()<deadline:
            waits = int(query(client,'SELECT COUNT(*) FROM performance_schema.data_lock_waits;').strip())
            if waits:
                break
            time.sleep(0.04)
        if not waits:
            raise AssertionError('未观察到 B 等待 A 的 Session 锁')
        visible = query(client,f"SELECT last_projection_seq,COALESCE(title,'<NULL>'),(SELECT COUNT(*) FROM session_projection_event WHERE session_id={sid}) FROM business_session WHERE id={sid};").strip()
        if visible != '1\t<NULL>\t1':
            raise AssertionError(f'出现单边提交或脏读：{visible}')
        seqs = [int(a.result().strip()), int(b.result().strip())]
    expected = [2,2] if rollback else [2,3]
    if seqs != expected:
        raise AssertionError(f'分配顺序不符：{seqs}')
    state = query(client,f"SELECT last_projection_seq,title,(SELECT GROUP_CONCAT(seq ORDER BY seq) FROM session_projection_event WHERE session_id={sid}) FROM business_session WHERE id={sid};").strip()
    if state != ('2\twriter-b\t1,2' if rollback else '3\twriter-b\t1,2,3'):
        raise AssertionError(f'提交前缀读回失败：{state}')
    assert_clean(client)
    return {'case': 'writer_rollback_reuses_seq' if rollback else 'writers_commit_prefix',
            'allocated': seqs, 'observed_lock_waits': waits, 'uncommitted_read': visible, 'committed_read': state}


def finalizer(batch_hex, rollback=False, budget_failure=False):
    cid = bid(960001)
    # L7→L8→L10→L15；仅投影收尾子序列，不修改许可／记忆／授权。
    return f"""START TRANSACTION;
SELECT last_projection_seq INTO @last FROM business_session WHERE id=950002 FOR UPDATE;
SELECT status INTO @runstatus FROM business_run WHERE id=950002 FOR UPDATE;
SELECT id INTO @callid FROM runtime_sdk_call WHERE id=950102 FOR UPDATE;
SET @finish=(SELECT COUNT(*) FROM projection_content c JOIN runtime_sdk_call k ON k.id=950102
 WHERE c.content_id={cid} AND c.status='OPEN' AND c.current_version=2 AND @runstatus='RUNNING'
 AND k.run_id=950002 AND k.session_id=950002 AND k.user_id=950001 AND k.employee_release_id=950001
 AND k.status='VERIFIED' AND k.save_status='SAVED' AND k.readback_status='MATCHED'
 AND k.saved_state_sha256=k.readback_state_sha256 AND k.save_receipt_ref IS NOT NULL);
SET @seq=@last+1;
INSERT INTO business_message (id,session_id,user_id,message_no,sender_kind,produced_by_run_id,accepted_at,gmt_create,gmt_modified)
 SELECT 951002,950002,950001,3,'ASSISTANT',950002,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) WHERE @finish=1;
INSERT INTO message_content_block (id,message_id,block_no,block_kind,text_content,gmt_create,gmt_modified)
 SELECT 951002,951002,1,'TEXT',CONVERT(body USING utf8mb4),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM projection_content_version WHERE content_id={cid} AND content_version=2 AND @finish=1;
INSERT INTO projection_content_version (content_id,content_version,base_version,change_kind,content_status,close_reason,replacement_message_id,body_format,payload_storage,body,byte_count,body_sha256,source_kind,source_id,projection_seq,projection_position,gmt_create,gmt_modified)
 SELECT content_id,3,2,'CONTENT_CLOSE','PROMOTED','PROMOTED',951002,body_format,'INLINE',body,byte_count,body_sha256,'MESSAGE_BLOCK',951002,@seq,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
 FROM projection_content_version WHERE content_id={cid} AND content_version=2 AND @finish=1;
UPDATE projection_content SET current_version=3,status='PROMOTED',close_reason='PROMOTED',replacement_message_id=951002,row_version=row_version+1 WHERE content_id={cid} AND @finish=1;
UPDATE business_run SET status='SUCCEEDED',row_version=row_version+1,ended_at=UTC_TIMESTAMP(6),state_version_after=3 WHERE id=950002 AND @finish=1;
UPDATE business_session SET last_message_no=3,last_projection_seq=@seq WHERE id=950002 AND @finish=1;
SET @occurred=UTC_TIMESTAMP(6);
SET @batch=CONVERT(CAST(JSON_SET(CONVERT(UNHEX('{batch_hex}') USING utf8mb4),'$.occurredAt',DATE_FORMAT(@occurred,'%Y-%m-%dT%H:%i:%s.%fZ')) AS CHAR CHARACTER SET utf8mb4) USING binary);
{'SET @batch=CONVERT(CAST(JSON_OBJECT("oversized",REPEAT("x",1048576)) AS CHAR CHARACTER SET utf8mb4) USING binary);' if budget_failure else ''}
INSERT INTO session_projection_event (session_id,stream_id,seq,previous_seq,protocol_version,batch_payload,byte_count,batch_sha256,change_count,occurred_at,gmt_create,gmt_modified)
 SELECT 950002,{bid(951002)},@seq,@seq-1,'web-projection-v1',@batch,OCTET_LENGTH(@batch),UNHEX(SHA2(@batch,256)),3,@occurred,@occurred,@occurred WHERE @finish=1;
SELECT @finish;
{'ROLLBACK' if rollback else 'COMMIT'};
"""


def independent_readback(client):
    reset(client)
    rows = base.tab_rows(query(client,"""SELECT HEX(v.content_id),v.content_version,v.byte_count,HEX(v.body_sha256),
 HEX(CASE WHEN v.payload_storage='INLINE' THEN v.body WHEN v.source_kind='INTERACTION_REVISION' THEN r.snapshot_body ELSE CONVERT(b.text_content USING binary) END),v.source_kind,v.payload_storage
 FROM projection_content_version v LEFT JOIN interaction_revision r ON r.id=v.source_revision_id
 LEFT JOIN message_content_block b ON v.source_kind='MESSAGE_BLOCK' AND b.id=v.source_id ORDER BY v.content_id,v.content_version;"""))
    fingerprints = []
    for cid, ver, size, digest, body_hex, source, storage in rows:
        data = bytes.fromhex(body_hex)
        data.decode('utf-8', errors='strict')
        if len(data)!=int(size) or hashlib.sha256(data).hexdigest()!=digest.lower():
            raise AssertionError(f'独立 UTF-8/摘要读回失败：{cid}/{ver}')
        fingerprints.append({'content_id':cid.lower(),'version':int(ver),'bytes':len(data),'sha256':digest.lower(),'source':source,'storage':storage})
    first = query(client,f'SELECT HEX(body) FROM projection_content_version WHERE content_id={bid(960001)} AND content_version=1;').strip()
    if bytes.fromhex(first).decode('utf-8') != '合成正文：甲':
        raise AssertionError('旧版本被最新版替换')
    query(client,f'DELETE FROM projection_content_version WHERE content_id={bid(960010)} AND content_version=1;')
    if query(client,f'SELECT content_version FROM projection_content_version WHERE content_id={bid(960010)} AND content_version=1;').strip():
        raise AssertionError('缺失版本静默回退')
    # 从存储字节解析 JSON，额外检查递归禁用字段，报告仅保存摘要和计数。
    forbidden = {'token','confirmationtoken','token_ciphertext','token_nonce','ciphertext','sdkstate','rawthinking','apikey','systemprompt'}
    def check(value):
        if isinstance(value,dict):
            if any(k.lower() in forbidden for k in value):
                raise AssertionError('投影存在禁止字段')
            for v in value.values():
                check(v)
        elif isinstance(value,list):
            for v in value:
                check(v)
    batches = query(client,'SELECT HEX(batch_payload) FROM session_projection_event ORDER BY session_id,seq;').splitlines()
    for raw in batches:
        check(json.loads(bytes.fromhex(raw).decode('utf-8')))
    clear(client)
    return {'immutable_readback_versions':fingerprints,'independent_utf8_sha256_checks':len(rows),
            'old_version_retained':True,'missing_exact_version_no_fallback':True,'projection_json_without_forbidden_fields':len(batches)}


def append_text(expected_fence=2, rollback=False):
    cid=bid(960001)
    changes=f"""JSON_ARRAY(JSON_OBJECT('type','TEXT_APPEND','contentId',BIN_TO_UUID({cid}),
 'contentVersion','3','baseContentVersion','2','firstSeq','1','firstPosition',1,'status','OPEN',
 'byteCount',OCTET_LENGTH(@body),'sha256',LOWER(SHA2(@body,256)),'bodyFormat','markdown-v1','delta','；追加'))"""
    return f"""START TRANSACTION;
SELECT last_projection_seq INTO @last FROM business_session WHERE id=950002 FOR UPDATE;
SELECT status INTO @runstatus FROM business_run WHERE id=950002 FOR UPDATE;
SELECT id INTO @callid FROM runtime_sdk_call WHERE id=950102 FOR UPDATE;
SET @append=(SELECT COUNT(*) FROM projection_content c JOIN runtime_sdk_call k ON k.id=950102
 WHERE c.content_id={cid} AND c.status='OPEN' AND c.current_version=2 AND @runstatus='RUNNING'
 AND k.run_id=c.run_id AND k.claim_fence={expected_fence});
SET @seq=@last+1;
SELECT CONCAT(body,CONVERT('；追加' USING binary)) INTO @body FROM projection_content_version WHERE content_id={cid} AND content_version=2;
INSERT INTO projection_content_version (content_id,content_version,base_version,change_kind,content_status,body_format,payload_storage,body,byte_count,body_sha256,source_kind,source_id,projection_seq,projection_position,gmt_create,gmt_modified)
 SELECT {cid},3,2,'TEXT_APPEND','OPEN','markdown-v1','INLINE',@body,OCTET_LENGTH(@body),UNHEX(SHA2(@body,256)),'RUN_PROGRESS',960004,@seq,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) WHERE @append=1;
INSERT INTO run_progress (id,run_id,event_no,event_kind,display_text,occurred_at,payload_version,content_id,content_version,sdk_call_key,source_kind,source_id,first_projection_seq,first_projection_position,content_status,projection_stream_id,projection_seq,projection_position,gmt_create,gmt_modified)
 SELECT 960004,950002,3,'TEXT_DELTA','；追加',UTC_TIMESTAMP(6),1,{cid},3,{bid(1250102)},'MODEL_TEXT',950102,1,1,'OPEN',{bid(951002)},@seq,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) WHERE @append=1;
UPDATE projection_content SET current_version=3,row_version=row_version+1 WHERE content_id={cid} AND @append=1;
UPDATE business_session SET last_projection_seq=@seq WHERE id=950002 AND @append=1;
{insert_batch(950002,changes,'@append=1')}
SELECT @append;
{'ROLLBACK' if rollback else 'COMMIT'};
"""


def concurrency(client):
    reset(client)
    batch_hex = query(client,'SELECT HEX(batch_payload) FROM session_projection_event WHERE session_id=950002 AND seq=3;').strip()
    results = [interleaving(client,False), interleaving(client,True)]
    reset(client,True)
    query(client,finalizer(batch_hex,rollback=True))
    state = query(client,f"SELECT r.status,c.status,c.current_version,s.last_projection_seq,s.last_message_no,(SELECT COUNT(*) FROM business_message WHERE produced_by_run_id=950002) FROM business_run r JOIN business_session s ON s.id=r.session_id JOIN projection_content c ON c.content_id={bid(960001)} WHERE r.id=950002;").strip()
    if state!='RUNNING\tOPEN\t2\t2\t2\t0':
        raise AssertionError('收尾回滚留下单边事实')
    results.append({'case':'finalization_rollback','readback':state})
    failed = base.run(client,sql=finalizer(batch_hex,budget_failure=True).encode('utf-8'))
    if failed.returncode==0 or b'chk_projection_event_bytes' not in failed.stderr:
        raise AssertionError('超预算批次未在提交前拒绝')
    if query(client,'SELECT COUNT(*) FROM business_message WHERE produced_by_run_id=950002;').strip()!='0':
        raise AssertionError('预算失败遗留正式回复')
    if query(client,'SELECT last_projection_seq,last_message_no FROM business_session WHERE id=950002;').strip()!='2\t2':
        raise AssertionError('预算失败分裂序号或消息号')
    assert_clean(client)
    results.append({'case':'budget_rejection_rolls_back_whole_finalization','reply_count':0,'head_seq':2})
    outputs = base.run_workers(client,[base.barrier_sql()+finalizer(batch_hex)]*4)
    winners = sorted(int(s.strip()) for s in outputs)
    if winners != [0,0,0,1]:
        raise AssertionError(f'重复收尾竞争不符：{winners}')
    state = query(client,f"SELECT COUNT(*),(SELECT COUNT(*) FROM session_projection_event WHERE session_id=950002 AND seq=3),(SELECT current_version FROM projection_content WHERE content_id={bid(960001)}) FROM business_message WHERE produced_by_run_id=950002;").strip()
    if state!='1\t1\t3':
        raise AssertionError('重复收尾增加消息或成功批次')
    assert_clean(client)
    results.append({'case':'four_finalizers_exactly_once','affected':winners,'reply_batch_version':state})
    if query(client,finalizer(batch_hex)).strip()!='0':
        raise AssertionError('重复回调重做收尾')
    results.append({'case':'repeated_finalization_returns_existing_fact','affected':0})
    # 使用与实际合成追加相同的完整入口验证迟到、旧 fence 与前驱竞争。
    if query(client,append_text()).strip()!='0':
        raise AssertionError('提升后追加通过')
    results.append({'case':'late_append_after_promotion_rejected','affected':0})
    reset(client,True)
    if query(client,append_text(expected_fence=1)).strip()!='0':
        raise AssertionError('旧调用 fence 可写')
    results.append({'case':'stale_call_fence_rejected','affected':0})
    if query(client,append_text(rollback=True)).strip()!='1':
        raise AssertionError('追加回滚未执行预期路径')
    if query(client,f'SELECT current_version FROM projection_content WHERE content_id={bid(960001)};').strip()!='2':
        raise AssertionError('追加回滚仍推进版本')
    results.append({'case':'append_rollback_preserves_version_and_batch','version':2})
    outputs=base.run_workers(client,[base.barrier_sql()+append_text()]*4)
    winners=sorted(int(s.strip()) for s in outputs)
    if winners!=[0,0,0,1]:
        raise AssertionError('相同前驱的实际追加竞争失败')
    assert_clean(client)
    results.append({'case':'four_appenders_one_version_and_batch','affected':winners,'version':3})
    if query(client,append_text()).strip()!='0':
        raise AssertionError('旧内容前驱可再次追加')
    results.append({'case':'stale_content_predecessor_rejected','affected':0})
    # CLOSED 片段以原 Session/Run 锁序检查；失败 Run 与封闭状态均不得重开。
    closed = f"""START TRANSACTION;
SELECT id INTO @s FROM business_session WHERE id=950003 FOR UPDATE;
SELECT id INTO @r FROM business_run WHERE id=950003 FOR UPDATE;
UPDATE projection_content SET row_version=row_version+1 WHERE content_id={bid(960002)} AND status='OPEN'; SELECT ROW_COUNT(); COMMIT;"""
    if query(client,closed).strip()!='0':
        raise AssertionError('封闭后追加通过')
    results.append({'case':'closed_content_append_rejected','affected':0})
    assert_clean(client)
    results.append({'case':'committed_facts_cross_inspected','checks':len(CHECK_NAMES)})
    clear(client)
    return {'concurrency_checks':len(results),'concurrency_results':results,'synthetic_rows_remaining':0}


def extra(client):
    contract = constraints(client)
    cases = directed(client)
    empty(client)
    readback = independent_readback(client)
    races = concurrency(client)
    # 现有正式消息唯一性、工具/确认和交互正反约束，在投影扩展后回归。
    prior_errors,prior_passes = base.run_probe(client,legacy.LEGACY_PROBE)
    prior_contract = legacy.run_constraints(client)
    prior_cases = legacy.run_inspections(client)
    empty(client)
    stats = query(client,'SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE();').strip()
    if stats!='0':
        raise AssertionError('出现触发器')
    mysql_settings=query(client,"SELECT @@sql_mode,@@character_set_database,@@collation_database,@@transaction_isolation,@@time_zone,@@system_time_zone;").strip().split('\t')
    return {**contract,**cases,**readback,**races,'expected_rejections':contract['constraint_rejections'],
            'positive_checks':contract['constraint_positive_checks'],
            'synthetic_rows_rolled_back':False,'committed_synthetic_rows_deleted':True,
            'mysql_settings':dict(zip(['sql_mode','database_charset','database_collation','isolation','time_zone','system_time_zone'],mysql_settings)),
            'legacy_regression':{'tool_and_message_rejections':prior_errors,'tool_and_message_positive':prior_passes,
                'interaction_constraints':prior_contract,'interaction_anomalies':prior_cases['directed_anomaly_checks'],
                'interaction_clean':prior_cases['directed_positive_checks']},
            'foreign_keys':0,'triggers':0,'model_calls':0,'sdk_calls':0,'tool_sends':0,'real_crypto_operations':0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    found = shutil.which('mysql')
    parser.add_argument('--mysql-bin',type=Path,default=Path(found).parent if found else None)
    parser.add_argument('--report',type=Path)
    args = parser.parse_args()
    if args.mysql_bin is None:
        parser.error('请指定 --mysql-bin')
    with redirect_stdout(io.StringIO()):
        summary = base.verify(args.mysql_bin.resolve(),ddl_files=DDL,probes=[],expected_table_count=40,
            inspection_check=lambda _:0,concurrency_check=lambda _:0,extra_checks=extra)
    inputs = sorted(set(DDL+[PROBE,INSPECTION,SEED,DIRECTED,LEGACY_PATH,legacy.SEED,legacy.PROBE,legacy.DIRECTED,
        legacy.INSPECTION,legacy.LEGACY_PROBE,legacy.BASE_PATH,Path(__file__).resolve()]))
    binary_fingerprints={}
    for name in ('mysql.exe','mysqld.exe','mysqladmin.exe'):
        path=args.mysql_bin/name
        if path.is_file():
            with path.open('rb') as stream:
                binary_fingerprints[name]=hashlib.file_digest(stream,'sha256').hexdigest()
    report = {'issue':33,'phase':'stage1-10','executed_at':datetime.now(timezone(timedelta(hours=8))).isoformat(),
        'contract_refs':[
            'https://github.com/BeyondzychenProton/agentscope-brain/issues/22#issuecomment-6394913987',
            'https://github.com/BeyondzychenProton/agentscope-brain/issues/20#issuecomment-6361323849',
            'https://github.com/BeyondzychenProton/agentscope-brain/issues/23#issuecomment-6396363970'],
        'git_head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        'worktree':'codex/formal-web-implementation; pre-existing changes preserved; no commit/push',
        'environment':'temporary no-defaults loopback MySQL; datadir ownership verified; synthetic only',
        'runtime':{'python':platform.python_version(),'os':platform.platform(),'dependencies':'Python standard library; MySQL CLI',
            'mysql_binary_sha256':binary_fingerprints,'java_build_or_release_loaded':False},
        'synthetic_release_id':950001,'summary':summary,
        'fingerprints':{p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},
        'claims':{'database_contracts_verified':True,'application_projection_verified':False,'real_finalization_verified':False,
            'snapshot_or_watermark_api_verified':False,'sse_or_reducer_verified':False,'sdk_persistence_verified':False}}
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in summary.items() if k not in ('directed_results','immutable_readback_versions')},ensure_ascii=False),flush=True)
    return 0


if __name__=='__main__':
    raise SystemExit(main())
