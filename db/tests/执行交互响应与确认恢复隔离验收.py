"""Issue #32：交互、确认恢复与 nonce 的合成数据库验证。

复用已有一次性 MySQL 实例生命周期；不连接既有数据库、不读取真实 keyring，
不加密、不签发 token、不运行 SDK 或工具。所有探针只证明数据库约束及条件 SQL。
"""
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
import re
import shutil
import subprocess
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
TESTS = Path(__file__).resolve().parent
BASE_PATH = TESTS / "执行记忆控制隔离验收.py"
spec = importlib.util.spec_from_file_location("isolated_memory_schema", BASE_PATH)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
DDL = [ROOT / "db/schema" / name for name in [
    "01_用户与管理员建表.sql", "02_会话运行消息建表.sql", "03_记忆控制建表.sql",
    "04_会话运行资料建表.sql", "05_员工配置发布与授权建表.sql",
    "08_Run调度可信调用与终结建表.sql", "09_交互响应与确认恢复建表.sql",
]]
PROBE = TESTS / "24_交互响应与确认恢复建表验收.sql"
INSPECTION = TESTS / "25_交互响应与确认恢复关联巡检.sql"
SEED = TESTS / "26_交互响应与确认恢复巡检种子.sql"
DIRECTED = TESTS / "27_交互响应与确认恢复定向异常验收.sql"
LEGACY_PROBE = TESTS / "03_通用素材与工具操作建表验收.sql"
CHECK_NAMES = re.findall(r"SELECT '(interaction_\w+)'", INSPECTION.read_text(encoding="utf-8"))


def query(client, sql, phase="交互 SQL"):
    return base.require_success(base.run(client, sql=sql.encode("utf-8"), timeout=180), phase)


def baseline():
    return SEED.read_text(encoding="utf-8").split("-- INSPECT_ALL_ZERO")[0]


def inspect_rows(client, mutation=""):
    rows = base.tab_rows(query(client, baseline() + mutation + ";\n" +
        INSPECTION.read_text(encoding="utf-8") + "ROLLBACK;\n"))
    if [r[0] for r in rows] != CHECK_NAMES:
        raise AssertionError(f"巡检输出数量或顺序不符：{rows}")
    return {name: int(value) for name, value in rows}


def run_constraints(client):
    sql = PROBE.read_text(encoding="utf-8")
    expected = [(int(c), n.split("|")) for c, n in re.findall(r"EXPECT_ERROR\s+(\d+)\s+([\w|]+)", sql)]
    result = base.run(client + ["--force"], sql=(baseline() + sql + "ROLLBACK;\n").encode("utf-8"), timeout=180)
    actual = [(int(m[1]), m[0].decode("utf-8", errors="replace"))
              for m in re.finditer(rb"ERROR\s+(\d+)[^\n]*", result.stderr)]
    # --force 正常拒绝由预期清单逐个核对，不能把其他 SQL 错误当作通过。
    output = base.require_success(result, "单表约束探针")
    if len(actual) != len(expected) or any(c != ac or not any(n in detail for n in names)
            for (c, names), (ac, detail) in zip(expected, actual)):
        mismatches = [i+1 for i, ((c, names), (ac, detail)) in enumerate(zip(expected, actual))
                      if c != ac or not any(n in detail for n in names)]
        raise AssertionError(f"约束拒绝不符：预期 {len(expected)} 项，实际 {len(actual)} 项，不匹配项 {mismatches}")
    passes = len(re.findall(r"-- EXPECT_PASS", sql))
    if output.splitlines() != ["PASS"] * passes:
        raise AssertionError(f"正向探针失败：{output}")
    return {"constraint_rejections": len(expected), "constraint_positive_checks": passes}


def run_inspections(client):
    clean = inspect_rows(client)
    if any(clean.values()):
        raise AssertionError(f"合法基线误报：{clean}")
    probes = DIRECTED.read_text(encoding="utf-8")
    markers = list(re.finditer(r"^-- EXPECT_INSPECTION (ANOMALY|CLEAN) (\w+)(?: (\w+))?[\t ]*$", probes, re.M))
    if not markers or len({m[2] for m in markers}) != len(markers):
        raise AssertionError("定向用例缺失或重名")
    cases = []
    for i, m in enumerate(markers):
        mode, name, target = m.groups()
        mutation = probes[m.end():markers[i+1].start() if i+1 < len(markers) else len(probes)]
        counts = inspect_rows(client, mutation)
        if mode == "CLEAN" and any(counts.values()):
            raise AssertionError(f"{name} 合法场景误报：{counts}")
        if mode == "ANOMALY" and (not target or counts.get(target, 0) <= 0):
            raise AssertionError(f"{name} 未命中目标 {target}：{counts}")
        cases.append({"case": name, "expected": mode, "target": target,
                      "target_anomalies": counts.get(target, 0) if target else 0})
    return {"inspection_checks": len(CHECK_NAMES), "inspection_names": CHECK_NAMES,
            "directed_anomaly_checks": sum(c["expected"] == "ANOMALY" for c in cases),
            "directed_positive_checks": sum(c["expected"] == "CLEAN" for c in cases),
            "directed_results": cases}


def constraint_race(client, scripts, constraint):
    with ThreadPoolExecutor(max_workers=len(scripts)) as pool:
        results = list(pool.map(lambda s: base.run(client, sql=s.encode("utf-8"), timeout=180), scripts))
    winners = sum(r.returncode == 0 for r in results)
    if winners != 1 or any(r.returncode and not (b"ERROR 1062" in r.stderr and constraint.encode() in r.stderr) for r in results):
        # 不转储 SQL 或秘密字段；这里只给错误码与获胜数量。
        raise AssertionError(f"{constraint} 竞争失败，成功数={winners}，返回码={[r.returncode for r in results]}")


def changed(client, script):
    return int(query(client, script).strip().splitlines()[-1])


def assert_empty(client):
    tables = query(client, "SHOW TABLES;").splitlines()
    if any(not re.fullmatch(r"[a-z_]+", t) for t in tables):
        raise AssertionError("非平台表名")
    remaining = int(query(client, "SELECT " + "+".join(f"(SELECT COUNT(*) FROM {t})" for t in tables) + ";").strip())
    if remaining:
        raise AssertionError(f"探针剩余合成行数：{remaining}")


def run_concurrency(client):
    query(client, baseline() + "COMMIT;")
    names = []
    now = "UTC_TIMESTAMP(6)"
    digest = "UNHEX(REPEAT('ab',32))"
    snapshot_hex = query(client, "SELECT HEX(snapshot_sha256) FROM interaction_revision WHERE id=950001;").strip()
    if not re.fullmatch(r"[0-9A-F]{64}", snapshot_hex):
        raise AssertionError("冻结展示摘要格式错误")
    expected_snapshot = f"UNHEX('{snapshot_hex}')"
    # L1→L7→L8→L11→L12→L13 的整体回滚，随后换连接读回原等待事实。
    query(client, f"""
START TRANSACTION;
SELECT id FROM platform_user WHERE id=950001 FOR UPDATE;
SELECT id FROM business_session WHERE id=950004 FOR UPDATE;
SELECT id FROM business_run WHERE id=950004 FOR UPDATE;
SELECT id FROM interaction_request WHERE id=950004 FOR UPDATE;
SELECT id FROM interaction_revision WHERE id=950004 FOR UPDATE;
SELECT id FROM tool_operation WHERE id=950004 FOR UPDATE;
SELECT id FROM operation_confirmation WHERE id=950004 FOR UPDATE;
SET @accepted_at={now};
INSERT INTO interaction_response (id,request_id,interaction_type,actor_user_id,submission_key,submission_sha256,accepted_revision_id,snapshot_sha256,accepted_at,status,consumed_at,gmt_create,gmt_modified)
SELECT 950004,950004,'OPERATION_CONFIRMATION',950001,UNHEX(REPEAT('98',16)),{digest},950004,snapshot_sha256,@accepted_at,'CONSUMED',@accepted_at,@accepted_at,@accepted_at
FROM interaction_revision WHERE id=950004;
UPDATE interaction_request SET status='ANSWERED' WHERE id=950004;
UPDATE tool_operation SET status='READY_TO_SEND' WHERE id=950004;
UPDATE operation_confirmation SET status='CONSUMED',consumed_at=@accepted_at WHERE id=950004;
ROLLBACK;
""")
    if query(client, """SELECT q.status,a.id IS NULL,k.status,o.status,k.consumed_at IS NULL,
k.expires_at=TIMESTAMPADD(MINUTE,10,k.gmt_create)
FROM interaction_request q LEFT JOIN interaction_response a ON a.request_id=q.id
JOIN operation_confirmation k ON k.id=q.confirmation_id JOIN tool_operation o ON o.id=k.operation_id WHERE q.id=950004;""").strip() != "WAITING\t1\tPENDING\tPROPOSED\t1\t1":
        raise AssertionError("确认整体回滚未保留原事实")
    names.append("confirmation_atomic_rollback_readback")
    # 同一 Run 两种交互共享唯一活动边界，三个活动状态都参加唯一性。
    query(client, "UPDATE interaction_request SET status='INVALIDATED' WHERE id=950001;")
    barrier = base.barrier_sql()
    scripts = []
    for i, (kind, status) in enumerate([("FORM", "PREPARING"), ("OPERATION_CONFIRMATION", "RECONCILING"), ("FORM", "PREPARING")]):
        scripts.append(barrier + f"""
INSERT INTO interaction_request (id,public_request_id,owner_user_id,session_id,run_id,employee_release_id,
 run_request_no,interaction_type,status,authorization_version,security_version,gmt_create,gmt_modified)
VALUES ({951001+i},UNHEX(LPAD(HEX({951001+i}),32,'0')),950001,950001,950001,950001,
 {2+i},'{kind}','{status}',1,1,{now},{now});
""")
    constraint_race(client, scripts, "uk_interaction_active_run")
    if query(client, "SELECT COUNT(*) FROM interaction_request WHERE active_run_id=950001;").strip() != "1":
        raise AssertionError("活动请求唯一读回失败")
    query(client, "DELETE FROM interaction_request WHERE id BETWEEN 951001 AND 951003; UPDATE interaction_request SET status='WAITING' WHERE id=950001;")
    names.append("shared_active_request_unique")

    # L1→L7→L8→L11：仅更新草稿区，展示字节与摘要不随竞争改变。
    barrier = base.barrier_sql()
    scripts = []
    for i in range(4):
        scripts.append(barrier + f"""
START TRANSACTION;
SELECT id FROM platform_user WHERE id=950001 FOR UPDATE;
SELECT id FROM business_session WHERE id=950001 FOR UPDATE;
SELECT id FROM business_run WHERE id=950001 FOR UPDATE;
SELECT id FROM interaction_request WHERE id=950001 FOR UPDATE;
UPDATE interaction_revision v JOIN interaction_request q ON q.current_revision_id=v.id
SET v.draft_data=JSON_OBJECT('syntheticWinner',{i}),v.draft_revision=v.draft_revision+1,
 v.last_save_request_key=UNHEX(LPAD(HEX({990000+i}),32,'0')),v.last_save_sha256={digest},v.gmt_modified={now}
WHERE q.id=950001 AND q.status='WAITING' AND q.interaction_type='FORM' AND q.revision_no=1
 AND v.id=950001 AND v.revision_no=1 AND v.draft_revision=1 AND v.snapshot_sha256={expected_snapshot};
SELECT ROW_COUNT(); COMMIT;
""")
    outputs = base.run_workers(client, scripts)
    if sorted(int(s.strip().splitlines()[-1]) for s in outputs) != [0,0,0,1]:
        raise AssertionError("草稿 CAS 竞争失败")
    if query(client, f"SELECT draft_revision,snapshot_sha256={expected_snapshot},UNHEX(SHA2(snapshot_body,256))={expected_snapshot} FROM interaction_revision WHERE id=950001;").strip() != "2\t1\t1":
        raise AssertionError("草稿或冻结展示读回失败")
    names.append("draft_cas_preserves_snapshot")
    for expected_version, expected_hash in [(1, expected_snapshot), (2, "UNHEX(REPEAT('00',32))")]:
        if changed(client, f"""UPDATE interaction_revision v JOIN interaction_request q ON q.current_revision_id=v.id
SET v.draft_revision=v.draft_revision+1,v.draft_data=JSON_OBJECT('stale',TRUE)
WHERE q.id=950001 AND q.status='WAITING' AND q.revision_no=1 AND v.id=950001
 AND v.draft_revision={expected_version} AND v.snapshot_sha256={expected_hash}; SELECT ROW_COUNT();""") != 0:
            raise AssertionError("旧草稿版本或错误展示摘要仍可写")
    names.append("stale_draft_version_and_snapshot_rejected")

    # 新修订将旧草稿封为只读；旧卡保存及旧 expectedDraftRevision 都更新零行。
    query(client, f"""
INSERT INTO interaction_revision (id,request_id,revision_no,interaction_type,contract_version,schema_dialect,snapshot_body,snapshot_sha256,draft_data,draft_revision,gmt_create,gmt_modified)
SELECT 950101,request_id,2,interaction_type,contract_version,schema_dialect,snapshot_body,snapshot_sha256,JSON_OBJECT(),1,{now},{now}
FROM interaction_revision WHERE id=950001;
UPDATE interaction_request SET current_revision_id=950101,revision_no=2,row_version=row_version+1 WHERE id=950001 AND status='WAITING' AND row_version=1;
""")
    old_save = """UPDATE interaction_revision v JOIN interaction_request q ON q.current_revision_id=v.id
SET v.draft_revision=v.draft_revision+1,v.draft_data=JSON_OBJECT('stale',TRUE)
WHERE q.id=950001 AND q.status='WAITING' AND q.revision_no=1 AND v.id=950001 AND v.draft_revision=2; SELECT ROW_COUNT();"""
    if changed(client, old_save) != 0:
        raise AssertionError("历史修订仍可写")
    names.append("historical_revision_draft_rejected")

    # FORM 接纳竞争：同一事务保存唯一回答、原 Run 正式消息和序号；不调用 SDK。
    barrier = base.barrier_sql()
    scripts = []
    for i in range(4):
        scripts.append(barrier + f"""
START TRANSACTION;
SELECT id FROM platform_user WHERE id=950001 FOR UPDATE;
SELECT id FROM memory_profile WHERE id=950001 FOR UPDATE;
SELECT id FROM business_session WHERE id=950001 FOR UPDATE;
SELECT id FROM business_run WHERE id=950001 FOR UPDATE;
SELECT id FROM interaction_request WHERE id=950001 FOR UPDATE;
SELECT id FROM interaction_revision WHERE id=950101 FOR UPDATE;
SET @ok=(SELECT COUNT(*) FROM interaction_request q JOIN interaction_revision v ON v.id=q.current_revision_id
 WHERE q.id=950001 AND q.status='WAITING' AND q.interaction_type='FORM' AND q.revision_no=2
 AND v.id=950101 AND v.draft_revision=1 AND v.snapshot_sha256={expected_snapshot}
 AND NOT EXISTS(SELECT 1 FROM interaction_response a WHERE a.request_id=q.id));
SET @accepted_at={now};
INSERT INTO business_message (id,session_id,user_id,employee_id,message_no,input_sequence,sender_kind,submission_key,submission_sha256,initial_run_id,accepted_at,gmt_create,gmt_modified)
SELECT 950101,950001,950001,950001,2,6,'USER',UNHEX(LPAD(HEX({981000+i}),32,'0')),{digest},950001,@accepted_at,@accepted_at,@accepted_at WHERE @ok=1;
INSERT INTO interaction_response (id,request_id,interaction_type,actor_user_id,submission_key,submission_sha256,accepted_revision_id,snapshot_sha256,raw_data,normalized_data,readable_text,accepted_message_id,accepted_at,status,gmt_create,gmt_modified)
SELECT 950001,950001,'FORM',950001,UNHEX(LPAD(HEX({981000+i}),32,'0')),{digest},950101,v.snapshot_sha256,JSON_OBJECT(),JSON_OBJECT(),'synthetic answer',950101,@accepted_at,'ACCEPTED',@accepted_at,@accepted_at
FROM interaction_revision v WHERE v.id=950101 AND @ok=1;
UPDATE interaction_request SET status='ANSWERED',row_version=row_version+1 WHERE id=950001 AND @ok=1;
UPDATE memory_profile SET last_input_sequence=6 WHERE id=950001 AND @ok=1;
UPDATE business_session SET last_message_no=2,row_version=row_version+1 WHERE id=950001 AND @ok=1;
SELECT @ok; COMMIT;
""")
    outputs = base.run_workers(client, scripts)
    if sorted(int(s.strip().splitlines()[-1]) for s in outputs) != [0,0,0,1]:
        raise AssertionError("FORM 唯一接纳竞争失败")
    state = query(client, """SELECT (SELECT COUNT(*) FROM interaction_response WHERE request_id=950001),
 (SELECT COUNT(*) FROM business_message WHERE initial_run_id=950001),
 (SELECT input_message_id FROM business_run WHERE id=950001),
 (SELECT initial_run_id FROM business_message WHERE id=950101),
 (SELECT last_input_sequence FROM memory_profile WHERE id=950001);""").strip()
    if state != "1\t2\t950001\t950001\t6":
        raise AssertionError("FORM 原 Run 接纳读回失败")
    names.append("form_acceptance_atomic_original_run")
    # 已回答请求不能借新修订或状态 CAS 复活；旧修订与同键不同语义均保留原事实。
    if changed(client, """UPDATE interaction_request q SET status='WAITING',row_version=row_version+1
WHERE q.id=950001 AND q.status IN ('PREPARING','WAITING') AND q.row_version=3
AND NOT EXISTS(SELECT 1 FROM interaction_response a WHERE a.request_id=q.id); SELECT ROW_COUNT();""") != 0:
        raise AssertionError("已回答请求被复活")
    if changed(client, """INSERT INTO interaction_revision (request_id,revision_no,interaction_type,contract_version,schema_dialect,snapshot_body,snapshot_sha256,draft_revision,gmt_create,gmt_modified)
SELECT q.id,3,'FORM','form-v1','draft-07','{}',UNHEX(SHA2('{}',256)),1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM interaction_request q WHERE q.id=950001 AND q.status='WAITING'
AND NOT EXISTS(SELECT 1 FROM interaction_response a WHERE a.request_id=q.id); SELECT ROW_COUNT();""") != 0:
        raise AssertionError("已回答请求新增可响应修订")
    names.append("answered_request_cannot_reopen")

    # 明确继续的领取 CAS，只引用合成 PREPARED 调用，不运行 SDK。
    query(client, f"""
INSERT INTO runtime_dispatch (id,dispatch_key,user_id,employee_id,session_id,run_id,employee_release_id,
 dispatch_kind,work_kind,source_kind,source_id,source_version,request_key,request_sha256,status,permit_state,boot_epoch,claim_fence,gmt_create,gmt_modified)
VALUES (950101,UNHEX(LPAD(HEX(995101),32,'0')),950001,950001,950001,950001,950001,
 'CONTINUATION','FORM_CONTINUE','INTERACTION_RESPONSE',950001,1,UNHEX(LPAD(HEX(995102),32,'0')),{digest},'WAITING','NONE',1,2,{now},{now});
INSERT INTO runtime_sdk_call (id,sdk_call_key,run_id,session_id,dispatch_id,call_no,user_id,employee_id,employee_release_id,
 state_session_key,state_generation,sdk_version,boot_epoch,claim_fence,executor_ref,call_kind,input_sha256,response_id,
 pending_id,pending_name,frozen_tool_use_sha256,status,store_version_capability,before_exists,before_store_version,
 before_state_sha256,before_evidence_ref,gmt_create,gmt_modified)
SELECT 950101,UNHEX(LPAD(HEX(995103),32,'0')),run_id,session_id,950101,2,user_id,employee_id,employee_release_id,
 state_session_key,state_generation,sdk_version,1,2,'synthetic-no-sdk','FORM_CONTINUE',input_sha256,950001,
 pending_id,pending_name,frozen_tool_use_sha256,'PREPARED','VERSIONED',1,saved_store_version,
 saved_state_sha256,'synthetic-before-950101',{now},{now} FROM runtime_sdk_call WHERE id=950001;
""")
    barrier = base.barrier_sql()
    outputs = base.run_workers(client, [barrier + f"""UPDATE interaction_response
SET status='CLAIMED',sdk_call_id=950101,claim_fence=2,before_checkpoint_ref='synthetic-before-950101',
 before_checkpoint_sha256={digest},continue_request_key=UNHEX(LPAD(HEX({993000+i}),32,'0')),
 continue_request_sha256={digest},row_version=row_version+1
WHERE id=950001 AND interaction_type='FORM' AND status='ACCEPTED' AND row_version=1;
SELECT ROW_COUNT();""" for i in range(4)])
    if sorted(int(s.strip()) for s in outputs) != [0,0,0,1]:
        raise AssertionError("FORM 明确继续领取 CAS 失败")
    if query(client, "SELECT status,sdk_call_id,claim_fence,row_version,accepted_message_id FROM interaction_response WHERE id=950001;").strip() != "CLAIMED\t950101\t2\t2\t950101":
        raise AssertionError("FORM 领取后接纳事实改变")
    names.append("form_continue_claim_cas_preserves_answer")

    # actor＋提交键唯一性的实际并发，独立于 request_id 唯一。
    barrier = base.barrier_sql()
    scripts = [barrier + f"""INSERT INTO interaction_response (request_id,interaction_type,actor_user_id,submission_key,submission_sha256,accepted_revision_id,snapshot_sha256,accepted_at,status,consumed_at,gmt_create,gmt_modified)
VALUES ({952000+i},'OPERATION_CONFIRMATION',950001,UNHEX(REPEAT('99',16)),{digest},950004,{digest},{now},'CONSUMED',{now},{now},{now});""" for i in range(4)]
    constraint_race(client, scripts, "uk_response_actor_submission")
    query(client, "DELETE FROM interaction_response WHERE request_id BETWEEN 952000 AND 952003;")
    names.append("actor_submission_unique")

    # L1→L7→L8→L11→L12→L13：确认一次消费、响应与 READY_TO_SEND 原子提交。
    barrier = base.barrier_sql()
    scripts = []
    for i in range(4):
        scripts.append(barrier + f"""
START TRANSACTION;
SELECT id FROM platform_user WHERE id=950001 FOR UPDATE;
SELECT id FROM business_session WHERE id=950004 FOR UPDATE;
SELECT id FROM business_run WHERE id=950004 FOR UPDATE;
SELECT id FROM interaction_request WHERE id=950004 FOR UPDATE;
SELECT id FROM interaction_revision WHERE id=950004 FOR UPDATE;
SELECT id FROM tool_operation WHERE id=950004 FOR UPDATE;
SELECT id FROM operation_confirmation WHERE id=950004 FOR UPDATE;
SET @ok=(SELECT COUNT(*) FROM interaction_request q JOIN operation_confirmation k ON k.id=q.confirmation_id
 JOIN tool_operation o ON o.id=k.operation_id WHERE q.id=950004 AND q.status='WAITING' AND q.interaction_type='OPERATION_CONFIRMATION'
 AND k.status='PENDING' AND k.recovery_status='AVAILABLE' AND k.expires_at>{now} AND o.status='PROPOSED');
SET @accepted_at={now};
INSERT INTO interaction_response (id,request_id,interaction_type,actor_user_id,submission_key,submission_sha256,accepted_revision_id,snapshot_sha256,accepted_at,status,consumed_at,gmt_create,gmt_modified)
SELECT 950004,q.id,'OPERATION_CONFIRMATION',950001,UNHEX(LPAD(HEX({982000+i}),32,'0')),{digest},950004,v.snapshot_sha256,@accepted_at,'CONSUMED',@accepted_at,@accepted_at,@accepted_at
FROM interaction_request q JOIN interaction_revision v ON v.id=q.current_revision_id WHERE q.id=950004 AND @ok=1;
UPDATE interaction_request SET status='ANSWERED',row_version=row_version+1 WHERE id=950004 AND @ok=1;
UPDATE tool_operation SET status='READY_TO_SEND' WHERE id=950004 AND @ok=1;
UPDATE operation_confirmation SET status='CONSUMED',consumed_at=@accepted_at WHERE id=950004 AND @ok=1;
SELECT @ok; COMMIT;
""")
    outputs = base.run_workers(client, scripts)
    if sorted(int(s.strip().splitlines()[-1]) for s in outputs) != [0,0,0,1]:
        raise AssertionError("确认消费竞争失败")
    state = query(client, """SELECT q.status,a.status,a.accepted_message_id IS NULL,k.status,o.status,
 k.expires_at=TIMESTAMPADD(MINUTE,10,k.gmt_create),a.consumed_at=k.consumed_at
FROM interaction_request q JOIN interaction_response a ON a.request_id=q.id
JOIN operation_confirmation k ON k.id=q.confirmation_id JOIN tool_operation o ON o.id=k.operation_id WHERE q.id=950004;""").strip()
    if state != "ANSWERED\tCONSUMED\t1\tCONSUMED\tREADY_TO_SEND\t1\t1":
        raise AssertionError("确认三方事实或原期限读回失败")
    names.append("confirmation_consumption_atomic_no_form_message")
    # 全部已提交竞争结果的独立只读巡检，禁止结构孤儿被竞争测试掩盖。
    counts = base.tab_rows(query(client, INSPECTION.read_text(encoding="utf-8")))
    if [r[0] for r in counts] != CHECK_NAMES or any(int(v) for _, v in counts):
        raise AssertionError(f"竞争后关联异常：{counts}")
    names.append("post_race_facts_inspected")

    # nonce 独立事务：合成 CAS／锁竞争只验证数据库，不加密、不证明真实签发流程。
    barrier = base.barrier_sql()
    script = barrier + f"""UPDATE crypto_nonce_cursor SET last_reserved_counter=last_reserved_counter+1,row_version=row_version+1,gmt_modified={now}
WHERE key_id='synthetic-key-v1' AND key_generation_ref='synthetic-generation-v1' AND nonce_prefix=UNHEX('11223344')
 AND row_version=1 AND last_reserved_counter<4294967295; SELECT ROW_COUNT();"""
    outputs = base.run_workers(client, [script]*4)
    if sorted(int(s.strip()) for s in outputs) != [0,0,0,1]:
        raise AssertionError("nonce CAS 竞争失败")
    names.append("nonce_expected_version_cas")
    if changed(client, """UPDATE crypto_nonce_cursor SET last_reserved_counter=last_reserved_counter+1,row_version=row_version+1
WHERE key_id='synthetic-key-v1' AND key_generation_ref='wrong-generation' AND row_version=2; SELECT ROW_COUNT();""") != 0:
        raise AssertionError("错误 key 代次预留成功")
    names.append("nonce_wrong_generation_rejected")
    if changed(client, """UPDATE crypto_nonce_cursor SET last_reserved_counter=last_reserved_counter+1,row_version=row_version+1
WHERE key_id='synthetic-key-v1' AND nonce_prefix=UNHEX('55667788') AND row_version=2; SELECT ROW_COUNT();""") != 0:
        raise AssertionError("错误 key 前缀预留成功")
    names.append("nonce_wrong_prefix_rejected")
    barrier = base.barrier_sql()
    reserve = barrier + """START TRANSACTION;
SELECT id FROM crypto_nonce_cursor WHERE key_id='synthetic-key-v1' FOR UPDATE;
UPDATE crypto_nonce_cursor SET last_reserved_counter=last_reserved_counter+1,row_version=row_version+1
WHERE key_id='synthetic-key-v1' AND key_generation_ref='synthetic-generation-v1' AND last_reserved_counter<4294967295;
SELECT last_reserved_counter FROM crypto_nonce_cursor WHERE key_id='synthetic-key-v1'; COMMIT;"""
    outputs = base.run_workers(client, [reserve]*4)
    counters = sorted(int(s.strip().splitlines()[-1]) for s in outputs)
    if counters != [4,5,6,7]:
        raise AssertionError("nonce 行锁预留计数不连续唯一")
    names.append("nonce_serial_reservations_unique")
    # 独立已提交游标不因后续领域事务回滚而退回。
    query(client, "START TRANSACTION; UPDATE operation_confirmation SET recovery_status='UNAVAILABLE' WHERE id=950004; ROLLBACK;")
    if query(client, "SELECT last_reserved_counter,row_version FROM crypto_nonce_cursor WHERE id=950001;").strip() != "7\t6":
        raise AssertionError("领域回滚破坏已提交游标")
    names.append("committed_nonce_survives_domain_rollback")
    query(client, "UPDATE crypto_nonce_cursor SET last_reserved_counter=4294967294,row_version=row_version+1 WHERE id=950001;")
    barrier = base.barrier_sql()
    outputs = base.run_workers(client, [barrier + """UPDATE crypto_nonce_cursor
SET last_reserved_counter=last_reserved_counter+1,row_version=row_version+1
WHERE key_id='synthetic-key-v1' AND last_reserved_counter<4294967295; SELECT ROW_COUNT();"""]*4)
    if sorted(int(s.strip()) for s in outputs) != [0,0,0,1]:
        raise AssertionError("nonce 计数上限竞争失败")
    if query(client, "SELECT last_reserved_counter FROM crypto_nonce_cursor WHERE id=950001;").strip() != "4294967295":
        raise AssertionError("nonce 上限回绕")
    names.append("nonce_exhaustion_no_wrap")

    # 只清空本执行器已校验归属的一次性实例；不接触任何外部资源。
    tables = query(client, "SHOW TABLES;").splitlines()
    if any(not re.fullmatch(r"[a-z_]+", t) for t in tables):
        raise AssertionError("非平台表名")
    query(client, "START TRANSACTION;\n" + "\n".join(f"DELETE FROM {t};" for t in tables) + "\nCOMMIT;")
    assert_empty(client)
    return {"concurrency_checks": len(names), "concurrency_names": names,
            "synthetic_nonce_reservation_counters": counters, "synthetic_rows_remaining": 0}


def extra_checks(client):
    legacy_rejections, legacy_passes = base.run_probe(client, LEGACY_PROBE)
    assert_empty(client)
    constraints = run_constraints(client)
    inspections = run_inspections(client)
    races = run_concurrency(client)
    stats = query(client, """SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE();
SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operation_confirmation'
 AND ((column_name='token_nonce' AND column_type='binary(12)') OR (column_name='token_ciphertext' AND column_type='varbinary(48)'));
""").splitlines()
    if stats != ['0','2']:
        raise AssertionError(f"触发器或恢复字段类型不符：{stats}")
    return {**constraints, **inspections, **races,
            "expected_rejections": constraints["constraint_rejections"],
            "positive_checks": constraints["constraint_positive_checks"],
            "legacy_operation_regression": {"rejections": legacy_rejections, "positive_checks": legacy_passes},
            "foreign_keys": 0, "triggers": 0,
            "model_calls": 0, "sdk_calls": 0, "tool_sends": 0, "crypto_operations": 0,
            "real_keyring_used": False, "real_sdk_consumption_proven": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    found = shutil.which("mysql")
    parser.add_argument("--mysql-bin", type=Path, default=Path(found).parent if found else None)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.mysql_bin is None:
        parser.error("请指定 --mysql-bin")
    with redirect_stdout(io.StringIO()):
        summary = base.verify(args.mysql_bin.resolve(), ddl_files=DDL, probes=[], expected_table_count=37,
            inspection_check=lambda _: 0, concurrency_check=lambda _: 0, extra_checks=extra_checks)
    report = {"issue": 32, "phase": "stage1-09", "executed_at": datetime.now(timezone(timedelta(hours=8))).isoformat(),
        "contract_refs": [
            "https://github.com/BeyondzychenProton/agentscope-brain/issues/22#issuecomment-6394913987",
            "https://github.com/BeyondzychenProton/agentscope-brain/issues/19#issuecomment-6359974007",
            "https://github.com/BeyondzychenProton/agentscope-brain/issues/23#issuecomment-6396363970"],
        "git_head": subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        "worktree": "codex/formal-web-implementation; existing changes preserved; no commit/push",
        "environment": "one-shot loopback MySQL; no-defaults; isolated datadir verified; synthetic data only",
        "summary": summary,
        "fingerprints": {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                         for p in sorted(set(DDL+[PROBE,INSPECTION,SEED,DIRECTED,LEGACY_PROBE,BASE_PATH,Path(__file__).resolve()]))},
        "claims": {"database_contracts_verified": True, "http_acceptance_verified": False,
            "sdk_consumption_verified": False, "real_crypto_or_key_recovery_verified": False,
            "independent_nonce_runtime_verified": False, "real_tool_send_verified": False}}
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in summary.items() if k != 'directed_results'},ensure_ascii=False),flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
