"""Real, explicitly supplied Gradle 8.10.2; all repository traffic stays on loopback."""
import base64
import contextlib
import http.server
import json
import os
import pathlib
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import zipfile
from unittest import mock

runtime_file, executable, evidence, group = sys.argv[1:5]
runtime = {'__name__': 'gradle_repository_fixture'}
exec(compile(pathlib.Path(runtime_file).read_text(encoding='utf-8'), runtime_file, 'exec'), runtime)
EXE = pathlib.Path(executable).resolve()
EVIDENCE = pathlib.Path(evidence).resolve()
JAVA = pathlib.Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
PLUGIN = 'GRADLE_PLUGIN'
MAVEN = 'MAVEN_REPOSITORY'
P1 = 'https://plugins.gradle.org/m2/'
P2 = 'https://plugins.second.invalid/'
M1 = 'https://repo.maven.apache.org/maven2/'
M2 = 'https://deps.second.invalid/repo/'
SHARED = 'https://shared.invalid/repo/'

NETWORK_INIT = r'''
class WtlFixtureNetworkGuard extends SecurityManager {
  void checkPermission(java.security.Permission permission) { }
  void checkConnect(String host, int port) {
    if (!(host in ['127.0.0.1', 'localhost', '::1', '0:0:0:0:0:0:0:1']))
      throw new SecurityException('fixture forbids public connection or DNS: ' + host)
  }
}
System.setSecurityManager(new WtlFixtureNetworkGuard())
'''


def invoke(command, cwd, output, timeout=150, env=None):
    """Retain the actual process until wait completes, including failure and timeout."""
    daemons = {}
    if os.name == 'nt':
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        kernel.WaitForSingleObject.restype = wintypes.DWORD
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]

    def retain_daemons():
        if os.name != 'nt':
            return
        for pid in re.findall(r'logging from the daemon \(pid: (\d+)\)', output.read_text(encoding='utf-8', errors='replace')):
            if pid not in daemons:
                handle = kernel.OpenProcess(0x100000 | 0x1000 | 0x1, False, int(pid))
                daemons[pid] = handle
                print('GRADLE_FIXTURE_DAEMON pid=%s retained=%s' % (pid, bool(handle)), flush=True)

    with output.open('w', encoding='utf-8') as log:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
        print('GRADLE_FIXTURE_PROCESS pid=%d cwd=%s log=%s' % (process.pid, cwd, output), flush=True)
        try:
            deadline = time.monotonic() + timeout
            while True:
                retain_daemons()
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise subprocess.TimeoutExpired(command, timeout)
                try:
                    code = process.wait(timeout=min(.2, remaining))
                    retain_daemons()
                    break
                except subprocess.TimeoutExpired:
                    continue
        except subprocess.TimeoutExpired:
            if os.name == 'nt':
                killer = subprocess.Popen([r'C:\Windows\System32\taskkill.exe', '/PID', str(process.pid), '/T', '/F'],
                                          cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
                killer.wait(timeout=15)
            else:
                process.kill()
            process.wait(timeout=15)
            raise
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=15)
            print('GRADLE_FIXTURE_PROCESS_EXIT pid=%d exit=%d alive=false' % (process.pid, process.returncode), flush=True)
            for pid, handle in daemons.items():
                if handle:
                    result = kernel.WaitForSingleObject(handle, 30000)
                    if result != 0:
                        kernel.TerminateProcess(handle, 1)
                        stopped = kernel.WaitForSingleObject(handle, 15000)
                        kernel.CloseHandle(handle)
                        if stopped != 0:
                            raise AssertionError('owned Gradle daemon could not be stopped: ' + pid)
                        raise AssertionError('owned single-use Gradle daemon did not exit: ' + pid)
                    kernel.CloseHandle(handle)
                    print('GRADLE_FIXTURE_DAEMON_EXIT pid=%s alive=false' % pid, flush=True)
    return code, output.read_text(encoding='utf-8', errors='replace')


class GradleRepositoryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not EXE.is_file():
            raise AssertionError('explicit Gradle executable is absent: ' + str(EXE))
        if EXE.parent.parent.name != 'gradle-8.10.2':
            raise AssertionError('fixture requires the supplied Gradle 8.10.2 distribution')
        EVIDENCE.mkdir(parents=True, exist_ok=True)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='gradle-repository-')
        self.root = pathlib.Path(self.temp.name)
        self.addCleanup(self.cleanup_owned_directory)
        self.hits = []
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def finish(self):
                super().finish()
                if os.name == 'nt':
                    time.sleep(.05)

            def log_message(self, *args):
                pass

            def do_GET(self):
                fixture.hits.append(self.path)
                if fixture.sequences.get(self.path):
                    step = fixture.sequences[self.path].pop(0)
                    if step == 'timeout':
                        time.sleep(.3)
                        return
                    fixture.statuses[self.path] = step
                if self.path in fixture.expire:
                    fixture.expire[self.path].deadline = time.monotonic() - 1
                if self.path in fixture.delays:
                    time.sleep(fixture.delays[self.path])
                    return
                status = fixture.statuses.get(self.path, 200 if self.path in fixture.objects else 404)
                if status != 200:
                    with contextlib.suppress(OSError):
                        self.send_error(status)
                    return
                content = fixture.objects.get(self.path)
                if content is None:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header('Content-Length', str(len(content)))
                self.send_header('Content-Type', 'application/octet-stream')
                self.end_headers()
                if self.command != 'HEAD':
                    self.wfile.write(content)

            do_HEAD = do_GET

        self.objects = {}
        self.statuses, self.delays, self.expire, self.sequences = {}, {}, {}, {}
        self.upstream = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=self.upstream.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join)
        self.addCleanup(self.upstream.server_close)
        self.addCleanup(self.upstream.shutdown)
        upstream = 'http://127.0.0.1:%d/' % self.upstream.server_port
        points = {}
        for point, origins, prefix in ((PLUGIN, (P1, P2, SHARED), 'plugin'), (MAVEN, (M1, M2, SHARED), 'maven')):
            points[point] = [{'providerId': 'local-' + prefix, 'baseUrl': upstream,
                              'allowedHosts': ['127.0.0.1'], 'official': False,
                              'routes': [{'origin': origin, 'prefix': upstream + prefix + '/' + str(index) + '/'}
                                         for index, origin in enumerate(origins)]}]
        self.policy = runtime['MirrorPolicy']({'formatVersion': 1, 'points': points,
            'timeouts': {'attemptsPerEndpoint': 1, 'connectSeconds': 2, 'readSeconds': 5,
                         'noProgressSeconds': 5, 'lowSpeedBytesPerSecond': 1}})
        self.addCleanup(self.cleanup_ledger)
        # The Python upstream client cannot make a public DNS query or connection either.
        real_getaddrinfo = socket.getaddrinfo

        def loopback_only(host, *args, **kwargs):
            if host not in ('127.0.0.1', '::1', 'localhost'):
                raise AssertionError('fixture forbids public DNS: ' + str(host))
            return real_getaddrinfo(host, *args, **kwargs)
        patch = mock.patch.object(socket, 'getaddrinfo', side_effect=loopback_only)
        patch.start()
        self.addCleanup(patch.stop)

    def cleanup_ledger(self):
        if self.policy.transfer_ledger.directory is not None:
            self.policy.transfer_ledger.directory.cleanup()

    def cleanup_owned_directory(self):
        root = self.root.resolve()
        parent = pathlib.Path(tempfile.gettempdir()).resolve()
        if root.parent != parent or root.is_symlink():
            raise AssertionError('refusing cleanup outside the owned temporary directory')
        # Gradle creates cache class paths beyond Win32 MAX_PATH under long workspace roots.
        path = '\\\\?\\' + str(root) if os.name == 'nt' else str(root)
        shutil.rmtree(path)
        self.temp.cleanup()

    def run_gradle(self, name, settings='', build='', kotlin=False, offline=False, task='help', mutation=None):
        directory = self.root / name
        directory.mkdir()
        home = directory / 'user-home'
        tmp = directory / 'tmp'
        tmp.mkdir()
        suffix = '.gradle.kts' if kotlin else '.gradle'
        (directory / ('settings' + suffix)).write_text(settings, encoding='utf-8')
        (directory / ('build' + suffix)).write_text(build, encoding='utf-8')
        (directory / 'network.gradle').write_text(NETWORK_INIT, encoding='utf-8')
        log = EVIDENCE / (name + '.log')
        self.assertFalse(log.exists(), 'each original invocation needs its own evidence filename')
        environment = dict(os.environ, TEMP=str(tmp), TMP=str(tmp), TMPDIR=str(tmp), GRADLE_USER_HOME=str(home),
                           GRADLE_OPTS='', JAVA_OPTS='', JAVA_TOOL_OPTIONS='-Djava.security.manager=allow ' + ' '.join(
                               '-D%s=%s' % (key, tmp) for key in ('java.io.tmpdir', 'jna.tmpdir', 'jdk.net.unixdomain.tmpdir')))
        with runtime['RepositoryGateway'](self.policy, directory) as gateway:
            command = [str(EXE), '--no-daemon', '--console=plain', '--info', '--stacktrace', '--max-workers=1',
                       '-Dorg.gradle.java.installations.auto-download=false',
                       '-Dorg.gradle.jvmargs=-Xmx384m -Djava.security.manager=allow ' + ' '.join(
                           '-D%s=%s' % (key, tmp) for key in ('java.io.tmpdir', 'jna.tmpdir', 'jdk.net.unixdomain.tmpdir')),
                       '--init-script', str(directory / 'network.gradle')]
            generated_environment, command = runtime['dependency_environment'](self.policy, gateway, PLUGIN, command, directory)
            generated_environment.update(environment)
            environment = generated_environment
            if mutation is not None:
                path = directory / ('build' + suffix)
                path.write_text(path.read_text() + mutation(gateway), encoding='utf-8')
            if offline:
                command.append('--offline')
            command.append(task)
            code, output = invoke(command, directory, log, env=environment)
        print('GRADLE_CASE name=%s exit=%d hits=%s' % (name, code, json.dumps(self.hits)), flush=True)
        return code, output

    def test_core_plugins_groovy_and_kotlin_are_idempotent_and_bind_real_wrappers(self):
        for kotlin in (False, True):
            name = 'core-kotlin' if kotlin else 'core-groovy'
            with self.subTest(name=name):
                build = 'plugins { java }\nrepositories { mavenCentral() }' if kotlin else "plugins { id 'java' }\nrepositories { mavenCentral() }"
                code, output = self.run_gradle(name, build=build, kotlin=kotlin, offline=True)
                self.assertEqual(0, code, output)
                self.assertIn('Gradle 8.10.2', output)
                self.assertIn('Task repository wrapper bound role=buildscript point=GRADLE_PLUGIN', output)
                self.assertEqual(1, output.count('Task repository listener installed role=pluginManagement'), output)
                self.assertEqual(1, output.count('Task repository container rechecked role=pluginManagement'), output)
                self.assertEqual(1, output.count('Task repository listener installed role=settingsDependencies'), output)
                self.assertEqual([], self.hits)

    def add_pom(self, prefix, group, artifact, version='1', dependencies='', packaging='jar'):
        path = '/' + prefix + '/' + group.replace('.', '/') + '/' + artifact + '/' + version + '/' + artifact + '-' + version
        self.objects[path + '.pom'] = ('<project><modelVersion>4.0.0</modelVersion><groupId>' + group + '</groupId><artifactId>'
            + artifact + '</artifactId><version>' + version + '</version><packaging>' + packaging
            + '</packaging>' + dependencies + '</project>').encode()
        return path

    def plugin_jar(self):
        source = self.root / 'FixturePlugin.java'
        source.write_text('public class FixturePlugin implements org.gradle.api.Plugin<org.gradle.api.Project> {'
                          ' public void apply(org.gradle.api.Project project) {} }')
        environment = dict(os.environ, TEMP=str(self.root), TMP=str(self.root), TMPDIR=str(self.root))
        code, output = invoke([str(JAVA.parent / ('javac.exe' if os.name == 'nt' else 'javac')), '-cp',
            str(EXE.parent.parent / 'lib' / '*'), '-d', str(self.root), str(source)], self.root,
            EVIDENCE / 'plugin-javac.log', 30, environment)
        self.assertEqual(0, code, output)
        jar = self.root / 'plugin.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.write(self.root / 'FixturePlugin.class', 'FixturePlugin.class')
            for plugin in ('fixture.first', 'fixture.shared'):
                archive.writestr('META-INF/gradle-plugins/' + plugin + '.properties', 'implementation-class=FixturePlugin')
        return jar.read_bytes()

    def test_local_plugin_and_dependency_artifacts_keep_exact_point_origin_and_wrapper_routes(self):
        jar = self.plugin_jar()
        for prefix, plugin in (('plugin/1', 'fixture.first'), ('plugin/2', 'fixture.shared')):
            implementation = self.add_pom(prefix, plugin, 'implementation')
            self.objects[implementation + '.jar'] = jar
            marker = self.add_pom(prefix, plugin, plugin + '.gradle.plugin', dependencies='<dependencies><dependency><groupId>'
                + plugin + '</groupId><artifactId>implementation</artifactId><version>1</version></dependency></dependencies>', packaging='pom')
        for prefix, artifact in (('maven/1', 'lib'), ('maven/2', 'sharedlib'), ('plugin/1', 'pluginlib')):
            path = self.add_pom(prefix, 'test', artifact)
            self.objects[path + '.jar'] = jar
        del self.objects['/maven/1/test/lib/1/lib-1.jar']
        self.objects['/plugin/1/test/lib/1/lib-1.jar'] = jar
        settings = """pluginManagement {
  repositories {
    maven { url = uri('%s'); content { includeGroup('fixture.first') } }
    maven { url = uri('%s'); content { includeGroup('fixture.shared') } }
  }
}
""" % (P2, SHARED)
        build = """plugins { id 'fixture.first' version '1'; id 'fixture.shared' version '1' }
repositories {
  maven { url = uri('%s'); artifactUrls('%s'); content { includeModule('test', 'lib') } }
  maven { url = uri('%s'); content { includeModule('test', 'sharedlib') } }
  maven { url = uri('%s'); content { includeModule('test', 'pluginlib') } }
}
configurations { fixtureCheck }
dependencies { fixtureCheck 'test:lib:1'; fixtureCheck 'test:sharedlib:1'; fixtureCheck 'test:pluginlib:1' }
tasks.register('resolveFixture') { doLast {
  assert plugins.hasPlugin('fixture.first') && plugins.hasPlugin('fixture.shared')
  assert configurations.fixtureCheck.resolve().size() == 3
  def extra = repositories.find { !it.artifactUrls.empty }.artifactUrls*.toString()
  assert extra.size() == 1 && extra.first().contains('/GRADLE_PLUGIN/u/%s/')
  println('EXACT_POINT_ORIGIN_ARTIFACTS_OK')
} }
""" % (M2, P2, SHARED, P2, base64.urlsafe_b64encode(P2.encode()).decode().rstrip('='))
        code, output = self.run_gradle('local-exact-routes', settings, build, task='resolveFixture')
        self.assertEqual(0, code, output)
        self.assertIn('EXACT_POINT_ORIGIN_ARTIFACTS_OK', output)
        self.assertEqual(3, output.count('Task repository wrapper bound role=buildscript point=GRADLE_PLUGIN'), output)
        for path in ('/plugin/1/fixture/first/fixture.first.gradle.plugin/1/fixture.first.gradle.plugin-1.pom',
                     '/plugin/2/fixture/shared/fixture.shared.gradle.plugin/1/fixture.shared.gradle.plugin-1.pom',
                     '/plugin/1/fixture/first/implementation/1/implementation-1.jar',
                     '/plugin/2/fixture/shared/implementation/1/implementation-1.jar',
                     '/maven/1/test/lib/1/lib-1.pom', '/plugin/1/test/lib/1/lib-1.jar',
                     '/maven/2/test/sharedlib/1/sharedlib-1.jar', '/plugin/1/test/pluginlib/1/pluginlib-1.jar'):
            self.assertIn(path, self.hits)
        self.assertIn('/plugin/0/fixture/first/fixture.first.gradle.plugin/1/fixture.first.gradle.plugin-1.pom', self.hits)
        self.assertIn('/plugin/1/fixture/first/fixture.first.gradle.plugin/1/fixture.first.gradle.plugin-1.jar', self.hits)
        self.assertIn('/maven/0/fixture/first/fixture.first.gradle.plugin/1/fixture.first.gradle.plugin-1.pom', self.hits)
        self.assertIn('/maven/1/test/lib/1/lib-1.jar', self.hits)

    def test_not_found_preserves_http_status_and_exact_ledger_evidence_without_masking_failures(self):
        missing = runtime['MirrorNotFound']
        failure = runtime['MirrorFailure']
        upstream = 'http://127.0.0.1:%d/' % self.upstream.server_port
        original = P1 + 'object.pom'
        for name, statuses, expected_status in (
                ('all404', (404, 404), 404), ('mixed403', (404, 403), 502),
                ('mixed500', (404, 500), 502), ('mixedtimeout', (404, 200), 502),
                ('mixedhash', (404, 200), 502), ('fallback200', (404, 200), 200),
                ('expired-after404', (404, 404), 502), ('allowlist', (404, 404), 502)):
            with self.subTest(name=name):
                entries = []
                for index, status in enumerate(statuses):
                    prefix = name + '/' + str(index) + '/'
                    path = '/' + prefix + 'object.pom'
                    self.statuses[path] = status
                    self.objects[path] = b'locally-published-object'
                    entries.append({'providerId': name + '-' + str(index), 'baseUrl': upstream,
                        'allowedHosts': [] if name == 'allowlist' and index == 1 else ['127.0.0.1'],
                        'routes': [{'origin': P1, 'prefix': upstream + prefix}]})
                policy = runtime['MirrorPolicy']({'formatVersion': 1, 'points': {PLUGIN: entries},
                    'timeouts': {'attemptsPerEndpoint': 1, 'connectSeconds': 1, 'readSeconds': .1,
                                 'noProgressSeconds': .1, 'lowSpeedBytesPerSecond': 1}})
                if name == 'mixedtimeout':
                    self.delays['/' + name + '/1/object.pom'] = .3
                if name == 'expired-after404':
                    self.expire['/' + name + '/0/object.pom'] = policy
                try:
                    with runtime['RepositoryGateway'](policy, self.root) as gateway:
                        if name == 'mixedhash':
                            gateway.hashes[original] = ('sha256', '0' * 64)
                        url = gateway.root(PLUGIN) + 'object.pom'
                        first_count = None
                        for repeat in range(2):
                            try:
                                with urllib.request.urlopen(url, timeout=3) as response:
                                    status = response.status
                                    self.assertEqual(b'locally-published-object', response.read())
                            except urllib.error.HTTPError as error:
                                status = error.code
                                error.close()
                            self.assertEqual(expected_status, status, name)
                            count = len(self.hits)
                            if repeat:
                                self.assertEqual(first_count, count, name + ' retried an exhausted/cached object')
                            first_count = count
                    if name == 'all404':
                        with self.assertRaises(failure) as caught:
                            runtime['mirror_bytes'](policy, PLUGIN, original)
                        self.assertIsInstance(caught.exception, missing)
                        with self.assertRaises(failure) as changed_request:
                            runtime['mirror_transfer'](policy, PLUGIN, original, self.root / 'absent', headers={'Accept': 'different'})
                        self.assertNotIsInstance(changed_request.exception, missing)
                        self.assertFalse((self.root / 'absent').exists())
                        policy.points[PLUGIN][0]['routes'][0]['prefix'] += 'changed/'
                        with self.assertRaises(failure) as changed_source:
                            runtime['mirror_bytes'](policy, PLUGIN, original)
                        self.assertNotIsInstance(changed_source.exception, missing)
                finally:
                    if policy.transfer_ledger.directory is not None:
                        policy.transfer_ledger.directory.cleanup()
        path = '/timeout-then404/object.pom'
        self.sequences[path] = ['timeout', 404]
        endpoint = {'providerId': 'timeout-then404', 'baseUrl': upstream, 'allowedHosts': ['127.0.0.1'],
                    'routes': [{'origin': P1, 'prefix': upstream + 'timeout-then404/'}]}
        policy = runtime['MirrorPolicy']({'formatVersion': 1, 'points': {PLUGIN: [endpoint]},
            'timeouts': {'attemptsPerEndpoint': 2, 'connectSeconds': 1, 'readSeconds': .1,
                         'noProgressSeconds': .1, 'lowSpeedBytesPerSecond': 1}})
        with runtime['RepositoryGateway'](policy, self.root) as gateway:
            for repeat in range(2):
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    urllib.request.urlopen(gateway.root(PLUGIN) + 'object.pom', timeout=3)
                self.assertEqual(502, caught.exception.code)
                caught.exception.close()
        self.assertEqual(2, self.hits.count(path))
        # The status adapter change is confined to Gradle/Maven; other callers still
        # receive the existing base failure and their gateway keeps its old status.
        policy = runtime['MirrorPolicy']({'formatVersion': 1, 'points': {'NODE_PACKAGE': [endpoint]}})
        with runtime['RepositoryGateway'](policy, self.root) as gateway:
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(gateway.root('NODE_PACKAGE') + 'other-object', timeout=3)
            self.assertEqual(502, caught.exception.code)
            caught.exception.close()
        print('NOT_FOUND_STATUS_LEDGER_AND_BASE_FAILURE_BOUNDARIES_OK', flush=True)

    def test_rejects_unbound_repositories_spoofs_and_late_url_changes_before_network(self):
        resolve = """plugins { id 'java' }
configurations { forbidden }
dependencies { forbidden 'test:forbidden:1' }
tasks.register('resolveForbidden') { doLast { configurations.forbidden.resolve() } }
"""
        mutations = {
            'external-http': lambda gateway: "\nrepositories { maven { url='http://unmapped.invalid/repo/'; allowInsecureProtocol=true } }\n",
            'external-file': lambda gateway: "\nrepositories { maven { url=uri('file:///forbidden-repository/') } }\n",
            'unmapped-https': lambda gateway: "\nrepositories { maven { url='https://unmapped.invalid/repo/' } }\n",
            'wrong-loopback-port': lambda gateway: "\nrepositories { maven { url='http://127.0.0.1:1/GRADLE_PLUGIN/root/'; allowInsecureProtocol=true } }\n",
            'wrong-loopback-path': lambda gateway: "\nrepositories { maven { url='%s/not-task-root/'; allowInsecureProtocol=true } }\n" % gateway.base,
            'late-main-url': lambda gateway: "\nrepositories.first().url='https://unmapped.invalid/repo/'\n",
            'late-artifact-url': lambda gateway: "\nrepositories.first().artifactUrls('https://unmapped.invalid/artifacts/')\n",
            'late-plugin-source': lambda gateway: "\ngradle.settings.pluginManagement.repositories.first().url='https://unmapped.invalid/repo/'\n",
            'late-admitted-origin': lambda gateway: "\nrepositories.first().url='%s'\n" % P2,
            'metaclass-source-spoof': lambda gateway: """
def source = gradle.settings.pluginManagement.repositories.first()
def admitted = source.url
def oldDescriptor = source.descriptor
source.url = 'https://unmapped.invalid/repo/'
source.metaClass.getUrl = { admitted }
source.metaClass.getDescriptor = { oldDescriptor }
""",
            'task-action-main-url': lambda gateway: "\ntasks.named('resolveForbidden') { doFirst { repositories.first().url='https://unmapped.invalid/repo/' } }\n",
            'task-action-artifact-url': lambda gateway: "\ntasks.named('resolveForbidden') { doFirst { repositories.first().artifactUrls('https://unmapped.invalid/artifacts/') } }\n",
            'unknown-no-url': lambda gateway: """
def fake = java.lang.reflect.Proxy.newProxyInstance(org.gradle.api.internal.artifacts.repositories.ArtifactRepositoryInternal.class.classLoader,
  [org.gradle.api.internal.artifacts.repositories.ArtifactRepositoryInternal, org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository] as Class[],
  { proxy, method, args ->
    switch (method.name) {
      case 'getName': return 'fake'
      case 'hashCode': return System.identityHashCode(proxy)
      case 'equals': return proxy.is(args[0])
      case 'getDescriptor': return [id: 'forged-content-id']
      default: return null
    }
  } as java.lang.reflect.InvocationHandler)
repositories.add(fake)
""",
            'metaclass-fake-descriptor': lambda gateway: """
def source = gradle.settings.pluginManagement.repositories.first()
def copy = source.descriptor
def fake = [getName: { 'fake-descriptor' }, setName: { n -> }, content: { a -> }] as org.gradle.api.artifacts.repositories.ArtifactRepository
fake.metaClass.getDescriptor = { copy }
fake.metaClass.getUrl = { source.url }
repositories.add(fake)
""",
        }
        for name, mutation in mutations.items():
            with self.subTest(name=name):
                code, output = self.run_gradle('reject-' + name, build=resolve, task='resolveForbidden', mutation=mutation)
                self.assertNotEqual(0, code, output)
                self.assertIn('task repository rejected:', output)
                self.assertNotIn('fixture forbids public connection or DNS:', output)
                self.assertEqual([], self.hits, name + ' reached a forbidden endpoint')


if __name__ == '__main__':
    selected = {'core': 'test_core_plugins_groovy_and_kotlin_are_idempotent_and_bind_real_wrappers',
                'local': 'test_local_plugin_and_dependency_artifacts_keep_exact_point_origin_and_wrapper_routes',
                'status': 'test_not_found_preserves_http_status_and_exact_ledger_evidence_without_masking_failures',
                'reject': 'test_rejects_unbound_repositories_spoofs_and_late_url_changes_before_network'}[group]
    unittest.main(argv=[sys.argv[0], 'GradleRepositoryTests.' + selected], verbosity=2)
