"""Catalog-derived NuGet route proof; all upstream traffic is synthetic loopback HTTPS."""
import copy
import pathlib
import runpy
import socket
import sys
import unittest
from unittest.mock import patch


fixture = runpy.run_path(str(pathlib.Path(__file__).with_name('locked_transport_test.py')),
                        run_name='locked_fixture')
Policy, Failure, Repository, request = (fixture[name] for name in ('Policy', 'Failure', 'Repository', 'request'))
json = fixture['json']


class NugetSignatureRouteTests(fixture['LockedTransportTests']):
    def test_catalog_signature_routes_preserve_bytes_and_boundaries(self):
        documents = {name: json.loads(pathlib.Path(name + '.json').read_text())
                     for name in ('official-nuget', 'huawei', 'custom')}
        official = documents['official-nuget']['points']['NUGET'][0]
        origin = 'https://api.nuget.org/v3-index/repository-signatures/'
        indexes = [origin + version + '/index.json' for version in ('4.7.0', '4.9.0', '5.0.0')]
        certificate = origin + 'fixture/certificates/fixture.pem'
        # This approved-prefix fixture does not assert where every valid NuGet certificate lives.
        for url in indexes + [certificate]:
            self.assertEqual(url, Policy.maps(official, url))
        self.assertIn(origin, official['sourceOrigins'])
        self.assertIn({'origin': origin, 'prefix': origin, 'pattern': '', 'replacement': ''}, official['routes'])
        rejected = [origin.replace('repository-signatures/', 'repository-signatures-evil/') + '5.0.0/index.json',
                    'https://api.nuget.org/v3-index/other/index.json',
                    'https://api.nuget.org.evil.invalid/v3-index/repository-signatures/5.0.0/index.json',
                    'https://unapproved.invalid/certificate.pem', '/v3-index/repository-signatures/5.0.0/index.json']
        for url in rejected:
            with self.subTest(rejected=url), self.assertRaises(Failure):
                Policy.maps(official, url)
        custom = documents['custom']['points']['NUGET'][0]
        self.assertTrue(custom['custom'])
        self.assertEqual(['custom.invalid'], custom['allowedHosts'])
        for url in indexes + [certificate]:
            self.assertTrue(Policy.maps(custom, url).startswith('https://custom.invalid/nuget/'))
            with self.assertRaises(Failure):
                Policy.maps(documents['huawei']['points']['NUGET'][0], url)
        lock = self.project / 'packages.lock.json'
        config = self.project / 'NuGet.Config'
        lock.write_bytes(b'{"version":1,"dependencies":{}}\n')
        config.write_bytes(b'<configuration><config><add key="signatureValidationMode" value="require"/></config>'
                           b'<trustedSigners/><packageSourceMapping/><auditSources/></configuration>\n')
        originals = lock.read_bytes(), config.read_bytes()
        index_bytes = b'{ "allRepositorySigned" : true, "repositorySignatures" : [] }\n'
        certificate_bytes = b'fixture certificate bytes; no real SDK signature-validation claim\n'
        served = {url.removeprefix('https://api.nuget.org'): index_bytes for url in indexes}
        served[certificate.removeprefix('https://api.nuget.org')] = certificate_bytes
        def respond(path):
            return 200, 'application/octet-stream', served[path]
        with Repository(self.certificates.tls_context('api.nuget.org'), respond) as repository:
            # Preserve catalog routes and identity target; redirect only the socket to loopback.
            resolve = socket.getaddrinfo
            def loopback(host, port, *args, **kwargs):
                if host == 'api.nuget.org':
                    host, port = '127.0.0.1', repository.server.server_port
                elif host != '127.0.0.1':
                    raise AssertionError('non-loopback network attempt: ' + repr((host, port)))
                return resolve(host, port, *args, **kwargs)
            with patch('socket.getaddrinfo', side_effect=loopback):
                policy = Policy(copy.deepcopy(documents['official-nuget']))
                policy.transport_options = lambda endpoint, url: ({}, self.upstream_trust)
                with self.transport(policy, 'NUGET') as transport:
                    for url in indexes + [certificate]:
                        self.assertEqual((200, served[url.removeprefix('https://api.nuget.org')]),
                                         request(transport, url.removeprefix('https://api.nuget.org'), 'api.nuget.org'))
                    for url in rejected:
                        with self.assertRaises(Failure):
                            transport.fetch(url, self.directory / 'rejected')
                self.assertEqual(list(served), [entry[0] for entry in repository.requests])
                # A failing custom-only source must never reach the approved official socket.
                policy = Policy(copy.deepcopy(documents['custom']))
                seen = []
                def fail_custom(endpoint, url):
                    seen.append(url)
                    self.assertTrue(url.startswith('https://custom.invalid/nuget/'))
                    raise Failure('synthetic custom-only failure')
                policy.transport_options = fail_custom
                with self.transport(policy, 'NUGET') as transport:
                    with self.assertRaises(Failure):
                        transport.fetch(indexes[-1], self.directory / 'custom-failed')
                self.assertEqual(policy.attempts, len(seen))
                self.assertEqual(4, len(repository.requests))
        self.assertEqual(originals, (lock.read_bytes(), config.read_bytes()))


if __name__ == '__main__':
    unittest.main(verbosity=2)
