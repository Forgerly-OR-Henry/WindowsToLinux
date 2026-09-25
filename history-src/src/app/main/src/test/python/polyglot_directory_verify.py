"""Filesystem acceptance with independent hashes, race injection and complete-only history."""

from concurrent.futures import ThreadPoolExecutor
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import time
from polyglot_business_verify import check, concurrent
from polyglot_csv_verify import wait_for
from polyglot_native_verify import invoke, fault_helper
from polyglot_process import OwnedProcess


def directory_diff(r):
    p = r.work / "success-directory-diff"
    database = r.evidence / "快照 数据.sqlite"
    folder = r.evidence / "中文 文件树"
    folder.mkdir()
    count = 10000 if r.profile == "standard" else 500
    expected = {}
    for n in range(count):
        relative = f"层 {n % 7}/子 {n % 5}/文件 {n}.txt"
        content = b"" if n % 101 == 0 else f"content-{n % 997:04}".encode()
        path = folder / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        expected[relative] = hashlib.sha256(content).hexdigest()
    deep = folder / "深层" / "a" / "b" / "c" / "d" / "e" / "f" / "g" / "h" / "i" / "j" / "空 文件.txt"
    deep.parent.mkdir(parents=True)
    deep.write_bytes(b"")
    expected[deep.relative_to(folder).as_posix()] = hashlib.sha256(b"").hexdigest()
    (folder / "忽略.tmp").write_text("excluded", encoding="utf-8")
    outside = r.evidence / "外部文件"
    outside.mkdir()
    (outside / "不得访问.txt").write_text("outside", encoding="utf-8")
    link = folder / "目录联接"
    if os.name == "nt":
        # Both paths are explicit test-owned directories; no deletion/move or shell-built command.
        r.command(
            [
                "powershell",
                "-NoProfile",
                "-Command",
                "New-Item -ItemType Junction -Path $env:TEST_JUNCTION_PATH -Target $env:TEST_JUNCTION_TARGET | Out-Null",
            ],
            r.work,
            env={"TEST_JUNCTION_PATH": str(link), "TEST_JUNCTION_TARGET": str(outside)},
        )
    else:
        link.symlink_to(outside, target_is_directory=True)
    base = r.tool("python") + [p / "cli/main.py", "--db", database, "--format", "json"]

    def command(name):
        return base + [
            "snapshot",
            "--root",
            folder,
            "--name",
            name,
            "--exclude",
            "*.tmp",
            "--workers",
            "4",
        ]

    def snapshot(name):
        return invoke(r, command(name))

    def diff(before, after, extra=None):
        return invoke(
            r,
            base + ["diff", "--before", str(before), "--after", str(after)] + (extra or []),
        )

    r.scale = {"files": len(expected), "hashWorkers": 4, "competingRequests": 10}
    with check(
        r,
        "scale",
        f"{len(expected)} files, Unicode/deep/empty paths, junction exclusion and independent SHA-256",
    ):
        first = snapshot("基线")
        assert first["status"] == "complete" and first["files"] == len(expected)
        assert any(i["path"] == "目录联接" and i["kind"] == "skipped-link" for i in first["issues"])
        with sqlite3.connect(database) as db:
            hashes = dict(
                db.execute(
                    "SELECT path,sha256 FROM entries WHERE snapshot_id=?",
                    (first["id"],),
                ).fetchall()
            )
            assert hashes == expected
        groups = invoke(r, base + ["duplicates", "--snapshot", str(first["id"])])
        oracle = {}
        for path, digest in expected.items():
            oracle.setdefault(digest, []).append(path)
        assert {g["sha256"]: sorted(g["paths"]) for g in groups["groups"]} == {
            h: sorted(paths) for h, paths in oracle.items() if len(paths) > 1
        }
    with check(
        r,
        "business",
        "same-size preserved-mtime changes, rename candidates, filtering and JSON/CSV exports",
    ):
        target = folder / "层 1/子 1/文件 1.txt"
        before = target.stat()
        target.write_bytes(b"changed-0001")
        os.utime(target, ns=(before.st_atime_ns, before.st_mtime_ns))
        assert target.stat().st_size == before.st_size and target.stat().st_mtime_ns == before.st_mtime_ns
        removed = "层 2/子 2/文件 2.txt"
        (folder / removed).unlink()
        old = "层 3/子 3/文件 3.txt"
        new = "重命名 文件.txt"
        (folder / old).rename(folder / new)
        (folder / "新增.txt").write_bytes(b"new-content")
        second = snapshot("变更后")
        result = diff(first["id"], second["id"])
        assert (
            result["added"] == sorted(["新增.txt", new])
            and result["removed"] == sorted([removed, old])
            and result["changed"] == ["层 1/子 1/文件 1.txt"]
        )
        assert result["unchanged"] == len(expected) - 3
        assert result["renameCandidates"] == [{"from": old, "to": [new], "sha256": expected[old]}]
        assert diff(first["id"], second["id"], ["--pattern", "层 1/*"])["changed"] == ["层 1/子 1/文件 1.txt"]
        csvfile = r.evidence / "差异.csv"
        csv_command = r.tool("python") + [
            p / "cli/main.py",
            "--db",
            database,
            "--format",
            "csv",
            "--output",
            csvfile,
            "diff",
            "--before",
            str(first["id"]),
            "--after",
            str(second["id"]),
        ]
        text, _ = invoke(r, csv_command, json_output=False)
        rows = list(csv.DictReader(io.StringIO(csvfile.read_text(encoding="utf-8"))))
        assert len(rows) == 6 and any(row["kind"] == "rename-candidate" for row in rows)
        selected = invoke(
            r,
            base + ["snapshot", "--root", folder, "--name", "仅一层", "--include", "层 1/*"],
        )
        invoke(
            r,
            base + ["diff", "--before", str(first["id"]), "--after", str(selected["id"])],
            2,
            json_output=False,
        )

    def race(name, mutate=None, point="hashes-computed", kill=False):
        gate = r.evidence / name
        env = {**r.env, "SAMPLE_FAULT_POINT": point, "SAMPLE_FAULT_DIR": str(gate)}
        owned = OwnedProcess(
            list(map(str, command(name))),
            cwd=r.work,
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        try:
            wait_for((gate / (point + ".ready")).exists, 60)
            if kill:
                owned.kill_member(int((gate / (point + ".ready")).read_text()))
                owned.process.wait(5)
                return
            mutate()
            (gate / (point + ".release")).write_text("continue")
            stdout, stderr = owned.process.communicate(timeout=60)
            assert owned.process.returncode == 3, (stdout, stderr)
            result = json.loads(stdout)
            assert result["status"] == "failed" and result["issues"]
            return result
        finally:
            owned.close()

    with check(
        r,
        "recovery",
        "mutation/deletion during scan cannot publish a complete snapshot",
    ):
        target = folder / "新增.txt"
        original = target.read_bytes()
        failed = race("扫描中修改", lambda: target.write_bytes(b"new-CONTENT"))
        invoke(
            r,
            base + ["diff", "--before", str(first["id"]), "--after", str(failed["id"])],
            2,
            json_output=False,
        )
        target.write_bytes(original)
        race("扫描中删除", target.unlink)
        target.write_bytes(original)
        assert snapshot("失败后可再次扫描")["status"] == "complete"
    with check(
        r,
        "recovery",
        "kill during SQLite publication preserves previous snapshots, interrupted attempt remains visible",
    ):
        race("发布中断", point="snapshot-publishing", kill=True)
        history = invoke(r, base + ["list"])
        interrupted = next(j for j in history["items"] if j["name"] == "发布中断")
        assert interrupted["status"] == "interrupted"
        assert diff(first["id"], second["id"]) == result
        assert snapshot("中断后可再次扫描")["status"] == "complete"
    with check(r, "concurrency", "10 competing snapshot names produce one completed snapshot"):

        def attempt(_):
            owned = OwnedProcess(
                list(map(str, command("并发同名"))),
                cwd=r.work,
                env=r.env,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                stdin=subprocess.DEVNULL,
            )
            try:
                out, err = owned.process.communicate(timeout=90)
                assert owned.process.returncode in [0, 2], (out, err)
                return owned.process.returncode
            finally:
                owned.close()

        codes = concurrent(10, attempt)
        assert codes.count(0) == 1 and codes.count(2) == 9
    with check(
        r,
        "fault",
        "native protocol errors, missing module, timeout, limits and child exit before cleanup",
    ):
        helper = fault_helper(r)
        records = [
            {
                "protocolVersion": 2,
                "component": "cpp-scan",
                "type": "start",
                "sequence": 0,
            },
            {
                "protocolVersion": 2,
                "component": "cpp-scan",
                "type": "entry",
                "sequence": 1,
                "path": "x",
                "size": 1,
            },
            {
                "protocolVersion": 2,
                "component": "cpp-scan",
                "type": "end",
                "sequence": 2,
                "messages": 3,
                "files": 1,
                "skipped": 0,
                "problems": 0,
                "complete": True,
            },
        ]
        for mode in ["version", "missing", "no-end", "timeout", "exit", "oversize"]:
            pid = r.evidence / f"directory-fault-{mode}.pid"
            body = (
                [dict(records[0], protocolVersion=999)]
                if mode == "version"
                else records
                if mode == "missing"
                else records[:1]
            )
            cmd = r.tool("python") + [
                p / "cli/main.py",
                "--db",
                database,
                "--format",
                "json",
                "--timeout",
                "0.25",
                "snapshot",
                "--root",
                folder,
                "--name",
                "故障-" + mode,
            ]
            value = invoke(
                r,
                cmd,
                4,
                {
                    "NATIVE_HELPER": str(helper),
                    "FAULT_MODE": mode,
                    "FAULT_PID_FILE": str(pid),
                    "FAULT_BODY": "\n".join(json.dumps(v) for v in body),
                },
            )
            assert value["status"] == "failed" and value["issues"]
        native = p / "native/build" / ("scan-worker.exe" if os.name == "nt" else "scan-worker")
        disabled = native.with_suffix(".disabled")
        native.rename(disabled)
        try:
            assert invoke(r, command("模块移除"), 4)["status"] == "failed"
        finally:
            disabled.rename(native)
        limited = command("文件数限制") + ["--max-files", "10"]
        assert invoke(r, limited, 4)["status"] == "failed"
        assert snapshot("恢复正常")["status"] == "complete"
        with sqlite3.connect(database) as db:
            assert db.execute("PRAGMA foreign_key_check").fetchall() == []
