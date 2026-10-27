"""Issue #30：隔离验收 session_intent／media_upload／ordinary_submission。

新建一次性本机 MySQL 实例；复用记忆控制执行器的生命周期。不读取登录路径，
不连接既有开发库或 Issue #29 预置事实。SQL 约束／竞争／巡检不构成 HTTP、MinIO 或真实 Run 验收。
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
    "07_新建意图暂存与普通提交建表.sql",
]]
CONSTRAINT_PROBE = TESTS / "16_新建意图暂存与普通提交建表验收.sql"
INSPECTION = TESTS / "17_新建意图暂存与普通提交关联巡检.sql"
INSPECTION_SEED = TESTS / "18_新建意图暂存与普通提交巡检种子.sql"
DIRECTED = TESTS / "19_新建意图暂存与普通提交定向异常验收.sql"
EXPECTED_TABLES = 32
EXPECTED_INSPECTIONS = 8


def query(client, sql, phase="SQL"):
    return base.require_success(base.run(client, sql=sql.encode("utf-8"), timeout=180), phase)


def assert_empty_control_tables(client):
    remaining = int(query(client, """
SELECT (SELECT COUNT(*) FROM session_intent)
     + (SELECT COUNT(*) FROM media_upload)
     + (SELECT COUNT(*) FROM ordinary_submission);
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
    rows = base.tab_rows(query(client, script, "意图／暂存／提交巡检"))
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
    if not markers or len({m.group(2) for m in markers}) != len(markers):
        raise AssertionError("定向巡检缺少用例或标识重复")
    results = []
    for i, marker in enumerate(markers):
        mode, name, target = marker.groups()
        if mode == "ANOMALY" and not target:
            raise AssertionError(f"{name} 缺少目标巡检项")
        mutation = probes[marker.end():markers[i + 1].start() if i + 1 < len(markers) else len(probes)]
        rows = base.tab_rows(query(client, baseline + mutation + inspection + "ROLLBACK;\n", f"定向 {name}"))
        if len(rows) != EXPECTED_INSPECTIONS or len(dict(rows)) != EXPECTED_INSPECTIONS:
            raise AssertionError(f"{name} 巡检组数不符：{rows}")
        counts = {key: int(count) for key, count in rows}
        if mode == "CLEAN":
            if any(counts.values()):
                raise AssertionError(f"{name} 合法场景误报：{counts}")
        elif counts.get(target, 0) <= 0:
            raise AssertionError(f"{name} 未命中 {target}：{counts}")
        results.append({"case": name, "expected": mode, "target": target,
                        "target_anomalies": counts.get(target, 0)})
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
VALUES (932001,'竞争探针用户',{now},{now},{now});
INSERT INTO business_session (id,user_id,employee_id,employee_release_id,state_session_key,last_activity_at,gmt_create,gmt_modified)
VALUES (932001,932001,1,1,REPEAT('e',32),{now},{now},{now});
INSERT INTO business_message (id,session_id,user_id,employee_id,message_no,input_sequence,sender_kind,submission_key,submission_sha256,accepted_at,gmt_create,gmt_modified)
VALUES (932001,932001,932001,1,1,1,'USER',UNHEX(REPEAT('b1',16)),{digest},{now},{now},{now});
INSERT INTO business_run (id,session_id,input_message_id,actor_user_id,employee_release_id,memory_generation_at_start,memory_revision_at_start,memory_policy_version,run_request_key,request_sha256,gmt_create,gmt_modified)
VALUES (932001,932001,932001,932001,1,1,1,'p1',UNHEX(REPEAT('b2',16)),{digest},{now},{now});
UPDATE business_message SET initial_run_id=932001 WHERE id=932001;
INSERT INTO message_media (id,message_id,user_id,storage_profile,object_key,media_kind,mime_type,byte_count,content_sha256,gmt_create,gmt_modified)
VALUES (932001,932001,932001,'synthetic-private-v1','synthetic/race/a.png','IMAGE','image/png',100,{digest},{now},{now});
INSERT INTO session_intent (id,owner_user_id,client_intent_id,employee_id,employee_release_id,image_capability_sha256,image_capability_json,prepared_at,gmt_create,gmt_modified)
VALUES (932001,932001,UNHEX(REPEAT('c1',16)),1,1,{digest},JSON_OBJECT('maxBytes',1),{now},{now},{now});
""")


def run_concurrency(client):
    """首次控制行插入、意图绑定 CAS、接纳／封闭竞争、暂存绑定竞争。"""
    names = []
    now = sql_now()
    digest = "UNHEX(REPEAT('ab',32))"
    prepare_fixtures(client)

    # 1 同 user＋submission_key 首次插入恰一条成功。
    key = secrets.token_hex(16)
    barrier = base.barrier_sql()
    insert = (
        f"INSERT INTO ordinary_submission (user_id,submission_key,has_registered_intent,target_session_id,employee_id,"
        f"submission_sha256,upload_ref_json,status,gmt_create,gmt_modified) VALUES "
        f"(932001,UNHEX('{key}'),1,932001,1,{digest},JSON_ARRAY(),'REGISTERED',{now},{now});"
    )
    run_constraint_race(client, [barrier + insert for _ in range(4)], "uk_user_submission_key")
    names.append("submission_first_insert_unique")

    # 2 同键异参：已有行时再次插入不同摘要仍唯一冲突，原行不变。
    before = query(client, f"SELECT LOWER(HEX(submission_sha256)),status FROM ordinary_submission WHERE user_id=932001 AND submission_key=UNHEX('{key}');").strip()
    conflict = (
        f"INSERT INTO ordinary_submission (user_id,submission_key,has_registered_intent,target_session_id,employee_id,"
        f"submission_sha256,upload_ref_json,status,gmt_create,gmt_modified) VALUES "
        f"(932001,UNHEX('{key}'),1,932001,1,UNHEX(REPEAT('cd',32)),JSON_ARRAY('x'),'REGISTERED',{now},{now});"
    )
    result = base.run(client, sql=conflict.encode("utf-8"), timeout=60)
    if result.returncode == 0 or b"uk_user_submission_key" not in result.stderr:
        raise AssertionError("同键异参未按唯一键拒绝")
    after = query(client, f"SELECT LOWER(HEX(submission_sha256)),status FROM ordinary_submission WHERE user_id=932001 AND submission_key=UNHEX('{key}');").strip()
    if after != before:
        raise AssertionError(f"同键异参改写了原行：{before} -> {after}")
    names.append("submission_same_key_different_params")

    # 3 意图绑定 CAS：仅 unbound＋原 row_version 成功一次。
    barrier = base.barrier_sql()
    scripts = []
    for i, sid in enumerate(range(932010, 932014)):
        body = (
            f"UPDATE session_intent SET bound_session_id={sid}, first_submission_id={9000 + i}, "
            f"bound_at={now}, row_version=row_version+1, gmt_modified={now} "
            f"WHERE id=932001 AND bound_session_id IS NULL AND row_version=1; SELECT ROW_COUNT();"
        )
        scripts.append(barrier + body)
    outputs = base.run_workers(client, scripts)
    if sorted(int(s.strip()) for s in outputs) != [0, 0, 0, 1]:
        raise AssertionError(f"意图绑定 CAS 失败：{outputs}")
    bound = query(client, "SELECT bound_session_id,row_version FROM session_intent WHERE id=932001;").strip()
    if not re.fullmatch(r"93201[0-3]\t2", bound):
        raise AssertionError(f"意图绑定结果异常：{bound}")
    names.append("intent_bind_cas")

    # 4 接纳与封闭竞争：REGISTERED 行条件更新恰一个终态。
    sub_id = query(client, f"SELECT id FROM ordinary_submission WHERE submission_key=UNHEX('{key}');").strip()
    barrier = base.barrier_sql()
    accept = (
        f"UPDATE ordinary_submission SET status='ACCEPTED', accepted_session_id=932001, accepted_message_id=932001, "
        f"accepted_run_id=932001, accepted_at={now}, acceptance_fence=acceptance_fence+1, row_version=row_version+1, "
        f"gmt_modified={now} WHERE id={sub_id} AND status='REGISTERED'; SELECT ROW_COUNT();"
    )
    close = (
        f"UPDATE ordinary_submission SET status='CLOSED_UNACCEPTED', closed_at={now}, reason_code='USER_CLOSE', "
        f"close_request_key=UNHEX(REPEAT('ee',16)), close_request_sha256={digest}, "
        f"acceptance_fence=acceptance_fence+1, row_version=row_version+1, gmt_modified={now} "
        f"WHERE id={sub_id} AND status='REGISTERED'; SELECT ROW_COUNT();"
    )
    outputs = base.run_workers(client, [barrier + accept, barrier + close, barrier + accept, barrier + close])
    counts = sorted(int(s.strip()) for s in outputs)
    if counts.count(1) != 1 or counts.count(0) != 3:
        raise AssertionError(f"接纳／封闭竞争失败：{outputs}")
    final = query(client, f"SELECT status, accepted_message_id IS NOT NULL, closed_at IS NOT NULL FROM ordinary_submission WHERE id={sub_id};").strip()
    if final not in ("ACCEPTED\t1\t0", "CLOSED_UNACCEPTED\t0\t1"):
        raise AssertionError(f"终态不合法：{final}")
    names.append("accept_versus_close_cas")

    # 4b 封闭先提交：迟到接纳条件更新影响 0 行，墓碑与空结果保持不变。
    # 4c 接纳先提交：迟到封闭影响 0 行，原 Session／消息／Run 读回不变。
    query(client, f"""
INSERT INTO business_session (id,user_id,employee_id,employee_release_id,state_session_key,last_activity_at,gmt_create,gmt_modified)
VALUES (932002,932001,1,1,REPEAT('f',32),{now},{now},{now});
INSERT INTO business_message (id,session_id,user_id,employee_id,message_no,input_sequence,sender_kind,submission_key,submission_sha256,accepted_at,gmt_create,gmt_modified)
VALUES (932002,932002,932001,1,1,2,'USER',UNHEX(REPEAT('b3',16)),{digest},{now},{now},{now});
INSERT INTO business_run (id,session_id,input_message_id,actor_user_id,employee_release_id,memory_generation_at_start,memory_revision_at_start,memory_policy_version,run_request_key,request_sha256,gmt_create,gmt_modified)
VALUES (932002,932002,932002,932001,1,1,1,'p1',UNHEX(REPEAT('b4',16)),{digest},{now},{now});
UPDATE business_message SET initial_run_id=932002 WHERE id=932002;
INSERT INTO ordinary_submission (id,user_id,submission_key,has_registered_intent,target_session_id,employee_id,submission_sha256,upload_ref_json,status,gmt_create,gmt_modified)
VALUES (932010,932001,UNHEX(REPEAT('c2',16)),1,932002,1,{digest},JSON_ARRAY(),'REGISTERED',{now},{now}),
       (932011,932001,UNHEX(REPEAT('c3',16)),1,932002,1,{digest},JSON_ARRAY(),'REGISTERED',{now},{now});
UPDATE ordinary_submission SET status='CLOSED_UNACCEPTED', closed_at={now}, reason_code='USER_CLOSE',
 close_request_key=UNHEX(REPEAT('c4',16)), close_request_sha256={digest}, gmt_modified={now}
WHERE id=932010 AND status='REGISTERED';
UPDATE ordinary_submission SET status='ACCEPTED', accepted_session_id=932002, accepted_message_id=932002,
 accepted_run_id=932002, accepted_at={now}, gmt_modified={now}
WHERE id=932011 AND status='REGISTERED';
""")
    late_accept = query(client, f"""
UPDATE ordinary_submission SET status='ACCEPTED', accepted_session_id=932002, accepted_message_id=932002,
 accepted_run_id=932002, accepted_at={now}, gmt_modified={now}
WHERE id=932010 AND status='REGISTERED';
SELECT ROW_COUNT();
SELECT status, accepted_message_id IS NULL, reason_code FROM ordinary_submission WHERE id=932010;
""").strip().splitlines()
    if late_accept != ["0", "CLOSED_UNACCEPTED\t1\tUSER_CLOSE"]:
        raise AssertionError(f"封闭先提交后迟到接纳未被拒绝：{late_accept}")
    names.append("close_first_rejects_late_accept")
    late_close = query(client, f"""
UPDATE ordinary_submission SET status='CLOSED_UNACCEPTED', closed_at={now}, reason_code='LATE_CLOSE',
 close_request_key=UNHEX(REPEAT('c5',16)), close_request_sha256={digest}, gmt_modified={now}
WHERE id=932011 AND status='REGISTERED';
SELECT ROW_COUNT();
SELECT status, accepted_session_id, accepted_message_id, accepted_run_id, closed_at IS NULL
FROM ordinary_submission WHERE id=932011;
""").strip().splitlines()
    if late_close != ["0", "ACCEPTED\t932002\t932002\t932002\t1"]:
        raise AssertionError(f"接纳先提交后封闭未读回原结果：{late_close}")
    names.append("accept_first_close_reads_original")

    # 5 暂存 READY→BOUND 条件更新恰一次；禁绑清理不能回到 READY。
    query(client, f"""
INSERT INTO media_upload (id,upload_id,user_id,upload_request_key,upload_request_sha256,employee_id,employee_release_id,
 session_id,storage_profile,object_key,media_kind,status,issued_at,expires_at,mime_type,byte_count,pixel_width,pixel_height,
 content_sha256,validation_version,gmt_create,gmt_modified)
VALUES (932001,UNHEX(REPEAT('d1',16)),932001,UNHEX(REPEAT('d2',16)),{digest},1,1,932001,
 'synthetic-private-v1','synthetic/race/ready.png','IMAGE','READY',{now},{now}+INTERVAL 24 HOUR,
 'image/png',100,10,10,{digest},1,{now},{now});
""")
    barrier = base.barrier_sql()
    bind = (
        f"UPDATE media_upload SET status='BOUND', message_id=932001, message_media_id=932001, "
        f"row_version=row_version+1, gmt_modified={now} "
        f"WHERE id=932001 AND status='READY' AND deletion_status='NONE' AND expires_at >= {now}; SELECT ROW_COUNT();"
    )
    outputs = base.run_workers(client, [barrier + bind for _ in range(4)])
    if sorted(int(s.strip()) for s in outputs) != [0, 0, 0, 1]:
        raise AssertionError(f"暂存绑定竞争失败：{outputs}")
    # 禁绑后不得回到 READY
    block = base.run(client, sql=f"""
UPDATE media_upload SET deletion_status='PENDING', gmt_modified={now} WHERE id=932001 AND status='BOUND';
UPDATE media_upload SET status='READY', message_id=NULL, message_media_id=NULL, deletion_status='PENDING', gmt_modified={now}
WHERE id=932001;
""".encode("utf-8"), timeout=60)
    if block.returncode == 0 or b"chk_media_upload_no_ready_after_delete_fail" not in block.stderr:
        raise AssertionError("禁绑后回到 READY 未被拒绝")
    names.append("upload_bind_cas_and_block")

    # 平台 SQL 回滚／提交不明：用原控制键读回；本段已提交事实用键核对后删除。
    readback = query(client, f"""
SELECT
 (SELECT COUNT(*) FROM ordinary_submission WHERE user_id=932001 AND submission_key=UNHEX('{key}')),
 (SELECT COUNT(*) FROM media_upload WHERE upload_id=UNHEX(REPEAT('d1',16))),
 (SELECT COUNT(*) FROM session_intent WHERE id=932001 AND bound_session_id IS NOT NULL);
""").strip()
    if readback != "1\t1\t1":
        raise AssertionError(f"原控制键读回失败：{readback}")
    names.append("control_key_readback_after_commit")

    query(client, """
DELETE FROM media_upload WHERE user_id=932001;
DELETE FROM ordinary_submission WHERE user_id=932001;
DELETE FROM session_intent WHERE owner_user_id=932001;
DELETE FROM message_media WHERE user_id=932001;
DELETE FROM business_run WHERE actor_user_id=932001;
DELETE FROM business_message WHERE user_id=932001;
DELETE FROM business_session WHERE user_id=932001;
DELETE FROM platform_user WHERE id=932001;
""")
    assert_empty_control_tables(client)
    remaining = int(query(client, "SELECT (SELECT COUNT(*) FROM platform_user)+(SELECT COUNT(*) FROM business_session);").strip())
    if remaining:
        raise AssertionError("竞争夹具未清理干净")
    return names


def schema_stats(client):
    output = query(client, """
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('session_intent','media_upload','ordinary_submission');
SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='CHECK'
 AND table_name IN ('session_intent','media_upload','ordinary_submission');
SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='FOREIGN KEY';
SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE();
""")
    tables, checks, fks, triggers = [int(x) for x in output.splitlines()]
    if tables != 3 or fks or triggers:
        raise AssertionError(f"结构规约失败 tables={tables} fks={fks} triggers={triggers}")
    return {"new_tables": tables, "new_check_constraints": checks, "foreign_keys": fks, "triggers": triggers}


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
        "http_acceptance_tested": False,
        "minio_object_tested": False,
        "real_business_run_tested": False,
        "issue29_preset_polluted": False,
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
    # verify 已跑 inspection_check；extra 再跑一次 inspection/directed/concurrency。
    # 为避免巡检双跑成本，上面 inspection_check 仍保留契约计数；extra 含完整细节。
    print(json.dumps(summary, ensure_ascii=False), flush=True)

    inputs = sorted(set(DDL + [
        CONSTRAINT_PROBE, INSPECTION, INSPECTION_SEED, DIRECTED, BASE_PATH, Path(__file__).resolve(),
    ]))
    report = {
        "issue": 30,
        "phase": "stage1-07",
        "executed_at": datetime.now(timezone(timedelta(hours=8))).isoformat(),
        "contract_refs": [
            "issue-22 data contract §6.1/§7/§8/§13",
            "issue-22 interface §5 close/late-accept semantics (DB probe only)",
            "issue-23 stage1 exit condition staging/submission portion",
        ],
        "summary": summary,
        "fingerprints": {
            p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest().upper()
            for p in inputs
        },
        "claims": {
            "database_constraints_verified": True,
            "http_accept_upload_close_verified": False,
            "minio_verified": False,
            "real_run_verified": False,
        },
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
