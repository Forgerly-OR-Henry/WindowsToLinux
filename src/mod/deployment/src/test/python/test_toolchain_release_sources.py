"""Offline checks for .NET release origins and frozen HTTPS destinations."""
import json
import pathlib
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


if __name__ == '__main__':
    unittest.main()
