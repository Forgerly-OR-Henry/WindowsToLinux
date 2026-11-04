"""Consumes only protected task/server/policy-bound material, never public JSON secret values."""
import stat


class MirrorCredentialMaterial:
    def __init__(self, policy, directory, policy_sha):
        self.policy, self.directory, self.cache = policy, pathlib.Path(directory), {}
        task, server = policy.document.get('task', ''), policy.document.get('server', '')
        if (not re.fullmatch(r'[0-9a-f-]{36}', task) or not isinstance(server, str)
                or not re.fullmatch('[0-9a-f]{64}', policy_sha)):
            raise MirrorFailure('mirror credential binding is invalid')
        expected = (task, hashlib.sha256(server.encode()).hexdigest(), policy_sha)
        if tuple(self.directory.parts[-3:]) != expected:
            raise MirrorFailure('mirror credentials are bound to a different task, server or policy')
        for path in (self.directory, self.directory.parent, self.directory.parent.parent):
            value = path.lstat()
            if (not stat.S_ISDIR(value.st_mode) or path.is_symlink()
                    or os.name != 'nt' and (value.st_uid not in (0, os.geteuid()) or value.st_mode & 0o077)):
                raise MirrorFailure('mirror credential directory is not task private')
        if self.directory.resolve() != self.directory.absolute():
            raise MirrorFailure('mirror credential path traverses a symbolic link')

    def read(self, endpoint):
        reference = endpoint.get('secretRef', '')
        if not reference:
            return '', ''
        identity = hashlib.sha256(reference.encode()).hexdigest()
        if identity in self.cache:
            return self.cache[identity]
        path = self.directory / identity
        descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
        with os.fdopen(descriptor, 'rb') as source:
            metadata = os.fstat(source.fileno())
            if (not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1 or not 14 <= metadata.st_size <= 65536
                    or os.name != 'nt' and (metadata.st_uid not in (0, os.geteuid()) or metadata.st_mode & 0o077)):
                raise MirrorFailure('mirror credential file is not protected material')
            value = bytearray(source.read(65537))
        try:
            parts = value.decode('utf-8').split('\n', 2)
            if len(parts) != 3 or parts[0] != 'WTL-MIRROR-1':
                raise MirrorFailure('mirror credential format invalid')
            authorization, ca = parts[1:]
            if authorization and not re.fullmatch(r'(Basic [A-Za-z0-9+/]+=*|Bearer [A-Za-z0-9._~+/-]+=*)', authorization):
                raise MirrorFailure('mirror authorization format invalid')
            if not authorization and not ca:
                raise MirrorFailure('mirror credential is empty')
            self.cache[identity] = authorization, ca
            return authorization, ca
        finally:
            value[:] = b'\x00' * len(value)

    @staticmethod
    def in_scope(endpoint, url):
        expected = urllib.parse.urlsplit(endpoint['baseUrl'])
        actual = urllib.parse.urlsplit(url)
        prefix = expected.path.rstrip('/')
        decoded = urllib.parse.unquote(actual.path)
        return (actual.scheme == expected.scheme == 'https' and actual.hostname == expected.hostname
                and (actual.port or 443) == (expected.port or 443)
                and (not prefix or actual.path == prefix or actual.path.startswith(prefix + '/'))
                and '\\' not in decoded and not any(part in ('.', '..') for part in decoded.split('/'))
                and re.search(r'%[0-9a-fA-F]{2}', decoded) is None
                and decoded.count('/') == actual.path.count('/')
                and not actual.username and not actual.password)

    def options(self, endpoint, url):
        authorization, ca = self.read(endpoint)
        scoped = self.in_scope(endpoint, url)
        context = None
        if ca and scoped:
            try:
                context = ssl.create_default_context()
                context.load_verify_locations(cadata=ca)
            except (ValueError, ssl.SSLError):
                raise MirrorFailure('mirror CA material invalid') from None
        return ({'Authorization': authorization} if authorization and scoped else {}), context


def install_mirror_credentials(policy, policy_sha):
    if not any(endpoint.get('secretRef') for endpoints in policy.points.values() for endpoint in endpoints):
        return policy
    directory = os.environ.get('WTL_MIRROR_CREDENTIALS_DIR')
    if not directory:
        raise MirrorFailure('selected authenticated source has no bound task credential directory')
    try:
        material = MirrorCredentialMaterial(policy, directory, policy_sha)
        for endpoints in policy.points.values():
            for endpoint in endpoints:
                if endpoint.get('secretRef'):
                    material.read(endpoint)
        policy.transport_options = material.options
        policy.credential_material = material
        return policy
    except (OSError, UnicodeError):
        raise MirrorFailure('bound mirror credential material is unavailable') from None
