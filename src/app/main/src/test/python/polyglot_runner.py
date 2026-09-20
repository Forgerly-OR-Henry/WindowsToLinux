"""Build/run independent polyglot fixtures; no SSH or production deployment calls."""

import argparse
import contextlib
import functools
import http.client
import http.server
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from polyglot_process import OwnedProcess

ROOT = Path(__file__).resolve().parents[6]
FIXTURES = ROOT / "test/multi-language"
IGNORED = {
    "target",
    "node_modules",
    ".venv",
    ".gradle",
    "build",
    "dist",
    "vendor",
    "bin",
    "obj",
    "__pycache__",
    "data",
    ".bundle",
}


class Runner:
    def __init__(self, work, tools=None, host="127.0.0.1", profile="standard"):
        self.work = Path(work).resolve()
        self.work.mkdir(parents=True, exist_ok=True)
        self.tools = tools or {}
        self.host = host
        self.profile = profile
        self.seed = 20260919
        self.metrics = []
        self.commands = []
        self.stack = contextlib.ExitStack()
        self.urls = {}
        self.ports = {}
        self.processes = {}
        self.data = self.work / "运行 数据"
        self.evidence = self.work
        self.env = dict(os.environ)
        self.env.update({str(k): str(v) for k, v in self.tools.get("env", {}).items()})
        directories = []
        for key, value in self.tools.items():
            if key != "env" and isinstance(value, str):
                directories.append(str(Path(value).parent))
        self.env["PATH"] = os.pathsep.join(directories + [self.env.get("PATH", "")])

    def tool(self, name):
        value = self.tools.get(name)
        if value:
            return value if isinstance(value, list) else [value]
        found = shutil.which(name, path=self.env["PATH"])
        if not found:
            raise RuntimeError(
                "Required tool unavailable: "
                + name
                + "; configure --tools, do not count as passed"
            )
        return [found]

    def command(self, args, cwd, timeout=1800, env=None):
        log = self.work / ("command-" + str(len(self.commands)) + ".log")
        record = {
            "argv": list(map(str, args)),
            "cwd": str(cwd),
            "log": str(log),
            "status": "running",
        }
        self.commands.append(record)
        start = time.monotonic()
        try:
            with log.open("wb") as output, OwnedProcess(
                list(map(str, args)),
                cwd=cwd,
                env={**self.env, **(env or {})},
                stdout=output,
                stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
            ) as p:
                code = p.wait(timeout=timeout)
                record.update(exitCode=code, status="passed" if code == 0 else "failed")
        except BaseException as error:
            record.update(status="failed", error=type(error).__name__)
            raise
        finally:
            record["seconds"] = round(time.monotonic() - start, 3)
        if code:
            raise RuntimeError(
                "Command failed "
                + str(code)
                + ": "
                + str(args)
                + "\n"
                + log.read_text(encoding="utf-8", errors="replace")[-7000:]
            )

    def versions(self, slug):
        names = {
            "task-board": ["java", "mvn", "node", "npm"],
            "file-transfer": ["go", "node", "npm"],
            "asset-lending": ["dotnet", "node", "npm"],
            "csv-inspector": ["php", "composer", "python"],
            "survey-scoring": ["java", "ruby", "bundle", "node", "npm"],
            "log-analyzer": ["cargo", "cmake", "g++"],
            "directory-diff": ["python", "cmake", "g++"],
            "binary-inspector": ["cargo", "cmake", "gcc"],
        }[slug]
        for name in names:
            flag = (
                ["version"]
                if name == "go"
                else ["-version"] if name == "java" else ["--version"]
            )
            self.command(self.tool(name) + flag, self.work, timeout=60)
        if slug == "survey-scoring" and "gradle" in self.tools:
            self.command(self.tool("gradle") + ["--version"], self.work, timeout=60)

    def copy(self, slug):
        source = FIXTURES / ("success-" + slug)
        dest = self.work / source.name
        if dest.resolve().is_relative_to(FIXTURES.resolve()):
            raise ValueError(
                "Isolated work directory must not be inside fixture sources"
            )
        manifest = dest / ".fixture-sources.json"
        previous = (
            json.loads(manifest.read_text(encoding="utf-8"))
            if manifest.exists()
            else []
        )
        for relative in previous:
            target = dest / relative
            if not target.resolve().is_relative_to(dest.resolve()):
                raise ValueError("Invalid isolated source manifest path")
            if target.is_file() and not (source / relative).exists():
                target.unlink()
        shutil.copytree(
            source,
            dest,
            dirs_exist_ok=True,
            ignore=lambda d, n: [
                v
                for v in n
                if v in IGNORED and not (v == "vendor" and Path(d).name == "native")
            ],
        )
        copied = []
        for directory, subdirs, files in os.walk(source):
            subdirs[:] = [
                name
                for name in subdirs
                if name not in IGNORED
                or (name == "vendor" and Path(directory).name == "native")
            ]
            copied.extend(
                (Path(directory) / name).relative_to(source).as_posix()
                for name in files
                if name not in IGNORED
            )
        manifest.write_text(json.dumps(sorted(copied), indent=2), encoding="utf-8")
        return dest

    def build(self, slug):
        p = self.copy(slug)
        if slug == "task-board":
            self.command(self.tool("mvn") + ["-B", "-ntp", "package"], p / "backend")
            self.npm(p / "frontend")
        elif slug == "file-transfer":
            self.command(
                self.tool("go")
                + [
                    "build",
                    "-mod=readonly",
                    "-o",
                    "file-transfer" + (".exe" if os.name == "nt" else ""),
                    ".",
                ],
                p / "backend",
            )
            self.npm(p / "web")
        elif slug == "asset-lending":
            source = (
                ["--source", self.tools["nugetSource"]]
                if "nugetSource" in self.tools
                else []
            )
            self.command(
                self.tool("dotnet") + ["restore", "--locked-mode"] + source,
                p / "backend",
            )
            self.command(
                self.tool("dotnet")
                + ["publish", "--no-restore", "-c", "Release", "-o", "publish"],
                p / "backend",
            )
            self.npm(p / "web")
        elif slug == "csv-inspector":
            self.command(self.tool("python") + ["-m", "venv", ".venv"], p / "analyzer")
            python = (
                p
                / "analyzer/.venv"
                / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
            )
            self.command(
                [
                    python,
                    "-m",
                    "pip",
                    "install",
                    "--require-hashes",
                    "-r",
                    "requirements.lock",
                ],
                p / "analyzer",
            )
            self.command(
                self.tool("composer") + ["install", "--no-interaction", "--no-dev"],
                p / "web",
            )
        elif slug == "survey-scoring":
            gradle = (
                self.tool("gradle")
                if "gradle" in self.tools
                else (
                    ["cmd", "/c", "gradlew.bat"]
                    if os.name == "nt"
                    else ["sh", "gradlew"]
                )
            )
            self.command(
                gradle + ["--no-daemon", "installDist"], p / "backend", timeout=1200
            )
            self.command(
                self.tool("bundle") + ["install"],
                p / "scorer",
                env={
                    "BUNDLE_FROZEN": "true",
                    "BUNDLE_PATH": str(p / "scorer/vendor/bundle"),
                },
            )
            self.npm(p / "frontend")
        elif slug in ["log-analyzer", "directory-diff", "binary-inspector"]:
            self.command(self.tool("cmake") + ["--preset", "w2l-release"], p / "native")
            self.command(
                self.tool("cmake") + ["--build", "--preset", "w2l-release-build"],
                p / "native",
            )
            if slug != "directory-diff":
                self.command(
                    self.tool("cargo") + ["build", "--locked", "--release"], p / "cli"
                )
        return p

    def npm(self, p):
        self.command(self.tool("npm") + ["ci", "--no-audit", "--no-fund"], p)
        self.command(self.tool("npm") + ["run", "build"], p)

    def free_port(self):
        with socket.socket() as sock:
            sock.bind((self.host, 0))
            return sock.getsockname()[1]

    def start_process(self, name, args, cwd, env):
        log = self.stack.enter_context((self.work / (name + ".log")).open("wb"))
        owned = OwnedProcess(
            list(map(str, args)),
            cwd=cwd,
            env={**self.env, **env},
            stdout=log,
            stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL,
        )
        self.processes[name] = owned
        self.stack.callback(owned.close)
        return owned.process

    def wait_http(self, name, path="/healthz", process=None):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if process and process.poll() is not None:
                raise RuntimeError(
                    name + " exited; see " + str(self.work / (name + ".log"))
                )
            try:
                code, _, _ = self.request(name, path)
                if code == 200:
                    return
            except OSError:
                pass
            time.sleep(0.1)
        raise TimeoutError(name + " did not become ready")

    def request(self, name, path, method="GET", body=None, headers=None):
        conn = http.client.HTTPConnection(self.host, self.ports[name], timeout=45)
        try:
            conn.request(method, path, body, headers or {})
            r = conn.getresponse()
            return r.status, {k.lower(): v for k, v in r.getheaders()}, r.read()
        finally:
            conn.close()

    def json(self, name, path, method="GET", body=None, expected=200):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        code, _, raw = self.request(
            name, path, method, data, {"Content-Type": "application/json"}
        )
        assert code == expected, (name, path, code, raw)
        return json.loads(raw)

    def start(self, slug):
        p = self.work / ("success-" + slug)
        data = self.data
        data.mkdir(parents=True, exist_ok=True)
        names = {
            "task-board": ["backend", "frontend"],
            "file-transfer": ["backend", "web"],
            "asset-lending": ["backend", "web"],
            "csv-inspector": ["analyzer", "web"],
            "survey-scoring": ["scorer", "backend", "frontend"],
        }[slug]
        self.ports = {name: self.free_port() for name in names}
        self.urls = {
            n: "http://" + self.host + ":" + str(v) for n, v in self.ports.items()
        }
        for name in names:
            env = {
                "PORT": str(self.ports[name]),
                "HOST": self.host,
                "DATA_DIR": str(data),
                "WEB_ORIGIN": self.urls.get("frontend", self.urls.get("web", "")),
            }
            if name == "frontend":
                folder = p / name / "dist"
                (folder / "runtime-config.json").write_text(
                    json.dumps({"apiBase": self.urls["backend"]}), encoding="utf-8"
                )
                handler = functools.partial(
                    http.server.SimpleHTTPRequestHandler, directory=str(folder)
                )
                server = http.server.ThreadingHTTPServer(
                    (self.host, self.ports[name]), handler
                )
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                self.stack.callback(
                    lambda s=server, t=thread: (
                        s.shutdown(),
                        s.server_close(),
                        t.join(5),
                    )
                )
                self.wait_http(name, "/")
                continue
            if slug == "task-board":
                args = self.tool("java") + ["-jar", "target/task-board-1.0.0.jar"]
            elif slug == "file-transfer" and name == "backend":
                args = [
                    p
                    / name
                    / ("file-transfer.exe" if os.name == "nt" else "file-transfer")
                ]
            elif slug == "asset-lending" and name == "backend":
                args = self.tool("dotnet") + ["publish/AssetLending.dll"]
            elif slug in ["file-transfer", "asset-lending"]:
                args = self.tool("node") + ["dist/server.js"]
                env["API_URL"] = self.urls["backend"]
            elif slug == "csv-inspector" and name == "analyzer":
                args = [
                    p
                    / name
                    / ".venv"
                    / ("Scripts/python.exe" if os.name == "nt" else "bin/python"),
                    "server.py",
                ]
            elif slug == "csv-inspector":
                args = self.tool("php") + [
                    "-S",
                    self.host + ":" + str(self.ports[name]),
                    "-t",
                    "public",
                    "router.php",
                ]
                env["ANALYZER_URL"] = self.urls["analyzer"]
            elif name == "scorer":
                args = self.tool("bundle") + ["exec", "ruby", "server.rb"]
                env["BUNDLE_PATH"] = str(p / "scorer/vendor/bundle")
                env["BUNDLE_FROZEN"] = "true"
            else:
                launcher = (
                    p
                    / name
                    / "build/install/survey-scoring/bin"
                    / ("survey-scoring.bat" if os.name == "nt" else "survey-scoring")
                )
                args = ["cmd", "/c", str(launcher)] if os.name == "nt" else [launcher]
                env["SCORER_URL"] = self.urls["scorer"]
            process = self.start_process(name, args, p / name, env)
            self.wait_http(name, process=process)
        if slug == "csv-inspector":
            self.start_process(
                "worker",
                self.tool("php") + ["worker.php"],
                p / "web",
                {"DATA_DIR": str(self.data), "ANALYZER_URL": self.urls["analyzer"]},
            )
            deadline = time.monotonic() + 15
            while True:
                worker = self.json("web", "/api/worker")
                if (
                    worker["pid"] == self.processes["worker"].process.pid
                    and worker["responsive"]
                ):
                    break
                if (
                    self.processes["worker"].process.poll() is not None
                    or time.monotonic() > deadline
                ):
                    raise RuntimeError("CSV worker did not become ready")
                time.sleep(0.05)
        return self.urls[names[-1]]

    def stop(self, name):
        self.processes[name].close()

    def run_cli(self, slug, args):
        if not args:
            raise ValueError(
                "CLI run requires --cli-args followed by the tool arguments"
            )
        p = self.work / ("success-" + slug)
        command = (
            self.tool("python") + [p / "cli/main.py"]
            if slug == "directory-diff"
            else [
                p / "cli/target/release" / (slug + (".exe" if os.name == "nt" else ""))
            ]
        )
        with OwnedProcess(
            list(map(str, command + args)), cwd=p, env=self.env
        ) as process:
            return process.wait()

    def close(self):
        for name, owned in self.processes.items():
            self.metrics.append({"component": name, **owned.metrics()})
        self.stack.close()
        self.stack = contextlib.ExitStack()
        self.processes = {}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["build", "run", "verify", "verify-all"])
    parser.add_argument("--project")
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--tools", type=Path)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--profile", choices=["quick", "standard"], default="standard")
    parser.add_argument("--cli-args", nargs=argparse.REMAINDER, default=[])
    args = parser.parse_args()
    tools = json.loads(args.tools.read_text(encoding="utf-8")) if args.tools else {}
    projects = (
        [
            p["id"]
            for p in json.loads((FIXTURES / "matrix.json").read_text(encoding="utf-8"))
        ]
        if args.action == "verify-all"
        else [args.project]
    )
    if not all(projects):
        parser.error("--project is required")
    catalog = {
        p["id"]: p
        for p in json.loads((FIXTURES / "matrix.json").read_text(encoding="utf-8"))
    }
    if any(slug not in catalog for slug in projects):
        parser.error("Unknown project")
    reports = []
    for slug in projects:
        runner = Runner(args.work / slug, tools, args.host, args.profile)
        try:
            runner.versions(slug)
            runner.build(slug)
            if args.action in ["verify", "verify-all"]:
                from polyglot_verify import verify

                reports.append(verify(runner, slug))
            elif args.action == "run":
                if catalog[slug]["kind"] == "cli":
                    code = runner.run_cli(slug, args.cli_args)
                    if code:
                        raise SystemExit(code)
                else:
                    print(runner.start(slug), flush=True)
                    while True:
                        time.sleep(1)
        finally:
            runner.close()
            (runner.work / "commands.json").write_text(
                json.dumps(runner.commands, indent=2), encoding="utf-8"
            )
    (args.work / "results.json").write_text(
        json.dumps(reports, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(reports, ensure_ascii=False))


if __name__ == "__main__":
    main()
