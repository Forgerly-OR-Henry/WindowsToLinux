"""Bounded NDJSON scan transport with watchdog and explicit child reaping."""

import json
from pathlib import PurePosixPath
import queue
import subprocess
import threading
import time


class WorkerError(Exception):
    pass


def scan(helper, root, timeout, max_files):
    try:
        process = subprocess.Popen(
            [str(helper), "--root", str(root), "--max-files", str(max_files)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError as error:
        raise WorkerError("扫描子程序不可用: " + str(error)) from error
    records = queue.Queue(maxsize=64)
    stop = threading.Event()
    diagnostic = []
    deadline = time.monotonic() + timeout

    def send(value):
        while not stop.is_set():
            try:
                records.put(value, timeout=0.1)
                return
            except queue.Full:
                pass

    def reader():
        try:
            while not stop.is_set():
                line = process.stdout.readline(65537)
                if not line:
                    break
                if len(line) > 65536:
                    raise WorkerError("扫描协议单条记录超过 64 KiB")
                send(line)
        except Exception as error:
            send(error)
        finally:
            send(None)

    def errors():
        diagnostic.append(process.stderr.read(1048577))

    def watchdog():
        if not stop.wait(max(0, deadline - time.monotonic())) and process.poll() is None:
            process.kill()

    threads = [threading.Thread(target=fn, daemon=True) for fn in [reader, errors, watchdog]]
    for thread in threads:
        thread.start()
    sequence = files = skips = problems = 0
    ended = False
    paths = set()
    try:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise WorkerError("扫描子程序超时")
            try:
                raw = records.get(timeout=remaining)
            except queue.Empty:
                raise WorkerError("扫描子程序超时")
            if raw is None:
                break
            if isinstance(raw, Exception):
                raise raw
            try:
                value = json.loads(raw)
            except (ValueError, UnicodeError) as error:
                raise WorkerError("扫描子程序 JSON 无效") from error
            if (
                not isinstance(value, dict)
                or value.get("protocolVersion") != 2
                or value.get("component") != "cpp-scan"
                or value.get("sequence") != sequence
                or ended
            ):
                raise WorkerError("扫描协议版本、序号或结束标记错误")
            kind = value.get("type")
            sequence += 1
            if sequence == 1:
                if kind != "start":
                    raise WorkerError("扫描协议缺少 start")
                continue
            if kind == "end":
                if any(
                    value.get(k) != v
                    for k, v in [
                        ("files", files),
                        ("skipped", skips),
                        ("problems", problems),
                        ("messages", sequence),
                        ("complete", problems == 0),
                    ]
                ):
                    raise WorkerError("扫描结束数量不一致")
                ended = True
                continue
            if kind not in ["entry", "skip", "problem"] or not isinstance(value.get("path"), str):
                raise WorkerError("扫描条目缺少字段")
            relative = PurePosixPath(value["path"])
            if (
                relative.is_absolute()
                or ".." in relative.parts
                or "\\" in value["path"]
                or ":" in value["path"]
                or value["path"] == ""
                or len(value["path"]) > 16000
            ):
                raise WorkerError("扫描路径越界或无效")
            if kind == "entry":
                if (
                    value["path"] in paths
                    or type(value.get("size")) is not int
                    or value["size"] < 0
                    or type(value.get("modifiedNs")) is not int
                ):
                    raise WorkerError("扫描文件重复或字段无效")
                paths.add(value["path"])
                files += 1
                if files > max_files:
                    raise WorkerError("扫描文件数量超过限制")
            elif kind == "skip":
                if value.get("reason") != "symbolic-link-or-reparse-point":
                    raise WorkerError("扫描跳过原因无效")
                skips += 1
            else:
                if not isinstance(value.get("error"), str):
                    raise WorkerError("扫描错误缺少诊断")
                problems += 1
            yield value
        process.wait(timeout=max(0.01, deadline - time.monotonic()))
        if time.monotonic() >= deadline:
            raise WorkerError("扫描子程序超时")
        if not ended:
            raise WorkerError("扫描协议缺少结束标记: " + b"".join(diagnostic).decode("utf-8", errors="replace"))
        if process.returncode != (3 if problems else 0):
            raise WorkerError("扫描子程序异常退出")
    finally:
        stop.set()
        if process.poll() is None:
            process.kill()
        process.wait(timeout=5)
        for thread in threads:
            thread.join(timeout=5)
        process.stdout.close()
        process.stderr.close()
        if any(thread.is_alive() for thread in threads):
            raise WorkerError("扫描读取线程未能退出")
