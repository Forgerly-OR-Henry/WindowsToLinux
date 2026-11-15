"""Offline checks for release origins and frozen HTTPS destinations."""
import hashlib
import io
import json
import pathlib
import tempfile
import unittest
from unittest import mock


RESOURCES = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/gold/debug/windowstolinux/mod/deployment'
CATALOG = json.loads((RESOURCES / 'config/mirrors.yaml').read_text(encoding='utf-8'))
ORIGIN = 'https://builds.dotnet.microsoft.com/dotnet/'
ASSET = 'Sdk/10.0.300/dotnet-sdk-10.0.300-linux-x64.tar.gz'
CUSTOM = 'https://mirror.example.invalid:8443/sdk/'
DIGEST = 'a' * 128


def load_resource(path, replacements=()):
    source = (RESOURCES / path).read_text(encoding='utf-8')
    for marker, value in replacements:
        source = source.replace(marker, repr(value))
    namespace = {'__name__': 'release_source_fixture'}
    exec(compile(source, path, 'exec'), namespace)
    return namespace


class ToolchainReleaseSourcesTest(unittest.TestCase):
    def setUp(self):
        provider = next(provider for provider in CATALOG['providers'] if provider['id'] == 'official-dotnet_sdk')
        self.official = dict(provider['sources']['DOTNET_SDK'], official=True,
                             sourceOrigins=CATALOG['points']['DOTNET_SDK']['sourceOrigins'])
        self.custom = {'providerId': 'custom-test', 'custom': True, 'baseUrl': CUSTOM,
                       'allowedHosts': ['mirror.example.invalid'], 'sourceOrigins': self.official['sourceOrigins'],
                       'routes': [{'origin': ORIGIN, 'prefix': CUSTOM}]}
        runtime = load_resource('linux/mirror/10-transfer.py')
        self.policy = runtime['MirrorPolicy']({'formatVersion': 1, 'points': {'DOTNET_SDK': [self.custom]}})
        self.mirror_failure = runtime['MirrorFailure']
        self.release = load_resource('linux/helper/toolchain/10-release-metadata.py', (
            ('__WTL_MIRROR_DEFAULT_ALLOWED_HOSTS__', self.official['allowedHosts']),
            ('__WTL_MIRROR_DEFAULT_SOURCE_ORIGINS__', {point: CATALOG['points'][point]['sourceOrigins']
                for point in ('DOTNET_METADATA', 'DOTNET_SDK')})))
        self.official_url = self.release['official_url']
        self.preparation_failure = self.release['PreparationFailure']

    def test_official_sources_use_default_https_port_with_or_without_frozen_policy(self):
        for environment in ({}, {'WTL_MIRROR_POLICY': 'fixture'}):
            with self.subTest(environment=environment), mock.patch.dict('os.environ', environment, clear=True):
                self.assertEqual(ORIGIN + ASSET, self.official_url(ORIGIN + ASSET))
                explicit_default = ORIGIN.replace('.com/', '.com:443/') + ASSET
                self.assertEqual(ORIGIN + ASSET, self.official_url(explicit_default))
                for url in (ORIGIN.replace('.com/', '.com:8443/') + ASSET, CUSTOM + ASSET):
                    with self.subTest(url=url), self.assertRaises(self.preparation_failure):
                        self.official_url(url)

    def test_selected_dotnet_metadata_preserves_release_and_sha512_identity(self):
        file = {'rid': 'linux-x64', 'name': 'dotnet-sdk-linux-x64.tar.gz', 'url': ORIGIN + ASSET,
                'hash': DIGEST.upper()}
        sdk = {'version': '10.0.300', 'files': [file]}
        resolver = mock.Mock(return_value={'releases': [{'sdk': sdk}]})
        with mock.patch.dict(self.release, {'json_at': resolver}), mock.patch.dict('os.environ', {}, clear=True):
            self.assertEqual(('10.0.300', ORIGIN + ASSET, 'sha512', DIGEST, 'flat'),
                             self.release['release']('DOTNET', '10', '10.0.300'))
            resolver.assert_called_once_with(ORIGIN + 'release-metadata/10.0/releases.json')
            file['url'] = CUSTOM + ASSET
            with self.assertRaises(self.preparation_failure):
                self.release['release']('DOTNET', '10', '10.0.300')

    def test_historical_frozen_binding_remains_readable(self):
        archived = CUSTOM + ASSET
        loader = mock.Mock(return_value=(None, self.policy))
        with mock.patch.dict('os.environ', {'WTL_MIRROR_POLICY': 'fixture'}, clear=True), \
             mock.patch.dict(self.release, {'task_mirrors': loader}):
            self.assertEqual(archived, self.official_url(archived, historical=True))
            with self.assertRaises(self.preparation_failure):
                self.official_url(archived)
            loader.assert_called_once_with()

    def test_frozen_route_and_redirect_use_selected_custom_port(self):
        selected = self.policy.maps(self.custom, self.official_url(ORIGIN + ASSET))
        self.assertEqual(CUSTOM + ASSET, selected)
        self.assertEqual(selected, self.policy.validate_url(self.custom, selected))
        redirected = selected + '?fixture=1'
        self.assertEqual(redirected, self.policy.validate_redirect(self.custom, selected, redirected))
        for destination in (selected.replace(':8443', ''), selected.replace(':8443', ':9443')):
            with self.subTest(destination=destination):
                with self.assertRaises(self.mirror_failure):
                    self.policy.validate_url(self.custom, destination)
                with self.assertRaises(self.mirror_failure):
                    self.policy.validate_redirect(self.custom, selected, destination)

    def test_official_redirect_preserves_existing_cdn_allowlist_on_https_443(self):
        selected = self.policy.maps(self.official, ORIGIN + ASSET)
        self.assertEqual(selected, self.policy.validate_url(self.official, selected))
        cdn = 'https://dotnetcli.blob.core.windows.net/dotnet/' + ASSET
        self.assertEqual(cdn, self.policy.validate_redirect(self.official, selected, cdn))
        for destination in (cdn.replace('.net/', '.net:8443/'), selected.replace('.com/', '.com:8443/')):
            with self.subTest(destination=destination), self.assertRaises(self.mirror_failure):
                self.policy.validate_redirect(self.official, selected, destination)


class GnuMakeReleaseSourcesTest(unittest.TestCase):
    point = 'GNU_MAKE_SOURCE'
    origin = 'https://ftp.gnu.org/gnu/make/'
    name = 'make-4.4.1.tar.gz'
    expected = 'dd16fb1d67bfab79a72f5e8390735c49e3e8e70b4945a15ab1f81ddb78658fb3'
    maximum = 2348200

    def setUp(self):
        provider, = [provider for provider in CATALOG['providers'] if self.point in provider['sources']]
        source = provider['sources'][self.point]
        self.url = self.origin + self.name
        self.endpoint = dict(source, official=provider['official'], custom=False,
                             providerId=provider['id'], protocol=CATALOG['points'][self.point]['protocol'],
                             sourceOrigins=CATALOG['points'][self.point]['sourceOrigins'])
        self.endpoint['routes'] = [dict(route, pattern='', replacement='') for route in source['routes']]
        self.runtime = load_resource('linux/mirror/10-transfer.py')
        self.release = load_resource('linux/helper/toolchain/10-release-metadata.py', (
            ('__WTL_MIRROR_DEFAULT_ALLOWED_HOSTS__', ['www.python.org']),
            ('__WTL_MIRROR_DEFAULT_SOURCE_ORIGINS__', {self.point: [self.origin]})))
        self.release.update(GNU_MAKE_SOURCE_SHA256=self.expected, GNU_MAKE_SOURCE_BYTES=self.maximum,
                            GNU_MAKE_SOURCE_NAME=self.name, GNU_MAKE_SOURCE_URL=self.url, remaining=lambda: None)
        self.failure = self.release['PreparationFailure']

    def policy(self, endpoints):
        return self.runtime['MirrorPolicy']({'formatVersion': 1, 'points': {self.point: endpoints}})

    def test_fixed_source_rejects_missing_alternate_or_credentialed_policy_and_redirects(self):
        valid = (self.runtime, self.policy([self.endpoint]))
        with mock.patch.dict(self.release, {'task_mirrors': lambda: valid}):
            self.assertEqual(self.url, self.release['source_url'](self.point, self.name))
            for relative, index in (('../' + self.name, 0), (self.name + '.sig', 0), (self.name, 1)):
                with self.assertRaises(self.failure):
                    self.release['source_url'](self.point, relative, index)
        for endpoints in ([], [dict(self.endpoint, secretRef='fixture-ref')],
                          [dict(self.endpoint, allowedHosts=['alternate.invalid'])],
                          [self.endpoint, self.endpoint]):
            with self.subTest(endpoints=len(endpoints)), \
                 mock.patch.dict(self.release, {'task_mirrors': lambda: (self.runtime, self.policy(endpoints))}), \
                 self.assertRaises(self.failure):
                self.release['source_url'](self.point, self.name)
        for redirect in (self.url, self.origin + 'other.tar.gz', 'https://elsewhere.invalid/'):
            with self.assertRaises(self.runtime['MirrorFailure']):
                valid[1].validate_redirect(self.endpoint, self.url, redirect)
        with self.assertRaises(self.failure):
            self.release['official_url'](self.url)

    def test_mirror_download_binds_the_exact_release_identity_and_limit(self):
        policy = self.policy([self.endpoint])
        transfer = mock.Mock(return_value={'sha256': self.expected, 'bytes': self.maximum})
        runtime = dict(self.runtime, mirror_transfer=transfer)
        target = pathlib.Path('mirror-only-fixture')
        with mock.patch.dict(self.release, {'task_mirrors': lambda: (runtime, policy)}):
            self.assertEqual(self.expected, self.release['download'](
                self.url, target, 'sha256', self.expected, point=self.point, maximum=self.maximum))
            transfer.assert_called_once_with(policy, self.point, self.url, target, 'sha256', self.expected, self.maximum)
            transfer.reset_mock()
            for url, digest, limit in ((self.url.replace('ftp.gnu.org', 'another.invalid'), self.expected, self.maximum),
                                       (self.url, '0' * 64, self.maximum), (self.url, self.expected, self.maximum + 1)):
                with self.assertRaises(self.failure):
                    self.release['download'](url, target, 'sha256', digest, point=self.point, maximum=limit)
            transfer.assert_not_called()

    def test_direct_download_uses_no_proxy_or_redirect_and_caps_the_read(self):
        body = b'fixed'
        digest = hashlib.sha256(body).hexdigest()
        urllib = self.release['urllib'].request
        with tempfile.TemporaryDirectory() as directory, \
             mock.patch.dict(self.release, {'task_mirrors': lambda: (None, None),
                                            'GNU_MAKE_SOURCE_BYTES': len(body), 'GNU_MAKE_SOURCE_SHA256': digest}):
            self.assertEqual(self.url, self.release['source_url'](self.point, self.name))
            for supplied in (body, body[:-1], body + b'!more bytes'):
                with self.subTest(size=len(supplied)):
                    target = pathlib.Path(directory) / str(len(supplied))
                    stream = mock.Mock(wraps=io.BytesIO(supplied))
                    response = mock.Mock()
                    response.__enter__ = mock.Mock(return_value=stream)
                    response.__exit__ = mock.Mock(return_value=False)
                    opener = mock.Mock()
                    opener.open.return_value = response
                    with mock.patch.object(urllib, 'build_opener', return_value=opener) as build_opener:
                        if supplied == body:
                            self.assertEqual(digest, self.release['download'](
                                self.url, target, 'sha256', digest, point=self.point, maximum=len(body)))
                            self.assertEqual(body, target.read_bytes())
                        else:
                            with self.assertRaises(self.failure):
                                self.release['download'](self.url, target, 'sha256', digest,
                                                         point=self.point, maximum=len(body))
                        proxy, redirect = build_opener.call_args.args
                        self.assertEqual({}, proxy.proxies)
                        with self.assertRaises(self.failure):
                            redirect.redirect_request(None, None, 302, None, None, self.url)
                        self.assertEqual(mock.call(len(body) + 1), stream.read.call_args_list[0])
                        self.assertEqual(self.url, opener.open.call_args.args[0].full_url)
                        if len(supplied) > len(body):
                            self.assertEqual(1, stream.read.call_count)


if __name__ == '__main__':
    unittest.main()
