from contextlib import nullcontext
from hashlib import sha256
from pathlib import PurePosixPath
from types import SimpleNamespace
import sys


helper = sys.stdin.buffer.read().decode('utf-8')
module = helper.split("<<'WTL_OFFICIAL_TOOLCHAINS'\n", 1)[1].split('\nWTL_OFFICIAL_TOOLCHAINS\n', 1)[0]
scope = {'__name__': 'php_system_fixture'}
exec(compile(module, 'rendered-toolchain-helper', 'exec'), scope)

admitted = '/usr/bin/php8.3'
alias = '/usr/local/bin/php'
alternate = '/usr/bin/php8.4'
events = []
probed = []
lookups = []
binary = b'protected versioned PHP executable'


class FixturePath:
    def __init__(self, path):
        self.path = PurePosixPath(str(path))

    def __str__(self):
        return str(self.path)

    def __truediv__(self, component):
        return FixturePath(self.path / component)

    @property
    def parent(self):
        return FixturePath(self.path.parent)

    def resolve(self):
        return FixturePath(admitted if str(self) == alias else str(self))

    def is_file(self):
        return str(self) in (admitted, alternate)

    def exists(self):
        return self.is_file()

    def stat(self):
        return SimpleNamespace(st_mode=0o755, st_uid=0)

    def read_bytes(self):
        return binary if str(self) == admitted else b'different PHP executable'

    def mkdir(self):
        pass

    def chmod(self, mode):
        pass

    def symlink_to(self, path, target_is_directory=False):
        events.append(('wrapper', str(self), str(path)))

    def write_text(self, value):
        events.append(('marker', str(self)))


def which(name, path):
    assert name == 'php'
    lookups.append(path)
    return alias if len(lookups) == 1 else alternate


def isolated_probe(path, version):
    probed.append((str(path), version))


scope.update(pathlib=SimpleNamespace(Path=FixturePath), shutil=SimpleNamespace(which=which),
             ROOT=FixturePath('/managed'), owned_directory=lambda path: None,
             php_protected_executable=lambda path: None, php_system_probe=isolated_probe,
             saved_installation_marker=lambda directory: None,
             create_installation_directory=lambda directory, tracked: nullcontext())
record = scope['discover_system']('PHP', '8.3.7')
assert len(lookups) == 1, lookups
assert probed == [(admitted, '8.3.7')], probed
assert record['origin'] == 'SYSTEM' and record['source'] == 'system:' + admitted, record
assert record['system_files'] == [{'name': 'php', 'path': admitted, 'sha256': sha256(binary).hexdigest()}], record


def changes_after_probe(path, version):
    global binary
    binary = b'replaced by a concurrent update'


lookups.clear()
scope['php_system_probe'] = changes_after_probe
assert scope['discover_system']('PHP', '8.3.7') is None


def verify(record):
    assert events == [('wrapper', record['directory'] + '/bin/php', admitted)], events
    events.append(('verify', record['source']))


scope['verify_system'] = verify
completed = scope['materialize_system'](record)
assert completed['system_files'] == record['system_files']
assert [e[0] for e in events] == ['wrapper', 'verify', 'marker'], events


def changed_before_publication(record):
    verify(record)
    raise scope['PreparationFailure']('integrity', 'pinned PHP changed')


events.clear()
scope['verify_system'] = changed_before_publication
try:
    scope['materialize_system'](record)
except scope['PreparationFailure']:
    assert [e[0] for e in events] == ['wrapper', 'verify'], events
else:
    raise AssertionError('Unverified PHP marker was published')
