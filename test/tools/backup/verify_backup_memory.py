"""Windows current-user DPAPI + memory-only SQLite verifier candidate; no payload logging."""
import argparse
import ctypes
from ctypes import wintypes as W
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import sqlite3
import tarfile
import time

MAX_PLAIN = 64 * 1024 * 1024
MAX_CIPHER = MAX_PLAIN + 64 * 1024
UI_FORBIDDEN = 1
MAX_RECEIPT = 1024 * 1024
MAX_MANIFEST = 64 * 1024
SNAPSHOT_TYPE = 'SQLITE_SNAPSHOT_BUNDLE'
CONTEXT_TYPE = 'CONFIGURATION_AND_OPERATION_RECEIPTS_BUNDLE'
SNAPSHOT_MEMBERS = {'manifest.json'} | {f'databases/{index:02}.sqlite' for index in range(12)}

class GuardError(Exception):
    pass

class NativeError(Exception):
    def __init__(self, code, winerror):
        self.code, self.winerror = code, winerror

class Blob(ctypes.Structure):
    _fields_ = [('cbData', W.DWORD), ('pbData', ctypes.POINTER(ctypes.c_ubyte))]

class FileId(ctypes.Structure):
    _fields_ = [('volume', ctypes.c_ulonglong), ('id', ctypes.c_ubyte * 16)]

class AttributeTag(ctypes.Structure):
    _fields_ = [('attributes', W.DWORD), ('tag', W.DWORD)]

def require(valid, code):
    if not valid:
        raise GuardError(code)

def wipe(data):
    if data:
        ctypes.memset(ctypes.addressof((ctypes.c_ubyte * len(data)).from_buffer(data)), 0, len(data))

class WindowsApi:
    def __init__(self):
        require(os.name == 'nt', 'WINDOWS_REQUIRED')
        shell = ctypes.WinDLL('shell32', use_last_error=True)
        shell.IsUserAnAdmin.restype = W.BOOL
        require(not shell.IsUserAnAdmin(), 'NORMAL_NONADMIN_USER_REQUIRED')
        self.k = ctypes.WinDLL('kernel32', use_last_error=True)
        self.c = ctypes.WinDLL('crypt32', use_last_error=True)
        self.k.GetFileAttributesW.argtypes = [W.LPCWSTR]
        self.k.GetFileAttributesW.restype = W.DWORD
        self.k.CreateFileW.argtypes = [W.LPCWSTR, W.DWORD, W.DWORD, ctypes.c_void_p, W.DWORD, W.DWORD, W.HANDLE]
        self.k.CreateFileW.restype = W.HANDLE
        self.k.CloseHandle.argtypes = [W.HANDLE]
        self.k.CloseHandle.restype = W.BOOL
        self.k.GetFileSizeEx.argtypes = [W.HANDLE, ctypes.POINTER(ctypes.c_longlong)]
        self.k.GetFileSizeEx.restype = W.BOOL
        self.k.GetFileInformationByHandleEx.argtypes = [W.HANDLE, ctypes.c_int, ctypes.c_void_p, W.DWORD]
        self.k.GetFileInformationByHandleEx.restype = W.BOOL
        self.k.ReadFile.argtypes = [W.HANDLE, ctypes.c_void_p, W.DWORD, ctypes.POINTER(W.DWORD), ctypes.c_void_p]
        self.k.ReadFile.restype = W.BOOL
        self.k.LocalFree.argtypes = [ctypes.c_void_p]
        self.k.LocalFree.restype = ctypes.c_void_p
        signature = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, W.DWORD, ctypes.POINTER(Blob)]
        self.c.CryptUnprotectData.argtypes = signature
        self.c.CryptUnprotectData.restype = W.BOOL
        self.c.CryptProtectData.argtypes = signature
        self.c.CryptProtectData.restype = W.BOOL
        self.unprotect_calls = 0
        self.protect_calls = 0

    def checked(self, value, code):
        if not value:
            raise NativeError(code, ctypes.get_last_error())

    def canonical(self, value):
        path = Path(value)
        require(path.is_absolute() and not str(path).startswith('\\\\'), 'ABSOLUTE_LOCAL_PATH_REQUIRED')
        require(path.resolve(strict=True) == path, 'CANONICAL_PATH_REQUIRED')
        for item in [path, *path.parents]:
            flags = self.k.GetFileAttributesW(str(item))
            require(flags != 0xFFFFFFFF and not flags & 0x400, 'REPARSE_OR_MISSING_PATH_REJECTED')
        return path

    def identity(self, handle):
        info = FileId()
        tag = AttributeTag()
        self.checked(self.k.GetFileInformationByHandleEx(handle, 18, ctypes.byref(info), ctypes.sizeof(info)), 'FILE_ID_FAILED')
        self.checked(self.k.GetFileInformationByHandleEx(handle, 9, ctypes.byref(tag), ctypes.sizeof(tag)), 'FILE_ATTRIBUTE_FAILED')
        require(not tag.attributes & (0x400 | 0x10), 'REPARSE_OR_DIRECTORY_REJECTED')
        require(any(info.id), 'NONEMPTY_NATIVE_FILE_ID_REQUIRED')
        size = ctypes.c_longlong()
        self.checked(self.k.GetFileSizeEx(handle, ctypes.byref(size)), 'FILE_SIZE_FAILED')
        return info.volume, bytes(info.id).hex(), size.value

    def read_cipher(self, value, maximum=MAX_CIPHER):
        path = self.canonical(value)
        handle = self.k.CreateFileW(str(path), 0x80000000, 1, None, 3, 0x00200000, None)
        require(handle not in (None, ctypes.c_void_p(-1).value), 'READ_HANDLE_REJECTED')
        data = None
        try:
            before = self.identity(handle)
            require(0 < before[2] <= maximum, 'CIPHER_SIZE_OUT_OF_BOUNDS')
            data = bytearray(before[2])
            array = (ctypes.c_ubyte * len(data)).from_buffer(data)
            offset = 0
            while offset < len(data):
                count = W.DWORD()
                self.checked(self.k.ReadFile(handle, ctypes.byref(array, offset), min(65536, len(data) - offset), ctypes.byref(count), None), 'CIPHER_READ_FAILED')
                require(count.value > 0, 'CIPHER_TRUNCATED_DURING_READ')
                offset += count.value
            require(self.identity(handle) == before and self.canonical(path) == path, 'CIPHER_IDENTITY_CHANGED')
            return data, before
        except BaseException:
            wipe(data)
            raise
        finally:
            self.checked(self.k.CloseHandle(handle), 'CIPHER_HANDLE_CLOSE_FAILED')

    def crypt(self, data, protect=False):
        require(isinstance(data, bytearray), 'OWNED_BYTEARRAY_REQUIRED')
        array = (ctypes.c_ubyte * len(data)).from_buffer(data)
        source = Blob(len(data), ctypes.cast(array, ctypes.POINTER(ctypes.c_ubyte)))
        output = Blob()
        result = None
        try:
            fn = self.c.CryptProtectData if protect else self.c.CryptUnprotectData
            if protect:
                self.protect_calls += 1
            else:
                self.unprotect_calls += 1
            # Same CurrentUserProtection contract: current user, entropy=None, UI forbidden.
            self.checked(fn(ctypes.byref(source), None, None, None, None, UI_FORBIDDEN, ctypes.byref(output)), 'DPAPI_PROTECT_FAILED' if protect else 'DPAPI_UNPROTECT_FAILED')
            limit = MAX_CIPHER if protect else MAX_PLAIN
            require(output.cbData <= limit and bool(output.pbData), 'NATIVE_OUTPUT_SIZE_REJECTED')
            result = bytearray(output.cbData)
            if result:
                ctypes.memmove((ctypes.c_ubyte * len(result)).from_buffer(result), output.pbData, len(result))
            return result
        except BaseException:
            wipe(result)
            raise
        finally:
            if output.pbData:
                if output.cbData:
                    ctypes.memset(output.pbData, 0, output.cbData)
                require(not self.k.LocalFree(ctypes.cast(output.pbData, ctypes.c_void_p)), 'NATIVE_LOCALFREE_FAILED')

def sqlite_metadata(clear):
    require(clear[:16] == b'SQLite format 3\x00', 'NOT_SQLITE_HEADER')
    require(hasattr(sqlite3.Connection, 'deserialize'), 'SQLITE_DESERIALIZE_UNAVAILABLE')
    connection = sqlite3.connect(':memory:', isolation_level=None)
    start = time.monotonic()
    ticks = 0
    def progress():
        nonlocal ticks
        ticks += 1000
        return ticks > 50_000_000 or time.monotonic() - start > 30
    try:
        connection.enable_load_extension(False)
        connection.execute('PRAGMA temp_store=MEMORY')
        connection.execute('PRAGMA trusted_schema=OFF')
        connection.deserialize(clear)
        connection.execute('PRAGMA query_only=ON')
        require(connection.execute('PRAGMA query_only').fetchone() == (1,), 'SQLITE_QUERY_ONLY_REQUIRED')
        def authorize(action, first, second, db, trigger):
            if action in (sqlite3.SQLITE_ATTACH, sqlite3.SQLITE_DETACH):
                return sqlite3.SQLITE_DENY
            if action == sqlite3.SQLITE_FUNCTION and str(second).lower() == 'load_extension':
                return sqlite3.SQLITE_DENY
            return sqlite3.SQLITE_OK
        connection.set_authorizer(authorize)
        connection.set_progress_handler(progress, 1000)
        integrity = connection.execute('PRAGMA integrity_check(1)').fetchmany(2)
        require(integrity == [('ok',)], 'SQLITE_INTEGRITY_NOT_OK')
        violations = connection.execute('PRAGMA foreign_key_check').fetchmany(1001)
        require(len(violations) <= 1000, 'SQLITE_FK_CHECK_BUDGET_EXCEEDED')
        require(not violations, 'SQLITE_FOREIGN_KEY_VIOLATIONS')
        objects = connection.execute("SELECT type,name FROM sqlite_schema WHERE type IN ('table','view','index','trigger')").fetchmany(1025)
        require(len(objects) <= 1024, 'SQLITE_SCHEMA_OBJECT_BUDGET_EXCEEDED')
        counts = {kind: 0 for kind in ('table', 'view', 'index', 'trigger')}
        columns = 0
        for kind, name in objects:
            require(isinstance(name, str) and len(name) <= 1024, 'SQLITE_IDENTIFIER_BUDGET_EXCEEDED')
            counts[kind] += 1
            if kind == 'table':
                columns += connection.execute('SELECT COUNT(*) FROM pragma_table_xinfo(?)', (name,)).fetchone()[0]
                require(columns <= 8192, 'SQLITE_COLUMN_BUDGET_EXCEEDED')
        return {'integrityCheck': 'ok', 'foreignKeyViolationCount': 0, 'schemaObjectCounts': counts,
                'tableColumnCount': columns, 'queryOnly': True, 'extensionsDisabled': True,
                'memoryDeserialize': True, 'businessRowsOutput': False, 'tableOrColumnNamesOutput': False}
    finally:
        connection.close()

def strict_json(data, code):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, code)
            result[key] = value
        return result
    def constant(_):
        raise GuardError(code)
    try:
        return json.loads(data.decode('utf-8', errors='strict'), object_pairs_hook=pairs, parse_constant=constant)
    except (ValueError, UnicodeError):
        raise GuardError(code) from None

def exact_fields(value, fields, code):
    require(isinstance(value, dict) and set(value) == set(fields), code)

def digest_value(value):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) is not None

def remote_path(value):
    return (isinstance(value, str) and 0 < len(value) <= 4096 and value.startswith('/') and not value.startswith('//')
            and value == str(PurePosixPath(value)) and '..' not in PurePosixPath(value).parts
            and not any(ord(char) < 32 or ord(char) == 127 for char in value))

def verified_producer_receipt(receipt_file, expected_receipt_sha, api, prefix):
    require(digest_value(expected_receipt_sha), prefix + '_RECEIPT_SHA256_REQUIRED')
    raw = None
    try:
        raw, _ = api.read_cipher(receipt_file, maximum=MAX_RECEIPT)
        require(hashlib.sha256(raw).hexdigest() == expected_receipt_sha, prefix + '_RECEIPT_SHA256_MISMATCH')
        sdk = strict_json(raw, prefix + '_RECEIPT_JSON_INVALID')
    finally:
        wipe(raw)
    require(isinstance(sdk, dict) and sdk.get('outcome') == 'SUCCEEDED', prefix + '_SDK_OUTCOME_NOT_SUCCEEDED')
    command = sdk.get('commandResult')
    exact_fields(command, ('exitObserved', 'exitCode', 'stdout', 'stderr', 'truncated'), prefix + '_COMMAND_RESULT_FIELDS_INVALID')
    require(command['exitObserved'] is True and type(command['exitCode']) is int and command['exitCode'] == 0
            and command['truncated'] is False and command['stderr'] == '', prefix + '_COMMAND_NOT_COMPLETE')
    require(isinstance(command['stdout'], str) and len(command['stdout'].encode('utf-8')) <= MAX_RECEIPT
            and len(command['stdout'].splitlines()) == 1, prefix + '_STDOUT_NOT_ONE_JSON_LINE')
    receipt = strict_json(command['stdout'].encode('utf-8'), prefix + '_PRODUCER_JSON_INVALID')
    require(isinstance(receipt, dict), prefix + '_PRODUCER_FIELDS_INVALID')
    if 'details' in receipt:
        require(isinstance(receipt['details'], dict), prefix + '_DETAILS_METADATA_INVALID')
        receipt = {key: value for key, value in receipt.items() if key != 'details'}
    return receipt

def receipt_bundle(clear, receipt_file, expected_receipt_sha, api):
    receipt = verified_producer_receipt(receipt_file, expected_receipt_sha, api, 'SNAPSHOT')
    exact_fields(receipt, ('schema', 'type', 'complete', 'run', 'server', 'fingerprint', 'layoutSha256',
                          'operationRoot', 'ownerMarkerSha256', 'bundlePath', 'bundleBytes', 'bundleSha256',
                          'manifestMember', 'manifestSha256', 'sources', 'limits'), 'SNAPSHOT_PRODUCER_FIELDS_INVALID')
    require(type(receipt['schema']) is int and receipt['schema'] == 1 and receipt['type'] == SNAPSHOT_TYPE
            and receipt['complete'] is True and receipt['manifestMember'] == 'manifest.json', 'SNAPSHOT_PRODUCER_NOT_COMPLETE')
    for key in ('run', 'server'):
        require(isinstance(receipt[key], str) and re.fullmatch('[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}', receipt[key]) is not None, 'SNAPSHOT_IDENTITY_INVALID')
    require(isinstance(receipt['fingerprint'], str) and re.fullmatch(r'SHA256:[A-Za-z0-9+/]{43}', receipt['fingerprint']) is not None
            and all(digest_value(receipt[key]) for key in ('layoutSha256', 'ownerMarkerSha256', 'bundleSha256', 'manifestSha256')), 'SNAPSHOT_IDENTITY_INVALID')
    require(remote_path(receipt['operationRoot']) and remote_path(receipt['bundlePath'])
            and receipt['bundlePath'].startswith(receipt['operationRoot'] + '/'), 'SNAPSHOT_OPERATION_PATH_INVALID')
    limits = receipt['limits']
    validate_snapshot_limits(limits)
    require(type(receipt['bundleBytes']) is int and receipt['bundleBytes'] == len(clear) <= 16 * 1024 * 1024
            and receipt['bundleSha256'] == hashlib.sha256(clear).hexdigest(), 'SNAPSHOT_OUTER_BUNDLE_MISMATCH')
    sources = receipt['sources']
    require(isinstance(sources, list) and len(sources) == 12, 'SNAPSHOT_SOURCE_COUNT_INVALID')
    by_member = {}
    source_paths = set()
    for source in sources:
        exact_fields(source, ('sourcePath', 'sourceDevice', 'sourceInode', 'sourceNlink', 'sourceBytes', 'member',
                              'snapshotBytes', 'snapshotSha256', 'method', 'integrity'), 'SNAPSHOT_SOURCE_FIELDS_INVALID')
        validate_snapshot_source_numbers(source)
        require(remote_path(source['sourcePath']) and source['sourcePath'] not in source_paths, 'SNAPSHOT_SOURCE_PATH_INVALID_OR_DUPLICATE')
        require(isinstance(source['member'], str) and source['member'] in SNAPSHOT_MEMBERS - {'manifest.json'}
                and source['member'] not in by_member, 'SNAPSHOT_SOURCE_MEMBER_INVALID_OR_DUPLICATE')
        require(type(source['snapshotBytes']) is int and 0 < source['snapshotBytes'] <= 1024 * 1024
                and digest_value(source['snapshotSha256']) and source['method'] == 'PYTHON_SQLITE3_ONLINE_BACKUP'
                and source['integrity'] == 'ok', 'SNAPSHOT_SOURCE_NOT_VALIDATED')
        by_member[source['member']] = source
        source_paths.add(source['sourcePath'])
    stream = io.BytesIO(clear)
    verified = []
    try:
        with tarfile.open(fileobj=stream, mode='r:') as archive:
            regular_ustar_members(archive, clear, SNAPSHOT_MEMBERS, MAX_MANIFEST, 1024 * 1024, False, 'SNAPSHOT')
            manifest_bytes = None
            try:
                manifest = archive.getmember('manifest.json')
                manifest_bytes = read_tar_member(archive, manifest)
                require(hashlib.sha256(manifest_bytes).hexdigest() == receipt['manifestSha256'], 'SNAPSHOT_MANIFEST_SHA256_MISMATCH')
                manifest_value = strict_json(manifest_bytes, 'SNAPSHOT_MANIFEST_JSON_INVALID')
                exact_fields(manifest_value, ('schema', 'type', 'run', 'server', 'fingerprint', 'layoutSha256', 'sources'), 'SNAPSHOT_MANIFEST_FIELDS_INVALID')
                expected_manifest = {key: receipt[key] for key in manifest_value}
                require(json.dumps(manifest_value, sort_keys=True, separators=(',', ':'))
                        == json.dumps(expected_manifest, sort_keys=True, separators=(',', ':')), 'SNAPSHOT_MANIFEST_RECEIPT_MISMATCH')
            finally:
                wipe(manifest_bytes)
            for source in sources:
                content = None
                try:
                    member = archive.getmember(source['member'])
                    require(member.size == source['snapshotBytes'], 'SNAPSHOT_DATABASE_LENGTH_MISMATCH')
                    content = read_tar_member(archive, member)
                    require(hashlib.sha256(content).hexdigest() == source['snapshotSha256'], 'SNAPSHOT_DATABASE_SHA256_MISMATCH')
                    metadata = sqlite_metadata(content)
                    verified.append({'sourcePath': source['sourcePath'], 'member': source['member'],
                                     'snapshotBytes': len(content), 'snapshotSha256': source['snapshotSha256'], 'sqlite': metadata})
                finally:
                    wipe(content)
    except (tarfile.TarError, EOFError):
        raise GuardError('SNAPSHOT_TAR_INVALID') from None
    finally:
        view = stream.getbuffer()
        if view:
            ctypes.memset(ctypes.addressof((ctypes.c_ubyte * len(view)).from_buffer(view)), 0, len(view))
        view.release()
        stream.close()
    require(len(verified) == 12, 'SNAPSHOT_DATABASE_VALIDATION_INCOMPLETE')
    return {'status': 'PASS', 'receiptSha256': expected_receipt_sha, 'regularUstarMembers': 13,
            'manifestSha256': receipt['manifestSha256'], 'databasesIndependentlyVerified': 12,
            'databasesAreNotOneSimultaneousSnapshot': True, 'databases': verified,
            'plaintextExtractedToDisk': False, 'businessRowsOutput': False}

def regular_ustar_members(archive, clear, expected_members, manifest_limit, file_limit, allow_empty_files, prefix):
    members = []
    for item in archive:
        require(len(members) < len(expected_members), prefix + '_TAR_MEMBER_COUNT_OR_PAX_INVALID')
        members.append(item)
    require(len(members) == len(expected_members) and not archive.pax_headers, prefix + '_TAR_MEMBER_COUNT_OR_PAX_INVALID')
    seen = set()
    end = 0
    for member in members:
        require(member.name in expected_members and member.name not in seen, prefix + '_TAR_NAME_INVALID_OR_DUPLICATE')
        require(member.type == tarfile.REGTYPE and member.isreg() and not member.linkname
                and not member.pax_headers and member.sparse is None, prefix + '_TAR_NONREGULAR_OR_EXTRA_REJECTED')
        require(member.uid == member.gid == member.mtime == 0 and member.mode == 0o400
                and not member.uname and not member.gname, prefix + '_TAR_OWNER_METADATA_INVALID')
        require(member.offset == end and member.offset_data == member.offset + 512
                and clear[member.offset + 257:member.offset + 265] == b'ustar\x0000', prefix + '_TAR_NOT_EXACT_USTAR')
        maximum = manifest_limit if member.name == 'manifest.json' else file_limit
        minimum = 0 if allow_empty_files and member.name != 'manifest.json' else 1
        require(minimum <= member.size <= maximum, prefix + '_TAR_MEMBER_SIZE_REJECTED')
        end = member.offset_data + ((member.size + 511) // 512) * 512
        require(not any(memoryview(clear)[member.offset_data + member.size:end]), prefix + '_TAR_PADDING_INVALID')
        seen.add(member.name)
    require(seen == expected_members and len(clear) % 512 == 0 and len(clear) >= end + 1024
            and not any(memoryview(clear)[end:]), prefix + '_TAR_TRAILING_DATA_OR_MEMBERS_INVALID')
    return members

def read_tar_member(archive, member, prefix='SNAPSHOT'):
    result = bytearray(member.size)
    try:
        with archive.extractfile(member) as source:
            require(source.readinto(result) == len(result) and source.read(1) == b'', prefix + '_TAR_MEMBER_TRUNCATED')
        return result
    except BaseException:
        wipe(result)
        raise

def validate_snapshot_limits(limits):
    exact_fields(limits, ('snapshotMaxBytes', 'bundleMaxBytes', 'peakMaxBytes', 'minimumAvailableBytes',
                          'availableBeforeBytes'), 'SNAPSHOT_LIMIT_FIELDS_INVALID')
    fixed = {'snapshotMaxBytes': 1048576, 'bundleMaxBytes': 16777216,
             'peakMaxBytes': 33554432, 'minimumAvailableBytes': 134217728}
    require(all(type(limits[key]) is int and limits[key] == value for key, value in fixed.items()), 'SNAPSHOT_FIXED_LIMITS_MISMATCH')
    require(type(limits['availableBeforeBytes']) is int and limits['availableBeforeBytes'] >= 134217728,
            'SNAPSHOT_AVAILABLE_BYTES_BELOW_MINIMUM')

def validate_snapshot_source_numbers(source):
    require(all(isinstance(source[key], str) and re.fullmatch(r'(0|[1-9][0-9]{0,19})', source[key]) is not None
                for key in ('sourceDevice', 'sourceInode')), 'SNAPSHOT_SOURCE_NATIVE_ID_INVALID')
    require(type(source['sourceNlink']) is int and source['sourceNlink'] == 1
            and type(source['sourceBytes']) is int and 0 < source['sourceBytes'] <= 1048576,
            'SNAPSHOT_SOURCE_SIZE_OR_LINK_COUNT_INVALID')

def context_metadata_rows(path, expected_sha, api):
    require(digest_value(expected_sha), 'CONTEXT_METADATA_SHA256_REQUIRED')
    raw = None
    try:
        raw, _ = api.read_cipher(path, maximum=MAX_RECEIPT)
        require(hashlib.sha256(raw).hexdigest() == expected_sha, 'CONTEXT_METADATA_SHA256_MISMATCH')
        rows = strict_json(raw, 'CONTEXT_METADATA_JSON_INVALID')
    finally:
        wipe(raw)
    require(isinstance(rows, list) and 0 < len(rows) <= 512, 'CONTEXT_METADATA_COUNT_REJECTED')
    paths = []
    total = 0
    for row in rows:
        exact_fields(row, ('path', 'bytes', 'uid', 'gid', 'mode', 'device', 'inode'), 'CONTEXT_METADATA_FIELDS_INVALID')
        require(remote_path(row['path']), 'CONTEXT_SOURCE_PATH_INVALID')
        require(not row['path'].lower().endswith(('.db', '.sqlite', '-wal', '-shm', '-journal', '.wal', '.shm', '.journal')),
                'CONTEXT_DATABASE_SOURCE_PATH_FORBIDDEN')
        require(type(row['bytes']) is int and 0 <= row['bytes'] <= 65536
                and type(row['uid']) is int and row['uid'] == 0
                and type(row['gid']) is int and 0 <= row['gid'] <= 0xffffffff
                and type(row['mode']) is int and row['mode'] in (0o400, 0o600, 0o444), 'CONTEXT_ROOT_FILE_METADATA_INVALID')
        require(all(isinstance(row[key], str) and re.fullmatch(r'(0|[1-9][0-9]{0,19})', row[key]) is not None
                    for key in ('device', 'inode')), 'CONTEXT_NATIVE_ID_METADATA_INVALID')
        paths.append(row['path'])
        total += row['bytes']
    require(paths == sorted(paths) and len(paths) == len(set(paths)), 'CONTEXT_METADATA_NOT_SORTED_UNIQUE')
    require(total <= 131072, 'CONTEXT_SOURCE_BYTES_LIMIT_EXCEEDED')
    return rows, total

def receipt_context(clear, receipt_path, receipt_sha, metadata_path, metadata_sha, api):
    rows, total = context_metadata_rows(metadata_path, metadata_sha, api)
    receipt = verified_producer_receipt(receipt_path, receipt_sha, api, 'CONTEXT')
    exact_fields(receipt, ('schema', 'type', 'run', 'server', 'fingerprint', 'layoutSha256', 'operationRoot',
                          'ownerMarkerSha256', 'bundlePath', 'bundleBytes', 'bundleSha256', 'manifestSha256',
                          'manifestMember', 'sources', 'sourceCount', 'sourceBytes', 'complete'), 'CONTEXT_PRODUCER_FIELDS_INVALID')
    require(type(receipt['schema']) is int and receipt['schema'] == 1 and receipt['type'] == CONTEXT_TYPE
            and receipt['complete'] is True and receipt['manifestMember'] == 'manifest.json', 'CONTEXT_PRODUCER_NOT_COMPLETE')
    require(all(isinstance(receipt[key], str) and re.fullmatch('[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}', receipt[key]) is not None
                for key in ('run', 'server')), 'CONTEXT_IDENTITY_INVALID')
    require(isinstance(receipt['fingerprint'], str) and re.fullmatch(r'SHA256:[A-Za-z0-9+/]{43}', receipt['fingerprint']) is not None
            and all(digest_value(receipt[key]) for key in ('layoutSha256', 'ownerMarkerSha256', 'bundleSha256', 'manifestSha256')),
            'CONTEXT_IDENTITY_INVALID')
    require(remote_path(receipt['operationRoot']) and remote_path(receipt['bundlePath'])
            and receipt['bundlePath'].startswith(receipt['operationRoot'] + '/'), 'CONTEXT_OPERATION_PATH_INVALID')
    require(type(receipt['bundleBytes']) is int and receipt['bundleBytes'] == len(clear) <= 2 * 1024 * 1024
            and receipt['bundleSha256'] == hashlib.sha256(clear).hexdigest(), 'CONTEXT_OUTER_BUNDLE_MISMATCH')
    require(type(receipt['sourceCount']) is int and receipt['sourceCount'] == len(rows)
            and type(receipt['sourceBytes']) is int and receipt['sourceBytes'] == total, 'CONTEXT_SOURCE_TOTAL_MISMATCH')
    sources = receipt['sources']
    require(isinstance(sources, list) and len(sources) == len(rows), 'CONTEXT_SOURCE_COUNT_MISMATCH')
    for index, (source, expected) in enumerate(zip(sources, rows)):
        exact_fields(source, (*expected.keys(), 'member', 'sha256'), 'CONTEXT_SOURCE_FIELDS_INVALID')
        actual = {key: source[key] for key in expected}
        require(json.dumps(actual, sort_keys=True, separators=(',', ':'))
                == json.dumps(expected, sort_keys=True, separators=(',', ':')), 'CONTEXT_SOURCE_METADATA_MISMATCH')
        require(source['member'] == f'files/{index:04}.bin' and digest_value(source['sha256']), 'CONTEXT_SOURCE_MEMBER_OR_SHA_INVALID')
    expected_members = {'manifest.json'} | {source['member'] for source in sources}
    stream = io.BytesIO(clear)
    verified = 0
    empty = 0
    try:
        with tarfile.open(fileobj=stream, mode='r:') as archive:
            regular_ustar_members(archive, clear, expected_members, 262144, 65536, True, 'CONTEXT')
            body = None
            try:
                body = read_tar_member(archive, archive.getmember('manifest.json'), 'CONTEXT')
                require(hashlib.sha256(body).hexdigest() == receipt['manifestSha256'], 'CONTEXT_MANIFEST_SHA256_MISMATCH')
                manifest = strict_json(body, 'CONTEXT_MANIFEST_JSON_INVALID')
                exact_fields(manifest, ('schema', 'type', 'run', 'server', 'fingerprint', 'layoutSha256', 'sources'), 'CONTEXT_MANIFEST_FIELDS_INVALID')
                expected_manifest = {key: receipt[key] for key in manifest}
                require(json.dumps(manifest, sort_keys=True, separators=(',', ':'))
                        == json.dumps(expected_manifest, sort_keys=True, separators=(',', ':')), 'CONTEXT_MANIFEST_RECEIPT_MISMATCH')
            finally:
                wipe(body)
            for source in sources:
                body = None
                try:
                    member = archive.getmember(source['member'])
                    require(member.size == source['bytes'], 'CONTEXT_FILE_LENGTH_MISMATCH')
                    body = read_tar_member(archive, member, 'CONTEXT')
                    require(hashlib.sha256(body).hexdigest() == source['sha256'], 'CONTEXT_FILE_SHA256_MISMATCH')
                    require(not body.startswith(b'SQLite format 3\x00'), 'CONTEXT_SQLITE_HEADER_FORBIDDEN')
                    empty += len(body) == 0
                    verified += 1
                finally:
                    wipe(body)
    except (tarfile.TarError, EOFError):
        raise GuardError('CONTEXT_TAR_INVALID') from None
    finally:
        view = stream.getbuffer()
        if view:
            ctypes.memset(ctypes.addressof((ctypes.c_ubyte * len(view)).from_buffer(view)), 0, len(view))
        view.release()
        stream.close()
    require(verified == len(rows), 'CONTEXT_FILE_VALIDATION_INCOMPLETE')
    return {'status': 'PASS', 'receiptSha256': receipt_sha, 'metadataSha256': metadata_sha,
            'manifestSha256': receipt['manifestSha256'], 'regularUstarMembers': verified + 1,
            'sourceCount': verified, 'sourceBytes': total, 'emptyFileCount': empty,
            'plaintextExtractedToDisk': False, 'configurationValuesOutput': False,
            'databaseFilesExcluded': True, 'frozenMetadataMappingVerified': True}

def verify(cipher, expected_cipher_sha, expected_plaintext_sha, expected_bytes, sqlite=False, api=None,
           snapshot_receipt=None, snapshot_receipt_sha=None, context_receipt=None, context_receipt_sha=None,
           context_metadata=None, context_metadata_sha=None):
    require(re.fullmatch('[0-9a-f]{64}', expected_cipher_sha) is not None and re.fullmatch('[0-9a-f]{64}', expected_plaintext_sha) is not None, 'EXPECTED_SHA256_REQUIRED')
    require(isinstance(expected_bytes, int) and 0 <= expected_bytes <= MAX_PLAIN, 'EXPECTED_PLAINTEXT_LENGTH_REJECTED')
    require(bool(snapshot_receipt) == bool(snapshot_receipt_sha) and not (sqlite and snapshot_receipt), 'SNAPSHOT_RECEIPT_ARGUMENT_PAIR_REQUIRED')
    context_arguments = (context_receipt, context_receipt_sha, context_metadata, context_metadata_sha)
    context_mode = any(value is not None for value in context_arguments)
    require(not context_mode or all(isinstance(value, str) and value for value in context_arguments), 'CONTEXT_ARGUMENT_QUARTET_REQUIRED')
    require(not context_mode or not (sqlite or snapshot_receipt), 'CONTEXT_MODES_MUST_NOT_MIX')
    require(not context_mode or expected_bytes <= 2 * 1024 * 1024, 'CONTEXT_PLAINTEXT_BYTES_LIMIT_EXCEEDED')
    api = api or WindowsApi()
    encrypted = clear = None
    try:
        encrypted, identity = api.read_cipher(cipher)
        actual_cipher_sha = hashlib.sha256(encrypted).hexdigest()
        require(actual_cipher_sha == expected_cipher_sha, 'CIPHER_SHA256_MISMATCH')
        clear = api.crypt(encrypted)
        require(len(clear) == expected_bytes, 'PLAINTEXT_LENGTH_MISMATCH')
        actual_plain_sha = hashlib.sha256(clear).hexdigest()
        require(actual_plain_sha == expected_plaintext_sha, 'PLAINTEXT_SHA256_MISMATCH')
        result = {'status': 'PASS', 'cipherSha256': actual_cipher_sha, 'cipherBytes': len(encrypted),
                  'plaintextSha256': actual_plain_sha, 'plaintextBytes': len(clear), 'currentUserDpapi': True,
                  'normalNonAdministrator': True, 'plaintextFileCreated': False, 'payloadLogged': False,
                  'ownedBuffersWipeAttempted': True, 'allInternalCopiesSecurelyErased': False}
        if sqlite:
            result['sqlite'] = sqlite_metadata(clear)
        if snapshot_receipt:
            result['snapshotBundle'] = receipt_bundle(clear, snapshot_receipt, snapshot_receipt_sha, api)
        if context_mode:
            result['contextBundle'] = receipt_context(clear, context_receipt, context_receipt_sha,
                                                      context_metadata, context_metadata_sha, api)
        return result
    finally:
        wipe(clear)
        wipe(encrypted)

def evaluate(**kwargs):
    api = kwargs.pop('api', None) or WindowsApi()
    before = api.unprotect_calls
    try:
        result = verify(api=api, **kwargs)
    except GuardError as error:
        result = {'status': 'REJECTED', 'code': str(error)}
    except NativeError as error:
        result = {'status': 'UNKNOWN', 'code': error.code, 'winerror': error.winerror}
    except sqlite3.Error as error:
        result = {'status': 'UNKNOWN', 'code': 'SQLITE_INVALID_OR_UNSUPPORTED', 'sqliteErrorName': getattr(error, 'sqlite_errorname', 'UNKNOWN')}
    except Exception as error:
        result = {'status': 'UNKNOWN', 'code': 'UNEXPECTED_VALIDATION_ERROR', 'exceptionClass': type(error).__name__}
    result['nativeUnprotectCalls'] = api.unprotect_calls - before
    result['plaintextFileCreated'] = False
    result['payloadLogged'] = False
    return result

def main():
    parser = argparse.ArgumentParser(description='Current-user DPAPI, memory-only integrity verifier candidate')
    parser.add_argument('--cipher', required=True)
    parser.add_argument('--expected-cipher-sha256', required=True)
    parser.add_argument('--expected-plaintext-sha256', required=True)
    parser.add_argument('--expected-plaintext-bytes', required=True, type=int)
    parser.add_argument('--sqlite', action='store_true')
    parser.add_argument('--snapshot-receipt')
    parser.add_argument('--snapshot-receipt-sha256')
    parser.add_argument('--context-receipt')
    parser.add_argument('--context-receipt-sha256')
    parser.add_argument('--context-metadata')
    parser.add_argument('--context-metadata-sha256')
    args = parser.parse_args()
    try:
        result = evaluate(cipher=args.cipher, expected_cipher_sha=args.expected_cipher_sha256,
                          expected_plaintext_sha=args.expected_plaintext_sha256,
                          expected_bytes=args.expected_plaintext_bytes, sqlite=args.sqlite,
                          snapshot_receipt=args.snapshot_receipt, snapshot_receipt_sha=args.snapshot_receipt_sha256,
                          context_receipt=args.context_receipt, context_receipt_sha=args.context_receipt_sha256,
                          context_metadata=args.context_metadata, context_metadata_sha=args.context_metadata_sha256)
    except Exception as error:
        result = {'status': 'UNKNOWN', 'code': 'PLATFORM_INITIALIZATION_REJECTED', 'exceptionClass': type(error).__name__}
    print(json.dumps(result, ensure_ascii=True, separators=(',', ':')))
    return 0 if result['status'] == 'PASS' else 2

if __name__ == '__main__':
    raise SystemExit(main())
