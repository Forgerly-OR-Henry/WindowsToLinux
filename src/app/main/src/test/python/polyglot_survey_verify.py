"""Survey revision and Ruby-score acceptance, with an independent arithmetic oracle."""

from concurrent.futures import ThreadPoolExecutor
import copy
import http.server
import json
import socket
import threading
import time
import uuid
from polyglot_business_verify import check, browser, concurrent, fault_crash


def survey_scoring(r):
    r.start("survey-scoring")
    count = 10000 if r.profile == "standard" else 60
    r.scale = {"submissionRecords": count, "competingRequests": 10}

    def call(path, data=None, method="POST", expected=200):
        return r.json("backend", path, method, data, expected)

    def submit(answers, revision=1, key=None, respondent="林同学", expected=200):
        return call(
            "/api/submissions",
            {
                "revisionId": revision,
                "respondent": respondent,
                "requestId": key or uuid.uuid4().hex,
                "answers": answers,
            },
            expected=expected,
        )

    baseline = {
        "participated": "yes",
        "quality": 4,
        "support": ["docs", "peer"],
        "friction": 2,
    }

    def answers(i):
        return {
            "participated": "yes" if i % 2 == 0 else "no",
            "quality": 1 + i % 5,
            "support": ["docs", "peer"] if i % 3 == 0 else ["docs"],
            "friction": 1 + i % 5,
        }

    def expected(i):
        # Counts and arithmetic derive from the published fixed sample, not scorer output.
        participating = i % 2 == 0
        raw = (2 + 2 * (i % 5) if participating else 0) + (5 if i % 3 == 0 else 2) + (4 - i % 5)
        return round(raw * 100 / (19 if participating else 11), 2)

    with check(
        r,
        "scale",
        f"{count} real Kotlin/Ruby submissions with independent score expectations",
    ):

        def run(i):
            result = submit(answers(i), respondent="林同学" if i % 2 == 0 else "周同学")
            assert result["result"]["score"] == expected(i), (i, result)
            assert result["result"]["hidden"] == ([] if i % 2 == 0 else ["quality"])
            return result["id"]

        with ThreadPoolExecutor(max_workers=8) as pool:
            ids = list(pool.map(run, range(count)))
        history = r.json("backend", "/api/submissions?revisionId=1&limit=25&offset=25")
        assert history["total"] == count and len(history["items"]) == 25
        stats = r.json("backend", "/api/surveys/1/stats")
        assert (
            stats["total"] == count
            and abs(stats["revisions"][0]["average"] - sum(expected(i) for i in range(count)) / count) < 1e-8
        )
    with check(
        r,
        "business",
        "conditional required questions, input boundaries and immutable published revisions",
    ):
        result = submit({"participated": "no", "support": ["docs"], "friction": 2})
        assert result["result"]["score"] == 45.45 and "quality" not in result["answers"]
        submit({"participated": "yes", "support": ["docs"], "friction": 2}, expected=400)
        submit({**baseline, "quality": 6}, expected=400)
        submit({**baseline, "support": ["docs", "docs"]}, expected=400)
        submit({**baseline, "unknown": 1}, expected=400)
        old = r.json("backend", "/api/revisions/1")
        call(
            "/api/revisions/1",
            {
                "actor": "陈老师",
                "editVersion": old["editVersion"],
                "content": old["content"],
            },
            "PUT",
            409,
        )
        original = submit(baseline)
        assert original["result"]["score"] == 84.21
        preserved = r.json("backend", f"/api/submissions/{original['id']}")
    with check(
        r,
        "concurrency",
        "10 repeated submissions yield one result; identity and payload conflicts rejected",
    ):
        key = uuid.uuid4().hex
        results = concurrent(10, lambda _: submit(baseline, key=key))
        assert len({v["id"] for v in results}) == 1
        submit(baseline, key=key, respondent="周同学", expected=409)
        submit({**baseline, "quality": 3}, key=key, expected=409)
    with check(
        r,
        "business",
        "new draft, changed version rules, close, history/export and survey isolation",
    ):
        draft = call("/api/surveys/1/revisions", {"actor": "陈老师"})
        submit(baseline, revision=draft["id"], expected=409)
        content = copy.deepcopy(draft["content"])
        content["questions"][1]["rule"]["weight"] = 3
        edited = call(
            f"/api/revisions/{draft['id']}",
            {
                "actor": "陈老师",
                "editVersion": draft["editVersion"],
                "content": content,
            },
            "PUT",
        )
        call(f"/api/revisions/{draft['id']}/publish", {"actor": "陈老师"})
        changed = submit(baseline, revision=draft["id"])
        assert (
            changed["result"]["score"] == 82.61
            and changed["result"]["weightedTotal"] == 19
            and changed["result"]["maximum"] == 23
        )
        call("/api/revisions/1/close", {"actor": "陈老师"})
        submit(baseline, expected=409)
        saved = r.json("backend", f"/api/submissions/{original['id']}")
        assert (
            saved["result"] == preserved["result"] and saved["revision"]["content"] == preserved["revision"]["content"]
        )
        exported = r.json("backend", f"/api/submissions/{original['id']}/export")
        assert exported == saved
        other = call("/api/surveys", {"name": "独立问卷", "actor": "林同学"})
        assert r.json("backend", f"/api/surveys/{other['surveyId']}/stats")["total"] == 0
        assert r.json("backend", f"/api/submissions?revisionId={draft['id']}")["total"] == 1
        active = draft["id"]
    for point in ["score-returned", "submission-written"]:
        with check(
            r,
            "fault",
            point + ": interrupted scoring/publication leaves no ghost or duplicate result",
        ):
            before = r.json("backend", f"/api/submissions?revisionId={active}")["total"]
            key = uuid.uuid4().hex
            fault_crash(
                r,
                "survey-scoring",
                point,
                lambda: submit(baseline, revision=active, key=key),
            )
            assert r.json("backend", f"/api/submissions?revisionId={active}")["total"] == before
            retried = submit(baseline, revision=active, key=key)
            assert submit(baseline, revision=active, key=key)["id"] == retried["id"]
            assert r.json("backend", f"/api/submissions?revisionId={active}")["total"] == before + 1
    with check(
        r,
        "fault",
        "Ruby rule mutation affects real score; restored module recovers and history stays immutable",
    ):
        scorer = r.work / "success-survey-scoring/scorer"
        file = scorer / "scoring.rb"
        source_bytes = file.read_bytes()
        source = source_bytes.decode("utf-8")
        expected_before = submit(baseline, revision=active)

        def restart():
            process = r.start_process(
                "scorer",
                r.tool("bundle") + ["exec", "ruby", "server.rb"],
                scorer,
                {
                    "HOST": r.host,
                    "PORT": str(r.ports["scorer"]),
                    "BUNDLE_FROZEN": "true",
                    "BUNDLE_PATH": str(scorer / "vendor/bundle"),
                },
            )
            r.wait_http("scorer", process=process)

        try:
            r.stop("scorer")
            assert "weighted = raw * weight;" in source
            file.write_text(
                source.replace("weighted = raw * weight;", "weighted = (raw * weight) / 2;"),
                encoding="utf-8",
            )
            restart()
            changed = submit(baseline, revision=active)
            assert changed["result"]["score"] != expected_before["result"]["score"], "Ruby collaboration was bypassed"
            assert r.json("backend", f"/api/submissions/{expected_before['id']}")["result"] == expected_before["result"]
        finally:
            r.stop("scorer")
            file.write_bytes(source_bytes)
            restart()
        assert submit(baseline, revision=active)["result"]["score"] == 82.61
    with check(
        r,
        "fault",
        "Ruby unavailable, bad protocol, missing fields, oversized response, timeout and abort",
    ):
        r.close()
        r.env["SCORER_TIMEOUT_MS"] = "400"
        r.start("survey-scoring")
        r.stop("scorer")
        before = r.json("backend", f"/api/submissions?revisionId={active}")["total"]
        submit(baseline, revision=active, expected=502)

        class Handler(http.server.BaseHTTPRequestHandler):
            mode = "version"

            def log_message(self, *args):
                pass

            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                if self.mode == "exit":
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                if self.mode == "timeout":
                    time.sleep(0.8)
                value = {
                    "protocolVersion": 1 if self.mode == "version" else 2,
                    "component": "ruby-scoring",
                    "revisionId": active,
                }
                body = b" " * 300000 if self.mode == "oversized" else json.dumps(value).encode()
                try:
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                except (OSError, ConnectionError):
                    pass

        server = http.server.ThreadingHTTPServer((r.host, r.ports["scorer"]), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            for mode in ["version", "missing-fields", "oversized", "timeout", "exit"]:
                Handler.mode = mode
                submit(baseline, revision=active, expected=502)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(5)
        assert not thread.is_alive() and r.json("backend", f"/api/submissions?revisionId={active}")["total"] == before
        r.close()
        r.env.pop("SCORER_TIMEOUT_MS")
        r.start("survey-scoring")
        assert submit(baseline, revision=active)["result"]["score"] == 82.61
    with check(
        r,
        "browser",
        "question editor, conditional answers, publication, new version, history and explanations",
    ):
        browser(r, "survey-scoring", r.urls["frontend"])
    with check(r, "recovery", "restart retains original answers, immutable content and scores"):
        before = r.json("backend", f"/api/submissions/{original['id']}")
        r.close()
        r.start("survey-scoring")
        assert r.json("backend", f"/api/submissions/{original['id']}") == before
        r.scale["actualSubmissions"] = sum(s["submissions"] for s in r.json("backend", "/api/surveys"))
