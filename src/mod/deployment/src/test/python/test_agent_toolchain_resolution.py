"""Direct actual-resource resolution checks; no network, Linux installation or SSH."""
import base64
import contextlib
import copy
import hashlib
import io
import json
import sys
import types
import unittest
from unittest import mock

from test_agent_toolchain_isolation import controller, OPERATION


class AgentToolchainResolutionTest(unittest.TestCase):
    def setUp(self):
        self.c = controller()
        self.source = self.c.EXPECTED_RESOLVER_SOURCE_SHA256

    def descriptor(self, **changes):
        value = {'format': 'WTL_AGENT_RELEASE_1', 'operation': OPERATION, 'ecosystem': 'RUST',
            'branch': '1.95', 'requestedExact': '-', 'version': '1.95.0',
            'url': 'https://static.rust-lang.org/dist/rust-1.95.0.tar.gz', 'algorithm': 'sha256',
            'digest': 'a' * 64, 'layout': 'rust', 'platform': 'linux-x86_64',
            'resolverSourceSha256': self.source}
        value.update(changes)
        return value

    def encoded(self, value=None):
        return base64.b64encode(self.c.resolution_encode(value or self.descriptor())).decode()

    def test_current_descriptor_rejects_foreign_operation_source_and_changed_asset(self):
        for changes in ({'operation': 'b' * 32}, {'resolverSourceSha256': 'b' * 64},
            {'version': '1.94.0'}, {'url': 'https://example.org/archive'}, {'algorithm': 'md5'},
            {'digest': 'abc'}, {'layout': 'flat'}, {'requestedExact': '1.95.0'}, {'extra': 'value'}):
            with self.subTest(changes=changes), self.assertRaises(self.c.PreparationFailure):
                self.c.resolution_decode(self.encoded(self.descriptor(**changes)), 'RUST', '1.95', '-', OPERATION, self.source)

    def test_noncanonical_encoding_duplicate_fields_and_oversize_are_rejected(self):
        value = self.descriptor()
        duplicate = json.dumps(value)[:-1] + ',"operation":"' + OPERATION + '"}'
        for encoded in (self.encoded() + '\n', base64.b64encode(json.dumps(value).encode()).decode(),
            base64.b64encode(duplicate.encode()).decode(), 'a' * 6000):
            with self.assertRaises(self.c.PreparationFailure):
                self.c.resolution_decode(encoded, 'RUST', '1.95', '-', OPERATION, self.source)

    def test_resolution_reads_metadata_without_entering_any_installation_operation(self):
        output = io.StringIO()
        linux = types.SimpleNamespace(sysname='Linux', machine='x86_64')
        with mock.patch.object(self.c.os, 'uname', return_value=linux, create=True), \
            mock.patch.object(self.c.signal, 'SIGALRM', 14, create=True), \
            mock.patch.object(self.c.signal, 'signal'), mock.patch.object(self.c.signal, 'alarm', create=True), \
            mock.patch.object(self.c, 'release', return_value=('1.95.0', self.descriptor()['url'], 'sha256', 'a' * 64, 'rust')) as release, \
            mock.patch.object(self.c, 'agent_create', side_effect=AssertionError('No volume')), \
            mock.patch.object(self.c, 'owned_directory', side_effect=AssertionError('No installation directory')), \
            mock.patch.object(self.c, 'download', side_effect=AssertionError('No executable archive')), \
            contextlib.redirect_stdout(output):
            self.c.resolution_main(['resolve', 'RUST', '1.95', '-', '60', OPERATION])
        release.assert_called_once_with('RUST', '1.95', '')
        encoded = output.getvalue().removeprefix('TOOLCHAIN_RELEASE=').rstrip('\n')
        selected = self.c.resolution_decode(encoded, 'RUST', '1.95', '-', OPERATION,
            hashlib.sha256(self.c.PROGRAM_SOURCE).hexdigest())
        self.assertEqual('1.95.0', selected['version'])

    def test_prepare_consumes_pinned_descriptor_without_another_metadata_lookup(self):
        manifest = {'operation': OPERATION}
        with mock.patch.object(self.c, 'release', side_effect=AssertionError('Must not re-resolve')), \
            mock.patch.object(self.c, 'agent_create', return_value=manifest), \
            mock.patch.object(self.c, 'agent_prepare_selected') as selected:
            self.c.agent_prepare(['RUST', '1.95', '-', '60', '-', OPERATION, self.encoded()])
        selected.assert_called_once_with(manifest, 'RUST', '1.95.0', self.descriptor()['url'], 'sha256', 'a' * 64, 'rust', '-')

    def test_changed_descriptor_rejects_before_root_directory_lock_or_volume(self):
        encoded = self.encoded(self.descriptor(operation='b' * 32))
        with mock.patch.dict(sys.modules, {'fcntl': types.SimpleNamespace()}), \
            mock.patch.object(self.c.os, 'geteuid', return_value=0, create=True), \
            mock.patch.object(self.c, 'owned_directory') as directory, \
            mock.patch.object(self.c.os, 'open') as opened, \
            self.assertRaises(self.c.PreparationFailure):
            self.c.agent_main(['prepare', 'RUST', '1.95', '-', '60', '-', OPERATION, encoded])
        directory.assert_not_called()
        opened.assert_not_called()

    def test_sha512_remains_official_installation_identity_not_descriptor_hash(self):
        digest = 'a' * 128
        value = self.descriptor(ecosystem='DOTNET', branch='8', requestedExact='8.0.100',
            version='8.0.100', url='https://builds.dotnet.microsoft.com/archive', algorithm='sha512', digest=digest, layout='flat')
        selected = self.c.resolution_decode(self.encoded(value), 'DOTNET', '8', '8.0.100', OPERATION, self.source)
        self.assertEqual(digest, selected['digest'])
        self.assertEqual(hashlib.sha256(('DOTNET\n8.0.100\n' + digest).encode()).hexdigest(),
            self.c.agent_installation_key('DOTNET', '8.0.100', selected['digest']))

    def test_equivalent_java_build_keeps_original_request_and_rejects_changed_release(self):
        value = self.descriptor(ecosystem='JAVA', branch='21', requestedExact='21.0.5-b11',
            version='21.0.5+11', url='https://api.adoptium.net/archive', layout='archive')
        selected = self.c.resolution_decode(self.encoded(value), 'JAVA', '21', '21.0.5-b11', OPERATION, self.source)
        self.assertEqual('21.0.5-b11', selected['requestedExact'])
        self.assertEqual('21.0.5+11', selected['version'])
        for version in ('21.0.5+12', '21.0.6+11'):
            value['version'] = version
            with self.assertRaises(self.c.PreparationFailure):
                self.c.resolution_decode(self.encoded(value), 'JAVA', '21', '21.0.5-b11', OPERATION, self.source)


if __name__ == '__main__':
    unittest.main()
