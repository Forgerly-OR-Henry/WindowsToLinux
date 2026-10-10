def owned_directory(path):
    for parent in [path] + list(path.parents):
        if parent.exists():
            stat = parent.lstat()
            if parent.is_symlink() or stat.st_uid != 0 or stat.st_mode & 0o022:
                fail('ownership', 'toolchain directory must be root-owned and not group/world writable')


def extract(archive, destination):
    """No traversal, devices, external links, or unbounded expansion, including older Python hosts."""
    root = destination.resolve()
    total = 0
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as data:
            members = data.infolist()
            if len(members) > 200000 or len({m.filename for m in members}) != len(members):
                fail('archive', 'too many archive members')
            for m in members:
                target = root / m.filename
                total += m.file_size
                if (
                    '\\' in m.filename
                    or root not in target.resolve().parents
                    or total > 8 * MAX_ARCHIVE
                    or (m.external_attr >> 16) & 0o170000 == 0o120000
                ):
                    fail('archive', 'unsafe ZIP entry')
            data.extractall(root)
            for m in members:
                if not m.is_dir():
                    (root / m.filename).chmod(0o755 if m.external_attr >> 16 & 0o111 else 0o644)
        return
    with tarfile.open(archive) as data:
        members = data.getmembers()
        if len(members) > 200000 or len({m.name.rstrip('/') for m in members}) != len(members):
            fail('archive', 'too many archive members')
        links = {m.name.rstrip('/'): m for m in members if m.issym() or m.islnk()}
        for member in members:
            parts = pathlib.PurePosixPath(member.name).parts
            if '\\' in member.name or any('/'.join(parts[:i]) in links for i in range(1, len(parts))):
                fail('archive', 'archive entry has a link ancestor')
        # Resolve link chains virtually before extraction, including .. after a symlink and hard-link root semantics.
        for name in links:
            queue = name.split('/')
            resolved = []
            hops = 0
            while queue:
                part = queue.pop(0)
                if part in ('', '.'):
                    continue
                if part == '..':
                    if not resolved:
                        fail('archive', 'external archive link chain')
                    resolved.pop()
                    continue
                key = '/'.join(resolved + [part])
                if key in links:
                    hops += 1
                    link = links[key]
                    if hops > 40 or link.linkname.startswith('/') or '\\' in link.linkname:
                        fail('archive', 'cyclic or absolute archive link')
                    if link.islnk():
                        resolved = []
                    queue = link.linkname.split('/') + queue
                else:
                    resolved.append(part)
        for m in members:
            target = root / m.name
            total += m.size
            if target.resolve() != root and root not in target.resolve().parents:
                fail('archive', 'archive traversal')
            if not (m.isfile() or m.isdir() or m.issym() or m.islnk()) or total > 8 * MAX_ARCHIVE:
                fail('archive', 'unsafe archive member')
            if m.issym() or m.islnk():
                link = (target.parent if m.issym() else root) / m.linkname
                if root not in link.resolve().parents:
                    fail('archive', 'external archive link')
            m.mode &= 0o755
        data.extractall(root, members=members)
