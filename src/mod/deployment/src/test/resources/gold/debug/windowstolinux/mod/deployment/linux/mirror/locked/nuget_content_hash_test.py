"""ZIP structure fixtures; synthetic signature bytes are not a CMS verification proof."""
import hashlib
import pathlib
import struct
import sys
import unittest
import zlib

import locked_transport_test as shared


def archive(names=('a.txt', 'b.txt'), signature=None, position=2, reverse=False, descriptor=0,
            extra=b'\xfe\xca\x03\x00xyz', comment=b'archive-comment'):
    entries = [(name.encode(), (name + '-payload').encode(), 8, descriptor) for name in names]
    if signature is not None:
        entries.insert(position, (b'.signature.p7s', signature, 0, 0))
    local, central = bytearray(), []
    for name, body, method, dd in entries:
        compressor = zlib.compressobj(wbits=-15)
        packed = compressor.compress(body) + compressor.flush() if method == 8 else body
        crc, flags, offset = zlib.crc32(body), 8 if dd else 0, len(local)
        values = (0, 0, 0) if dd else (crc, len(packed), len(body))
        local += struct.pack('<4s5H3I2H', b'PK\x03\x04', 20, flags, method, 0, 0,
                             *values, len(name), len(extra)) + name + extra + packed
        if dd:
            local += (b'PK\x07\x08' if dd == 2 else b'') + struct.pack('<3I', crc, len(packed), len(body))
        central.append(struct.pack('<4s6H3I5H2I', b'PK\x01\x02', 20, 20, flags, method, 0, 0,
                                   crc, len(packed), len(body), len(name), len(extra), 7, 0, 0, 0, offset)
                       + name + extra + b'comment')
    if reverse:
        central.reverse()
    directory = b''.join(central)
    return bytes(local) + directory + struct.pack('<4s4H2IH', b'PK\x05\x06', 0, 0, len(entries),
        len(entries), len(directory), len(local), len(comment)) + comment


class NugetArchiveHashTests(unittest.TestCase):
    def setUp(self):
        self.work = shared.tempfile.TemporaryDirectory(prefix='nh-')
        self.directory = pathlib.Path(self.work.name)

    def tearDown(self):
        self.work.cleanup()

    def digest(self, payload):
        path = self.directory / 'package.nupkg'
        path.write_bytes(payload)
        return shared.runtime['nuget_content_hash'](path)

    def test_signed_hash_reconstructs_original_bytes_for_physical_and_central_orders(self):
        for reverse in (False, True):
            for descriptor in (0, 1, 2):
                original = archive(reverse=reverse, descriptor=descriptor)
                expected = hashlib.sha512(original).hexdigest()
                self.assertEqual(expected, self.digest(original))
                for position in (0, 1, 2):
                    for signature in (b'first synthetic CMS', b'new longer synthetic CMS contents'):
                        signed = archive(signature=signature, position=position, reverse=reverse, descriptor=descriptor)
                        self.assertNotEqual(expected, hashlib.sha512(signed).hexdigest())
                        self.assertEqual(expected, self.digest(signed))
        tampered = archive(names=('changed.txt', 'b.txt'), signature=b'CMS')
        self.assertNotEqual(hashlib.sha512(archive()).hexdigest(), self.digest(tampered))

    def test_malformed_or_unsupported_signed_archives_fail_closed(self):
        signed = archive(signature=b'CMS')
        eocd, cd = signed.rfind(b'PK\x05\x06'), signed.find(b'PK\x01\x02')
        cases = [b'not a ZIP', signed[:-1], signed + b'trailing', b'prefix' + signed,
                 archive(names=('.signature.p7s',), signature=b'CMS')]
        def changed(offset, fmt, value):
            data = bytearray(signed)
            struct.pack_into(fmt, data, offset, value)
            return bytes(data)
        cases += [changed(eocd + 4, '<H', 1), changed(eocd + 10, '<H', 0xffff),
                  changed(cd + 42, '<I', 1), changed(cd + 8, '<H', 1),
                  changed(cd + 10, '<H', 99), changed(cd + 6, '<H', 0xffff)]
        sig_cd = signed.rfind(b'PK\x01\x02')
        cases += [changed(sig_cd + 8, '<H', 0x800), changed(sig_cd + 10, '<H', 8)]
        # ZIP64 extra fields are rejected even without saturated classic fields.
        cases += [signed.replace(b'\xfe\xca\x03\x00xyz', b'\x01\x00\x03\x00xyz')]
        # Duplicate LFH reference and overlapping local payload.
        second_cd = signed.find(b'PK\x01\x02', cd + 4)
        cases += [changed(second_cd + 42, '<I', 0), changed(cd + 20, '<I', 0x100000)]
        for index, payload in enumerate(cases):
            with self.subTest(index=index), self.assertRaises(shared.Failure):
                self.digest(payload)

    def test_unsigned_zip64_remains_a_raw_archive_hash(self):
        import io
        import zipfile
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as package:
            with package.open('large-capable.txt', 'w', force_zip64=True) as entry:
                entry.write(b'unsigned ZIP64 local entry')
        payload = stream.getvalue()
        self.assertEqual(hashlib.sha512(payload).hexdigest(), self.digest(payload))


class NugetContentHashTransportTests(unittest.TestCase):
    setUp = shared.LockedTransportTests.setUp
    tearDown = shared.LockedTransportTests.tearDown
    policy = shared.LockedTransportTests.policy
    transport = shared.LockedTransportTests.transport

    def lock(self, digest):
        path = self.project / 'packages.lock.json'
        path.write_text(shared.json.dumps({'version': 1, 'dependencies': {'net10.0': {'Example': {
            'type': 'Direct', 'resolved': '1.0.0', 'contentHash': shared.base64.b64encode(bytes.fromhex(digest)).decode()}}}}))
        return path

    def test_signed_package_content_hash_transport_and_cache(self):
        payload, original = archive(signature=b'synthetic CMS'), archive()
        lock = self.lock(hashlib.sha512(original).hexdigest())
        original_lock = lock.read_bytes()
        with shared.Repository(self.upstream_context, lambda path: (200, 'application/octet-stream', payload)) as repository:
            policy = self.policy('NUGET', [shared.endpoint('selected', repository.base)])
            with self.transport(policy, 'NUGET') as transport:
                url = '/flat/example/1.0.0/example.1.0.0.nupkg'
                self.assertEqual((200, payload), shared.request(transport, url))
                self.assertEqual((200, payload), shared.request(transport, url))
                self.assertEqual(1, len(repository.requests))
                record = policy.transfer_ledger.object(('NUGET', 'https://locked.example' + url))
                self.assertNotIn('expected', record)
                self.assertEqual(hashlib.sha512(original).hexdigest(), record['nuget_content_hash_expected'])
                cached, _ = next(iter(record['cache'].values()))
                cached.write_bytes(archive(names=('changed.txt', 'b.txt'), signature=b'CMS'))
                self.assertEqual(502, shared.request(transport, url)[0])
                self.assertEqual(1, len(repository.requests))
        self.assertEqual(original_lock, lock.read_bytes())

    def test_same_url_cannot_change_locked_content_hash(self):
        payload = archive(signature=b'CMS')
        self.lock(hashlib.sha512(archive()).hexdigest())
        with shared.Repository(self.upstream_context, lambda path: (200, 'application/octet-stream', payload)) as repository:
            policy = self.policy('NUGET', [shared.endpoint('selected', repository.base)])
            with self.transport(policy, 'NUGET') as transport:
                url = '/flat/example/1.0.0/example.1.0.0.nupkg'
                self.assertEqual(200, shared.request(transport, url)[0])
                transport.nuget_hashes['example.1.0.0.nupkg'] = '00' * 64
                self.assertEqual(502, shared.request(transport, url)[0])
                self.assertEqual(1, len(repository.requests))

    def test_another_transport_without_a_lock_inherits_bound_content_hash(self):
        payload = archive(signature=b'CMS')
        lock = self.lock(hashlib.sha512(archive()).hexdigest())
        with shared.Repository(self.upstream_context, lambda path: (200, 'application/octet-stream', payload)) as repository:
            policy = self.policy('NUGET', [shared.endpoint('selected', repository.base)])
            url = '/flat/example/1.0.0/example.1.0.0.nupkg'
            with self.transport(policy, 'NUGET') as first:
                self.assertEqual(200, shared.request(first, url)[0])
            lock.unlink()
            record = policy.transfer_ledger.object(('NUGET', 'https://locked.example' + url))
            cached, _ = next(iter(record['cache'].values()))
            cached.write_bytes(archive(names=('changed.txt', 'b.txt'), signature=b'CMS'))
            with self.transport(policy, 'NUGET') as second:
                self.assertEqual({}, second.nuget_hashes)
                self.assertEqual(502, shared.request(second, url)[0])
            self.assertEqual(1, len(repository.requests))

    def test_wrong_lock_and_custom_only_failure_do_not_fall_back(self):
        self.lock('00' * 64)
        payload = archive(signature=b'CMS')
        with shared.Repository(self.upstream_context, lambda path: (200, 'application/octet-stream', payload)) as repository:
            selected = dict(shared.endpoint('custom', repository.base), custom=True)
            policy = self.policy('NUGET', [selected])
            with self.transport(policy, 'NUGET') as transport:
                self.assertEqual(502, shared.request(transport, '/flat/example/1.0.0/example.1.0.0.nupkg')[0])
                self.assertLessEqual(len(repository.requests), policy.attempts)
                self.assertEqual(1, len(policy.endpoints('NUGET')))

    def test_binding_between_precheck_and_transfer_is_enforced_without_a_race_sleep(self):
        payload = archive(names=('changed.txt', 'b.txt'), signature=b'CMS')
        expected = hashlib.sha512(archive()).hexdigest()
        with shared.Repository(self.upstream_context, lambda path: (200, 'application/octet-stream', payload)) as repository:
            path = '/flat/example/1.0.0/example.1.0.0.nupkg'
            url = 'https://locked.example' + path
            for cache_hit in (False, True):
                with self.subTest(cache_hit=cache_hit):
                    (self.project / 'packages.lock.json').unlink(missing_ok=True)
                    policy = self.policy('NUGET', [shared.endpoint('selected', repository.base)])
                    request_count = len(repository.requests)
                    with self.transport(policy, 'NUGET') as first:
                        self.assertEqual({}, first.nuget_hashes)
                        if cache_hit:
                            self.assertEqual(200, shared.request(first, path)[0])
                        record = policy.transfer_ledger.object(('NUGET', url))
                        self.assertNotIn('nuget_content_hash_expected', record)
                        original = shared.runtime['mirror_transfer']
                        original_hash = shared.runtime['nuget_content_hash']
                        checks = []
                        def locked_hash(package):
                            self.assertTrue(record['lock'].locked())
                            self.assertEqual(shared.threading.get_ident(), record['owner'])
                            checks.append(cache_hit)
                            return original_hash(package)
                        def stop_after_binding(*args, **kwargs):
                            raise shared.Failure('test stops the second transport after its real ledger binding')
                        def bind_then_transfer(*args, **kwargs):
                            # Deterministically bind between the precheck and the real transfer.
                            shared.runtime['mirror_transfer'] = stop_after_binding
                            try:
                                self.lock(expected)
                                with self.transport(policy, 'NUGET') as second:
                                    with self.assertRaises(shared.Failure):
                                        second.fetch(url, self.directory / 'unused-response')
                                self.assertEqual(expected, record['nuget_content_hash_expected'])
                            finally:
                                shared.runtime['mirror_transfer'] = original
                            return original(*args, **kwargs)
                        shared.runtime['mirror_transfer'] = bind_then_transfer
                        shared.runtime['nuget_content_hash'] = locked_hash
                        try:
                            self.assertEqual(502, shared.request(first, path)[0])
                            self.assertEqual([cache_hit], checks)
                        finally:
                            shared.runtime['mirror_transfer'] = original
                            shared.runtime['nuget_content_hash'] = original_hash
                    self.assertEqual(request_count + 1, len(repository.requests))


if __name__ == '__main__':
    unittest.main(verbosity=2)
