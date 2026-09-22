"""Copy a built fixture, remove/alter required inputs, and require a specific failure.

The original fixture is never edited. Commands run directly (without a shell).
Use {fixture} in command arguments to refer to the disposable copy.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile


def verify(source, work_parent, removals, replacements, command, expected, timeout=90):
    source = Path(source).resolve()
    work_parent = Path(work_parent).resolve()
    if not source.is_dir() or not work_parent.is_dir():
        raise ValueError('source and work parent must be existing directories')
    if work_parent == source or source in work_parent.parents:
        raise ValueError('temporary copies must be outside the source tree')
    with tempfile.TemporaryDirectory(prefix='fixture-negative-', dir=work_parent) as temporary:
        root = Path(temporary) / 'fixture'
        shutil.copytree(source, root, symlinks=True, ignore=shutil.ignore_patterns('.git', '__pycache__'))

        def target(relative):
            path = root / relative
            if path == root or root not in path.resolve().parents:
                raise ValueError('mutation must stay inside the temporary fixture: ' + relative)
            return path

        for relative in removals:
            path = target(relative)
            if path.is_symlink() or path.is_file():
                path.unlink()
            elif path.is_dir():
                shutil.rmtree(path)
            else:
                raise FileNotFoundError(path)
        for relative, old, new in replacements:
            path = target(relative)
            content = path.read_text(encoding='utf-8')
            if old not in content:
                raise ValueError('replacement did not match: ' + relative)
            path.write_text(content.replace(old, new), encoding='utf-8')
        process = subprocess.Popen(
            [part.replace('{fixture}', str(root)) for part in command],
            cwd=root,
            env=dict(os.environ, CI='true'),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            start_new_session=os.name != 'nt',
        )
        try:
            stdout, _ = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            if os.name == 'nt':
                subprocess.run(['taskkill.exe', '/PID', str(process.pid), '/T', '/F'], capture_output=True, timeout=10)
            else:
                os.killpg(process.pid, signal.SIGKILL)
            process.communicate(timeout=10)
            raise AssertionError('verification command timed out; timeout is not an expected build failure')
        output = stdout.decode('utf-8', errors='replace')
        if process.returncode == 0 or expected.lower() not in output.lower():
            raise AssertionError((process.returncode, expected, output[-4000:]))
        return {
            'status': 'passed',
            'exit_code': process.returncode,
            'removed': removals,
            'changed': [item[0] for item in replacements],
            'expected_diagnostic': expected,
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--work-parent', type=Path, required=True)
    parser.add_argument('--remove', action='append', default=[])
    parser.add_argument('--replace', nargs=3, action='append', default=[], metavar=('FILE', 'OLD', 'NEW'))
    parser.add_argument('--expect', required=True)
    parser.add_argument('--timeout', type=int, default=90)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command and args.command[0] == '--' else args.command
    if not command or not (args.remove or args.replace):
        parser.error('provide a mutation and a direct verification command after --')
    print(
        json.dumps(verify(args.source, args.work_parent, args.remove, args.replace, command, args.expect, args.timeout))
    )


if __name__ == '__main__':
    main()
