"""Synthetic Go distribution redirects; no DNS, public transfers or credential material."""
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
runtime = {'__name__': 'go_redirect_fixture'}
exec(compile(pathlib.Path(runtime_path).read_text(encoding='utf-8'), runtime_path, 'exec'), runtime)
Policy, Failure, Redirect = (runtime[name] for name in ('MirrorPolicy', 'MirrorFailure', 'MirrorRedirect'))
DOCUMENT = json.loads(pathlib.Path(policy_path).read_text(encoding='utf-8'))
ORIGINAL = 'https://proxy.golang.org/modernc.org/sqlite/@v/v1.36.3.zip'
BUCKET = 'https://storage.googleapis.com/proxy-golang-org-prod/'
TARGET = BUCKET + 'synthetic-module.zip?Expires=1&Signature=synthetic%2Bsignature%2Fvalue%3D'
PAYLOAD = b'synthetic-go-module-zip'


class GoRedirectTests(unittest.TestCase):
    def setUp(self):
        self.endpoint = copy.deepcopy(DOCUMENT['points']['GO_MODULE'][0])
        self.network = mock.patch.object(socket, 'getaddrinfo', side_effect=AssertionError('network forbidden'))
        self.network.start()
        self.addCleanup(self.network.stop)

    def test_exact_distribution_edge_and_default_https_port(self):
        self.assertEqual(['proxy.golang.org'], self.endpoint['allowedHosts'])
        for target in (TARGET, TARGET.replace('storage.googleapis.com/', 'storage.googleapis.com:443/')):
            self.assertEqual(target, Policy.validate_redirect(self.endpoint, ORIGINAL, target))
        self.assertEqual(ORIGINAL, Policy.validate_redirect(self.endpoint, ORIGINAL, ORIGINAL))
        safe_encoded = BUCKET + 'module%21name.zip'
        self.assertEqual(safe_encoded, Policy.validate_redirect(self.endpoint, ORIGINAL, safe_encoded))

    def test_redirect_rejects_bucket_host_path_and_url_escapes(self):
        invalid = [
            'https://storage.googleapis.com/other-bucket/module.zip',
            'https://storage.googleapis.com/proxy-golang-org-prod-lookalike/module.zip',
            'https://storage.googleapis.com/proxy-golang-org-prod',
            'https://storage.googleapis.com.evil.invalid/proxy-golang-org-prod/module.zip',
            TARGET.replace('https:', 'http:', 1),
            TARGET.replace('storage.googleapis.com/', 'storage.googleapis.com:444/'),
            TARGET.replace('storage.googleapis.com/', 'storage.googleapis.com:/'),
            TARGET.replace('storage.googleapis.com/', 'user@storage.googleapis.com/'),
            TARGET.replace('storage.googleapis.com/', '@storage.googleapis.com/'),
            TARGET + '#fragment', TARGET + '#', TARGET + '\n',
        ]
        for path in ('../other.zip', './module.zip', 'nested/../../other.zip', '%2e%2e/other.zip',
                     '%2E/module.zip', 'nested%2f..%2fother.zip', 'nested%5c..%5cother.zip',
                     '%252e%252e/other.zip', '\\other.zip', 'nested//other.zip', 'module%00.zip'):
            invalid.append(BUCKET + path)
        for target in invalid:
            with self.subTest(target=target), self.assertRaises(Failure):
                Policy.validate_redirect(self.endpoint, ORIGINAL, target)

    def test_initial_download_custom_and_other_sources_get_no_exception(self):
        with self.assertRaises(Failure):
            Policy.validate_url(self.endpoint, TARGET)
        for endpoint in (
                dict(self.endpoint, redirectRules=[]),
                dict(self.endpoint, providerId='custom', custom=True, official=False, redirectRules=[]),
                dict(self.endpoint, providerId='custom', custom=True, official=False),
                dict(self.endpoint, providerId='another-source', allowedHosts=['other.invalid'], redirectRules=[])):
            with self.assertRaises(Failure):
                Policy.validate_redirect(endpoint, ORIGINAL, TARGET)
        with self.assertRaises(Failure):
            Policy.validate_redirect(self.endpoint, 'https://unselected.invalid/module.zip', TARGET)
        with self.assertRaises(Failure):
            Policy.validate_redirect(self.endpoint, ORIGINAL.replace('https:', 'http:', 1), TARGET)

    def test_distribution_host_does_not_gain_a_second_redirect_edge(self):
        for target in (BUCKET + 'second.zip', 'https://storage.googleapis.com/other-bucket/module.zip',
                       'https://unselected.invalid/module.zip'):
            with self.subTest(target=target), self.assertRaises(Failure):
                Policy.validate_redirect(self.endpoint, TARGET, target)

    def test_signed_query_is_preserved_and_prior_credentials_are_removed(self):
        request = urllib.request.Request(ORIGINAL, headers={
            'Authorization': 'Bearer synthetic-only', 'Cookie': 'synthetic=only',
            'X-Source-Token': 'synthetic-only', 'Accept': 'application/zip'})
        request.wtl_bound_headers = ['X-Source-Token']
        policy = Policy(DOCUMENT)
        with mock.patch.object(policy, 'transport_options', return_value=({}, None)) as options:
            redirected = Redirect(self.endpoint, policy).redirect_request(request, None, 302, 'Found', {}, TARGET)
        self.assertEqual(TARGET, redirected.full_url)
        self.assertIsNone(redirected.get_header('Authorization'))
        self.assertIsNone(redirected.get_header('Cookie'))
        self.assertIsNone(redirected.get_header('X-source-token'))
        self.assertEqual('application/zip', redirected.get_header('Accept'))
        options.assert_called_once_with(self.endpoint, TARGET)

    def test_malformed_runtime_rules_fail_closed(self):
        rule = self.endpoint['redirectRules'][0]
        bad_rules = [None, {}, 'invalid', [rule] * 17, [None], [rule, None],
                     [dict(rule, fromHost='outside.invalid')], [dict(rule, toHost='*.googleapis.com')]]
        for prefix in ('/', 'bucket/', '/bucket', '/bucket/../', '/bucket//', '/bucket/%2e/', '/bucket/\\x/'):
            bad_rules.append([dict(rule, pathPrefix=prefix)])
        for rules in bad_rules:
            with self.subTest(rules=rules), self.assertRaises(Failure):
                Policy.validate_redirect(dict(self.endpoint, redirectRules=rules), ORIGINAL, TARGET)
        # Python's case-insensitive ASCII ranges otherwise also match Unicode long s.
        with self.assertRaises(Failure):
            Policy.validate_redirect(dict(self.endpoint, redirectRules=[dict(rule, toHost='\u017ftorage.googleapis.com')]),
                                     ORIGINAL, TARGET.replace('storage.googleapis.com', '\u017ftorage.googleapis.com'))

    def transfer_fixture(self, target):
        document = copy.deepcopy(DOCUMENT)
        document['timeouts']['attemptsPerEndpoint'] = 2
        policy = Policy(document)
        seen, validated = [], []

        def opener(*handlers):
            redirect = next(handler for handler in handlers if isinstance(handler, Redirect))

            class SyntheticOpener:
                def open(self, request, timeout):
                    seen.append(request.full_url)
                    redirected = redirect.redirect_request(request, None, 302, 'Found', {}, target)
                    seen.append(redirected.full_url)
                    response = io.BytesIO(PAYLOAD)
                    response.headers = {'Content-Length': str(len(PAYLOAD)), 'Content-Type': 'application/zip'}
                    return response
            return SyntheticOpener()

        def validator(path, headers):
            validated.append(path.read_bytes())
            self.assertEqual('application/zip', headers['Content-Type'])

        logs = io.StringIO()
        with tempfile.TemporaryDirectory(prefix='synthetic-go-transfer-') as directory:
            output = pathlib.Path(directory) / 'module.zip'
            with mock.patch.object(urllib.request, 'build_opener', side_effect=opener), contextlib.redirect_stderr(logs):
                if target == TARGET:
                    metadata = runtime['mirror_transfer'](policy, 'GO_MODULE', ORIGINAL, output,
                        'sha256', hashlib.sha256(PAYLOAD).hexdigest(), validator=validator)
                    self.assertEqual(PAYLOAD, output.read_bytes())
                    self.assertEqual('official-go_module', metadata['source'])
                    self.assertEqual([PAYLOAD], validated)
                    self.assertEqual([ORIGINAL, TARGET], seen)
                else:
                    with self.assertRaisesRegex(Failure, 'selected sources exhausted'):
                        runtime['mirror_transfer'](policy, 'GO_MODULE', ORIGINAL, output)
                    self.assertFalse(output.exists())
                    self.assertEqual([ORIGINAL, ORIGINAL], seen)
                    self.assertEqual([], validated)
            self.assertFalse(list(pathlib.Path(directory).glob('.wtl-transfer-*')))
        for value in ('Signature', 'synthetic%2Bsignature', 'Expires', 'https://'):
            self.assertNotIn(value, logs.getvalue())
        # This synthetic ledger belongs only to this fixture, never a live policy or store.
        if policy.transfer_ledger.directory is not None:
            policy.transfer_ledger.directory.cleanup()

    def test_transfer_keeps_existing_digest_validator_and_selected_source(self):
        self.transfer_fixture(TARGET)

    def test_rejected_transfer_exhausts_only_selected_source_without_implicit_fallback(self):
        self.transfer_fixture('https://storage.googleapis.com/other-bucket/module.zip?synthetic=only')


if __name__ == '__main__':
    unittest.main(argv=[sys.argv[0]], verbosity=2)
