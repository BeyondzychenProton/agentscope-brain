"""Issue #31：隔离验收 Run CAS、runtime_capacity／dispatch、sdk_call 与独立终结证据。

新建一次性本机 MySQL 实例；复用记忆控制执行器的生命周期。不读取登录路径，
不连接既有开发库或 Issue #29／#30 预置事实。SQL 约束／竞争／巡检不构成真实 SDK、
容量调度或无执行终结通过证明。
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import secrets
import shutil
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
TESTS = Path(__file__).resolve().parent
BASE_PATH = TESTS / "执行记忆控制隔离验收.py"
spec = importlib.util.spec_from_file_location("isolated_memory_schema", BASE_PATH)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

DDL = [ROOT / "db/schema" / name for name in [
    "01_用户与管理员建表.sql",
    "02_会话运行消息建表.sql",
    "03_记忆控制建表.sql",
    "04_会话运行资料建表.sql",
    "05_员工配置发布与授权建表.sql",
    "08_Run调度可信调用与终结建表.sql",
]]
CONSTRAINT_PROBE = TESTS / "20_Run调度可信调用与终结建表验收.sql"
INSPECTION = TESTS / "21_Run调度可信调用与终结关联巡检.sql"
INSPECTION_SEED = TESTS / "22_Run调度可信调用与终结巡检种子.sql"
DIRECTED = TESTS / "23_Run调度可信调用与终结定向异常验收.sql"
EXPECTED_TABLES = 33
EXPECTED_INSPECTIONS = 9
CONTROL_TABLES = (
    "runtime_capacity",
    "runtime_dispatch",
    "runtime_sdk_call",
    "runtime_state_terminalization",
)


def query(client, sql, phase="SQL"):
    return base.require_success(base.run(client, sql=sql.encode("utf-8"), timeout=180), phase)


def assert_empty_control_tables(client):
    remaining = int(query(client, f"""
SELECT (SELECT COUNT(*) FROM runtime_capacity)
     + (SELECT COUNT(*) FROM runtime_dispatch)
     + (SELECT COUNT(*) FROM runtime_sdk_call)
     + (SELECT COUNT(*) FROM runtime_state_terminalization);
""").strip())
    if remaining:
        raise AssertionError(f"控制表仍有 {remaining} 行合成数据")
    return remaining


def run_inspection(client):
    seed = INSPECTION_SEED.read_text(encoding="utf-8")
    baseline, rest = seed.split("-- INSPECT_ALL_ZERO")
    anomalies = rest.split("-- INSPECT_ALL_POSITIVE")[0]
    inspection = INSPECTION.read_text(encoding="utf-8")
    script = baseline + inspection + "SELECT 'MARK',0;\n" + anomalies + inspection + "ROLLBACK;\n"
    rows = base.tab_rows(query(client, script, "调度／调用／终结巡检"))
    marks = [i for i, row in enumerate(rows) if row[0] == "MARK"]
    if len(marks) != 1:
        raise AssertionError(f"巡检输出缺标记：{rows}")
    good, bad = rows[:marks[0]], rows[marks[0] + 1:]
    if len(good) != EXPECTED_INSPECTIONS or any(value != "0" for _, value in good):
        raise AssertionError(f"基线误报：{good}")
    if [n for n, _ in bad] != [n for n, _ in good] or any(int(v) <= 0 for _, v in bad):
        raise AssertionError(f"异常漏报：{bad}")
    return {"inspection_checks": len(bad), "inspection_names": [n for n, _ in bad]}


def run_directed(client):
    baseline = INSPECTION_SEED.read_text(encoding="utf-8").split("-- INSPECT_ALL_ZERO")[0]
    inspection = INSPECTION.read_text(encoding="utf-8")
    probes = DIRECTED.read_text(encoding="utf-8")
    markers = list(re.finditer(
        r"^-- EXPECT_INSPECTION (ANOMALY|CLEAN) (\w+)(?: (\w+))?[\t ]*$", probes, re.M))
    if not markers or len({m[2] for m in markers}) != len(markers):
        raise AssertionError("定向巡检缺少用例或标识重复")
    results = []
    for i, marker in enumerate(markers):
        mode, name, target = marker.groups()
        mutation = probes[marker.end():markers[i + 1].start() if i + 1 < len(markers) else len(probes)]
        rows = base.tab_rows(query(client, baseline + mutation + inspection + "ROLLBACK;\n", f"定向 {name}"))
        if len(rows) != EXPECTED_INSPECTIONS or len(dict(rows)) != EXPECTED_INSPECTIONS:
            raise AssertionError(f"{name} 巡检组数不符：{rows}")
        counts = {key: int(count) for key, count in rows}
        if mode == "CLEAN":
            if any(counts.values()):
                raise AssertionError(f"{name} 合法场景误报：{counts}")
        elif not target or counts.get(target, 0) <= 0:
            raise AssertionError(f"{name} 未命中 {target}：{counts}")
        results.append({"case": name, "expected": mode, "target": target,
                        "target_anomalies": counts.get(target, 0) if target else 0})
    return {"directed_anomaly_checks": sum(r["expected"] == "ANOMALY" for r in results),
            "directed_positive_checks": sum(r["expected"] == "CLEAN" for r in results),
            "directed_inspection_results": results}


def run_constraint_race(client, scripts, expected_constraint):
    with ThreadPoolExecutor(max_workers=len(scripts)) as pool:
        futures = [pool.submit(base.run, client, sql=s.encode("utf-8"), timeout=180) for s in scripts]
        results = [f.result() for f in futures]
    winners = 0
    for result in results:
        if result.returncode == 0:
            winners += 1
        elif not (b"ERROR 1062" in result.stderr and expected_constraint.encode() in result.stderr):
            raise AssertionError(result.stderr.decode("utf-8", errors="replace")[-2000:])
    if winners != 1:
        raise AssertionError(f"{expected_constraint} 并发成功数应为 1，实际 {winners}")


def sql_now():
    return "UTC_TIMESTAMP(6)"


def prepare_fixtures(client):
    now = sql_now()
    digest = "UNHEX(REPEAT('ab',32))"
    query(client, f"""
INSERT INTO platform_user (id,display_name,status_changed_at,gmt_create,gmt_modified)
VALUES (942001,'竞争探针用户',{now},{now},{now});
INSERT INTO business_session (id,user_id,employee_id,employee_release_id,state_session_key,last_activity_at,gmt_create,gmt_modified)
VALUES (942001,942001,1,1,REPEAT('e',32),{now},{now},{now});
INSERT INTO business_message (id,session_id,user_id,employee_id,message_no,input_sequence,sender_kind,submission_key,submission_sha256,accepted_at,gmt_create,gmt_modified)
VALUES (942001,942001,942001,1,1,1,'USER',UNHEX(REPEAT('b1',16)),{digest},{now},{now},{now});
INSERT INTO business_run (id,session_id,input_message_id,actor_user_id,employee_release_id,memory_generation_at_start,memory_revision_at_start,memory_policy_version,run_request_key,request_sha256,status,row_version,gmt_create,gmt_modified)
VALUES (942001,942001,942001,942001,1,1,1,'p1',UNHEX(REPEAT('b2',16)),{digest},'ACCEPTED',1,{now},{now});
UPDATE business_message SET initial_run_id=942001 WHERE id=942001;
INSERT INTO runtime_capacity (id,singleton_key,foreground_limit,normal_waiting_limit,occupied_count,normal_waiting_count,boot_epoch,row_version,gmt_create,gmt_modified)
VALUES (1,1,2,16,0,0,1,1,{now},{now});
""")


def run_concurrency(client):
    """锁序子序列内的 CAS、领取竞争、容量计数与回滚读回。"""
    names = []
    now = sql_now()
    digest = "UNHEX(REPEAT('ab',32))"
    prepare_fixtures(client)

    # 1 business_run.row_version CAS：旧版本不能覆盖新事实；WAITING_INPUT 仍占 busy。
    barrier = base.barrier_sql()
    scripts = []
    for status in ("RUNNING", "WAITING_INPUT", "RUNNING", "WAITING_INPUT"):
        body = (
            f"UPDATE business_run SET status='{status}', row_version=row_version+1, gmt_modified={now} "
            f"WHERE id=942001 AND row_version=1; SELECT ROW_COUNT();"
        )
        scripts.append(barrier + body)
    outputs = base.run_workers(client, scripts)
    if sorted(int(s.strip()) for s in outputs) != [0, 0, 0, 1]:
        raise AssertionError(f"Run CAS 失败：{outputs}")
    run_state = query(client, "SELECT status,row_version,busy_session_id FROM business_run WHERE id=942001;").strip()
    if not re.fullmatch(r"(RUNNING|WAITING_INPUT)\t2\t942001", run_state):
        raise AssertionError(f"Run CAS 结果异常：{run_state}")
    names.append("business_run_cas_waiting_input_busy")

    # 2 同 Session HELD 许可唯一；并发领取恰一条成功。
    for i in range(4):
        key = secrets.token_hex(16)
        query(client, f"""
INSERT INTO runtime_dispatch (
 dispatch_key,user_id,employee_id,session_id,run_id,employee_release_id,dispatch_kind,work_kind,
 source_kind,source_id,source_version,request_key,request_sha256,status,permit_state,
 counts_toward_waiting,boot_epoch,gmt_create,gmt_modified)
VALUES (UNHEX('{key}'),942001,1,942001,942001,1,'ORDINARY','ORIGINAL_RUN_{i}',
 'ORDINARY_SUBMISSION',{4200 + i},1,UNHEX(REPEAT('{i+1:02x}',16)),{digest},
 'WAITING','NONE',1,1,{now},{now});
""")
    query(client, f"UPDATE runtime_capacity SET normal_waiting_count=4, row_version=row_version+1, gmt_modified={now} WHERE id=1;")
    ids = [int(x) for x in query(client, "SELECT id FROM runtime_dispatch WHERE session_id=942001 ORDER BY id;").splitlines()]
    barrier = base.barrier_sql()
    claim_scripts = []
    for did in ids:
        token = secrets.token_hex(16)
        body = f"""
START TRANSACTION;
SELECT id FROM runtime_capacity WHERE singleton_key=1 FOR UPDATE;
SELECT id FROM business_run WHERE id=942001 FOR UPDATE;
UPDATE runtime_dispatch SET status='CLAIMED', permit_state='HELD', counts_toward_waiting=0,
 claim_token=UNHEX('{token}'), claimed_at={now}, claim_fence=claim_fence+1, row_version=row_version+1, gmt_modified={now}
WHERE id={did} AND status='WAITING' AND permit_state='NONE' AND boot_epoch=1
  AND NOT EXISTS (
    SELECT 1 FROM (
      SELECT held.id FROM runtime_dispatch AS held
      WHERE held.session_id=942001 AND held.permit_state='HELD'
    ) AS already_held
  );
SET @claimed = ROW_COUNT();
UPDATE runtime_capacity SET occupied_count=occupied_count+1, normal_waiting_count=normal_waiting_count-1,
 row_version=row_version+1, gmt_modified={now}
WHERE singleton_key=1 AND @claimed=1 AND occupied_count < foreground_limit AND normal_waiting_count > 0;
SELECT @claimed;
COMMIT;
"""
        claim_scripts.append(barrier + body)
    outputs = base.run_workers(client, claim_scripts)
    success_claims = sorted(int(s.strip().splitlines()[-1]) for s in outputs)
    if success_claims != [0, 0, 0, 1]:
        raise AssertionError(f"HELD 领取竞争失败：{outputs}")
    held = query(client, "SELECT COUNT(*),SUM(permit_state='HELD'),SUM(counts_toward_waiting=1) FROM runtime_dispatch WHERE session_id=942001;").strip()
    cap = query(client, "SELECT occupied_count,normal_waiting_count FROM runtime_capacity WHERE id=1;").strip()
    if held != "4\t1\t3" or cap != "1\t3":
        raise AssertionError(f"HELD 领取后计数异常 held={held} cap={cap}")
    names.append("dispatch_held_unique_with_c0")

    # 3 旧 epoch 领取条件失败。
    query(client, f"UPDATE runtime_capacity SET boot_epoch=2, row_version=row_version+1, gmt_modified={now} WHERE id=1;")
    waiting_id = query(client, "SELECT id FROM runtime_dispatch WHERE session_id=942001 AND status='WAITING' ORDER BY id LIMIT 1;").strip()
    old_claim = base.run(client, sql=f"""
START TRANSACTION;
SELECT id FROM runtime_capacity WHERE singleton_key=1 FOR UPDATE;
UPDATE runtime_dispatch SET status='CLAIMED', permit_state='HELD', counts_toward_waiting=0,
 claim_token=UNHEX(REPEAT('ee',16)), claimed_at={now}, claim_fence=claim_fence+1, row_version=row_version+1, gmt_modified={now}
WHERE id={waiting_id} AND status='WAITING' AND boot_epoch=(SELECT boot_epoch FROM runtime_capacity WHERE singleton_key=1);
SELECT ROW_COUNT();
COMMIT;
""".encode("utf-8"), timeout=60)
    if old_claim.returncode != 0:
        raise AssertionError(old_claim.stderr.decode("utf-8", errors="replace")[-1000:])
    if old_claim.stdout.decode().strip().splitlines()[-1] != "0":
        raise AssertionError("旧 epoch 等待行不应按新 epoch 领取成功")
    names.append("old_epoch_claim_rejected")

    # 4 来源去重：同 source_kind/source_id/source_version/work_kind 并发插入恰一条成功。
    barrier = base.barrier_sql()
    source_scripts = []
    for i in range(4):
        source_scripts.append(barrier + f"""
INSERT INTO runtime_dispatch (
 dispatch_key,user_id,employee_id,session_id,run_id,employee_release_id,dispatch_kind,work_kind,
 source_kind,source_id,source_version,request_key,request_sha256,status,permit_state,boot_epoch,gmt_create,gmt_modified)
VALUES (UNHEX('{secrets.token_hex(16)}'),942001,1,942001,942001,1,'CONTINUATION','FORM_CONTINUE',
 'INTERACTION_RESPONSE',5001,1,UNHEX(REPEAT('{i+10:02x}',16)),{digest},'WAITING','NONE',2,{now},{now});
""")
    run_constraint_race(client, source_scripts, "uk_dispatch_source_action")
    names.append("dispatch_source_dedupe")

    # 5 终结键条件转换：同一键旧版本不覆盖；锁序 C0→Run→sdk_call→terminalization。
    call_key = secrets.token_hex(16)
    term_key = secrets.token_hex(16)
    held_id = query(client, "SELECT id FROM runtime_dispatch WHERE permit_state='HELD' AND session_id=942001 LIMIT 1;").strip()
    query(client, f"""
INSERT INTO runtime_sdk_call (
 sdk_call_key,run_id,session_id,dispatch_id,call_no,user_id,employee_id,employee_release_id,
 state_session_key,state_generation,sdk_version,boot_epoch,claim_fence,executor_ref,call_kind,input_sha256,
 status,store_version_capability,before_exists,gmt_create,gmt_modified)
VALUES (UNHEX('{call_key}'),942001,942001,{held_id},1,942001,1,1,REPEAT('e',32),1,'2.0.4',2,1,'worker-race',
 'ORDINARY',{digest},'PREPARED','UNAVAILABLE',0,{now},{now});
""")
    call_id = query(client, f"SELECT id FROM runtime_sdk_call WHERE sdk_call_key=UNHEX('{call_key}');").strip()
    query(client, f"""
INSERT INTO runtime_state_terminalization (
 terminalization_key,sdk_call_id,user_id,session_id,run_id,state_session_key,state_generation,
 pending_id,pending_name,frozen_tool_use_sha256,quiescence_proof_sha256,boot_epoch,claim_fence,
 before_state_sha256,reason_code,zero_execution_proof_sha256,store_version_capability,status,row_version,
 gmt_create,gmt_modified)
VALUES (UNHEX('{term_key}'),{call_id},942001,942001,942001,REPEAT('e',32),1,
 'pending-race','request_user_input',{digest},UNHEX(REPEAT('cd',32)),2,1,
 {digest},'CANCEL',{digest},'UNAVAILABLE','PREPARED',1,{now},{now});
""")
    barrier = base.barrier_sql()
    apply_body = f"""
START TRANSACTION;
SELECT id FROM runtime_capacity WHERE singleton_key=1 FOR UPDATE;
SELECT id FROM business_run WHERE id=942001 FOR UPDATE;
SELECT id FROM runtime_sdk_call WHERE id={call_id} FOR UPDATE;
UPDATE runtime_state_terminalization SET status='APPLIED', applied_at={now}, after_state_sha256=UNHEX(REPEAT('cd',32)),
 row_version=row_version+1, gmt_modified={now}
WHERE terminalization_key=UNHEX('{term_key}') AND status='PREPARED' AND row_version=1;
SELECT ROW_COUNT();
COMMIT;
"""
    outputs = base.run_workers(client, [barrier + apply_body for _ in range(4)])
    if sorted(int(s.strip().splitlines()[-1]) for s in outputs) != [0, 0, 0, 1]:
        raise AssertionError(f"终结条件转换失败：{outputs}")
    # 旧版本回写失败
    stale = base.run(client, sql=f"""
UPDATE runtime_state_terminalization SET status='RECONCILING', row_version=50, gmt_modified={now}
WHERE terminalization_key=UNHEX('{term_key}') AND row_version=1; SELECT ROW_COUNT();
""".encode("utf-8"), timeout=60)
    if stale.stdout.decode().strip() != "0":
        raise AssertionError("旧 row_version 不应覆盖终结事实")
    term_state = query(client, f"SELECT status,row_version FROM runtime_state_terminalization WHERE terminalization_key=UNHEX('{term_key}');").strip()
    if term_state != "APPLIED\t2":
        raise AssertionError(f"终结读回异常：{term_state}")
    names.append("terminalization_cas_lock_order")

    # 6 事务回滚后原键读回为空插入；已提交事实仍在。
    rollback_key = secrets.token_hex(16)
    query(client, f"""
START TRANSACTION;
INSERT INTO runtime_dispatch (
 dispatch_key,user_id,employee_id,session_id,run_id,employee_release_id,dispatch_kind,work_kind,
 source_kind,source_id,source_version,request_key,request_sha256,status,permit_state,boot_epoch,gmt_create,gmt_modified)
VALUES (UNHEX('{rollback_key}'),942001,1,942001,942001,1,'ORDINARY','ROLLBACK_PROBE',
 'ORDINARY_SUBMISSION',6001,1,UNHEX(REPEAT('a9',16)),{digest},'WAITING','NONE',2,{now},{now});
ROLLBACK;
""")
    rolled = query(client, f"SELECT COUNT(*) FROM runtime_dispatch WHERE dispatch_key=UNHEX('{rollback_key}');").strip()
    committed = query(client, f"SELECT COUNT(*) FROM runtime_state_terminalization WHERE terminalization_key=UNHEX('{term_key}');").strip()
    if rolled != "0" or committed != "1":
        raise AssertionError(f"回滚／提交读回失败 rolled={rolled} committed={committed}")
    names.append("rollback_and_commit_readback")

    query(client, """
DELETE FROM runtime_state_terminalization WHERE user_id=942001;
DELETE FROM runtime_sdk_call WHERE user_id=942001;
DELETE FROM runtime_dispatch WHERE user_id=942001;
DELETE FROM runtime_capacity WHERE id=1;
DELETE FROM business_run WHERE actor_user_id=942001;
DELETE FROM business_message WHERE user_id=942001;
DELETE FROM business_session WHERE user_id=942001;
DELETE FROM platform_user WHERE id=942001;
""")
    assert_empty_control_tables(client)
    remaining = int(query(client, "SELECT (SELECT COUNT(*) FROM platform_user)+(SELECT COUNT(*) FROM business_session);").strip())
    if remaining:
        raise AssertionError("竞争夹具未清理干净")
    return names


def schema_stats(client):
    names = ",".join(f"'{n}'" for n in CONTROL_TABLES)
    output = query(client, f"""
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ({names});
SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='business_run' AND column_name='row_version';
SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='CHECK'
 AND table_name IN ({names},'business_run');
SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='FOREIGN KEY';
SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE();
""")
    tables, run_cas, checks, fks, triggers = [int(x) for x in output.splitlines()]
    if tables != 4 or run_cas != 1 or fks or triggers:
        raise AssertionError(f"结构规约失败 tables={tables} run_cas={run_cas} fks={fks} triggers={triggers}")
    return {"new_tables": tables, "business_run_row_version": run_cas, "related_check_constraints": checks,
            "foreign_keys": fks, "triggers": triggers}


def extra_checks(client):
    inspection = run_inspection(client)
    directed = run_directed(client)
    races = run_concurrency(client)
    stats = schema_stats(client)
    return {
        **inspection,
        **directed,
        **stats,
        "concurrency_checks": len(races),
        "concurrency_names": races,
        "real_sdk_store_tested": False,
        "production_dispatch_loop_tested": False,
        "zero_execution_adapter_tested": False,
        "issue29_or_30_preset_polluted": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    found = shutil.which("mysql")
    parser.add_argument("--mysql-bin", type=Path, default=Path(found).parent if found else None)
    parser.add_argument("--report", type=Path, help="可选 JSON 验收结果")
    args = parser.parse_args()
    if args.mysql_bin is None:
        parser.error("请用 --mysql-bin 指定 MySQL bin 目录")

    captured = io.StringIO()
    from contextlib import redirect_stdout
    with redirect_stdout(captured):
        summary = base.verify(
            args.mysql_bin.resolve(),
            ddl_files=DDL,
            probes=[CONSTRAINT_PROBE],
            expected_table_count=EXPECTED_TABLES,
            inspection_check=lambda client: run_inspection(client)["inspection_checks"],
            concurrency_check=lambda client: 0,
            extra_checks=extra_checks,
        )
    print(json.dumps(summary, ensure_ascii=False), flush=True)

    inputs = sorted(set(DDL + [
        CONSTRAINT_PROBE, INSPECTION, INSPECTION_SEED, DIRECTED, BASE_PATH, Path(__file__).resolve(),
        ROOT / "db/schema/02_会话运行消息建表.sql",
    ]))
    report = {
        "issue": 31,
        "phase": "stage1-08",
        "executed_at": datetime.now(timezone(timedelta(hours=8))).isoformat(),
        "contract_refs": [
            "issue-22 data contract §4/§5/§6/§13",
            "issue-22 interface §9-10 lock subsequence (DB probe only)",
            "issue-23 stage1 exit condition dispatch/sdk_call/terminalization portion",
        ],
        "summary": summary,
        "fingerprints": {
            p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest().upper()
            for p in inputs
        },
        "claims": {
            "database_constraints_verified": True,
            "real_sdk_store_verified": False,
            "production_capacity_scheduler_verified": False,
            "zero_execution_terminalization_adapter_verified": False,
        },
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
