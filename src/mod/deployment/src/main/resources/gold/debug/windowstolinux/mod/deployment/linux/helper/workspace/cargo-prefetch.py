import hashlib
import json
import os
import pathlib
import re
import sys
import time


def prefetch():
    candidate, expected_runtime, seconds = sys.argv[1:]
    mutable = pathlib.Path(candidate) / 'mutable'
    document = (pathlib.Path(candidate) / '.build-prefetch').read_text(encoding='utf-8')
    binding = None
    if document.startswith('# WTL_MIRROR_BINDING='):
        binding, document = document.split('\n', 1)
        binding = binding.removeprefix('# WTL_MIRROR_BINDING=')
    value = json.loads(document)
    if set(value) != {'task', 'runtime', 'policy', 'archiveSha256', 'lockMember'}:
        raise ValueError('prefetch fields')
    task = value['task']
    if (not re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', task)
            or (pathlib.Path(candidate) / '.agent-task').read_text(encoding='ascii') != task + '\n'):
        raise ValueError('prefetch candidate task')
    runtime, raw_policy = value['runtime'].encode(), value['policy'].encode()
    if hashlib.sha256(runtime).hexdigest() != expected_runtime:
        raise ValueError('runtime identity')
    # Only the pinned runtime executes. Request fields are data, including the project lock member.
    scope = globals()
    scope['__name__'] = 'wtl_cargo_prefetch'
    exec(compile(runtime, 'pinned-mirror-runtime', 'exec'), scope)
    try:
        if not raw_policy:
            locked = cargo_prefetch_lock(mutable / 'source.tar.gz', value['archiveSha256'], value['lockMember'])
            if locked:
                raise MirrorFailure('Cargo dependency acquisition requires the original frozen policy')
            (mutable / 'home' / 'wtl-cargo-registry' / 'index').mkdir(parents=True)
            return
        policy_document = json.loads(raw_policy)
        if policy_document.get('task') != task:
            raise ValueError('prefetch policy task')
        policy_sha = hashlib.sha256(raw_policy).hexdigest()
        if binding:
            expected = '/'.join((policy_document['task'], hashlib.sha256(policy_document['server'].encode()).hexdigest(),
                                 policy_sha))
            if binding != expected or not re.fullmatch(r'[a-f0-9-]{36}/[a-f0-9]{64}/[a-f0-9]{64}', binding):
                raise ValueError('prefetch material binding')
            os.environ['WTL_MIRROR_CREDENTIALS_DIR'] = str(mutable / 'tmp' / 'wtl-mirror-material' / binding)
        policy = MirrorPolicy(policy_document, time.monotonic() + int(seconds))
        policy = install_mirror_credentials(policy, policy_sha)
        mirror_cargo_prefetch(policy, mutable / 'source.tar.gz', value['archiveSha256'], value['lockMember'],
                              mutable / 'home' / 'wtl-cargo-registry')
    except MirrorFailure as error:
        print('MIRROR_REJECT=' + str(error), file=sys.stderr)
        return 65
    return 0


try:
    raise SystemExit(prefetch())
except (OSError, ValueError, AttributeError, KeyError, TypeError):
    print('MIRROR_REJECT=invalid-cargo-prefetch-data', file=sys.stderr)
    raise SystemExit(65)
