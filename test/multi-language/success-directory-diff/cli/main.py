import argparse
import csv
import io
import json
import os
from pathlib import Path
import sqlite3
import sys
from scanner import WorkerError
from store import SnapshotStore


def output(result, format):
    if format != "csv":
        return json.dumps(result, ensure_ascii=False, indent=2)
    stream = io.StringIO(newline="")
    writer = csv.writer(stream)
    writer.writerow(["kind", "path", "detail"])
    for kind in ["added", "removed", "changed"]:
        for path in result.get(kind, []):
            writer.writerow([kind, path, ""])
    for item in result.get("renameCandidates", []):
        writer.writerow(
            [
                "rename-candidate",
                item["from"],
                json.dumps(item["to"], ensure_ascii=False),
            ]
        )
    for group in result.get("groups", []):
        for path in group["paths"]:
            writer.writerow(["duplicate", path, group["sha256"]])
    for item in result.get("items", []):
        writer.writerow(["snapshot", item["name"], json.dumps(item, ensure_ascii=False)])
    if "status" in result:
        writer.writerow(["snapshot", result["name"], result["status"]])
    for item in result.get("issues", []):
        writer.writerow([item["kind"], item["path"], item["detail"]])
    return stream.getvalue()


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="C++ 流式扫描、Python 并行摘要、完整快照与 SQLite 比较")
    parser.add_argument(
        "--db",
        type=Path,
        default=Path(os.getenv("DATA_DIR", str(root / "data"))) / "snapshots.db",
    )
    parser.add_argument(
        "--helper",
        type=Path,
        default=Path(
            os.getenv(
                "NATIVE_HELPER",
                str(root / "native/build" / ("scan-worker.exe" if os.name == "nt" else "scan-worker")),
            )
        ),
    )
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--format", choices=["text", "json", "csv"], default="text")
    parser.add_argument("--output", type=Path)
    sub = parser.add_subparsers(dest="operation", required=True)
    snapshot = sub.add_parser("snapshot")
    snapshot.add_argument("--root", type=Path, required=True)
    snapshot.add_argument("--name", required=True)
    snapshot.add_argument("--include", action="append", default=[])
    snapshot.add_argument("--exclude", action="append", default=[])
    snapshot.add_argument("--workers", type=int, default=int(os.getenv("HASH_WORKERS", "4")))
    snapshot.add_argument("--max-files", type=int, default=100000)
    snapshot.add_argument("--max-file-bytes", type=int, default=268435456)
    history = sub.add_parser("list")
    history.add_argument("--offset", type=int, default=0)
    history.add_argument("--limit", type=int, default=100)
    detail = sub.add_parser("show")
    detail.add_argument("--snapshot", type=int, required=True)
    diff = sub.add_parser("diff")
    diff.add_argument("--before", type=int, required=True)
    diff.add_argument("--after", type=int, required=True)
    diff.add_argument("--pattern", default="*")
    dup = sub.add_parser("duplicates")
    dup.add_argument("--snapshot", type=int, required=True)
    dup.add_argument("--pattern", default="*")
    args = parser.parse_args()
    if not 0 < args.timeout <= 600:
        parser.error("--timeout must be within (0,600]")
    if args.operation == "snapshot" and (
        not args.name.strip()
        or len(args.name) > 100
        or not 1 <= args.workers <= 32
        or not 1 <= args.max_files <= 1000000
        or not 1 <= args.max_file_bytes <= 10737418240
        or len(args.include) + len(args.exclude) > 40
        or any(len(p) > 256 for p in args.include + args.exclude)
    ):
        parser.error("snapshot name, patterns or limits are invalid")
    store = SnapshotStore(args.db)
    code = 0
    if args.operation == "snapshot":
        result, code = store.snapshot(args)
    elif args.operation == "list":
        if args.offset < 0 or not 1 <= args.limit <= 1000:
            parser.error("invalid pagination")
        result = store.history(args.offset, args.limit)
    elif args.operation == "show":
        result = store.details(args.snapshot)
    elif args.operation == "duplicates":
        result = store.duplicates(args.snapshot, args.pattern)
    else:
        result = store.diff(args.before, args.after, args.pattern)
    rendered = output(result, args.format)
    if len(rendered.encode("utf-8")) > 64 * 1024 * 1024:
        raise ValueError("报告超过 64 MiB 输出限制")
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered if args.format != "text" else args.operation + "\n" + rendered)
    if code:
        print("快照未完成，请查看 issues；不能用于正式比较", file=sys.stderr)
    return code


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try:
        sys.exit(main())
    except WorkerError as error:
        print(str(error), file=sys.stderr)
        sys.exit(4)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
    except (OSError, sqlite3.Error) as error:
        print(str(error), file=sys.stderr)
        sys.exit(3)
