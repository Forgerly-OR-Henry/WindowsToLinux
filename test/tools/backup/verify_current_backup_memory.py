"""Verify the versioned current-host backup using CurrentUser DPAPI and memory only."""
import argparse
import ctypes
import hashlib
import io
import json
import re
import sqlite3
import tarfile

from verify_backup_memory import (GuardError, NativeError, WindowsApi, digest_value,
                                 exact_fields, read_tar_member, remote_path, require,
                                 sqlite_metadata, strict_json, wipe)

FORMAT = 'WTL_PRE_REINSTALL_BUNDLE_1'
MAX_BUNDLE = 16 * 1024 * 1024
MAX_MANIFEST = 262144
MAX_RECEIPT = 1024 * 1024
META_FIELDS = ('kind', 'bytes', 'device', 'inode', 'mode', 'uid', 'gid', 'mtime')
BASE_FIELDS = ('schema', 'format', 'kind', 'selectionSha256', 'run', 'operationRoot')


def equivalent(left, right):
    return json.dumps(left, sort_keys=True, separators=(',', ':')) == json.dumps(right, sort_keys=True, separators=(',', ':'))


def selection(raw):
    require(0 < len(raw) <= 98304, 'SELECTION_BYTES_REJECTED')
    value = strict_json(raw, 'SELECTION_JSON_INVALID')
    exact_fields(value, ('schema', 'format', 'kind', 'run', 'operationRoot', 'items'), 'SELECTION_FIELDS_INVALID')
    require(type(value['schema']) is int and value['schema'] == 1 and value['format'] == FORMAT
            and value['kind'] in ('FILES', 'DATABASES'), 'SELECTION_VERSION_INVALID')
    require(isinstance(value['run'], str) and re.fullmatch(r'pre-reinstall-[a-z0-9-]{1,96}', value['run']) is not None,
            'SELECTION_RUN_INVALID')
    require(remote_path(value['operationRoot']) and re.fullmatch(r'/var/lib/windowstolinux/backups/' + re.escape(value['run']) + r'-[0-9a-f]{16}', value['operationRoot']) is not None,
            'SELECTION_OPERATION_ROOT_INVALID')
    items = value['items']
    require(isinstance(items, list) and 0 < len(items) <= (512 if value['kind'] == 'FILES' else 16), 'SELECTION_COUNT_REJECTED')
    paths = []
    total = 0
    for item in items:
        exact_fields(item, ('source', 'sourceMetadata'), 'SELECTION_ITEM_FIELDS_INVALID')
        require(remote_path(item['source']), 'SELECTION_SOURCE_PATH_INVALID')
        meta = item['sourceMetadata']
        exact_fields(meta, META_FIELDS, 'SELECTION_METADATA_FIELDS_INVALID')
        require(meta['kind'] == 'f' and all(type(meta[k]) is int and meta[k] >= 0 for k in ('bytes', 'device', 'inode', 'uid', 'gid'))
                and meta['inode'] > 0 and isinstance(meta['mode'], str) and re.fullmatch(r'[0-7]{3,4}', meta['mode']) is not None
                and isinstance(meta['mtime'], str) and re.fullmatch(r'[0-9]{1,12}\.[0-9]{1,10}', meta['mtime']) is not None,
                'SELECTION_METADATA_INVALID')
        require(meta['bytes'] <= (65536 if value['kind'] == 'FILES' else 4 * 1024 * 1024), 'SELECTION_FILE_TOO_LARGE')
        if value['kind'] == 'DATABASES':
            require(item['source'].startswith('/var/opt/windowstolinux/apps/') and '/databases/' in item['source']
                    and item['source'].endswith('.db'), 'SELECTION_DATABASE_PATH_INVALID')
        else:
            require(not item['source'].lower().endswith(('.db', '.sqlite', '.sqlite3', '-wal', '-shm', '-journal')), 'SELECTION_DATABASE_IN_FILES')
        paths.append(item['source'])
        total += meta['bytes']
    require(paths == sorted(set(paths)), 'SELECTION_PATHS_NOT_SORTED_UNIQUE')
    require(total <= (1024 * 1024 if value['kind'] == 'FILES' else 8 * 1024 * 1024), 'SELECTION_TOTAL_TOO_LARGE')
    return value


def producer_output(sdk):
    require(isinstance(sdk, dict) and sdk.get('outcome') == 'SUCCEEDED', 'PRODUCER_SDK_NOT_SUCCEEDED')
    command = sdk.get('commandResult')
    exact_fields(command, ('exitObserved', 'exitCode', 'stdout', 'stderr', 'truncated'), 'PRODUCER_COMMAND_FIELDS_INVALID')
    require(command['exitObserved'] is True and type(command['exitCode']) is int and command['exitCode'] == 0
            and command['stderr'] == '' and command['truncated'] is False, 'PRODUCER_COMMAND_INCOMPLETE')
    require(isinstance(command['stdout'], str) and len(command['stdout'].encode('utf-8')) <= MAX_RECEIPT
            and len(command['stdout'].splitlines()) == 1, 'PRODUCER_STDOUT_NOT_ONE_JSON_LINE')
    return strict_json(command['stdout'].encode('utf-8'), 'PRODUCER_JSON_INVALID')


def validate_bundle(clear, expected_raw, receipt):
    expected = selection(expected_raw)
    exact_fields(receipt, (*BASE_FIELDS, 'bundlePath', 'bundleBytes', 'bundleSha256', 'manifestSha256', 'manifest', 'complete'), 'PRODUCER_FIELDS_INVALID')
    base = {key: expected[key] for key in ('schema', 'format', 'kind', 'run', 'operationRoot')}
    base['selectionSha256'] = hashlib.sha256(expected_raw).hexdigest()
    require(equivalent({key: receipt[key] for key in BASE_FIELDS}, base) and receipt['complete'] is True, 'PRODUCER_SELECTION_MISMATCH')
    require(receipt['bundlePath'] == expected['operationRoot'] + '/bundle.tar', 'PRODUCER_BUNDLE_PATH_MISMATCH')
    require(type(receipt['bundleBytes']) is int and 0 < receipt['bundleBytes'] == len(clear) <= MAX_BUNDLE
            and digest_value(receipt['bundleSha256']) and receipt['bundleSha256'] == hashlib.sha256(clear).hexdigest(), 'BUNDLE_LENGTH_OR_SHA_MISMATCH')
    require(digest_value(receipt['manifestSha256']), 'MANIFEST_SHA_INVALID')
    manifest = receipt['manifest']
    exact_fields(manifest, (*BASE_FIELDS, 'items'), 'MANIFEST_FIELDS_INVALID')
    require(equivalent({key: manifest[key] for key in BASE_FIELDS}, base), 'MANIFEST_SELECTION_MISMATCH')
    sources = manifest['items']
    require(isinstance(sources, list) and len(sources) == len(expected['items']), 'MANIFEST_ITEM_COUNT_MISMATCH')
    is_db = expected['kind'] == 'DATABASES'
    total = 0
    for index, (actual, frozen) in enumerate(zip(sources, expected['items'])):
        exact_fields(actual, ('source', 'member', 'type', 'sourceMetadata', 'bytes', 'sha256', 'consistency'), 'MANIFEST_ITEM_FIELDS_INVALID')
        require(equivalent({key: actual[key] for key in frozen}, frozen), 'MANIFEST_SOURCE_METADATA_MISMATCH')
        require(actual['member'] == (f'databases/{index:04}.sqlite' if is_db else f'files/{index:04}.bin')
                and actual['type'] == ('SQLITE_ONLINE' if is_db else 'FILE')
                and actual['consistency'] == ('SQLITE_ONLINE_BACKUP_INDEPENDENT_POINT' if is_db else 'FD_STABLE_READ'), 'MANIFEST_MEMBER_OR_CONSISTENCY_INVALID')
        require(type(actual['bytes']) is int and (1 if is_db else 0) <= actual['bytes'] <= (4 * 1024 * 1024 if is_db else 65536)
                and digest_value(actual['sha256']), 'MANIFEST_SIZE_OR_SHA_INVALID')
        require(is_db or actual['bytes'] == frozen['sourceMetadata']['bytes'], 'MANIFEST_FILE_SIZE_CHANGED')
        total += actual['bytes']
    require(total <= (8 * 1024 * 1024 if is_db else 1024 * 1024), 'MANIFEST_TOTAL_TOO_LARGE')
    expected_members = {'manifest.json'} | {item['member'] for item in sources}
    stream = io.BytesIO(clear)
    verified = []
    try:
        with tarfile.open(fileobj=stream, mode='r:') as archive:
            members = []
            end = 0
            for member in archive:
                require(len(members) < len(expected_members) and member.name in expected_members
                        and member.name not in {m.name for m in members}, 'TAR_EXTRA_OR_DUPLICATE_MEMBER')
                require(member.type == tarfile.REGTYPE and member.isreg() and not member.linkname and not member.pax_headers
                        and member.sparse is None and not archive.pax_headers, 'TAR_NONREGULAR_OR_PAX_REJECTED')
                require(member.uid == member.gid == member.mtime == 0 and member.mode == 0o600 and not member.uname and not member.gname,
                        'TAR_OWNER_METADATA_INVALID')
                require(member.offset == end and member.offset_data == member.offset + 512
                        and clear[member.offset + 257:member.offset + 265] == b'ustar\x0000', 'TAR_NOT_EXACT_USTAR')
                maximum = MAX_MANIFEST if member.name == 'manifest.json' else (4 * 1024 * 1024 if is_db else 65536)
                require(0 <= member.size <= maximum and (member.name != 'manifest.json' or member.size > 0), 'TAR_MEMBER_SIZE_REJECTED')
                end = member.offset_data + ((member.size + 511) // 512) * 512
                require(not any(memoryview(clear)[member.offset_data + member.size:end]), 'TAR_PADDING_INVALID')
                members.append(member)
            require({m.name for m in members} == expected_members and len(clear) % 512 == 0 and len(clear) >= end + 1024
                    and not any(memoryview(clear)[end:]), 'TAR_MISSING_OR_TRAILING_DATA')
            body = None
            try:
                body = read_tar_member(archive, archive.getmember('manifest.json'), 'CURRENT')
                require(hashlib.sha256(body).hexdigest() == receipt['manifestSha256']
                        and equivalent(strict_json(body, 'TAR_MANIFEST_JSON_INVALID'), manifest), 'TAR_MANIFEST_MISMATCH')
            finally:
                wipe(body)
            for item in sources:
                body = None
                try:
                    member = archive.getmember(item['member'])
                    require(member.size == item['bytes'], 'TAR_FILE_LENGTH_MISMATCH')
                    body = read_tar_member(archive, member, 'CURRENT')
                    require(hashlib.sha256(body).hexdigest() == item['sha256'], 'TAR_FILE_SHA_MISMATCH')
                    result = {'source': item['source'], 'member': item['member'], 'bytes': len(body), 'sha256': item['sha256']}
                    if is_db:
                        result['sqlite'] = sqlite_metadata(body)
                    else:
                        require(not body.startswith(b'SQLite format 3\x00'), 'SQLITE_PAYLOAD_IN_FILES')
                    verified.append(result)
                finally:
                    wipe(body)
    except (tarfile.TarError, EOFError):
        raise GuardError('CURRENT_TAR_INVALID') from None
    finally:
        view = stream.getbuffer()
        if view:
            ctypes.memset(ctypes.addressof((ctypes.c_ubyte * len(view)).from_buffer(view)), 0, len(view))
        view.release()
        stream.close()
    return {'status': 'PASS', 'format': FORMAT, 'kind': expected['kind'], 'selectionSha256': base['selectionSha256'],
            'manifestSha256': receipt['manifestSha256'], 'itemCount': len(verified), 'itemBytes': total,
            'items': verified, 'databasesAreNotOneSimultaneousSnapshot': is_db,
            'plaintextExtractedToDisk': False, 'payloadLogged': False}


def read_metadata(api, path, digest, maximum):
    require(digest_value(digest), 'METADATA_SHA_REQUIRED')
    raw, _ = api.read_cipher(path, maximum=maximum)
    require(hashlib.sha256(raw).hexdigest() == digest, 'METADATA_SHA_MISMATCH')
    return raw


def verify_request(request, api):
    exact_fields(request, ('schema', 'cipher', 'cipherSha256', 'plaintextSha256', 'plaintextBytes', 'expectedSelectionFile',
                           'expectedSelectionSha256', 'producerReceiptFile', 'producerReceiptSha256'), 'REQUEST_FIELDS_INVALID')
    require(type(request['schema']) is int and request['schema'] == 1 and digest_value(request['cipherSha256'])
            and digest_value(request['plaintextSha256']) and type(request['plaintextBytes']) is int
            and 0 < request['plaintextBytes'] <= MAX_BUNDLE, 'REQUEST_METADATA_INVALID')
    encrypted = clear = expected = sdk_raw = None
    try:
        expected = read_metadata(api, request['expectedSelectionFile'], request['expectedSelectionSha256'], 98304)
        sdk_raw = read_metadata(api, request['producerReceiptFile'], request['producerReceiptSha256'], MAX_RECEIPT)
        receipt = producer_output(strict_json(sdk_raw, 'SDK_RECEIPT_JSON_INVALID'))
        encrypted, _ = api.read_cipher(request['cipher'])
        require(hashlib.sha256(encrypted).hexdigest() == request['cipherSha256'], 'CIPHER_SHA_MISMATCH')
        clear = api.crypt(encrypted)
        require(len(clear) == request['plaintextBytes'] and hashlib.sha256(clear).hexdigest() == request['plaintextSha256'], 'PLAINTEXT_LENGTH_OR_SHA_MISMATCH')
        result = validate_bundle(clear, expected, receipt)
        result.update({'cipherSha256': request['cipherSha256'], 'cipherBytes': len(encrypted), 'plaintextSha256': request['plaintextSha256'],
                       'plaintextBytes': len(clear), 'producerReceiptSha256': request['producerReceiptSha256'],
                       'currentUserDpapi': True, 'normalNonAdministrator': True, 'plaintextFileCreated': False,
                       'ownedBuffersWipeAttempted': True, 'allInternalCopiesSecurelyErased': False})
        return result
    finally:
        for data in (clear, encrypted, expected, sdk_raw):
            wipe(data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--request-json', required=True)
    args = parser.parse_args()
    api = None
    try:
        api = WindowsApi()
        raw, _ = api.read_cipher(args.request_json, maximum=32768)
        try:
            request = strict_json(raw, 'REQUEST_JSON_INVALID')
        finally:
            wipe(raw)
        result = verify_request(request, api)
    except GuardError as error:
        result = {'status': 'REJECTED', 'code': str(error)}
    except NativeError as error:
        result = {'status': 'UNKNOWN', 'code': error.code, 'winerror': error.winerror}
    except sqlite3.Error as error:
        result = {'status': 'UNKNOWN', 'code': 'SQLITE_INVALID_OR_UNSUPPORTED', 'sqliteErrorName': getattr(error, 'sqlite_errorname', 'UNKNOWN')}
    except Exception as error:
        result = {'status': 'UNKNOWN', 'code': 'VALIDATION_ERROR', 'exceptionClass': type(error).__name__}
    result.update({'nativeUnprotectCalls': api.unprotect_calls if api else 0, 'plaintextFileCreated': False, 'payloadLogged': False})
    print(json.dumps(result, ensure_ascii=True, separators=(',', ':')))
    return 0 if result['status'] == 'PASS' else 2


if __name__ == '__main__':
    raise SystemExit(main())
