"""Real native tool acceptance, independent data generation and subprocess fault injection."""

from collections import Counter
from datetime import datetime, timedelta, timezone
import gzip
import json
import os
from pathlib import Path
import subprocess
import time
from polyglot_business_verify import check
from polyglot_process import OwnedProcess


def exited(pid):
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes as w

        k = ctypes.WinDLL("kernel32", use_last_error=True)
        k.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
        k.OpenProcess.restype = w.HANDLE
        k.WaitForSingleObject.argtypes = [w.HANDLE, w.DWORD]
        k.CloseHandle.argtypes = [w.HANDLE]
        handle = k.OpenProcess(0x100000, False, pid)
        if handle:
            try:
                assert k.WaitForSingleObject(handle, 0) == 0, "helper still alive before runner cleanup"
            finally:
                k.CloseHandle(handle)
        else:
            assert ctypes.get_last_error() == 87
    else:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return
        raise AssertionError("helper still alive before runner cleanup")


def invoke(r, command, expected=0, env=None, json_output=True):
    cwd = r.evidence / "任意 工作目录"
    cwd.mkdir(exist_ok=True)
    started = time.monotonic()
    owned = OwnedProcess(
        list(map(str, command)),
        cwd=cwd,
        env={**r.env, **(env or {})},
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    try:
        out, err = owned.process.communicate(timeout=120)
        assert owned.process.returncode == expected, (
            command,
            owned.process.returncode,
            out[:2000],
            err[:2000],
        )
        if env and env.get("FAULT_PID_FILE"):
            exited(int(Path(env["FAULT_PID_FILE"]).read_text()))
        r.metrics.append(
            {
                "component": "native-cli",
                "seconds": round(time.monotonic() - started, 3),
                **owned.metrics(),
            }
        )
        record = {
            "argv": list(map(str, command)),
            "exitCode": owned.process.returncode,
            "seconds": round(time.monotonic() - started, 3),
            "stderr": err.decode("utf-8", errors="replace"),
        }
        with (r.evidence / "native-commands.jsonl").open("a", encoding="utf-8") as file:
            file.write(json.dumps(record, ensure_ascii=False) + "\n")
        return json.loads(out) if json_output else (out.decode("utf-8"), err.decode("utf-8", errors="replace"))
    finally:
        owned.close()


def fault_helper(r):
    source = r.evidence / "fault-worker.c"
    helper = r.evidence / ("fault-worker.exe" if os.name == "nt" else "fault-worker")
    source.write_text(
        r"""#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <windows.h>
#define WAIT() Sleep(10000)
#define PID() GetCurrentProcessId()
#else
#include <unistd.h>
#define WAIT() sleep(10)
#define PID() getpid()
#endif
int main(void) {
 const char *pid=getenv("FAULT_PID_FILE");if(pid){FILE *f=fopen(pid,"w");if(!f)return 3;fprintf(f,"%lu",(unsigned long)PID());fclose(f);}
 const char *mode=getenv("FAULT_MODE");
 if(mode&&strcmp(mode,"timeout")==0){WAIT();return 0;}
 if(mode&&strcmp(mode,"exit")==0){fprintf(stderr,"injected abnormal exit\n");return 9;}
 if(mode&&strcmp(mode,"oversize")==0){for(int n=0;n<10*1024*1024;n++)putchar('x');return 0;}
 const char *body=getenv("FAULT_BODY");puts(body?body:"{}");return 0;
}
""",
        encoding="utf-8",
    )
    r.command(r.tool("gcc") + [source, "-o", helper], r.work)
    return helper


def protocol_faults(r, command, valid_records):
    helper = fault_helper(r)
    cases = {
        "version": [dict(valid_records[0], protocolVersion=999)],
        "missing": [
            valid_records[0],
            {
                "protocolVersion": 2,
                "type": "summary",
                "sequence": 1,
                "component": valid_records[0]["component"],
            },
            valid_records[-1],
        ],
        "no-end": valid_records[:-1],
    }
    for mode in ["version", "missing", "no-end", "timeout", "exit", "oversize"]:
        pid = r.evidence / f"fault-{mode}.pid"
        env = {
            "NATIVE_HELPER": str(helper),
            "FAULT_MODE": mode,
            "FAULT_PID_FILE": str(pid),
            "FAULT_BODY": "\n".join(json.dumps(v) for v in cases.get(mode, [])),
        }
        result = invoke(r, command, 4, env)
        assert result["status"] == "partial" and result["items"][0]["status"] == "error"
    result = invoke(r, command, 4, {"NATIVE_HELPER": str(r.evidence / "missing-worker")})
    assert result["items"][0]["exitCode"] == 4


def log_analyzer(r):
    p = r.work / "success-log-analyzer"
    exe = p / "cli/target/release" / ("log-analyzer.exe" if os.name == "nt" else "log-analyzer")
    count = 100000 if r.profile == "standard" else 5000
    root = r.evidence / "中文 日志"
    root.mkdir()
    paths = [root / "api text.log", root / "worker.jsonl", root / "archive.gz"]
    writers = [
        paths[0].open("w", encoding="utf-8", newline=""),
        paths[1].open("w", encoding="utf-8", newline=""),
        gzip.open(paths[2], "wt", encoding="utf-8", newline=""),
    ]
    levels = Counter(dict.fromkeys(["DEBUG", "INFO", "WARN", "ERROR"], 0))
    services = Counter()
    minutes = Counter()
    errors = Counter()
    selected = Counter()
    selected_minutes = Counter()
    base = datetime(2026, 9, 19, 10, tzinfo=timezone.utc)

    def record(n):
        stamp = (base + timedelta(seconds=n % 3600)).strftime("%Y-%m-%dT%H:%M:%SZ")
        level = ["INFO", "WARN", "ERROR", "DEBUG"][n % 4]
        service = ["api", "worker"][n % 3 == 0]
        message = f"request {n} timeout" if level == "ERROR" else f"processed {n}"
        return {"time": stamp, "level": level, "service": service, "message": message}

    try:
        for n in range(count):
            v = record(n)
            slot = n % 3
            writers[slot].write(
                (
                    json.dumps(v, ensure_ascii=False)
                    if slot == 1
                    else f"{v['time']} {v['level']} [{v['service']}] {v['message']}"
                )
                + "\n"
            )
            levels[v["level"]] += 1
            services[v["service"]] += 1
            minutes[v["time"][:16]] += 1
            if v["level"] == "ERROR":
                errors[v["service"] + ": request # timeout"] += 1
            if (
                v["level"] == "ERROR"
                and v["service"] == "api"
                and "2026-09-19T10:10:00Z" <= v["time"] <= "2026-09-19T10:20:00Z"
            ):
                selected["matched"] += 1
                selected_minutes[v["time"][:16]] += 1
        for writer in writers:
            writer.write("this is not a log\n")
    finally:
        for writer in writers:
            writer.close()
    r.scale = {
        "logRecords": count,
        "inputFiles": 3,
        "formats": ["text", "JSON Lines", "gzip"],
        "maxConcurrentHelpers": 3,
    }
    command = [
        exe,
        "--input",
        root,
        "--format",
        "json",
        "--jobs",
        "3",
        "--timeout-ms",
        "15000",
    ]
    with check(r, "scale", f"{count} multi-file records, gzip and independent aggregate counts"):
        result = invoke(r, command, env={"DATA_DIR": str(r.evidence / "temp")})
        summary = result["summary"]
        assert summary["lines"] == count + 3 and summary["matched"] == count and summary["invalidCount"] == 3
        assert (
            summary["levels"] == levels
            and summary["services"] == services
            and summary["minutes"] == minutes
            and summary["errors"] == errors
        )
        assert len(result["items"]) == 3 and sum(i["result"]["invalidCount"] for i in result["items"]) == 3
        assert not list((r.evidence / "temp").glob("*"))
        output = r.evidence / "统计.json"
        reverse = [exe, "--format", "json", "--output", output]
        for path in reversed(paths):
            reverse.extend(["--input", path])
        reversed_report = invoke(r, reverse)
        assert (
            reversed_report["summary"] == summary and json.loads(output.read_text(encoding="utf-8")) == reversed_report
        )
        assert (
            invoke(r, reverse + ["--input", paths[0]])["summary"] == summary
        )  # Overlapping directory/explicit paths cannot double-count.
    with check(
        r,
        "business",
        "service, time, severity, keyword, ranking and equivalent text/JSONL",
    ):
        filtered = invoke(
            r,
            command
            + [
                "--service",
                "api",
                "--level",
                "ERROR",
                "--from",
                "2026-09-19T10:10:00Z",
                "--to",
                "2026-09-19T10:20:00Z",
                "--keyword",
                "timeout",
                "--top",
                "1",
            ],
        )["summary"]
        assert (
            filtered["matched"] == selected["matched"]
            and filtered["minutes"] == selected_minutes
            and len(filtered["topErrors"]) == 1
        )
        a = r.evidence / "equivalent.log"
        b = r.evidence / "equivalent.jsonl"
        a.write_text(
            "2026-09-19T10:00:00Z WARNING [服务] hello\n2026-09-19T10:00:01Z ERR [服务] failure 42\n",
            encoding="utf-8",
        )
        b.write_text(
            "\n".join(
                json.dumps(v, ensure_ascii=False)
                for v in [
                    {
                        "time": "2026-09-19T10:00:00Z",
                        "level": "WARN",
                        "service": "服务",
                        "message": "hello",
                    },
                    {
                        "time": "2026-09-19T10:00:01Z",
                        "level": "ERROR",
                        "service": "服务",
                        "message": "failure 42",
                    },
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        single = [exe, "--format", "json", "--input", a]
        assert invoke(r, single)["summary"] == invoke(r, [exe, "--format", "json", "--input", b])["summary"]
        invoke(r, single + ["--from", "2026-02-30T00:00:00Z"], 2)
        text, _ = invoke(r, [exe, "--input", a], json_output=False)
        assert "log-analyzer" in text
        multi = r.evidence / "members.gz"
        multi.write_bytes(gzip.compress(a.read_bytes()) + gzip.compress(b.read_bytes()))
        assert invoke(r, [exe, "--format", "json", "--input", multi])["summary"]["matched"] == 4
    with check(
        r,
        "fault",
        "corrupt gzip and size/key limits fail explicitly while other files continue",
    ):
        corrupt = r.evidence / "corrupt.gz"
        data = bytearray(paths[2].read_bytes())
        data[-8] ^= 1
        corrupt.write_bytes(data)
        partial = invoke(
            r,
            [exe, "--format", "json", "--input", a, "--input", corrupt],
            3,
            {"DATA_DIR": str(r.evidence / "temp")},
        )
        assert (
            partial["status"] == "partial"
            and partial["summary"]["matched"] == 2
            and any(i["status"] == "error" for i in partial["items"])
        )
        assert not list((r.evidence / "temp").glob("*"))
        invoke(r, command + ["--max-keys", "1"], 4)
        invoke(
            r,
            [exe, "--format", "json", "--input", paths[2], "--max-bytes", "1024"],
            3,
            {"DATA_DIR": str(r.evidence / "temp")},
        )
        assert not list((r.evidence / "temp").glob("*"))
        large = r.evidence / "large-line.log"
        large.write_bytes(b"a" * 70000 + b"\n")
        assert invoke(r, [exe, "--format", "json", "--input", a, "--input", large], 3)["summary"]["matched"] == 2
        assert invoke(r, single)["summary"]["matched"] == 2
    with check(
        r,
        "fault",
        "strict protocol, missing fields/end, abnormal exit, timeout and actual helper termination",
    ):
        valid = [
            {
                "protocolVersion": 2,
                "component": "cpp-log",
                "type": "start",
                "sequence": 0,
            },
            {
                "protocolVersion": 2,
                "component": "cpp-log",
                "type": "summary",
                "sequence": 1,
                **invoke(r, single)["items"][0]["result"],
            },
            {
                "protocolVersion": 2,
                "component": "cpp-log",
                "type": "end",
                "sequence": 2,
                "messages": 3,
                "records": 2,
            },
        ]
        protocol_faults(r, single + ["--timeout-ms", "250"], valid)
        native = p / "native/build" / ("log-worker.exe" if os.name == "nt" else "log-worker")
        disabled = native.with_suffix(".disabled")
        native.rename(disabled)
        try:
            assert invoke(r, single, 4)["items"][0]["status"] == "error"
        finally:
            disabled.rename(native)
        assert invoke(r, single)["summary"]["matched"] == 2
