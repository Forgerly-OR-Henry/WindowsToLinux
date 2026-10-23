"""Requires the public 293 Observer definitions; read-only OS metadata, never a grow authorization."""
import selectors
import signal
import subprocess
import time

EXPECTED_MM = '253:1'
SAFE_NAME = r'[A-Za-z0-9][A-Za-z0-9_.+-]{0,127}'


def integer(value):
    if isinstance(value, str):
        need(re.fullmatch(r'0|[1-9][0-9]{0,18}', value), 'INTEGER')
        value = int(value)
    return long_value(value)


def major_minor(value):
    need(isinstance(value, str) and re.fullmatch(r'(0|[1-9][0-9]{0,6}):(0|[1-9][0-9]{0,6})', value), 'DEVICE_NUMBER')
    return value


def strict_json(data):
    need(isinstance(data, bytes) and 0 < len(data) <= 65536, 'JSON_BOUND')
    def pairs(items):
        result = {}
        for key, value in items:
            need(key not in result, 'DUPLICATE_JSON_FIELD')
            result[key] = value
        return result
    def invalid(value):
        raise ValueError('NON_INTEGER_JSON_NUMBER')
    return json.loads(data.decode('utf-8'), object_pairs_hook=pairs, parse_constant=invalid, parse_float=invalid)


def block_topology(value):
    need(isinstance(value, dict) and isinstance(value.get('blockdevices'), list) and len(value['blockdevices']) == 1, 'TOPOLOGY_ROOT')
    nodes, edges, seen = [], [], set()
    def visit(row, depth):
        need(isinstance(row, dict) and depth <= 8 and len(nodes) < 32, 'TOPOLOGY_BOUND')
        name, kname, mm = row.get('name'), row.get('kname'), major_minor(row.get('maj:min'))
        need(isinstance(name, str) and re.fullmatch(SAFE_NAME, name) and isinstance(kname, str) and re.fullmatch(SAFE_NAME, kname), 'BLOCK_NAME')
        need(mm not in seen and not any(item['kname'] == kname for item in nodes), 'TOPOLOGY_DUPLICATE')
        seen.add(mm)
        kind, ro, pkname = row.get('type'), row.get('ro'), row.get('pkname')
        need(isinstance(kind, str) and re.fullmatch(r'[a-z0-9_-]{1,24}', kind), 'BLOCK_TYPE')
        need(type(ro) is bool or type(ro) is int and ro in (0, 1) or isinstance(ro, str) and ro in ('0', '1'), 'READONLY')
        need(pkname is None or isinstance(pkname, str) and re.fullmatch(SAFE_NAME, pkname), 'BLOCK_PARENT')
        nodes.append({'name': name, 'kname': kname, 'majorMinor': mm, 'byteSize': integer(row.get('size')), 'type': kind,
                      'readOnly': ro in (True, 1, '1'), 'parentKname': pkname})
        children = row.get('children', [])
        need(isinstance(children, list) and len(children) <= 8, 'TOPOLOGY_BOUND')
        for child in children:
            child_mm = visit(child, depth + 1)
            edges.append({'dependent': mm, 'parent': child_mm})
        return mm
    need(visit(value['blockdevices'][0], 0) == EXPECTED_MM, 'ROOT_DEVICE_CHANGED')
    return {'state': 'OBSERVED', 'devices': nodes, 'parentEdges': edges}


def unique_physical_disk(topology):
    nodes = {row['majorMinor']: row for row in topology['devices']}
    current, visited = EXPECTED_MM, set()
    while current not in visited:
        visited.add(current)
        row = nodes[current]
        parents = [edge['parent'] for edge in topology['parentEdges'] if edge['dependent'] == current]
        if not parents:
            need(row['type'] == 'disk' and row['parentKname'] is None and len(visited) == len(nodes), 'PHYSICAL_DISK_UNCONFIRMED')
            return row
        need(len(parents) == 1 and row['type'] in ('part', 'lvm', 'crypt', 'dm')
             and row['parentKname'] == nodes[parents[0]]['kname'], 'PHYSICAL_DISK_UNCONFIRMED')
        current = parents[0]
    raise ValueError('PHYSICAL_DISK_UNCONFIRMED')


def mount_projection(value, state_root):
    rows = value.get('filesystems') if isinstance(value, dict) else None
    need(isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict), 'MOUNT_BOUND')
    row = rows[0]
    target, kind = row.get('target'), row.get('fstype')
    need(isinstance(target, str) and re.fullmatch(r'/[A-Za-z0-9._+/-]*', target) and len(target) <= 4096, 'MOUNT_TARGET')
    need(state_root == target or state_root.startswith(target.rstrip('/') + '/'), 'MOUNT_COVERAGE')
    need(isinstance(kind, str) and re.fullmatch(r'[a-zA-Z0-9_.+-]{1,32}', kind) and major_minor(row.get('maj:min')) == EXPECTED_MM, 'MOUNT_IDENTITY')
    return {'state': 'OBSERVED', 'requestedStateRoot': state_root, 'mountpoint': target, 'fsType': kind, 'majorMinor': EXPECTED_MM}


def partition_projection(value, disk):
    table = value.get('partitiontable') if isinstance(value, dict) else None
    need(isinstance(table, dict) and table.get('device') == '/dev/' + disk['kname'] and table.get('unit') == 'sectors'
         and table.get('label') in ('gpt', 'dos'), 'PARTITION_TABLE')
    sector = integer(table.get('sectorsize'))
    need(512 <= sector <= 65536 and sector & (sector - 1) == 0, 'SECTOR_SIZE_REQUIRED')
    rows = table.get('partitions')
    need(isinstance(rows, list) and len(rows) <= 32, 'PARTITION_BOUND')
    result, names = [], set()
    for row in rows:
        need(isinstance(row, dict), 'PARTITION_ROW')
        node = row.get('node')
        need(isinstance(node, str) and re.fullmatch('/dev/' + SAFE_NAME, node) and node not in names, 'PARTITION_NODE')
        names.add(node)
        start, size = integer(row.get('start')), integer(row.get('size'))
        end = long_value(start + size)
        need(size > 0 and long_value(end * sector) <= disk['byteSize'], 'PARTITION_GEOMETRY')
        result.append({'node': node, 'startSector': start, 'sizeSectors': size})
    ordered = sorted(result, key=lambda row: row['startSector'])
    need(all(a['startSector'] + a['sizeSectors'] <= b['startSector'] for a, b in zip(ordered, ordered[1:])), 'OVERLAPPING_PARTITIONS')
    covered = long_value(sum(row['sizeSectors'] * sector for row in result))
    return {'state': 'OBSERVED', 'diskKname': disk['kname'], 'diskBytes': disk['byteSize'], 'tableKind': table['label'],
            'sectorSize': sector, 'partitions': result, 'uncoveredByListedPartitionBytes': long_value(disk['byteSize'] - covered),
            'meaning': 'Uncovered bytes include metadata/reserved gaps; not allocatable space or evidence that growing any layer is safe.'}


def filesystem_projection(before, after, device):
    need(unsigned_hex(before.f_fsid) == unsigned_hex(after.f_fsid) and before.f_frsize == after.f_frsize
         and bool(before.f_flag & os.ST_RDONLY) == bool(after.f_flag & os.ST_RDONLY), 'FILESYSTEM_CHANGED')
    fragment = long_value(before.f_frsize)
    need(fragment > 0, 'CAPACITY_INTEGER')
    samples = []
    for sample in (before, after):
        blocks, free, available = (integer(getattr(sample, key)) for key in ('f_blocks', 'f_bfree', 'f_bavail'))
        need(available <= free <= blocks, 'CAPACITY_INTEGER')
        samples.append({'blocks': blocks, 'bfree': free, 'bavail': available, 'totalBytes': long_value(blocks * fragment),
                        'freeBytes': long_value(free * fragment), 'availableBytes': long_value(available * fragment)})
    return {'state': 'OBSERVED', 'device': unsigned_hex(device), 'majorMinor': EXPECTED_MM, 'fsid': unsigned_hex(before.f_fsid),
            'frsize': fragment, 'readOnly': bool(before.f_flag & os.ST_RDONLY), 'samples': samples,
            'availableBytesMinimum': min(row['availableBytes'] for row in samples), 'meaning': 'Two point samples, not a reservation.'}


def block_binding(observer, kname, mm, bindings):
    need(isinstance(kname, str) and re.fullmatch(SAFE_NAME, kname), 'BLOCK_NAME')
    parent = observer.resolve('/dev')
    need(parent['exists'] and stat.S_ISDIR(parent['info'].st_mode), 'DEVICE_PARENT')
    info = os.stat(kname, dir_fd=parent['fd'], follow_symlinks=False)
    expected = os.makedev(*(int(part) for part in major_minor(mm).split(':')))
    need(stat.S_ISBLK(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o002 and info.st_rdev == expected, 'BLOCK_BINDING')
    fd = os.open(kname, os.O_PATH | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=parent['fd'])
    observer.fds.append(fd)
    observer.entries.append((parent['fd'], kname, fd, signature(info)))
    need(signature(info) == signature(os.fstat(fd)) and os.fstat(fd).st_rdev == expected, 'BLOCK_BINDING')
    bindings.append((fd, parent['fd'], kname, expected))


def root_alias(observer, bindings):
    parent = observer.resolve('/dev/block')
    need(parent['exists'], 'ROOT_BLOCK_ALIAS')
    info = os.stat(EXPECTED_MM, dir_fd=parent['fd'], follow_symlinks=False)
    need(stat.S_ISLNK(info.st_mode) and info.st_uid == 0, 'ROOT_BLOCK_ALIAS')
    target = os.readlink(EXPECTED_MM, dir_fd=parent['fd'])
    match = re.fullmatch(r'(?:\.\./|/dev/)(' + SAFE_NAME + ')', target)
    need(match is not None, 'ROOT_BLOCK_ALIAS')
    observer.links.append((parent['fd'], EXPECTED_MM, signature(info), target))
    block_binding(observer, match.group(1), EXPECTED_MM, bindings)


def utility_json(observer, argv):
    resolved = observer.resolve(argv[0])
    need(resolved['exists'] and stat.S_ISREG(resolved['info'].st_mode) and resolved['info'].st_mode & 0o111, 'UTILITY_UNAVAILABLE')
    observer.verify()
    selector = selectors.DefaultSelector()
    try:
        process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C', 'LC_ALL': 'C'}, close_fds=True, start_new_session=True)
    except Exception:
        selector.close()
        raise
    buffers = [bytearray(), bytearray()]
    try:
        selector.register(process.stdout, selectors.EVENT_READ, 0)
        selector.register(process.stderr, selectors.EVENT_READ, 1)
        deadline = time.monotonic() + 10
        while selector.get_map():
            need(time.monotonic() < deadline, 'UTILITY_TIMEOUT')
            for key, _ in selector.select(min(0.1, max(0, deadline - time.monotonic()))):
                chunk = os.read(key.fileobj.fileno(), 4096)
                need(sum(map(len, buffers)) + len(chunk) <= 65536, 'UTILITY_OUTPUT_BOUND')
                if chunk: buffers[key.data].extend(chunk)
                else: selector.unregister(key.fileobj)
        need(process.wait(timeout=max(0.01, deadline - time.monotonic())) == 0 and not buffers[1], 'UTILITY_RESULT')
        observer.verify()
        value = strict_json(bytes(buffers[0]))
        return value
    finally:
        try:
            if process.poll() is None:
                try: os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError: pass
            process.stdout.close(); process.stderr.close()
            process.wait(timeout=3)
        except Exception:
            raise RuntimeError('UTILITY_CLEANUP_UNCONFIRMED') from None
        finally:
            selector.close()


def block_observation(state_root):
    observer, bindings = Observer(), []
    unknown = {'state': 'UNKNOWN', 'meaning': 'Not observed or not uniquely/credibly established; no zero-capacity inference.'}
    try:
        root = observer.resolve(state_root)
        need(root['exists'] and stat.S_ISDIR(root['info'].st_mode), 'CAPTURED_STATE_ROOT')
        device = os.fstat(root['fd']).st_dev
        need(device == os.makedev(253, 1), 'CAPTURED_ROOT_DEVICE_CHANGED')
        before = os.fstatvfs(root['fd'])
        topology, mount, partitions = dict(unknown), dict(unknown), dict(unknown)
        try:
            root_alias(observer, bindings)
            topology = block_topology(utility_json(observer, ['/usr/bin/lsblk', '-b', '--json', '--inverse', '-o', 'NAME,KNAME,PKNAME,MAJ:MIN,SIZE,TYPE,RO', '/dev/block/253:1']))
            disk = unique_physical_disk(topology)
            block_binding(observer, disk['kname'], disk['majorMinor'], bindings)
            utility = None
            for candidate in ('/usr/sbin/sfdisk', '/usr/bin/sfdisk'):
                if observer.resolve(candidate)['exists']:
                    utility = candidate
                    break
            if utility is not None:
                partitions = partition_projection(utility_json(observer, [utility, '--json', '/dev/' + disk['kname']]), disk)
        except (OSError, ValueError, KeyError, AttributeError, RecursionError, subprocess.SubprocessError):
            pass
        try:
            mount = mount_projection(utility_json(observer, ['/usr/bin/findmnt', '--json', '--target', state_root, '-o', 'TARGET,FSTYPE,MAJ:MIN']), state_root)
        except (OSError, ValueError, KeyError, AttributeError, RecursionError, subprocess.SubprocessError):
            pass
        filesystem = filesystem_projection(before, os.fstatvfs(root['fd']), device)
        observer.verify()
        need(all(os.fstat(fd).st_rdev == expected and os.stat(name, dir_fd=parent, follow_symlinks=False).st_rdev == expected
                 for fd, parent, name, expected in bindings), 'BLOCK_BINDING_CHANGED')
        return {'schema': 1, 'mode': 'METADATA_ONLY', 'status': 'OBSERVED', 'capturedStateRoot': state_root,
                'filesystem': filesystem, 'mount': mount, 'topology': topology, 'partitionTable': partitions,
                'sdkExecutions': 0, 'meaning': 'Trusted SSH/OS startup baseline and stable path attributes; no OS content-integrity proof. Read-only standard OS utilities, no SDK execution or remote writes. Geometry does not authorize grow/format/migration/layout changes.'}
    finally:
        observer.close()


if __name__ == '__main__':
    try:
        output = json.dumps(block_observation(CAPTURED_STATE_ROOT), sort_keys=True, separators=(',', ':'))
        need(len(output.encode('utf-8')) <= 60000, 'OUTPUT_BOUND')
        print('BLOCK_FILESYSTEM_LAYOUT_METADATA=' + output)
    except (OSError, ValueError, UnicodeError, KeyError, AttributeError, RecursionError, subprocess.SubprocessError):
        print('BLOCK_FILESYSTEM_LAYOUT_METADATA={"schema":1,"mode":"METADATA_ONLY","status":"UNCONFIRMED"}')
        sys.exit(65)
