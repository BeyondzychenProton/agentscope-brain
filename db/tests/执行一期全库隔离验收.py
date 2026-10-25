"""一期全库隔离验收：复用一次性实例生命周期，分别运行7/17/21/29表范围。

不读取用户登录路径，不连接既有数据库；只用合成数据。
SQL约束/并发探针不构成Java应用、规范化摘要、框架KV、对象存储或真实MCP验收。
"""
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
    "01_用户与管理员建表.sql", "02_会话运行消息建表.sql", "03_记忆控制建表.sql",
    "04_会话运行资料建表.sql", "05_员工配置发布与授权建表.sql"]]
PROBES = {n: TESTS / name for n, name in {
    1: "01_用户与管理员建表验收.sql", 3: "03_通用素材与工具操作建表验收.sql",
    4: "04_记忆控制建表验收.sql", 6: "06_记忆扩展与会话运行资料验收.sql",
    8: "08_员工配置发布与授权建表验收.sql"}.items()}
FULL_INSPECTION = TESTS / "09_一期全库关联巡检.sql"
FULL_SEED = TESTS / "10_一期全库巡检种子.sql"
DIRECTED_PROBES = TESTS / "11_一期全库定向异常验收.sql"


def query(client, sql, phase="全库SQL探针"):
    return base.require_success(base.run(client, sql=sql.encode("utf-8"), timeout=180), phase)


def identity_inspection(client):
    rows = base.tab_rows(query(client, (TESTS / "02_身份关联巡检.sql").read_text(encoding="utf-8")))
    if len(rows) != 9 or any(count != "0" for _, count in rows):
        raise AssertionError(f"身份巡检不为全零：{rows}")
    return len(rows)


def assert_empty(client):
    names = query(client, "SHOW TABLES;").splitlines()
    if any(not re.fullmatch(r"[a-z_]+", name) for name in names):
        raise AssertionError("拒绝拼接非平台表标识")
    remaining = int(query(client, "SELECT " + "+".join(
        f"(SELECT COUNT(*) FROM {name})" for name in names) + ";").strip())
    if remaining:
        raise AssertionError(f"隔离库仍有 {remaining} 行合成数据")
    return remaining


def run_full_inspection(client):
    seed = FULL_SEED.read_text(encoding="utf-8")
    baseline, rest = seed.split("-- INSPECT_ALL_ZERO")
    anomalies = rest.split("-- INSPECT_ALL_POSITIVE")[0]
    inspection = FULL_INSPECTION.read_text(encoding="utf-8")
    # 两份旧巡检同时验证完整基线；只要求新12项在针对性的异常段全部命中。
    identity = (TESTS / "02_身份关联巡检.sql").read_text(encoding="utf-8")
    memory = (TESTS / "05_记忆控制关联巡检.sql").read_text(encoding="utf-8")
    script = (baseline + inspection + identity + memory + "SELECT 'MARK',0;\n" +
              anomalies + inspection + "ROLLBACK;\n")
    rows = base.tab_rows(query(client, script, "全库基线与异常巡检"))
    marks = [i for i, row in enumerate(rows) if row[0] == "MARK"]
    if len(marks) != 1:
        raise AssertionError(f"巡检输出缺标记：{rows}")
    good, bad = rows[:marks[0]], rows[marks[0]+1:]
    if len(good) != 32 or any(value != "0" for _, value in good):
        raise AssertionError(f"完整基线误报：{good}")
    if len(bad) != 12 or [n for n, _ in bad] != [n for n, _ in good[:12]] or any(int(v) <= 0 for _, v in bad):
        raise AssertionError(f"全库异常漏报：{bad}")
    assert_empty(client)
    return {"full_inspection_checks": len(bad), "full_baseline_identity_checks": 9,
            "full_baseline_memory_checks": 11, "full_inspection_names": [n for n, _ in bad]}


def sql_insert(table, fields):
    data = {**fields, "gmt_create": "UTC_TIMESTAMP(6)", "gmt_modified": "UTC_TIMESTAMP(6)"}
    return f"INSERT INTO {table} ({','.join(data)}) VALUES ({','.join(data.values())});\n"


def run_directed_inspection(client):
    baseline = FULL_SEED.read_text(encoding="utf-8").split("-- INSPECT_ALL_ZERO")[0]
    inspection = FULL_INSPECTION.read_text(encoding="utf-8")
    probes = DIRECTED_PROBES.read_text(encoding="utf-8")
    markers = list(re.finditer(r"^-- EXPECT_INSPECTION (ANOMALY|CLEAN) (\w+) (\w+)[\t ]*$", probes, re.M))
    if not markers or len({m[2] for m in markers}) != len(markers):
        raise AssertionError("定向巡检缺少用例或标识重复")
    results = []
    for i, marker in enumerate(markers):
        mode, name, target = marker.groups()
        mutation = probes[marker.end():markers[i+1].start() if i+1 < len(markers) else len(probes)]
        rows = base.tab_rows(query(client, baseline + mutation + inspection + "ROLLBACK;\n", f"定向巡检 {name}"))
        if len(rows) != 12 or len(dict(rows)) != 12:
            raise AssertionError(f"{name} 巡检组数不符：{rows}")
        counts = {key: int(count) for key, count in rows}
        if mode == "CLEAN":
            if any(counts.values()):
                raise AssertionError(f"{name} 合法历史场景误报：{counts}")
        elif counts.get(target, 0) <= 0:
            raise AssertionError(f"{name} 未命中 {target}：{counts}")
        results.append({"case": name, "expected": mode, "target": target,
                        "target_anomalies": counts.get(target, 0)})
    assert_empty(client)
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
            raise AssertionError(result.stderr.decode("utf-8", errors="replace"))
    if winners != 1:
        raise AssertionError(f"{expected_constraint} 并发成功数应为1，实际{winners}")


def run_new_concurrency(client):
    """仅验证实际执行的SQL性质；不模拟Java发布、身份或重启服务。"""
    now = "UTC_TIMESTAMP(6)"
    digest = "UNHEX(REPEAT('ab',32))"
    admin, target, employee = "997001", "997002", "997001"
    query(client, sql_insert("platform_user", {"id": admin, "display_name": "'并发合成管理员'", "status_changed_at": now}) +
          sql_insert("platform_user", {"id": target, "display_name": "'并发合成目标用户'", "status_changed_at": now}) +
          sql_insert("digital_employee", {"id": employee, "employee_code": "'concurrency-only'", "display_name": "'并发合成员工'"}) +
          sql_insert("employee_draft", {"id": employee, "employee_id": employee, "config_json": "JSON_OBJECT()"}))
    names = []
    # 1 期望修订相同的保存，恰一个更新成功。
    barrier = base.barrier_sql()
    outputs = base.run_workers(client, [barrier +
        f"UPDATE employee_draft SET draft_revision=draft_revision+1 WHERE id={employee} AND draft_revision=1; SELECT ROW_COUNT();"
        for _ in range(4)])
    if sorted(int(s.strip()) for s in outputs) != [0, 0, 0, 1]:
        raise AssertionError(f"草稿CAS失败：{outputs}")
    names.append("draft_revision_cas")
    # 2 不同发布号但同来源修订，恰一条通过唯一键。
    barrier = base.barrier_sql()
    scripts = []
    for i in range(4):
        release = {"employee_id": employee, "release_no": str(100+i), "source_draft_revision": "77",
                   "config_schema_version": "1", "config_json": "JSON_OBJECT()", "dependency_sha256": digest,
                   "config_sha256": digest, "manifest_sha256": digest, "published_by": admin,
                   "published_at": now, "availability_changed_by": admin, "availability_changed_at": now}
        scripts.append(barrier + sql_insert("employee_release", release))
    run_constraint_race(client, scripts, "uk_employee_source_draft_revision")
    names.append("source_draft_revision_unique")
    # 3 相同成功命令收据恰一条；失败事件不参与该唯一性。
    request = secrets.token_hex(16)
    receipt = {"actor_user_id": admin, "employee_id": employee, "action_code": "'DRAFT_SAVE'",
               "outcome": "'SUCCESS'", "result_code": "'SAVED'", "request_id": f"'{request}'",
               "request_sha256": digest, "occurred_at": now}
    barrier = base.barrier_sql()
    run_constraint_race(client, [barrier + sql_insert("employee_configuration_audit_event", receipt) for _ in range(4)],
                        "uk_actor_success_request")
    names.append("successful_receipt_unique")
    failed = {**receipt, "outcome": "'FAILURE'", "result_code": "NULL", "reason_code": "'SYNTHETIC_TRANSIENT'"}
    barrier = base.barrier_sql()
    base.run_workers(client, [barrier + sql_insert("employee_configuration_audit_event", failed) for _ in range(4)])
    if query(client, f"SELECT COUNT(*) FROM employee_configuration_audit_event WHERE outcome='FAILURE' AND request_id='{request}';").strip() != "4":
        raise AssertionError("并发失败事件未全部保留")
    names.append("failed_receipts_nonunique")
    # 4 目标用户授权版本CAS，关系、用户版本和审计在同一事务提交。
    barrier = base.barrier_sql()
    script = (barrier + "START TRANSACTION;\n" +
        f"UPDATE platform_user SET authorization_version=authorization_version+1 WHERE id={target} AND authorization_version=1;\n"
        "SET @changed=ROW_COUNT();\n" +
        f"INSERT INTO employee_use_grant (user_id,employee_id,grant_status,granted_by,granted_at,gmt_create,gmt_modified) "
        f"SELECT {target},{employee},'ACTIVE',{admin},{now},{now},{now} WHERE @changed=1;\n" +
        f"INSERT INTO identity_audit_event (actor_user_id,target_user_id,action_code,outcome,employee_id,grant_id,"
        "authorization_version_before,authorization_version_after,occurred_at,gmt_create,gmt_modified) "
        f"SELECT {admin},{target},'EMPLOYEE_GRANT','SUCCESS',{employee},"
        f"(SELECT id FROM employee_use_grant WHERE user_id={target} AND employee_id={employee}),1,2,{now},{now},{now} WHERE @changed=1;\n"
        "COMMIT; SELECT @changed;")
    outputs = base.run_workers(client, [script for _ in range(4)])
    if sorted(int(s.strip()) for s in outputs) != [0, 0, 0, 1]:
        raise AssertionError(f"授权CAS失败：{outputs}")
    actual = query(client, f"SELECT (SELECT authorization_version FROM platform_user WHERE id={target}),"
                   f"(SELECT COUNT(*) FROM employee_use_grant WHERE user_id={target}),"
                   f"(SELECT COUNT(*) FROM identity_audit_event WHERE target_user_id={target});").strip()
    if actual != "2\t1\t1":
        raise AssertionError(f"授权关系/版本/审计不一致：{actual}")
    names.append("authorization_version_transaction")
    # 仅清理本函数刚提交的固定合成标识，前后都在本轮一次性实例内。
    query(client, f"DELETE FROM identity_audit_event WHERE target_user_id={target};"
          f"DELETE FROM employee_use_grant WHERE user_id={target};"
          f"DELETE FROM employee_configuration_audit_event WHERE actor_user_id={admin};"
          f"DELETE FROM employee_release WHERE employee_id={employee};"
          f"DELETE FROM employee_draft WHERE employee_id={employee};"
          f"DELETE FROM digital_employee WHERE id={employee};"
          f"DELETE FROM platform_user WHERE id IN ({admin},{target});")
    assert_empty(client)
    return names


def schema_stats(client):
    output = query(client, """SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE();
SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='CHECK';
SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE();""")
    columns, checks, triggers = [int(x) for x in output.splitlines()]
    if triggers:
        raise AssertionError("不允许创建触发器")
    assert_empty(client)
    return {"column_count": columns, "check_constraints": checks, "trigger_count": triggers, "synthetic_rows_remaining": 0}


def full_extra(client):
    inspection = run_full_inspection(client)
    directed = run_directed_inspection(client)
    races = run_new_concurrency(client)
    return {**inspection, **directed, **schema_stats(client), "additional_concurrency_checks": len(races),
            "additional_concurrency_names": races, "transactional_probes_rolled_back": True,
            "concurrency_rows_committed_then_removed": True, "canonical_package_fingerprints_tested": False,
            "external_storage_tested": False, "real_mcp_tested": False}


def run_scope(mysql_bin, scope):
    configs = {
        "identity": (DDL[:1], [PROBES[1]], 7, identity_inspection, lambda client: 0, schema_stats),
        "session": (DDL[:2], [PROBES[3]], 17, identity_inspection, lambda client: 0, schema_stats),
        "memory": (DDL[:4], [PROBES[i] for i in [3, 4, 6]], 21, base.run_inspection, base.run_concurrency, schema_stats),
        "full": (DDL, [PROBES[i] for i in [1, 3, 4, 6, 8]], 29, base.run_inspection, base.run_concurrency, full_extra),
    }
    ddl, probes, count, inspections, concurrency, extra = configs[scope]
    # 公共入口打印发生在finally之前；仅在关闭和清理成功后把本层结果向外发布。
    captured = io.StringIO()
    with redirect_stdout(captured):
        result = base.verify(mysql_bin, ddl_files=ddl, probes=probes, expected_table_count=count,
                             inspection_check=inspections, concurrency_check=concurrency, extra_checks=extra)
    return {"scope": scope, **result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    found = shutil.which("mysql")
    parser.add_argument("--mysql-bin", type=Path, default=Path(found).parent if found else None)
    parser.add_argument("--scope", choices=["all", "identity", "session", "memory", "full"], default="all")
    parser.add_argument("--report", type=Path, help="可选JSON验收结果，含本次输入文件SHA-256")
    args = parser.parse_args()
    if args.mysql_bin is None:
        parser.error("请指定 --mysql-bin")
    scopes = ["identity", "session", "memory", "full"] if args.scope == "all" else [args.scope]
    results = []
    for scope in scopes:
        print(json.dumps({"phase": "start", "scope": scope}, ensure_ascii=False), flush=True)
        result = run_scope(args.mysql_bin.resolve(), scope)
        results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    inputs = sorted(set(DDL + list(PROBES.values()) +
        [TESTS / "02_身份关联巡检.sql", TESTS / "05_记忆控制关联巡检.sql",
         TESTS / "07_记忆控制巡检种子.sql", FULL_INSPECTION, FULL_SEED, DIRECTED_PROBES, BASE_PATH, Path(__file__).resolve()]))
    report = {"executed_at": datetime.now(timezone(timedelta(hours=8))).isoformat(), "results": results,
              "fingerprints": {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest().upper() for p in inputs}}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
