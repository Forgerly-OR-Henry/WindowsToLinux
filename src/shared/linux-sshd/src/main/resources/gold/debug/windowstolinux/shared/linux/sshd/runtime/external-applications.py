import base64
import hashlib
import os
import re
import shutil
import stat
import subprocess
import sys
import time

UNIT = re.compile(r'[A-Za-z0-9_][A-Za-z0-9_.:@\\-]{0,240}\.service\Z')
CONTAINER = re.compile(r'[a-f0-9]{64}\Z')
DEADLINE = time.monotonic() + 90
LIMIT = 512


def run(args, timeout=8):
    if time.monotonic() >= DEADLINE:
        raise TimeoutError('scan bound reached')
    result = subprocess.run(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout, env={**os.environ, 'LC_ALL': 'C', 'SYSTEMD_COLORS': '0'})
    if len(result.stdout) > 1048576:
        raise ValueError('output bound exceeded')
    if result.returncode:
        if b'Permission denied' in result.stderr or b'permission denied' in result.stderr or b'Access denied' in result.stderr:
            raise PermissionError('permission denied')
        raise RuntimeError('runtime command failed')
    return result.stdout.decode('utf-8', 'strict').rstrip('\r\n')


def text(value):
    return base64.b64encode(value.encode('utf-8')).decode('ascii')


def systemd(unit):
    if not UNIT.fullmatch(unit) or '@.service' in unit:
        raise ValueError('unsupported unit')
    props = ('Id', 'Description', 'ActiveState', 'SubState', 'FragmentPath', 'DropInPaths', 'LoadState', 'NeedDaemonReload', 'CanStart', 'CanStop')
    output = run(['systemctl', 'show', '--no-pager', '--property=' + ','.join(props), '--', unit])
    values = dict(line.split('=', 1) for line in output.splitlines() if '=' in line)
    identity = values.get('Id', '')
    if not UNIT.fullmatch(identity) or values.get('LoadState') != 'loaded' or values.get('NeedDaemonReload') == 'yes':
        raise ValueError('unit is not a stable loaded definition')
    paths = [values.get('FragmentPath', '')] + values.get('DropInPaths', '').split()
    digest = hashlib.sha256(identity.encode())
    for path in paths:
        if not path.startswith(('/etc/systemd/', '/usr/lib/systemd/', '/lib/systemd/', '/run/systemd/')):
            raise ValueError('unsupported unit location')
        with open(path, 'rb') as source:
            before = os.fstat(source.fileno())
            if not stat.S_ISREG(before.st_mode) or before.st_size > 1048576:
                raise ValueError('unbounded unit definition')
            contents = source.read(1048577)
            after = os.fstat(source.fileno())
            if (before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns):
                raise ValueError('unit changed while scanning')
            digest.update(path.encode() + b'\0' + str((before.st_dev, before.st_ino)).encode() + b'\0' + contents)
    active = values.get('ActiveState')
    state = 'RUNNING' if active == 'active' else 'STOPPED' if active == 'inactive' else 'ERROR' if active == 'failed' else 'UNKNOWN'
    return ('SYSTEMD', identity, digest.hexdigest(), values.get('Description') or identity, state,
            values.get('CanStart') == 'yes', values.get('CanStop') == 'yes', identity.startswith('windowstolinux-'))


def docker(identity):
    if not CONTAINER.fullmatch(identity):
        raise ValueError('full container identity required')
    template = '{{.Id}}\t{{.Name}}\t{{.State.Status}}\t{{index .Config.Labels "io.windowstolinux.application"}}\t{{index .Config.Labels "io.windowstolinux.owner"}}'
    fields = run(['docker', 'container', 'inspect', '--format', template, identity]).split('\t')
    if len(fields) != 5 or fields[0] != identity:
        raise ValueError('container identity differs')
    state = 'RUNNING' if fields[2] == 'running' else 'STOPPED' if fields[2] in ('exited', 'created') else 'ERROR' if fields[2] == 'dead' else 'UNKNOWN'
    name = fields[1].lstrip('/')
    managed = name.startswith('windowstolinux-') or any(value not in ('', '<no value>') for value in fields[3:])
    return ('DOCKER', identity, identity, name, state, True, True, managed)


def emit(app):
    kind, identity, fingerprint, name, state, start, stop, managed = app
    name = ''.join(char for char in name if char.isprintable())[:240] or identity
    print('\t'.join(('APP', kind, text(identity), fingerprint, text(name), state, str(int(start)), str(int(stop)), str(int(managed)))))


def scan():
    found, issues = {}, set()
    if shutil.which('systemctl'):
        try:
            units = set()
            for command in ('list-unit-files', 'list-units'):
                try:
                    output = run(['systemctl', command, '--all', '--type=service', '--no-legend', '--no-pager', '--plain'])
                    units.update(line.split()[0] for line in output.splitlines() if line.split())
                except PermissionError:
                    issues.add('SYSTEMD_PERMISSION')
                except (RuntimeError, subprocess.TimeoutExpired, TimeoutError):
                    issues.add('SYSTEMD_PARTIAL')
            for unit in sorted(units):
                if '@.service' in unit or not UNIT.fullmatch(unit):
                    continue
                try:
                    app = systemd(unit)
                    found[app[0:2]] = app
                except PermissionError:
                    issues.add('SYSTEMD_PERMISSION')
                except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired, TimeoutError):
                    issues.add('SYSTEMD_PARTIAL')
                if len(found) >= LIMIT or time.monotonic() >= DEADLINE:
                    issues.add('LIMIT_REACHED')
                    break
        except (OSError, ValueError):
            issues.add('SYSTEMD_PARTIAL')
    else:
        issues.add('SYSTEMD_UNAVAILABLE')
    if shutil.which('docker'):
        try:
            for identity in run(['docker', 'container', 'ls', '--all', '--quiet', '--no-trunc']).splitlines():
                if len(found) >= LIMIT or time.monotonic() >= DEADLINE:
                    issues.add('LIMIT_REACHED')
                    break
                try:
                    app = docker(identity)
                    found[app[0:2]] = app
                except PermissionError:
                    issues.add('DOCKER_PERMISSION')
                except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired, TimeoutError):
                    issues.add('DOCKER_PARTIAL')
        except PermissionError:
            issues.add('DOCKER_PERMISSION')
        except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired, TimeoutError):
            issues.add('DOCKER_PARTIAL')
    else:
        issues.add('DOCKER_UNAVAILABLE')
    for app in found.values():
        emit(app)
    for issue in sorted(issues):
        print('ISSUE\t' + issue)
    print('END\t' + str(len(found)))


def execute(kind, identity, fingerprint, action):
    inspect = systemd if kind == 'SYSTEMD' else docker if kind == 'DOCKER' else None
    if inspect is None or action not in ('REFRESH_STATUS', 'START', 'STOP', 'RESTART'):
        raise ValueError('unsupported application operation')
    app = inspect(identity)
    if app[1] != identity or app[2] != fingerprint:
        print('ERROR\tIDENTITY_CHANGED')
        return 3
    if action != 'REFRESH_STATUS':
        if app[7]:
            print('ERROR\tMANAGED_OWNERSHIP_REQUIRED')
            return 3
        if (action in ('START', 'RESTART') and not app[5]) or (action in ('STOP', 'RESTART') and not app[6]):
            raise ValueError('operation unsupported by runtime')
        if kind == 'SYSTEMD':
            command = ['systemctl', '--no-ask-password', action.lower(), '--', identity]
            if os.geteuid() != 0:
                command = ['sudo', '-n'] + command
        else:
            command = ['docker', 'container', action.lower(), identity]
        run(command, 45)
        app = inspect(identity)
        if app[2] != fingerprint:
            print('ERROR\tIDENTITY_CHANGED')
            return 3
    emit(app)
    print('END\t1')
    return 0


if __name__ == '__main__':
    try:
        if sys.argv[1:] == ['SCAN']:
            scan()
        elif len(sys.argv) == 5:
            sys.exit(execute(*sys.argv[1:]))
        else:
            raise ValueError('invalid protocol arguments')
    except PermissionError:
        print('ERROR\tPERMISSION_DENIED')
        sys.exit(4)
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired, TimeoutError):
        print('ERROR\tRUNTIME_UNAVAILABLE')
        sys.exit(5)
