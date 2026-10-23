"""Pure standard-Go declaration and captured-root filesystem observations; never READY."""
import json
import os
import re
import stat
import sys

GO_CANDIDATES = ('/usr/local/bin/go', '/usr/bin/go', '/bin/go', '/usr/local/go/bin/go')
ROOT_FIELDS = {'helperDirectory', 'applicationsRoot', 'stateRoot', 'configurationRoot', 'dataRoot'}
LONG_MAX = (1 << 63) - 1
DIRECTORY_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC


def need(value, code='UNTRUSTED_OR_CHANGED_METADATA'):
    if not value:
        raise ValueError(code)


def signature(info):
    return tuple(getattr(info, key) for key in ('st_dev', 'st_ino', 'st_mode', 'st_uid', 'st_gid', 'st_nlink', 'st_size', 'st_mtime_ns', 'st_ctime_ns'))


def trusted(info):
    need(info.st_uid == 0 and info.st_gid == 0)
    # Linux symlink mode 0777 is not an access-control grant; its parent and target must be protected.
    need(stat.S_IMODE(info.st_mode) == 0o777 if stat.S_ISLNK(info.st_mode) else not info.st_mode & 0o022)


def long_value(value):
    need(type(value) is int and 0 <= value <= LONG_MAX, 'CAPACITY_INTEGER')
    return value


def unsigned_hex(value):
    need(type(value) is int and 0 <= value < (1 << 64), 'FILESYSTEM_IDENTIFIER')
    return '0x%016x' % value


def version_declaration(data):
    need(isinstance(data, bytes) and 0 < len(data) <= 1024, 'VERSION_BOUND')
    line = data.split(b'\n', 1)[0].rstrip(b'\r').decode('ascii')
    need(re.fullmatch(r'go[1-9][0-9]*\.[0-9]+(?:\.[0-9]+)?', line) and len(line) <= 96, 'VERSION_DECLARATION')
    return line


class Observer:
    def __init__(self):
        self.fds, self.entries, self.links, self.missing = [], [], [], []
        fd = os.open('/', DIRECTORY_FLAGS)
        try:
            info = os.fstat(fd)
            trusted(info)
            need(stat.S_ISDIR(info.st_mode))
            self.root = fd
            self.fds.append(fd)
            self.entries.append((None, '/', fd, signature(info)))
        except Exception:
            os.close(fd)
            raise

    def close(self):
        for fd in reversed(self.fds):
            os.close(fd)

    def resolve(self, requested, no_final_link=False):
        need(isinstance(requested, str) and re.fullmatch(r'/[A-Za-z0-9._+/-]*', requested) and len(requested) <= 4096, 'PATH_BOUND')
        queue = requested.split('/')[1:]
        need(len(queue) <= 128, 'PATH_BOUND')
        stack, parts, seen = [self.root], [], set()
        while queue:
            name = queue.pop(0)
            if name in ('', '.'):
                continue
            if name == '..':
                need(len(stack) > 1, 'PATH_ESCAPE')
                stack.pop(); parts.pop()
                continue
            parent = stack[-1]
            try:
                info = os.stat(name, dir_fd=parent, follow_symlinks=False)
            except FileNotFoundError:
                self.missing.append((parent, name))
                return {'exists': False, 'fd': parent, 'path': '/' + '/'.join(parts)}
            trusted(info)
            if stat.S_ISLNK(info.st_mode):
                need(not (no_final_link and not queue), 'VERSION_LINK')
                key = info.st_dev, info.st_ino
                need(key not in seen and len(seen) < 16, 'LINK_LOOP_OR_LIMIT')
                seen.add(key)
                target = os.readlink(name, dir_fd=parent)
                need(isinstance(target, str) and re.fullmatch(r'[A-Za-z0-9._+/-]{1,4096}', target), 'LINK_TARGET')
                need(signature(info) == signature(os.stat(name, dir_fd=parent, follow_symlinks=False)))
                self.links.append((parent, name, signature(info), target))
                if target.startswith('/'):
                    stack, parts = [self.root], []
                queue = target.split('/') + queue
                need(len(queue) <= 128, 'PATH_BOUND')
                continue
            directory = stat.S_ISDIR(info.st_mode)
            need(directory or stat.S_ISREG(info.st_mode), 'ENTRY_TYPE')
            need(directory or not queue, 'NON_DIRECTORY_PARENT')
            need(len(self.fds) < 256, 'DESCRIPTOR_BOUND')
            flags = DIRECTORY_FLAGS if directory else os.O_PATH | os.O_NOFOLLOW | os.O_CLOEXEC
            fd = os.open(name, flags, dir_fd=parent)
            try:
                need(signature(info) == signature(os.fstat(fd)))
            except Exception:
                os.close(fd)
                raise
            self.fds.append(fd)
            self.entries.append((parent, name, fd, signature(info)))
            parts.append(name)
            if directory:
                stack.append(fd)
            if not queue:
                return {'exists': True, 'fd': fd, 'parent': parent, 'name': name, 'path': '/' + '/'.join(parts), 'info': info}
        return {'exists': True, 'fd': stack[-1], 'path': '/' + '/'.join(parts), 'info': os.fstat(stack[-1])}

    def verify(self):
        for parent, name, fd, before in self.entries:
            need(before == signature(os.fstat(fd)))
            if parent is not None:
                need(before == signature(os.stat(name, dir_fd=parent, follow_symlinks=False)))
        for parent, name, before, target in self.links:
            need(before == signature(os.stat(name, dir_fd=parent, follow_symlinks=False)) and target == os.readlink(name, dir_fd=parent))
        for parent, name in self.missing:
            try:
                os.stat(name, dir_fd=parent, follow_symlinks=False)
                need(False, 'ABSENCE_CHANGED')
            except FileNotFoundError:
                pass

    def version(self, path):
        resolved = self.resolve(path, no_final_link=True)
        if not resolved['exists']:
            return {'state': 'ABSENT'}
        info = resolved['info']
        need(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and 0 < info.st_size <= 1024, 'VERSION_BOUND')
        fd = os.open(resolved['name'], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=resolved['parent'])
        try:
            need(signature(info) == signature(os.fstat(fd)))
            chunks, size = [], 0
            while size <= 1024:
                chunk = os.read(fd, min(256, 1025 - size))
                if not chunk:
                    break
                chunks.append(chunk); size += len(chunk)
            need(size == info.st_size and signature(info) == signature(os.fstat(fd)))
            return {'state': 'DECLARATION_OBSERVED', 'text': version_declaration(b''.join(chunks))}
        finally:
            os.close(fd)

    def go(self, candidate):
        resolved = self.resolve(candidate)
        if not resolved['exists']:
            return {'candidate': candidate, 'state': 'ABSENT_AT_RESOLVED_PATH'}
        info, path = resolved['info'], resolved['path']
        need(stat.S_ISREG(info.st_mode) and info.st_mode & 0o111, 'GO_FILE_ATTRIBUTES')
        result = {'candidate': candidate, 'state': 'FILE_ATTRIBUTES_OBSERVED', 'resolvedPath': path,
                  'bytes': long_value(info.st_size), 'mode': '%04o' % stat.S_IMODE(info.st_mode)}
        if not path.endswith('/bin/go'):
            result['version'] = {'state': 'HOME_RELATION_UNCONFIRMED'}
        else:
            try:
                result['version'] = self.version(path[:-len('/bin/go')] + '/VERSION')
            except (OSError, ValueError, UnicodeError, KeyError):
                result['version'] = {'state': 'UNCONFIRMED'}
        return result


def capacity(fd):
    before, after = os.fstatvfs(fd), os.fstatvfs(fd)
    device = unsigned_hex(os.fstat(fd).st_dev)
    fsid = unsigned_hex(before.f_fsid)
    need(fsid == unsigned_hex(after.f_fsid) and before.f_frsize == after.f_frsize, 'FILESYSTEM_CHANGED')
    fragment = long_value(before.f_frsize)
    need(fragment > 0, 'CAPACITY_INTEGER')
    available = [long_value(before.f_bavail), long_value(after.f_bavail)]
    free = [long_value(fragment * value) for value in available]
    return {'device': device, 'fsid': fsid, 'frsize': fragment, 'bavailSamples': available,
            'freeBytesSamples': free, 'freeBytesMinimum': min(free), 'meaning': 'Two point samples; minimum is not a reservation or future capacity guarantee.'}


def observe(roots):
    need(isinstance(roots, dict) and set(roots) == ROOT_FIELDS, 'CAPTURED_ROOTS')
    observer = Observer()
    try:
        go, filesystems, groups = [], [], {}
        for candidate in GO_CANDIDATES:
            try:
                go.append(observer.go(candidate))
            except (OSError, ValueError, UnicodeError, KeyError):
                go.append({'candidate': candidate, 'state': 'UNCONFIRMED'})
        for role, requested in sorted(roots.items()):
            row = {'role': role, 'requested': requested}
            try:
                resolved = observer.resolve(requested)
                need(stat.S_ISDIR(os.fstat(resolved['fd']).st_mode), 'CAPACITY_DIRECTORY')
                row.update(state='OBSERVED', exists=resolved['exists'], observedExistingAncestor=resolved['path'], **capacity(resolved['fd']))
                key = row['device'] + ':' + row['fsid']
                row['filesystemKey'] = key
                groups.setdefault(key, []).append(role)
            except (OSError, ValueError, KeyError, AttributeError):
                row = {'role': role, 'requested': requested, 'state': 'UNCONFIRMED'}
            filesystems.append(row)
        observer.verify()
        return {'schema': 1, 'mode': 'METADATA_ONLY', 'status': 'OBSERVED', 'go': go, 'capacities': filesystems,
                'filesystemGroups': groups, 'sdkExecutions': 0, 'meaning': 'Standard paths and VERSION declarations only; no SDK binary reads/execution, runtime compatibility or READY. Missing roots sample their nearest trusted existing ancestor, not a future filesystem.'}
    finally:
        observer.close()


if __name__ == '__main__':
    try:
        output = json.dumps(observe(ROOTS), sort_keys=True, separators=(',', ':'))
        need(len(output.encode('utf-8')) <= 60000, 'OUTPUT_BOUND')
        print('SYSTEM_GO_CAPACITY_METADATA=' + output)
    except (OSError, ValueError, UnicodeError, KeyError, AttributeError):
        print('SYSTEM_GO_CAPACITY_METADATA={"schema":1,"mode":"METADATA_ONLY","status":"UNCONFIRMED"}')
        sys.exit(65)
