"""The privileged helper executes only this shipped code, with frozen data quoted as data."""


def mirror_system_command(policy, manager, arguments):
    providers = ['wtl_source_providers() { case "$1" in']
    routes = ['wtl_source_routes() { case "$1:$2" in']
    repositories = ['wtl_source_repo_templates() { case "$1:$2" in']
    credentials = ['wtl_source_credential() { wtl_credential_file= wtl_credential_scope= wtl_credential_ref=; case "$1:$2" in']
    for point, endpoints in policy.points.items():
        if not re.fullmatch('[A-Z][A-Z0-9_]+', point):
            raise MirrorFailure('invalid frozen system point')
        ids = []
        for endpoint in endpoints:
            provider = endpoint['providerId']
            if not re.fullmatch('[A-Za-z0-9_.-]{1,96}', provider):
                raise MirrorFailure('invalid frozen source id')
            ids.append(shlex.quote(provider))
            origins = [shlex.quote(route['origin']) for route in endpoint.get('routes', []) if not route.get('pattern')]
            prefixes = [shlex.quote(route['prefix']) for route in endpoint.get('routes', []) if not route.get('pattern')]
            routes.append(point + ':' + provider + ') origins=(' + ' '.join(origins)
                          + '); prefixes=(' + ' '.join(prefixes) + ');;')
            templates = ['[' + shlex.quote(key) + ']=' + shlex.quote(value)
                         for key, value in endpoint.get('repoTemplates', {}).items()]
            repositories.append(point + ':' + provider + ') repo_templates=(' + ' '.join(templates) + ');;')
            reference = endpoint.get('secretRef', '')
            credential = point + ':' + provider + ') '
            if reference:
                credential += ('wtl_credential_ref=' + shlex.quote(reference)
                               + '; wtl_credential_scope=' + shlex.quote(endpoint['baseUrl'])
                               + '; wtl_credential_file="${WTL_MIRROR_CREDENTIALS_DIR:?}/'
                               + hashlib.sha256(reference.encode()).hexdigest() + '";')
            else:
                credential += ':;'
            credentials.append(credential + ';')
        providers.append(point + ') providers=(' + ' '.join(ids) + ');;')
    providers.append('*) return 65;; esac; }')
    routes.append('*) return 65;; esac; }')
    repositories.append('*) return 65;; esac; }')
    credentials.append('*) return 65;; esac; }')
    shell = 'export WTL_MIRROR_SYSTEM_PYTHON=' + shlex.quote(sys.executable) + '\n'
    shell += 'wtl_mirror_runtime_sha256=' + shlex.quote(globals().get('MIRROR_RUNTIME_SHA256', '')) + '\n'
    shell += ('if [ "$EUID" = 0 ]; then\n'
              '  export TMPDIR="${WTL_MIRROR_POLICY%/*}" TMP="${WTL_MIRROR_POLICY%/*}" TEMP="${WTL_MIRROR_POLICY%/*}"\n'
              'fi\n')
    shell += 'wtl_mirror_connect_seconds=%d\nwtl_mirror_idle_seconds=%d\nwtl_mirror_low_window_seconds=%d\nwtl_mirror_low_rate=%d\nwtl_mirror_attempts=%d\n' % (
        max(1, int(policy.connect)), max(1, int(policy.idle)), max(1, int(policy.window)), policy.minimum, policy.attempts)
    shell += '\n'.join(providers + routes + repositories + credentials) + '\n' + BOOTSTRAP_SHELL
    entrypoint = {'apt-get': 'apt', 'apt-cache': 'apt_candidate', 'dnf': 'dnf'}.get(manager)
    if entrypoint is None:
        raise MirrorFailure('unsupported native package operation')
    shell += '\nwtl_' + entrypoint + ' "$@"\n'
    return ['/bin/bash', '-pc', shell, 'wtl-system-package'] + list(arguments)
