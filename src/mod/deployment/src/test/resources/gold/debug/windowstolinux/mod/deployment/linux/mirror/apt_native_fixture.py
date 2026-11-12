"""Mock only the native APT boundary; its metadata and package requests use real loopback HTTP."""
import hashlib
import json
import os
import pathlib
import sys
import urllib.parse
import urllib.request

sys.stdout.reconfigure(newline='\n')
sys.stderr.reconfigure(newline='\n')
mode, provider, folder, cache, log, server, error = sys.argv[1:8]
arguments = sys.argv[8:]
folder, cache = pathlib.Path(folder), pathlib.Path(cache)
phase = (mode if mode != 'native' else 'plan' if '--print-uris' in arguments
         else 'install' if '--no-download' in arguments else 'update')
source = next((folder / 'repos').glob('fixture.*')).read_text()
with pathlib.Path(log).open('a', encoding='utf-8') as output:
    output.write(json.dumps({'phase': phase, 'source': provider, 'arguments': arguments,
        'aptConfig': os.environ.get('APT_CONFIG', ''),
        'aptConfigText': (folder / 'apt.conf').read_text(),
        'configurationParts': [path.name for path in (folder / 'apt.conf.d').iterdir()],
        'sourceText': source}) + '\n')
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
if phase == 'update':
    with opener.open(server + '/' + provider + '/index.json', timeout=3) as response:
        (folder / 'metadata.json').write_bytes(response.read())
elif phase in ('plan', 'query'):
    available = '1-1' in json.loads((folder / 'metadata.json').read_bytes())['versions']
    if phase == 'query':
        print('demo:\n  Installed: (none)\n  Candidate: ' + ('1-1' if available else '(none)'))
    elif error == 'permission':
        print('E: Unable to locate package demo', file=sys.stderr)
        print('E: Could not open lock file /var/lib/dpkg/lock - open (13: Permission denied)', file=sys.stderr)
        sys.exit(100)
    elif not available:
        print("E: Version '1-1' for 'demo' was not found", file=sys.stderr)
        sys.exit(100)
    else:
        payload = b'locked-archive-content' * 2048
        selected = next(value for line in source.splitlines() if not line.lstrip().startswith('#')
                        for value in line.split() if value.startswith(('http://', 'https://')))
        print("'" + selected.rstrip('/') + "/demo.deb' demo_1-1_amd64.deb " + str(len(payload))
              + ' SHA256:' + hashlib.sha256(payload).hexdigest())
elif phase == 'download':
    _, selected, target, expected = arguments
    parsed = urllib.parse.urlsplit(selected)
    if parsed.scheme not in ('http', 'https') or parsed.hostname not in ('apt-fixture.invalid', '::1') or not parsed.path.startswith('/' + provider + '/'):
        sys.exit(65)
    try:
        with opener.open(server + parsed.path, timeout=3) as response:
            payload = response.read()
    except OSError:
        sys.exit(1)
    if expected != 'SHA256:' + hashlib.sha256(payload).hexdigest():
        sys.exit(2)
    pathlib.Path(target).write_bytes(payload)
elif phase == 'install':
    target = cache / 'archives' / 'demo_1-1_amd64.deb'
    if not target.is_file():
        sys.exit(100)
    if error == 'post-install-no-match':
        print('E: Unable to locate package demo', file=sys.stderr)
        sys.exit(100)
else:
    sys.exit(65)
