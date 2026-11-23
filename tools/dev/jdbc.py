"""Run developer JDBC modes on a new owned MySQL datadir (Windows)."""

import argparse
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]

ENGINE_POM = "experiments/matching-engine-optimized/pom.xml"
MIGRATIONS = ROOT / "src/main/resources/db/migration"
SMOKE = ROOT / "qa/theme-parser-20261113/flyway_smoke.py"


def provision_bulk(sql):
    for version in range(2, 8):
        migration, = MIGRATIONS.glob(f"V{version}__*.sql")
        sql("USE alpha_matcher_ui_demo;\n" + migration.read_text(encoding="utf-8"))
    sql("""USE alpha_matcher_ui_demo;
        INSERT INTO bulk_executor_permission VALUES
            ('a.rozhkov@bank.internal', TRUE), ('s.isaev@bank.internal', TRUE);
        GRANT INSERT ON alpha_matcher_ui_demo.bulk_preview_job TO 'ui_demo'@'127.0.0.1';
        GRANT INSERT ON alpha_matcher_ui_demo.bulk_preview_row TO 'ui_demo'@'127.0.0.1';
        GRANT INSERT ON alpha_matcher_ui_demo.bulk_execution TO 'ui_demo'@'127.0.0.1';
        GRANT INSERT ON alpha_matcher_ui_demo.bulk_execution_result TO 'ui_demo'@'127.0.0.1';
        GRANT INSERT, DELETE ON alpha_matcher_ui_demo.registry_gram TO 'ui_demo'@'127.0.0.1';
        """)


def seed_bulk_cases(sql):
    # Keep the M65 fixture shape expected by the existing bulk integration tests.
    sql("""USE alpha_matcher_ui_demo;
        INSERT INTO operation(id, reference, operation_at, amount_rub, payment_purpose, status_id, contract_id)
        WITH RECURSIVE n AS (SELECT 21 i UNION ALL SELECT i + 1 FROM n WHERE i < 140)
        SELECT 20000 + n.i, CONCAT('M65-', LPAD(n.i, 5, '0')),
               DATE_ADD('2026-10-02 00:00:00', INTERVAL MOD(n.i, 9) DAY),
               CASE WHEN MOD(n.i, 31) = 0 THEN NULL ELSE (n.i - 1000) / 17 END,
               CASE WHEN MOD(n.i, 37) = 0 THEN REPEAT('А', 515)
                    WHEN MOD(n.i, 47) = 0 THEN NULL ELSE o.payment_purpose END,
               o.status_id, o.contract_id
          FROM n JOIN operation o ON o.id = 1 + MOD(n.i, 20);
        INSERT INTO operation_party(operation_id, role_id, client_id, client_type_id, inn, name)
        SELECT o.id, p.role_id, p.client_id, p.client_type_id, p.inn, p.name
          FROM operation o JOIN operation_party p ON p.operation_id = 1 + MOD(o.id - 20000, 20)
         WHERE o.id BETWEEN 20021 AND 20140;
        """)
    if sql("SELECT COUNT(*) FROM alpha_matcher_ui_demo.operation") != "140":
        raise RuntimeError("Bulk fixture must contain exactly 140 operations")


def run_maven(args, env, job):
    process = subprocess.Popen([str(ROOT / "mvnw.cmd"), *args], cwd=ROOT, env=env)
    job.add(process)
    return process.wait()


def exercise(mode, env, sql, job):
    if mode == "engine-it":
        return run_maven(["-f", ENGINE_POM, "-Pmysql-it", "verify"], env, job)
    if mode == "app-it":
        env.update(ALPHA_BR_DISPOSABLE="true", M51_QA_DISPOSABLE="true")
        tests = ("JdbcEngineApplicationTest,RawPartySafeIntegrationTest,"
                 "JdbcRecoveryIntegrationTest,BusinessRequirementsProbeTest")
        return run_maven([f"-Dtest={tests}", "test"], env, job)
    provision_bulk(sql)
    if mode == "bulk-it":
        seed_bulk_cases(sql)
        env.update(M7_BULK_QA="true", M7_EXECUTION_QA="true")
        tests = "JdbcBulkPreviewTest,JdbcBulkExecutionTest,JdbcRegistryIndexTest"
        return run_maven([f"-Dtest={tests}", "test"], env, job)
    if mode == "correction-it":
        env["ALPHA_LINK_CORRECTION_QA"] = "true"
        return run_maven(["-Dtest=JdbcPackageCorrectionTest", "test"], env, job)
    from demo_data import data_sql

    sql("USE alpha_matcher_ui_demo;\n" + data_sql())
    return run_maven([
        "-Dspring-boot.run.profiles=jdbc-demo",
        "-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8088 "
        "--alpha.demo.startup-fixture=true",
        "spring-boot:run",
    ], env, job)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("demo", "app-it", "bulk-it", "correction-it", "engine-it", "flyway-smoke"))
    mode = parser.parse_args().mode
    if os.name != "nt":
        parser.error("The owned local MySQL runner requires Windows")
    sys.path.insert(0, str(ROOT / "qa/final-hardening"))
    from run_mysql import BIN, owned_phase

    if not all((BIN / name).is_file() for name in ("mysqld.exe", "mysql.exe", "mysqladmin.exe")):
        parser.error("Install MySQL 8.4.8 for Windows in tools/runtime-dependencies/mysql-8.4.8-winx64")
    if mode == "flyway-smoke":
        (SMOKE.parent / "runtime").mkdir(exist_ok=True)
        return subprocess.call([sys.executable, "-X", "utf8", "-B", str(SMOKE)], cwd=ROOT)
    run = Path(__file__).resolve().parent / "runtime" / f"{mode}-{time.time_ns()}"

    def extension(_run, env, sql, job):
        try:
            return {mode: exercise(mode, env, sql, job)}
        except KeyboardInterrupt:
            return {mode: 130}

    return owned_phase(run, mode, prior=None, extension=extension)


if __name__ == "__main__":
    raise SystemExit(main())
