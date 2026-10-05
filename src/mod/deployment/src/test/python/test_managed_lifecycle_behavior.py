"""Execute typed lifecycle/publish/rollback against original temporary identities. / 对原临时身份执行当前生命周期、发布与回滚。"""

import base64
import hashlib
import io
import json
import os
import pathlib
import tarfile
import tempfile
import unittest

from test_managed_storage_behavior import LINUX, invoke


PARTS = ('input/15-deployment-input.sh', 'input/16-application-input.sh', 'input/17-managed-content.sh',
         'ecosystem/35-ecosystem-dispatch.sh', 'runtime/40-typed-runtime.sh',
         'runtime/41-application-admission.sh', 'runtime/41-application-runtime.sh',
         'runtime/45-application-container-contract.sh', 'release/12-container-image-input.sh',
         'release/18-container-storage.sh', 'release/50-container-release.sh', 'release/52-container-recovery.sh',
         'database/10-native-instances.sh', 'database/20-native-targets.sh', 'database/21-native-operations.sh',
         str(LINUX / 'runtime/systemd/helper/61-service-identity.sh'),
         str(LINUX / 'runtime/systemd/helper/systemd-manager-isolation.sh'),
         str(LINUX / 'runtime/container/helper/55-podman-quadlet.sh'))

PUBLISH_PARTS = PARTS + ('workspace/20-candidate-workspace.sh', 'workspace/21-workspace-volume.sh',
                         'workspace/22-restricted-build.sh', 'release/10-typed-release.sh')


def payload(container=True, mode='DAEMON', endpoint=False):
    fields = ['application-v1', mode, '1', '', '', 'main' if container else '',
              '2' if container else '0']
    if container:
        fields += ['two words', 'literal$arg']
    fields += ['1', 'verify', '0'] if mode == 'ON_DEMAND' else ['0']
    fields += ['', '0', '1' if endpoint else '0']
    if endpoint:
        fields += ['web', 'TCP', '127.0.0.1', '8080', '8080', 'INTERNAL', '']
    fields += ['0', '0']
    fields += ['COMMAND', '10', 'verify', '0', ''] if mode == 'ON_DEMAND' else ['PROCESS', '10', '1']
    return base64.b64encode(('\0'.join(fields) + '\0').encode()).decode()


# Only commands addressing the OS/runtime are substituted. Actual current/owner/parameter,
# maintenance acquisition, mount/port checks and lifecycle decisions execute production functions.
RUNTIME = r'''
manifest=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
original=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
candidate=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
image_id=sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
unit_path() { printf '%s/unit' "$fixture"; }
podman_quadlet_path() { printf '%s/quadlets/windowstolinux-%s.container' "$fixture" "$1"; }
flock() { printf 'lock:%s\n' "$*" >>"$fixture/locks"; }
log_command() {
    printf '%s\0' "$@" >>"$fixture/commands"
    printf '\n' >>"$fixture/commands"
}
systemctl() {
    if [[ "${@: -1}" = windowstolinux-build-*.service ]]; then
        [ "$1" = show ] && [ "${4:-}" = LoadState ] || reject unexpected-build-runtime-command
        log_command systemctl "$@"
        printf not-found
        return
    fi
    case "$1" in
        show)
            case "${4:-}" in
                FragmentPath) unit_path demo ;;
                DropInPaths) : ;;
                LoadState) printf loaded ;;
                ActiveState) if [ -f "$fixture/stopped" ]; then printf inactive; else printf active; fi ;;
                MainPID) printf 0 ;;
                ControlGroup) : ;;
                *) printf 'ActiveState=inactive\nMainPID=0\nExecMainStatus=0\n' ;;
            esac ;;
        is-active) [ ! -f "$fixture/stopped" ] ;;
        cat) return 0 ;;
        stop) log_command systemctl "$@"; touch "$fixture/stopped" ;;
        start|restart) log_command systemctl "$@"; rm -f "$fixture/stopped" ;;
        *) log_command systemctl "$@" ;;
    esac
}
engine_command() {
    local engine="$1"; shift
    case "$1" in
        image)
            if [ "$#" = 3 ] && [ "$2" = inspect ]; then log_command "$engine" "$@"; return; fi
            case "$4" in
                '{{.Config.WorkingDir}}') printf /app ;;
                '{{.Config.User}}') printf 1000:1000 ;;
                '{{.Id}}') printf '%s' "$image_id" ;;
                '{{ index .Config.Labels "io.windowstolinux.application" }}')
                    log_command "$engine" "$@"
                    if [ "${publication_scenario:-exact}" = label-drift ]; then printf foreign; else printf demo; fi ;;
                '{{ index .Config.Labels "io.windowstolinux.candidate" }}')
                    log_command "$engine" "$@"; printf '%s' "$publish_candidate_id" ;;
                *) reject unexpected-image-inspection ;;
            esac ;;
        inspect)
            if [ "$#" = 2 ]; then return 0; fi
            case "$3" in
                '{{.Image}}') printf '%s' "$image_id" ;;
                '{{.State.Running}}') if [ -f "$fixture/stopped" ]; then printf false; else printf true; fi ;;
                '{{.HostConfig.ReadonlyRootfs}}') printf true ;;
                '{{json .HostConfig.PortBindings}}')
                    if [ "${#container_ports[@]}" = 0 ]; then printf '{}';
                    else printf '{"8080/tcp":[{"HostIp":"127.0.0.1","HostPort":"8080"}]}'; fi ;;
                *'.Type .Source .Destination .RW'*)
                    local mount
                    for mount in "${container_bind_mounts[@]}"; do printf 'bind:%s:true\n' "${mount%:*}"; done ;;
                *'.Source .Destination .RW'*)
                    local mount
                    for mount in "${container_bind_mounts[@]}"; do printf '%s:true\n' "${mount%:*}"; done ;;
                *) reject unexpected-container-inspection ;;
            esac ;;
        ps) printf 'windowstolinux-demo\n' ;;
        stop) log_command "$engine" "$@"; touch "$fixture/stopped" ;;
        start|restart) log_command "$engine" "$@"; rm -f "$fixture/stopped" ;;
        create|rm|update|load|tag) log_command "$engine" "$@" ;;
        *) reject unexpected-engine-command ;;
    esac
}
docker() { engine_command docker "$@"; }
podman() { engine_command podman "$@"; }
create_original() {
    local backend="$1" encoded="$2" files="${3:-0}"
    [ "$files" = 0 ] || [ "$files" = 1 ] || reject fixture-original-binding-count
    local release="$(app_root demo)/releases/$original"
    mkdir -p "$release/source" "$fixture/config/demo/$manifest"
    printf '%s' "$manifest" >"$release/.windowstolinux-owner"
    printf CONFIG=approved >"$fixture/config/demo/$manifest/container.env"
    printf CONFIG=approved >"$fixture/config/demo/$manifest/systemd.env"
    if [ "$backend" = native ]; then
        [ "$files" = 0 ] || reject fixture-native-container-binding
        save_deployment_parameters "$release/.windowstolinux-deployment-parameters" identity-v2 SYSTEMD_STATIC "$encoded" "$manifest" 0 demo demo 0 static public 8080 STATIC_SITE_BUILD
        mkdir -p "$release/source/public"
        render_deployment_unit demo identity-v2 SYSTEMD_STATIC "$encoded" "$manifest" 0 demo demo 0 static public 8080 STATIC_SITE_BUILD >"$(unit_path demo)"
    else
        local -a bindings=(0)
        if [ "$files" = 1 ]; then bindings=(1 files /data rw FILE DEFAULT - - - - -); fi
        parse_deployment_inputs identity-v2 CONTAINER_NON_ROOT "$encoded" "$manifest" 0 demo demo "${bindings[@]}"
        parse_managed_data_bindings "${deployment_remaining_arguments[@]}"
        parse_container_parameters "$container_engine" 0 0
        save_container_parameters "$release/.windowstolinux-container-parameters"
        printf '%s' "$container_engine" >"$release/.windowstolinux-container-engine"
        printf '%s' "$image_id" >"$release/.windowstolinux-container-image-id"
    fi
    ln -s -- "$release" "$(app_root demo)/current"
    [ -L "$(app_root demo)/current" ] && [ "$(readlink -f "$(app_root demo)/current")" = "$release" ] || reject fixture-current-link
}
'''


CURRENT_POSTCONDITION = r'''
current="$applications_root/demo/current"
release="$applications_root/demo/releases/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
if [ ! -L "$current" ] || [ "$(readlink -f -- "$current")" != "$release" ]; then
    printf 'Fixture current must be a real MSYS link to the original release\n' >&2
    exit 97
fi
owner="$(cat "$current/.windowstolinux-owner")"
if [ -z "$owner" ] || [ "$owner" != "$(cat "$release/.windowstolinux-owner")" ]; then
    printf 'Fixture current must read the original owner through the link\n' >&2
    exit 97
fi
printf 'MANAGED_FIXTURE_CURRENT=original\n'
'''


def commands(root):
    path = pathlib.Path(root) / 'commands'
    return [[value.decode() for value in row.split(b'\0')[:-1]]
            for row in path.read_bytes().splitlines()] if path.exists() else []


def image_archive(root, engine, candidate_id):
    """A real rootless export fixture consumed by the current tar validator, with a controlled engine."""
    candidate = pathlib.Path(root) / 'work' / candidate_id
    source = candidate / 'mutable/source'
    source.mkdir(parents=True)
    (source / 'main').write_bytes(b'controlled source executable\n')
    for name, value in (('.deployer', 'fixture\n'), ('.sealed', 'sealed\n'),
                        ('.workspace-device', '/dev/loop7\n'), ('.purpose', 'build\n')):
        (candidate / name).write_text(value, encoding='utf-8')
    (candidate / '.workspace.ext4').write_bytes(b'controlled loop-device boundary')
    tag = ('localhost/' if engine == 'podman' else '') + 'windowstolinux-candidate:' + candidate_id
    config = json.dumps({'config': {'User': '1000:1000', 'Labels': {
        'io.windowstolinux.application': 'demo', 'io.windowstolinux.candidate': candidate_id}}}).encode()
    config_name = hashlib.sha256(config).hexdigest() + '.json'
    manifest = json.dumps([{'Config': config_name, 'RepoTags': [tag], 'Layers': []}]).encode()
    with tarfile.open(candidate / 'mutable/candidate-image.tar', 'w') as archive:
        for name, value in ((config_name, config), ('manifest.json', manifest)):
            member = tarfile.TarInfo(name)
            member.size = len(value)
            archive.addfile(member, io.BytesIO(value))
    return tag


class ManagedLifecycleBehaviorTest(unittest.TestCase):
    def test_four_argument_actions_keep_original_maintenance_and_release_identity(self):
        for backend in ('native', 'docker', 'podman'):
            for scenario, expected in (('exact', 0), ('drift', 64), ('owner', 64), ('orphan', 64)):
                with self.subTest(backend=backend, scenario=scenario), tempfile.TemporaryDirectory() as temporary:
                    root = pathlib.Path(temporary)
                    body = RUNTIME + r'''
backend="$1"; scenario="$2"; container_engine="$backend"
create_original "$backend" "$3"
if [ "$scenario" = drift ]; then expected="$candidate"; else expected="$original"; fi
if [ "$scenario" = owner ]; then printf foreign >"$(app_root demo)/releases/$original/.windowstolinux-owner"; fi
if [ "$scenario" = orphan ]; then
    directory="$(application_lock_directory demo)"
    printf 'docker\nwindowstolinux-job-orphan\n' >"$directory/job"
fi
if [ "$backend" = native ]; then lifecycle_deployment demo restart "$manifest" "$expected";
else lifecycle_container demo restart "$manifest" "$expected"; fi
'''
                    result = invoke(root, body, PARTS, (backend, scenario, payload(backend != 'native')),
                                    CURRENT_POSTCONDITION)
                    self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
                    self.assertIn('MANAGED_FIXTURE_CURRENT=original\n', result.stdout)
                    self.assertTrue((root / 'control/application-locks/demo/admission').is_file())
                    self.assertTrue((root / 'control/application-locks/demo/run').is_file())
                    self.assertGreaterEqual((root / 'locks').read_text().count('lock:'), 2)
                    if expected:
                        self.assertEqual([], commands(root))
                    else:
                        self.assertIn((['docker', 'restart', 'windowstolinux-demo'] if backend == 'docker'
                                       else ['systemctl', 'restart', 'windowstolinux-demo.service']), commands(root))

    def test_docker_and_podman_actions_use_complete_original_argv(self):
        for engine in ('docker', 'podman'):
            for action in ('start', 'stop', 'restart', 'enable', 'disable'):
                with self.subTest(engine=engine, action=action), tempfile.TemporaryDirectory() as temporary:
                    root = pathlib.Path(temporary)
                    result = invoke(root, RUNTIME + r'''
container_engine="$1"
create_original "$container_engine" "$3"
lifecycle_container demo "$2" "$manifest" "$original"
''', PARTS, (engine, action, payload()), CURRENT_POSTCONDITION)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertIn('MANAGED_FIXTURE_CURRENT=original\n', result.stdout)
                    actual = commands(root)
                    if engine == 'docker':
                        wanted = (['docker', 'update', '--restart', 'unless-stopped' if action == 'enable' else 'no', 'windowstolinux-demo']
                                  if action in ('enable', 'disable') else ['docker', action, 'windowstolinux-demo'])
                        self.assertIn(wanted, actual)
                    elif action in ('enable', 'disable'):
                        self.assertIn(['systemctl', 'daemon-reload'], actual)
                        autostart = root / 'quadlets/windowstolinux-demo.container.d/10-windowstolinux-autostart.conf'
                        self.assertEqual(action == 'enable', autostart.exists())
                        if autostart.exists():
                            self.assertEqual('[Install]\nWantedBy=multi-user.target\n', autostart.read_text())
                    else:
                        self.assertIn(['systemctl', action, 'windowstolinux-demo.service'], actual)

    def test_on_demand_rejects_mutations_after_actual_current_and_maintenance_checks(self):
        for engine in ('docker', 'podman'):
            with self.subTest(engine=engine), tempfile.TemporaryDirectory() as temporary:
                result = invoke(temporary, RUNTIME + r'''
container_engine="$1"
create_original "$container_engine" "$2"
lifecycle_container demo start "$manifest" "$original"
''', PARTS, (engine, payload(mode='ON_DEMAND')), CURRENT_POSTCONDITION)
                self.assertEqual(64, result.returncode, result.stdout + result.stderr)
                self.assertIn('MANAGED_FIXTURE_CURRENT=original\n', result.stdout)
                self.assertIn('application-lifecycle-not-applicable', result.stderr)
                self.assertEqual([], commands(temporary))

    def test_start_container_release_builds_actual_docker_argv_and_podman_quadlet(self):
        for engine in ('docker', 'podman'):
            with self.subTest(engine=engine), tempfile.TemporaryDirectory() as temporary:
                root = pathlib.Path(temporary)
                result = invoke(root, RUNTIME + r'''
container_engine="$1"
release="$(app_root demo)/releases/$original"
mkdir -p "$release/source" "$fixture/config/demo/$manifest" "$data_root/demo/files/files" "$base_root/storage/demo"
printf CONFIG=approved >"$fixture/config/demo/$manifest/container.env"
printf user-data >"$data_root/demo/files/files/value"
printf 'FILE:DEFAULT:-\n' >"$base_root/storage/demo/files"
parse_deployment_inputs identity-v2 CONTAINER_NON_ROOT "$2" "$manifest" 0 demo demo 1 files /data rw FILE DEFAULT - - - - -
parse_managed_data_bindings "${deployment_remaining_arguments[@]}"
parse_container_parameters "$container_engine" 1 tcp 127.0.0.1 8080 8080 0
start_container_release demo "$original" "$manifest"
''', PARTS, (engine, payload(endpoint=True)))
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(b'user-data', (root / 'data/demo/files/files/value').read_bytes())
                actual = commands(root)
                if engine == 'docker':
                    create = next(row for row in actual if row[:2] == ['docker', 'create'])
                    # Paths are obtained from the emitted argv; all non-path options remain exact.
                    config = create[create.index('--env-file') + 1]
                    volume = create[create.index('--volume') + 1]
                    self.assertEqual(['docker', 'create', '--name', 'windowstolinux-demo', '--restart', 'unless-stopped',
                                      '--user', '1000:1000', '--security-opt', 'no-new-privileges', '--cap-drop', 'ALL',
                                      '--read-only', '--tmpfs', '/tmp:rw,nosuid,nodev,mode=1777', '--tmpfs', '/run:rw,nosuid,nodev,mode=755',
                                      '--entrypoint', '/app/main', '--env-file', config, '--publish', '127.0.0.1:8080:8080/tcp',
                                      '--volume', volume, 'windowstolinux-demo:' + 'a' * 64, 'two words', 'literal$arg'], create)
                    self.assertTrue(config.endswith('/config/demo/' + 'c' * 64 + '/container.env'))
                    self.assertTrue(volume.endswith('/data/demo/files/files:/data:rw,Z'))
                    self.assertIn(['docker', 'start', 'windowstolinux-demo'], actual)
                else:
                    quadlet = (root / 'quadlets/windowstolinux-demo.container').read_text()
                    config = next(line.split('=', 1)[1] for line in quadlet.splitlines() if line.startswith('EnvironmentFile='))
                    volume = next(line.split('=', 1)[1] for line in quadlet.splitlines() if line.startswith('Volume='))
                    self.assertTrue(config.endswith('/config/demo/' + 'c' * 64 + '/container.env'))
                    self.assertTrue(volume.endswith('/data/demo/files/files:/data:rw,Z'))
                    self.assertEqual('[Container]\nImage=windowstolinux-demo:' + 'a' * 64
                                     + '\nContainerName=windowstolinux-demo\nEnvironmentFile=' + config
                                     + '\nEntrypoint="/app/main"\nExec="two words" "literal$$arg" '
                                     + '\nUser=1000:1000\nNoNewPrivileges=true\nDropCapability=all'
                                     + '\nPublishPort=127.0.0.1:8080:8080/tcp\nReadOnly=true'
                                     + '\nTmpfs=/tmp:rw,nosuid,nodev,mode=1777\nTmpfs=/run:rw,nosuid,nodev,mode=755'
                                     + '\nVolume=' + volume + '\n\n[Service]'
                                     + '\nExecStartPost=/usr/local/lib/windowstolinux/managed-helper podman-cni-forward demo ' + 'c' * 64
                                     + '\nExecStopPost=/usr/local/lib/windowstolinux/managed-helper podman-cni-clear demo ' + 'c' * 64 + '\n', quadlet)
                    self.assertIn(['systemctl', 'restart', 'windowstolinux-demo.service'], actual)

    def test_publish_container_seals_source_and_switches_current_only_after_image_identity(self):
        candidate_id = 'demo-dddddddddddddddd'
        for engine in ('docker', 'podman'):
            for scenario in ('exact', 'label-drift'):
                with self.subTest(engine=engine, scenario=scenario), tempfile.TemporaryDirectory() as temporary:
                    root = pathlib.Path(temporary)
                    tag = image_archive(root, engine, candidate_id)
                    encoded = payload()
                    result = invoke(root, RUNTIME + r'''
container_engine="$1"
publication_scenario="$2"
publish_candidate_id=demo-dddddddddddddddd
deployer=fixture
candidate_path="$(candidate_root "$publish_candidate_id")"
# Device and mount queries are the OS boundary; assert_workspace_volume/sealed checks stay actual.
losetup() {
    [ "$#" = 5 ] && [ "$1" = --noheadings ] && [ "$2" = --raw ] && [ "$3" = --output ] \
        && [ "$4" = BACK-FILE ] && [ "$5" = /dev/loop7 ] || reject fixture-loop-query
    log_command losetup "$@"
    printf '%s' "$candidate_path/.workspace.ext4"
}
findmnt() {
    if [ "$#" = 3 ] && [ "$1" = -rn ] && [ "$2" = -o ] && [ "$3" = TARGET ]; then
        log_command findmnt "$@"
        # The only controlled nested mount is the sealed candidate workspace, outside managed data.
        printf '/\n%s\n' "$candidate_path/mutable"
        return
    fi
    [ "$#" = 5 ] && [ "$1" = -rn ] && [ "$2" = -M ] && [ "$3" = "$candidate_path/mutable" ] \
        && [ "$4" = -o ] || reject fixture-mount-query
    log_command findmnt "$@"
    case "$5" in SOURCE) printf /dev/loop7 ;; FSTYPE) printf ext4 ;; OPTIONS) printf ro,nodev,nosuid ;;
        *) reject fixture-mount-property ;; esac
}
create_original "$container_engine" "$3"
mkdir -p "$data_root/demo/files/files" "$base_root/storage/demo"
printf user-data >"$data_root/demo/files/files/value"
printf 'FILE:DEFAULT:-\n' >"$base_root/storage/demo/files"
publish_container demo "$publish_candidate_id" "$candidate" "$manifest" identity-v2 CONTAINER_NON_ROOT "$3" "$manifest" 0 demo demo 1 files /data rw FILE DEFAULT - - - - - "$container_engine" 0 0
''', PUBLISH_PARTS, (engine, scenario, encoded), r'''
if [ "$2" = exact ]; then digest=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb;
else digest=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; fi
current="$applications_root/demo/current"
release="$applications_root/demo/releases/$digest"
[ -L "$current" ] && [ "$(readlink -f -- "$current")" = "$release" ] || exit 97
[ "$(cat "$current/.windowstolinux-owner")" = cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc ] || exit 97
[ "$(cat "$data_root/demo/files/files/value")" = user-data ] || exit 97
printf 'MANAGED_FIXTURE_PUBLISH_CURRENT=%s\n' "$2"
''', publication_adapters=True)
                    self.assertEqual(0 if scenario == 'exact' else 64, result.returncode,
                                     result.stdout + result.stderr)
                    self.assertIn('MANAGED_FIXTURE_PUBLISH_CURRENT=' + scenario + '\n', result.stdout)
                    self.assertEqual(b'user-data', (root / 'data/demo/files/files/value').read_bytes())
                    actual = commands(root)
                    loads = [row for row in actual if row[:2] == [engine, 'load']]
                    self.assertEqual(1, len(loads))
                    self.assertEqual([engine, 'load', '--input'], loads[0][:-1])
                    self.assertTrue(loads[0][-1].endswith('/work/' + candidate_id + '/mutable/candidate-image.tar'))
                    release = root / 'apps/demo/releases' / ('b' * 64)
                    tags = [row for row in actual if row[:2] == [engine, 'tag']]
                    if scenario == 'label-drift':
                        self.assertIn('container-image-owner', result.stderr)
                        self.assertNotIn('PUBLISHED=1', result.stdout)
                        self.assertEqual([], tags)
                        self.assertFalse(release.exists())
                        continue
                    self.assertIn('PUBLISHED=1\n', result.stdout)
                    self.assertEqual([[engine, 'tag', tag, 'windowstolinux-demo:' + 'b' * 64]], tags)
                    self.assertEqual(b'controlled source executable\n', (release / 'source/main').read_bytes())
                    self.assertEqual('c' * 64 + '\n', (release / '.windowstolinux-owner').read_text())
                    self.assertEqual(engine + '\n', (release / '.windowstolinux-container-engine').read_text())
                    self.assertEqual('sha256:' + 'f' * 64, (release / '.windowstolinux-container-image-id').read_text())
                    parameters = ['identity-v2', 'CONTAINER_NON_ROOT', encoded, 'c' * 64, '0', 'demo', 'demo', '1',
                                  'files', '/data', 'rw', 'FILE', 'DEFAULT', '-', '-', '-', '-', '-', engine, '0', '0']
                    self.assertEqual('\n'.join(parameters) + '\n',
                                     (release / '.windowstolinux-container-parameters').read_text())
                    self.assertEqual(b'controlled source executable\n',
                                     (root / 'work' / candidate_id / 'mutable/source/main').read_bytes())
                    if os.name == 'nt':
                        metadata = [[value.decode() for value in row.split(b'\0')[:-1]]
                                    for row in (root / 'posix-metadata').read_bytes().splitlines()]
                        self.assertTrue(any(row[:4] == ['setfacl', '-R', '-b', '--']
                                            and row[-1].endswith('/apps/demo/releases/' + 'b' * 64 + '/source')
                                            for row in metadata))

    def test_rollback_restores_original_release_and_never_reseeds_persistent_data(self):
        for engine in ('docker', 'podman'):
            for originally_running in ('0', '1'):
                with self.subTest(engine=engine, originally_running=originally_running), tempfile.TemporaryDirectory() as temporary:
                    root = pathlib.Path(temporary)
                    result = invoke(root, RUNTIME + r'''
container_engine="$1"
mkdir -p "$data_root/demo/files/files" "$base_root/storage/demo"
printf user-data >"$data_root/demo/files/files/value"
printf 'FILE:DEFAULT:-\n' >"$base_root/storage/demo/files"
create_original "$container_engine" "$3" 1
release="$(app_root demo)/releases/$original"
sha256sum -- "$release/.windowstolinux-container-parameters" | awk '{print $1}' >"$fixture/original-parameters.sha256"
token=dddddddd-dddd-dddd-dddd-dddddddddddd
snapshot="$(snapshot_root demo "$token")"
mkdir -p "$snapshot" "$(app_root demo)/releases/$candidate/source"
cp "$release/.windowstolinux-container-parameters" "$snapshot/container-parameters"
printf '%s' "$release" >"$snapshot/current-path"
printf '%s' "$2" >"$snapshot/runtime"
printf no >"$snapshot/autostart"
printf '%s' "$manifest" >"$(app_root demo)/releases/$candidate/.windowstolinux-owner"
ln -sfnT -- "$(app_root demo)/releases/$candidate" "$(app_root demo)/current"
application_maintenance demo
rollback_container demo "$candidate" "$manifest" "$token"
''', PARTS, (engine, originally_running, payload()), CURRENT_POSTCONDITION + r'''
[ "$(cat "$data_root/demo/files/files/value")" = user-data ] || exit 97
[ "$(sha256sum -- "$release/.windowstolinux-container-parameters" | awk '{print $1}')" = "$(cat "$fixture/original-parameters.sha256")" ] || exit 97
''')
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertIn('MANAGED_FIXTURE_CURRENT=original\n', result.stdout)
                    self.assertFalse((root / 'apps/demo/releases' / ('b' * 64)).exists())
                    self.assertFalse((root / 'snapshots/demo-dddddddd-dddd-dddd-dddd-dddddddddddd').exists())
                    self.assertEqual(b'user-data', (root / 'data/demo/files/files/value').read_bytes())
                    self.assertEqual(originally_running == '0', (root / 'stopped').exists())


if __name__ == '__main__':
    unittest.main()
