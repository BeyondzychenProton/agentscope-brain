"""#29：仅新建一次性本机 MySQL，用合成输入验证受控初始化。

单用户基线、回滚／竞争／异常探针各用独立 schema；不读实际配置或登录路径。
"""
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from decimal import Decimal
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
from threading import Barrier
from unittest.mock import patch

import bcrypt
import pymysql

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "db/bootstrap"))
import controlled_bootstrap as boot

spec = importlib.util.spec_from_file_location("issue29_isolated_mysql", Path(__file__).with_name("执行记忆控制隔离验收.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
DDL = [ROOT / "db/schema" / name for name in (
    "01_用户与管理员建表.sql", "02_会话运行消息建表.sql", "03_记忆控制建表.sql",
    "04_会话运行资料建表.sql", "05_员工配置发布与授权建表.sql", "06_初始化来源与批次.sql")]
EVIDENCE = ROOT / "verification/issue-29/probe-summary.json"


def fixture():
    # 仅合成密码：随机 salt 且哈希不进入证据；真实预置必须从本机受控材料提供。
    return {"sourceRef": "issue29-synthetic-v1", "bootstrapScope": boot.SCOPE,
        "identity": {"displayName": "合成测试用户", "phone": "19900000029", "ownershipSourceRef": "synthetic-roster/29",
                     "credentialRefVersion": "synthetic-credential/v1", "passwordHash": "{bcrypt}" + bcrypt.hashpw(b"synthetic-bootstrap-29", bcrypt.gensalt(rounds=12)).decode()},
        "employee": {"code": "synthetic-meeting", "displayName": "合成会议员工", "description": "仅用于隔离契约验证",
            "model": {"configRef": "synthetic-model/v1", "modelId": "synthetic-model", "endpointRef": "offline/v1",
                      "credentialRef": "synthetic-secret/v1", "revision": "1", "parameters": {"temperature": Decimal("0.50"), "maxTokens": 512}},
            "instructions": "查询合成会议资料。\r\n不得发送网络请求。",
            "skills": [{"markdown": "---\r\nname: synthetic-meeting\r\ndescription: 合成会议资料查询\r\nmetadata:\r\n  category: 测试\r\n  labels: [会议, 离线]\r\n---\r\n读取 参考/会议说明.md。\r\n",
                        "resources": [{"path": "参考/会议说明.md", "text": "# 合成资料\r\n中文、空白和换行须原样保留。\r\n"}, {"path": "empty.txt", "text": ""}]}],
            "tools": [{"version": "synthetic-meeting-query-v1", "providerKey": "synthetic-meeting", "toolName": "query",
                "binding": {"targetRef": "synthetic-target/v1", "transport": "mcp-http", "credentialRef": "synthetic-secret/v1",
                    "inputSchemaRef": "synthetic-schema/v1", "adapterRef": "synthetic-adapter/v1", "identityPolicyRef": "trusted-user/v1",
                    "confirmationPolicyRef": "read-only/v1", "idempotencyPolicyRef": "no-replay/v1", "reconciliationPolicyRef": "original-key/v1"}}],
            "workspace": {"policyRef": "session-isolated/v1", "revision": "1"},
            "memory": {"policyRef": "effective-generation/v1", "revision": "1"}, "storageProfiles": []}}


def assert_error(fn, expected):
    try:
        fn()
    except boot.BootstrapError as error:
        assert str(error) == expected, (str(error), expected)
    else:
        raise AssertionError("缺少预期拒绝：" + expected)


def unit_checks(request):
    assert boot.canonical({"z": None, "a": ["中文\r\n", Decimal("1.200"), False]}) == b'{"a":["\xe4\xb8\xad\xe6\x96\x87\\r\\n",1.2,false],"z":null}'
    assert boot.canonical(Decimal("-0.00")) == b"0"
    assert boot.canonical({}) != boot.canonical({"x": None}) != boot.canonical({"x": False})
    assert_error(lambda: boot.load_json('{"x":1,"x":2}'), "DUPLICATE_JSON_KEY")
    assert_error(lambda: boot.canonical(float("nan")), "UNSUPPORTED_JSON")
    assert_error(lambda: boot.load_json('{"x":NaN}'), "INVALID_NUMBER")
    for path in ("../secret", "/root", "C:/secret", "a\\b", "a//b", "a/./b", "a/../b", "a /b", "SKILL.md", "a\x00b", "e\u0301.md"):
        assert_error(lambda: boot.resource_path(path), "INVALID_RESOURCE_PATH")
    bad = deepcopy(request)
    bad["employee"]["skills"][0]["resources"].append({"path": "EMPTY.txt", "text": "x"})
    assert_error(lambda: boot.prepare(bad), "DUPLICATE_RESOURCE")
    bad = deepcopy(request)
    bad["identity"]["passwordHash"] = "{noop}not-allowed"
    assert_error(lambda: boot.prepare(bad), "INVALID_CREDENTIAL")
    bad = deepcopy(request)
    bad["employee"]["model"]["apiKey"] = "synthetic-not-a-secret"
    assert_error(lambda: boot.prepare(bad), "INVALID_FIELDS")
    bad = deepcopy(request)
    bad["employee"]["skills"][0]["markdown"] = "---\nname: x\nname: y\ndescription: test\n---\nbody"
    assert_error(lambda: boot.prepare(bad), "INVALID_FRONTMATTER")
    entry = request["employee"]["skills"][0]
    package = boot.skill_package(entry["markdown"], entry["resources"])
    assert package["sha"] == boot.skill_package(entry["markdown"], list(reversed(entry["resources"]))) ["sha"]
    assert package["sha"] != boot.skill_package(entry["markdown"].replace("\r\n", "\n"), entry["resources"])["sha"]
    reordered = deepcopy(request)
    reordered["employee"]["skills"][0]["resources"].reverse()
    assert boot.prepare(reordered)["input_sha256"] == boot.prepare(request)["input_sha256"]
    image_request = deepcopy(request)
    image_request["employee"]["storageProfiles"] = [{"id": "synthetic-originals-v1", "sha256": "a" * 64}]
    image_request["employee"]["images"] = {"storageProfile": "synthetic-originals-v1", "mimeTypes": ["image/jpeg", "image/png"],
        "maxCount": 4, "maxBytesPerImage": 5242880, "maxBytesPerMessage": 10485760, "maxPixelsPerImage": 16000000}
    prepared = boot.prepare(image_request)
    image_config = boot.release_config(prepared, [])
    assert image_config["images"] == image_request["employee"]["images"]
    assert image_config["storageProfiles"] == image_request["employee"]["storageProfiles"]
    mutations = [
        (lambda e: e["images"].update(maxCount=True), "INVALID_IMAGE_LIMIT"),
        (lambda e: e["images"].update(maxPixelsPerImage=0), "INVALID_IMAGE_LIMIT"),
        (lambda e: e["images"].update(maxBytesPerImage=1.5), "INVALID_IMAGE_LIMIT"),
        (lambda e: e["images"].update(maxBytesPerMessage=1), "INCONSISTENT_IMAGE_BUDGET"),
        (lambda e: e["images"].update(maxBytesPerMessage=2147483647), "INCONSISTENT_IMAGE_BUDGET"),
        (lambda e: e["images"].update(mimeTypes=["image/svg+xml"]), "INVALID_IMAGE_MIME_TYPES"),
        (lambda e: e["images"].update(mimeTypes=["image/jpeg", "image/jpeg"]), "INVALID_IMAGE_MIME_TYPES"),
        (lambda e: e["images"].update(mimeTypes=[]), "INVALID_IMAGE_MIME_TYPES"),
        (lambda e: e["images"].update(apiKey="synthetic-not-a-secret"), "INVALID_FIELDS"),
        (lambda e: e["images"].update(storageProfile="missing-profile"), "IMAGE_STORAGE_PROFILE_MISSING"),
    ]
    for mutation, expected in mutations:
        bad = deepcopy(image_request)
        mutation(bad["employee"])
        assert_error(lambda: boot.prepare(bad), expected)
    changed = deepcopy(image_request)
    changed["employee"]["images"]["maxCount"] = 3
    assert boot.prepare(changed)["input_sha256"] != prepared["input_sha256"]
    assert boot.digest(boot.release_dependencies(changed["employee"], [])) != boot.digest(boot.release_dependencies(image_request["employee"], []))
    assert boot.release_config(boot.prepare(request), []).keys() == {"configSchemaVersion", "model", "instructions", "skills", "tools", "workspace", "memory"}
    bad = deepcopy(request)
    bad["employee"] = None
    assert_error(lambda: boot.prepare(bad), "INVALID_FIELDS")
    return 40


def integration(client, request, ddl_bytes):
    port = int(next(a.split("=", 1)[1] for a in client if a.startswith("--port=")))
    records = []

    def connect(schema):
        return pymysql.connect(host="127.0.0.1", port=port, user="root", password="", database=schema,
                               charset="utf8mb4", autocommit=False, connect_timeout=5, read_timeout=20, write_timeout=20)

    def sql(c, statement, args=()):
        return boot.execute(c, statement, args)

    def database(name):
        # 调用前 base.verify 已核对随机本机端口和独占 @@datadir。
        assert name.startswith("issue29_") and name.replace("_", "").isalnum()
        with connect("memory_schema_probe") as c:
            sql(c, f"CREATE DATABASE {name} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
        result = base.run(client[:-1] + ["--database=" + name], sql=b"\n".join(ddl_bytes.values()), timeout=60)
        base.require_success(result, "独立合成数据集建表")
        return name

    def record(case, observed, facts=None):
        records.append({"case": "1-06/" + case, "expected": "PASS", "observed": observed,
                        "facts": facts or {}, "modelCalls": 0, "mcpCalls": 0, "minioCalls": 0})

    def readonly(schema, source=request["sourceRef"]):
        with connect(schema) as c:
            sql(c, "SET TRANSACTION READ ONLY")
            c.begin()
            answer = boot.verify(c, source)
            c.rollback()
            return answer

    def counts(schema):
        with connect(schema) as c:
            tables = sql(c, "SHOW TABLES")
            return {next(iter(r.values())): sql(c, "SELECT COUNT(*) AS n FROM " + next(iter(r.values())))[0]["n"] for r in tables}

    def expect_sql(c, statement, args, code):
        try:
            sql(c, statement, args)
        except pymysql.MySQLError as error:
            assert error.args[0] == code, error.args[0]
        else:
            raise AssertionError("未拒绝非法 SQL：" + str(code))

    schema = database("issue29_single_user")
    with connect(schema) as c:
        applied = boot.apply(c, request)
    assert readonly(schema) == applied
    r = applied["result"]
    with connect(schema) as c:
        assert sql(c, "SELECT COUNT(*) AS n FROM platform_user")[0]["n"] == 1
        assert sql(c, "SELECT COUNT(*) AS n FROM fixed_admin_assignment")[0]["n"] == 0
        assert sql(c, "SELECT COUNT(*) AS n FROM user_credential WHERE is_password_change_required=1")[0]["n"] == 1
        manifest = sql(c, "SELECT manifest_json FROM bootstrap_batch")[0]["manifest_json"]
        assert request["identity"]["phone"] not in manifest and request["identity"]["passwordHash"] not in manifest
        assert not any(k in manifest for k in ("passwordHash", "account_status", "grant_status", "security_version"))
    record("single-user", "一名普通用户、零管理员、首次改密、完整固定发布及独立读回", {"batchId": applied["batchId"], "manifestSha256": applied["manifestSha256"]})
    original_counts = counts(schema)
    with connect(schema) as c:
        release_fingerprints = sql(c, "SELECT LOWER(HEX(manifest_sha256)) AS manifestSha256,LOWER(HEX(config_sha256)) AS configSha256,LOWER(HEX(dependency_sha256)) AS dependencySha256 FROM employee_release")[0]
        structure = {"columns": sql(c, "SELECT table_name,column_name,column_type,is_nullable,extra FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position"),
                     "checks": sql(c, "SELECT constraint_name,check_clause FROM information_schema.check_constraints WHERE constraint_schema=DATABASE() ORDER BY constraint_name")}
        structure_sha256 = boot.digest(structure).hex()
    with connect(schema) as c:
        sql(c, "SET TRANSACTION READ ONLY")
        assert boot.apply(c, request) == applied
    assert counts(schema) == original_counts
    record("same-source", "在 READ ONLY 事务中同来源同内容返回原批次、目标和收据，行数不变")
    for label, mutation, expected in (("different-content", lambda p: p["employee"].update(instructions="不同稳定内容"), "SOURCE_CONTENT_CONFLICT"),
                                      ("different-source", lambda p: p.update(sourceRef="issue29-synthetic-v2"), "SCOPE_CONFLICT")):
        bad = deepcopy(request)
        mutation(bad)
        with connect(schema) as c:
            assert_error(lambda: boot.apply(c, bad), expected)
        assert counts(schema) == original_counts
        record(label, expected)
    # 用户后续合法动作使用真实正 UserId；这里仅合成状态和 actor，不声称维护 API 已完成。
    new_hash = "{bcrypt}" + bcrypt.hashpw(b"synthetic-changed-credential", bcrypt.gensalt(rounds=12)).decode()
    with connect(schema) as c:
        sql(c, "UPDATE user_credential SET password_hash=%s,is_password_change_required=0,credential_version=2,password_changed_at=UTC_TIMESTAMP(6) WHERE id=%s", (new_hash, r["credentialId"]))
        sql(c, "UPDATE platform_user SET account_status='DISABLED',security_version=2,authorization_version=2,modified_by_user_id=id,modification_source_kind='USER_ACTION',modification_bootstrap_batch_id=NULL WHERE id=%s", (r["userId"],))
        sql(c, "UPDATE employee_use_grant SET grant_status='REVOKED',revoked_by=%s,revoked_at=UTC_TIMESTAMP(6) WHERE id=%s", (r["userId"], r["grantId"]))
        sql(c, "UPDATE employee_release SET availability_status='DISABLED',availability_revision=2,availability_changed_by=%s,availability_source_kind='USER_ACTION',availability_bootstrap_batch_id=NULL WHERE id=%s", (r["userId"], r["releaseId"]))
        c.commit()
    with connect(schema) as c:
        assert boot.apply(c, request) == applied
        assert sql(c, "SELECT password_hash,is_password_change_required,credential_version FROM user_credential")[0] == {"password_hash": new_hash, "is_password_change_required": 0, "credential_version": 2}
        assert sql(c, "SELECT account_status FROM platform_user")[0]["account_status"] == "DISABLED"
        assert sql(c, "SELECT grant_status FROM employee_use_grant")[0]["grant_status"] == "REVOKED"
        assert sql(c, "SELECT availability_status FROM employee_release")[0]["availability_status"] == "DISABLED"
        audit_count = sql(c, "SELECT COUNT(*) AS n FROM identity_audit_event")[0]["n"]
        assert audit_count == 2
    assert readonly(schema) == applied
    record("preserve-later-state", "改密、首次改密标记、安全／授权版本、停用及撤权不被重置；原 manifest 仍有效")
    # 回滚：完整预置写入后、APPLIED 前失败，不留下账户或成功审计。
    rollback_schema = database("issue29_rollback")
    original_create = boot.create_facts
    def fail_after_facts(c, p, batch):
        original_create(c, p, batch)
        raise boot.BootstrapError("INJECTED_BEFORE_APPLIED")
    with connect(rollback_schema) as c, patch.object(boot, "create_facts", side_effect=fail_after_facts):
        assert_error(lambda: boot.apply(c, request), "INJECTED_BEFORE_APPLIED")
    assert sum(counts(rollback_schema).values()) == 0
    with connect(rollback_schema) as c:
        boot.apply(c, request)
    record("atomic-rollback", "预置和成功审计全部回滚；原来源安全重做")
    # COMMIT 已落地／未落地但响应丢失，必须丢弃原连接并独立按原键核对。
    for did_commit in (True, False):
        commit_schema = database("issue29_commit_" + ("applied" if did_commit else "absent"))
        with connect(commit_schema) as c:
            commit = c.commit
            def unknown_commit():
                if did_commit:
                    commit()
                else:
                    c.rollback()
                raise OSError("synthetic transport loss")
            with patch.object(c, "commit", side_effect=unknown_commit):
                assert_error(lambda: boot.apply(c, request), "COMMIT_OUTCOME_UNKNOWN")
        if did_commit:
            first = readonly(commit_schema)
            with connect(commit_schema) as c:
                assert boot.apply(c, request) == first
        else:
            assert sum(counts(commit_schema).values()) == 0
            with connect(commit_schema) as c:
                assert_error(lambda: boot.verify(c, request["sourceRef"]), "BATCH_NOT_APPLIED")
                boot.apply(c, request)
        record("commit-unknown-" + str(did_commit).lower(), "独立连接按原来源核对已应用／未应用；无第二份成功收据")
    # 并发两个独立连接，同时到达。每轮使用新的数据集。
    for mode in ("same", "different-content", "different-source"):
        concurrent_schema = database("issue29_race_" + mode.replace("-", "_"))
        second = deepcopy(request)
        if mode == "different-content":
            second["employee"]["instructions"] = "并发不同内容"
        if mode == "different-source":
            second["sourceRef"] = "issue29-racing-source"
        barrier = Barrier(2)
        def worker(p):
            with connect(concurrent_schema) as c:
                barrier.wait(timeout=10)
                try:
                    return boot.apply(c, p)
                except boot.BootstrapError as error:
                    return str(error)
        with ThreadPoolExecutor(max_workers=2) as pool:
            outcomes = list(pool.map(worker, [request, second]))
        successes = [o for o in outcomes if isinstance(o, dict)]
        assert len(successes) == (2 if mode == "same" else 1), [type(o).__name__ for o in outcomes]
        if mode == "same":
            assert outcomes[0] == outcomes[1]
        else:
            assert ("SOURCE_CONTENT_CONFLICT" if mode == "different-content" else "SCOPE_CONFLICT") in outcomes
        actual = counts(concurrent_schema)
        assert actual["bootstrap_batch"] == actual["platform_user"] == actual["employee_configuration_audit_event"] == 1
        assert readonly(concurrent_schema, successes[0]["sourceRef"]) == successes[0]
        record("race-" + mode, "唯一 scope 串行化：只产生一套完整事实与一份成功收据")
    # 持久 PREPARING、FAILED、RECONCILING 均不自动接管。
    for status in ("PREPARING", "FAILED", "RECONCILING"):
        stuck_schema = database("issue29_stuck_" + status.lower())
        with connect(stuck_schema) as c:
            sql(c, "INSERT INTO bootstrap_batch(source_ref,bootstrap_scope,status,program_version,created_at,gmt_create,gmt_modified) VALUES(%s,%s,%s,'synthetic',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", (request["sourceRef"], boot.SCOPE, status))
            c.commit()
            assert_error(lambda: boot.apply(c, request), "RECONCILIATION_REQUIRED")
        assert counts(stuck_schema)["platform_user"] == 0
        record("stuck-" + status.lower(), "拒绝接管未证实批次，要求核对")
    dirty_schema = database("issue29_nonempty")
    with connect(dirty_schema) as c:
        sql(c, "INSERT INTO fixed_admin_assignment(singleton_key,user_id,initialization_ref,initialized_at,gmt_create,gmt_modified) VALUES(1,99,'synthetic-existing-data',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")
        c.commit()
        assert_error(lambda: boot.apply(c, request), "TARGET_NOT_EMPTY")
    assert counts(dirty_schema)["bootstrap_batch"] == 0
    record("nonempty", "拒绝已有事实的目标库，不覆盖、不补齐")
    # 异常引用／坏正文在专用探针数据集，不污染单用户基线。
    probe_schema = database("issue29_anomaly_probes")
    with connect(probe_schema) as c:
        probe = boot.apply(c, request)
        pr = probe["result"]
        for statement, args, code in [
            ("INSERT INTO platform_user(display_name,status_changed_at,gmt_create,gmt_modified) VALUES('bad',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", (), 3819),
            ("UPDATE platform_user SET modification_source_kind='SYSTEM_BOOTSTRAP',modification_bootstrap_batch_id=NULL WHERE id=%s", (pr["userId"],), 3819),
            ("UPDATE employee_configuration_audit_event SET receipt_scope_id=999 WHERE id=%s", (pr["configurationAuditId"],), 3819),
            ("UPDATE employee_release SET availability_status='DISABLED' WHERE id=%s", (pr["releaseId"],), 3819),
            ("UPDATE employee_use_grant SET grant_status='REVOKED' WHERE id=%s", (pr["grantId"],), 3819),
            ("UPDATE employee_release SET creation_source_kind='USER_ACTION',bootstrap_batch_id=NULL WHERE id=%s", (pr["releaseId"],), 3819),
            ("UPDATE platform_user SET security_version=2 WHERE id=%s", (pr["userId"],), 3819),
            ("UPDATE employee_draft SET draft_revision=2 WHERE employee_id=%s", (pr["employeeId"],), 3819),
        ]:
            expect_sql(c, statement, args, code)
            c.rollback()
        columns = [r["COLUMN_NAME"] for r in sql(c, "SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='employee_configuration_audit_event' AND COLUMN_NAME<>'id' AND EXTRA NOT LIKE '%%GENERATED%%' ORDER BY ORDINAL_POSITION")]
        column_list = ",".join(columns)
        expect_sql(c, f"INSERT INTO employee_configuration_audit_event({column_list}) SELECT {column_list} FROM employee_configuration_audit_event", (), 1062)
        c.rollback()
        identity_columns = [r["COLUMN_NAME"] for r in sql(c, "SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='identity_audit_event' AND COLUMN_NAME<>'id' AND EXTRA NOT LIKE '%%GENERATED%%' ORDER BY ORDINAL_POSITION")]
        identity_list = ",".join(identity_columns)
        expect_sql(c, f"INSERT INTO identity_audit_event({identity_list}) SELECT {identity_list} FROM identity_audit_event WHERE id=%s", (pr["identityAuditIds"][0],), 1062)
        c.rollback()
        selected = ["'FAILURE'" if name == "outcome" else "NULL" if name == "result_code" else "'SYNTHETIC_FAILURE'" if name == "reason_code" else name for name in columns]
        for _ in range(2):
            sql(c, f"INSERT INTO employee_configuration_audit_event({column_list}) SELECT {','.join(selected)} FROM employee_configuration_audit_event WHERE id=%s", (pr["configurationAuditId"],))
        assert sql(c, "SELECT COUNT(*) AS n FROM employee_configuration_audit_event WHERE outcome='FAILURE'")[0]["n"] == 2
        c.rollback()
        record("negative-constraints", "8 项来源／状态 CHECK 拒绝、身份与配置重复成功收据唯一拒绝；重复失败审计允许")
        mutations = [
            ("UPDATE employee_release_skill_resource SET resource_content='tamper' WHERE resource_path='empty.txt'", "RESOURCE_DIGEST_MISMATCH"),
            ("UPDATE employee_release_skill SET description='tamper'", "SKILL_MISMATCH"),
            ("UPDATE tool_binding_revision SET tool_name='tamper'", "BINDING_REFERENCE_MISMATCH"),
            ("UPDATE employee_release SET config_json=JSON_SET(config_json,'$.instructions','tamper')", "RELEASE_MANIFEST_MISMATCH"),
            ("UPDATE employee_release SET employee_id=999", "RELEASE_OWNER_MISMATCH"),
            ("UPDATE employee_use_grant SET user_id=999", "GRANT_OWNER_MISMATCH"),
            ("DELETE FROM employee_release_skill_resource WHERE resource_path='empty.txt'", "RESOURCE_REFERENCE_MISMATCH"),
            ("UPDATE bootstrap_batch SET manifest_sha256=UNHEX(REPEAT('00',32))", "BOOTSTRAP_MANIFEST_MISMATCH"),
            ("UPDATE identity_audit_event SET target_user_id=999 WHERE action_code='USER_CREATED'", "INVALID_IDENTITY_AUDIT"),
            ("UPDATE employee_configuration_audit_event SET employee_id=999", "INVALID_RECEIPT"),
            ("DELETE FROM user_credential", "MISSING_TARGET")]
        mutations.extend([
            ("UPDATE phone_reservation SET reservation_status='HELD',held_at=UTC_TIMESTAMP(6)", "MISSING_CURRENT_PHONE"),
            ("UPDATE digital_employee SET current_release_id=999", "MISSING_TARGET"),
            ("DELETE FROM employee_draft", "MISSING_TARGET"),
            ("UPDATE employee_configuration_audit_event SET id=id+100", "MISSING_TARGET")])
        for number, (statement, expected) in enumerate(mutations, 1):
            sql(c, statement)
            assert_error(lambda: boot.verify(c, request["sourceRef"]), expected)
            c.rollback()
            record("anomaly-" + str(number), expected)
        # 无物理外键：形式完整但 batch 不存在，必须由关联巡检检出。
        sql(c, "UPDATE phone_reservation SET ownership_bootstrap_batch_id=999")
        patrol = (ROOT / "db/tests/12_初始化来源巡检.sql").read_text(encoding="utf-8")
        # pymysql 单语句执行，跳过注释后逐条读取。
        checks = [s.strip() for s in "\n".join(line for line in patrol.splitlines() if not line.startswith("--")).split(";") if s.strip()]
        anomalies = {r["check_name"]: r["anomaly_count"] for query in checks for r in sql(c, query)}
        assert anomalies["source_3"] > 0
        c.rollback()
        clean = {r["check_name"]: r["anomaly_count"] for query in checks for r in sql(c, query)}
        assert len(clean) == 12 and not any(clean.values())
        c.rollback()
        record("source-patrol", "12 项只读巡检基线为零，完整但悬空系统来源命中")
    # 合法后续状态也不误报来源巡检。
    with connect(schema) as c:
        clean = {r["check_name"]: r["anomaly_count"] for query in checks for r in sql(c, query)}
        assert not any(clean.values())
    record("legal-state-patrol", "改密／撤权／停用后来源巡检仍为零")
    image_request = deepcopy(request)
    image_request["employee"]["storageProfiles"] = [{"id": "synthetic-originals-v1", "sha256": "a" * 64}]
    image_request["employee"]["images"] = {"storageProfile": "synthetic-originals-v1", "mimeTypes": ["image/jpeg", "image/png"],
        "maxCount": 4, "maxBytesPerImage": 5242880, "maxBytesPerMessage": 10485760, "maxPixelsPerImage": 16000000}
    image_schema = database("issue29_image_profile")
    with connect(image_schema) as c:
        image_applied = boot.apply(c, image_request)
    assert readonly(image_schema) == image_applied
    with connect(image_schema) as c:
        stored = sql(c, "SELECT config_json FROM employee_release")[0]["config_json"]
        image_config = boot.load_json(stored)
        assert image_config["images"] == image_request["employee"]["images"]
        assert image_config["storageProfiles"] == image_request["employee"]["storageProfiles"]
        manifest = boot.load_json(sql(c, "SELECT manifest_json FROM bootstrap_batch")[0]["manifest_json"])
        assert {"kind": "storage_profile", **image_request["employee"]["storageProfiles"][0]} in manifest["immutableTargets"]
    record("image-profile", "图片限额及存储引用随固定发布落库，独立读回与 manifest 中存储摘要一致")
    before_images = counts(image_schema)
    with connect(image_schema) as c:
        assert boot.apply(c, image_request) == image_applied
    assert counts(image_schema) == before_images
    changed = deepcopy(image_request)
    changed["employee"]["images"]["maxCount"] = 3
    with connect(image_schema) as c:
        assert_error(lambda: boot.apply(c, changed), "SOURCE_CONTENT_CONFLICT")
    record("image-policy-conflict", "同内容返回原结果；同来源更改图片限额拒绝，不创建新版或覆盖原发布")
    with connect(image_schema) as c:
        changed_config = deepcopy(image_config)
        changed_config["storageProfiles"][0]["sha256"] = "b" * 64
        sql(c, "UPDATE employee_release SET config_json=%s,manifest_sha256=%s", (boot.encoded(changed_config), boot.digest(changed_config)))
        assert_error(lambda: boot.verify(c, request["sourceRef"]), "STORAGE_PROFILE_MISMATCH")
        c.rollback()
    assert readonly(image_schema) == image_applied
    record("image-profile-anomaly", "存储清单损坏即使重算发布正文摘要仍被独立关联核对检出；回滚后正常")
    return {"cases": records, "unit_checks": unit_checks(request), "synthetic_rows_rolled_back": False,
            "synthetic_dataset_disposal": "entire owned temporary MySQL instance removed after verification",
            "real_user_seeded": False, "shared_resources_accessed": False, "source_patrol_checks": 12,
            "single_user_dataset": "issue29_single_user", "anomaly_dataset": "issue29_anomaly_probes",
            "manifest_sha256": applied["manifestSha256"], "release_fingerprints": release_fingerprints,
            "structure_sha256": structure_sha256, "sql_expected_rejections": 10,
            "race_scenarios": 3, "controlled_bootstrap_execution_tested": True,
            "isolation": {"bound_to_loopback": True, "random_port": True, "datadir_verified_before_ddl": True,
                          "default_client_config_and_login_path_bypassed": True}}


def main():
    request = fixture()
    mysql_bin = Path(shutil.which("mysql")).parent
    ddl_bytes = {path: path.read_bytes() for path in DDL}
    evidence_sources = [ROOT / "db/bootstrap/controlled_bootstrap.py", Path(__file__),
                        ROOT / "db/tests/12_初始化来源巡检.sql", Path(base.__file__)]
    pinned_sources = {path: path.read_bytes() for path in evidence_sources}
    # 其他工单可能同时修改公共 DDL；本次全部 schema 使用同一份显式结构快照。
    target = ROOT / "backend/target"
    target.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="issue29-ddl-", dir=target) as snapshot_dir:
        snapshots = []
        for path, content in ddl_bytes.items():
            snapshot = Path(snapshot_dir) / path.name
            snapshot.write_bytes(content)
            snapshots.append(snapshot)
        summary = base.verify(mysql_bin, ddl_files=snapshots, probes=[], expected_table_count=30,
            inspection_check=lambda _: 0, concurrency_check=lambda _: 0, extra_checks=lambda client: integration(client, request, ddl_bytes))
    assert all(path.read_bytes() == content for path, content in {**ddl_bytes, **pinned_sources}.items()), "Source changed during verification; rerun against current sources"
    for unused in ("probes", "expected_rejections", "positive_checks", "inspection_checks", "concurrency_checks"):
        summary.pop(unused)
    summary.update({"contract": "Issue #29; #22 数据契约 §12/§13; #23 阶段1/06",
        "case_count": len(summary["cases"]), "ddl_sha256": {p.relative_to(ROOT).as_posix(): hashlib.sha256(content).hexdigest() for p, content in ddl_bytes.items()},
        "implementation_sha256": hashlib.sha256((ROOT / "db/bootstrap/controlled_bootstrap.py").read_bytes()).hexdigest(),
        "probe_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "patrol_sha256": hashlib.sha256((ROOT / "db/tests/12_初始化来源巡检.sql").read_bytes()).hexdigest(),
        "isolated_lifecycle_sha256": hashlib.sha256(pinned_sources[Path(base.__file__)]).hexdigest(),
        "ddl_snapshot_verified_unchanged": True,
        "executed_at": datetime.now(timezone.utc).isoformat(),
        "python_version": sys.version.split()[0], "pymysql_version": pymysql.VERSION_STRING,
        "bcrypt_version": bcrypt.__version__, "yaml_version": boot.yaml.__version__,
        "temporary_instance_stopped_and_removed": True,
        "real_acceptance": "PENDING: target database, trusted identity/credential source and complete real release inputs"})
    EVIDENCE.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: #29 {summary['case_count']} MySQL cases + {summary['unit_checks']} encoding/input checks; owned instance removed")


if __name__ == "__main__":
    main()
