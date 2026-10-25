"""新建一次性本机 MySQL 实例验收 01～04 号 DDL（21 张表）；不读取登录路径或连接既有数据库。

依次运行旧的 03 号会话探针（确认无回归）、04 号记忆控制探针、06 号扩展列与会话运行资料探针，
再用 07 号种子验证 05 号巡检（基线全 0、注入异常全部命中），最后用多个并发 mysql 客户端进程验证三条纯 SQL 并发性质。
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import time


ROOT = Path(__file__).resolve().parents[2]
TESTS = Path(__file__).resolve().parent
PROBES = [TESTS / "03_通用素材与工具操作建表验收.sql", TESTS / "04_记忆控制建表验收.sql",
          TESTS / "06_记忆扩展与会话运行资料验收.sql"]
INSPECTION = TESTS / "05_记忆控制关联巡检.sql"
INSPECTION_SEED = TESTS / "07_记忆控制巡检种子.sql"
DDL_FILES = [ROOT / "db/schema/01_用户与管理员建表.sql", ROOT / "db/schema/02_会话运行消息建表.sql",
             ROOT / "db/schema/03_记忆控制建表.sql", ROOT / "db/schema/04_会话运行资料建表.sql"]
EXPECTED_TABLE_COUNT = 21
EXPECTED_INSPECTIONS = 11
TEMP_PREFIX = "agentscope-memory-schema-"
HIDDEN = getattr(subprocess, "CREATE_NO_WINDOW", 0)
CLIENT_ENV = None


def run(args, *, sql=None, timeout=30):
    return subprocess.run(args, input=sql, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=timeout, creationflags=HIDDEN, check=False, env=CLIENT_ENV)


def require_success(result, phase):
    if result.returncode:
        raise RuntimeError(f"{phase}失败：{result.stderr.decode('utf-8', errors='replace')[-2000:]}")
    return result.stdout.decode("utf-8", errors="replace").replace("\r", "")


def run_probe(client, probe):
    sql = probe.read_bytes()
    expected_errors = [(int(code), names.decode().split("|"))
                       for code, names in re.findall(rb"EXPECT_ERROR\s+(\d+)\s+([\w|]+)", sql)]
    result = run(client + ["--force"], sql=sql)
    actual_errors = [(int(match.group(1)), match.group(0).decode("utf-8", errors="replace"))
                     for match in re.finditer(rb"ERROR\s+(\d+)[^\n]*", result.stderr)]
    output = require_success(result, f"运行 {probe.name}")
    expected_passes = len(re.findall(rb"-- EXPECT_PASS", sql))
    if len(actual_errors) != len(expected_errors) or any(code != actual_code or not any(name in detail for name in names)
            for (code, names), (actual_code, detail) in zip(expected_errors, actual_errors)):
        raise AssertionError(f"{probe.name} 拒绝探针不匹配：expected={expected_errors}; actual={actual_errors}")
    if output.splitlines() != ["PASS"] * expected_passes:
        raise AssertionError(f"{probe.name} 接纳/回滚探针失败：{output}")
    return len(expected_errors), expected_passes


def tab_rows(output):
    return [line.split("\t") for line in output.splitlines() if line]


def run_inspection(client):
    """基线段所有巡检必须为 0，注入异常段每项必须大于 0；全程在事务内，最后回滚。"""
    seed = INSPECTION_SEED.read_text(encoding="utf-8")
    baseline, rest = seed.split("-- INSPECT_ALL_ZERO")
    anomalies = rest.split("-- INSPECT_ALL_POSITIVE")[0]
    inspection = INSPECTION.read_text(encoding="utf-8")
    script = (baseline + inspection + "SELECT 'MARK', 0;\n" + anomalies + inspection + "ROLLBACK;\n"
              "SELECT 'REMAINING', (SELECT COUNT(*) FROM platform_user) + (SELECT COUNT(*) FROM business_session)"
              " + (SELECT COUNT(*) FROM business_message) + (SELECT COUNT(*) FROM business_run)"
              " + (SELECT COUNT(*) FROM memory_profile) + (SELECT COUNT(*) FROM memory_update_job);\n")
    rows = tab_rows(require_success(run(client, sql=script.encode("utf-8")), "运行 05 号巡检与 07 号种子"))
    marks = [i for i, row in enumerate(rows) if row[0] == "MARK"]
    if len(marks) != 1 or rows[-1] != ["REMAINING", "0"]:
        raise AssertionError(f"巡检输出结构异常：{rows}")
    before, after = rows[:marks[0]], rows[marks[0] + 1:-1]
    if len(before) != EXPECTED_INSPECTIONS or any(count != "0" for _, count in before):
        raise AssertionError(f"基线数据上巡检不为全 0：{before}")
    if [name for name, _ in after] != [name for name, _ in before] or any(int(count) <= 0 for _, count in after):
        raise AssertionError(f"注入异常后存在未命中的巡检项：{after}")
    return len(before)


def barrier_sql(seconds=3):
    start = (datetime.now(timezone.utc) + timedelta(seconds=seconds)).strftime("%Y-%m-%d %H:%M:%S.%f")
    return f"DO SLEEP(GREATEST(0, TIMESTAMPDIFF(MICROSECOND, UTC_TIMESTAMP(6), '{start}') / 1000000));\n"


def run_workers(client, scripts):
    with ThreadPoolExecutor(max_workers=len(scripts)) as pool:
        futures = [pool.submit(lambda s: require_success(run(client, sql=s.encode("utf-8"), timeout=180), "并发工作进程"), s)
                   for s in scripts]
        return [future.result() for future in futures]


def run_concurrency(client):
    """多个并发 mysql 客户端：输入序号分配、任务领取、激活条件更新。提交的合成数据在末尾删除并核对为空。"""
    now = "UTC_TIMESTAMP(6)"
    digest = "UNHEX(REPEAT('ab',32))"

    # 1 输入序号：同一（用户，员工）并发分配，序号互不重复、无空洞。
    workers, iterations = 6, 15
    require_success(run(client, sql=(
        "INSERT INTO memory_profile (user_id, employee_id, document_kv_version, document_sha256, memory_schema_version, policy_version, activated_at, gmt_create, gmt_modified) "
        f"VALUES (960001, 1, 1, {digest}, 'v1', 'p1', {now}, {now}, {now});").encode()), "并发序号准备数据")
    step = ("START TRANSACTION;\n"
            f"UPDATE memory_profile SET last_input_sequence = last_input_sequence + 1, gmt_modified = {now} WHERE user_id = 960001 AND employee_id = 1;\n"
            "SELECT last_input_sequence FROM memory_profile WHERE user_id = 960001 AND employee_id = 1;\nCOMMIT;\n")
    outputs = run_workers(client, [barrier_sql() + step * iterations for _ in range(workers)])
    sequences = sorted(int(line) for output in outputs for line in output.split())
    if sequences != list(range(1, workers * iterations + 1)):
        raise AssertionError(f"并发输入序号出现重复或空洞：{sequences}")

    # 2 任务领取：多个客户端争同一批 PENDING 任务，每个任务恰好被领取一次。
    job_ids = list(range(961001, 961021))
    values = ",".join(f"({job_id}, 960001, 1, 1, 'FLUSH', 960001, {job_id}, {job_id}, 1, 1, 'v1', 'p1', {now} - INTERVAL 1 MINUTE, {now}, {now})"
                      for job_id in job_ids)
    require_success(run(client, sql=(
        "INSERT INTO memory_update_job (id, user_id, employee_id, generation, task_type, source_session_id, source_run_id, source_message_id, source_input_sequence, "
        f"authorization_version_at_creation, memory_schema_version, policy_version, next_attempt_at, gmt_create, gmt_modified) VALUES {values};").encode()), "并发领取准备数据")
    tokens = [secrets.token_hex(16) for _ in range(8)]
    scripts = []
    for token in tokens:
        body = "".join(
            f"UPDATE memory_update_job SET status = 'RUNNING', claim_token = UNHEX('{token}'), claimed_at = {now}, lease_expires_at = {now} + INTERVAL 10 MINUTE, "
            f"attempt_count = attempt_count + 1, gmt_modified = {now} WHERE id = {job_id} AND status = 'PENDING' AND next_attempt_at <= {now};\n"
            f"SELECT {job_id}, ROW_COUNT();\n" for job_id in job_ids)
        scripts.append(barrier_sql() + body)
    claimed = {}
    for output in run_workers(client, scripts):
        for job_id, rows in tab_rows(output):
            claimed[job_id] = claimed.get(job_id, 0) + int(rows)
    if sorted(claimed) != [str(i) for i in job_ids] or any(total != 1 for total in claimed.values()):
        raise AssertionError(f"任务领取不是恰好一次：{claimed}")
    state = tab_rows(require_success(run(client, sql=(
        "SELECT COUNT(*), SUM(attempt_count = 1), SUM(status = 'RUNNING'), COUNT(DISTINCT claim_token) "
        "FROM memory_update_job WHERE id BETWEEN 961001 AND 961020;").encode()), "核对领取结果"))[0]
    if int(state[0]) != 20 or int(state[1]) != 20 or int(state[2]) != 20 or not 1 <= int(state[3]) <= len(tokens):
        raise AssertionError(f"领取后任务状态异常：{state}")

    # 3 激活条件更新：相同代次与修订的多个客户端同时激活，每个控制记录恰好一个成功。
    users = list(range(962001, 962021))
    values = ",".join(f"({user}, 1, 1, {digest}, 'v1', 'p1', {now}, {now}, {now})" for user in users)
    require_success(run(client, sql=(
        "INSERT INTO memory_profile (user_id, employee_id, document_kv_version, document_sha256, memory_schema_version, policy_version, activated_at, gmt_create, gmt_modified) "
        f"VALUES {values};").encode()), "并发激活准备数据")
    keys = [secrets.token_hex(16) for _ in range(4)]
    scripts = []
    for key in keys:
        body = "".join(
            f"UPDATE memory_profile SET generation = generation + 1, document_revision = 1, document_kv_version = 1, document_sha256 = {digest}, "
            f"generation_input_cutoff = last_input_sequence, last_operation_key = UNHEX('{key}'), last_operation_sha256 = UNHEX(REPEAT('cd',32)), "
            f"activated_at = {now}, gmt_modified = {now} WHERE user_id = {user} AND employee_id = 1 AND generation = 1 AND document_revision = 1;\n"
            f"SELECT {user}, ROW_COUNT();\n" for user in users)
        scripts.append(barrier_sql() + body)
    activated = {}
    for output in run_workers(client, scripts):
        for user, rows in tab_rows(output):
            activated[user] = activated.get(user, 0) + int(rows)
    if sorted(activated) != [str(i) for i in users] or any(total != 1 for total in activated.values()):
        raise AssertionError(f"激活不是恰好一个成功：{activated}")
    state = tab_rows(require_success(run(client, sql=(
        "SELECT COUNT(*), SUM(generation = 2), SUM(document_revision = 1) FROM memory_profile WHERE user_id BETWEEN 962001 AND 962020;").encode()), "核对激活结果"))[0]
    if [int(value) for value in state] != [20, 20, 20]:
        raise AssertionError(f"激活后控制记录异常：{state}")

    require_success(run(client, sql=b"DELETE FROM memory_update_job; DELETE FROM memory_profile;"), "清理并发合成数据")
    remaining = require_success(run(client, sql=b"SELECT (SELECT COUNT(*) FROM memory_profile) + (SELECT COUNT(*) FROM memory_update_job);"), "核对清理").strip()
    if remaining != "0":
        raise AssertionError("并发合成数据未清理干净")
    return 3


def verify(mysql_bin, *, ddl_files=None, probes=None, expected_table_count=None,
           inspection_check=None, concurrency_check=None, extra_checks=None):
    """默认仍验收 21 表；供全库入口复用同一隔离实例生命周期。"""
    global CLIENT_ENV
    ddl_files = DDL_FILES if ddl_files is None else ddl_files
    probes = PROBES if probes is None else probes
    expected_table_count = EXPECTED_TABLE_COUNT if expected_table_count is None else expected_table_count
    inspection_check = run_inspection if inspection_check is None else inspection_check
    concurrency_check = run_concurrency if concurrency_check is None else concurrency_check
    exe_suffix = ".exe" if (mysql_bin / "mysql.exe").exists() else ""
    mysql = mysql_bin / f"mysql{exe_suffix}"
    mysqld = mysql_bin / f"mysqld{exe_suffix}"
    mysqladmin = mysql_bin / f"mysqladmin{exe_suffix}"
    for executable in (mysql, mysqld, mysqladmin):
        if not executable.is_file():
            raise RuntimeError(f"未找到 MySQL 可执行文件：{executable}")

    with socket.socket() as probe_socket:
        probe_socket.bind(("127.0.0.1", 0))
        port = probe_socket.getsockname()[1]
    temp = tempfile.TemporaryDirectory(prefix=TEMP_PREFIX)
    isolated_root = Path(temp.name).resolve()
    # 清理仅允许本执行器刚创建的系统临时目录，绝不递归操作工作区或既有 MySQL 目录。
    if isolated_root.parent != Path(tempfile.gettempdir()).resolve() or not isolated_root.name.startswith(TEMP_PREFIX):
        raise RuntimeError("隔离目录校验失败")
    data = isolated_root / "data"
    log = isolated_root / "mysql.log"
    previous_env = CLIENT_ENV
    CLIENT_ENV = os.environ.copy()
    # MySQL 8.0 不支持 --no-login-paths；显式改用本轮不存在的登录文件，不读取用户的 .mylogin.cnf。
    CLIENT_ENV["MYSQL_TEST_LOGIN_FILE"] = str(isolated_root / "unused-login.cnf")
    server = None
    instance_verified = False
    client = [str(mysql), "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", f"--port={port}",
              "--user=root", "--password=", "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names"]
    try:
        require_success(run([str(mysql), "--no-defaults", "--version"], timeout=5), "检查客户端")
        require_success(run([str(mysqld), "--no-defaults", "--initialize-insecure",
                             f"--basedir={mysql_bin.parent}", f"--datadir={data}"], timeout=120), "初始化临时实例")
        server = subprocess.Popen([str(mysqld), "--no-defaults", f"--basedir={mysql_bin.parent}", f"--datadir={data}",
                                   "--bind-address=127.0.0.1", f"--port={port}", "--mysqlx=0", "--skip-log-bin",
                                   f"--log-error={log}", f"--pid-file={isolated_root / 'mysql.pid'}"],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=HIDDEN, env=CLIENT_ENV)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if server.poll() is not None:
                raise RuntimeError(f"临时 MySQL 提前退出：{log.read_text(encoding='utf-8', errors='replace')[-2000:]}")
            if run(client + ["--execute", "SELECT 1"], timeout=3).returncode == 0:
                break
            time.sleep(0.25)
        else:
            raise RuntimeError(f"临时 MySQL 启动超时：{log.read_text(encoding='utf-8', errors='replace')[-2000:]}")

        actual_datadir = require_success(run(client + ["--execute", "SELECT @@datadir"]), "核对实例数据目录").strip()
        if Path(actual_datadir).resolve() != data.resolve():
            raise RuntimeError("拒绝连接数据目录不属于本轮临时实例的 MySQL")
        instance_verified = True

        version = require_success(run(client + ["--execute", "SELECT VERSION()"]), "读取版本").strip()
        if "mariadb" in version.lower() or tuple(map(int, version.split("-")[0].split(".")[:3])) < (8, 0, 16):
            raise RuntimeError(f"需要 MySQL 8.0.16+，当前版本为 {version}")
        require_success(run(client, sql=b"CREATE DATABASE memory_schema_probe CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"), "创建隔离 schema")
        client += ["--database=memory_schema_probe"]
        for ddl in ddl_files:
            require_success(run(client, sql=ddl.read_bytes()), f"建立 {ddl.name}")

        actual_tables = require_success(run(client + ["--execute", "SHOW TABLES"]), "清点表").splitlines()
        expected_tables = [name for ddl in ddl_files for name in re.findall(r"CREATE TABLE (\w+)", ddl.read_text(encoding="utf-8"))]
        if len(actual_tables) != expected_table_count or set(actual_tables) != set(expected_tables):
            raise AssertionError(f"表结构不符：{actual_tables}")
        checks = """
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_comment='';
SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_comment='';
SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='FOREIGN KEY';
SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE();
SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('memory_profile','memory_update_job','workspace_entry','workspace_snapshot_reference') AND (data_type LIKE '%blob' OR data_type LIKE '%text' OR data_type='json');
SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name LIKE 'is\\_%' AND column_type<>'tinyint unsigned';
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agentscope_store';
"""
        if require_success(run(client, sql=checks.encode()), "检查结构规约").splitlines() != ["0"] * 7:
            raise AssertionError("表/列注释、无外键/过程、记忆与资料表无正文/JSON 列、是否字段规约或不得创建框架 KV 表的检查失败")

        rejections = passes = 0
        probe_results = {}
        for probe in probes:
            probe_rejections, probe_passes = run_probe(client, probe)
            rejections += probe_rejections
            passes += probe_passes
            probe_results[probe.name] = {"rejections": probe_rejections, "positive_checks": probe_passes}
        inspections = inspection_check(client)
        concurrency = concurrency_check(client)
        extra = extra_checks(client) if extra_checks is not None else {}
        summary = {"mysql_version": version, "tables": len(actual_tables), "expected_rejections": rejections,
                   "positive_checks": passes, "inspection_checks": inspections, "concurrency_checks": concurrency,
                   "synthetic_rows_rolled_back": True,
                    "framework_kv_tested": False, "application_integration_tested": False,
                    "probes": probe_results, **extra}
        print(json.dumps(summary, ensure_ascii=False))
        return summary
    finally:
        if server is not None and server.poll() is None:
            stopped = False
            # 端口就绪不代表实例属于本轮。身份未核验时绝不向该端口发 shutdown。
            if instance_verified:
                admin = [str(mysqladmin), "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", f"--port={port}",
                         "--user=root", "--password=", "shutdown"]
                try:
                    require_success(run(admin, timeout=15), "关闭已核验临时实例")
                    server.wait(timeout=15)
                    stopped = True
                except (subprocess.TimeoutExpired, RuntimeError):
                    pass
            if not stopped:
                # 只终止本函数创建并持有的 Popen 进程树，不按端口或进程名杀服务。
                if os.name == "nt":
                    killed = run(["taskkill", "/PID", str(server.pid), "/T", "/F"], timeout=15)
                    if killed.returncode and server.poll() is None:
                        require_success(killed, "终止本轮未核验实例进程树")
                else:
                    server.kill()
                server.wait(timeout=15)
        if isolated_root.parent != Path(tempfile.gettempdir()).resolve() or not isolated_root.name.startswith(TEMP_PREFIX):
            raise RuntimeError("拒绝清理非本任务临时目录")
        try:
            temp.cleanup()
        finally:
            CLIENT_ENV = previous_env


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    discovered_mysql = shutil.which("mysql")
    parser.add_argument("--mysql-bin", type=Path, default=Path(discovered_mysql).parent if discovered_mysql else None,
                        help="包含 mysqld、mysql、mysqladmin 的 MySQL bin 目录")
    options = parser.parse_args()
    if options.mysql_bin is None:
        parser.error("请用 --mysql-bin 指定 MySQL 安装的 bin 目录")
    verify(options.mysql_bin.resolve())
