import copy
import hashlib
import io
import json
import sqlite3
import tarfile
import unittest

import verify_current_backup_memory as verifier


def fixture(kind='FILES', contents=None, extra=None):
    if contents is None:
        contents = [b'configuration-value', b'']
    metadata = {'kind': 'f', 'bytes': 0, 'device': 64769, 'inode': 11, 'mode': '600', 'uid': 0, 'gid': 0, 'mtime': '1792261437.0000000000'}
    expected = {'schema': 1, 'format': verifier.FORMAT, 'kind': kind, 'run': 'pre-reinstall-test-296-01',
                'operationRoot': '/var/lib/windowstolinux/backups/pre-reinstall-test-296-01-0123456789abcdef', 'items': []}
    for index, body in enumerate(contents):
        meta = dict(metadata, bytes=len(body), inode=11 + index)
        expected['items'].append({'source': (f'/var/opt/windowstolinux/apps/example/databases/db{index}/data.db' if kind == 'DATABASES' else f'/etc/opt/windowstolinux/apps/example/{index}.env'), 'sourceMetadata': meta})
    raw = json.dumps(expected, separators=(',', ':')).encode()
    manifest = {key: value for key, value in expected.items() if key != 'items'}
    manifest['selectionSha256'] = hashlib.sha256(raw).hexdigest()
    manifest['items'] = []
    for index, (frozen, body) in enumerate(zip(expected['items'], contents)):
        manifest['items'].append(dict(frozen, member=f'databases/{index:04}.sqlite' if kind == 'DATABASES' else f'files/{index:04}.bin',
                                      type='SQLITE_ONLINE' if kind == 'DATABASES' else 'FILE', bytes=len(body), sha256=hashlib.sha256(body).hexdigest(),
                                      consistency='SQLITE_ONLINE_BACKUP_INDEPENDENT_POINT' if kind == 'DATABASES' else 'FD_STABLE_READ'))
    manifest_raw = json.dumps(manifest, separators=(',', ':')).encode()
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w', format=tarfile.USTAR_FORMAT) as archive:
        for name, body in [('manifest.json', manifest_raw)] + [(item['member'], body) for item, body in zip(manifest['items'], contents)]:
            member = tarfile.TarInfo(name)
            member.size, member.mode = len(body), 0o600
            archive.addfile(member, io.BytesIO(body))
        if extra is not None:
            archive.addfile(extra, io.BytesIO(b''))
    clear = bytearray(stream.getvalue())
    receipt = {key: value for key, value in manifest.items() if key != 'items'}
    receipt.update(bundlePath=expected['operationRoot'] + '/bundle.tar', bundleBytes=len(clear), bundleSha256=hashlib.sha256(clear).hexdigest(),
                   manifestSha256=hashlib.sha256(manifest_raw).hexdigest(), manifest=manifest, complete=True)
    return clear, raw, receipt


class CurrentBackupTests(unittest.TestCase):
    def test_files_exact_mapping_including_empty_file_and_no_payload_output(self):
        clear, expected, receipt = fixture()
        result = verifier.validate_bundle(clear, expected, receipt)
        self.assertEqual((result['itemCount'], result['itemBytes']), (2, 19))
        self.assertFalse(result['plaintextExtractedToDisk'])
        self.assertNotIn('configuration-value', json.dumps(result))

    def test_rejects_same_content_under_changed_source_identity_and_forged_selection(self):
        clear, expected, receipt = fixture()
        changed = copy.deepcopy(receipt)
        changed['manifest']['items'][0]['sourceMetadata']['inode'] += 1
        with self.assertRaisesRegex(verifier.GuardError, 'MANIFEST_SOURCE_METADATA_MISMATCH'):
            verifier.validate_bundle(clear, expected, changed)
        with self.assertRaisesRegex(verifier.GuardError, 'PRODUCER_SELECTION_MISMATCH'):
            verifier.validate_bundle(clear, expected + b'\n', receipt)

    def test_rejects_extra_member_symlink_duplicate_and_trailing_data(self):
        for name, kind in [('unexpected', tarfile.REGTYPE), ('files/0000.bin', tarfile.REGTYPE), ('link', tarfile.SYMTYPE)]:
            item = tarfile.TarInfo(name)
            item.type = kind
            clear, expected, receipt = fixture(extra=item)
            with self.subTest(name=name, kind=kind), self.assertRaises(verifier.GuardError):
                verifier.validate_bundle(clear, expected, receipt)
        clear, expected, receipt = fixture()
        clear[-1] = 1
        receipt.update(bundleSha256=hashlib.sha256(clear).hexdigest())
        with self.assertRaisesRegex(verifier.GuardError, 'TRAILING_DATA'):
            verifier.validate_bundle(clear, expected, receipt)

    def test_rejects_corrupted_member_even_when_outer_hash_is_updated(self):
        clear, expected, receipt = fixture()
        offset = clear.index(b'configuration-value')
        clear[offset] ^= 1
        receipt['bundleSha256'] = hashlib.sha256(clear).hexdigest()
        with self.assertRaisesRegex(verifier.GuardError, 'TAR_FILE_SHA_MISMATCH'):
            verifier.validate_bundle(clear, expected, receipt)

    def test_two_independent_sqlite_snapshots_and_corruption(self):
        dbs = []
        for table in ('alpha', 'beta'):
            with sqlite3.connect(':memory:') as connection:
                connection.execute(f'CREATE TABLE {table}(id INTEGER PRIMARY KEY, value TEXT)')
                connection.execute(f'INSERT INTO {table}(value) VALUES (?)', ('private-business-value',))
                dbs.append(connection.serialize())
        clear, expected, receipt = fixture('DATABASES', dbs)
        result = verifier.validate_bundle(clear, expected, receipt)
        self.assertEqual(result['itemCount'], 2)
        self.assertTrue(result['databasesAreNotOneSimultaneousSnapshot'])
        self.assertTrue(all(item['sqlite']['integrityCheck'] == 'ok' for item in result['items']))
        self.assertNotIn('private-business-value', json.dumps(result))
        clear, expected, receipt = fixture('DATABASES', [b'invalid sqlite'])
        with self.assertRaisesRegex(verifier.GuardError, 'NOT_SQLITE_HEADER'):
            verifier.validate_bundle(clear, expected, receipt)

    def test_producer_failure_or_truncation_cannot_be_used_as_proof(self):
        command = {'exitObserved': True, 'exitCode': 0, 'stdout': '{}\n', 'stderr': '', 'truncated': False}
        for outcome, altered in [('UNKNOWN', command), ('SUCCEEDED', dict(command, truncated=True)),
                                 ('SUCCEEDED', dict(command, exitCode=1)), ('SUCCEEDED', dict(command, stderr='warning'))]:
            with self.subTest(outcome=outcome, altered=altered), self.assertRaises(verifier.GuardError):
                verifier.producer_output({'outcome': outcome, 'commandResult': altered})


if __name__ == '__main__':
    unittest.main()
