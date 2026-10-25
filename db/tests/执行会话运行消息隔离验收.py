"""新建一次性本机 MySQL 实例验收通用化 DDL；不读取登录路径或连接既有数据库。"""

import argparse
import json
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time


ROOT = Path(__file__).resolve().parents[2]
PROBES = Path(__file__).with_name("03_通用素材与工具操作建表验收.sql")
DDL_FILES = [ROOT / "db/schema/01_用户与管理员建表.sql", ROOT / "db/schema/02_会话运行消息建表.sql"]
HIDDEN = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def run(args, *, sql=None, timeout=30):
    return subprocess.run(args, input=sql, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=timeout, creationflags=HIDDEN, check=False)


def require_success(result, phase):
    if result.returncode:
        raise RuntimeError(f"{phase}失败：{result.stderr.decode('utf-8', errors='replace')[-2000:]}")
    return result.stdout.decode("utf-8", errors="replace").replace("\r", "")


def verify(mysql_bin):
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
    temp = tempfile.TemporaryDirectory(prefix="agentscope-generic-schema-")
    isolated_root = Path(temp.name).resolve()
    # 清理仅允许本执行器刚创建的系统临时目录，绝不递归操作工作区或既有 MySQL 目录。
    if isolated_root.parent != Path(tempfile.gettempdir()).resolve() or not isolated_root.name.startswith("agentscope-generic-schema-"):
        raise RuntimeError("隔离目录校验失败")
    data = isolated_root / "data"
    log = isolated_root / "mysql.log"
    server = None
    client = [str(mysql), "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", f"--port={port}",
              "--user=root", "--password=", "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names"]
    try:
        require_success(run([str(mysqld), "--no-defaults", "--initialize-insecure",
                             f"--basedir={mysql_bin.parent}", f"--datadir={data}"], timeout=120), "初始化临时实例")
        server = subprocess.Popen([str(mysqld), "--no-defaults", f"--basedir={mysql_bin.parent}", f"--datadir={data}",
                                   "--bind-address=127.0.0.1", f"--port={port}", "--mysqlx=0", "--skip-log-bin",
                                   f"--log-error={log}", f"--pid-file={isolated_root / 'mysql.pid'}"],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=HIDDEN)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if server.poll() is not None:
                raise RuntimeError(f"临时 MySQL 提前退出：{log.read_text(encoding='utf-8', errors='replace')[-2000:]}")
            if run(client + ["--execute", "SELECT 1"], timeout=3).returncode == 0:
                break
            time.sleep(0.25)
        else:
            raise RuntimeError("临时 MySQL 启动超时")

        version = require_success(run(client + ["--execute", "SELECT VERSION()"]), "读取版本").strip()
        if "mariadb" in version.lower() or tuple(map(int, version.split("-")[0].split(".")[:3])) < (8, 0, 16):
            raise RuntimeError(f"需要 MySQL 8.0.16+，当前版本为 {version}")
        require_success(run(client, sql=b"CREATE DATABASE generic_schema_probe CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"), "创建隔离 schema")
        client += ["--database=generic_schema_probe"]
        for ddl in DDL_FILES:
            require_success(run(client, sql=ddl.read_bytes()), f"建立 {ddl.name}")

        actual_tables = require_success(run(client + ["--execute", "SHOW TABLES"]), "清点表").splitlines()
        expected_tables = [name for ddl in DDL_FILES for name in re.findall(r"CREATE TABLE (\w+)", ddl.read_text(encoding="utf-8"))]
        if len(actual_tables) != 17 or set(actual_tables) != set(expected_tables):
            raise AssertionError(f"表结构不符：{actual_tables}")
        checks = """
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_comment='';
SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_comment='';
SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='FOREIGN KEY';
SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE();
SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='message_media' AND (data_type LIKE '%blob' OR column_name='media_bytes');
SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name LIKE 'is\\_%' AND column_type<>'tinyint unsigned';
"""
        if require_success(run(client, sql=checks.encode()), "检查结构规约").splitlines() != ["0"] * 6:
            raise AssertionError("表/列注释、无外键/过程、素材无 BLOB 或是否字段规约检查失败")

        sql = PROBES.read_bytes()
        expected_errors = [(int(code), name.decode()) for code, name in re.findall(rb"EXPECT_ERROR\s+(\d+)\s+(\w+)", sql)]
        result = run(client + ["--force"], sql=sql)
        actual_errors = [(int(match.group(1)), match.group(0).decode("utf-8", errors="replace"))
                         for match in re.finditer(rb"ERROR\s+(\d+)[^\n]*", result.stderr)]
        output = require_success(result, "运行约束探针")
        expected_passes = len(re.findall(rb"-- EXPECT_PASS", sql))
        if len(actual_errors) != len(expected_errors) or any(code != actual_code or name not in detail
                for (code, name), (actual_code, detail) in zip(expected_errors, actual_errors)):
            raise AssertionError(f"拒绝探针不匹配：expected={expected_errors}; actual={actual_errors}")
        if output.splitlines() != ["PASS"] * expected_passes:
            raise AssertionError(f"接纳/回滚探针失败：{output}")
        summary = {"mysql_version": version, "tables": len(actual_tables), "expected_rejections": len(expected_errors),
                   "positive_checks": expected_passes, "synthetic_rows_rolled_back": True,
                   "object_storage_tested": False, "application_integration_tested": False}
        print(json.dumps(summary, ensure_ascii=False))
    finally:
        if server is not None and server.poll() is None:
            admin = [str(mysqladmin), "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", f"--port={port}",
                     "--user=root", "--password=", "shutdown"]
            try:
                run(admin, timeout=15)
                server.wait(timeout=15)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=15)
        if isolated_root.parent != Path(tempfile.gettempdir()).resolve() or not isolated_root.name.startswith("agentscope-generic-schema-"):
            raise RuntimeError("拒绝清理非本任务临时目录")
        temp.cleanup()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    discovered_mysql = shutil.which("mysql")
    parser.add_argument("--mysql-bin", type=Path, default=Path(discovered_mysql).parent if discovered_mysql else None,
                        help="包含 mysqld、mysql、mysqladmin 的 MySQL bin 目录")
    options = parser.parse_args()
    if options.mysql_bin is None:
        parser.error("请用 --mysql-bin 指定 MySQL 安装的 bin 目录")
    verify(options.mysql_bin.resolve())
