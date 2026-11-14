package gold.debug.windowstolinux.mod.deployment.linux.helper;

import java.util.List;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayoutTemplate;

/** Version-bound effects of trusted product code, never an execution permission. / 绑定版本的可信产品代码副作用，不产生执行许可。 */
public final class DeploymentHelperReviewContract {
    /** Reviewed contract revision. / 已审阅契约修订。 */
    public static final String REVISION = "managed-helper-review.v2";

    /** Independently pinned helper source identity. / 独立固定的 helper 源码身份。 */
    private static final String HELPER_SHA = "465dfa7284068239e65f34ad8d927a2b67209fa9ab7a0535038304f75dc37466";

    /** Independently pinned non-root build entry identity. / 独立固定的非 root 构建入口身份。 */
    private static final String BUILD_SHA = "516062e3d3dba347f7eb7057ae66e36d5fc2ff332ed75eb0ed3e568a92a75496";

    /** Exact public dispatch verbs covered by this source-bound contract. / 本源码绑定契约覆盖的精确公开分发动作。 */
    private static final java.util.Set<String> VERBS = java.util.Set.of(
        "application-maintenance", "application-health", "app-run", "app-client", "probe", "workspace-recover",
        "candidate-create", "candidate-create-task", "candidate-query-task", "candidate-cleanup-task", "candidate-restore-create",
        "agent-open", "agent-read", "agent-patch", "agent-run", "agent-seal", "build-preflight", "build-run", "build-freeze",
        "build-identity", "build-stop", "candidate-cleanup", "stage-config", "stage-secret", "native-db", "database-inspect",
        "database-export", "database-stage-artifact", "database-read-artifact", "database-discard-artifact", "database-restore-candidate",
        "database-commit-candidate", "database-recover-candidate", "database-discard-candidate", "handoff-database-export",
        "handoff-database-status", "handoff-database-discard", "backup-create", "backup-read", "backup-discard", "handoff-backup-status",
        "handoff-maintenance-status", "handoff-operation-close", "handoff-operation-close-status", "handoff-restore-preflight",
        "handoff-restore-create", "handoff-restore-file", "handoff-restore-step", "handoff-restore-step-status", "handoff-restore-discard",
        "handoff-restore-discard-status", "handoff-restore-cleanup", "handoff-restore-cleanup-status", "restore-preflight", "restore-prepare",
        "restore-application-health", "restore-start-candidate", "restore-stop-candidate", "restore-mark-quiesced", "restore-quiesce-recovery",
        "restore-snapshot", "restore-start-formal", "restore-recover", "activate-retained", "rollback-retained", "finish-retained",
        "retained-status", "managed-current-status", "managed-target-status", "snapshot-retained", "snapshot-deployment",
        "runtime-settings-preview", "runtime-settings-derive", "runtime-settings-status",
        "snapshot-deployment-attempt", "snapshot-container-attempt", "publication-original-status", "publication-original-status-attempt",
        "snapshot-discard-task", "snapshot-discard-attempt", "publish-deployment", "prepare-toolchains", "retain", "rollback-deployment",
        "rollback-deployment-first", "inspect-runtime", "lifecycle-deployment", "observe-deployment", "snapshot-container", "publish-container",
        "rollback-container", "rollback-container-first", "lifecycle-container", "podman-cni-forward", "podman-cni-clear");

    /** Fixed common prefix; source ownership guarantees do not authorize any proposed target. / 固定公共前缀，源码所有权保证不授权任何拟议目标。 */
    private static final String TEMPLATE = """
        managed-helper-review.v2; protocol 16.
        Helper @wtl:helper-directory@/managed-helper SHA256 %s; build-entry @wtl:helper-directory@/build-entry SHA256 %s.
        Only these locally checked, independently pinned sources are platform code.
        Installed calls require the exact pre-invocation guard: root-owned non-symlink helper/parents, no group/world write, exact SHA256. Review commands before/after; no trust for mutations/replacements, sourced code or other executables.
        The pinned helper starts through its absolute #!/bin/bash -p entry. Bash suppresses startup imports at that entry. This does not clear the entire inherited environment or constrain SSH server startup and later child processes.
        Each systemd isolation preflight starts the absolute systemd-run client from / with only fixed PATH and LC_ALL, and sets the unit working directory to /. Inside the transient unit, env -i starts the fixed true or container user-namespace check with the same PATH and LC_ALL. This bounds the environment of those invocations while relying on the installed system manager and SSH environment.
        Standard build-run parameters, in order (9): application,candidate,seconds,max processes,memory MiB,max output,engine,script digest,encoded tool binding. Its fixed Python collector verifies the complete executable stdin digest and forwards this helper verb, these exact arguments and that stdin. Original task/workflow authority and captured archive/source association are separate current review evidence; match them independently. The third and fourth standard arguments are execution limits.
        Agent agent-run parameters, in order (9): application,candidate,task,source revision,seconds,max output,engine,script digest,encoded tool binding. It checks source before/after executable stdin, exact prepared binding and build-entry non-root isolation, then invokes the standard restricted build with its own fixed process/memory limits. "-" means no catalog tools, never another binding. agent-patch validates UTF8 revision-bound edits, freezes the task copy and returns its tree digest; promotion requires matching the client prediction.
        Management/candidate ownership requires root; require_deployer accepts SUDO_USER=root or root when unset/empty, except internal Podman CNI hooks. Projects run as restricted non-root. Validate typed ids/digests/paths/manifests/operations; reject unknowns/drift/unsafe paths. No mutable-state proof: match arguments/bindings/resourceTargets and review initialization/selected effects.
        Interface: managed-helper VERB literal args plus framed opaque data or specified executable stdin. Labels/hashes grant no permission.
        The pinned prepare_official_toolchains wrapper changes to / in a subshell and invokes /usr/bin/python3 -I -S -B - "$@" 3<&0 <<'WTL_OFFICIAL_TOOLCHAINS'. The shell copies the original input to fd 3 before supplying the fixed Python source on fd 0. JSON request verbs take canonical JSON on the outer helper's stdin. The managed or SYSTEM Python version and standard-library import probes also run from / with -I -S -B. Installation commands with an explicit source directory retain that directory.
        For tasks with a frozen mirror snapshot, an exact task/server/policy-bound bootstrap may surround preparation commands or executable build stdin. Its complete actual bytes and decoded shipped mirror runtime (inline mirrorTransport.runtimeSource, exact snapshot-bound evidence pages, or its validated runtimeSourcePointer to the complete same-request installer source) and frozenPolicy remain review input; a helper pin alone does not approve this bootstrap, its endpoints, task-private files, temporary CA, local gateways or source-credential staging. The wrapper preserves original opaque stdin and helper integrity guards, uses the original deadline, and cleans task-owned transport material only at the corresponding confirmed boundary. Typed transfers use the frozen ordered sources, preserve resolved versions and expected artifact identity, and retain native signatures/archive checks. "Official" below identifies vendor release semantics, not a requirement to contact the vendor when a mirror policy is present. Metadata lacking a required version may advance before artifact identity is fixed; installation, build, permission and publication errors cannot replay the whole operation. Git and arbitrary project network scripts are outside URL rewriting; explicit unconstrainable single-source paths reject before execution. Credentials remain opaque and task-scoped; this contract and mirror receipts grant no authorization.
        runtime-settings-preview validates the exact current native ownership/release and parses a replacement structured application payload, ordinary configuration digest and port without activating it; it returns base64 before/after rendered launch commands and the complete replacement parameter hash. runtime-settings-derive takes application, old/new component release digests, ownership, original task, structured application payload, configuration digest, port, reviewed parameter hash and whole-application intent digest. Under the application locks it checks exact current and inactive maintenance, immutable existing configuration/secret inputs and changed ports with ss (a point-in-time check, never a reservation), copies only the sealed old tree without following symlinks, replaces its typed parameters, binds original task/source/recipe/intent, and atomically publishes the new inactive release tree. It never builds source, imports databases, resets data, changes DNS/TLS, activates a runtime or treats model text as shell. runtime-settings-status only checks that exact derived tree/origin/recipe, or proves the expected source remains current with no target/temporary/snapshot/maintenance. An absent result cannot settle an in-flight unknown command. Existing snapshot-retained/activate-retained/rollback-retained/finish-retained perform activation and preserve the original running/autostart state; a settings derivation receipt is not an activation or health receipt.
        probe prints protocol only; candidate-query-task, retained-status, managed-current-status, managed-target-status, publication-original-status(-attempt), inspect-runtime and observe-deployment inspect the named managed state. application-health checks the named runtime. build-preflight takes one engine (ordinary, ordinary-offline, docker or podman) with empty stdin and checks host prerequisites in a transient unit. ordinary-offline additionally probes the separate network namespace and failed socket creation. Application and source identities come from the original workflow.
        Publication calls may prefix the literal helper invocation with WTL_SYSTEMD_QUERY_SECONDS=N (canonical decimal 1..7200), copied from that actual CommandRequest timeout. The complete prefix remains in the reviewed command and exact action digest; typed argument decoding removes only this validated budget prefix. It grants no permission and never increases the original transport timeout. Native systemd show observations share one helper-entry deadline, including at most three read attempts with 1 then 2 second waits. Failed or unavailable queries exit 75 with MANAGED_HELPER_OBSERVATION_UNAVAILABLE=systemd-query and remain UNKNOWN, never an identity mismatch. Valid FragmentPath/drop-in/unit-byte conflicts still reject. Only read-only queries retry; start/stop/restart are never replayed by this mechanism.
        candidate-create(-task) and candidate-restore-create create task-owned candidates/metadata. Pinned initialise_controlled_roots checks ancestry symlink/owner/write, validates existing roots and initializes managed roots; task creation is exclusive and binds its task marker. workspace-recover inventories existing candidates and reports retention without creating roots, stopping services, unmounting volumes or deleting candidates. candidate-cleanup(-task) removes only matched owned candidates. build-identity prepares restricted identity; build-stop stops its build; build-freeze seals output. agent-open/read/patch/seal operate on that candidate; patch stdin is source-edit data, never shell evaluation.
        build-run/agent-run execute full stdin via protected hash-checked build-entry bound to task/candidate/input/binding/engine and time/CPU/memory/output limits. Ordinary builds use non-root systemd isolation: ProtectSystem=strict, candidate writable paths, no capabilities, NoNewPrivileges. ordinary-offline also applies the preflight's network restrictions to the build unit. Rootless containers use engine-required user namespaces. The root-owned start marker is reset only while the unit is unloaded and claimed exclusively by ExecStartPre to prevent restart replay. Review complete executable stdin including hooks and dependencies. Arbitrary SSH/model host commands are outside these bounds.
        prepare-toolchains bind/verify enforce canonical bindings, live directory/marker ownership, exact version/source/digest and SYSTEM rehashing before sealing; missing/drifted installations fail. Metadata probes do not imply project imports/hooks.
        prepare takes ecosystem/catalog branch/exact-or-"-"/30..7200 seconds/guarded Java-or-"-", no caller URL/path/script. Official resolvers bind artifact checksums; HTTPS/redirects enforce host and size limits. Reject archive traversal, external/cyclic links, devices, duplicates and expansion overflow. Version directories enforce ancestor owner/non-write/symlink guards. SYSTEM reuse requires exact probing/pinning/rehashing/protected wrappers. Python/PHP/Ruby can install fixed apt/dnf dependencies and run official configure/make/install. For Rust the fixed receiver copies validated version-3 component data and probes managed rustc as non-root, without invoking vendor install.sh. Review selected prerequisites, transport, effects, and exact original authority.
        prepare-toolchains prepare-tracked/bind-tracked/observe-tracked/cleanup-tracked take original operation UUID/request SHA and bounded strict JSON data preserved on descriptor 3; the Python source occupies stdin. Root-owned operations/<UUID> bind original task/execution/server/fingerprint/layout/business intent and raw request bytes. Selected release/version/source are durable before installation; tracked subprocess groups inherit the preparation lock and retain PID/start evidence. Binding publication is atomic and never overwrites different retained bytes. Observe validates only the original selection/marker/binding without resolving, installing, resealing or rerunning it; unavailable/busy evidence stays unknown. Cleanup holds the exclusive preparation lock, rejects active original process groups, and removes only proven original work/temporary binding/unreferenced incomplete owned installation. Complete shared installations/bindings and system dependency effects are retained and reported. Original execution status and current effect proof are separate; cleanup or a verified current binding does not manufacture an original successful exit or authorize a new build.
        prepare-toolchains prepare-diagnosed/diagnose-only take an original operation UUID, exact request SHA and canonical UTF-8 JSON on descriptor 3, binding operationId/taskId/serverId/fingerprintSha256/planRevision/remoteLayout and all six original prepare arguments. prepare-diagnosed exclusively creates root-owned toolchains/diagnostics/<operation>/request.json and diagnostic.json before preparation; an existing operation cannot be prepared again. The attachment keeps original preparation status and the last actual installation command stage/executable/exit code, timeout/cancellation flags, and redacted stdout/stderr tails each at most 16 KiB with independent truncation flags. A missing exit remains null, never zero. The original TOOLCHAIN/TOOLCHAIN_FAILURE stdout protocol is unchanged; helper wrapper exit and actual installer exit are separate facts. diagnose-only requires new independent review and a one-use host grant, checks original exact request bytes, protected-file ownership and all identity fields, and only reads that attachment. It never replays preparation or changes the original outcome. Command argv and environment are not exported.
        prepare-diagnosed writes only its exclusive diagnostic intent before waiting for the same validated .prepare.lock used by native preparation. Lock type, owner and write permissions are checked on the opened descriptor. Exclusive preparation covers shared version setup and installation; installation and probe children inherit that descriptor. An existing version directory without a valid protected completion marker is rejected and retained. Automatic installation cleanup is limited to a directory created exclusively by the current invocation whose identity still matches.
        stage-config(app,configuration-id,format,payload-sha,bytes) allows empty data; stage-secret(app,id,revision,payload-sha,bytes) forbids it. Both check each existing directory before descending, create only an absent immediate child, and publish checked immutable bytes with an atomic no-replace link; existing files must match. Neither has a task argument. native-db inspect/target/status read instances; start/install/prepare/initialize/resume may install packages, create/start services, initialize databases or resume matched operations. Database export/stage/read/discard use bound artifacts; restore/commit/recover/discard-candidate operate on owned candidates.
        Build identity setup may copy the exact reviewed WTL_MIRROR_BINDING task/server/policy credential directory to the isolated build's private temporary directory. Existing task candidates must match their protected task marker. Values remain opaque files and are never executable inputs; the authorized package-manager transport consumes them and build freeze removes the copies after the cgroup stops. Project code in that same build identity shares its file access boundary; this is not secret isolation from untrusted project hooks.
        snapshot-deployment/container(app,owner-manifest[,task]): derive release path; check ownership/absence. Absent: PREVIOUS=0; else capture protected prior runtime. snapshot-*/backup-create make protected artifacts; *-read/status inspect and *-discard remove matched artifacts. publish-deployment/container stage/activate exact releases, maintenance/operation metadata and runtime/service/container/network bindings. rollback-*/retained activation restore/switch matched releases; retain/finish-retained trim owned old releases. application-maintenance controls its lock. lifecycle-* start/stop/restart service/container or set autostart. app-run/client execute managed workloads/clients under runtime identity, not metadata queries.
        restore-*/handoff-* validate app/task/operation before the named snapshot, file staging, database/app stop/start, activation, reconciliation, discard or cleanup. Status/preflight inspect prerequisites; review exact supplied restore steps and host effects. Podman CNI forward/clear affect only exact engine forwarding rules.
        Receipt/ownership/resource/time guards apply. Review input/prerequisites/user authority. Helper/build-entry installation/replacement requires full installer/embedded source, not installed-call trust. Deny missing boundaries/input.
        """;

    private static final String TEXT = DeploymentRemoteLayoutTemplate.render(TEMPLATE.formatted(HELPER_SHA, BUILD_SHA), DeploymentRemoteLayout.original());

    /** Prevents utility construction. / 防止构造工具类。 */
    private DeploymentHelperReviewContract() {
    }

    /** Returns the contract only after packaged identities match its independently pinned source. / 打包身份匹配独立固定源码后才返回契约。
     * @return stable trusted policy text / 稳定可信策略文本 */
    public static String text() {
        requireIdentity(DeploymentManagedHelperBundle.PROTOCOL_VERSION, DeploymentManagedHelperBundle.EXPECTED_SHA256,
            DeploymentManagedHelperBundle.buildEntrySha256());
        DeploymentManagedHelperBundle.renderScript();
        return TEXT;
    }

    /** Checks the complete integrity guard and literal helper entry before omitting fixed source. / 省略固定源码前核验完整守卫和字面量 helper 入口。
     * @param script actual complete guarded request / 实际完整受保护请求
     * @return canonical invocation after any exact budget metadata / 去除精确预算元数据后的规范调用正文 */
    public static String text(DeploymentRemoteLayout layout) {
        text();
        return DeploymentRemoteLayoutTemplate.render(TEMPLATE.formatted(DeploymentManagedHelperBundle.helperSha256(layout),
            DeploymentManagedHelperBundle.buildEntrySha256(layout)), layout);
    }

    /** Retains the pinned entrypoint, mirror and toolchain boundaries for the fixed toolchain review. */
    public static String toolchainText(DeploymentRemoteLayout layout) {
        return selectedText(layout, List.of("managed-helper-review.", "Helper ", "Only these ", "Installed calls ",
            "The pinned helper starts ", "Management/candidate ownership ", "Interface: ", "The pinned prepare_official_toolchains ",
            "For tasks with a frozen mirror snapshot", "prepare-toolchains bind/verify ", "prepare takes ",
            "prepare-toolchains prepare-diagnosed/", "prepare-diagnosed writes ", "Receipt/ownership/"));
    }

    /** Source-bound review contract for the literal bind and its selected receiver. */
    public static String bindingText(DeploymentRemoteLayout layout) {
        return selectedText(layout, List.of("managed-helper-review.", "Helper ", "Only these ", "Installed calls ",
            "The pinned helper starts ", "Management/candidate ownership ", "Interface: ", "The pinned prepare_official_toolchains ",
            "prepare-toolchains bind/verify ", "Receipt/ownership/"));
    }

    /** The exact native publication observation uses the complete guarded command and this pinned read contract. */
    public static String nativeObservationText(DeploymentRemoteLayout layout) {
        return selectedText(layout, List.of("managed-helper-review.", "Helper ", "Only these ", "Installed calls ",
            "The pinned helper starts ", "Management/candidate ownership ", "Interface: "))
            + """

            inspect-runtime receives the application id and original ownership marker. It checks the managed current release and saved runtime identity before reporting kind, mode, and engine when applicable.
            observe-deployment receives the same two arguments. It validates the owned current release, typed saved runtime parameters and native unit identity. An ON_DEMAND release reports an installed service-free state. A DAEMON queries its own UnitFileState, running state and process metadata. These commands only read the guarded release, runtime and service state. They do not start, stop, enable, rebuild, publish or clean an application.
            The literal WTL_SYSTEMD_QUERY_SECONDS value comes from the same request's timeout. The helper uses one deadline for systemd read observations and at most three attempts. A missing or failed reply is unavailable, not proof of ownership or absent runtime. This static contract supplies neither a current observation nor an execution grant.
            """;
    }

    private static String selectedText(DeploymentRemoteLayout layout, List<String> prefixes) {
        var relevant = text(layout).lines().filter(line -> prefixes.stream().anyMatch(line::startsWith)).toList();
        if (relevant.size() != prefixes.size()) throw new IllegalStateException("Pinned toolchain review contract changed");
        return String.join("\n", relevant);
    }

    public static String verifiedInvocation(String script) {
        return verifiedInvocation(script, DeploymentRemoteLayout.original());
    }

    public static String verifiedInvocation(String script, DeploymentRemoteLayout layout) {
        text(layout);
        String body = DeploymentManagedHelperBundle.verifiedBody(script, layout);
        if (body.startsWith("WTL_SYSTEMD_QUERY_SECONDS=")) {
            var budget = java.util.regex.Pattern.compile("\\AWTL_SYSTEMD_QUERY_SECONDS=([1-9][0-9]{0,3}) "
                + "(?=" + java.util.regex.Pattern.quote("'" + layout.helperPath() + "' ") + ")").matcher(body);
            if (!budget.find() || Integer.parseInt(budget.group(1)) > 7200)
                throw new IllegalArgumentException("Noncanonical systemd query budget");
            body = body.substring(budget.end());
        }
        var matcher = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("'" + layout.helperPath() + "'")
            + "[ \\t]+(?:'([a-z][a-z0-9-]*)'|([a-z][a-z0-9-]*))(?=[ \\t\\r\\n]|$)").matcher(body);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String verb = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            if (!VERBS.contains(verb)) throw new IllegalArgumentException("Uncovered helper entrypoint");
        }
        if (!found) throw new IllegalArgumentException("No literal product helper invocation");
        return body;
    }

    /** Rejects stale contract/source pairings, including updates with an unchanged protocol. / 拒绝过期契约/源码配对，包括协议号不变的更新。
     * @param protocol helper protocol / helper 协议
     * @param helper helper digest / helper 摘要
     * @param build build entry digest / 构建入口摘要 */
    static void requireIdentity(int protocol, String helper, String build) {
        if (protocol != 16 || !HELPER_SHA.equals(helper) || !BUILD_SHA.equals(build))
            throw new IllegalArgumentException("Helper review contract identity changed");
    }
}
