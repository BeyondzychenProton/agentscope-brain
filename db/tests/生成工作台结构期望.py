"""Generate the exact current workbench DDL baseline in an owned disposable MySQL only."""
import argparse
import hashlib
import importlib.util
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('login_metadata', Path(__file__).with_name('生成登录结构期望.py'))
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)
DDL = sorted((ROOT / 'db/schema').glob('*.sql'))


def generate(client):
    base = baseline.fixture.base
    native = sorted((ROOT / 'db/vendor').glob('*.sql'))
    for path in native:
        base.require_success(base.run(client, sql=path.read_bytes()), 'native DDL')
    tables = base.require_success(base.run(client, sql=b'SHOW TABLES'), 'metadata tables').splitlines()
    revision = DDL[0].name[:2] + '..' + DDL[-1].name[:2]
    lines = [f'# workbench-structure-v1: exact platform {revision} / AgentScope JDBC 2.0.4 / Spring Session JDBC 4.1.1']
    for path in DDL + native:
        lines.append('# ' + path.relative_to(ROOT).as_posix() + ' sha256=' + hashlib.sha256(path.read_bytes()).hexdigest())
    for table in tables:
        for query in baseline.QUERIES:
            rows = base.require_success(base.run(client, sql=query.replace('?', "'" + table + "'").encode()), 'metadata').splitlines()
            lines.append(table.lower() + '\t' + query + '\t' + '~'.join(rows))
    target = ROOT / 'backend/src/main/resources/readiness/structure-workbench-v1.tsv'
    target.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return {'metadataTables': len(tables), 'baselineSha256': hashlib.sha256(target.read_bytes()).hexdigest()}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-bin', type=Path, default=Path('D:/code environment/mysql/mysql-8.0.45-winx64/bin'))
    args = parser.parse_args()
    count = sum(len(re.findall(r'CREATE TABLE (\w+)', path.read_text('utf-8'))) for path in DDL)
    baseline.fixture.base.verify(args.mysql_bin.resolve(), ddl_files=DDL, probes=[], expected_table_count=count,
        inspection_check=lambda _: 0, concurrency_check=lambda _: 0, extra_checks=generate)
