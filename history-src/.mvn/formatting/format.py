"""Check or format all maintained source with pinned tools. / 使用固定工具检查或格式化全部维护源码。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import textwrap

BASE = Path(__file__).resolve().parent
ROOT = BASE.parent.parent
CACHE = BASE / "target"
TOOLS = json.loads((BASE / "tools.json").read_text(encoding="utf-8"))
EXCEPTIONS = json.loads((BASE / "exceptions.json").read_text(encoding="utf-8"))


def command(args, *, cwd=ROOT, env=None, capture=False, allowed=(0,), data=None):
    """Run one checked tool without an interpolated shell. / 不经字符串拼接的 Shell 执行受检工具。"""
    result = subprocess.run([str(value) for value in args], cwd=cwd, env=env, capture_output=capture, input=data)
    if result.returncode not in allowed:
        if capture:
            sys.stderr.buffer.write(result.stdout + result.stderr)
        raise RuntimeError(f"Formatting command failed ({result.returncode}): {args[0]}")
    return result


def executable(name):
    """Resolve a required tool and report missing prerequisites. / 定位必需工具并报告缺失依赖。"""
    found = shutil.which(name)
    if not found:
        raise RuntimeError(f"Required formatting tool is missing: {name}; see .mvn/formatting/README.md")
    return found


def required(path):
    """Require an explicitly installed cached tool. / 要求已显式安装的缓存工具。"""
    if not path.is_file():
        raise RuntimeError(f"Missing formatting tool: {path.name}; run python .mvn/formatting/bootstrap.py")
    return path


def inventory():
    """Use Git inventory, excluding only named generated or retained content. / 使用 Git 清单，仅排除登记的生成或保留内容。"""
    result = command(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], capture=True)
    names = sorted(set(result.stdout.decode("utf-8").split("\0")))
    paths = []
    for name in names:
        path = ROOT / name
        if not name or not path.is_file() or path.is_symlink():
            continue
        if name in EXCEPTIONS["files"] or path.name in EXCEPTIONS["excludedNames"]:
            continue
        if path.suffix.lower() in EXCEPTIONS["excludedSuffixes"]:
            continue
        if any(part in EXCEPTIONS["generatedSegments"] for part in Path(name).parts):
            continue
        paths.append(path)
    return paths


def batches(paths, size=20):
    """Bound Windows command-line length. / 限制 Windows 命令行长度。"""
    for offset in range(0, len(paths), size):
        yield paths[offset : offset + size]


def java(paths, write):
    """Use the root reactor's fixed JDT and import rules. / 使用根工程固定的 JDT 及导入规则。"""
    command(
        [
            executable("mvn.cmd"),
            "-B",
            "-ntp",
            "com.diffplug.spotless:spotless-maven-plugin:2.44.3:" + ("apply" if write else "check"),
        ]
    )


def web(paths, write):
    """Preserve embedded languages while formatting Web and XML. / 格式化 Web 及 XML 并保留嵌入语言。"""
    tool = required(BASE / "node_modules" / "prettier" / "bin" / "prettier.cjs")
    for batch in batches(paths):
        command(
            [executable("node"), tool, "--config", BASE / "prettier.json", "--write" if write else "--check", *batch],
            cwd=BASE,
        )


def python(paths, write):
    """Format Python without lint fixes. / 格式化 Python，不执行 lint 修复。"""
    tool = required(CACHE / "python" / "Scripts" / "ruff.exe")
    fragments = [path for path in paths if path.name == "centos-source-repositories.py"]
    for path in fragments:
        original = path.read_bytes()
        source = textwrap.dedent(original.decode("utf-8")).encode()
        formatted = command(
            [tool, "format", "--config", BASE / "ruff.toml", "--stdin-filename", path, "-"], capture=True, data=source
        ).stdout.decode()
        result = textwrap.indent(formatted, " " * 8).encode()
        if write:
            path.write_bytes(result)
        elif result != original:
            raise RuntimeError(f"Python resource fragment needs formatting: {path}")
    for batch in batches([path for path in paths if path not in fragments]):
        command([tool, "format", "--config", BASE / "ruff.toml", *([] if write else ["--check"]), *batch])


def kotlin(paths, write):
    """Apply the fixed four-space Kotlin formatter. / 使用固定的四空格 Kotlin 格式工具。"""
    for batch in batches(paths):
        command(
            [
                executable("java"),
                "-jar",
                required(CACHE / "ktfmt.jar"),
                "--kotlinlang-style",
                *([] if write else ["--dry-run", "--set-exit-if-changed"]),
                *batch,
            ]
        )


def shell(paths, write):
    """Format shell syntax without simplifying scripts or heredocs. / 格式化 Shell 语法，不简化脚本或 heredoc。"""
    first = next(path for path in paths if path.name == "10-native-instances.sh")
    second = first.with_name("20-native-targets.sh")
    marker = "# WTL_FORMAT_FRAGMENT_BOUNDARY\n"
    original = first.read_text(encoding="utf-8") + marker + second.read_text(encoding="utf-8")
    options = [required(CACHE / "shfmt.exe"), "-ln", "bash", "-i", "4", "-bn", "-ci"]
    result = command(options, capture=True, data=original.encode()).stdout.decode()
    if result.count(marker) != 1:
        raise RuntimeError("Shell resource fragment boundary lost")
    if write:
        left, right = result.split(marker)
        first.write_bytes(left.encode())
        second.write_bytes(right.encode())
    elif result != original:
        raise RuntimeError("Combined native database shell fragments need formatting")
    for batch in batches([path for path in paths if path not in (first, second)]):
        command([*options, "-w" if write else "-d", *batch])


def native(paths, write):
    """Apply syntax-aware C and C++ spacing. / 应用语法感知的 C 和 C++ 空白规则。"""
    tool = required(CACHE / "python" / "Scripts" / "clang-format.exe")
    for batch in batches(paths):
        command([tool, f"-style=file:{BASE / 'clang.yaml'}", *(["-i"] if write else ["--dry-run", "--Werror"]), *batch])


def csharp(paths, write):
    """Check whitespace only, avoiding analyzer-driven changes. / 仅检查空白，避免分析器驱动的改动。"""
    tool = executable("dotnet")
    version = command([tool, "--version"], capture=True).stdout.decode().strip()
    if version != TOOLS["dotnet"]:
        raise RuntimeError(f"Expected .NET SDK {TOOLS['dotnet']}, got {version}")
    command(
        [
            tool,
            "format",
            "whitespace",
            ROOT / "test",
            "--folder",
            "--include",
            *paths,
            *([] if write else ["--verify-no-changes"]),
            "--verbosity",
            "quiet",
        ]
    )


def go(paths, write):
    """Use the pinned Go toolchain's formatter. / 使用固定 Go 工具链的格式工具。"""
    version = command([executable("go"), "version"], capture=True).stdout.decode()
    if TOOLS["gofmt"] not in version:
        raise RuntimeError(f"Expected Go {TOOLS['gofmt']}")
    for path in paths:
        if path.name == "go.mod":
            result = command([executable("go"), "mod", "edit", "-print", path], capture=True).stdout
            if write:
                path.write_bytes(result)
            elif result != path.read_bytes():
                raise RuntimeError(f"Go module formatting differs: {path}")
    for batch in batches([path for path in paths if path.suffix == ".go"]):
        result = command([executable("gofmt"), "-w" if write else "-l", *batch], capture=True)
        if not write and result.stdout.strip():
            sys.stdout.buffer.write(result.stdout)
            raise RuntimeError("Go formatting differs")


def rust(paths, write):
    """Use each fixture's declared edition without changing module discovery. / 使用样例声明版本，不改变模块发现。"""
    tool = executable("rustfmt")
    if TOOLS["rustfmt"] not in command([tool, "--version"], capture=True).stdout.decode():
        raise RuntimeError(f"Expected rustfmt {TOOLS['rustfmt']}")
    import tomllib

    for path in paths:
        manifest = next((parent / "Cargo.toml" for parent in path.parents if (parent / "Cargo.toml").is_file()), None)
        edition = (
            str(tomllib.loads(manifest.read_text(encoding="utf-8")).get("package", {}).get("edition", "2015"))
            if manifest
            else "2021"
        )
        command(
            [
                tool,
                "--edition",
                edition,
                "--config",
                "max_width=120,skip_children=true",
                *([] if write else ["--check"]),
                path,
            ]
        )


def php(paths, write):
    """Apply only the explicitly configured PHP whitespace rules. / 仅应用明确配置的 PHP 空白规则。"""
    tool = required(CACHE / "php" / "php.exe")
    for batch in batches(paths):
        command(
            [
                tool,
                required(CACHE / "php-cs-fixer.phar"),
                "fix",
                f"--config={BASE / 'php.php'}",
                "--using-cache=no",
                "--sequential",
                *([] if write else ["--dry-run"]),
                *batch,
            ]
        )


def ruby(paths, write):
    """Restrict RuboCop to layout checks and safe layout corrections. / 将 RuboCop 限制为布局检查及安全布局修正。"""
    interpreter = CACHE / "ruby" / TOOLS["ruby"] / "bin" / "ruby.exe"
    if not interpreter.exists():
        raise RuntimeError("Ruby formatting tools are missing; run bootstrap.py")
    env = dict(
        os.environ,
        GEM_HOME=str(CACHE / ("gems-" + TOOLS["rubocop"])),
        GEM_PATH=str(CACHE / ("gems-" + TOOLS["rubocop"]))
        + os.pathsep
        + str(CACHE / "ruby" / TOOLS["ruby"] / "lib/ruby/gems/3.4.0"),
    )
    env["PATH"] = str(interpreter.parent) + os.pathsep + env.get("PATH", "")
    for batch in batches(paths):
        command(
            [
                interpreter,
                required(CACHE / ("gems-" + TOOLS["rubocop"]) / "bin" / "rubocop"),
                "--config",
                BASE / "rubocop.yml",
                "--only",
                "Layout",
                "--cache",
                "false",
                *(["--autocorrect"] if write else []),
                *batch,
            ],
            env=env,
        )


def basic(paths, write):
    """Normalize safe file boundaries without rewriting embedded values. / 规范安全文件边界，不改写嵌入值。"""
    differences = []
    for path in paths:
        data = path.read_bytes()
        text = data.decode("utf-8-sig")
        normalized = text.replace("\r\n", "\n")
        if path.name in {".gitignore", ".gitattributes", ".editorconfig"}:
            normalized = "\n".join(line.rstrip() for line in normalized.split("\n"))
        normalized = normalized.rstrip("\n") + "\n" if normalized else ""
        if path.suffix.lower() in {".bat", ".cmd"}:
            normalized = normalized.replace("\n", "\r\n")
        encoded = normalized.encode("utf-8")
        if encoded != data:
            differences.append(str(path.relative_to(ROOT)))
            if write:
                path.write_bytes(encoded)
    if differences and not write:
        raise RuntimeError("File boundary formatting differs: " + ", ".join(differences))


FORMATTERS = {
    "java": ({".java", ".gradle"}, java),
    "web": (
        {
            ".js",
            ".mjs",
            ".ts",
            ".vue",
            ".html",
            ".css",
            ".json",
            ".xml",
            ".csproj",
            ".config",
            ".yaml",
            ".yml",
            ".md",
            ".svg",
        },
        web,
    ),
    "python": ({".py"}, python),
    "kotlin": ({".kt", ".kts"}, kotlin),
    "shell": ({".sh"}, shell),
    "native": ({".c", ".cpp", ".h", ".hpp"}, native),
    "csharp": ({".cs"}, csharp),
    "go": ({".go", ".mod"}, go),
    "rust": ({".rs"}, rust),
    "php": ({".php"}, php),
    "ruby": ({".rb", ".ru"}, ruby),
    "basic": ({".properties", ".toml", ".sql", ".bat", ".cmd", ".gitignore", ".in"}, basic),
}


def main():
    """Expose explicit check/write modes and verify checks never rewrite sources. / 提供显式检查及写入模式，并验证检查不会改写源码。"""
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--only", choices=FORMATTERS)
    args = parser.parse_args()
    paths = inventory()
    before = {path: hashlib.sha256(path.read_bytes()).digest() for path in paths} if args.check else {}
    failures = []
    selected_paths = set()
    for name, (suffixes, formatter) in FORMATTERS.items():
        if args.only and args.only != name:
            continue
        named = {
            "shell": {"gradlew"},
            "ruby": {"Gemfile"},
            "basic": {
                "Pipfile",
                "CMakeLists.txt",
                "requirements.txt",
                ".editorconfig",
                ".gitattributes",
                ".gitignore",
                ".ruby-version",
                "Makefile",
            },
        }
        selected = [path for path in paths if path.suffix.lower() in suffixes or path.name in named.get(name, set())]
        if not selected:
            continue
        selected_paths.update(selected)
        print(f"{name}: {len(selected)} files", flush=True)
        try:
            formatter(selected, args.write)
        except (RuntimeError, OSError) as failure:
            failures.append(f"{name}: {failure}")
    for path in sorted(selected_paths):
        original = path.read_bytes()
        normalized = original.replace(b"\r\n", b"\n")
        if normalized and not normalized.endswith(b"\n"):
            normalized += b"\n"
        if path.suffix.lower() in {".bat", ".cmd"}:
            normalized = normalized.replace(b"\n", b"\r\n")
        if original != normalized:
            if args.write:
                path.write_bytes(normalized)
            else:
                failures.append(f"Line endings or final newline differ: {path.relative_to(ROOT)}")
    if args.check:
        changed = [
            str(path.relative_to(ROOT))
            for path, digest in before.items()
            if hashlib.sha256(path.read_bytes()).digest() != digest
        ]
        if changed:
            failures.append("Check mode modified files: " + ", ".join(changed))
    if failures:
        raise SystemExit("\n".join(failures))
    print("Formatting complete." if args.write else "All formatting checks passed.")


if __name__ == "__main__":
    main()
