"""在真实 MySQL 上验收一期身份建表；凭据由 mysql 登录路径提供，不写入仓库。"""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


SQL_FILE = Path(__file__).with_name("01_用户与管理员建表验收.sql")
PATROL_FILE = Path(__file__).with_name("02_身份关联巡检.sql")
DDL_FILE = Path(__file__).parent.parent / "schema" / "01_用户与管理员建表.sql"


def mysql_run(mysql_exe: str, login_path: str, schema: str, *, sql: bytes | None = None, query: str | None = None, force: bool = False) -> subprocess.CompletedProcess[bytes]:
    args = [mysql_exe, f"--login-path={login_path}", "--default-character-set=utf8mb4", f"--database={schema}", "--batch", "--raw", "--skip-column-names"]
    if force:
        args.append("--force")
    if query is not None:
        args.extend(["--execute", query])
    return subprocess.run(args, input=sql, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)


def rows(result: subprocess.CompletedProcess[bytes]) -> list[list[str]]:
    if result.returncode != 0:
        raise RuntimeError("MySQL 查询失败；请检查本地登录路径、schema 和客户端版本。")
    return [line.split("\t") for line in result.stdout.decode("utf-8", errors="replace").replace("\r", "").splitlines() if line]


def check_schema(mysql_exe: str, login_path: str, schema: str) -> int:
    column_rows = rows(mysql_run(mysql_exe, login_path, schema, query=(
        "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,EXTRA,COLUMN_KEY,IS_NULLABLE,COLUMN_COMMENT "
        "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION"
    )))
    table_rows = rows(mysql_run(mysql_exe, login_path, schema, query=(
        "SELECT TABLE_NAME,TABLE_COMMENT FROM INFORMATION_SCHEMA.TABLES "
        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'"
    )))
    if len(table_rows) != 7 or len({item[0] for item in table_rows}) != 7:
        raise AssertionError(f"身份表数量不符：{len(table_rows)}")
    if any(not item[1] for item in table_rows) or any(not item[6] for item in column_rows):
        raise AssertionError("存在缺少中文说明的表或字段")
    by_table: dict[str, dict[str, list[str]]] = {}
    for item in column_rows:
        by_table.setdefault(item[0], {})[item[1]] = item
        if item[1].startswith("is_") and item[2] != "tinyint unsigned":
            raise AssertionError(f"是否字段类型不符合规约：{item[0]}.{item[1]}")
    for table, columns in by_table.items():
        for required in ("id", "gmt_create", "gmt_modified"):
            if required not in columns:
                raise AssertionError(f"缺少必备字段：{table}.{required}")
        pk = columns["id"]
        if pk[2] != "bigint unsigned" or "auto_increment" not in pk[3] or pk[4] != "PRI":
            raise AssertionError(f"id 主键不符合规约：{table}")
        for timestamp in ("gmt_create", "gmt_modified"):
            col = columns[timestamp]
            if not col[2].startswith("datetime") or col[5] != "NO":
                raise AssertionError(f"时间字段不符合规约：{table}.{timestamp}")

    ddl_source = DDL_FILE.read_text(encoding="utf-8")
    index_rows = rows(mysql_run(mysql_exe, login_path, schema, query=(
        "SELECT TABLE_NAME,INDEX_NAME,COLUMN_NAME,SUB_PART FROM INFORMATION_SCHEMA.STATISTICS "
        "WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX"
    )))
    for table, index, column, sub_part in index_rows:
        if index != "PRIMARY" and not index.startswith(("uk_", "idx_")):
            raise AssertionError(f"索引命名不符合规约：{table}.{index}")
        if column in by_table[table] and by_table[table][column][2].startswith("varchar(") and sub_part == "NULL":
            explicit_length = re.search(
                rf"(?:UNIQUE\s+)?KEY\s+{re.escape(index)}\s*\([^\n]*\b{re.escape(column)}\(\d+\)",
                ddl_source,
            )
            if not explicit_length:
                raise AssertionError(f"VARCHAR 索引未声明长度：{table}.{index}")

    constraint_rows = rows(mysql_run(mysql_exe, login_path, schema, query=(
        "SELECT CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
        "WHERE TABLE_SCHEMA=DATABASE() AND CONSTRAINT_TYPE='FOREIGN KEY'"
    )))
    routine_rows = rows(mysql_run(mysql_exe, login_path, schema, query=(
        "SELECT ROUTINE_NAME FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE()"
    )))
    if constraint_rows or routine_rows:
        raise AssertionError("规约禁止的外键或存储过程仍存在")
    return len(column_rows)


def main() -> int:
    parser = argparse.ArgumentParser(description="验收一期用户与管理员数据库结构")
    parser.add_argument("--mysql", default=os.environ.get("MYSQL_CLIENT") or shutil.which("mysql"), help="mysql 客户端路径")
    parser.add_argument("--login-path", default="agentscope-brain", help="mysql_config_editor 登录路径名称")
    parser.add_argument("--schema", default="agentscope_brain_dev", help="专用开发数据库名称")
    options = parser.parse_args()
    if not options.mysql:
        parser.error("未找到 mysql 客户端，请用 --mysql 指定路径")

    column_count = check_schema(options.mysql, options.login_path, options.schema)
    sql = SQL_FILE.read_bytes()
    expected_errors = [int(value) for value in re.findall(rb"EXPECT_ERROR\s+(\d+)", sql)]
    result = mysql_run(options.mysql, options.login_path, options.schema, sql=sql, force=True)
    actual_errors = [int(value) for value in re.findall(rb"ERROR\s+(\d+)", result.stderr)]
    output = result.stdout.decode("utf-8", errors="replace").replace("\r", "")
    if result.returncode != 0 or actual_errors != expected_errors or output.splitlines().count("PASS") != 2 or "FAIL" in output:
        raise AssertionError(
            f"SQL 验收失败：退出码={result.returncode}，预期错误码={expected_errors}，"
            f"实际错误码={actual_errors}，PASS 数量={output.splitlines().count('PASS')}"
        )
    patrol_rows = rows(mysql_run(options.mysql, options.login_path, options.schema, sql=PATROL_FILE.read_bytes()))
    if len(patrol_rows) != 9 or any(len(item) != 2 or item[1] != "0" for item in patrol_rows):
        raise AssertionError(f"身份关联巡检发现异常：{patrol_rows}")
    print(f"PASS：7 张表、{column_count} 个有注释字段、0 外键、0 存储过程；业务唯一约束、事务回滚和 9 项关联巡检通过")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, RuntimeError) as error:
        print(f"FAIL：{error}", file=sys.stderr)
        sys.exit(1)
