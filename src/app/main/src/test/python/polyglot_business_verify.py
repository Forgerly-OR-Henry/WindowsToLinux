"""Independent business oracles and fault checks for the medium fixtures."""

from concurrent.futures import ThreadPoolExecutor
import contextlib
import hashlib
import json
import os
from pathlib import Path
import random
import socket
import sqlite3
import time
import uuid
import http.server
import threading
from polyglot_runner import ROOT


@contextlib.contextmanager
def check(r, category, name):
    result = {"category": category, "name": name, "status": "running"}
    r.checks.append(result)
    start = time.monotonic()
    try:
        yield
        result["status"] = "passed"
    except BaseException as error:
        result.update(status="failed", error=str(error))
        raise
    finally:
        result["seconds"] = round(time.monotonic() - start, 3)


def browser(r, slug, url):
    r.command(
        r.tool("node")
        + [
            ROOT / "src/app/main/src/test/browser/verify-business.mjs",
            slug,
            url,
            r.evidence / "browser",
        ],
        ROOT,
        timeout=180,
    )


def concurrent(count, fn):
    with ThreadPoolExecutor(max_workers=count) as pool:
        return list(pool.map(fn, range(count)))


def json_request(r, path, data, expected=200):
    return r.json("web", path, "POST", data, expected)


def gateway_faults(r, slug, path):
    r.close()
    r.env["API_TIMEOUT_MS"] = "250"
    r.start(slug)
    r.stop("backend")

    class Handler(http.server.BaseHTTPRequestHandler):
        mode = "version"

        def log_message(self, *args):
            pass

        def do_GET(self):
            if self.mode == "exit":
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            if self.mode == "timeout":
                time.sleep(0.6)
            body = b"{}"
            try:
                self.send_response(200)
                self.send_header(
                    "X-Sample-Protocol", "1" if self.mode == "version" else "2"
                )
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

    server = http.server.ThreadingHTTPServer((r.host, r.ports["backend"]), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        for mode in ["version", "missing-fields", "timeout", "exit"]:
            Handler.mode = mode
            result = r.json("web", path, expected=502)
            assert result["error"]
    finally:
        server.shutdown()
        server.server_close()
        thread.join(5)
        assert not thread.is_alive()
    r.close()
    r.env.pop("API_TIMEOUT_MS")
    r.start(slug)
    r.json("web", path)


def fault_crash(r, slug, point, invoke):
    r.close()
    gate = r.evidence / point
    r.env.update(SAMPLE_FAULT_POINT=point, SAMPLE_FAULT_DIR=str(gate))
    r.start(slug)
    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(invoke)
        deadline = time.monotonic() + 20
        while not (gate / (point + ".ready")).exists():
            if future.done():
                future.result()
                raise AssertionError("operation ended before fault boundary")
            assert time.monotonic() < deadline, "fault marker not reached"
            time.sleep(0.02)
        owned = r.processes["backend"]
        process = owned.process
        owned.kill_member(int((gate / (point + ".ready")).read_text()))
        process.wait(timeout=5)
        assert process.poll() is not None
        try:
            future.result(timeout=10)
        except (OSError, AssertionError):
            pass
        else:
            raise AssertionError("interrupted operation reported success")
    r.close()
    r.env.pop("SAMPLE_FAULT_POINT")
    r.env.pop("SAMPLE_FAULT_DIR")
    r.start(slug)


def file_transfer(r):
    r.start("file-transfer")
    size = 64 * 1024 * 1024 if r.profile == "standard" else 2 * 1024 * 1024
    block = random.Random(r.seed).randbytes(1024 * 1024)
    # A known repeated block keeps generation bounded; compute the expected digest independently.
    digest = hashlib.sha256()
    for _ in range(size // len(block)):
        digest.update(block)
    r.scale = {"fileBytes": size, "chunkBytes": len(block), "competingRequests": 10}

    def create(name="验收 文件.bin", folder=1, size=size, sha=digest.hexdigest()):
        return json_request(
            r,
            "/api/uploads",
            {
                "folderId": folder,
                "name": name,
                "size": size,
                "sha256": sha,
                "chunkSize": len(block),
            },
        )

    def put(upload, n, body=block, status=200):
        code, _, raw = r.request(
            "web",
            f"/api/uploads/{upload['id']}/chunks/{n}",
            "PUT",
            body,
            {"Content-Type": "application/octet-stream"},
        )
        assert code == status, (code, raw)
        return json.loads(raw)

    def complete(upload):
        return json_request(r, f"/api/uploads/{upload['id']}/complete", {})

    def fetch(upload):
        return r.json("web", f"/api/uploads/{upload['id']}")

    with check(
        r, "business", "folder boundaries, chunk conflicts and incomplete download"
    ):
        upload = create()
        put(upload, 0)
        assert put(upload, 0)["duplicate"] is True
        put(upload, 0, b"x" * len(block), 409)
        json_request(r, f"/api/uploads/{upload['id']}/complete", {}, 409)
        assert r.request("web", f"/api/versions/{upload['id']}/download")[0] == 404
        assert len(fetch(upload)["chunks"]) == 1
        assert r.json("web", "/api/files?folderId=1")["total"] == 0
        r.json("web", "/api/files?folderId=1&limit=0", expected=400)
    with check(r, "recovery", "restart resumes confirmed chunks"):
        r.close()
        r.start("file-transfer")
        assert len(fetch(upload)["chunks"]) == 1
        for n in range(1, size // len(block)):
            put(upload, n)
    with check(
        r, "scale", f"10 concurrent completions publish exactly one {size} byte version"
    ):
        results = concurrent(10, lambda _: complete(upload))
        assert len({v["id"] for v in results}) == 1
        version = results[0]
        file_id = version["fileId"]
        assert len(r.json("web", f"/api/files/{file_id}/versions")) == 1
        code, headers, data = r.request(
            "web", f"/api/versions/{version['id']}/download"
        )
        assert (
            code == 200
            and len(data) == size
            and hashlib.sha256(data).hexdigest()
            == digest.hexdigest()
            == headers["x-content-sha256"]
        )
        del data
    with check(r, "business", "historical versions and folder isolation"):
        second = create(size=len(block), sha=hashlib.sha256(block).hexdigest())
        put(second, 0)
        v2 = complete(second)
        assert v2["fileId"] == file_id and v2["number"] == 2
        other = create(folder=2, size=0, sha=hashlib.sha256(b"").hexdigest())
        vo = complete(other)
        assert vo["fileId"] != file_id and vo["number"] == 1
        assert r.json("web", "/api/files?folderId=1")["items"][0]["version"] == 2
        assert r.json("web", "/api/files?folderId=2")["total"] == 1
        assert (
            hashlib.sha256(
                r.request("web", f"/api/versions/{version['id']}/download")[2]
            ).hexdigest()
            == digest.hexdigest()
        )
    with check(r, "business", "cancel, invalid digest, aborted stream and recovery"):
        cancelled = create(size=len(block))
        json_request(r, f"/api/uploads/{cancelled['id']}/cancel", {})
        put(cancelled, 0, status=409)
        bad = create(size=len(block), sha="0" * 64)
        put(bad, 0)
        json_request(r, f"/api/uploads/{bad['id']}/complete", {}, 422)
        assert fetch(bad)["state"] == "failed"
        interrupted = create(size=len(block), sha=hashlib.sha256(block).hexdigest())
        with socket.create_connection((r.host, r.ports["backend"])) as sock:
            sock.sendall(
                f"PUT /api/uploads/{interrupted['id']}/chunks/0 HTTP/1.1\r\nHost: localhost\r\nContent-Length: {len(block)}\r\n\r\n".encode()
                + b"partial"
            )
        deadline = time.monotonic() + 5
        while (
            list((r.data / "staging").glob("chunk-*")) and time.monotonic() < deadline
        ):
            time.sleep(0.05)
        assert not fetch(interrupted)["chunks"]
        put(interrupted, 0)
        complete(interrupted)
    with check(
        r, "fault", "crash after chunk fsync and before metadata acknowledgement"
    ):
        target = create(
            name="分块落盘.bin", size=len(block), sha=hashlib.sha256(block).hexdigest()
        )
        fault_crash(r, "file-transfer", "chunk-written", lambda: put(target, 0))
        assert fetch(target)["chunks"] == []
        put(target, 0)
        complete(target)
    with check(r, "fault", "crash before publication leaves no visible version"):
        target = create(
            name="发布恢复.bin", size=len(block), sha=hashlib.sha256(block).hexdigest()
        )
        put(target, 0)
        fault_crash(r, "file-transfer", "before-publish", lambda: complete(target))
        assert fetch(target)["state"] == "receiving"
        assert (
            r.json(
                "web",
                "/api/files?folderId=1&q="
                + __import__("urllib.parse", fromlist=["quote"]).quote("发布恢复"),
            )["total"]
            == 0
        )
        complete(target)
        assert not list((r.data / "staging").iterdir())
    with check(
        r, "browser", "folder, upload, pause/resume, versions, download and history"
    ):
        browser(r, "file-transfer", r.urls["web"])
    with check(
        r,
        "fault",
        "Go backend missing fails Node business request; restart preserves versions",
    ):
        before = r.json("web", "/api/files?folderId=1")
        r.stop("backend")
        r.json("web", "/api/files?folderId=1", expected=502)
        r.close()
        r.start("file-transfer")
        assert r.json("web", "/api/files?folderId=1") == before
    with check(
        r,
        "fault",
        "wrong protocol, missing fields, timeout and abnormal downstream close",
    ):
        gateway_faults(r, "file-transfer", "/api/files?folderId=1")


def asset_lending(r):
    r.start("asset-lending")
    count = 10000 if r.profile == "standard" else 50
    r.scale = {"assetRecords": count + 3, "competingRequests": 10}

    def action(id, op, expected=200, **values):
        return json_request(
            r,
            f"/api/loans/{id}/{op}",
            {"requestId": uuid.uuid4().hex, "actor": "陈老师", **values},
            expected,
        )

    def loan(ids, borrower="林同学", due="2020-01-01", key=None):
        return json_request(
            r,
            "/api/loans",
            {
                "assetIds": ids,
                "borrower": borrower,
                "dueDate": due,
                "purpose": "验收借用",
                "actor": "林同学",
                "requestId": key or uuid.uuid4().hex,
            },
        )

    def asset(id):
        return r.json("web", f"/api/assets/{id}/history")["asset"]

    with check(
        r,
        "scale",
        f"{count} actual asset registrations; database paging, categories and statistics",
    ):

        def create(i):
            return json_request(
                r,
                "/api/assets",
                {
                    "name": f"验收资产 {i}",
                    "categoryId": 1 + i % 2,
                    "serial": f"ACCEPT-{r.seed}-{i:05}",
                },
            )

        with ThreadPoolExecutor(max_workers=8) as pool:
            records = list(pool.map(create, range(count)))
        stats = r.json("web", "/api/stats")
        assert stats["total"] == count + 3
        page = r.json("web", "/api/assets?limit=25&offset=25")
        assert page["total"] == count + 3 and len(page["items"]) == 25
        selected = r.json("web", "/api/assets?categoryId=2&limit=100")
        assert selected["total"] == count // 2 + 1 and all(
            x["categoryId"] == 2 for x in selected["items"]
        )
        ids = [x["id"] for x in records[:4]]
        json_request(
            r,
            "/api/loans",
            {
                "assetIds": ids * 6,
                "borrower": "林同学",
                "dueDate": "2030-01-01",
                "purpose": "bad",
                "actor": "林同学",
                "requestId": uuid.uuid4().hex,
            },
            400,
        )
    with check(
        r,
        "business",
        "multi-asset draft, approval, checkout, partial/damaged returns and maintenance",
    ):
        first = loan(ids[:2])
        id = first["id"]
        action(id, "checkout", 409)
        action(id, "submit")
        action(id, "approve")
        action(id, "checkout")
        assert r.json("web", "/api/loans?overdue=true")["total"] == 1
        key = uuid.uuid4().hex
        item = [{"assetId": ids[0], "condition": "good", "note": "完好"}]
        returned = action(id, "return", requestId=key, items=item)
        assert returned["status"] == "partially_returned"
        assert action(id, "return", requestId=key, items=item) == returned
        action(
            id,
            "return",
            requestId=key,
            items=[{"assetId": ids[1], "condition": "good", "note": "不同内容"}],
            expected=409,
        )
        action(id, "return", items=item, expected=409)
        action(
            id,
            "return",
            items=[{"assetId": ids[1], "condition": "damaged", "note": "镜头损坏"}],
        )
        assert (
            asset(ids[1])["status"] == "maintenance"
            and r.json("web", f"/api/loans/{id}")["status"] == "closed"
        )
        blocked = loan([ids[1]])
        action(blocked["id"], "submit")
        action(blocked["id"], "approve", 409)
        work = next(
            x for x in r.json("web", "/api/maintenance") if x["assetId"] == ids[1]
        )
        json_request(
            r,
            f"/api/maintenance/{work['id']}/complete",
            {
                "requestId": uuid.uuid4().hex,
                "actor": "陈老师",
                "note": "更换镜头，测试正常",
            },
        )
        action(blocked["id"], "approve")
        assert asset(ids[1])["status"] == "reserved"
        history = r.json("web", f"/api/assets/{ids[0]}/history")["events"]
        assert sum(x["action"] == "returned" for x in history) == 1
        assert (
            r.json(
                "web",
                "/api/loans?borrower="
                + __import__("urllib.parse", fromlist=["quote"]).quote("周同学"),
            )["total"]
            == 0
        )
    with check(
        r, "concurrency", "10 competing approvals reserve one asset exactly once"
    ):
        contenders = [loan([ids[2]], borrower="周同学") for _ in range(10)]
        for c in contenders:
            action(c["id"], "submit")

        def approve(c):
            return r.request(
                "web",
                f"/api/loans/{contenders[c]['id']}/approve",
                "POST",
                json.dumps({"requestId": uuid.uuid4().hex, "actor": "陈老师"}),
                {"Content-Type": "application/json"},
            )[0]

        codes = concurrent(10, approve)
        assert codes.count(200) == 1 and codes.count(409) == 9, codes
        assert asset(ids[2])["status"] == "reserved"
        before = asset(ids[3])
        bad = loan([ids[3], ids[2]])
        action(bad["id"], "submit")
        action(bad["id"], "approve", 409)
        assert asset(ids[3]) == before
    with check(
        r, "fault", "killed approval rolls back every asset and permits explicit retry"
    ):
        pending = loan([ids[0], ids[3]])
        action(pending["id"], "submit")
        fault_crash(
            r,
            "asset-lending",
            "approval-reserved",
            lambda: action(pending["id"], "approve"),
        )
        assert asset(ids[0])["status"] == asset(ids[3])["status"] == "available"
        assert r.json("web", f"/api/loans/{pending['id']}")["status"] == "submitted"
        action(pending["id"], "approve")
    with check(
        r,
        "concurrency",
        "10 retries of same creation key produce one loan and reject changed payload",
    ):
        key = uuid.uuid4().hex
        duplicates = concurrent(10, lambda _: loan([ids[0]], key=key))
        assert len({v["id"] for v in duplicates}) == 1
        json_request(
            r,
            "/api/loans",
            {
                "assetIds": [ids[1]],
                "borrower": "林同学",
                "dueDate": "2020-01-01",
                "purpose": "验收借用",
                "actor": "林同学",
                "requestId": key,
            },
            409,
        )
    with check(
        r,
        "browser",
        "registration, approval, checkout, returns, repair, lists, statistics and history",
    ):
        browser(r, "asset-lending", r.urls["web"])
    with check(r, "recovery", "SQLite restart, real C# module removal and recovery"):
        before = r.json("web", "/api/stats")
        r.stop("backend")
        r.json("web", "/api/stats", expected=502)
        r.close()
        r.start("asset-lending")
        assert r.json("web", "/api/stats") == before
        with sqlite3.connect(r.data / "assets.db") as db:
            assert db.execute("PRAGMA foreign_key_check").fetchall() == []
            assert (
                db.execute(
                    "SELECT count(*) FROM assets a WHERE status IN ('reserved','lent') AND NOT EXISTS(SELECT 1 FROM loan_items i WHERE i.asset_id=a.id AND i.loan_id=a.loan_id AND i.returned=0)"
                ).fetchone()[0]
                == 0
            )
    with check(
        r,
        "fault",
        "wrong protocol, missing fields, timeout and abnormal downstream close",
    ):
        gateway_faults(r, "asset-lending", "/api/stats")
    r.scale["assetRecords"] = r.json("web", "/api/stats")["total"]


def task_board(r):
    r.start("task-board")
    count = 10000 if r.profile == "standard" else 60
    r.scale = {"createdTasks": count, "competingRequests": 10}

    def call(path, body=None, method="POST", expected=200):
        return r.json("backend", path, method, body, expected)

    def payload(i):
        project = 1 + i % 2
        return {
            "projectId": project,
            "title": f"规模任务 {i}",
            "description": "独立规模数据",
            "ownerId": (1 if project == 1 else 3) + (i // 2) % 2,
            "priority": 1 + i % 3,
            "labels": ["scale", "even" if i % 2 == 0 else "odd"],
            "dependsOn": [],
            "dueDate": "2020-01-01" if i % 5 == 0 else "2030-10-01",
            "version": 0,
            "actorId": 1 if project == 1 else 3,
        }

    def get(id, project=1):
        return r.json("backend", f"/api/tasks/{id}?projectId={project}")

    def change(t, status, expected=200):
        return call(
            f"/api/tasks/{t['id']}/transition?projectId={t['projectId']}",
            {
                "status": status,
                "version": t["version"],
                "actorId": 1 if t["projectId"] == 1 else 3,
            },
            expected=expected,
        )

    def edit_payload(t, **changes):
        return {
            "projectId": t["projectId"],
            "title": t["title"],
            "description": t["description"],
            "ownerId": t["ownerId"],
            "priority": t["priority"],
            "labels": t["labels"],
            "dependsOn": [d["id"] for d in t["dependencies"]],
            "dueDate": t["dueDate"],
            "version": t["version"],
            "actorId": 1 if t["projectId"] == 1 else 3,
            **changes,
        }

    with check(
        r,
        "scale",
        f"{count} real task creations, independent SQL filtering and consistent overview",
    ):
        with ThreadPoolExecutor(max_workers=8) as pool:
            created = list(
                pool.map(lambda i: call("/api/tasks", payload(i)), range(count))
            )
        p1 = r.json("backend", "/api/tasks?projectId=1&size=25&page=2")
        p2 = r.json("backend", "/api/tasks?projectId=2")
        assert (
            p1["total"] == count // 2 + 2
            and p2["total"] == count // 2 + 1
            and len(p1["items"]) == 25
        )
        expected = sum(
            1
            for i in range(count)
            if payload(i)["projectId"] == 1
            and payload(i)["ownerId"] == 2
            and payload(i)["priority"] == 3
        )
        filtered = r.json(
            "backend", "/api/tasks?projectId=1&label=scale&priority=3&ownerId=2"
        )
        assert filtered["total"] == expected
        assert all(
            t["projectId"] == 1
            and t["ownerId"] == 2
            and t["priority"] == 3
            and "scale" in t["labels"]
            for t in filtered["items"]
        )
        stats = r.json("backend", "/api/stats?projectId=1")
        assert (
            stats["total"]
            == p1["total"]
            == sum(s["count"] for s in stats["statuses"])
            == sum(o["count"] for o in stats["owners"])
        )
        assert stats["overdue"] == sum(
            1 for i in range(count) if i % 2 == 0 and i % 5 == 0
        )
        r.json("backend", "/api/tasks?projectId=1&page=0", expected=400)
    with check(
        r,
        "business",
        "project boundaries, dependency cycles, invalid transition and required review flow",
    ):
        a = call("/api/tasks", {**payload(0), "title": "依赖任务"})
        b = call(
            "/api/tasks", {**payload(0), "title": "后续任务", "dependsOn": [a["id"]]}
        )
        r.json("backend", f"/api/tasks/{a['id']}?projectId=2", expected=404)
        call("/api/tasks", {**payload(0), "ownerId": 3}, expected=400)
        call(
            "/api/tasks", {**payload(0), "dependsOn": [created[1]["id"]]}, expected=404
        )
        call(
            f"/api/tasks/{a['id']}", edit_payload(a, dependsOn=[b["id"]]), "PATCH", 409
        )
        change(b, "done", 409)
        b = change(change(b, "doing"), "review")
        change(b, "done", 409)
        a = change(change(change(a, "doing"), "review"), "done")
        b = change(b, "done")
        assert get(a["id"])["status"] == get(b["id"])["status"] == "done"
        call(f"/api/tasks/{b['id']}", edit_payload(b, title="结束后编辑"), "PATCH", 409)
    with check(
        r,
        "concurrency",
        "10 edits with same record version produce one update; comments idempotent",
    ):
        target = created[2]
        data = edit_payload(target, title="竞争写入后的唯一标题")

        def update(_):
            return r.request(
                "backend",
                f"/api/tasks/{target['id']}",
                "PATCH",
                json.dumps(data).encode(),
                {"Content-Type": "application/json"},
            )[0]

        codes = concurrent(10, update)
        assert codes.count(200) == 1 and codes.count(409) == 9, codes
        changed = get(target["id"])
        assert (
            changed["version"] == target["version"] + 1
            and changed["title"] == data["title"]
        )
        key = uuid.uuid4().hex
        comments = concurrent(
            10,
            lambda _: call(
                f"/api/tasks/{target['id']}/comments?projectId=1",
                {"actorId": 1, "text": "同一条评论", "requestId": key},
            ),
        )
        assert all(len(v["comments"]) == 1 for v in comments)
        call(
            f"/api/tasks/{target['id']}/comments?projectId=1",
            {"actorId": 1, "text": "不同内容", "requestId": key},
            expected=409,
        )
    with check(r, "fault", "killed task edit rolls back version, fields and events"):
        before = get(target["id"])
        fault_crash(
            r,
            "task-board",
            "task-written",
            lambda: call(
                f"/api/tasks/{target['id']}",
                edit_payload(before, title="不会提交的字段"),
                "PATCH",
            ),
        )
        assert get(target["id"]) == before
        after = call(
            f"/api/tasks/{target['id']}",
            edit_payload(before, title="恢复成功"),
            "PATCH",
        )
        assert after["version"] == before["version"] + 1
    with check(
        r,
        "browser",
        "project overview, list, edit conflict, detail, comments, transitions and history",
    ):
        browser(r, "task-board", r.urls["frontend"])
    with check(
        r,
        "recovery",
        "Java unavailable fails business; restart preserves project results",
    ):
        before = r.json("backend", "/api/stats?projectId=1")
        r.stop("backend")
        try:
            r.json("backend", "/api/stats?projectId=1")
        except OSError:
            pass
        else:
            raise AssertionError("business succeeded without Java")
        r.close()
        r.start("task-board")
        assert r.json("backend", "/api/stats?projectId=1") == before
        with sqlite3.connect(r.data / "tasks.db") as db:
            assert db.execute("PRAGMA foreign_key_check").fetchall() == []
            assert (
                db.execute(
                    "SELECT count(*) FROM dependencies d JOIN tasks a ON a.id=d.task_id JOIN tasks b ON b.id=d.depends_on WHERE a.project_id<>b.project_id"
                ).fetchone()[0]
                == 0
            )
            r.scale["actualTasks"] = db.execute(
                "SELECT count(*) FROM tasks"
            ).fetchone()[0]


def verify_business(r, slug):
    from polyglot_survey_verify import survey_scoring
    from polyglot_csv_verify import csv_inspector
    from polyglot_native_verify import log_analyzer
    from polyglot_directory_verify import directory_diff
    from polyglot_binary_verify import binary_inspector

    r.checks = []
    r.scale = {}
    report = {
        "project": slug,
        "profile": r.profile,
        "seed": r.seed,
        "status": "failed",
        "checks": r.checks,
        "unverified": ["Linux real machine", "WindowsToLinux deployment"],
    }
    start = time.monotonic()
    try:
        {
            "file-transfer": file_transfer,
            "asset-lending": asset_lending,
            "task-board": task_board,
            "survey-scoring": survey_scoring,
            "csv-inspector": csv_inspector,
            "log-analyzer": log_analyzer,
            "directory-diff": directory_diff,
            "binary-inspector": binary_inspector,
        }[slug](r)
        report["status"] = "passed"
        return report
    finally:
        r.close()
        report.update(
            seconds=round(time.monotonic() - start, 3),
            scale=r.scale,
            resources=r.metrics,
        )
        (r.evidence / "report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
