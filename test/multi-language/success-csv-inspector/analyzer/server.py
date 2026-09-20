"""HTTP CSV stream input and versioned NDJSON output for the independent PHP worker."""

import base64
import csv
import io
import json
import os
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from jsonschema import ValidationError
from analysis import analyze

active = 0
lock = threading.Lock()
slots = threading.BoundedSemaphore(int(os.getenv("MAX_CONCURRENT_JOBS", "4")))


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path != "/healthz":
            self.reply(404, {"error": "not found"})
            return
        with lock:
            running = active
        self.reply(
            200,
            {
                "status": "ok",
                "component": "python-csv",
                "version": 2,
                "activeJobs": running,
            },
        )

    def do_POST(self):
        global active
        if self.path != "/analyze":
            self.reply(404, {"error": "not found"})
            return
        if not slots.acquire(blocking=False):
            self.reply(503, {"error": "分析并发达到上限"})
            return
        with lock:
            active += 1
        started = False
        sequence = 0

        def send(record):
            nonlocal sequence
            value = {"protocolVersion": 2, "sequence": sequence, **record}
            sequence += 1
            self.wfile.write((json.dumps(value, ensure_ascii=False) + "\n").encode())
            self.wfile.flush()

        try:
            self.connection.settimeout(float(os.getenv("INPUT_TIMEOUT_SECONDS", "30")))
            size = int(self.headers.get("Content-Length", "0"))
            if not 1 <= size <= int(os.getenv("MAX_FILE_BYTES", "134217728")):
                raise ValueError("CSV 请求大小无效")
            if self.headers.get("X-Sample-Protocol") != "2":
                raise ValueError("protocol version mismatch")
            encoded = self.headers.get("X-CSV-Rules", "")
            if len(encoded) > 16384:
                raise ValueError("规则头过大")
            rules = json.loads(base64.b64decode(encoded, validate=True).decode("utf-8"))
            with tempfile.TemporaryFile("w+b") as file:
                remaining = size
                while remaining:
                    chunk = self.rfile.read(min(65536, remaining))
                    if not chunk:
                        raise ValueError("CSV 上传中断")
                    file.write(chunk)
                    remaining -= len(chunk)
                file.seek(0)
                with io.TextIOWrapper(file, encoding="utf-8-sig", newline="") as stream:
                    records = analyze(stream, rules)
                    first = next(records)
                    self.send_response(200)
                    self.send_header(
                        "Content-Type", "application/x-ndjson; charset=utf-8"
                    )
                    self.send_header("X-Sample-Protocol", "2")
                    self.send_header("Connection", "close")
                    self.end_headers()
                    self.close_connection = True
                    started = True
                    send(first)
                    for record in records:
                        send(record)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass
        except Exception as error:
            text = (
                str(error).split("\n")[0]
                if isinstance(
                    error, (ValueError, ValidationError, csv.Error, UnicodeError)
                )
                else type(error).__name__
            )
            try:
                if started:
                    send({"type": "error", "error": text})
                else:
                    self.reply(400, {"error": text})
            except OSError:
                pass
        finally:
            with lock:
                active -= 1
            slots.release()


if __name__ == "__main__":
    ThreadingHTTPServer(
        (os.getenv("HOST", "127.0.0.1"), int(os.getenv("PORT", "18131"))), Handler
    ).serve_forever()
