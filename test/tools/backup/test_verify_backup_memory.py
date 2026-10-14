"""Scoped public bundle regressions; never opens a business database or backup."""
import copy
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import sys
import tarfile
from urllib.parse import quote

import verify_backup_memory as v

ROOT = Path(__file__).resolve().parents[3]
STAGE = ROOT / '.ai-workspace/agent-live-evidence/agent-autonomy-20261012/backup-bundle-verifier-149'
ROUND = sys.argv[1] if len(sys.argv) == 2 else 'windows-01'
if ROUND not in ('windows-01', 'windows-02', 'context-153'):
    raise SystemExit(64)
if ROUND == 'context-153':
    STAGE = ROOT / '.ai-workspace/agent-live-evidence/agent-autonomy-20261012/backup-context-verifier-153'
EVIDENCE = STAGE / ROUND
TMP = STAGE / 'tmp/round02' if ROUND == 'windows-02' else STAGE / 'tmp'


def check(value, code):
    if not value:
        raise AssertionError(code)


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


def sha(value):
    return hashlib.sha256(value).hexdigest()


def wal_snapshot():
    """Same sqlite3 mode=ro/backup/pages16/DELETE APIs as producer, using public files."""
    source_path = TMP / 'public-wal-source.sqlite'
    target_path = TMP / 'public-wal-snapshot.sqlite'
    writer = sqlite3.connect(source_path)
    source = target = None
    try:
        check(writer.execute('PRAGMA journal_mode=WAL').fetchone() == ('wal',), 'PUBLIC_WAL_MODE')
        writer.execute('PRAGMA wal_autocheckpoint=0')
        writer.execute('CREATE TABLE public_wal(id INTEGER PRIMARY KEY, value INTEGER)')
        writer.commit()
        writer.execute('PRAGMA wal_checkpoint(TRUNCATE)')
        before_sha = sha(source_path.read_bytes())
        writer.execute('INSERT INTO public_wal VALUES(1,149)')
        writer.commit()
        wal = Path(str(source_path) + '-wal')
        check(wal.exists() and wal.stat().st_size > 0, 'PUBLIC_COMMITTED_WAL_PRESENT')
        check(sha(source_path.read_bytes()) == before_sha, 'PUBLIC_MAIN_FILE_UNCHANGED_AFTER_COMMIT')
        source = sqlite3.connect('file:' + quote(source_path.as_posix(), safe='/:') + '?mode=ro', uri=True, timeout=5)
        source.enable_load_extension(False)
        source.execute('PRAGMA query_only=ON')
        target = sqlite3.connect(target_path, timeout=5)
        check(target.execute('PRAGMA journal_mode=DELETE').fetchone() == ('delete',), 'PUBLIC_SNAPSHOT_DELETE_MODE')
        source.backup(target, pages=16, sleep=0.05)
        check(target.execute('PRAGMA journal_mode=DELETE').fetchone() == ('delete',), 'PUBLIC_POST_BACKUP_DELETE_MODE')
        count = target.execute('SELECT COUNT(*) FROM public_wal WHERE value=149').fetchone()[0]
        check(count == 1, 'PUBLIC_COMMITTED_WAL_ROW_PRESERVED')
        check(target.execute('PRAGMA integrity_check(1)').fetchmany(2) == [('ok',)], 'PUBLIC_SNAPSHOT_INTEGRITY')
        check(target.execute('PRAGMA foreign_key_check').fetchmany(1) == [], 'PUBLIC_SNAPSHOT_FOREIGN_KEYS')
        target.close(); target = None
        source.close(); source = None
        check(not any(Path(str(target_path) + suffix).exists() for suffix in ('-wal', '-shm', '-journal')), 'PUBLIC_SNAPSHOT_NO_SIDECAR')
        snapshot = target_path.read_bytes()
        check(snapshot[18:20] == b'\x01\x01', 'PUBLIC_SNAPSHOT_ROLLBACK_HEADER')
        return snapshot, {'writerRemainedOpenDuringBackup': True, 'committedWalPresent': True,
                          'rawMainFileUnchangedAfterCommittedInsert': True, 'snapshotCommittedRowCount': count,
                          'snapshotJournalMode': 'delete', 'snapshotHasNoSidecars': True}
    finally:
        if target is not None:
            target.close()
        if source is not None:
            source.close()
        writer.close()


def public_inputs():
    first, wal_proof = wal_snapshot()
    bodies = {'databases/00.sqlite': first}
    for index in range(1, 12):
        connection = sqlite3.connect(':memory:')
        try:
            connection.execute('CREATE TABLE public_items(id INTEGER PRIMARY KEY, value INTEGER)')
            connection.execute('INSERT INTO public_items VALUES(1,?)', (index,))
            connection.commit()
            bodies[f'databases/{index:02}.sqlite'] = connection.serialize()
        finally:
            connection.close()
    sources = [{'sourcePath': f'/var/opt/windowstolinux/apps/public-test-149-{i:02}/databases/tasks/tasks.db',
                'sourceDevice': '2049', 'sourceInode': str(14900 + i), 'sourceBytes': len(bodies[f'databases/{i:02}.sqlite']),
                'member': f'databases/{i:02}.sqlite', 'sourceNlink': 1,
                'snapshotBytes': len(bodies[f'databases/{i:02}.sqlite']), 'snapshotSha256': sha(bodies[f'databases/{i:02}.sqlite']),
                'method': 'PYTHON_SQLITE3_ONLINE_BACKUP', 'integrity': 'ok'} for i in range(12)]
    manifest = {'schema': 1, 'type': v.SNAPSHOT_TYPE, 'run': 'public-bundle-149', 'server': 'public-test',
                'fingerprint': 'SHA256:ioa2rovqWA0tIF954jRvT2RUnbzjPEirj3BM7HTAySU', 'layoutSha256': '1' * 64,
                'sources': sources}
    return bodies, manifest, wal_proof


def make_tar(bodies, manifest, transform=None):
    entries = [(name, value) for name, value in bodies.items()] + [('manifest.json', encoded(manifest))]
    if transform:
        entries = transform(entries)
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w', format=tarfile.USTAR_FORMAT) as archive:
        for name, body in entries:
            header = tarfile.TarInfo(name)
            header.size = len(body)
            header.mode = 0o400
            if name.startswith('LINK:'):
                header.name = name[5:]
                header.type = tarfile.SYMTYPE
                header.linkname = 'elsewhere'
                header.size = 0
            if name.startswith('HARDLINK:'):
                header.name = name[9:]
                header.type = tarfile.LNKTYPE
                header.linkname = 'databases/01.sqlite'
                header.size = 0
            if name.startswith('SPARSE:'):
                header.name = name[7:]
                header.type = tarfile.GNUTYPE_SPARSE
            archive.addfile(header, io.BytesIO(body) if header.isreg() else None)
    return bytearray(stream.getvalue())


def producer_receipt(bundle, manifest, manifest_body=None):
    result = {key: copy.deepcopy(manifest[key]) for key in ('schema', 'type', 'run', 'server', 'fingerprint', 'layoutSha256', 'sources')}
    result.update(complete=True, operationRoot='/var/lib/windowstolinux/public-backup-149',
                  ownerMarkerSha256='2' * 64, bundlePath='/var/lib/windowstolinux/public-backup-149/bundle.tar',
                  bundleBytes=len(bundle), bundleSha256=sha(bundle), manifestMember='manifest.json',
                  manifestSha256=sha(encoded(manifest) if manifest_body is None else manifest_body),
                  limits={'snapshotMaxBytes': 1048576, 'bundleMaxBytes': 16777216,
                          'peakMaxBytes': 33554432, 'minimumAvailableBytes': 134217728,
                          'availableBeforeBytes': 268435456}, details={'publicFixture': True, 'nativeTools': {}})
    return {'outcome': 'SUCCEEDED', 'commandResult': {'exitObserved': True, 'exitCode': 0,
            'stdout': encoded(result).decode() + '\n', 'stderr': '', 'truncated': False}, 'backupComplete': False}


def main():
    check(TMP.resolve(strict=True) == TMP, 'OWN_TMP_BOUNDARY')
    api = v.WindowsApi()
    bodies, manifest, wal_proof = public_inputs()
    results = []
    counter = 0

    def run_case(name, bundle, receipt, expected=None):
        nonlocal counter
        counter += 1
        cipher = None
        try:
            cipher = api.crypt(bundle, protect=True)
            cipher_path = TMP / f'public-{counter:02}.dpapi'
            receipt_path = TMP / f'public-{counter:02}-sdk.json'
            cipher_path.write_bytes(cipher)
            receipt_bytes = encoded(receipt)
            receipt_path.write_bytes(receipt_bytes)
            outcome = v.evaluate(cipher=str(cipher_path), expected_cipher_sha=sha(cipher),
                                 expected_plaintext_sha=sha(bundle), expected_bytes=len(bundle),
                                 snapshot_receipt=str(receipt_path), snapshot_receipt_sha=sha(receipt_bytes), api=api)
            record = {'case': name, 'status': 'OBSERVED', 'verifierOutcome': outcome}
            results.append(record)
            (EVIDENCE / 'case-progress.json').write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
            if expected == 'PASS':
                check(outcome['status'] == 'PASS' and outcome['snapshotBundle']['databasesIndependentlyVerified'] == 12, name)
            else:
                check(outcome['status'] != 'PASS', name)
                if expected:
                    check(outcome.get('code') == expected, name + '_EXACT_CODE')
            record['status'] = 'PASS'
            (EVIDENCE / 'case-progress.json').write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
        finally:
            v.wipe(cipher)
            v.wipe(bundle)

    bundle = make_tar(bodies, manifest)
    run_case('PUBLIC_12_SQLITE_BUNDLE_DPAPI_ROUNDTRIP_WITH_COMMITTED_WAL_SNAPSHOT', bundle, producer_receipt(bundle, manifest), 'PASS')
    for name, transform in [
        ('TAR_EXTRA_MEMBER', lambda e: e + [('extra', b'public')]),
        ('TAR_MISSING_MEMBER', lambda e: e[1:]),
        ('TAR_ABSOLUTE_MEMBER', lambda e: [('/databases/00.sqlite', e[0][1])] + e[1:]),
        ('TAR_PARENT_TRAVERSAL_MEMBER', lambda e: [('../databases/00.sqlite', e[0][1])] + e[1:]),
        ('TAR_DUPLICATE_MEMBER', lambda e: [e[1]] + e[1:]),
        ('TAR_SYMBOLIC_LINK_MEMBER', lambda e: [('LINK:databases/00.sqlite', b'')] + e[1:]),
        ('TAR_HARDLINK_MEMBER', lambda e: [('HARDLINK:databases/00.sqlite', b'')] + e[1:]),
        ('TAR_SPARSE_MEMBER', lambda e: [('SPARSE:databases/00.sqlite', e[0][1])] + e[1:]),
        ('TAR_OVERSIZED_DATABASE_MEMBER', lambda e: [(e[0][0], b'0' * (1048576 + 1))] + e[1:]),
    ]:
        bundle = make_tar(bodies, manifest, transform)
        run_case(name, bundle, producer_receipt(bundle, manifest))
    altered = copy.deepcopy(manifest)
    altered['sources'][0]['snapshotSha256'] = '0' * 64
    bundle = make_tar(bodies, altered)
    run_case('DATABASE_MEMBER_SHA256_MISMATCH', bundle, producer_receipt(bundle, altered), 'SNAPSHOT_DATABASE_SHA256_MISMATCH')
    bundle = make_tar(bodies, manifest)
    receipt = producer_receipt(bundle, manifest)
    core = json.loads(receipt['commandResult']['stdout']);core['manifestSha256'] = '0' * 64
    receipt['commandResult']['stdout'] = encoded(core).decode() + '\n'
    run_case('MANIFEST_SHA256_MISMATCH', bundle, receipt, 'SNAPSHOT_MANIFEST_SHA256_MISMATCH')
    altered = copy.deepcopy(manifest);altered['sources'][0]['sourceBytes'] += 1
    bundle = make_tar(bodies, altered)
    receipt = producer_receipt(bundle, manifest, encoded(altered))
    run_case('SOURCE_DESCRIPTOR_DIFFERS_FROM_RECEIPT', bundle, receipt, 'SNAPSHOT_MANIFEST_RECEIPT_MISMATCH')
    altered = copy.deepcopy(manifest);altered['extra'] = 'public'
    bundle = make_tar(bodies, altered)
    run_case('MANIFEST_EXTRA_FIELD', bundle, producer_receipt(bundle, manifest, encoded(altered)), 'SNAPSHOT_MANIFEST_FIELDS_INVALID')
    bundle = make_tar(bodies, manifest);receipt = producer_receipt(bundle, manifest);receipt['outcome'] = 'UNKNOWN'
    run_case('SDK_RECEIPT_UNKNOWN_NOT_SUCCESS', bundle, receipt, 'SNAPSHOT_SDK_OUTCOME_NOT_SUCCEEDED')
    # USTAR header plus a valid pax extended header must still be rejected.
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w', format=tarfile.PAX_FORMAT, pax_headers={'public-extra': '149'}) as archive:
        for name, body in list(bodies.items()) + [('manifest.json', encoded(manifest))]:
            header = tarfile.TarInfo(name);header.size = len(body);header.mode = 0o400
            archive.addfile(header, io.BytesIO(body))
    bundle = bytearray(stream.getvalue())
    run_case('PAX_EXTRA_REJECTED', bundle, producer_receipt(bundle, manifest))
    bundle = make_tar(bodies, manifest);bundle.extend(b'x' + b'\0' * 511)
    run_case('TRAILING_NONZERO_TAR_DATA_REJECTED', bundle, producer_receipt(bundle, manifest), 'SNAPSHOT_TAR_TRAILING_DATA_OR_MEMBERS_INVALID')
    report = {'status': 'ALL_SCOPED_BUNDLE_CASES_PASS', 'cases': results, 'caseCount': len(results),
              'publicWalProof': wal_proof, 'businessSourceOrBackupRead': False, 'remoteActions': 0,
              'sqliteVersion': sqlite3.sqlite_version, 'plaintextTarExtractedToDisk': False,
              'publicSQLiteFilesCreatedOnlyForWalCounterexample': True,
              'actualLinuxRlimitsOrTwelveRemoteDatabasesVerified': False}
    (EVIDENCE / 'test-results.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(f'PUBLIC_BUNDLE149_PASS cases={len(results)} independentDatabases=12 committedWalRowPreserved=true remoteActions=0')


def context_inputs():
    rows, bodies, sources = [], {}, []
    for index in range(310):
        size = 0 if index == 0 else (81 if index == 309 else 93)
        body = b'p' * size
        row = {'path': f'/var/lib/windowstolinux/public-context-153/{index:04}.receipt',
               'bytes': size, 'uid': 0, 'gid': 0, 'mode': (0o400, 0o600, 0o444)[index % 3],
               'device': '2049', 'inode': str(15300 + index)}
        member = f'files/{index:04}.bin'
        rows.append(row)
        bodies[member] = body
        sources.append(dict(row, member=member, sha256=sha(body)))
    manifest = {'schema': 1, 'type': v.CONTEXT_TYPE, 'run': 'public-context-153', 'server': 'public-test',
                'fingerprint': 'SHA256:ioa2rovqWA0tIF954jRvT2RUnbzjPEirj3BM7HTAySU',
                'layoutSha256': '1' * 64, 'sources': sources}
    return rows, bodies, manifest


def context_receipt(bundle, manifest):
    core = copy.deepcopy(manifest)
    core.update(complete=True, operationRoot='/var/lib/windowstolinux/public-backup-153',
                ownerMarkerSha256='2' * 64, bundlePath='/var/lib/windowstolinux/public-backup-153/context.tar',
                bundleBytes=len(bundle), bundleSha256=sha(bundle), manifestMember='manifest.json',
                manifestSha256=sha(encoded(manifest)), sourceCount=len(manifest['sources']),
                sourceBytes=sum(row['bytes'] for row in manifest['sources']), details={'publicFixture': True})
    return {'outcome': 'SUCCEEDED', 'commandResult': {'exitObserved': True, 'exitCode': 0,
            'stdout': encoded(core).decode() + '\n', 'stderr': '', 'truncated': False}}


def main_context():
    check(TMP.resolve(strict=True) == TMP, 'OWN_TMP_BOUNDARY')
    api = v.WindowsApi()
    rows, bodies, manifest = context_inputs()
    results = []

    def run_case(name, bundle, receipt, metadata, expected, *, snapshot=False, overrides=None):
        cipher = None
        try:
            index = len(results) + 1
            cipher = api.crypt(bundle, protect=True)
            cipher_path = TMP / f'public-{index:02}.dpapi'
            receipt_path = TMP / f'public-{index:02}-sdk.json'
            metadata_path = TMP / f'public-{index:02}-metadata.json'
            cipher_path.write_bytes(cipher)
            receipt_bytes = encoded(receipt)
            receipt_path.write_bytes(receipt_bytes)
            kwargs = dict(cipher=str(cipher_path), expected_cipher_sha=sha(cipher),
                          expected_plaintext_sha=sha(bundle), expected_bytes=len(bundle), api=api)
            if snapshot:
                kwargs.update(snapshot_receipt=str(receipt_path), snapshot_receipt_sha=sha(receipt_bytes))
            else:
                metadata_bytes = encoded(metadata)
                metadata_path.write_bytes(metadata_bytes)
                kwargs.update(context_receipt=str(receipt_path), context_receipt_sha=sha(receipt_bytes),
                              context_metadata=str(metadata_path), context_metadata_sha=sha(metadata_bytes))
            if overrides:
                kwargs.update(overrides)
            outcome = v.evaluate(**kwargs)
            record = {'case': name, 'status': 'OBSERVED', 'verifierOutcome': outcome}
            results.append(record)
            (EVIDENCE / 'case-progress.json').write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
            if expected == 'PASS':
                check(outcome['status'] == 'PASS', name)
                if snapshot:
                    check(outcome['snapshotBundle']['databasesIndependentlyVerified'] == 12, name)
                else:
                    check(outcome['contextBundle']['sourceCount'] == 310
                          and outcome['contextBundle']['sourceBytes'] == 28725
                          and outcome['contextBundle']['emptyFileCount'] == 1, name)
            else:
                check(outcome['status'] == 'REJECTED' and outcome['code'] == expected, name + '_EXACT_REJECTION')
            record['status'] = 'PASS'
            (EVIDENCE / 'case-progress.json').write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
        finally:
            v.wipe(cipher)
            v.wipe(bundle)

    bundle = make_tar(bodies, manifest)
    run_case('CONTEXT_310_FILES_ZERO_RECEIPT_AND_CONFIGURATION_ROUNDTRIP', bundle,
             context_receipt(bundle, manifest), rows, 'PASS')
    for name, overrides, expected in [
        ('CONTEXT_METADATA_SHA_WRONG', {'context_metadata_sha': '0' * 64}, 'CONTEXT_METADATA_SHA256_MISMATCH'),
        ('CONTEXT_RECEIPT_SHA_WRONG', {'context_receipt_sha': '0' * 64}, 'CONTEXT_RECEIPT_SHA256_MISMATCH'),
        ('CONTEXT_ARGUMENT_QUARTET_INCOMPLETE', {'context_metadata_sha': None}, 'CONTEXT_ARGUMENT_QUARTET_REQUIRED'),
        ('CONTEXT_SQLITE_MODE_MIX_REJECTED', {'sqlite': True}, 'CONTEXT_MODES_MUST_NOT_MIX'),
    ]:
        bundle = make_tar(bodies, manifest)
        run_case(name, bundle, context_receipt(bundle, manifest), rows, expected, overrides=overrides)
    altered_rows = copy.deepcopy(rows); altered_rows[1]['inode'] = '99999'
    bundle = make_tar(bodies, manifest)
    run_case('CONTEXT_FROZEN_METADATA_DIFFERS', bundle, context_receipt(bundle, manifest), altered_rows,
             'CONTEXT_SOURCE_METADATA_MISMATCH')
    altered = copy.deepcopy(manifest); altered['sources'][1]['sha256'] = '0' * 64
    bundle = make_tar(bodies, altered)
    run_case('CONTEXT_FILE_HASH_DIFFERS', bundle, context_receipt(bundle, altered), rows, 'CONTEXT_FILE_SHA256_MISMATCH')
    altered_rows = copy.deepcopy(rows); altered_rows[1]['path'] += '.db'
    bundle = make_tar(bodies, manifest)
    run_case('CONTEXT_DATABASE_SOURCE_FORBIDDEN', bundle, context_receipt(bundle, manifest), altered_rows,
             'CONTEXT_DATABASE_SOURCE_PATH_FORBIDDEN')
    altered_bodies = dict(bodies); altered_bodies['files/0001.bin'] = b'SQLite format 3\x00' + b'p' * 77
    altered = copy.deepcopy(manifest); altered['sources'][1]['sha256'] = sha(altered_bodies['files/0001.bin'])
    bundle = make_tar(altered_bodies, altered)
    run_case('CONTEXT_SQLITE_HEADER_FORBIDDEN', bundle, context_receipt(bundle, altered), rows,
             'CONTEXT_SQLITE_HEADER_FORBIDDEN')
    bundle = make_tar(bodies, manifest); receipt = context_receipt(bundle, manifest); receipt['outcome'] = 'UNKNOWN'
    run_case('CONTEXT_UNKNOWN_SDK_RECEIPT', bundle, receipt, rows, 'CONTEXT_SDK_OUTCOME_NOT_SUCCEEDED')
    bundle = make_tar(bodies, manifest, lambda entries: [('LINK:files/0000.bin', b'')] + entries[1:])
    run_case('CONTEXT_LINK_REJECTED', bundle, context_receipt(bundle, manifest), rows, 'CONTEXT_TAR_NONREGULAR_OR_EXTRA_REJECTED')
    # Only the snapshot paths affected by shared receipt/tar helpers are rerun.
    old_bodies, old_manifest, wal_proof = public_inputs()
    bundle = make_tar(old_bodies, old_manifest)
    run_case('AFFECTED_SNAPSHOT_POSITIVE_WITH_COMMITTED_WAL', bundle, producer_receipt(bundle, old_manifest), None, 'PASS', snapshot=True)
    bundle = make_tar(old_bodies, old_manifest); receipt = producer_receipt(bundle, old_manifest); receipt['outcome'] = 'UNKNOWN'
    run_case('AFFECTED_SNAPSHOT_UNKNOWN_RECEIPT', bundle, receipt, None, 'SNAPSHOT_SDK_OUTCOME_NOT_SUCCEEDED', snapshot=True)
    bundle = make_tar(old_bodies, old_manifest, lambda entries: [('LINK:databases/00.sqlite', b'')] + entries[1:])
    run_case('AFFECTED_SNAPSHOT_LINK_REJECTED', bundle, producer_receipt(bundle, old_manifest), None,
             'SNAPSHOT_TAR_NONREGULAR_OR_EXTRA_REJECTED', snapshot=True)
    report = {'status': 'ALL_SCOPED_CONTEXT_CASES_PASS', 'caseCount': len(results), 'cases': results,
              'contextCases': 11, 'affectedSnapshotCases': 3, 'publicWalProof': wal_proof,
              'businessSourceOrBackupRead': False, 'remoteActions': 0, 'sqliteVersion': sqlite3.sqlite_version,
              'plaintextTarExtractedToDisk': False, 'actualContextPayloadVerified': False,
              'allInternalCopiesSecurelyErased': False}
    (EVIDENCE / 'test-results.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(f'PUBLIC_CONTEXT153_PASS cases={len(results)} contextFiles=310 sourceBytes=28725 affectedSnapshotCases=3 remoteActions=0')


if __name__ == '__main__':
    main_context() if ROUND == 'context-153' else main()
