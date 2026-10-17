"""Explicit authorized content reads for ordered digests; no payload or explicit source writes.
Validation/Paths below are copied pure definitions from the frozen bundle producer.
This program never imports/executes that module, snapshots SQLite, creates output or cleans resources.
"""
import argparse
from decimal import Decimal
import hashlib
import json
import os
from pathlib import PurePosixPath
import re
import stat
import sys
import time

FORMAT = 'WTL_PRE_REINSTALL_BUNDLE_1'
MAX_INPUT, MAX_MANIFEST = 98304, 262144
DEADLINE = 0


def need(value, code='FROZEN_SOURCE_OR_OUTPUT_UNCONFIRMED'):
    if not value:
        raise ValueError(code)


def fields(value, names):
    need(isinstance(value, dict) and set(value) == set(names), 'EXACT_FIELDS')


def strict_json(raw):
    def pairs(items):
        value = {}
        for key, item in items:
            need(key not in value, 'DUPLICATE_JSON_FIELD')
            value[key] = item
        return value
    def invalid(value):
        raise ValueError('NON_INTEGER_JSON_NUMBER')
    return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs, parse_float=invalid, parse_constant=invalid)


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('utf-8')


def sha(value):
    return hashlib.sha256(value).hexdigest()


def path(value):
    need(isinstance(value, str) and re.fullmatch(r'/[A-Za-z0-9._+/$-]+', value) and len(value) <= 4096
         and str(PurePosixPath(value)) == value and all(part not in ('.', '..') for part in value.split('/')), 'CANONICAL_PATH')
    return PurePosixPath(value)


def integer(value, maximum=(1 << 63) - 1):
    need(type(value) is int and 0 <= value <= maximum, 'INTEGER_BOUND')
    return value


def identity(info):
    return info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_gid, info.st_nlink


def matches(info, expected):
    nanoseconds = Decimal(expected['mtime']) * 1000000000
    need(nanoseconds == int(nanoseconds) and stat.S_ISREG(info.st_mode) and info.st_nlink == 1
         and (info.st_dev, info.st_ino, stat.S_IMODE(info.st_mode), info.st_uid, info.st_gid, info.st_size, info.st_mtime_ns)
         == (expected['device'], expected['inode'], int(expected['mode'], 8), expected['uid'], expected['gid'], expected['bytes'], int(nanoseconds)))


def selection(raw, backups_root):
    need(0 < len(raw) <= MAX_INPUT, 'INPUT_BOUND')
    value = strict_json(raw)
    fields(value, ('schema', 'format', 'kind', 'run', 'operationRoot', 'items'))
    need(type(value['schema']) is int and value['schema'] == 1 and value['format'] == FORMAT and value['kind'] in ('FILES', 'DATABASES'))
    need(isinstance(value['run'], str) and re.fullmatch(r'[a-z0-9][a-z0-9-]{0,95}', value['run']))
    operation, root = path(value['operationRoot']), path(backups_root)
    need(operation.parent == root and re.fullmatch(re.escape(value['run']) + r'-[a-f0-9]{16,32}', operation.name), 'OWN_OPERATION_ROOT')
    need(isinstance(value['items'], list) and 0 < len(value['items']) <= 512, 'MEMBER_BOUND')
    seen, inodes, total = set(), set(), 0
    for item in value['items']:
        fields(item, ('source', 'sourceMetadata'))
        source = path(item['source'])
        need(str(source) not in seen and source != operation and operation not in source.parents, 'SOURCE_SET')
        if value['kind'] == 'FILES':
            need(not str(source).lower().endswith(('.db', '.sqlite', '.sqlite3', '-wal', '-shm', '-journal')), 'DATABASE_NOT_A_FILE_BUNDLE')
        seen.add(str(source))
        meta = item['sourceMetadata']
        fields(meta, ('kind', 'bytes', 'device', 'inode', 'mode', 'uid', 'gid', 'mtime'))
        need(meta['kind'] == 'f' and isinstance(meta['mode'], str) and re.fullmatch(r'[0-7]{3,4}', meta['mode'])
             and not int(meta['mode'], 8) & 0o022 and isinstance(meta['mtime'], str) and re.fullmatch(r'-?[0-9]{1,12}\.[0-9]{1,12}', meta['mtime']))
        for key in ('device', 'inode', 'uid', 'gid'):
            integer(meta[key])
        need((meta['device'], meta['inode']) not in inodes, 'DUPLICATE_SOURCE_IDENTITY')
        inodes.add((meta['device'], meta['inode']))
        total += integer(meta['bytes'], 65536 if value['kind'] == 'FILES' else 4194304)
    need(total <= (1048576 if value['kind'] == 'FILES' else 8388608), 'SOURCE_TOTAL_BOUND')
    return value


class Paths:
    def __init__(self):
        self.directories, self.entries, self.sources = {}, [], []
        fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
        self.directories['/'] = fd
        info = os.fstat(fd)
        need(info.st_uid == 0 and info.st_gid == 0 and not info.st_mode & 0o022)

    def directory(self, value, owner=0, root_only=False):
        current, parent = '/', self.directories['/']
        for name in path(value).parts[1:]:
            current = current.rstrip('/') + '/' + name
            before = os.stat(name, dir_fd=parent, follow_symlinks=False)
            need(stat.S_ISDIR(before.st_mode) and not before.st_mode & 0o022 and before.st_uid in (0, owner)
                 and (not root_only or before.st_uid == 0 and before.st_gid == 0), 'SOURCE_PARENT_UNTRUSTED')
            if current not in self.directories:
                need(len(self.directories) + len(self.sources) < 1024, 'DESCRIPTOR_BOUND')
                fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=parent)
                self.directories[current] = fd
                need(identity(before)[:-1] == identity(os.fstat(fd))[:-1], 'PARENT_CHANGED')
                self.entries.append((parent, name, fd, identity(before)[:-1]))
            parent = self.directories[current]
            need(identity(before)[:-1] == identity(os.fstat(parent))[:-1], 'PARENT_CHANGED')
        return parent

    def source(self, item, stable):
        source, meta = path(item['source']), item['sourceMetadata']
        parent = self.directory(str(source.parent), meta['uid'])
        before = os.stat(source.name, dir_fd=parent, follow_symlinks=False)
        matches(before, meta)
        fd = os.open(source.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=parent)
        self.sources.append((parent, source.name, fd, identity(before), meta, stable))
        matches(os.fstat(fd), meta)
        return fd

    def verify(self):
        for parent, name, fd, frozen in self.entries:
            need(identity(os.fstat(fd))[:-1] == frozen == identity(os.stat(name, dir_fd=parent, follow_symlinks=False))[:-1], 'PARENT_CHANGED')
        for parent, name, fd, frozen, meta, stable in self.sources:
            after = os.stat(name, dir_fd=parent, follow_symlinks=False)
            need(identity(after) == frozen == identity(os.fstat(fd)), 'SOURCE_IDENTITY_CHANGED')
            if stable:
                matches(after, meta); matches(os.fstat(fd), meta)

    def close(self):
        for row in reversed(self.sources): os.close(row[2])
        for fd in reversed(list(self.directories.values())): os.close(fd)


def tick():
    need(time.monotonic() < DEADLINE, 'BACKUP_DEADLINE')


CONTENT_FORMAT = 'WTL_PRE_REINSTALL_CONTENT_DIGEST_1'


def observe(raw, backups_root):
    global DEADLINE
    DEADLINE = time.monotonic() + 120
    expected = selection(raw, backups_root)
    need(expected['kind'] == 'FILES', 'FILES_CONTENT_DIGEST_ONLY')
    paths = Paths()
    try:
        parent = paths.directory(backups_root, root_only=True)
        operation = path(expected['operationRoot'])
        def destination():
            try: os.stat(operation.name, dir_fd=parent, follow_symlinks=False)
            except FileNotFoundError: pass
            else: raise ValueError('NEW_OPERATION_ROOT_ALREADY_EXISTS')
            capacity = os.fstatvfs(parent)
            available = capacity.f_bavail * capacity.f_frsize
            integer(available)
            need(available >= 134217728, 'BACKUP_HEADROOM')
            return dict(backupsRoot=backups_root, operationRoot=str(operation), operationAbsent=True,
                        availableBytes=available, requiredHeadroomBytes=134217728)
        destination()
        for item in expected['items']: paths.source(item, True)
        paths.verify()
        items, total = [], 0
        for index, item in enumerate(expected['items']):
            tick(); paths.verify()
            fd, body = paths.sources[index][2], bytearray()
            while len(body) <= 65536:
                tick(); block = os.read(fd, min(65536, 65537 - len(body)))
                if not block: break
                body.extend(block)
            need(len(body) == item['sourceMetadata']['bytes'] and not body.startswith(b'SQLite format 3\x00'), 'SOURCE_EXTENT_OR_DATABASE')
            total += len(body)
            need(total <= 1048576, 'SOURCE_TOTAL_BOUND')
            paths.verify()
            items.append(dict(item, index=index, bytes=len(body), sha256=sha(body)))
        parents = []
        for name, fd in sorted(paths.directories.items()):
            info = os.fstat(fd)
            parents.append(dict(source=name, kind='d', device=info.st_dev, inode=info.st_ino,
                                mode=format(stat.S_IMODE(info.st_mode), 'o'), uid=info.st_uid, gid=info.st_gid))
        result = dict(schema=1, format=CONTENT_FORMAT, kind='FILES_CONTENT_DIGEST', selectionSha256=sha(raw), run=expected['run'],
                      operationRoot=expected['operationRoot'], items=items, parents=parents, destination=destination(),
                      consistency='ORDERED_FD_STABLE_READ_NOT_CROSS_FILE_ATOMIC', complete=True)
        paths.verify()
        return result
    finally:
        paths.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--backups-root', required=True)
    args = parser.parse_args()
    try:
        result = observe(sys.stdin.buffer.read(MAX_INPUT + 1), args.backups_root)
        output = encoded(result)
        need(len(output) <= 1048576, 'OUTPUT_BOUND')
        print(output.decode('utf-8'))
    except Exception:
        print('{"schema":1,"format":"WTL_PRE_REINSTALL_CONTENT_DIGEST_1","complete":false,"errorCode":"CONTENT_DIGEST_UNCONFIRMED"}')
        sys.exit(1)
