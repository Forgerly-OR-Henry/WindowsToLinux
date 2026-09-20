"""SQLite keeps incomplete attempts visible; only atomically published snapshots can compare."""

from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
from contextlib import contextmanager, closing
import fnmatch
import json
import os
from pathlib import Path
import sqlite3
from hashing import checked_path, change_time, digest, gate, identity, linked
from scanner import scan, WorkerError


def alive(pid):
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes as w

        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
        kernel.OpenProcess.restype = w.HANDLE
        kernel.WaitForSingleObject.argtypes = [w.HANDLE, w.DWORD]
        kernel.CloseHandle.argtypes = [w.HANDLE]
        handle = kernel.OpenProcess(0x100000, False, pid)
        if not handle:
            return ctypes.get_last_error() != 87
        try:
            return kernel.WaitForSingleObject(handle, 0) == 258
        finally:
            kernel.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


class SnapshotStore:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as c:
            c.execute("PRAGMA journal_mode=WAL")
            version = c.execute("PRAGMA user_version").fetchone()[0]
            if version not in [0, 2]:
                raise ValueError("需要新的 v2 数据目录")
            c.executescript(
                """CREATE TABLE IF NOT EXISTS snapshots(id INTEGER PRIMARY KEY,name TEXT NOT NULL UNIQUE,root TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN('building','complete','failed','interrupted')),owner INTEGER NOT NULL,filters TEXT NOT NULL,files INTEGER NOT NULL DEFAULT 0,bytes INTEGER NOT NULL DEFAULT 0,created TEXT DEFAULT CURRENT_TIMESTAMP,completed TEXT);
                CREATE TABLE IF NOT EXISTS entries(snapshot_id INTEGER NOT NULL REFERENCES snapshots(id),path TEXT NOT NULL,size INTEGER NOT NULL,sha256 TEXT NOT NULL,fingerprint TEXT NOT NULL,PRIMARY KEY(snapshot_id,path));
                CREATE INDEX IF NOT EXISTS entries_hash ON entries(snapshot_id,sha256,size);
                CREATE TABLE IF NOT EXISTS issues(id INTEGER PRIMARY KEY,snapshot_id INTEGER NOT NULL REFERENCES snapshots(id),path TEXT NOT NULL,kind TEXT NOT NULL,detail TEXT NOT NULL);
                PRAGMA user_version=2;"""
            )
            for row in c.execute(
                "SELECT id,owner FROM snapshots WHERE status='building'"
            ).fetchall():
                if not alive(row["owner"]):
                    c.execute(
                        "UPDATE snapshots SET status='interrupted' WHERE id=? AND status='building'",
                        (row["id"],),
                    )
                    c.execute(
                        "INSERT INTO issues(snapshot_id,path,kind,detail) VALUES(?,?,?,?)",
                        (
                            row["id"],
                            "",
                            "interrupted",
                            "扫描进程中断；请创建新的命名快照",
                        ),
                    )

    @contextmanager
    def connection(self):
        c = sqlite3.connect(self.path, timeout=10)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA foreign_keys=ON")
        c.create_function(
            "matches",
            2,
            lambda pattern, path: int(fnmatch.fnmatchcase(path, pattern)),
            deterministic=True,
        )
        try:
            with c:
                yield c
        finally:
            c.close()

    def issue(self, id, path, kind, detail):
        with self.connection() as c:
            c.execute(
                "INSERT INTO issues(snapshot_id,path,kind,detail) VALUES(?,?,?,?)",
                (id, path, kind, str(detail)[:2000]),
            )

    def details(self, id):
        with self.connection() as c:
            row = c.execute("SELECT * FROM snapshots WHERE id=?", (id,)).fetchone()
            if not row:
                raise ValueError("快照不存在")
            result = dict(row)
            result["filters"] = json.loads(result["filters"])
            result["issues"] = [
                dict(r)
                for r in c.execute(
                    "SELECT path,kind,detail FROM issues WHERE snapshot_id=? ORDER BY id",
                    (id,),
                )
            ]
            result["protocolVersion"] = 2
            return result

    def snapshot(self, args):
        root = args.root.absolute()
        if linked(root.lstat()) or not root.is_dir():
            raise OSError("根路径必须为真实目录，不能是符号链接或目录联接")
        root = root.resolve()
        if self.path.resolve().is_relative_to(root):
            raise ValueError("SQLite 数据库必须位于被扫描目录之外")
        includes = args.include or ["*"]
        excludes = args.exclude
        selected = lambda path: any(
            fnmatch.fnmatchcase(path, p) for p in includes
        ) and not any(fnmatch.fnmatchcase(path, p) for p in excludes)
        with self.connection() as c:
            try:
                id = c.execute(
                    "INSERT INTO snapshots(name,root,status,owner,filters) VALUES(?,?,'building',?,?)",
                    (
                        args.name,
                        str(root),
                        os.getpid(),
                        json.dumps({"include": includes, "exclude": excludes}),
                    ),
                ).lastrowid
            except sqlite3.IntegrityError as error:
                raise ValueError("快照名称已存在") from error
        code = 0
        batch = []
        pending = {}
        entries = {}
        first = {}
        skipped = set()

        def save():
            if not batch:
                return
            with self.connection() as c:
                c.executemany(
                    "INSERT INTO entries VALUES(?,?,?,?,?)",
                    [
                        (
                            id,
                            e["path"],
                            e["size"],
                            e["sha256"],
                            json.dumps(e["identity"]),
                        )
                        for e in batch
                    ],
                )
            batch.clear()

        def completed(block):
            nonlocal code
            done, _ = wait(
                pending, return_when=FIRST_COMPLETED, timeout=None if block else 0
            )
            for future in done:
                path = pending.pop(future)
                try:
                    entry = future.result()
                    entries[path] = entry
                    batch.append(entry)
                    if len(batch) >= 250:
                        save()
                except OSError as error:
                    code = 3
                    self.issue(id, path, "file", error)

        try:
            gate("scan-started")
            with ThreadPoolExecutor(max_workers=args.workers) as pool, closing(
                scan(args.helper, root, args.timeout, args.max_files)
            ) as records:
                for item in records:
                    path = item["path"]
                    if item["type"] == "problem":
                        code = 3
                        self.issue(id, path, "scan", item["error"])
                    elif item["type"] == "skip":
                        skipped.add(path)
                        self.issue(id, path, "skipped-link", item["reason"])
                    elif selected(path):
                        first[path] = (item["size"], item["modifiedNs"])
                        while len(pending) >= args.workers * 2:
                            completed(True)
                        pending[
                            pool.submit(digest, root, item, args.max_file_bytes)
                        ] = path
                        completed(False)
                while pending:
                    completed(True)
            save()
            gate("hashes-computed")
            second = {}
            second_links = set()
            with closing(
                scan(args.helper, root, args.timeout, args.max_files)
            ) as records:
                for item in records:
                    path = item["path"]
                    if item["type"] == "problem":
                        code = 3
                        self.issue(id, path, "rescan", item["error"])
                    elif item["type"] == "skip":
                        second_links.add(path)
                    elif selected(path):
                        second[path] = (item["size"], item["modifiedNs"])
            if first != second or skipped != second_links:
                code = 3
                self.issue(
                    id, "", "changed-tree", "扫描期间路径集合、大小、时间或链接发生变化"
                )
            for path, entry in entries.items():
                try:
                    file = checked_path(root, path)
                    if (
                        identity(file.stat(follow_symlinks=False)) != entry["identity"]
                        or change_time(path=file) != entry["changeTime"]
                    ):
                        raise OSError("扫描期间文件元数据或身份发生变化")
                except OSError as error:
                    code = 3
                    self.issue(id, path, "changed-file", error)
            with self.connection() as c:
                c.execute(
                    "UPDATE snapshots SET status=?,files=?,bytes=?,completed=CURRENT_TIMESTAMP WHERE id=?",
                    (
                        "failed" if code else "complete",
                        len(entries),
                        sum(e["size"] for e in entries.values()),
                        id,
                    ),
                )
                gate("snapshot-publishing")
        except WorkerError as error:
            code = 4
            self.issue(id, "", "worker", error)
        except (OSError, sqlite3.Error) as error:
            code = 3
            self.issue(id, "", "storage", error)
        finally:
            if code:
                with self.connection() as c:
                    c.execute("UPDATE snapshots SET status='failed' WHERE id=?", (id,))
        return self.details(id), code

    def history(self, offset=0, limit=100):
        with self.connection() as c:
            return {
                "protocolVersion": 2,
                "items": [
                    dict(r)
                    for r in c.execute(
                        "SELECT * FROM snapshots ORDER BY id DESC LIMIT ? OFFSET ?",
                        (limit, offset),
                    )
                ],
                "total": c.execute("SELECT count(*) FROM snapshots").fetchone()[0],
            }

    def require_complete(self, c, id):
        row = c.execute("SELECT * FROM snapshots WHERE id=?", (id,)).fetchone()
        if not row or row["status"] != "complete":
            raise ValueError("只有完整快照可以比较或检查重复内容")
        return row

    def duplicates(self, id, pattern):
        with self.connection() as c:
            self.require_complete(c, id)
            groups = []
            for row in c.execute(
                "SELECT sha256,size,count(*) copies FROM entries WHERE snapshot_id=? AND matches(?,path) GROUP BY sha256,size HAVING count(*)>1 ORDER BY sha256",
                (id, pattern),
            ):
                paths = [
                    r[0]
                    for r in c.execute(
                        "SELECT path FROM entries WHERE snapshot_id=? AND sha256=? AND size=? AND matches(?,path) ORDER BY path",
                        (id, row["sha256"], row["size"], pattern),
                    )
                ]
                groups.append({**dict(row), "paths": paths})
            return {
                "protocolVersion": 2,
                "snapshot": id,
                "groups": groups,
                "redundantBytes": sum((g["copies"] - 1) * g["size"] for g in groups),
            }

    def diff(self, before, after, pattern):
        with self.connection() as c:
            a = self.require_complete(c, before)
            b = self.require_complete(c, after)
            if a["root"] != b["root"] or a["filters"] != b["filters"]:
                raise ValueError("正式比较需要相同根目录及包含/排除规则")

            def absent(left, right):
                return [
                    dict(row)
                    for row in c.execute(
                        "SELECT a.path,a.size,a.sha256 FROM entries a LEFT JOIN entries b ON b.snapshot_id=? AND b.path=a.path WHERE a.snapshot_id=? AND b.path IS NULL AND matches(?,a.path) ORDER BY a.path",
                        (right, left, pattern),
                    )
                ]

            added = absent(after, before)
            removed = absent(before, after)
            changed = [
                r[0]
                for r in c.execute(
                    "SELECT a.path FROM entries a JOIN entries b ON b.snapshot_id=? AND b.path=a.path WHERE a.snapshot_id=? AND a.sha256<>b.sha256 AND matches(?,a.path) ORDER BY a.path",
                    (after, before, pattern),
                )
            ]
            unchanged = c.execute(
                "SELECT count(*) FROM entries a JOIN entries b ON b.snapshot_id=? AND b.path=a.path WHERE a.snapshot_id=? AND a.sha256=b.sha256 AND matches(?,a.path)",
                (after, before, pattern),
            ).fetchone()[0]
            by_hash = {}
            for item in added:
                by_hash.setdefault((item["sha256"], item["size"]), []).append(
                    item["path"]
                )
            candidates = [
                {
                    "from": item["path"],
                    "to": by_hash[(item["sha256"], item["size"])],
                    "sha256": item["sha256"],
                }
                for item in removed
                if (item["sha256"], item["size"]) in by_hash
            ]
            return {
                "protocolVersion": 2,
                "before": before,
                "after": after,
                "added": [i["path"] for i in added],
                "removed": [i["path"] for i in removed],
                "changed": changed,
                "unchanged": unchanged,
                "renameCandidates": candidates,
            }
