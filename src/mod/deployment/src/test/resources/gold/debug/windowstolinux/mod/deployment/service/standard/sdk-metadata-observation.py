"""SDK installation-tree metadata only; never execute an SDK or establish READY."""
import json
import hashlib
import os
import re
import stat
import sys

ROOT = '/usr/local/lib/windowstolinux/toolchains/versions'
FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
REQUIRED = {'ecosystem', 'version', 'directory', 'origin', 'source', 'sha256'}
FIELDS = REQUIRED | {'official_algorithm', 'official_digest', 'tree_sha256', 'agent_program_sha256', 'system_files', 'dependencies'}


def require(value):
    if not value:
        raise ValueError('UNTRUSTED_OR_CHANGED_METADATA')


def identity(info):
    return tuple(getattr(info, key) for key in ('st_dev', 'st_ino', 'st_mode', 'st_uid', 'st_gid', 'st_nlink', 'st_size', 'st_mtime_ns', 'st_ctime_ns'))


def trusted(info, directory):
    require((stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode))
            and info.st_uid == 0 and info.st_gid == 0 and not info.st_mode & 0o022)
    if not directory:
        require(info.st_nlink == 1 and 0 <= info.st_size <= 65536)


def open_directory(name, parent=None):
    fd = os.open(name, FLAGS, dir_fd=parent)
    try:
        trusted(os.fstat(fd), True)
        return fd
    except Exception:
        os.close(fd)
        raise


def marker(directory):
    try:
        fd = os.open('.w2l-toolchain.json', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=directory)
    except FileNotFoundError:
        return None
    try:
        before = os.fstat(fd)
        trusted(before, False)
        chunks, size = [], 0
        while size <= 65536:
            chunk = os.read(fd, min(4096, 65537 - size))
            if not chunk:
                break
            chunks.append(chunk)
            size += len(chunk)
        require(size == before.st_size and size <= 65536 and identity(before) == identity(os.fstat(fd)))
        require(identity(before) == identity(os.stat('.w2l-toolchain.json', dir_fd=directory, follow_symlinks=False)))
        return identity(before), b''.join(chunks)
    finally:
        os.close(fd)


def pairs(values):
    result = {}
    for key, value in values:
        require(key not in result)
        result[key] = value
    return result


def record(data, name):
    value = json.loads(data, object_pairs_hook=pairs)
    require(isinstance(value, dict) and REQUIRED <= set(value) <= FIELDS)
    require(all(isinstance(value[key], str) and re.fullmatch(r'[!-~]{1,2048}', value[key]) for key in REQUIRED))
    ecosystem = name.split('-')[0].upper()
    require(value['ecosystem'] == ecosystem and value['directory'] == ROOT + '/' + name)
    require(re.fullmatch(r'[0-9][0-9A-Za-z._+-]{0,95}', value['version']) and value['origin'] in ('MANAGED', 'SYSTEM'))
    for key in ('sha256', 'tree_sha256', 'agent_program_sha256'):
        require(key not in value or isinstance(value[key], str) and re.fullmatch('[a-f0-9]{64}', value[key]))
    require('official_algorithm' not in value or value['official_algorithm'] in ('sha256', 'sha512'))
    require('official_digest' not in value or isinstance(value['official_digest'], str) and re.fullmatch('[a-f0-9]{64}|[a-f0-9]{128}', value['official_digest']))
    if value['origin'] == 'MANAGED':
        require(value.get('official_algorithm') in ('sha256', 'sha512'))
        require(isinstance(value.get('official_digest'), str) and re.fullmatch('[a-f0-9]{' + ('64' if value['official_algorithm'] == 'sha256' else '128') + '}', value['official_digest']))
        require(value['official_algorithm'] != 'sha256' or value['sha256'] == value['official_digest'])
        require(name == ecosystem.lower() + '-' + hashlib.sha256((ecosystem + '\n' + value['version'] + '\n' + value['official_digest']).encode()).hexdigest())
    else:
        require(value['source'].startswith('system:/'))
    result = {key: value[key] for key in sorted(FIELDS - {'source', 'system_files', 'dependencies'}) if key in value}
    result['declaredBranchCompatible'] = bool(re.fullmatch(r'1\.24(?:\.[0-9]+)?', value['version']) if ecosystem == 'GO' else re.fullmatch(r'24(?:\.[0-9]+){0,2}', value['version']))
    return result


def scan(directory):
    names = []
    with os.scandir(directory) as entries:
        for entry in entries:
            require(len(names) < 32)
            names.append(entry.name)
    rows, proofs = [], []
    for name in sorted(names):
        if not name.startswith(('go-', 'node-')):
            continue
        require(re.fullmatch('(go|node)-[a-f0-9]{64}', name))
        fd = open_directory(name, directory)
        try:
            before = identity(os.fstat(fd))
            observed = marker(fd)
            require(before == identity(os.fstat(fd)) == identity(os.stat(name, dir_fd=directory, follow_symlinks=False)))
            rows.append({'slot': name, 'recordState': 'ABSENT' if observed is None else 'FIELDS_OBSERVED',
                         'fields': {} if observed is None else record(observed[1], name)})
            proofs.append((name, before, observed))
        finally:
            os.close(fd)
    return sorted(names), proofs, rows


def stable_scan(directory):
    before, after = scan(directory), scan(directory)
    require(before == after)
    return before[2]


def observe():
    chain, missing, rows = [], None, []
    try:
        fd = open_directory('/')
        chain.append((None, '/', fd, identity(os.fstat(fd))))
        for name in ROOT.strip('/').split('/'):
            parent = fd
            try:
                fd = open_directory(name, parent)
            except FileNotFoundError:
                missing = parent, name
                break
            chain.append((parent, name, fd, identity(os.fstat(fd))))
        if missing is None:
            rows = stable_scan(fd)
        else:
            try:
                os.stat(missing[1], dir_fd=missing[0], follow_symlinks=False)
                require(False)
            except FileNotFoundError:
                pass
        for parent, name, fd, before in chain:
            require(before == identity(os.fstat(fd)))
            if parent is not None:
                require(before == identity(os.stat(name, dir_fd=parent, follow_symlinks=False)))
        return {'schema': 1, 'mode': 'METADATA_ONLY', 'status': 'OBSERVED', 'rootState': 'PRESENT' if missing is None else 'ABSENT',
                'records': rows, 'sdkExecutions': 0, 'probeOperations': 0,
                'ecosystems': {key: 'SLOTS_OBSERVED' if any(row['slot'].startswith(key.lower() + '-') for row in rows) else 'NO_RECORDED_SLOTS' for key in ('GO', 'NODE')},
                'meaning': 'Declared metadata only; SDK execution, content digests, runtime compatibility and READY are not verified.'}
    finally:
        for _, _, fd, _ in reversed(chain):
            os.close(fd)


if __name__ == '__main__':
    try:
        output = json.dumps(observe(), sort_keys=True, separators=(',', ':'))
        require(len(output.encode('utf-8')) <= 60000)
        print('SDK_METADATA=' + output)
    except (OSError, ValueError, TypeError, KeyError):
        print('SDK_METADATA={"schema":1,"mode":"METADATA_ONLY","status":"UNCONFIRMED"}')
        sys.exit(65)
