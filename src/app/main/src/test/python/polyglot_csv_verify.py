"""CSV queue acceptance, with expectations generated without importing the analyzer."""

import csv
import hashlib
import http.server
import io
import json
from pathlib import Path
import socket
import sqlite3
import threading
import time
import uuid
from urllib.parse import quote
from polyglot_business_verify import check, browser, concurrent


def wait_for(predicate, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(0.04)
    raise AssertionError("condition not reached before deadline")


def csv_inspector(r):
    slug = "csv-inspector"
    r.env["SAMPLE_BATCH_DELAY_MS"] = "20"
    r.start(slug)
    count = 100000 if r.profile == "standard" else 5000
    path = r.evidence / "人员 数据.csv"
    expected = set()
    bad = set()
    counts = dict.fromkeys(
        ["required", "number", "date", "range", "enum", "duplicate"], 0
    )
    with path.open("w", encoding="utf-8", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(["id", "name", "age", "date", "team"])
        seen = set()
        for n in range(1, count + 1):
            identifier = n - 1 if n % 100 == 0 else n
            team = "green" if n % 31 == 0 else "red"
            if n % 100 == 0:
                team = "green" if (n - 1) % 31 == 0 else "red"
            flags = []
            if n % 17 == 0:
                flags.append(("name", "required"))
            if n % 19 == 0:
                flags.append(("age", "number"))
            elif n % 23 == 0:
                flags.append(("age", "range"))
            if n % 29 == 0:
                flags.append(("date", "date"))
            if team == "green":
                flags.append(("team", "enum"))
            if (identifier, team) in seen:
                flags.append(("id,team", "duplicate"))
            seen.add((identifier, team))
            writer.writerow(
                [
                    identifier,
                    "" if n % 17 == 0 else f"人员 {n}",
                    "bad" if n % 19 == 0 else 999 if n % 23 == 0 else 20,
                    "2026-02-30" if n % 29 == 0 else "2026-09-19",
                    team,
                ]
            )
            for column, code in flags:
                expected.add((n + 1, column, code))
                bad.add(n + 1)
                counts[code] += 1
    r.scale = {
        "csvRows": count,
        "expectedIssues": len(expected),
        "competingRequests": 10,
    }

    def upload(path):
        with path.open("rb") as f:
            code, _, raw = r.request(
                "web",
                "/api/datasets?name=" + quote(path.name),
                "POST",
                f,
                {
                    "Content-Type": "text/csv",
                    "Content-Length": str(path.stat().st_size),
                },
            )
        assert code == 200, (code, raw)
        result = json.loads(raw)
        assert result["sha256"] == hashlib.sha256(path.read_bytes()).hexdigest()
        return result

    def create(dataset, template="basic", key=None):
        return r.json(
            "web",
            "/api/jobs",
            "POST",
            {
                "datasetId": dataset,
                "templateId": template,
                "requestId": key or uuid.uuid4().hex,
            },
        )

    def job(id):
        return r.json("web", "/api/jobs/" + id)

    def finished(id, status="completed"):
        value = wait_for(
            lambda: (
                j if (j := job(id))["status"] not in ["queued", "running"] else None
            ),
            90,
        )
        assert value["status"] == status, value
        return value

    def action(id, name, key=None):
        return r.json(
            "web",
            f"/api/jobs/{id}/{name}",
            "POST",
            {"requestId": key or uuid.uuid4().hex},
        )

    def idle_analyzer():
        wait_for(lambda: r.json("analyzer", "/healthz")["activeJobs"] == 0)

    with check(r, "business", "initialized data and full rule explanations"):
        demo = finished(create("demo")["id"])
        assert (
            demo["summary"]["rows"] == 3
            and demo["summary"]["issueCount"] == 6
            and demo["summary"]["validRows"] == 1
        )
        assert set(
            i["code"] for i in r.json("web", f"/api/jobs/{demo['id']}/issues")["items"]
        ) == set(counts)
        invalid = {
            "name": "坏规则",
            "rules": {
                "required": [],
                "types": {},
                "ranges": {"age": {"min": 100, "max": 1}},
                "enums": {},
                "unique": [],
            },
        }
        r.json("web", "/api/templates", "POST", invalid, 400)
        r.json("web", "/api/jobs?limit=0", expected=400)
    with check(
        r,
        "scale",
        f"{count} CSV records streamed, independent issue and summary oracle",
    ):
        data = upload(path)
        big = create(data["id"])
        id = big["id"]
        wait_for(lambda: job(id)["progress_rows"] >= 1000)
        start = time.monotonic()
        assert r.json("web", "/api/templates")["items"]
        assert time.monotonic() - start < 5
        result = finished(id)
        assert result["summary"] == {
            "rows": count,
            "validRows": count - len(bad),
            "issueCount": len(expected),
            "statistics": counts,
            "columns": ["id", "name", "age", "date", "team"],
        }
        actual = set()
        for offset in range(0, len(expected), 100):
            page = r.json("web", f"/api/jobs/{id}/issues?offset={offset}&limit=100")
            assert page["total"] == len(expected)
            actual.update((i["row"], i["column"], i["code"]) for i in page["items"])
        assert actual == expected
        report = r.json("web", f"/api/jobs/{id}/export")
        assert (
            len(report["issues"]) == len(expected)
            and report["summary"] == result["summary"]
        )
    with check(
        r, "business", "report comparison keeps dataset and immutable rule boundaries"
    ):
        fewer = finished(create(data["id"], "required")["id"])
        comparison = r.json("web", f"/api/compare?left={id}&right={fewer['id']}")
        assert (
            comparison["added"] == 0
            and comparison["resolved"] == len(expected) - counts["required"]
        )
        assert comparison["statisticsDelta"]["number"] == -counts["number"]
        r.json("web", f"/api/compare?left={id}&right={demo['id']}", expected=409)
    with check(
        r,
        "recovery",
        "cancel aborts downstream, one explicit concurrent retry retains identity",
    ):
        key = uuid.uuid4().hex
        copies = concurrent(10, lambda _: create(data["id"], key=key))
        assert len({j["id"] for j in copies}) == 1
        cancel_id = copies[0]["id"]
        wait_for(lambda: job(cancel_id)["progress_rows"] >= 1000)
        action(cancel_id, "cancel")
        finished(cancel_id, "cancelled")
        idle_analyzer()
        r.json("web", f"/api/jobs/{cancel_id}/export", expected=409)
        key = uuid.uuid4().hex
        retries = concurrent(10, lambda _: action(cancel_id, "retry", key))
        assert {j["attempt"] for j in retries} == {2}
        retried = finished(cancel_id)
        assert retried["summary"] == result["summary"]
        assert r.json("web", f"/api/jobs/{cancel_id}/issues")["total"] == len(expected)
        assert sum(e["action"] == "retry" for e in retried["events"]) == 1
    with check(
        r,
        "recovery",
        "worker dies during real Python call, explicit retry recovers without duplicate issues",
    ):
        crash = create(data["id"])["id"]
        wait_for(lambda: job(crash)["progress_rows"] >= 1000)
        owned = r.processes["worker"]
        owned.kill_member(owned.process.pid)
        owned.process.wait(5)
        idle_analyzer()  # This must happen before runner cleanup.
        assert job(crash)["status"] == "running"
        r.close()
        r.start(slug)
        interrupted = finished(crash, "interrupted")
        assert interrupted["attempt"] == 1
        action(crash, "retry")
        assert finished(crash)["summary"] == result["summary"]
    for point in ["job-running", "before-report"]:
        with check(
            r,
            "recovery",
            "worker crash at " + point + " does not publish a successful report",
        ):
            r.close()
            gate = r.evidence / point
            r.env.update(SAMPLE_FAULT_POINT=point, SAMPLE_FAULT_DIR=str(gate))
            r.start(slug)
            crash = create("demo")["id"]
            marker = gate / (point + ".ready")
            wait_for(marker.exists)
            owned = r.processes["worker"]
            owned.kill_member(int(marker.read_text()))
            owned.process.wait(5)
            idle_analyzer()
            r.json("web", f"/api/jobs/{crash}/export", expected=409)
            r.close()
            r.env.pop("SAMPLE_FAULT_POINT")
            r.env.pop("SAMPLE_FAULT_DIR")
            r.start(slug)
            finished(crash, "interrupted")
            action(crash, "retry")
            assert finished(crash)["summary"] == demo["summary"]
            assert not list((r.data / "reports").glob("*.tmp"))
    with check(
        r,
        "fault",
        "unavailable, timeout, wrong version, missing field and interrupted NDJSON",
    ):
        r.close()
        r.env["ANALYZER_TIMEOUT_MS"] = "500"
        r.start(slug)
        r.stop("analyzer")
        failed = finished(create("demo")["id"], "failed")
        assert failed["error"]

        class Handler(http.server.BaseHTTPRequestHandler):
            mode = "version"

            def log_message(self, *args):
                pass

            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                if self.mode == "timeout":
                    time.sleep(1)
                if self.mode == "exit":
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                record = {
                    "protocolVersion": 1 if self.mode == "version" else 2,
                    "sequence": 0,
                    "type": "start",
                    "columns": ["id", "name", "age", "date", "team"],
                }
                if self.mode == "missing":
                    record.pop("columns")
                raw = (json.dumps(record) + "\n").encode()
                try:
                    self.send_response(200)
                    self.send_header("X-Sample-Protocol", "2")
                    self.send_header("Content-Length", str(len(raw)))
                    self.end_headers()
                    self.wfile.write(raw)
                except OSError:
                    pass

        server = http.server.ThreadingHTTPServer((r.host, r.ports["analyzer"]), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            for mode in ["version", "missing", "no-end", "timeout", "exit"]:
                Handler.mode = mode
                bad_job = finished(create("demo")["id"], "failed")
                assert bad_job["error"]
                r.json("web", f"/api/jobs/{bad_job['id']}/export", expected=409)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(5)
        r.close()
        r.env.pop("ANALYZER_TIMEOUT_MS")
        r.start(slug)
        action(failed["id"], "retry")
        assert finished(failed["id"])["summary"] == demo["summary"]
    with check(
        r,
        "fault",
        "removing Python analysis module breaks actual service, restore recovers",
    ):
        r.stop("analyzer")
        p = r.work / "success-csv-inspector/analyzer"
        source = p / "analysis.py"
        disabled = p / "analysis.disabled"
        source.rename(disabled)
        try:
            process = r.start_process(
                "broken-analyzer",
                [
                    p
                    / ".venv"
                    / (
                        "Scripts/python.exe"
                        if __import__("os").name == "nt"
                        else "bin/python"
                    ),
                    "server.py",
                ],
                p,
                {"PORT": str(r.ports["analyzer"]), "HOST": r.host},
            )
            assert process.wait(10) != 0
            failed = finished(create("demo")["id"], "failed")
            assert failed["error"]
        finally:
            disabled.rename(source)
        r.close()
        r.start(slug)
        action(failed["id"], "retry")
        finished(failed["id"])
    with check(
        r,
        "browser",
        "upload, template editor, progress, details, history, comparison and errors",
    ):
        browser(r, slug, r.urls["web"])
    with check(
        r,
        "persistence",
        "restart keeps successful reports and independent SQLite counts",
    ):
        r.close()
        r.start(slug)
        assert job(id)["summary"] == result["summary"]
        with sqlite3.connect(r.data / "csv.sqlite") as db:
            assert db.execute(
                "SELECT count(*) FROM issues WHERE job_id=?", (id,)
            ).fetchone()[0] == len(expected)
            assert (
                db.execute(
                    "SELECT count(*) FROM jobs WHERE status='running'"
                ).fetchone()[0]
                == 0
            )
            assert db.execute("PRAGMA foreign_key_check").fetchall() == []
        assert r.json("web", f"/api/jobs/{id}/export")["summary"] == result["summary"]
