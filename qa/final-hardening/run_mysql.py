"""Run one M5 phase on a fresh, owned MySQL datadir, without the customer catalog."""
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HISTORICAL = ROOT / "experiments/matching-engine"
ENGINE = ROOT / "experiments/matching-engine-optimized"
BIN = ROOT / "tools/runtime-dependencies/mysql-8.4.8-winx64/bin"
sys.path[:0] = [str(ROOT / "tools/alpha_runtime"), str(HISTORICAL / "stage5a")]
from owned_job import Job
from persistence_v2 import Lease, own_info, same_process


def main():
    phase, *reuse = sys.argv[1:]
    assert phase in ("root-baseline", "focused", "acceptance", "browser", "timing")
    assert len(reuse) <= (2 if phase == "timing" else 1)
    assert shutil.disk_usage(ROOT).free >= 41 * 1024**3
    runtime = Path(__file__).parent / "runtime"
    run = runtime / f"{phase}-{time.time_ns()}"
    prior = (runtime / reuse[0]).resolve() if reuse else None
    browser_proof = (runtime / reuse[1]).resolve() if len(reuse) == 2 else None
    if prior:
        assert prior.parent == runtime.resolve() and (prior / "DATABASE.json").is_file()
    if browser_proof:
        assert browser_proof.parent == runtime.resolve() and (browser_proof / "BROWSER.json").is_file()
    return owned_phase(run, phase, prior, browser_proof)


def owned_phase(run, phase, prior, browser_proof=None, extension=None, minimum_free_gib=41,
                performance_schema=True, buffer_pool_mib=128, minimum_free_bytes=None):
    if minimum_free_bytes is None:
        assert minimum_free_gib >= 40
        minimum_free_bytes = minimum_free_gib * 1024**3
    assert minimum_free_bytes > 0 and shutil.disk_usage(ROOT).free >= minimum_free_bytes
    lease = Lease("optimized-ui-qa")
    try:
        for name in ("OWNER.json", "UI_OWNER.json"):
            marker = ENGINE / "runtime" / name
            if marker.exists():
                owner = json.loads(marker.read_text())
                assert not any(same_process(owner[k]) for k in ("owner", "server", "app") if owner.get(k))
        assert not subprocess.check_output(
            ["powershell", "-NoProfile", "-Command", "(Get-Process mysqld -ErrorAction SilentlyContinue).Id"]
        ).strip(), "Another MySQL process is running"
        for port in (33368, 8088):
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", port))
        run.mkdir(parents=True)
        return execute(run, phase, prior, browser_proof, extension, performance_schema, buffer_pool_mib)
    finally:
        lease.close()


def execute(run, phase, prior, browser_proof, extension, performance_schema=True, buffer_pool_mib=128):
    database_root = prior or run
    data = database_root / "data"
    private = database_root / "private"
    if prior:
        password, token = (private / "identity").read_text().splitlines()
    else:
        private.mkdir()
        data.mkdir()
        password, token = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        (private / "identity").write_text(password + "\n" + token)
    schema = HISTORICAL / "sql/001_schema.sql"
    fixture = HISTORICAL / "src/test/resources/fixtures.sql"
    server_config = private / "server.cnf"
    server_config.write_text(
        f"[mysqld]\nbasedir={BIN.parent.as_posix()}\ndatadir={data.as_posix()}\n"
        "port=33368\nbind-address=127.0.0.1\nmysqlx=OFF\nskip-log-bin\n"
        f"innodb-buffer-pool-size={buffer_pool_mib * 1024**2}\ninnodb-redo-log-capacity=104857600\n"
        + ("" if performance_schema else "performance-schema=OFF\n"),
        encoding="utf-8",
    )
    client_config = private / "client.cnf"
    client_config.write_text(f"[client]\nhost=127.0.0.1\nport=33368\nuser=root\npassword={password}\n")
    bootstrap = private / "bootstrap.sql"
    bootstrap.write_text(f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{password}';\n")
    defaults = f"--defaults-file={server_config}"
    job = Job()
    server = None
    inputs = fingerprint_inputs()
    (run / "INPUTS.json").write_text(json.dumps(inputs, indent=2))
    print(run, flush=True)
    try:
        if not prior:
            with (run / "initialize.log").open("xb") as log:
                init = subprocess.Popen([str(BIN / "mysqld.exe"), defaults, "--initialize-insecure"],
                                        stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
                job.add(init)
                assert init.wait(timeout=120) == 0
        with (run / "server.log").open("xb") as log:
            init_args = [] if prior else [f"--init-file={bootstrap}"]
            server = subprocess.Popen([str(BIN / "mysqld.exe"), defaults, *init_args, "--console"],
                                      stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        job.add(server)
        (run / "OWNER.json").write_text(json.dumps(
            {"owner": own_info(), "server": own_info(server.pid), "datadir": str(data), "port": 33368}, indent=2))
        deadline = time.monotonic() + 60
        while True:
            try:
                with socket.create_connection(("127.0.0.1", 33368), timeout=1):
                    break
            except OSError:
                assert server.poll() is None and time.monotonic() < deadline
                time.sleep(.5)
        cli = [f"--defaults-file={client_config}", "--get-server-public-key"]

        def sql(statement):
            result = subprocess.run([str(BIN / "mysql.exe"), *cli, "--default-character-set=utf8mb4",
                                     "--batch", "--skip-column-names"], input=statement.encode("utf-8"),
                                    capture_output=True, timeout=90)
            if result.returncode:
                error = result.stderr.decode("utf-8", errors="replace").replace(password, "[redacted]").replace(token, "[redacted]")
                raise RuntimeError(f"Owned fixture SQL failed (exit {result.returncode}): {error}")
            return result.stdout.decode("utf-8").strip()

        ddl, rows = (p.read_text(encoding="utf-8") for p in (schema, fixture))
        insert = ddl + "\n" + rows
        if not prior:
            sql("CREATE DATABASE matching_engine_exp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
            "CREATE DATABASE alpha_matcher_ui_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
            + insert + "\n" + insert.replace("matching_engine_exp", "alpha_matcher_ui_demo") + f"""
            CREATE TABLE alpha_matcher_ui_demo.ui_demo_owner (owner_token VARCHAR(128) NOT NULL);
            INSERT INTO alpha_matcher_ui_demo.ui_demo_owner VALUES ('{token}');
            CREATE TABLE alpha_matcher_ui_demo.ui_demo_state (id INT PRIMARY KEY, employee_snapshot MEDIUMBLOB NULL);
            INSERT INTO alpha_matcher_ui_demo.ui_demo_state (id) VALUES (1);
            CREATE USER 'matcher'@'127.0.0.1' IDENTIFIED BY '{password}';
            GRANT ALL ON matching_engine_exp.* TO 'matcher'@'127.0.0.1';
            GRANT SELECT ON performance_schema.* TO 'matcher'@'127.0.0.1';
            CREATE USER 'ui_demo'@'127.0.0.1' IDENTIFIED BY '{password}';
            GRANT SELECT, UPDATE ON alpha_matcher_ui_demo.* TO 'ui_demo'@'127.0.0.1';
        """)
        identity = sql("SELECT @@server_uuid, VERSION(), @@datadir").split("\t")
        assert Path(identity[2]).resolve() == data.resolve()
        fingerprint = {
            "uuid": identity[0], "version": identity[1], "datadir": str(data),
            "schemaSHA256": hashlib.sha256(schema.read_bytes()).hexdigest(),
            "fixtureSHA256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
        }
        if prior:
            assert fingerprint == json.loads((prior / "DATABASE.json").read_text())
        (run / "DATABASE.json").write_text(json.dumps(fingerprint, indent=2))
        jdbc = "jdbc:mysql://127.0.0.1:33368/"
        opts = "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=20000"
        env = dict(os.environ, MATCHING_DB_URL=jdbc + "matching_engine_exp" + opts,
                   MATCHING_DB_USER="matcher", MATCHING_DB_PASSWORD=password,
                   ALPHA_DEMO_OWNER=token, ALPHA_DEMO_PASSWORD=password,
                   MAVEN_OPTS="-Xmx768m -XX:ActiveProcessorCount=4")
        commands = {"root-baseline": [
            ("app", ["-Dtest=JdbcEngineApplicationTest#changedAmountRejectsOldPreview+staleServiceCannotOverwriteCommittedAudit", "test"])
        ], "focused": [
            ("engine", ["-f", str(ENGINE / "pom.xml"), "-Pmysql-it", "-DskipTests=false",
                        "-Dtest=BindingContextTest", "-Dit.test=M5FinancialOracleMySqlIT,MentionContextsMySqlIT",
                        "verify", "install"]),
            ("app", ["-Dtest=JdbcEngineApplicationTest,ManagedMatchingSessionTest", "test"]),
        ], "acceptance": [
            ("engine", ["-f", str(ENGINE / "pom.xml"), "-Pmysql-it", "install"]),
            ("app", ["package"]),
        ], "browser": [], "timing": []}
        results = {}
        for name, args in (commands[phase] if extension is None else []):
            command = [str(ROOT / "mvnw.cmd"), *args]
            with (run / f"{name}.log").open("xb") as log:
                results[name] = subprocess.run(command, cwd=ROOT, env=env, stdout=log,
                                               stderr=subprocess.STDOUT, timeout=600).returncode
            print(name, results[name], flush=True)
            if results[name] != 0:
                break
        if phase == "acceptance" and results == {"engine": 0, "app": 0}:
            verify_artifacts(run, env)
        if extension is None and phase in ("acceptance", "browser") and all(code == 0 for code in results.values()):
            verify_browser(run, env, sql, job)
            results["browser"] = 0
        if phase == "timing":
            if browser_proof:
                verify_previous_browser(browser_proof, run, sql)
            timing(run, env)
            results["timing"] = 0
        if extension is not None:
            results = extension(run, env, sql, job)
        assert inputs == fingerprint_inputs(), "Inputs or customer table files changed during the run"
        (run / "RESULT.json").write_text(json.dumps(results))
        return max(results.values(), default=0)
    finally:
        try:
            if server is not None and server.poll() is None:
                subprocess.run([str(BIN / "mysqladmin.exe"), f"--defaults-file={client_config}",
                                "--get-server-public-key", "shutdown"], capture_output=True, timeout=40)
                server.wait(timeout=40)
        finally:
            job.close()


def fingerprint_inputs():
    paths = [ROOT / "pom.xml", ENGINE / "pom.xml"]
    paths += [f for tree in (ROOT / "src", ENGINE / "src") for f in tree.rglob("*")
              if f.is_file() and not generated_frontend(f.relative_to(ROOT).as_posix())]
    files = {f.relative_to(ROOT).as_posix(): hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(paths)}
    customer = ENGINE / "runtime/data/alpha_matcher_ui_demo"
    return {
        "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "files": files,
        "sourceSHA256": hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(),
        "customerTablesSHA256": {name: hashlib.sha256((customer / name).read_bytes()).hexdigest()
                                 for name in ("operation.ibd", "ui_demo_state.ibd") if (customer / name).is_file()},
    }


def timing(run, env):
    java = Path(shutil.which("java.exe"))
    jars = [ENGINE / "target" / f"matching-engine-optimized-{v}.jar" for v in ("1.2.0-context1", "1.2.1-m5")]
    connector = Path.home() / ".m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar"
    source = Path(__file__).parent / "TimingMain.java"
    classes = run / "classes"
    classes.mkdir()
    subprocess.run([str(java.with_name("javac.exe")), "-encoding", "UTF-8", "-cp", str(jars[-1]),
                    "-d", str(classes), str(source)], check=True, timeout=30)
    for index in (0, 1, 1, 0):
        jar = jars[index]
        with (run / "timing.log").open("ab") as evidence:
            evidence.write(f"\n{jar.name} SHA256={hashlib.sha256(jar.read_bytes()).hexdigest()}\n".encode())
            evidence.flush()
            subprocess.run([str(java), "-Xms128m", "-Xmx512m", "-XX:ActiveProcessorCount=4", "-cp",
                            os.pathsep.join(map(str, (classes, jar, connector))),
                            "ru.urfu.alphamatcher.experiment.TimingMain"],
                           env=env, stdout=evidence, stderr=subprocess.STDOUT, check=True, timeout=30)


def verify_artifacts(run, env):
    qa = ROOT / "qa/parser-context-20261113"
    target = ENGINE / "target"
    jars = {label: target / f"matching-engine-optimized-{version}.jar" for label, version in
            (("historical", "1.1.0-parser1"), ("context1", "1.2.0-context1"), ("m5", "1.2.1-m5"))}
    app = ROOT / "target/alpha-matcher-1.0-SNAPSHOT.jar"
    app_bytes = app.read_bytes()
    with zipfile.ZipFile(BytesIO(app_bytes)) as archive:
        entries = [n for n in archive.namelist() if n.startswith("BOOT-INF/lib/matching-engine")]
        assert entries == [f"BOOT-INF/lib/{jars['m5'].name}"]
        assert archive.read(entries[0]) == jars["m5"].read_bytes()
        assert not any(n.startswith("BOOT-INF/classes/ru/urfu/alphamatcher/experiment/") for n in archive.namelist())
    java = Path(shutil.which("java.exe"))
    connector = Path.home() / ".m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar"
    classes = run / "proof-classes"
    classes.mkdir()
    source = qa / "ContextDecisionMain.java"
    subprocess.run([str(java.with_name("javac.exe")), "-encoding", "UTF-8", "-cp", str(jars["m5"]),
                    "-d", str(classes), str(source)], check=True, timeout=60)
    decisions = {}
    for label, jar in jars.items():
        with (run / f"{label}-decisions.jsonl").open("xb") as evidence:
            subprocess.run([str(java), "-Dstdout.encoding=UTF-8", f"-Dproof.jar={jar}", "-cp",
                            os.pathsep.join(map(str, (classes, jar, connector))),
                            "ru.urfu.alphamatcher.experiment.ContextDecisionMain", str(qa / "runtime")],
                           env=env, stdout=evidence, check=True, timeout=80)
        cases = (run / f"{label}-decisions.jsonl").read_text(encoding="utf-8").splitlines()
        assert len(cases) == 51
        decisions[label] = {d["id"]: d for d in map(json.loads, cases)}
    current = decisions["m5"]
    diffs = [case for case, actual in current.items() if actual != decisions["context1"][case]]
    for i in range(1, 21):
        key = f"BASE-{i}"
        assert current[key]["fullDecision"] == decisions["historical"][key]["fullDecision"]
    for key in ("RISK-0", "RISK-1", "RISK-2", "SAME-NAME-SWAP"):
        old, actual = decisions["historical"][key], current[key]
        assert old["status"] == "SAFE" and actual["status"] == "REVIEW"
        assert actual["candidateIds"] == [101] and actual["proposedContractId"] is None
        gate = "ROLE_CLAIMS" if key == "SAME-NAME-SWAP" else "INPUT_VALID"
        assert any(c["code"] == gate and not c["passed"] for c in actual["checks"])
    assert not diffs, diffs
    (run / "ARTIFACTS.json").write_text(json.dumps({
        "sha256": {label: hashlib.sha256(jar.read_bytes()).hexdigest() for label, jar in jars.items()},
        "springBootSha256": hashlib.sha256(app_bytes).hexdigest(),
        "proofSourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "manualInputSha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                              for p in (qa / "runtime").glob("case-*.txt")},
        "previousAcceptedFullDecisionsEqual": 20, "context1CasesEqual": 51,
        "exactHistoricalFalseSafeFixed": 4, "embeddedJarMatches": True,
    }, indent=2))


def verify_browser(run, env, sql, job):
    cases = json.loads((Path(__file__).parent / "SCENARIO_CATALOG.json").read_text(encoding="utf-8"))
    for row in cases["browserNew"]:
        # The sole runtime catalog here is disposable. Bind the UTF-8 fixture as a hex literal.
        values = "(" + str(row["id"]) + ",'M5-" + str(row["id"]) + "'," + (
            "'2026-10-02 10:00:00',_utf8mb4 0x" + row["purpose"].encode().hex() + ",1)"
        )
        sql("USE alpha_matcher_ui_demo;"
            "INSERT INTO operation(id,reference,operation_at,payment_purpose,status_id) VALUES " + values +
            " ON DUPLICATE KEY UPDATE payment_purpose=VALUES(payment_purpose);"
            "INSERT IGNORE INTO operation_party(operation_id,role_id,client_id,client_type_id,inn,name) "
            f"SELECT {row['id']},role_id,client_id,client_type_id,inn,name FROM operation_party WHERE operation_id=1;")
    # The UI includes the exact names for the fourth historical contrast.
    sql("UPDATE alpha_matcher_ui_demo.client SET name='ООО Вектор' WHERE id IN (1,2)")
    java = shutil.which("java.exe")
    with (run / "browser-app.log").open("xb") as log:
        app = subprocess.Popen([java, "-Xmx768m", "-Xlog:class+load=info:file=qa/final-hardening/runtime/"
                                + run.name + "/class-origins.log", "-jar",
                                str(ROOT / "target/alpha-matcher-1.0-SNAPSHOT.jar"),
                                "--spring.profiles.active=jdbc-demo", "--server.port=8088",
                                "--vaadin.launch-browser=false"], cwd=ROOT, env=env, stdout=log,
                               stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
    job.add(app)
    try:
        import urllib.request
        deadline = time.monotonic() + 80
        while True:
            try:
                with urllib.request.urlopen("http://127.0.0.1:8088/login", timeout=1) as response:
                    assert response.status == 200
                    break
            except OSError:
                assert app.poll() is None and time.monotonic() < deadline
                time.sleep(.5)
        before = links(sql)
        (run / "BROWSER_LINKS_BEFORE.json").write_text(json.dumps(before))
        with (run / "browser.log").open("xb") as log:
            subprocess.run(["node", str(Path(__file__).parent / "browser.cjs"), ".", str(run)],
                           cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=240)
        after = links(sql)
        assert before[0] == ["1", "NULL"] and after == [["1", "101"], *before[1:]]
        (run / "BROWSER_DATABASE.json").write_text(json.dumps({
            "onlySyntheticExp001Changed": True, "linksAfter": after, "origins": class_origins(run)
        }, indent=2))
    finally:
        if app.poll() is None:
            app.terminate()
            app.wait(timeout=30)


def links(sql):
    return [line.split("\t") for line in sql(
        "SELECT id,IFNULL(CAST(contract_id AS CHAR),'NULL') FROM alpha_matcher_ui_demo.operation ORDER BY id"
    ).splitlines()]


def class_origins(run):
    origins = (run / "class-origins.log").read_text(encoding="utf-8").splitlines()
    checked = {}
    for name in ("MatchingEngine", "PurposeParser", "JdbcMatchingRepository"):
        found = [line for line in origins if f".experiment.{name} source:" in line]
        assert len(found) == 1 and "BOOT-INF/lib/matching-engine-optimized-1.2.1-m5.jar" in found[0], found
        checked[name] = found[0]
    return checked


def generated_frontend(relative):
    return relative.startswith("src/main/frontend/generated/")


def verify_previous_browser(prior, run, sql):
    browser = json.loads((prior / "BROWSER.json").read_text(encoding="utf-8"))
    assert len(browser["results"]) == 32 and browser["auditCount"] == 1
    data = links(sql)
    assert [int(row[0]) for row in data] == [*range(1, 21), *range(301, 309)]
    assert [row for row in data if row[1] != "NULL"] == [["1", "101"], ["10", "101"]]
    assert int(sql("SELECT OCTET_LENGTH(employee_snapshot) FROM alpha_matcher_ui_demo.ui_demo_state WHERE id=1")) > 0
    before, current = (json.loads((p / "INPUTS.json").read_text(encoding="utf-8")) for p in (prior, run))
    assert before["head"] == current["head"]
    assert before["customerTablesSHA256"] == current["customerTablesSHA256"]
    assert {name: sha for name, sha in before["files"].items() if not generated_frontend(name)} == current["files"]
    (run / "BROWSER_DATABASE.json").write_text(json.dumps({
        "browserRun": prior.name, "linksAfter": data, "onlyExp001DiffersFromFixture": True,
        "customerSHA256Unchanged": True, "origins": class_origins(prior)
    }, indent=2))


if __name__ == "__main__":
    sys.exit(main())
