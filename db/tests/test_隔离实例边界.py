"""不用真实进程或网络连接，验证隔离执行器在身份未核验时只清理自建进程。"""
from contextlib import redirect_stdout
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("isolated_mysql_guard", Path(__file__).with_name("执行记忆控制隔离验收.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


class OwnedProcess:
    pid = 424242
    returncode = None

    def poll(self):
        return self.returncode

    def wait(self, timeout):
        if self.returncode is None:
            raise AssertionError("未先关闭自己持有的进程")
        return self.returncode

    def kill(self):
        self.returncode = -9


class IsolatedInstanceGuardTest(unittest.TestCase):
    def exercise(self, mode):
        calls = []
        data = None
        process = OwnedProcess()
        previous_env = {"SYNTHETIC_PREVIOUS_ENV": "preserve"}
        with tempfile.TemporaryDirectory(prefix="agentscope-guard-test-") as binary_dir:
            binary_dir = Path(binary_dir)
            for name in ("mysql.exe", "mysqld.exe", "mysqladmin.exe"):
                (binary_dir / name).touch()

            def fake_run(args, *, sql=None, timeout=30):
                nonlocal data
                calls.append((args, sql))
                login_file = Path(base.CLIENT_ENV["MYSQL_TEST_LOGIN_FILE"])
                self.assertFalse(login_file.exists())
                self.assertEqual(login_file.name, "unused-login.cnf")
                output, errors, code = b"", b"", 0
                if "--initialize-insecure" in args:
                    data = Path(next(a.removeprefix("--datadir=") for a in args if a.startswith("--datadir=")))
                    self.assertEqual(login_file.parent, data.parent)
                    if mode == "initialize_failure":
                        code, errors = 1, b"synthetic initialization failure"
                elif args[0] == "taskkill":
                    self.assertEqual(args, ["taskkill", "/PID", str(process.pid), "/T", "/F"])
                    process.returncode = -9
                elif Path(args[0]).name == "mysqladmin.exe":
                    self.assertEqual(mode, "verified")
                    process.returncode = 0
                elif "--execute" in args:
                    statement = args[args.index("--execute") + 1]
                    if statement == "SELECT @@datadir":
                        if mode == "datadir_failure":
                            code, errors = 1, b"synthetic identity query failure"
                        else:
                            output = str(data if mode == "verified" else data.parent / "other-instance").encode()
                    elif statement == "SELECT VERSION()":
                        output = b"8.0.45"
                elif sql and b"information_schema.tables" in sql:
                    output = b"0\n" * 7
                return subprocess.CompletedProcess(args, code, output, errors)

            def fake_popen(args, **kwargs):
                self.assertEqual(Path(next(a.removeprefix("--datadir=") for a in args if a.startswith("--datadir="))), data)
                self.assertIs(kwargs["env"], base.CLIENT_ENV)
                log = Path(next(a.removeprefix("--log-error=") for a in args if a.startswith("--log-error=")))
                log.write_text("synthetic owned process", encoding="utf-8")
                return process

            clock = [0, 46] if mode == "startup_timeout" else [0, 0]
            with mock.patch.object(base, "CLIENT_ENV", previous_env), mock.patch.object(base, "run", side_effect=fake_run), \
                    mock.patch.object(base.subprocess, "Popen", side_effect=fake_popen), \
                    mock.patch.object(base.time, "monotonic", side_effect=clock), redirect_stdout(io.StringIO()):
                options = dict(ddl_files=[], probes=[], expected_table_count=0,
                               inspection_check=lambda _: 0, concurrency_check=lambda _: 0)
                if mode == "verified":
                    self.assertEqual(base.verify(binary_dir, **options)["tables"], 0)
                else:
                    with self.assertRaises(RuntimeError):
                        base.verify(binary_dir, **options)
                self.assertIs(base.CLIENT_ENV, previous_env)
            self.assertIsNotNone(data)
            self.assertFalse(data.parent.exists(), "本轮临时目录未清理")
            shutdowns = [args for args, _ in calls if Path(args[0]).name == "mysqladmin.exe"]
            writes = [sql for _, sql in calls if sql and b"CREATE DATABASE" in sql]
            self.assertEqual(len(shutdowns), int(mode == "verified"))
            self.assertEqual(len(writes), int(mode == "verified"))
            if mode not in ("verified", "initialize_failure"):
                self.assertIsNotNone(process.poll(), "本轮未核验进程未终止")

    def test_mismatched_datadir_never_shuts_down_peer(self):
        self.exercise("mismatch")

    def test_failed_identity_query_never_shuts_down_peer(self):
        self.exercise("datadir_failure")

    def test_startup_timeout_only_stops_owned_process(self):
        self.exercise("startup_timeout")

    def test_initialization_failure_restores_environment(self):
        self.exercise("initialize_failure")

    def test_verified_instance_can_shutdown_and_cleanup(self):
        self.exercise("verified")


if __name__ == "__main__":
    unittest.main()
