"""Check or format the main project with IntelliJ IDEA. / 使用 IDEA 检查或格式化主项目。"""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

BASE = Path(__file__).resolve().parent
ROOT = BASE.parent.parent
CACHE = ROOT / "target" / "formatting" / "idea"
SUFFIXES = {
    ".java", ".mjs", ".xml", ".svg", ".json", ".toml", ".properties", ".sql",
}
GENERATED = {"target", "node_modules", ".git", ".idea", "__pycache__", ".venv", ".gradle", "vendor"}
EXCLUDED_NAMES = {"package-lock.json", "pnpm-lock.yaml", "yarn.lock"}
RETAINED_FILES = {
    "src/mod/deployment/src/test/resources/original-codec-goldens.properties",
    "src/mod/deployment/src/test/resources/original-format-provenance.md",
}
PROJECT_FILES = {"pom.xml", ".mvn/wtlmod-author-parent/pom.xml", ".mvn/formatting/idea.xml"}


def in_scope(name):
    """Exclude root examples, keeping module tests. / 排除根目录样例，保留模块测试。"""
    path = Path(name)
    return (
        (name.startswith("src/") or name in PROJECT_FILES)
        and path.suffix.lower() in SUFFIXES
        and path.name not in EXCLUDED_NAMES
        and name not in RETAINED_FILES
        and not GENERATED.intersection(path.parts)
    )


def inventory():
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT, check=True, capture_output=True,
    )
    return [
        ROOT / name
        for name in sorted(set(result.stdout.decode("utf-8").split("\0")))
        if in_scope(name) and (ROOT / name).is_file() and not (ROOT / name).is_symlink()
    ]


def idea_installation():
    configured = os.environ.get("IDEA_HOME")
    launcher = shutil.which("idea64.exe")
    if not configured and not launcher:
        raise RuntimeError("Set IDEA_HOME to the IntelliJ IDEA installation directory or add idea64.exe to PATH.")
    home = Path(configured).resolve() if configured else Path(launcher).resolve().parent.parent
    info = json.loads((home / "product-info.json").read_text(encoding="utf-8"))
    launch = next((item for item in info["launch"] if item["os"] == "Windows" and item["arch"] == "amd64"), None)
    if launch is None or not (home / launch["javaExecutablePath"]).is_file():
        raise RuntimeError("The formatter requires a Windows x64 IntelliJ IDEA installation with its bundled runtime.")
    return home, info, launch


def idea(paths):
    home, info, launch = idea_installation()
    print(f"IntelliJ IDEA {info['version']} ({info['buildNumber']}): {len(paths)} files", flush=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    (CACHE / "tmp").mkdir(exist_ok=True)
    # Use launcher metadata and an argument file to avoid Windows' command-length limit.
    # 使用安装包的启动参数和参数文件，避免 Windows 命令行长度限制。
    args = [
        line.strip()
        for line in (home / launch["vmOptionsFilePath"]).read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    args += [arg.replace("%IDE_HOME%", str(home)) for arg in launch["additionalJvmArguments"]]
    args += [f"-Didea.{key}.path={CACHE / key}" for key in ("config", "system", "plugins", "log")]
    args += [
        f"-Djava.io.tmpdir={CACHE / 'tmp'}",
        f"-XX:ErrorFile={CACHE / 'java_error_%p.log'}",
        f"-XX:HeapDumpPath={CACHE / 'java_error.hprof'}",
        "-Duser.language=en", "-Duser.country=US",
        "-cp", os.pathsep.join(str(home / "lib" / name) for name in launch["bootClassPathJarNames"]),
        launch["mainClass"], "format", "-s", str(BASE / "idea.xml"), "-charset", "UTF-8",
    ]
    args += [str(path) for path in paths]
    env = dict(os.environ, TEMP=str(CACHE / "tmp"), TMP=str(CACHE / "tmp"))
    env.pop("IDEA_PROPERTIES", None)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", suffix=".args", dir=CACHE, delete=False) as file:
        file.write("\n".join(json.dumps(arg, ensure_ascii=False) for arg in args))
        argument_file = Path(file.name)
    try:
        result = subprocess.run(
            [str(home / launch["javaExecutablePath"]), "@" + str(argument_file)],
            cwd=ROOT, env=env, capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW,
        )
    finally:
        argument_file.unlink(missing_ok=True)
    output = result.stdout.decode("utf-8", errors="replace")
    (CACHE / "last-run.log").write_text(output + result.stderr.decode("utf-8", errors="replace"), encoding="utf-8")
    for line in output.splitlines():
        if not line.startswith(("Checking ", "Formatting ")) or not line.endswith(("...Formatted well", "...OK")):
            print(line, flush=True)
    count = re.search(r"(?m)^(\d+) file\(s\) formatted\.", output)
    if not count or int(count.group(1)) != len(paths):
        raise RuntimeError(f"IDEA did not process all selected files; see {CACHE / 'last-run.log'}")
    if result.returncode:
        raise RuntimeError(f"IDEA formatting failed ({result.returncode}); see {CACHE / 'last-run.log'}")


def format_paths(paths, write):
    before = {path: path.read_bytes() for path in paths}
    CACHE.mkdir(parents=True, exist_ok=True)
    try:
        # IDEA dry-run can disagree with its writer; both modes use real formatting on copies.
        # 两种模式共用实际写入引擎；副本保留目录层级和 EditorConfig，检查不触碰源码。
        with tempfile.TemporaryDirectory(prefix="files-", dir=CACHE) as directory:
            mirror = Path(directory)
            copies = {}
            configs = set()
            for path, content in before.items():
                target = mirror / path.relative_to(ROOT)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
                copies[path] = target
                configs.update(
                    parent / ".editorconfig" for parent in path.parents
                    if parent.is_relative_to(ROOT) and (parent / ".editorconfig").is_file()
                )
            for config in configs:
                target = mirror / config.relative_to(ROOT)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(config, target)
            idea(list(copies.values()))
            concurrent = [str(path) for path, content in before.items() if path.read_bytes() != content]
            if concurrent:
                raise RuntimeError("Sources changed during formatting; retry after edits finish: " + ", ".join(concurrent))
            failures = []
            for path, target in copies.items():
                normalized = target.read_bytes().replace(b"\r\n", b"\n")
                if normalized and not normalized.endswith(b"\n"):
                    normalized += b"\n"
                if before[path] != normalized:
                    if write:
                        path.write_bytes(normalized)
                    else:
                        failures.append(str(path.relative_to(ROOT)))
            if failures:
                raise RuntimeError("Files need formatting (including LF/final newline):\n" + "\n".join(failures))
    finally:
        if not write:
            changed = [str(path) for path, content in before.items() if path.read_bytes() != content]
            if changed:
                raise RuntimeError("Sources changed during check: " + ", ".join(changed))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--list", action="store_true", help="List the exact scope without starting IDEA")
    parser.add_argument("--only", choices=["java"], help="Restrict to Java production and test sources")
    args = parser.parse_args()
    paths = inventory()
    if args.only == "java":
        paths = [path for path in paths if path.suffix == ".java"]
    if args.list:
        print("\n".join(path.relative_to(ROOT).as_posix() for path in paths))
        return
    if not paths:
        raise RuntimeError("No main-project files selected; refusing to report a successful check.")
    format_paths(paths, args.write)
    print("Formatting complete." if args.write else "All main-project formatting checks passed.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
