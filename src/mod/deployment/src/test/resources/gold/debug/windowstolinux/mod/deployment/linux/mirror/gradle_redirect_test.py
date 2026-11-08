"""Synthetic Gradle distribution redirects; no DNS or public Gradle ZIP transfer."""
import contextlib
import copy
import hashlib
import io
import json
import pathlib
import socket
import sys
import tempfile
import unittest
from unittest import mock
import urllib.request

runtime_path, policy_path = sys.argv[1:3]
runtime = {'__name__': 'gradle_redirect_fixture'}
exec(compile(pathlib.Path(runtime_path).read_text(encoding='utf-8'), runtime_path, 'exec'), runtime)
Policy, Failure, Redirect = (runtime[name] for name in ('MirrorPolicy', 'MirrorFailure', 'MirrorRedirect'))
DOCUMENT = json.loads(pathlib.Path(policy_path).read_text(encoding='utf-8'))
POINT, PROVIDER = 'GRADLE_DISTRIBUTION', 'official-gradle_distribution'
ORIGINAL = 'https://downloads.gradle.org/distributions/gradle-8.10.2-bin.zip'
GITHUB = 'https://github.com/gradle/gradle-distributions/releases/download/v8.10.2/gradle-8.10.2-bin.zip'
ASSETS = 'https://release-assets.githubusercontent.com/github-production-release-asset/696192900/'
TARGET = ASSETS + 'synthetic-asset-id?sp=r&sig=synthetic%2Bsignature%2Fvalue%3D&se=synthetic%3Aexpiry'
PAYLOAD = b'synthetic-gradle-distribution-content'
DIGEST = hashlib.sha256(PAYLOAD).hexdigest()


class GradleRedirectTests(unittest.TestCase):
    def setUp(self):
        self.endpoint = copy.deepcopy(DOCUMENT['points'][POINT][0])
        for name in ('getaddrinfo', 'socket'):
            patch = mock.patch.object(socket, name, side_effect=AssertionError('network forbidden'))
            patch.start()
            self.addCleanup(patch.stop)

    def test_two_hops_keep_initial_hosts_and_repo_scoped_edge(self):
        self.assertEqual(['downloads.gradle.org', 'github.com', 'services.gradle.org'], self.endpoint['allowedHosts'])
        self.assertEqual(ORIGINAL, Policy.validate_url(self.endpoint, ORIGINAL))
        self.assertEqual(GITHUB, Policy.validate_redirect(self.endpoint, ORIGINAL, GITHUB))
        for target in (TARGET, TARGET.replace('githubusercontent.com/', 'githubusercontent.com:443/'),
                       ASSETS + 'another-asset-id?sig=another%2Bvalue'):
            self.assertEqual(target, Policy.validate_redirect(self.endpoint, GITHUB, target))
        with self.assertRaises(Failure):
            Policy.validate_redirect(self.endpoint, ORIGINAL, TARGET)

    def test_release_edge_rejects_host_repo_path_and_url_escapes(self):
        invalid = [
            TARGET.replace('/696192900/', '/696192901/'),
            TARGET.replace('/696192900/', '/696192900-lookalike/'),
            'https://release-assets.githubusercontent.com/github-production-release-asset/696192900',
            TARGET.replace('release-assets.githubusercontent.com', 'release-assets.githubusercontent.com.evil.invalid'),
            TARGET.replace('https:', 'http:', 1),
            TARGET.replace('githubusercontent.com/', 'githubusercontent.com:444/'),
            TARGET.replace('githubusercontent.com/', 'githubusercontent.com:/'),
            TARGET.replace('release-assets.', 'user@release-assets.', 1),
            TARGET.replace('release-assets.', '@release-assets.', 1),
            TARGET + '#fragment', TARGET + '#', TARGET + '\n',
        ]
        for path in ('../other.zip', './gradle.zip', 'nested/../../other.zip', '%2e%2e/other.zip',
                     '%2E/gradle.zip', 'nested%2f..%2fother.zip', 'nested%5c..%5cother.zip',
                     '%252e%252e/other.zip', '\\other.zip', 'nested//other.zip', 'gradle%00.zip'):
            invalid.append(ASSETS + path)
        for target in invalid:
            with self.subTest(target=target), self.assertRaises(Failure):
                Policy.validate_redirect(self.endpoint, GITHUB, target)
        for source in (GITHUB.replace('https:', 'http:', 1), GITHUB.replace('github.com/', 'github.com:444/'),
                       GITHUB.replace('github.com/', 'user@github.com/'), GITHUB + '#fragment'):
            with self.subTest(source=source), self.assertRaises(Failure):
                Policy.validate_redirect(self.endpoint, source, TARGET)

    def test_initial_cdn_custom_and_other_sources_cannot_borrow_the_rule(self):
        with self.assertRaises(Failure):
            Policy.validate_url(self.endpoint, TARGET)
        for endpoint in (
                dict(self.endpoint, redirectRules=[]),
                dict(self.endpoint, providerId='custom', custom=True, official=False, redirectRules=[]),
                dict(self.endpoint, providerId='custom', custom=True, official=False),
                dict(self.endpoint, providerId='another-source', allowedHosts=['other.invalid'], redirectRules=[])):
            with self.subTest(provider=endpoint['providerId']), self.assertRaises(Failure):
                Policy.validate_redirect(endpoint, GITHUB, TARGET)
        with self.assertRaises(Failure):
            Policy.validate_redirect(self.endpoint, 'https://unselected.invalid/gradle.zip', TARGET)

    def test_cdn_has_no_extra_edge_but_can_return_to_current_allowed_hosts(self):
        for target in (ASSETS + 'second-asset', 'https://unselected.invalid/gradle.zip'):
            with self.subTest(target=target), self.assertRaises(Failure):
                Policy.validate_redirect(self.endpoint, TARGET, target)
        for target in (ORIGINAL, GITHUB, 'https://services.gradle.org/distributions/gradle-8.10.2-bin.zip'):
            self.assertEqual(target, Policy.validate_redirect(self.endpoint, TARGET, target))

    def test_signed_query_bytes_and_credentials_are_checked_on_each_hop(self):
        request = urllib.request.Request(ORIGINAL, headers={
            'Authorization': 'Bearer synthetic-only', 'Cookie': 'synthetic=only',
            'X-Source-Token': 'synthetic-only', 'Accept': 'application/zip'})
        request.wtl_bound_headers = ['X-Source-Token']
        policy = Policy(DOCUMENT)
        with mock.patch.object(policy, 'transport_options', side_effect=[
                ({'X-Github-Token': 'synthetic-github-only'}, None), ({}, None)]) as options:
            redirect = Redirect(self.endpoint, policy)
            github = redirect.redirect_request(request, None, 302, 'Found', {}, GITHUB)
            final = redirect.redirect_request(github, None, 302, 'Found', {}, TARGET)
        self.assertEqual(TARGET, final.full_url)
        self.assertEqual('synthetic-github-only', github.get_header('X-github-token'))
        for redirected in (github, final):
            for name in ('Authorization', 'Cookie', 'X-source-token'):
                self.assertIsNone(redirected.get_header(name))
            self.assertEqual('application/zip', redirected.get_header('Accept'))
        self.assertIsNone(final.get_header('X-github-token'))
        self.assertEqual([mock.call(self.endpoint, GITHUB), mock.call(self.endpoint, TARGET)], options.call_args_list)

    def transfer_fixture(self, target=TARGET, expected=DIGEST):
        policy = Policy(copy.deepcopy(DOCUMENT))
        self.assertEqual(2, policy.attempts)
        self.assertEqual([PROVIDER], [entry['providerId'] for entry in policy.endpoints(POINT)])
        seen, validated = [], []

        def opener(*handlers):
            redirect = next(handler for handler in handlers if isinstance(handler, Redirect))

            class SyntheticOpener:
                def open(self, request, timeout):
                    seen.append(request.full_url)
                    github = redirect.redirect_request(request, None, 302, 'Found', {}, GITHUB)
                    seen.append(github.full_url)
                    final = redirect.redirect_request(github, None, 302, 'Found', {}, target)
                    seen.append(final.full_url)
                    response = io.BytesIO(PAYLOAD)
                    response.headers = {'Content-Length': str(len(PAYLOAD)), 'Content-Type': 'application/zip'}
                    return response
            return SyntheticOpener()

        def validator(path, headers):
            validated.append(path.read_bytes())
            self.assertEqual('application/zip', headers['Content-Type'])

        logs = io.StringIO()
        try:
            with tempfile.TemporaryDirectory(prefix='synthetic-gradle-transfer-') as directory:
                output = pathlib.Path(directory) / 'gradle.zip'
                with mock.patch.object(urllib.request, 'build_opener', side_effect=opener), contextlib.redirect_stderr(logs):
                    if target == TARGET and expected == DIGEST:
                        metadata = runtime['mirror_transfer'](policy, POINT, ORIGINAL, output,
                            'sha256', expected, validator=validator)
                        self.assertEqual(PAYLOAD, output.read_bytes())
                        self.assertEqual(PROVIDER, metadata['source'])
                        self.assertEqual(DIGEST, metadata['sha256'])
                        self.assertEqual([PAYLOAD], validated)
                        self.assertEqual([ORIGINAL, GITHUB, TARGET], seen)
                    else:
                        with self.assertRaisesRegex(Failure, 'selected sources exhausted'):
                            runtime['mirror_transfer'](policy, POINT, ORIGINAL, output,
                                'sha256', expected, validator=validator)
                        self.assertFalse(output.exists())
                        self.assertEqual([], validated)
                        expected_seen = [ORIGINAL, GITHUB, TARGET] if target == TARGET else [ORIGINAL, GITHUB] * 2
                        self.assertEqual(expected_seen, seen)
                        # Exhaustion survives a native-client retry; no new attempt or implicit source appears.
                        with self.assertRaisesRegex(Failure, 'selected sources exhausted'):
                            runtime['mirror_transfer'](policy, POINT, ORIGINAL, output, 'sha256', expected)
                        self.assertEqual(expected_seen, seen)
                    self.assertFalse(list(pathlib.Path(directory).glob('.wtl-transfer-*')))
            for value in ('sig=', 'synthetic%2Bsignature', 'se=', 'https://'):
                self.assertNotIn(value, logs.getvalue())
            if expected != DIGEST:
                self.assertIn('reason=digest-mismatch', logs.getvalue())
        finally:
            # The ledger contains only synthetic bytes and never belongs to a live policy or store.
            if policy.transfer_ledger.directory is not None:
                policy.transfer_ledger.directory.cleanup()

    def test_transfer_preserves_digest_validation_and_official_source_metadata(self):
        self.transfer_fixture()

    def test_wrong_digest_does_not_publish_the_download(self):
        self.transfer_fixture(expected='0' * 64)

    def test_rejected_target_uses_existing_two_attempt_limit_without_fallback(self):
        self.transfer_fixture(target=TARGET.replace('/696192900/', '/other-repository/'))


if __name__ == '__main__':
    unittest.main(argv=[sys.argv[0]], verbosity=2)
