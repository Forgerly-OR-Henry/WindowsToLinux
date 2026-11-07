"""Named, hashed pip installs through the production gateway using only synthetic loopback data."""
import base64
import contextlib
import ctypes
import datetime
import hashlib
import html
import html.parser
import http.server
import io
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import unittest.mock
import urllib.error
import urllib.parse
import urllib.request
import zipfile

RUNTIME = {'__name__': 'wtl_python_index_test'}
exec(compile(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'), sys.argv[1], 'exec'), RUNTIME)
Gateway, Policy = RUNTIME['RepositoryGateway'], RUNTIME['MirrorPolicy']
NAME = 'example-1.0-py3-none-any.whl'
METADATA = b'Metadata-Version: 2.1\nName: example\nVersion: 1.0\n\n'


def wheel():
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w') as archive:
        files = {'example.py': b'VALUE = "synthetic-named-index"\n',
                 'example-1.0.dist-info/METADATA': METADATA,
                 'example-1.0.dist-info/WHEEL': b'Wheel-Version: 1.0\nGenerator: fixture\nRoot-Is-Purelib: true\nTag: py3-none-any\n',
                 'example-1.0.dist-info/RECORD': b''}
        for name, data in files.items():
            archive.writestr(zipfile.ZipInfo(name, (2020, 1, 1, 0, 0, 0)), data)
    return stream.getvalue()


WHEEL = wheel()
WHEEL_SHA = hashlib.sha256(WHEEL).hexdigest()
METADATA_SHA = hashlib.sha256(METADATA).hexdigest()
MIXED_PREFIX = '\r' * 2048 + '\v\f\u0085\u2028\n'


def simple_anchor(link, style='standard'):
    href = html.escape(link + '#sha256=' + WHEEL_SHA, quote=True)
    attributes = (' data-requires-python="&gt;=3.8" data-core-metadata="sha256=' + METADATA_SHA
                  + '" data-dist-info-metadata="sha256=' + METADATA_SHA + '"')
    if style == 'slash':
        return '<a/href="' + href + '"' + attributes + '>' + NAME + '</a>'
    if style == 'adjacent':
        return '<a href="ignored.whl"href="' + href + '"' + attributes + '>' + NAME + '</a>'
    if style == 'quoted_attr':
        return '<a title=\'note href="untouched.whl"\' data-empty href="' + href + '"' + attributes + '>' + NAME + '</a>'
    prefix = MIXED_PREFIX if style == 'mixed_newline' else ''
    return prefix + '<a href="' + href + '"' + attributes + '>' + NAME + '</a>'


def identity(pid):
    result = {'pid': str(pid), 'birthUtc7': 'UNKNOWN', 'birthFiletime': 'UNKNOWN'}
    if os.name == 'nt':
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.restype = ctypes.c_void_p
        handle = kernel.OpenProcess(0x1000, False, pid)
        if handle:
            created, exited, system, user = (ctypes.c_uint64() for _ in range(4))
            if kernel.GetProcessTimes(ctypes.c_void_p(handle), ctypes.byref(created), ctypes.byref(exited), ctypes.byref(system), ctypes.byref(user)):
                ticks = created.value
                utc = datetime.datetime(1601, 1, 1) + datetime.timedelta(microseconds=ticks // 10)
                result.update(birthFiletime=str(ticks), birthUtc7=utc.strftime('%Y-%m-%dT%H:%M:%S.') + ('%07d' % (ticks % 10000000)) + 'Z')
            kernel.CloseHandle(ctypes.c_void_p(handle))
    return result


class Fixture(http.server.BaseHTTPRequestHandler):
    def finish(self):
        super().finish()
        if os.name == 'nt':
            time.sleep(.05)

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.server.seen.append(self.path)
        path = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path)
        if path.startswith('/missing/'):
            self.send_error(404)
            return
        if path.endswith('/simple/example/'):
            link = self.server.origin + 'packages/' + NAME + self.server.query
            if self.server.mode == 'json':
                data = json.dumps({'meta': {'api-version': '1.1'}, 'name': 'example', 'files': [{
                    'filename': NAME, 'url': link, 'hashes': {'sha256': WHEEL_SHA},
                    'requires-python': '>=3.8', 'yanked': False,
                    'core-metadata': {'sha256': METADATA_SHA}}]}).encode()
                content = 'application/vnd.pypi.simple.v1+json'
            else:
                data = ('<!doctype html>' + simple_anchor(link, self.server.style)).encode()
                content = 'text/html; charset=utf-8'
        elif path.endswith('/' + NAME + '.metadata'):
            data = b'corrupt metadata' if self.server.corrupt_metadata else METADATA
            content = 'application/octet-stream'
        elif path.endswith('/' + NAME):
            data, content = WHEEL, 'application/octet-stream'
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', content)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class PythonIndexTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='python-index-', dir=os.environ['TEMP'])
        self.path = pathlib.Path(self.temp.name)
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
        self.server.daemon_threads = False
        self.server.mode, self.server.seen, self.server.corrupt_metadata = 'html', [], False
        self.server.query = '?ticket=a%2Fb&mode=one'
        self.server.origin, self.server.style = 'https://python.invalid/', 'standard'
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.actors = []

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)
        self.assertFalse(self.thread.is_alive())
        self.assertEqual(-1, self.server.socket.fileno())
        for process in self.actors:
            self.assertIsNotNone(process.poll(), 'owned pip must have exited')
        self.temp.cleanup()
        print('PYTHON_INDEX_CLOSURE upstreamThreadExited=true upstreamSocketClosed=true ownedPipExited=true', flush=True)

    def policy(self, sources=('good',)):
        root = 'http://127.0.0.1:' + str(self.server.server_port)
        entries = [{'providerId': name, 'protocol': 'HTTP', 'baseUrl': root + '/' + name + '/simple/',
                    'allowedHosts': ['127.0.0.1'], 'official': False, 'custom': True,
                    'sourceOrigins': [self.server.origin + 'simple/', self.server.origin + 'packages/'],
                    'routes': [{'origin': self.server.origin + 'simple/', 'prefix': root + '/' + name + '/simple/'},
                               {'origin': self.server.origin + 'packages/', 'prefix': root + '/' + name + '/packages/'}]}
                   for name in sources]
        return Policy({'formatVersion': 1, 'points': {'PYTHON_PACKAGE': entries}, 'timeouts': {
            'connectSeconds': 2, 'readSeconds': 4, 'noProgressSeconds': 4,
            'lowSpeedWindowSeconds': 4, 'lowSpeedBytesPerSecond': 1, 'attemptsPerEndpoint': 1}})

    @contextlib.contextmanager
    def gateway(self, policy=None):
        with Gateway(policy or self.policy(), self.path) as gateway:
            yield gateway
        self.assertFalse(gateway.thread.is_alive())
        self.assertEqual(-1, gateway.server.socket.fileno())
        print('PYTHON_INDEX_CLOSURE gatewayThreadExited=true gatewaySocketClosed=true', flush=True)

    def install_named(self, mode, expected_hash=WHEEL_SHA, style='standard', loopback_origin=False):
        self.server.mode = mode
        self.server.query = ''
        self.server.style = style
        self.server.seen.clear()
        if loopback_origin:
            self.server.origin = 'http://127.0.0.1:' + str(self.server.server_port) + '/origin/'
        lock = self.path / 'requirements.lock'
        locked = ('example==1.0 --hash=sha256:' + expected_hash + '\n').encode()
        lock.write_bytes(locked)
        original = self.path / NAME
        original.write_bytes(WHEEL)
        target = self.path / ('installed-' + style)
        native_popen = subprocess.Popen
        def tracked(*args, **kwargs):
            process = native_popen(*args, **kwargs)
            self.actors.append(process)
            print('PYTHON_INDEX_CHILD ' + json.dumps(identity(process.pid), sort_keys=True), flush=True)
            return process
        command = [sys.executable, '-B', '-m', 'pip', 'install', '--disable-pip-version-check', '--no-cache-dir',
                   '--no-input', '--ignore-installed', '--only-binary=:all:', '--require-hashes', '--no-compile',
                   '--progress-bar', 'off', '-vv', '--log', str(self.path / 'pip.log'), '--target', str(target), '-r', str(lock)]
        # Production mirror_dependency supplies the only index and nulls pip configuration.
        # All proxy/config/cache knobs supplied by the host are removed for this synthetic run.
        clear = {key: value for key, value in os.environ.items() if not key.upper().startswith(('PIP_', 'UV_'))
                 and key.upper() not in ('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY')}
        clear.update(PYTHONDONTWRITEBYTECODE='1', PYTHONNOUSERSITE='1')
        with unittest.mock.patch.dict(os.environ, clear, clear=True), unittest.mock.patch.object(subprocess, 'Popen', tracked):
            code = RUNTIME['mirror_dependency'](self.policy(), 'PYTHON_PACKAGE', command)
        print('PYTHON_INDEX_INSTALL ' + json.dumps({'mode': mode, 'code': code, 'upstreamRequests': self.server.seen,
              'wheelSha256': WHEEL_SHA, 'lockSha256': hashlib.sha256(locked).hexdigest(),
              'wheelUnchanged': original.read_bytes() == WHEEL, 'lockUnchanged': lock.read_bytes() == locked}), flush=True)
        self.assertEqual(locked, lock.read_bytes())
        self.assertEqual(WHEEL, original.read_bytes())
        self.assertTrue(any('/simple/example/' in path for path in self.server.seen))
        if expected_hash != WHEEL_SHA:
            self.assertNotEqual(0, code)
            self.assertIn('THESE PACKAGES DO NOT MATCH THE HASHES', (self.path / 'pip.log').read_text(encoding='utf-8'))
            self.assertFalse((target / 'example.py').exists())
            return
        self.assertEqual(0, code, 'named index candidate must install without changing its lock')
        self.assertEqual(b'VALUE = "synthetic-named-index"\n', (target / 'example.py').read_bytes())
        self.assertIn('/good/packages/' + NAME, self.server.seen)
        self.assertTrue(all(path.startswith('/good/') for path in self.server.seen))
        self.assertFalse(any(target.rglob('*.pyc')))

    def test_named_html_index_installs_unchanged_hashed_wheel(self):
        self.install_named('html')

    def test_named_json_index_installs_unchanged_hashed_wheel(self):
        self.install_named('json')

    def test_named_index_still_rejects_a_nonmatching_lock_hash(self):
        self.install_named('html', '0' * 64)

    def test_named_html_lexical_variants_install_without_upstream_bypass(self):
        for style in ('slash', 'adjacent', 'quoted_attr', 'mixed_newline'):
            with self.subTest(style=style):
                self.install_named('html', style=style, loopback_origin=True)

    def test_html_rewrite_matches_parser_attributes_positions_and_pip_links(self):
        from pip._internal.index.collector import IndexContent, parse_links
        class Anchors(html.parser.HTMLParser):
            def __init__(self):
                super().__init__()
                self.anchors = []
            def handle_starttag(self, tag, attrs):
                if tag == 'a':
                    self.anchors.append((self.getpos(), dict(attrs)))
        with self.gateway() as gateway:
            for style in ('slash', 'adjacent', 'quoted_attr', 'mixed_newline'):
                with self.subTest(style=style):
                    source = simple_anchor('https://python.invalid/packages/' + NAME, style)
                    parser = Anchors(); parser.feed(source)
                    rewritten = gateway.rewrite_python_html(source, 'https://python.invalid/simple/example/')
                    changed = Anchors(); changed.feed(rewritten)
                    self.assertEqual(1, len(changed.anchors))
                    original_attributes, changed_attributes = parser.anchors[0][1], changed.anchors[0][1]
                    self.assertEqual({key: value for key, value in original_attributes.items() if key != 'href'},
                                     {key: value for key, value in changed_attributes.items() if key != 'href'})
                    local = changed_attributes['href']
                    self.assertTrue(local.startswith(gateway.base + '/PYTHON_PACKAGE/f/'))
                    page = IndexContent(rewritten.encode(), 'text/html', 'utf-8', gateway.root('PYTHON_PACKAGE'), cache_link_parsing=False)
                    self.assertEqual([local], [link.url for link in parse_links(page)])
                    if style == 'mixed_newline':
                        self.assertEqual((2, 0), parser.anchors[0][0])
                        self.assertTrue(rewritten.startswith(MIXED_PREFIX))

    def first_link(self, gateway, mode):
        self.server.mode = mode
        with urllib.request.urlopen(gateway.root('PYTHON_PACKAGE') + 'example/') as response:
            body, content = response.read(), response.headers['Content-Type']
        if mode == 'json':
            document = json.loads(body)
            file = document['files'][0]
            self.assertEqual(NAME, file['filename'])
            self.assertEqual({'sha256': WHEEL_SHA}, file['hashes'])
            self.assertEqual({'sha256': METADATA_SHA}, file['core-metadata'])
            self.assertEqual('>=3.8', file['requires-python'])
            self.assertIs(file['yanked'], False)
            self.assertEqual('application/vnd.pypi.simple.v1+json', content)
            return file['url']
        class Anchor(html.parser.HTMLParser):
            def handle_starttag(self, tag, attrs):
                if tag == 'a':
                    self.attrs = dict(attrs)
        parser = Anchor()
        parser.feed(body.decode())
        self.assertEqual('>=3.8', parser.attrs['data-requires-python'])
        self.assertEqual('sha256=' + METADATA_SHA, parser.attrs['data-core-metadata'])
        self.assertEqual('sha256=' + METADATA_SHA, parser.attrs['data-dist-info-metadata'])
        self.assertEqual('text/html; charset=utf-8', content)
        self.assertTrue(parser.attrs['href'].endswith('#sha256=' + WHEEL_SHA))
        return parser.attrs['href']

    def test_html_and_json_preserve_candidate_and_independent_metadata_hashes(self):
        for mode in ('html', 'json'):
            with self.subTest(mode=mode), self.gateway() as gateway:
                local = self.first_link(gateway, mode)
                parsed = urllib.parse.urlsplit(local)
                self.assertEqual(NAME, parsed.path.rsplit('/', 1)[-1])
                sidecar = urllib.parse.urlunsplit(parsed._replace(path=parsed.path + '.metadata', fragment=''))
                with urllib.request.urlopen(sidecar) as response:
                    self.assertEqual(METADATA, response.read())
                upstream = 'https://python.invalid/packages/' + NAME + '?ticket=a%2Fb&mode=one'
                metadata = upstream.replace('.whl?', '.whl.metadata?')
                self.assertEqual(('sha256', METADATA_SHA), gateway.hashes[metadata])
                self.assertEqual(('sha256', WHEEL_SHA), gateway.hashes[upstream])
                with urllib.request.urlopen(local) as response:
                    self.assertEqual(WHEEL, response.read())
                self.assertIn('/good/packages/' + NAME + '.metadata?ticket=a%2Fb&mode=one', self.server.seen)

    def test_corrupt_metadata_fails_its_own_digest_and_does_not_poison_wheel(self):
        self.server.corrupt_metadata = True
        with self.gateway() as gateway:
            local = self.first_link(gateway, 'json')
            with self.assertRaises(urllib.error.HTTPError) as raised:
                urllib.request.urlopen(local + '.metadata')
            self.assertEqual(502, raised.exception.code)
            raised.exception.close()
            with urllib.request.urlopen(local) as response:
                self.assertEqual(WHEEL, response.read())
        self.assertEqual(1, sum('.metadata?' in path for path in self.server.seen))

    def test_filename_query_token_and_path_tampering_rejected_before_upstream(self):
        with self.gateway() as gateway:
            local = urllib.parse.urldefrag(self.first_link(gateway, 'html'))[0]
            prefix, filename = local.rsplit('/', 1)
            before = list(self.server.seen)
            attacks = [prefix + '/other-1.0-py3-none-any.whl', local + '/extra', local + '/../' + filename,
                       local + '?new=query', local + '.metadata?new=query', prefix + '/%2e%2e/' + filename,
                       prefix + '/' + filename.replace('.', '%252e', 1), prefix + '/' + filename.replace('-', '%2D', 1),
                       prefix + '/' + filename + '%2fextra', prefix + '/' + filename + '%5cextra',
                       prefix.rsplit('/', 1)[0] + '/' + base64.urlsafe_b64encode(
                           ('https://python.invalid/packages/' + NAME + '?changed=yes').encode()).decode().rstrip('=') + '/' + NAME]
            for attack in attacks:
                with self.subTest(attack=attack), self.assertRaises(urllib.error.HTTPError) as raised:
                    urllib.request.urlopen(attack)
                self.assertEqual(502, raised.exception.code)
                raised.exception.close()
                self.assertEqual(before, self.server.seen)

    def test_percent_encoding_html_entities_relative_json_and_metadata_query_binding(self):
        upstream = 'https://python.invalid/packages/example%2D1.0-py3-none-any.whl?ticket=a%2Fb&mode=one'
        with self.gateway() as gateway:
            source = '<a data-href="untouched" href="' + html.escape(upstream + '#sha256=' + WHEEL_SHA) + '" data-yanked="reason &amp; detail" data-dist-info-metadata="sha256=' + METADATA_SHA + '">x</a>'
            rewritten = gateway.rewrite_python_html(source, 'https://python.invalid/simple/example/')
            class Anchor(html.parser.HTMLParser):
                def handle_starttag(self, tag, attrs):
                    self.attrs = dict(attrs)
            parser = Anchor(); parser.feed(rewritten)
            self.assertEqual('untouched', parser.attrs['data-href'])
            self.assertEqual('reason & detail', parser.attrs['data-yanked'])
            local = urllib.parse.urldefrag(parser.attrs['href'])[0]
            self.assertTrue(local.endswith('/' + NAME))
            with urllib.request.urlopen(local + '.metadata') as response:
                self.assertEqual(METADATA, response.read())
            self.assertIn('/good/packages/example%2D1.0-py3-none-any.whl.metadata?ticket=a%2Fb&mode=one', self.server.seen)
            document = {'files': [{'url': '../../packages/' + NAME, 'hashes': {'sha256': WHEEL_SHA},
                                   'dist-info-metadata': True}]}
            result = gateway.rewrite_json(document, 'PYTHON_PACKAGE', base_url='https://python.invalid/simple/example/')
            self.assertTrue(result['files'][0]['url'].endswith('/' + NAME))
            self.assertIs(result['files'][0]['dist-info-metadata'], True)
            self.assertEqual(document['files'][0]['hashes'], result['files'][0]['hashes'])
            duplicate = gateway.rewrite_python_html('<a href="https://outside.invalid/ignored.whl" href=' + upstream + '>x</a>', upstream)
            parser = Anchor(); parser.feed(duplicate)
            self.assertNotIn('https://outside.invalid/', duplicate)
            self.assertTrue(parser.attrs['href'].startswith(gateway.base + '/PYTHON_PACKAGE/f/'))

    def test_ordered_sources_and_fixed_single_source_never_add_bypass(self):
        with self.gateway(self.policy(('missing', 'good'))) as gateway:
            local = self.first_link(gateway, 'json')
            with urllib.request.urlopen(local) as response:
                self.assertEqual(WHEEL, response.read())
        self.assertEqual(['/missing/simple/example/', '/good/simple/example/',
                          '/missing/packages/' + NAME + '?ticket=a%2Fb&mode=one',
                          '/good/packages/' + NAME + '?ticket=a%2Fb&mode=one'], self.server.seen)
        self.server.seen.clear()
        with self.gateway(self.policy(('missing',))) as gateway:
            with self.assertRaises(urllib.error.HTTPError) as raised:
                urllib.request.urlopen(gateway.root('PYTHON_PACKAGE') + 'example/')
            self.assertEqual(502, raised.exception.code)
            raised.exception.close()
        self.assertEqual(['/missing/simple/example/'], self.server.seen)

    def test_other_protocol_template_and_artifact_codecs_remain_unchanged(self):
        for point in ('NODE_PACKAGE', 'NUGET', 'COMPOSER_REPOSITORY'):
            policy = self.policy()
            policy.points[point] = policy.points.pop('PYTHON_PACKAGE')
            with self.gateway(policy) as gateway:
                artifact = 'https://python.invalid/packages/' + NAME
                encoded = base64.urlsafe_b64encode(artifact.encode()).decode().rstrip('=')
                self.assertEqual(gateway.base + '/' + point + '/u/' + encoded, gateway.link(point, artifact))
                for marker in ('{version}', '%package%', '$hash'):
                    local = gateway.link(point, 'https://python.invalid/packages/' + marker + '/file.json')
                    self.assertIn('/' + point + '/a/', local)
                    self.assertTrue(local.endswith('/' + marker + '/file.json'))
                document = {'url': artifact, 'version': '1.0', '@id': 'https://python.invalid/packages/{version}/file.json'}
                result = gateway.rewrite_json(document, point)
                self.assertEqual(gateway.link(point, artifact), result['url'])
                self.assertEqual(gateway.link(point, document['@id']), result['@id'])
                self.assertEqual('1.0', result['version'])
                if point == 'NODE_PACKAGE':
                    integrity = 'sha512-' + base64.b64encode(hashlib.sha512(WHEEL).digest()).decode()
                    result = gateway.rewrite_json({'dist': {'tarball': artifact, 'integrity': integrity}}, point)
                    self.assertEqual(integrity, result['dist']['integrity'])
                    self.assertEqual(('sha512', hashlib.sha512(WHEEL).hexdigest()), gateway.hashes[artifact])


if __name__ == '__main__':
    print('PYTHON_INDEX_ACTOR ' + json.dumps(identity(os.getpid()), sort_keys=True)
          + ' cwd=' + str(pathlib.Path.cwd()) + ' tmp=' + tempfile.gettempdir(), flush=True)
    import pip
    print('PYTHON_INDEX_PIP_VERSION=' + pip.__version__, flush=True)
    unittest.main(argv=[sys.argv[0]] + sys.argv[2:], verbosity=2)
