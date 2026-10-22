package gold.debug.windowstolinux.mod.deployment.linux.helper;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayoutTemplate;

/** Version-bound effects of trusted product code, never an execution permission. / 绑定版本的可信产品代码副作用，不产生执行许可。 */
public final class DeploymentHelperReviewContract {
    /** Reviewed contract revision. / 已审阅契约修订。 */
    public static final String REVISION = "managed-helper-review.v2";

    /** Independently pinned helper source identity. / 独立固定的 helper 源码身份。 */
    private static final String HELPER_SHA = "bb1ecc2ae54d46263ef1eab31b40fd8967a7acf0a7a86c463b80eeaf7b217efd";

    /** Independently pinned non-root build entry identity. / 独立固定的非 root 构建入口身份。 */
    private static final String BUILD_SHA = "6eb9b24576e51e318044f67b1c77fc34b41c4c979cadfec1a5eb799c5122a91a";

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
        agent-run parameters, in order: application,candidate,task,source revision,seconds,max output,engine,script digest,encoded tool binding. It checks source before/after executable stdin, exact prepared binding and build-entry non-root isolation. "-" means no catalog tools, never another binding. agent-patch validates UTF8 revision-bound edits, freezes the task copy and returns its tree digest; promotion requires matching the client prediction.
        Management/candidate ownership requires root; require_deployer accepts SUDO_USER=root or root when unset/empty, except internal Podman CNI hooks. Projects run as restricted non-root. Validate typed ids/digests/paths/manifests/operations; reject unknowns/drift/unsafe paths. No mutable-state proof: match arguments/bindings/resourceTargets and review initialization/selected effects.
        Interface: managed-helper VERB literal args plus framed opaque data or specified executable stdin. Labels/hashes grant no permission.
        probe prints protocol only; candidate-query-task, retained-status, managed-current-status, managed-target-status, publication-original-status(-attempt), inspect-runtime and observe-deployment inspect the named managed state. application-health checks the named runtime. build-preflight checks actual prerequisites.
        candidate-create(-task), candidate-restore-create and workspace-recover create/recover task-owned candidates/metadata. Pinned initialise_controlled_roots checks ancestry symlink/owner/write, validates existing roots and initializes managed roots; task creation is exclusive and binds its task marker. candidate-cleanup(-task) removes only matched owned candidates. build-identity prepares restricted identity; build-stop stops its build; build-freeze seals output. agent-open/read/patch/seal operate on that candidate; patch stdin is source-edit data, never shell evaluation.
        build-run/agent-run execute full build/body via root-owned, non-writable, hash-checked build-entry bound to task/candidate/input/binding/engine and time/CPU/memory/output limits. Ordinary builds use non-root systemd isolation: ProtectSystem=strict, candidate writable paths, no capabilities, NoNewPrivileges. Rootless containers use engine-required user namespaces, not identical flags. Each explicit build invocation resets a root-owned start marker only while its unit is not loaded; ExecStartPre exclusively claims it, so a manager/service restart cannot run that invocation twice. Hooks/dependencies run within these bounds. Require complete executable stdin; arbitrary SSH/model host commands are not sandboxed by this.
        prepare-toolchains bind/verify enforce canonical bindings, live directory/marker ownership, exact version/source/digest and SYSTEM rehashing before sealing; missing/drifted installations fail. Metadata probes do not imply project imports/hooks.
        prepare takes ecosystem/catalog branch/exact-or-"-"/30..7200 seconds/guarded Java-or-"-", no caller URL/path/script. Official resolvers: Adoptium Linux/x64/JDK/HotSpot/Eclipse package SHA256, Node index+SHASUMS256, Go metadata+SHA256, Microsoft .NET metadata+SHA512. HTTPS/redirects enforce host allowlist/size caps; check official digests before extraction. Reject archive traversal, external/cyclic links, devices, duplicates and expansion overflow. Version/digest directories under @wtl:helper-directory@/toolchains/versions enforce ancestor owner/non-write/symlink guards. SYSTEM reuse requires exact-version probing/pinning/rehashing/protected wrappers/markers; otherwise install/probe/seal verified official artifacts. Python/PHP/Ruby can install fixed apt/dnf dependencies and run official configure/make/install; Rust can run verified install.sh. Review prerequisites/arguments/targets/goal authority. URL/digest/origin resolve during prepare; never invent them or allow arbitrary downloads.
        prepare-toolchains prepare-tracked/bind-tracked/observe-tracked/cleanup-tracked take original operation UUID/request SHA and bounded strict JSON data preserved on descriptor 3; the Python source occupies stdin. Root-owned operations/<UUID> bind original task/execution/server/fingerprint/layout/business intent and raw request bytes. Selected release/version/source are durable before installation; tracked subprocess groups inherit the preparation lock and retain PID/start evidence. Binding publication is atomic and never overwrites different retained bytes. Observe validates only the original selection/marker/binding without resolving, installing, resealing or rerunning it; unavailable/busy evidence stays unknown. Cleanup holds the exclusive preparation lock, rejects active original process groups, and removes only proven original work/temporary binding/unreferenced incomplete owned installation. Complete shared installations/bindings and system dependency effects are retained and reported. Original execution status and current effect proof are separate; cleanup or a verified current binding does not manufacture an original successful exit or authorize a new build.
        stage-config(app,configuration-id,format,payload-sha,bytes) allows empty data; stage-secret(app,id,revision,payload-sha,bytes) forbids it. Both stage app-scoped immutable data; neither has a task argument. native-db inspect/target/status read instances; start/install/prepare/initialize/resume may install packages, create/start services, initialize databases or resume matched operations. Database export/stage/read/discard use bound artifacts; restore/commit/recover/discard-candidate operate on owned candidates.
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
     * @return unchanged invocation body / 未更改调用正文 */
    public static String text(DeploymentRemoteLayout layout) {
        text();
        return DeploymentRemoteLayoutTemplate.render(TEMPLATE.formatted(DeploymentManagedHelperBundle.helperSha256(layout),
            DeploymentManagedHelperBundle.buildEntrySha256(layout)), layout);
    }

    public static String verifiedInvocation(String script) {
        return verifiedInvocation(script, DeploymentRemoteLayout.original());
    }

    public static String verifiedInvocation(String script, DeploymentRemoteLayout layout) {
        text(layout);
        String body = DeploymentManagedHelperBundle.verifiedBody(script, layout);
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
