"""Fixed Agent dependency transactions; authenticated OS maintainers still execute as root."""
import configparser
import hashlib
import json
import os
import pathlib
import re
import selectors
import signal
import stat
import subprocess
import sys
import time
import urllib.parse

OS_PROFILE = None
HOOK_OWNERS = frozenset(('apt', 'dpkg', 'debconf', 'needrestart', 'ubuntu-release-upgrader-core',
                        'packagekit', 'appstream', 'apt-listchanges', 'man-db', 'update-notifier-common'))


class DependencyFailure(Exception):
    def __init__(self, detail, unknown=False):
        self.detail, self.unknown = detail, unknown


def dependency_reject(detail, unknown=False):
    raise DependencyFailure(detail, unknown)


def dependency_protected(path):
    path = pathlib.Path(path)
    resolved = path.resolve(strict=True)
    for item in (resolved, *resolved.parents):
        info = item.lstat()
        if item.is_symlink() or info.st_uid != 0 or info.st_mode & 0o022:
            dependency_reject('UNTRUSTED_OS_PATH')
    # The original traversal may include normal /bin or keyring links, but no writable parent.
    for item in path.parents:
        info = item.stat()
        if info.st_uid != 0 or info.st_mode & 0o022:
            dependency_reject('UNTRUSTED_OS_PARENT')
    return resolved


def dependency_bytes(path, maximum=1024 * 1024):
    path = dependency_protected(path)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_size > maximum:
        dependency_reject('OS_CONFIGURATION_LIMIT')
    fd = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW)
    try:
        current = os.fstat(fd)
        if (info.st_dev, info.st_ino) != (current.st_dev, current.st_ino):
            dependency_reject('OS_CONFIGURATION_CHANGED')
        with os.fdopen(os.dup(fd), 'rb') as stream:
            value = stream.read(maximum + 1)
        if len(value) > maximum or os.fstat(fd).st_mtime_ns != info.st_mtime_ns:
            dependency_reject('OS_CONFIGURATION_CHANGED')
        return value
    finally:
        os.close(fd)


def dependency_text(path):
    return dependency_bytes(path).decode('utf-8')


def dependency_profile():
    fields = {}
    for line in dependency_text('/etc/os-release').splitlines():
        match = re.fullmatch(r'([A-Z_]+)=(?:"([^"\\]*)"|([A-Za-z0-9._-]+))', line)
        if match and match[1] in ('ID', 'VERSION_ID', 'VERSION_CODENAME'):
            if match[1] in fields:
                dependency_reject('OS_PROFILE_SCHEMA')
            fields[match[1]] = match[2] if match[2] is not None else match[3]
    identity, version = fields.get('ID'), fields.get('VERSION_ID')
    profiles = {'ubuntu': ('22.04', '24.04'), 'debian': ('12', '13'), 'centos': ('9', '10')}
    if identity not in profiles or version not in profiles[identity]:
        dependency_reject('UNSUPPORTED_OS_PROFILE')
    return {'id': identity, 'version': version, 'manager': 'DNF' if identity == 'centos' else 'APT'}


def dependency_registered(path, owners, content=None):
    """Trust the existing protected native OS package DB; this is not a historical signature proof."""
    path = pathlib.Path(path)
    content = dependency_bytes(path) if content is None else content
    dependency_protected('/var/lib/dpkg')
    dependency_protected('/var/lib/dpkg/info')
    status = dependency_protected('/var/lib/dpkg/status')
    if not status.is_file() or status.stat().st_size > 256 * 1024 * 1024:
        dependency_reject('OS_PACKAGE_DATABASE')
    output = dependency_run([dependency_tool('dpkg-query'), '-S', str(path)])
    owner = None
    for line in output.splitlines():
        package, separator, registered = line.partition(': ')
        if not separator or registered != str(path) or owner is not None or not re.fullmatch('[a-z0-9+.-]+(?::[a-z0-9]+)?', package):
            dependency_reject('OS_CONFIGURATION_OWNER')
        owner = package
    if owner is None or owner.split(':')[0] not in owners:
        dependency_reject('OS_CONFIGURATION_OWNER')
    digest = hashlib.md5(content).hexdigest()
    conffiles = dependency_run([dependency_tool('dpkg-query'), '-W', '-f=' + '$' + '{Conffiles}\\n', owner])
    for line in conffiles.splitlines():
        values = line.split()
        if len(values) == 2 and values[0] == str(path) and re.fullmatch('[a-f0-9]{32}', values[1]):
            if values[1] == digest:
                return
            dependency_reject('MODIFIED_OS_CONFIGURATION')
    metadata = pathlib.Path('/var/lib/dpkg/info') / (owner + '.md5sums')
    if metadata.exists():
        for line in dependency_text(metadata).splitlines():
            values = line.split()
            if len(values) == 2 and values[1] == str(path).lstrip('/') and re.fullmatch('[a-f0-9]{32}', values[0]):
                if values[0] == digest:
                    return
                dependency_reject('MODIFIED_OS_CONFIGURATION')
    dependency_reject('UNREGISTERED_OS_CONFIGURATION')


def dependency_tool(name):
    for root in ('/usr/bin', '/usr/sbin', '/bin', '/sbin'):
        path = pathlib.Path(root) / name
        if path.exists():
            path = dependency_protected(path)
            if not path.is_file() or not os.access(path, os.X_OK):
                dependency_reject('OS_TOOL_NOT_EXECUTABLE')
            return str(path)
    dependency_reject('OS_TOOL_MISSING')


def dependency_run(args):
    process = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, start_new_session=True,
                               cwd='/', env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LC_ALL': 'C',
                                    'DEBIAN_FRONTEND': 'noninteractive', 'NEEDRESTART_MODE': 'l'})
    result, deadline = bytearray(), time.monotonic() + 1800
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map():
                if time.monotonic() >= deadline:
                    dependency_reject('DEPENDENCY_TRANSACTION_TIMEOUT', True)
                for key, _ in selector.select(0.2):
                    block = os.read(key.fileobj.fileno(), 8192)
                    if not block:
                        selector.unregister(key.fileobj)
                    else:
                        result.extend(block)
                        if len(result) > 1024 * 1024:
                            dependency_reject('DEPENDENCY_TRANSACTION_OUTPUT_LIMIT', True)
            if process.wait(timeout=max(0.01, deadline - time.monotonic())):
                dependency_reject('DEPENDENCY_TRANSACTION_FAILED')
        return bytes(result).decode('utf-8')
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
        process.wait()
        process.stdout.close()


def dependency_packages(manager, ecosystem):
    table = globals().get('DEPENDENCY_PACKAGES')
    if not isinstance(table, dict) or set(table) != {'APT', 'DNF'}:
        dependency_reject('PACKAGED_DEPENDENCY_TABLE')
    for branch in table.values():
        if not isinstance(branch, dict) or set(branch) != {'PYTHON', 'PHP', 'RUBY'}:
            dependency_reject('PACKAGED_DEPENDENCY_TABLE')
        for packages in branch.values():
            if not isinstance(packages, list) or not packages or len(packages) > 64 or any(not isinstance(p, str) or not re.fullmatch('[a-z0-9][a-z0-9+.-]{0,95}', p) for p in packages) or len(set(packages)) != len(packages):
                dependency_reject('PACKAGED_DEPENDENCY_TABLE')
    return list(table[manager][ecosystem])


def dependency_url(value, manager):
    parsed = urllib.parse.urlsplit(value)
    if OS_PROFILE is None or OS_PROFILE['manager'] != manager:
        dependency_reject('OS_SOURCE_PROFILE')
    domains = {'ubuntu': ('ubuntu.com',), 'debian': ('debian.org',), 'centos': ('centos.org',)}[OS_PROFILE['id']]
    if parsed.scheme not in ('http', 'https') or parsed.username is not None or parsed.password is not None or parsed.fragment or not parsed.hostname or parsed.port not in (None, 80, 443):
        dependency_reject('UNSUPPORTED_OS_SOURCE')
    if not any(parsed.hostname == d or parsed.hostname.endswith('.' + d) for d in domains):
        dependency_reject('UNSUPPORTED_OS_SOURCE')


def dependency_key(value, manager):
    if value.startswith('file://'):
        value = value[7:]
    roots = ('/usr/share/keyrings/', '/etc/apt/keyrings/') if manager == 'APT' else ('/etc/pki/rpm-gpg/',)
    if not value.startswith(roots) or '..' in pathlib.Path(value).parts:
        dependency_reject('UNSUPPORTED_OS_KEY')
    content = dependency_bytes(value)
    if manager == 'APT':
        if OS_PROFILE is None or OS_PROFILE['manager'] != 'APT':
            dependency_reject('OS_KEY_PROFILE')
        owner = 'ubuntu-keyring' if OS_PROFILE['id'] == 'ubuntu' else 'debian-archive-keyring'
        if not pathlib.Path(value).name.startswith(('ubuntu-' if OS_PROFILE['id'] == 'ubuntu' else 'debian-archive-')):
            dependency_reject('OS_KEY_PROFILE')
        dependency_registered(value, {owner}, content)
    elif pathlib.Path(value).name not in ('RPM-GPG-KEY-centosofficial', 'RPM-GPG-KEY-CentOS-Official'):
        dependency_reject('OS_KEY_PROFILE')
    else:
        if OS_PROFILE is None or OS_PROFILE['id'] != 'centos' or OS_PROFILE['manager'] != 'DNF':
            dependency_reject('OS_KEY_PROFILE')
        dependency_protected('/var/lib/rpm')
        owner = dependency_run([dependency_tool('rpm'), '-qf', '--qf', '%{NAME}\\n', value]).strip()
        if owner not in ('centos-gpg-keys', 'centos-stream-release'):
            dependency_reject('OS_KEY_OWNER')
        # rpm verifies the baseline registration, including the stored file digest; no vendor code.
        if dependency_run([dependency_tool('rpm'), '-Vf', value]).strip():
            dependency_reject('MODIFIED_OS_KEY')


def dependency_apt_options(value, path=None, effective=False):
    # Includes effective apt-config output. Reject source-dir/key hook overrides and auth relaxation.
    compact = re.sub(r'["\s;]', '', value).lower()
    checks = (r'(?:trusted|allow-unauthenticated|allow-insecure|allow-weak|allow-downgrade-to-insecure)[:=](?:yes|true|1)',
              r'(?:allowunauthenticated|allowinsecurerepositories|allowweakrepositories|allowdowngradetoinsecurerepositories)(?:=)?(?:true|yes|1)',
              r'(?:check-valid-until|checkvaliduntil|verify-peer|verify-host|verifypeer|verifyhost)[:=]?(?:false|no|0)')
    if any(re.search(pattern, compact) for pattern in checks):
        dependency_reject('UNAUTHENTICATED_APT_CONFIGURATION')
    if re.search(r'(?im)^\s*#(?:include|clear)|(?i:DPkg::Options)', value):
        dependency_reject('UNSUPPORTED_APT_CONFIGURATION')
    hooks = re.search(r'(?i)(Pre-Invoke|Post-Invoke|Pre-Install-Pkgs)', value)
    if hooks and not effective:
        if path is None:
            dependency_reject('UNREGISTERED_APT_HOOK')
        dependency_registered(path, HOOK_OWNERS, value.encode('utf-8'))
    expected = {'sourcelist': 'sources.list', 'sourceparts': 'sources.list.d',
                'trusted': 'trusted.gpg', 'trustedparts': 'trusted.gpg.d',
                'main': 'apt.conf', 'parts': 'apt.conf.d', 'netrc': 'auth.conf',
                'netrcparts': 'auth.conf.d', 'preferences': 'preferences',
                'preferencesparts': 'preferences.d'}
    for match in re.finditer(r'(?im)^\s*(Dir(?:::Etc(?:::[a-z]+)?)?)\s+"([^"]*)"\s*;', value):
        key, location = match[1].lower(), match[2]
        if key == 'dir':
            valid = location == '/'
        elif key == 'dir::etc':
            valid = location.rstrip('/') in ('etc/apt', '/etc/apt')
        else:
            leaf = key.split('::')[-1]
            valid = leaf in expected and location.rstrip('/') in (expected[leaf], '/etc/apt/' + expected[leaf])
        if not valid:
            dependency_reject('UNSUPPORTED_APT_CONFIGURATION')
    binaries = {'dpkg': '/usr/bin/dpkg', 'methods': '/usr/lib/apt/methods',
                'solvers': '/usr/lib/apt/solvers', 'planners': '/usr/lib/apt/planners',
                'apt-key': '/usr/bin/apt-key', 'gpg': '/usr/bin/gpg', 'gpgv': '/usr/bin/gpgv'}
    for match in re.finditer(r'(?im)^\s*Dir::Bin::([a-z-]+)\s+"([^"]*)"\s*;', value):
        if match[1].lower() not in binaries or match[2].rstrip('/') != binaries[match[1].lower()]:
            dependency_reject('UNSUPPORTED_APT_BINARY')
    if re.search(r'(?im)^\s*(?:APT::Solver|APT::Planner)\s+"(?!internal")[^"]+"', value):
        dependency_reject('UNSUPPORTED_APT_BINARY')


def dependency_validate_dpkg():
    for name in ('/etc/dpkg/dpkg.cfg', '/etc/dpkg/dpkg.cfg.d'):
        path = pathlib.Path(name)
        if not path.exists():
            continue
        dependency_protected(path)
        files = sorted(path.iterdir()) if path.is_dir() else [path]
        if len(files) > 1024:
            dependency_reject('OS_CONFIGURATION_LIMIT')
        for item in files:
            value = dependency_text(item)
            active = [line.strip() for line in value.splitlines() if line.strip() and not line.lstrip().startswith('#')]
            if any(re.match(r'(?:--)?(?:pre-invoke|post-invoke)\b', line) for line in active):
                dependency_registered(item, HOOK_OWNERS, value.encode())
            for line in active:
                if not re.fullmatch(r'(?:--)?(?:no-debsig|log\s+/var/log/dpkg\.log|abort-after\s+[0-9]+|(?:pre-invoke|post-invoke)\s+.+)', line):
                    dependency_reject('UNSUPPORTED_DPKG_CONFIGURATION')


def dependency_apt_sources(value):
    dependency_apt_options(value)
    count = 0
    if re.search(r'(?m)^Types\s*:', value):
        for paragraph in re.split(r'\n\s*\n', value):
            fields = {}
            for line in paragraph.splitlines():
                if not line.strip() or line.lstrip().startswith('#'):
                    continue
                key, separator, data = line.partition(':')
                if not separator or key.lower() in fields or line[:1].isspace():
                    dependency_reject('APT_SOURCE_SCHEMA')
                fields[key.lower()] = data.strip()
            if not fields or fields.get('enabled', 'yes').lower() == 'no':
                continue
            allowed = {'types', 'uris', 'suites', 'components', 'signed-by', 'enabled', 'architectures',
                       'trusted', 'check-valid-until', 'by-hash'}
            if not {'types', 'uris', 'suites'} <= fields.keys() or not fields.keys() <= allowed or any(v not in ('deb', 'deb-src') for v in fields['types'].split()):
                dependency_reject('APT_SOURCE_SCHEMA')
            for uri in fields['uris'].split():
                dependency_url(uri, 'APT')
                count += 1
            for key in fields.get('signed-by', '').split():
                dependency_key(key, 'APT')
    else:
        for line in value.splitlines():
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            match = re.fullmatch(r'deb(?:-src)?\s+(?:\[([^\]]+)\]\s+)?(\S+)\s+(\S+)(?:\s+.*)?', line)
            if not match:
                dependency_reject('APT_SOURCE_SCHEMA')
            if match[1]:
                for token in match[1].split():
                    key, separator, option = token.partition('=')
                    if not separator or key not in ('arch', 'signed-by', 'trusted', 'check-valid-until', 'by-hash'):
                        dependency_reject('APT_SOURCE_OPTION')
                    if key == 'signed-by':
                        dependency_key(option, 'APT')
            dependency_url(match[2], 'APT')
            count += 1
    return count


def dependency_validate_apt():
    dependency_protected('/etc/apt')
    dependency_protected('/usr/lib/apt/methods')
    count = 0
    for name in ('apt.conf', 'sources.list'):
        path = pathlib.Path('/etc/apt') / name
        if path.exists() or path.is_symlink():
            text = dependency_text(path)
            if name == 'sources.list':
                count += dependency_apt_sources(text)
            else:
                dependency_apt_options(text, path)
    for name in ('apt.conf.d', 'sources.list.d'):
        path = pathlib.Path('/etc/apt') / name
        if not path.exists():
            continue
        dependency_protected(path)
        files = sorted(path.iterdir())
        if len(files) > 1024:
            dependency_reject('OS_CONFIGURATION_LIMIT')
        for item in files:
            text = dependency_text(item)
            if name == 'sources.list.d' and item.suffix in ('.list', '.sources'):
                count += dependency_apt_sources(text)
            elif name == 'apt.conf.d':
                dependency_apt_options(text, item)
    if count < 1:
        dependency_reject('AUTHENTICATED_OS_SOURCE_REQUIRED')
    dependency_apt_options(dependency_run([dependency_tool('apt-config'), 'dump']), effective=True)
    # Unqualified sources use only the profile's registered protected archive keyrings.
    trusted = pathlib.Path('/etc/apt/trusted.gpg')
    if trusted.exists():
        dependency_reject('UNQUALIFIED_LEGACY_KEYRING')
    trusted_parts = pathlib.Path('/etc/apt/trusted.gpg.d')
    if trusted_parts.exists():
        dependency_protected(trusted_parts)
        for key in trusted_parts.iterdir():
            if key.suffix in ('.gpg', '.asc'):
                content = dependency_bytes(key)
                owner = 'ubuntu-keyring' if OS_PROFILE['id'] == 'ubuntu' else 'debian-archive-keyring'
                dependency_registered(key, {owner}, content)
    dependency_validate_dpkg()


def dependency_dnf_config(value, repository=False):
    parser = configparser.ConfigParser(interpolation=None, strict=True)
    try:
        parser.read_string(value)
    except configparser.Error:
        dependency_reject('DNF_CONFIGURATION_SCHEMA')
    count = 0
    for section in parser.sections():
        fields = dict(parser[section])
        if any(k in fields for k in ('include', 'reposdir', 'pluginpath', 'pluginconfpath', 'tsflags')):
            dependency_reject('UNSUPPORTED_DNF_CONFIGURATION')
        for key in ('gpgcheck', 'localpkg_gpgcheck', 'repo_gpgcheck', 'sslverify'):
            if key in fields and fields[key].lower() not in ('1', 'true', 'yes'):
                dependency_reject('UNAUTHENTICATED_DNF_CONFIGURATION')
        if not repository or fields.get('enabled', '1').lower() in ('0', 'false', 'no'):
            continue
        if fields.get('gpgcheck', '').lower() not in ('1', 'true', 'yes') or not fields.get('gpgkey'):
            dependency_reject('AUTHENTICATED_DNF_REPOSITORY_REQUIRED')
        locations = [v for key in ('baseurl', 'metalink', 'mirrorlist') for v in fields.get(key, '').split()]
        if not locations:
            dependency_reject('DNF_REPOSITORY_LOCATION')
        for url in locations:
            dependency_url(url, 'DNF')
        for key in fields['gpgkey'].split():
            dependency_key(key, 'DNF')
        count += 1
    return count


def dependency_validate_dnf():
    for name in ('/etc/dnf/dnf.conf', '/etc/yum.conf'):
        path = pathlib.Path(name)
        if path.exists() or path.is_symlink():
            dependency_dnf_config(dependency_text(path))
    root = dependency_protected('/etc/yum.repos.d')
    files = sorted(root.iterdir())
    if len(files) > 1024:
        dependency_reject('OS_CONFIGURATION_LIMIT')
    count = sum(dependency_dnf_config(dependency_text(path), True) for path in files if path.suffix == '.repo')
    if count < 1:
        dependency_reject('AUTHENTICATED_OS_SOURCE_REQUIRED')


def dependency_versions(output, packages):
    versions = {}
    for line in output.splitlines():
        values = line.split('\t')
        if len(values) != 2 or values[0] not in packages or values[0] in versions or not re.fullmatch('[A-Za-z0-9:~+._-]{1,128}', values[1]):
            dependency_reject('DEPENDENCY_VERSION_SCHEMA', True)
        versions[values[0]] = values[1]
    if set(versions) != set(packages):
        dependency_reject('DEPENDENCY_VERSION_INCOMPLETE', True)
    return versions


def dependency_main(args):
    global OS_PROFILE
    if os.geteuid() != 0 or len(args) != 2 or args[0] not in ('JAVA', 'NODE', 'PYTHON', 'DOTNET', 'KOTLIN', 'GO', 'RUST', 'PHP', 'RUBY') or not re.fullmatch('[a-f0-9]{32}', args[1]):
        dependency_reject('DEPENDENCY_ARGUMENT_FRAME')
    ecosystem, operation = args
    manager, versions = 'NONE', {}
    if ecosystem in ('PYTHON', 'PHP', 'RUBY'):
        OS_PROFILE = dependency_profile()
        manager = OS_PROFILE['manager']
        packages = dependency_packages(manager, ecosystem)
        if manager == 'APT':
            dependency_validate_apt()
            flags = ['-o', 'APT::Get::AllowUnauthenticated=false', '-o', 'Acquire::AllowInsecureRepositories=false',
                     '-o', 'Acquire::AllowWeakRepositories=false', '-o', 'Acquire::AllowDowngradeToInsecureRepositories=false',
                     '-o', 'Acquire::Check-Valid-Until=true', '-o', 'DPkg::Lock::Timeout=120']
            dependency_run([dependency_tool('apt-get'), *flags, 'update'])
            dependency_run([dependency_tool('apt-get'), *flags, 'install', '-y', '--no-install-recommends', *packages])
            output = dependency_run([dependency_tool('dpkg-query'), '-W', '-f=' + '$' + '{Package}\\t' + '$' + '{Version}\\n', *packages])
        else:
            dependency_validate_dnf()
            flags = ['--disableplugin=*', '--setopt=gpgcheck=1', '--setopt=localpkg_gpgcheck=1', '--setopt=sslverify=1']
            if OS_PROFILE['id'] == 'centos':
                flags += ['--enablerepo=crb']
            dependency_run([dependency_tool('dnf'), *flags, 'install', '-y', *packages])
            output = dependency_run([dependency_tool('rpm'), '-q', '--qf', '%{NAME}\\t%{EPOCHNUM}:%{VERSION}-%{RELEASE}.%{ARCH}\\n', *packages])
        versions = dependency_versions(output, packages)
    print('TOOLCHAIN_DEPENDENCIES=' + json.dumps({'operation': operation, 'ecosystem': ecosystem,
          'manager': manager, 'packages': versions}, sort_keys=True, separators=(',', ':')))


if __name__ == '__main__':
    try:
        dependency_main(sys.argv[1:])
    except DependencyFailure as error:
        print('TOOLCHAIN_FAILURE=' + ('unknown' if error.unknown else 'dependencies') + ':' + error.detail)
        sys.exit(70 if error.unknown else 65)
    except (OSError, ValueError, subprocess.TimeoutExpired):
        print('TOOLCHAIN_FAILURE=unknown:DEPENDENCY_STATE_UNCONFIRMED')
        sys.exit(70)
