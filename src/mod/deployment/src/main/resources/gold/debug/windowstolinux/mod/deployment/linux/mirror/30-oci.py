"""OCI policy applies only to the task-private engine passed by its build entry."""


def oci_command(command, policy, capture=False):
    process = subprocess.Popen(command, cwd=os.getcwd(), env=os.environ.copy(),
                               stdout=subprocess.PIPE if capture else None,
                               start_new_session=os.name != 'nt')
    try:
        output, _ = process.communicate(timeout=policy.remaining())
        if process.returncode:
            raise MirrorFailure('private OCI engine command failed')
        return output
    finally:
        if os.name != 'nt':
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        elif process.poll() is None:
            process.kill()
        process.wait()


def oci_reference(reference, policy):
    if not re.fullmatch(r'[A-Za-z0-9_.:/@+-]+', reference):
        raise MirrorFailure('unresolved OCI image reference')
    first, separator, rest = reference.partition('/')
    explicit = separator and ('.' in first or ':' in first or first == 'localhost')
    if explicit:
        host, repository = first, rest
    else:
        endpoint = policy.endpoints('OCI_REGISTRY')[0]
        routes = endpoint.get('routes', [])
        if not routes:
            raise MirrorFailure('OCI default registry origin is absent')
        host = urllib.parse.urlsplit(routes[0]['origin']).netloc
        repository = reference if separator else 'library/' + reference
    if '@' in repository:
        repository, identity = repository.rsplit('@', 1)
        if not re.fullmatch(r'sha256:[a-f0-9]{64}', identity):
            raise MirrorFailure('OCI manifest digest must be sha256')
    elif ':' in repository.rsplit('/', 1)[-1]:
        repository, identity = repository.rsplit(':', 1)
    else:
        identity = 'latest'
    return 'https://' + host + '/', repository, identity


def oci_manifest(policy, original, expected=None):
    headers = {'Accept': ', '.join(('application/vnd.oci.image.index.v1+json',
                                  'application/vnd.docker.distribution.manifest.list.v2+json',
                                  'application/vnd.oci.image.manifest.v1+json',
                                  'application/vnd.docker.distribution.manifest.v2+json')),
               'Accept-Encoding': 'identity'}
    def validate_manifest(path, response_headers):
        content = json.loads(path.read_bytes())
        if not isinstance(content, dict) or content.get('schemaVersion') != 2:
            raise ValueError('OCI schema 2 is required')
    payload, metadata = mirror_bytes(policy, 'OCI_REGISTRY', original, headers=headers, oci_auth=True,
                                     expected=expected[7:] if expected else None, validator=validate_manifest)
    content = json.loads(payload)
    return content, 'sha256:' + metadata['sha256']


def oci_prefetch(policy, engine, origin, repository, identity, platform):
    """Verify every OCI manifest/blob, then load a local Docker archive into the private engine."""
    import gzip
    import io
    import shutil
    with tempfile.TemporaryDirectory(prefix='wtl-oci-prefetch-') as directory:
        root = pathlib.Path(directory)
        api = origin + 'v2/' + repository + '/'
        manifest, frozen = oci_manifest(policy, api + 'manifests/' + identity,
                                        identity if identity.startswith('sha256:') else None)
        if 'manifests' in manifest:
            os_name, architecture = ('linux', 'amd64')
            if platform:
                parts = platform.removeprefix('--platform=').split('/')
                if len(parts) != 2 or parts[0] != 'linux':
                    raise MirrorFailure('OCI platform must be a concrete supported linux architecture')
                os_name, architecture = parts
            matches = [item for item in manifest['manifests'] if item.get('platform', {}).get('os') == os_name
                       and item.get('platform', {}).get('architecture') == architecture]
            if len(matches) != 1:
                raise MirrorFailure('OCI index platform is absent or ambiguous')
            child = matches[0].get('digest', '')
            if not re.fullmatch(r'sha256:[a-f0-9]{64}', child):
                raise MirrorFailure('unsupported OCI child digest')
            manifest, _ = oci_manifest(policy, api + 'manifests/' + child, child)
        config = manifest.get('config', {}).get('digest', '')
        if not re.fullmatch(r'sha256:[a-f0-9]{64}', config):
            raise MirrorFailure('OCI config SHA256 is absent')
        configuration = root / (config[7:] + '.json')
        mirror_transfer(policy, 'OCI_REGISTRY', api + 'blobs/' + config, configuration,
                        'sha256', config[7:], limit=33554432, oci_auth=True)
        details = json.loads(configuration.read_bytes())
        if details.get('os', 'linux') != 'linux':
            raise MirrorFailure('OCI base image must target linux')
        expected_layers = details.get('rootfs', {}).get('diff_ids', [])
        layers = manifest.get('layers', [])
        if len(layers) != len(expected_layers) or len(layers) > 1024:
            raise MirrorFailure('OCI layer/config identity mismatch')
        layer_paths = []
        total = 0
        for index, layer in enumerate(layers):
            digest = layer.get('digest', '')
            expected_diff = expected_layers[index]
            if not re.fullmatch(r'sha256:[a-f0-9]{64}', digest) or not re.fullmatch(r'sha256:[a-f0-9]{64}', expected_diff):
                raise MirrorFailure('OCI layer SHA256 is absent')
            if layer.get('urls'):
                raise MirrorFailure('foreign OCI layer URLs are not permitted')
            blob = root / ('blob-' + str(index))
            mirror_transfer(policy, 'OCI_REGISTRY', api + 'blobs/' + digest, blob,
                            'sha256', digest[7:], oci_auth=True)
            output = root / ('layer-' + str(index)) / 'layer.tar'
            output.parent.mkdir()
            mode = layer.get('mediaType', '')
            if mode.endswith('+gzip') or mode.endswith('.gzip'):
                stream = gzip.open(blob, 'rb')
            elif mode.endswith('.tar'):
                stream = blob.open('rb')
            else:
                raise MirrorFailure('unsupported OCI layer compression; no tool is installed implicitly')
            diff = hashlib.sha256()
            with stream, output.open('xb') as target:
                while True:
                    policy.remaining()
                    block = stream.read(65536)
                    if not block:
                        break
                    total += len(block)
                    if total > 8589934592:
                        raise MirrorFailure('OCI unpacked layers exceed task transfer limit')
                    diff.update(block)
                    target.write(block)
            if diff.hexdigest() != expected_diff[7:]:
                raise MirrorFailure('OCI uncompressed layer differs from config diff_id')
            blob.unlink()
            layer_paths.append(output.relative_to(root).as_posix())
        local = 'localhost/wtl-frozen-base:' + frozen[7:]
        metadata = json.dumps([{'Config': configuration.name, 'RepoTags': [local], 'Layers': layer_paths}]).encode()
        archive = root / 'image.tar'
        with tarfile.open(archive, 'w') as output:
            record = tarfile.TarInfo('manifest.json')
            record.size = len(metadata)
            output.addfile(record, io.BytesIO(metadata))
            output.add(configuration, arcname=configuration.name, recursive=False)
            for layer_path in layer_paths:
                output.add(root / layer_path, arcname=layer_path, recursive=False)
        oci_command(engine + ['load', '--input', str(archive)], policy)
        print('OCI_FROZEN=' + frozen, file=sys.stderr, flush=True)
        return local


def mirror_oci_prepare(policy, engine, dockerfile, output):
    source = pathlib.Path(dockerfile)
    if source.is_symlink() or not source.is_file():
        raise MirrorFailure('regular Dockerfile is required')
    text = source.read_text(encoding='utf-8')
    # Continuations are handled before tokenizing directives, including FROM/ARG.
    text = re.sub(r'\\\r?\n', ' ', text)
    arguments, stages, rewritten, frozen = {}, set(), [], {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith('#'):
            if stripped.lower().startswith('# syntax='):
                raise MirrorFailure('external Dockerfile frontend is not allowed under source policy')
            rewritten.append(line)
            continue
        directive, _, value = stripped.partition(' ')
        directive = directive.upper()
        if directive == 'ARG':
            key, equals, default = value.partition('=')
            if equals:
                arguments[key.strip()] = default.strip()
            rewritten.append(line)
            continue
        if directive in ('ADD', 'COPY'):
            if re.search(r'https?://|git@|git://|--from=', value):
                # Stage-local COPY is safe; external COPY requires a separately frozen image.
                match = re.search(r'--from=([^\s]+)', value)
                if not match or match.group(1).lower() not in stages and not match.group(1).isdigit():
                    raise MirrorFailure('external ADD/COPY cannot bypass frozen OCI inputs')
        if directive != 'FROM':
            rewritten.append(line)
            continue
        value = re.sub(r'\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)',
                       lambda m: arguments.get(m.group(1) or m.group(2), m.group(0)), value)
        tokens = shlex.split(value)
        platform = []
        if tokens and tokens[0].startswith('--platform='):
            platform.append(tokens.pop(0))
        if not tokens:
            raise MirrorFailure('invalid FROM')
        image = tokens[0]
        if len(tokens) not in (1, 3) or len(tokens) == 3 and tokens[1].upper() != 'AS':
            raise MirrorFailure('unsupported FROM form')
        alias = tokens[2] if len(tokens) == 3 else None
        if image.lower() == 'scratch' or image.lower() in stages:
            selected = image
        else:
            if image not in frozen:
                origin, repository, identity = oci_reference(image, policy)
                frozen[image] = oci_prefetch(policy, engine, origin, repository, identity,
                                              platform[0] if platform else None)
            selected = frozen[image]
        rewritten.append('FROM ' + ' '.join(platform + [selected] + (['AS', alias] if alias else [])))
        if alias:
            stages.add(alias.lower())
    if not any(line.upper().startswith('FROM ') for line in rewritten):
        raise MirrorFailure('Dockerfile has no FROM')
    pathlib.Path(output).write_text('\n'.join(rewritten) + '\n', encoding='utf-8')


def mirror_main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest='operation', required=True)
    transfer = commands.add_parser('download')
    transfer.add_argument('point')
    transfer.add_argument('url')
    transfer.add_argument('target')
    transfer.add_argument('--algorithm')
    transfer.add_argument('--expected')
    transfer.add_argument('--limit', type=int, default=2147483648)
    dependency = commands.add_parser('dependency')
    dependency.add_argument('point')
    dependency.add_argument('command', nargs=argparse.REMAINDER)
    oci = commands.add_parser('oci')
    oci.add_argument('dockerfile')
    oci.add_argument('output')
    oci.add_argument('engine', nargs=argparse.REMAINDER)
    wrapper = commands.add_parser('wrapper')
    wrapper.add_argument('point', choices=('MAVEN_DISTRIBUTION', 'GRADLE_DISTRIBUTION'))
    wrapper.add_argument('properties')
    wrapper.add_argument('directory')
    source = commands.add_parser('source-url')
    source.add_argument('point')
    source.add_argument('relative')
    system = commands.add_parser('system-dnf')
    system.add_argument('arguments', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    policy = MirrorPolicy.from_environment()
    if policy is None:
        raise MirrorFailure('frozen mirror policy is absent')
    if args.operation == 'download':
        mirror_transfer(policy, args.point, args.url, args.target, args.algorithm, args.expected, args.limit)
        return 0
    if args.operation == 'dependency':
        return mirror_dependency(policy, args.point, args.command)
    if args.operation == 'system-dnf':
        return mirror_dnf(policy, args.arguments)
    if args.operation == 'oci':
        mirror_oci_prepare(policy, args.engine, args.dockerfile, args.output)
        return 0
    if args.operation == 'wrapper':
        mirror_wrapper(policy, args.point, args.properties, args.directory)
        return 0
    if args.operation == 'source-url':
        if args.relative.startswith('/') or '..' in pathlib.PurePosixPath(args.relative).parts or ':' in args.relative:
            raise MirrorFailure('invalid configured source-relative path')
        origins = policy.endpoints(args.point)[0].get('sourceOrigins', [])
        if not origins:
            raise MirrorFailure('configured canonical source origin is absent')
        print(origins[0].rstrip('/') + '/' + args.relative)
        return 0


if __name__ == '__main__':
    def mirror_cancelled(signum, frame):
        raise MirrorFailure('task-cancelled')
    signal.signal(signal.SIGTERM, mirror_cancelled)
    signal.signal(signal.SIGINT, mirror_cancelled)
    try:
        raise SystemExit(mirror_main())
    except (MirrorFailure, subprocess.TimeoutExpired) as error:
        print('MIRROR_REJECT=' + str(error), file=sys.stderr)
        raise SystemExit(65)
