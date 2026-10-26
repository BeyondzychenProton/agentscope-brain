"""Generate #34's immutable metadata expectation on a newly owned loopback MySQL instance.

Never reads shared configuration. Platform 01..06 is the login baseline; later 07..10
remain independent slices. Vendor structures are obtained from the pinned source/JAR.
"""
import argparse
import hashlib
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('login_baseline_fixture', Path(__file__).with_name('执行初始化隔离验收.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

QUERIES = [
    "SELECT CONCAT(LOWER(column_name),'|',column_type,'|',is_nullable,'|',COALESCE(column_default,'<NULL>'),'|',extra,'|',COALESCE(collation_name,''),'|',generation_expression) FROM information_schema.columns WHERE table_schema=DATABASE() AND LOWER(table_name)=? ORDER BY ordinal_position",
    "SELECT CONCAT(LOWER(index_name),'|',seq_in_index,'|',LOWER(column_name),'|',non_unique,'|',COALESCE(sub_part,0)) FROM information_schema.statistics WHERE table_schema=DATABASE() AND LOWER(table_name)=? ORDER BY LOWER(index_name),seq_in_index",
    "SELECT CONCAT(LOWER(constraint_name),'|',constraint_type) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND LOWER(table_name)=? ORDER BY LOWER(constraint_name)",
    "SELECT CONCAT(LOWER(tc.constraint_name),'|',REPLACE(REPLACE(cc.check_clause,CHAR(10),''),CHAR(9),'')) FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name WHERE tc.constraint_schema=DATABASE() AND LOWER(tc.table_name)=? ORDER BY LOWER(tc.constraint_name)",
    "SELECT CONCAT(engine,'|',table_collation,'|',row_format) FROM information_schema.tables WHERE table_schema=DATABASE() AND LOWER(table_name)=?",
    "SELECT CONCAT(LOWER(k.constraint_name),'|',k.ordinal_position,'|',LOWER(k.column_name),'|',LOWER(k.referenced_table_name),'|',LOWER(k.referenced_column_name),'|',r.update_rule,'|',r.delete_rule) FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name WHERE k.constraint_schema=DATABASE() AND LOWER(k.table_name)=? ORDER BY LOWER(k.constraint_name),k.ordinal_position",
]


def generate(client):
    native = sorted((ROOT / 'db/vendor').glob('*.sql'))
    for path in native:
        fixture.base.require_success(fixture.base.run(client, sql=path.read_bytes()), 'native DDL')
    tables = fixture.base.require_success(fixture.base.run(client, sql=b'SHOW TABLES'), 'metadata tables').splitlines()
    lines = ['# login-structure-v1: platform 01..06 / AgentScope JDBC 2.0.4 / Spring Session JDBC 4.1.1']
    for path in fixture.DDL + native:
        lines.append('# ' + path.relative_to(ROOT).as_posix() + ' sha256=' + hashlib.sha256(path.read_bytes()).hexdigest())
    for table in tables:
        for query in QUERIES:
            raw = fixture.base.require_success(fixture.base.run(client, sql=query.replace('?', "'" + table + "'").encode()), 'metadata')
            rows = raw.splitlines()
            lines.append(table.lower() + '\t' + query + '\t' + '~'.join(rows))
    destination = ROOT / 'backend/src/main/resources/readiness/structure-v1.tsv'
    destination.parent.mkdir(exist_ok=True)
    destination.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return {'metadataTables': len(tables)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-bin', type=Path, default=Path('D:/code environment/mysql/mysql-8.0.45-winx64/bin'))
    args = parser.parse_args()
    fixture.base.verify(args.mysql_bin.resolve(), ddl_files=fixture.DDL, probes=[], expected_table_count=30,
        inspection_check=lambda _: 0, concurrency_check=lambda _: 0, extra_checks=generate)


if __name__ == '__main__':
    main()
