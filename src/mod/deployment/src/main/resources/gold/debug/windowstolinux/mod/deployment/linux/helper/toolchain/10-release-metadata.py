"""Product-owned official toolchain preparation. Branch admission belongs to the Java catalog.

The remote entry point accepts typed ecosystem, branch, exact version and timeout scalars.
It never accepts commands, URLs, install paths or source-controlled scripts as arguments.
"""

import base64
import hashlib
import gzip
import io
import html
import json
import os
import pathlib
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile

ROOT = pathlib.Path('@wtl:helper-directory@/toolchains')
MAX_METADATA = 32 * 1024 * 1024
MAX_ARCHIVE = 2 * 1024 * 1024 * 1024
MIRROR_DEFAULT_SOURCE_ORIGINS = __WTL_MIRROR_DEFAULT_SOURCE_ORIGINS__
MIRROR_DEFAULT_ALLOWED_HOSTS = set(__WTL_MIRROR_DEFAULT_ALLOWED_HOSTS__)


class PreparationFailure(Exception):
    def __init__(self, category, detail):
        self.category, self.detail = category, detail
        super().__init__(detail)


def fail(category, detail):
    raise PreparationFailure(category, detail)


def official_url(url, historical=False):
    rejection = 'release URL is outside official HTTPS sources'
    try:
        parsed = urllib.parse.urlsplit(url)
        port = parsed.port
    except ValueError:
        fail('metadata', rejection)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or '#' in url or parsed.netloc.endswith(':')
            or any(ord(char) <= 32 or ord(char) == 127 for char in url)):
        fail('metadata', rejection)
    if historical and os.environ.get('WTL_MIRROR_POLICY'):
        # A verified installation record may name a source from an older frozen policy.
        task_mirrors()
        return url
    if port not in (None, 443) or parsed.hostname not in MIRROR_DEFAULT_ALLOWED_HOSTS:
        fail('metadata', rejection)
    # Frozen routes use the canonical spelling of the HTTPS origin.
    return parsed._replace(netloc=parsed.hostname).geturl()


class OfficialRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return super().redirect_request(req, fp, code, msg, headers, official_url(newurl))


MIRROR_POINT = None
MIRROR_METADATA_POINT = None
MIRROR_MODULE = None


def task_mirrors():
    global MIRROR_MODULE
    if not os.environ.get('WTL_MIRROR_POLICY'):
        return None, None
    if MIRROR_MODULE is None:
        runtime = pathlib.Path(os.environ['WTL_MIRROR_RUNTIME'])
        policy_path = pathlib.Path(os.environ['WTL_MIRROR_POLICY'])
        if runtime.parent != policy_path.parent or runtime.name != 'runtime.py' or policy_path.name != 'policy.json':
            fail('ownership', 'mirror runtime and policy must share the task-owned private directory')
        parent = runtime.parent.lstat()
        if runtime.parent.is_symlink() or parent.st_uid != 0 or parent.st_mode & 0o077:
            fail('ownership', 'privileged mirror runtime directory is not root-private')
        for ancestor in runtime.parent.parents:
            identity = ancestor.lstat()
            if ancestor.is_symlink() or identity.st_uid != 0 or identity.st_mode & 0o022 and not identity.st_mode & 0o1000:
                fail('ownership', 'privileged mirror input has a replaceable ancestor')
        payload = None
        for path in (runtime, policy_path):
            with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as stream:
                identity = os.fstat(stream.fileno())
                if identity.st_uid != 0 or identity.st_mode & 0o022 or identity.st_nlink != 1 or not __import__('stat').S_ISREG(identity.st_mode):
                    fail('ownership', 'privileged mirror input is not an immutable root-owned file')
                data = stream.read(4194305)
                if len(data) > 4194304:
                    fail('ownership', 'privileged mirror input is too large')
                if path == runtime:
                    payload = data
        runtime_sha256 = hashlib.sha256(payload).hexdigest()
        if runtime_sha256 != '__WTL_MIRROR_RUNTIME_SHA256__':
            fail('integrity', 'privileged mirror runtime differs from the shipped implementation')
        MIRROR_MODULE = {'__name__': 'wtl_task_mirrors', 'MIRROR_RUNTIME_SHA256': runtime_sha256}
        exec(compile(payload, str(runtime), 'exec'), MIRROR_MODULE)
    policy = MIRROR_MODULE['MirrorPolicy'].from_environment(globals().get('DEADLINE'))
    return MIRROR_MODULE, policy


def source_origin(point, index=0):
    _, policy = task_mirrors()
    origins = (policy.endpoints(point)[0].get('sourceOrigins', []) if policy is not None
               else MIRROR_DEFAULT_SOURCE_ORIGINS.get(point, []))
    if index >= len(origins):
        fail('metadata', 'configured source origin is absent for ' + point)
    return origins[index]


def source_url(point, relative, index=0):
    return source_origin(point, index).rstrip('/') + '/' + relative.lstrip('/')


def toolchain_point(ecosystem):
    return {'JAVA': 'JAVA_JDK', 'NODE': 'NODE_DISTRIBUTION', 'GO': 'GO_DISTRIBUTION',
            'DOTNET': 'DOTNET_SDK', 'KOTLIN': 'KOTLIN_DISTRIBUTION', 'RUST': 'RUST_TOOLCHAIN',
            'PYTHON': 'PYTHON_DISTRIBUTION', 'PHP': 'PHP_DISTRIBUTION', 'RUBY': 'RUBY_DISTRIBUTION'}.get(ecosystem)


def fetch(url, limit=MAX_METADATA, point=None, validator=None):
    module, policy = task_mirrors()
    if policy is not None:
        try:
            return module['mirror_bytes'](policy, point or MIRROR_METADATA_POINT, official_url(url), limit,
                                           validator=validator)[0]
        except module['MirrorFailure'] as error:
            fail('network', str(error))
    request = urllib.request.Request(
        official_url(url), headers={'User-Agent': 'WindowsToLinux-toolchains/1', 'Accept-Encoding': 'identity'}
    )
    try:
        with urllib.request.build_opener(OfficialRedirect()).open(request, timeout=45) as response:
            data = response.read(limit + 1)
            if response.headers.get('Content-Encoding') == 'gzip':
                data = gzip.GzipFile(fileobj=io.BytesIO(data)).read(limit + 1)
            if len(data) > limit:
                fail('metadata', 'official response exceeds size limit')
            return data
    except (OSError, ValueError) as error:
        fail('network', type(error).__name__ + ': official source request failed')


def json_at(url, validator=None):
    try:
        def validate_json(path, headers):
            document = json.loads(path.read_bytes())
            if validator is not None:
                validator(document)
        return json.loads(fetch(url, validator=validate_json))
    except (ValueError, UnicodeError):
        fail('metadata', 'invalid official JSON')


def download(url, target, algorithm, expected):
    module, policy = task_mirrors()
    if policy is not None:
        try:
            point = 'RUBYGEMS' if any(url.startswith(origin) for origin in MIRROR_DEFAULT_SOURCE_ORIGINS.get('RUBYGEMS', [])) else MIRROR_POINT
            return module['mirror_transfer'](policy, point, official_url(url), target,
                                             algorithm, expected, MAX_ARCHIVE)['sha256']
        except module['MirrorFailure'] as error:
            fail('network', str(error))
    official = hashlib.new(algorithm)
    sha256 = hashlib.sha256()
    total = 0
    try:
        request = urllib.request.Request(official_url(url), headers={'User-Agent': 'WindowsToLinux-toolchains/1'})
        with (
            urllib.request.build_opener(OfficialRedirect()).open(request, timeout=45) as response,
            target.open('xb') as output,
        ):
            while True:
                remaining()
                block = response.read(1024 * 1024)
                if not block:
                    break
                total += len(block)
                if total > MAX_ARCHIVE:
                    fail('archive', 'archive exceeds the download size limit')
                official.update(block)
                sha256.update(block)
                output.write(block)
    except OSError as error:
        fail('network', type(error).__name__ + ': official artifact download failed')
    if official.hexdigest() != expected:
        fail('integrity', 'download does not match the official release digest')
    return sha256.hexdigest()


def version_key(version):
    value = re.sub(r'^(go|jdk-|v)', '', version)
    value = re.sub(r'^(\d+)u(\d+)', r'\1.0.\2', value)
    if value.startswith('1.8.') and ('_' in value or '-b' in value):
        value = value[2:].replace('_', '.')
    value = re.sub(r'-b\d+$', '', value)
    if not re.fullmatch(r'\d+(?:\.\d+)*(?:\+\d+(?:\.\d+)*)?', value):
        return None
    return tuple(int(n) for n in value.split('+')[0].split('.'))


def release_build(version):
    match = re.search(r'(?:\+|-b)(\d+(?:\.\d+)*)$', version)
    return tuple(int(n) for n in match.group(1).split('.')) if match else ()


def choose(versions, branch, exact):
    prefix = tuple(int(n) for n in branch.split('.'))
    candidates = [
        v
        for v in versions
        if version_key(v)
        and version_key(v)[: len(prefix)] == prefix
        and (
            not exact
            or (
                version_key(v) == version_key(exact)
                and (not release_build(exact) or release_build(v) == release_build(exact))
            )
        )
    ]
    if not candidates:
        fail('unavailable', 'no official stable artifact matches the selected branch or exact release')
    return max(candidates, key=lambda v: (version_key(v), release_build(v)))


def checksum_text(url, name=None):
    def validate_checksum(path, headers):
        for line in path.read_text(encoding='utf-8').splitlines():
            parts = line.split()
            if parts and re.fullmatch('[a-fA-F0-9]{64}', parts[0]) and (name is None or parts[-1].lstrip('*') == name):
                return
        raise ValueError('selected source SHA-256 is absent')
    text = fetch(url, validator=validate_checksum).decode('utf-8')
    for line in text.splitlines():
        parts = line.split()
        if parts and re.fullmatch('[a-fA-F0-9]{64}', parts[0]) and (name is None or parts[-1].lstrip('*') == name):
            return parts[0].lower()
    fail('metadata', 'official SHA-256 is absent')


def html_hash(page, filename):
    for row in re.findall(r'<tr\b[^>]*>.*?</tr>', page, re.S | re.I):
        if filename in row:
            hashes = re.findall(r'\b[a-fA-F0-9]{64}\b', html.unescape(re.sub('<[^>]+>', ' ', row)))
            if len(hashes) == 1:
                return hashes[0].lower()
    fail('metadata', 'official release page has no unambiguous SHA-256 for this archive')


def java_release_identity(data):
    version = data['openjdk_version'].removesuffix('-LTS')
    numbers, build = version_key(version), release_build(version)
    if numbers is None:
        fail('metadata', 'invalid official OpenJDK stable release identity')
    return '.'.join(map(str, numbers)) + ('+' + '.'.join(map(str, build)) if build else '')


def release(ecosystem, branch, exact):
    """Returns exact identity, official artifact URL, algorithm, expected digest, layout."""
    global MIRROR_POINT, MIRROR_METADATA_POINT
    MIRROR_POINT = toolchain_point(ecosystem)
    MIRROR_METADATA_POINT = {'JAVA': 'JAVA_METADATA', 'NODE': 'NODE_METADATA', 'GO': 'GO_METADATA',
                             'DOTNET': 'DOTNET_METADATA', 'KOTLIN': 'KOTLIN_METADATA', 'RUST': 'RUST_METADATA',
                             'PYTHON': 'PYTHON_METADATA', 'PHP': 'PHP_METADATA', 'RUBY': 'RUBY_METADATA'}.get(ecosystem)
    if ecosystem == 'JAVA':
        assets = json_at(
            source_url(MIRROR_METADATA_POINT, 'v3/assets/feature_releases/')
            + branch
            + '/ga?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=linux&page_size=100&vendor=eclipse'
        )
        versions = {java_release_identity(a['version_data']): a for a in assets}
        v = choose(versions, branch, exact)
        package = next(
            b['package'] for b in versions[v]['binaries'] if b['os'] == 'linux' and b['architecture'] == 'x64'
        )
        return v, package['link'], 'sha256', package['checksum'], 'archive'
    if ecosystem == 'NODE':
        def validate_node_index(document):
            if not isinstance(document, list):
                raise ValueError('Node release index is not an array')
            prefix = tuple(int(n) for n in branch.split('.'))
            versions = [item.get('version') for item in document if isinstance(item, dict) and isinstance(item.get('version'), str)]
            if len(versions) != len(document):
                raise ValueError('Node release index has invalid version entries')
            if not any(version_key(v) and version_key(v)[:len(prefix)] == prefix
                       and (not exact or version_key(v) == version_key(exact)) for v in versions):
                raise ValueError('Node source has no selected stable release')
        v = choose([a['version'] for a in json_at(source_url(MIRROR_METADATA_POINT, 'index.json'), validate_node_index)], branch, exact)
        name = 'node-' + v + '-linux-x64.tar.gz'
        base = source_url(MIRROR_POINT, v + '/')
        return v.lstrip('v'), base + name, 'sha256', checksum_text(base + 'SHASUMS256.txt', name), 'archive'
    if ecosystem == 'GO':
        assets = {a['version']: a for a in json_at(source_url(MIRROR_METADATA_POINT, '?mode=json&include=all')) if a['stable']}
        v = choose(assets, branch, exact)
        f = next(
            f for f in assets[v]['files'] if f['os'] == 'linux' and f['arch'] == 'amd64' and f['kind'] == 'archive'
        )
        return v[2:], source_url(MIRROR_POINT, f['filename']), 'sha256', f['sha256'], 'archive'
    if ecosystem == 'DOTNET':
        data = json_at(source_url(MIRROR_METADATA_POINT, branch + '.0/releases.json'))
        sdks = {s['version']: s for r in data['releases'] for s in r.get('sdks', [r['sdk']])}
        v = choose(sdks, branch, exact)
        f = next(f for f in sdks[v]['files'] if f['rid'] == 'linux-x64' and f['name'].endswith('.tar.gz'))
        return v, official_url(f['url']), 'sha512', f['hash'].lower(), 'flat'
    if ecosystem == 'KOTLIN':
        # Query exact tags when pinned; bounded pagination covers historical branches without a version whitelist.
        assets = []
        if exact:
            assets = [json_at(source_url(MIRROR_METADATA_POINT, 'releases/tags/v' + exact))]
        else:
            refs = json_at(source_url(MIRROR_METADATA_POINT, 'git/matching-refs/tags/v' + branch + '.'))
            tag = choose([r['ref'][len('refs/tags/v') :] for r in refs], branch, '')
            assets = [json_at(source_url(MIRROR_METADATA_POINT, 'releases/tags/v' + tag))]
        versions = {a['tag_name'].lstrip('v'): a for a in assets if not a['prerelease'] and not a['draft']}
        v = choose(versions, branch, exact)
        name = 'kotlin-compiler-' + v + '.zip'
        f = next(f for f in versions[v]['assets'] if f['name'] == name)
        digest = f.get('digest') or ''
        if not digest.startswith('sha256:'):
            sidecar = next(
                (f for f in versions[v]['assets'] if f['name'] in (name + '.sha256', name + '.sha256.txt')), None
            )
            if sidecar is None:
                sums = re.findall(re.escape(name) + r'[^\r\n]*?\b([a-fA-F0-9]{64})\b', versions[v].get('body', ''))
                if len(sums) != 1:
                    fail('metadata', 'Kotlin release has no unambiguous official SHA-256 metadata')
                digest = 'sha256:' + sums[0].lower()
            else:
                digest = 'sha256:' + checksum_text(sidecar['browser_download_url'])
        return v, f['browser_download_url'], 'sha256', digest[7:], 'archive'
    if ecosystem == 'RUST':
        # Stable channel manifests for an explicit branch are official release metadata and include artifact hashes.
        # Rust stable releases normally use x.y.0; resolve any patch tags through the official release API.
        tags = []
        if exact:
            tags = [exact]
        else:
            for page in range(1, 21):
                batch = json_at(source_url(MIRROR_METADATA_POINT, 'releases?per_page=100&page=' + str(page)))
                tags.extend(a['tag_name'] for a in batch if not a['prerelease'] and not a['draft'])
                if any(t.startswith(branch + '.') for t in tags) or len(batch) < 100:
                    break
        v = choose(tags, branch, exact)
        url = source_url(MIRROR_POINT, 'dist/rust-' + v + '-x86_64-unknown-linux-gnu.tar.gz')
        return v, url, 'sha256', checksum_text(url + '.sha256'), 'rust'
    if ecosystem == 'PYTHON':
        listing = fetch(source_origin(MIRROR_METADATA_POINT)).decode('utf-8')
        v = choose(re.findall(r'href="(\d+\.\d+\.\d+)/"', listing), branch, exact)
        name = 'Python-' + v + '.tgz'
        page = fetch(source_url(MIRROR_METADATA_POINT, 'python-' + v.replace('.', '') + '/', 1)).decode('utf-8')
        url = source_url(MIRROR_POINT, v + '/' + name)
        try:
            digest = html_hash(page, name)
        except PreparationFailure:
            # This is a checksum obtained over the official HTTPS channel, not a claim of Sigstore signature verification.
            bundle = json_at(url + '.sigstore')
            message = bundle.get('messageSignature', {}).get('messageDigest', {})
            if message.get('algorithm') != 'SHA2_256':
                fail('metadata', 'Python official bundle has no SHA-256 message digest')
            digest = base64.b64decode(message['digest'], validate=True).hex()
        return v, url, 'sha256', digest, 'python'
    if ecosystem == 'PHP':
        data = json_at(source_url(MIRROR_METADATA_POINT, 'index.php?json&version=' + branch.split('.')[0] + '&max=1000'))
        v = choose(data, branch, exact)
        f = next(f for f in data[v]['source'] if f['filename'].endswith('.tar.gz'))
        return v, source_url(MIRROR_POINT, f['filename']), 'sha256', f['sha256'], 'php'
    if ecosystem == 'RUBY':
        page = fetch(source_origin(MIRROR_METADATA_POINT)).decode('utf-8')
        versions = re.findall(r'ruby-(\d+\.\d+\.\d+)\.tar\.gz', page)
        v = choose(versions, branch, exact)
        name = 'ruby-' + v + '.tar.gz'
        row = next(row for row in re.findall(r'<tr\b[^>]*>.*?</tr>', page, re.S | re.I) if name in row)
        news = re.search(r'href="(/en/news/[^"]+)"', row)
        if news is None:
            fail('metadata', 'Ruby release announcement is absent')
        announcement = fetch(urllib.parse.urljoin(source_origin(MIRROR_METADATA_POINT), news.group(1))).decode('utf-8')
        entries = [item for item in re.findall(r'<li\b[^>]*>.*?</li>', announcement, re.S | re.I) if name in item]
        sums = re.findall(r'SHA256:\s*([a-fA-F0-9]{64})', ''.join(entries))
        if len(sums) != 1:
            fail('metadata', 'Ruby announcement has no unambiguous SHA-256')
        return v, source_url(MIRROR_POINT, branch + '/' + name), 'sha256', sums[0].lower(), 'ruby'
    fail('request', 'unsupported installation adapter')
