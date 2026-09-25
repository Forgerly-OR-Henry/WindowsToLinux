"""Install pinned formatting tools in an ignored project cache. / 将固定版本格式工具安装到忽略的项目缓存。"""

from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile

BASE = Path(__file__).resolve().parent
CACHE = BASE / "target"
MANIFEST = json.loads((BASE / "tools.json").read_text(encoding="utf-8"))


def run(command, **kwargs):
    """Fail when a development tool installation fails. / 开发工具安装失败时终止。"""
    subprocess.run([str(value) for value in command], check=True, **kwargs)


def download(item):
    """Fetch a versioned artifact and verify its recorded hash. / 获取版本化工具并验证记录的摘要。"""
    name, url = item
    target = CACHE / name
    if not target.exists():
        print(f"Downloading {name}", flush=True)
        request = urllib.request.Request(url, headers={"User-Agent": "WindowsToLinux-formatting"})
        with (
            urllib.request.urlopen(request, timeout=120) as source,
            target.with_suffix(".download").open("wb") as output,
        ):
            shutil.copyfileobj(source, output)
        target.with_suffix(".download").replace(target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    expected = MANIFEST["sha256"].get(name)
    if expected and expected != digest:
        raise RuntimeError(f"Formatting tool checksum mismatch: {name}")
    return name, digest


def main():
    """Prepare tools explicitly, never from a read-only format check. / 显式准备工具，只读检查不自动安装。"""
    if sys.version_info[:2] != (3, 12):
        raise SystemExit("Formatting bootstrap requires Python 3.12")
    CACHE.mkdir(exist_ok=True)
    with ThreadPoolExecutor(max_workers=4) as pool:
        digests = dict(pool.map(download, MANIFEST["downloads"].items()))
    print(json.dumps(digests, indent=2), flush=True)
    python = CACHE / "python" / "Scripts" / "python.exe"
    if not python.exists():
        run([sys.executable, "-m", "venv", CACHE / "python"])
    run([python, "-m", "pip", "install", "--disable-pip-version-check", "-r", BASE / "requirements.txt"])
    if not (CACHE / "php" / "php.exe").exists():
        with zipfile.ZipFile(CACHE / "php.zip") as archive:
            archive.extractall(CACHE / "php")
    ruby = CACHE / "ruby" / MANIFEST["ruby"] / "bin" / "ruby.exe"
    if not ruby.exists():
        run(
            [
                python,
                "-c",
                "import py7zr,sys; a=py7zr.SevenZipFile(sys.argv[1]); a.extractall(sys.argv[2]); a.close()",
                CACHE / "ruby.7z",
                CACHE / "ruby",
            ]
        )
    env = dict(
        os.environ,
        GEM_HOME=str(CACHE / ("gems-" + MANIFEST["rubocop"])),
        GEM_PATH=str(CACHE / ("gems-" + MANIFEST["rubocop"]))
        + os.pathsep
        + str(CACHE / "ruby" / MANIFEST["ruby"] / "lib/ruby/gems/3.4.0"),
    )
    env["PATH"] = str(ruby.parent) + os.pathsep + env.get("PATH", "")
    for name, version in MANIFEST["gems"].items():
        run(
            [
                ruby,
                ruby.parent / "gem",
                "install",
                name,
                "--version",
                version,
                "--ignore-dependencies",
                "--no-document",
            ],
            env=env,
        )
    npm = shutil.which("npm.cmd")
    if not npm:
        raise RuntimeError("Node.js/npm is required for Prettier")
    run(
        [
            npm,
            "ci" if (BASE / "package-lock.json").exists() else "install",
            "--ignore-scripts",
            "--no-audit",
            "--no-fund",
        ],
        cwd=BASE,
    )
    print("Formatting tools are ready.", flush=True)


if __name__ == "__main__":
    main()
