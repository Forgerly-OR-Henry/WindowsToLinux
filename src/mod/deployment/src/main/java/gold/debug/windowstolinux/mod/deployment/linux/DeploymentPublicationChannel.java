package gold.debug.windowstolinux.mod.deployment.linux;

import gold.debug.windowstolinux.appapi.task.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.ActionTarget;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.InteractionService;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import static gold.debug.windowstolinux.mod.deployment.linux.DeploymentPublicationReceipt.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.appapi.linux.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.CommandResult;
import gold.debug.windowstolinux.appapi.linux.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.server.ServerRef;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretReference;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;

/** Sends fixed managed publication verbs through the original reviewed session. / 经原已审阅会话发送固定受管发布动作。 */
public final class DeploymentPublicationChannel {
    /** Borrowed authenticated connection. / 借用的已认证连接。 */
    private final RemoteSession session;

    /** Actual host review capability. / 真实宿主审核能力。 */
    private final InteractionService interaction;

    /** Exact saved server identity. / 已保存的精确服务器身份。 */
    private final ServerRef server;

    /** Original admitted task. / 原准入任务。 */
    private final TaskContext task;

    /** Reviewed plan identity, never a final release identity. / 已审阅计划身份，不是最终发布身份。 */
    private final String planRevision;

    /** Caller-owned check immediately before remote dispatch. / 远端发送紧前调用方持有的核对。 */
    private final DeploymentSendGuard beforeSend;

    /** Root-task and attempt evidence consumer. / 根任务与尝试证据使用方。 */
    private final DeploymentPublicationEvidenceChannel evidence;

    /** Binds one original publication session without taking ownership of it. / 绑定一份原发布会话而不接管其寿命。
     * @param session original connection / 原连接
     * @param interaction actual host review / 真实宿主审核
     * @param server exact reviewed server / 精确已审阅服务器
     * @param task original task / 原任务
     * @param planRevision original reviewed plan digest / 原已审阅计划摘要 */
    public DeploymentPublicationChannel(RemoteSession session, InteractionService interaction, ServerRef server,
                                        TaskContext task, String planRevision) {
        this(session, interaction, server, task, planRevision, DeploymentSendGuard.NONE);
    }

    /** Binds an additional caller-owned pre-dispatch check. / 绑定调用方附加的发送前核对。
     * @param session original connection / 原连接
     * @param interaction actual host review / 真实宿主审核
     * @param server exact reviewed server / 精确服务器
     * @param task original task / 原任务
     * @param planRevision reviewed plan digest / 已审阅计划摘要
     * @param beforeSend caller-owned check / 调用方持有的核对 */
    public DeploymentPublicationChannel(RemoteSession session, InteractionService interaction, ServerRef server,
                                        TaskContext task, String planRevision, DeploymentSendGuard beforeSend) {
        this.session = Objects.requireNonNull(session);
        this.interaction = Objects.requireNonNull(interaction);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        if (planRevision == null || !planRevision.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Reviewed plan identity required");
        this.planRevision = planRevision;
        this.beforeSend = Objects.requireNonNull(beforeSend);
        this.evidence = new DeploymentPublicationEvidenceChannel(task, this::send);
    }

    /** Creates one original helper rollback snapshot; an incomplete reply never means first deployment. / 创建一份原helper回滚快照；不完整回执绝不表示首次部署。
     * @param unit exact owned application / 精确所属应用
     * @param runtime reviewed runtime kind / 已审阅运行类型
     * @return original snapshot or retained uncertainty / 原快照或保留未知
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    public OperationResult<DeploymentRemoteReleaseSnapshot> snapshot(DeploymentUnitIdentity unit,
                                                                     DeploymentRuntimeDefinition runtime) throws ApiException {
        requireUnit(unit);
        return evidence.snapshot(unit, runtime, task.id(), Optional.empty(), false);
    }

    /** Creates one distinct attempt snapshot under the reviewed previous CURRENT. / 按已审阅旧CURRENT创建独立尝试快照。
     * @param unit exact owned unit / 精确所属单元
     * @param runtime reviewed runtime / 已审阅运行定义
     * @param operation distinct operation UUID / 独立操作UUID
     * @param expectedCurrent reviewed old digest or explicit empty / 已审阅旧摘要或明确为空
     * @return exact snapshot or uncertainty / 精确快照或未知
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    public OperationResult<DeploymentRemoteReleaseSnapshot> snapshot(DeploymentUnitIdentity unit,
                                                                     DeploymentRuntimeDefinition runtime, java.util.UUID operation, Optional<String> expectedCurrent) throws ApiException {
        requireUnit(unit);
        return evidence.snapshot(unit, runtime, operation, expectedCurrent, true);
    }

    /** Checks one distinct attempt snapshot against all confirmed previous facts. / 按全部已确认旧事实核对独立尝试快照。
     * @param unit exact owned unit / 精确所属单元
     * @param runtime reviewed runtime / 已审阅运行定义
     * @param operation original attempt operation / 原尝试操作
     * @param expectedCurrent confirmed old digest or explicit empty / 已确认旧摘要或明确为空
     * @param expectedKind confirmed old owner kind / 已确认旧归属类别
     * @param expectedRunning confirmed old activity / 已确认旧运行状态
     * @return original snapshot or retained uncertainty / 原快照或保留未知
     * @throws ApiException if original admission rejects / 原准入拒绝时 */
    public OperationResult<DeploymentRemoteReleaseSnapshot> snapshot(DeploymentUnitIdentity unit,
                                                                     DeploymentRuntimeDefinition runtime, java.util.UUID operation, Optional<String> expectedCurrent,
                                                                     String expectedKind, boolean expectedRunning) throws ApiException {
        Objects.requireNonNull(expectedCurrent);
        Objects.requireNonNull(expectedKind);
        if (expectedCurrent.isEmpty() && (!expectedKind.equals("none") || expectedRunning)
            || expectedCurrent.isPresent() && !Set.of("ordinary", "deployment", "container").contains(expectedKind))
            throw new IllegalArgumentException("Complete confirmed previous publication facts required");
        var observed = snapshot(unit, runtime, operation, expectedCurrent);
        if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty())
            return observed;
        var actual = observed.value().orElseThrow();
        if (!expectedKind.equals(actual.previousKind()) || expectedRunning != actual.previousRunning())
            return unknown("DEPLOYMENT_SNAPSHOT_PREVIOUS_CHANGED");
        return observed;
    }

    /** Reads only the original task's owned publication evidence without replaying an effect. / 仅读取原任务所属发布证据而不重放副作用。
     * @param unit original owned application / 原所属应用
     * @param candidateId original confirmed candidate / 原已确认候选
     * @param releaseSha256 exact attempted release, absent before publication identity exists / 精确尝试发布；发布身份未形成前为空
     * @param backend original persisted native or container backend / 原已持久原生或容器后端
     * @param engine original persisted container engine, or none / 原已持久容器引擎或none
     * @return complete original status or retained uncertainty / 完整原状态或保留未知
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    public OperationResult<DeploymentOriginalPublicationStatus> originalStatus(DeploymentUnitIdentity unit,
                                                                               String candidateId, Optional<String> releaseSha256, String backend, String engine) throws ApiException {
        requireUnit(unit);
        return evidence.originalStatus(unit, candidateId, releaseSha256, backend, engine, task.id(), false);
    }

    /** Reads the original root task and exact later operation without replay. / 读取原根任务及精确后续操作而不重放。
     * @param unit exact owned unit / 精确所属单元
     * @param candidateId original candidate / 原候选
     * @param releaseSha256 attempted release / 尝试发布
     * @param backend original backend / 原后端
     * @param engine original engine / 原引擎
     * @param operation exact operation UUID / 精确操作UUID
     * @return exact original evidence or uncertainty / 精确原证据或未知
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    public OperationResult<DeploymentOriginalPublicationStatus> originalStatus(DeploymentUnitIdentity unit,
                                                                               String candidateId, Optional<String> releaseSha256, String backend, String engine, java.util.UUID operation) throws ApiException {
        requireUnit(unit);
        return evidence.originalStatus(unit, candidateId, releaseSha256, backend, engine, operation, true);
    }

    /** Discards only a task-bound prepublish snapshot after read-only proof that old current is unchanged. / 只在只读证明旧当前版本未变后丢弃原任务发布前快照。
     * @param unit exact original owner / 精确原归属
     * @return verified discard or retained uncertainty / 已核实丢弃或保留未知
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    public OperationResult<Void> discardSnapshot(DeploymentUnitIdentity unit) throws ApiException {
        requireUnit(unit);
        return evidence.discardSnapshot(unit, task.id(), false);
    }

    /** Cleans only the exact root-owned attempt snapshot. / 仅清理精确根任务所属的尝试快照。
     * @param unit exact owned unit / 精确所属单元
     * @param operation exact operation UUID / 精确操作UUID
     * @return verified cleanup or uncertainty / 已核实清理或未知
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    public OperationResult<Void> discardSnapshot(DeploymentUnitIdentity unit, java.util.UUID operation) throws ApiException {
        requireUnit(unit);
        return evidence.discardSnapshot(unit, operation, true);
    }

    /** Stages one immutable normal configuration payload through the original approval. / 经原授权暂存一份不可变普通配置。
     * @param unit exact owned application / 精确所属应用
     * @param configurationSha256 reviewed configuration identity / 已审阅配置身份
     * @param format fixed systemd or container consumer / 固定 systemd 或容器使用方
     * @param payload rendered non-secret UTF-8 bytes / 已渲染非秘密 UTF-8 字节
     * @return confirmed immutable input or uncertainty / 已确认不可变输入或未知
     * @throws ApiException if admission rejects before dispatch / 发送前准入拒绝时 */
    public OperationResult<Void> stageConfiguration(DeploymentUnitIdentity unit, String configurationSha256,
                                                    String format, byte[] payload) throws ApiException {
        requireUnit(unit);
        if (configurationSha256 == null || !configurationSha256.matches("[0-9a-f]{64}")
            || !Set.of("systemd", "container").contains(format))
            throw new IllegalArgumentException("Reviewed configuration identity required");
        return stage(unit, "stage-config", "deployment.publication.config.v1",
            List.of(unit.id(), configurationSha256, format), payload, false);
    }

    /** Stages one original secret revision without retaining its plaintext. / 暂存一份原秘密修订而不留存其明文。
     * @param unit exact owned application / 精确所属应用
     * @param identifier original secret identifier / 原秘密标识
     * @param revision original immutable revision / 原不可变修订
     * @param plaintext temporarily borrowed platform buffer / 暂借平台明文缓冲区
     * @return verified digest only after exact remote staging / 仅精确远端暂存后返回已核实摘要
     * @throws ApiException if admission rejects before dispatch / 发送前准入拒绝时 */
    public OperationResult<String> stageSecret(DeploymentUnitIdentity unit, String identifier, long revision,
                                               byte[] plaintext) throws ApiException {
        requireUnit(unit);
        if (identifier == null || !identifier.matches("[a-z0-9][a-z0-9._-]{0,63}") || revision < 1)
            throw new IllegalArgumentException("Original secret revision required");
        Objects.requireNonNull(plaintext);
        if (plaintext.length == 0)
            throw new IllegalArgumentException("Empty secret cannot be staged");
        var digest = sha256(plaintext);
        var observed = stage(unit, "stage-secret", "deployment.publication.secret.v1",
            List.of(unit.id(), identifier, Long.toString(revision)), plaintext, true);
        return observed.outcome() == Outcome.SUCCEEDED ? success(digest) : withoutValue(observed);
    }

    /** Sends one bounded immutable input and accepts only its exact helper acknowledgement. / 发送有界不可变输入且仅接受精确helper确认。
     * @param unit exact target / 精确目标
     * @param verb fixed stage verb / 固定暂存动作
     * @param operation fixed approval semantic / 固定授权语义
     * @param prefix reviewed identifying arguments / 已审阅身份参数
     * @param payload copied by the platform request / 平台请求复制的载荷
     * @param nonempty whether zero bytes are forbidden / 是否禁止零字节
     * @return confirmed stage or unknown / 已确认暂存或未知
     * @throws ApiException if admission rejects before dispatch / 发送前准入拒绝时 */
    private OperationResult<Void> stage(DeploymentUnitIdentity unit, String verb, String operation, List<String> prefix,
                                        byte[] payload, boolean nonempty) throws ApiException {
        Objects.requireNonNull(payload);
        if (payload.length > 65536 || (nonempty && payload.length == 0))
            throw new IllegalArgumentException("Immutable input exceeds helper limit");
        var args = new java.util.ArrayList<>(prefix);
        args.add(sha256(payload));
        args.add(Integer.toString(payload.length));
        var observed = send(operation, unit, verb, args, Duration.ofSeconds(90), 4096, payload);
        if (observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (observed.outcome() == Outcome.CANCELLED || observed.outcome() == Outcome.UNKNOWN)
            return withoutValue(observed);
        if (!complete(observed)
            || !Set.of("INPUT_STAGED=1\n", "INPUT_PRESENT=1\n").contains(observed.value().orElseThrow().stdout()))
            return unknown("DEPLOYMENT_INPUT_UNCONFIRMED");
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Computes only a non-secret content identity. / 仅计算内容身份而不保留输入。
     * @param payload original payload / 原载荷
     * @return lower-case SHA-256 / 小写 SHA-256 */
    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("JDK SHA-256 unavailable", missing);
        }
    }

    /** Seals exactly one built candidate and accepts only the fixed completion receipt. / 封存恰好一个已构建候选且仅接受固定完成回执。
     * @param unit exact reviewed target / 精确已审阅目标
     * @param facts original analyzed facts, absent only for neutral managed process or container / 原分析事实，仅中立受管进程或容器可缺失
     * @param runtime reviewed runtime / 已审阅运行时
     * @param resources reviewed persistent bindings / 已审阅持久绑定
     * @param configurationSha256 staged configuration identity / 已暂存配置身份
     * @param secrets staged original secret revision digests / 已暂存原秘密修订摘要
     * @param candidateId exact confirmed candidate / 精确已确认候选
     * @param releaseSha256 content-derived remote release / 内容派生远端发布摘要
     * @param toolchainBindingSha256 exact installed tool binding / 精确安装工具绑定
     * @param hasTools whether the binding names installed tools / 绑定是否含已安装工具
     * @return confirmed publication or retained uncertainty / 已确认发布或保留未知
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前准入拒绝时 */
    public OperationResult<Void> publish(DeploymentUnitIdentity unit, DeploymentProjectFacts facts,
                                         DeploymentRuntimeDefinition runtime, DeploymentResourceBindings resources, String configurationSha256,
                                         Map<DeploymentSecretReference, String> secrets, String candidateId, String releaseSha256,
                                         String toolchainBindingSha256, boolean hasTools) throws ApiException {
        requireUnit(unit);
        Objects.requireNonNull(runtime);
        if (!(runtime instanceof DeploymentRuntimeDefinition.ManagedProcess)
            && !(runtime instanceof DeploymentRuntimeDefinition.Container))
            Objects.requireNonNull(facts);
        if (candidateId == null || !candidateId.matches("[a-z0-9][a-z0-9-]{0,62}-[0-9a-f]{16}") || releaseSha256 == null
            || !releaseSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact built candidate and release required");
        var values = new java.util.ArrayList<>(
            List.of(unit.id(), candidateId, releaseSha256, unit.ownershipManifestSha256(), "identity-v2",
                runtime.identityPolicy().name(), DeploymentWorkloadArguments.payload(runtime)));
        values.addAll(DeploymentPublicationArguments.inputs(configurationSha256, secrets));
        values.addAll(DeploymentPublicationArguments.content(unit.id(), resources));
        if (hasTools) {
            if (toolchainBindingSha256 == null || !toolchainBindingSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Exact installed tool binding required");
            values.add("tools-v1");
            values.add(toolchainBindingSha256);
        }
        if (runtime instanceof DeploymentRuntimeDefinition.Container container)
            values.addAll(DeploymentContainerArguments.from(container));
        else
            values.addAll(DeploymentRuntimeArguments.from(facts, runtime));
        var observed = send("deployment.publication.publish.v1", unit,
            runtime instanceof DeploymentRuntimeDefinition.Container ? "publish-container" : "publish-deployment",
            values, Duration.ofSeconds(120), 4096);
        if (observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (observed.outcome() == Outcome.CANCELLED || observed.outcome() == Outcome.UNKNOWN)
            return withoutValue(observed);
        if (observed.value().isEmpty() || observed.value().orElseThrow().truncated()
            || observed.value().orElseThrow().exitCode().isEmpty())
            return unknown("DEPLOYMENT_PUBLISH_UNCONFIRMED");
        if (observed.value().orElseThrow().exitCode().orElseThrow() != 0)
            return failure(ProblemCode.IO_FAILURE, "DEPLOYMENT_PUBLISH_FAILED");
        String output = observed.value().orElseThrow().stdout();
        final String marker = "PUBLISHED=1\n";
        int at = output.length() - marker.length();
        if (at < 0 || !output.endsWith(marker) || (at > 0 && output.charAt(at - 1) != '\n')
            || output.indexOf("PUBLISHED=") != at)
            return unknown("DEPLOYMENT_PUBLISH_RECEIPT_INVALID");
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Runs a reviewed component probe or independent whole-application gate against its owned unit. / 对受管单元运行已审阅组件探测或独立整应用门禁。
     * @param unit exact selected owner / 精确选定归属
     * @param check reviewed component or whole-application probe / 已审阅组件或整应用探测
     * @return confirmed health or its actual non-success / 已确认健康或实际非成功
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    public OperationResult<Void> healthCheck(DeploymentUnitIdentity unit, DeploymentHealthCheck check)
        throws ApiException {
        requireUnit(unit);
        Objects.requireNonNull(check);
        var observed = send("deployment.publication.health.v1", unit, "application-health",
            List.of(unit.id(), unit.ownershipManifestSha256(), DeploymentWorkloadArguments.healthPayload(check)),
            Duration.ofSeconds(check.timeoutSeconds() + 45L), 4096);
        if (observed.outcome() == Outcome.CANCELLED || observed.outcome() == Outcome.UNKNOWN)
            return withoutValue(observed);
        if (observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (observed.value().isEmpty() || observed.value().orElseThrow().truncated()
            || observed.value().orElseThrow().exitCode().isEmpty())
            return unknown("DEPLOYMENT_HEALTH_UNCONFIRMED");
        if (observed.value().orElseThrow().exitCode().orElseThrow() != 0)
            return failure(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_HEALTH_FAILED");
        if (!"HEALTHY=1\n".equals(observed.value().orElseThrow().stdout()))
            return unknown("DEPLOYMENT_HEALTH_RECEIPT_INVALID");
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Stops one previously running managed runtime only after its original snapshot is durable. / 仅在原快照持久后停止旧受管运行时。
     * @param unit exact old owner / 精确旧归属
     * @param snapshot original task snapshot / 原任务快照
     * @return verified stopped state or uncertainty / 已核实停止状态或未知
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    public OperationResult<Void> stopPrevious(DeploymentUnitIdentity unit, DeploymentRemoteReleaseSnapshot snapshot)
        throws ApiException {
        requireUnit(unit);
        Objects.requireNonNull(snapshot);
        if (snapshot.token().isEmpty() || !snapshot.previousRunning())
            throw new IllegalArgumentException("Only a previously running original can be stopped");
        String verb = switch (snapshot.previousKind()) {
            case "container" -> "lifecycle-container";
            case "deployment" -> "lifecycle-deployment";
            case "ordinary" -> "lifecycle";
            default -> throw new IllegalArgumentException("Original runtime kind cannot be stopped");
        };
        var arguments = snapshot.previousKind().equals("ordinary")
            ? List.of(unit.id(), "stop", unit.ownershipManifestSha256())
            : List.of(unit.id(), "stop", unit.ownershipManifestSha256(), snapshot.previousDigest().orElseThrow());
        var observed = send("deployment.publication.stop-old.v1", unit, verb, arguments, Duration.ofSeconds(45), 4096);
        if (observed.outcome() != Outcome.SUCCEEDED) {
            if (observed.outcome() == Outcome.FAILED && observed.value().isPresent())
                return new OperationResult<>(Outcome.FAILED, Optional.empty(), observed.problem());
            return withoutValue(observed);
        }
        if (!complete(observed) || !"LIFECYCLE=stop\n".equals(observed.value().orElseThrow().stdout()))
            return unknown("DEPLOYMENT_OLD_STOP_UNCONFIRMED");
        var state = observeRestored(unit);
        if (state.outcome() != Outcome.SUCCEEDED || state.value().isEmpty())
            return unknown("DEPLOYMENT_OLD_STOP_STATE_UNCONFIRMED");
        return state.value().orElseThrow().state() == DeploymentRemoteRuntimeObservation.StateType.STOPPED
            ? new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty())
            : unknown("DEPLOYMENT_OLD_STOP_STATE_CHANGED");
    }

    /** Checks the actual owned runtime after a confirmed publish or rollback. / 在确认发布或回滚后检查实际所属运行时。
     * @param unit original owned target / 原所属目标
     * @param runtime reviewed native or container runtime / 已审阅原生或容器运行时
     * @return verified state or explicit uncertainty / 已核实状态或显式未知
     * @throws ApiException if admission rejects before dispatch / 发送前准入拒绝时 */
    public OperationResult<DeploymentRemoteRuntimeObservation> observe(DeploymentUnitIdentity unit,
                                                                       DeploymentRuntimeDefinition runtime) throws ApiException {
        return observeCurrent(unit, runtime);
    }

    /** Changes one confirmed owned daemon and verifies its resulting state. / 改变一个已确认归属的守护进程并核实结果状态。
     * @param unit exact managed unit / 精确受管单元
     * @param runtime saved runtime definition / 已保存运行定义
     * @param action start, stop or restart / 启动、停止或重启
     * @param expectedReleaseSha256 exact reviewed current unit release / 精确已审阅当前单元发布
     * @return confirmed lifecycle result or retained uncertainty / 已确认生命周期结果或保留未知
     * @throws ApiException if host admission fails / 宿主准入失败时 */
    public OperationResult<Void> lifecycle(DeploymentUnitIdentity unit, DeploymentRuntimeDefinition runtime,
                                           String action, String expectedReleaseSha256) throws ApiException {
        requireUnit(unit);
        if (expectedReleaseSha256 == null || !expectedReleaseSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact reviewed current release required");
        Objects.requireNonNull(runtime);
        if (!Set.of("start", "stop", "restart").contains(action)
            || runtime.workload().mode() != gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentWorkload.ExecutionMode.DAEMON)
            throw new IllegalArgumentException("Only a reviewed daemon lifecycle action is supported");
        var before = observe(unit, runtime);
        if (before.outcome() != Outcome.SUCCEEDED || before.value().isEmpty())
            return withoutValue(before);
        String verb = runtime instanceof DeploymentRuntimeDefinition.Container
            ? "lifecycle-container" : "lifecycle-deployment";
        var observed = send("deployment.application.lifecycle.v1", unit, verb,
            List.of(unit.id(), action, unit.ownershipManifestSha256(), expectedReleaseSha256), Duration.ofSeconds(45), 4096);
        if (observed.outcome() != Outcome.SUCCEEDED)
            return withoutValue(observed);
        if (!complete(observed) || !("LIFECYCLE=" + action + "\n").equals(observed.value().orElseThrow().stdout()))
            return unknown("DEPLOYMENT_LIFECYCLE_UNCONFIRMED");
        var after = observe(unit, runtime);
        if (after.outcome() != Outcome.SUCCEEDED || after.value().isEmpty())
            return unknown("DEPLOYMENT_LIFECYCLE_STATE_UNCONFIRMED");
        var expected = "stop".equals(action) ? DeploymentRemoteRuntimeObservation.StateType.STOPPED
            : DeploymentRemoteRuntimeObservation.StateType.RUNNING;
        return after.value().orElseThrow().state() == expected
            ? new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty())
            : unknown("DEPLOYMENT_LIFECYCLE_STATE_CHANGED");
    }

    /** Observes the restored old release's actual saved kind and mode. / 观测恢复的旧发布实际已保存类别和模式。
     * @param unit exact original ownership / 精确原归属
     * @return verified restored runtime or uncertainty / 已核实恢复运行时或未知
     * @throws ApiException if admission rejects before dispatch / 发送前准入拒绝时 */
    public OperationResult<DeploymentRemoteRuntimeObservation> observeRestored(DeploymentUnitIdentity unit)
        throws ApiException {
        return observeCurrent(unit, null);
    }

    /** Inspects current owned kind before selecting its observation contract. / 先检查当前所属类别再选择观测契约。
     * @param unit exact unit / 精确单元
     * @param expected reviewed new runtime, or absent for restored old release / 已审阅新运行时；恢复旧发布时为空
     * @return verified state or uncertainty / 已核实状态或未知
     * @throws ApiException if admission rejects before dispatch / 发送前准入拒绝时 */
    private OperationResult<DeploymentRemoteRuntimeObservation> observeCurrent(DeploymentUnitIdentity unit,
                                                                               DeploymentRuntimeDefinition expected) throws ApiException {
        requireUnit(unit);
        var inspect = send("deployment.publication.inspect.v1", unit, "inspect-runtime",
            List.of(unit.id(), unit.ownershipManifestSha256()), Duration.ofSeconds(20), 4096);
        if (inspect.outcome() == Outcome.FAILED && inspect.value().isEmpty())
            return withoutValue(inspect);
        if (inspect.outcome() == Outcome.CANCELLED || inspect.outcome() == Outcome.UNKNOWN)
            return withoutValue(inspect);
        if (!complete(inspect))
            return unknown("DEPLOYMENT_RUNTIME_IDENTITY_UNCONFIRMED");
        String identity = inspect.value().orElseThrow().stdout();
        if (expected != null) {
            String required = expected instanceof DeploymentRuntimeDefinition.Container container
                ? "KIND=container\nENGINE=" + container.engine().name().toLowerCase(java.util.Locale.ROOT)
                + "\nMODE=" + expected.workload().mode().name() + "\n"
                : "KIND=deployment\nMODE=" + expected.workload().mode().name() + "\n";
            if (!required.equals(identity))
                return unknown("DEPLOYMENT_RUNTIME_IDENTITY_CHANGED");
        }
        if (identity.equals("KIND=ordinary\n"))
            return expected == null ? observeOrdinary(unit) : unknown("DEPLOYMENT_RUNTIME_IDENTITY_CHANGED");
        var values = DeploymentPublicationReceipt.fields(identity);
        String mode = values.get("MODE");
        if (mode == null || !Set.of("DAEMON", "ON_DEMAND").contains(mode))
            return unknown("DEPLOYMENT_RUNTIME_IDENTITY_CHANGED");
        if ("deployment".equals(values.get("KIND")) && values.size() == 2)
            return observeNative(unit, "ON_DEMAND".equals(mode));
        if ("container".equals(values.get("KIND")) && values.size() == 3 && values.get("ENGINE") != null
            && Set.of("docker", "podman").contains(values.get("ENGINE")))
            return "ON_DEMAND".equals(mode)
                ? success(new DeploymentRemoteRuntimeObservation(
                DeploymentRemoteRuntimeObservation.StateType.INSTALLED))
                : observeContainer(unit, values.get("ENGINE"));
        return unknown("DEPLOYMENT_RUNTIME_IDENTITY_CHANGED");
    }

    /** Parses the helper's full native service or sparse on-demand observation. / 解析helper完整原生服务或稀疏按需观测。
     * @param unit original unit / 原单元
     * @param onDemand actual saved execution mode / 实际保存的运行模式
     * @return confirmed native state or unknown / 已确认原生状态或未知
     * @throws ApiException if admission rejects before dispatch / 发送前准入拒绝时 */
    private OperationResult<DeploymentRemoteRuntimeObservation> observeNative(DeploymentUnitIdentity unit,
                                                                              boolean onDemand) throws ApiException {
        var observed = send("deployment.publication.observe.v1", unit, "observe-deployment",
            List.of(unit.id(), unit.ownershipManifestSha256()), Duration.ofSeconds(20), 4096);
        if (observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (observed.outcome() == Outcome.CANCELLED || observed.outcome() == Outcome.UNKNOWN)
            return withoutValue(observed);
        if (!complete(observed))
            return unknown("DEPLOYMENT_RUNTIME_OBSERVATION_UNCONFIRMED");
        String output = observed.value().orElseThrow().stdout();
        if (onDemand)
            return output.equals(
                "OWNER=1\nINSTALLED=1\nRUNNING=0\nENABLED=not-applicable\nQUERY_OK=1\nActiveState=inactive\nMainPID=0\n")
                ? success(new DeploymentRemoteRuntimeObservation(
                DeploymentRemoteRuntimeObservation.StateType.INSTALLED))
                : unknown("DEPLOYMENT_ON_DEMAND_OBSERVATION_INVALID");
        var values = DeploymentPublicationReceipt.fields(output);
        if (!"1".equals(values.get("OWNER")) || !"1".equals(values.get("QUERY_OK"))
            || !values.keySet().containsAll(Set.of("RUNNING", "ENABLED", "ActiveState", "SubState", "Result",
            "ExecMainCode", "ExecMainStatus", "MainPID")))
            return unknown("DEPLOYMENT_RUNTIME_OBSERVATION_INVALID");
        for (String key : List.of("ActiveState", "SubState", "Result", "ExecMainCode", "ExecMainStatus", "MainPID"))
            if (!values.get(key).matches("[A-Za-z0-9_-]{1,64}"))
                return unknown("DEPLOYMENT_RUNTIME_OBSERVATION_INVALID");
        for (String key : List.of("ExecMainCode", "ExecMainStatus", "MainPID"))
            if (!values.get(key).matches("[0-9]{1,10}"))
                return unknown("DEPLOYMENT_RUNTIME_OBSERVATION_INVALID");
        if ("failed".equals(values.get("ActiveState")) || "activating".equals(values.get("ActiveState"))
            && "auto-restart".equals(values.get("SubState")) && !"success".equals(values.get("Result")))
            return success(new DeploymentRemoteRuntimeObservation(DeploymentRemoteRuntimeObservation.StateType.ERROR));
        if ("1".equals(values.get("RUNNING")) && "active".equals(values.get("ActiveState"))
            && "running".equals(values.get("SubState")) && "success".equals(values.get("Result"))
            && values.get("MainPID").matches("[0-9]{1,10}") && !"0".equals(values.get("MainPID")))
            return success(
                new DeploymentRemoteRuntimeObservation(DeploymentRemoteRuntimeObservation.StateType.RUNNING));
        if ("0".equals(values.get("RUNNING")) && "inactive".equals(values.get("ActiveState"))
            && "dead".equals(values.get("SubState")) && "0".equals(values.get("MainPID")))
            return success(
                new DeploymentRemoteRuntimeObservation(DeploymentRemoteRuntimeObservation.StateType.STOPPED));
        return unknown("DEPLOYMENT_RUNTIME_STATE_UNCONFIRMED");
    }

    /** Observes a restored ordinary systemd release after helper ownership inspection. / 在helper归属检查后观测恢复的普通systemd发布。
     * @param unit original owned service / 原所属服务
     * @return verified old state or uncertainty / 已核实旧状态或未知
     * @throws ApiException if admission rejects before dispatch / 发送前准入拒绝时 */
    private OperationResult<DeploymentRemoteRuntimeObservation> observeOrdinary(DeploymentUnitIdentity unit)
        throws ApiException {
        String script = "set -eu\nunit=" + DeploymentBuildCommandChannel.shellQuote(unit.systemdUnit()) + "\n"
            + "systemctl show --property=ActiveState,SubState,Result,ExecMainCode,ExecMainStatus,MainPID "
            + "\"$unit\"\n";
        var observed = dispatch("deployment.publication.observe-restored.v1", unit,
            new CommandRequest("/bin/bash -lc " + DeploymentBuildCommandChannel.shellQuote(script), new byte[0],
                Duration.ofSeconds(20), 4096));
        if (observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (observed.outcome() == Outcome.CANCELLED || observed.outcome() == Outcome.UNKNOWN)
            return withoutValue(observed);
        if (!complete(observed))
            return unknown("DEPLOYMENT_RESTORED_OBSERVATION_UNCONFIRMED");
        var values = DeploymentPublicationReceipt.fields(observed.value().orElseThrow().stdout());
        if (!values.keySet()
            .containsAll(Set.of("ActiveState", "SubState", "Result", "ExecMainCode", "ExecMainStatus", "MainPID")))
            return unknown("DEPLOYMENT_RESTORED_OBSERVATION_INVALID");
        for (String key : List.of("ExecMainCode", "ExecMainStatus", "MainPID"))
            if (!values.get(key).matches("[0-9]{1,10}"))
                return unknown("DEPLOYMENT_RESTORED_OBSERVATION_INVALID");
        if ("failed".equals(values.get("ActiveState")) || "activating".equals(values.get("ActiveState"))
            && "auto-restart".equals(values.get("SubState")) && !"success".equals(values.get("Result")))
            return success(new DeploymentRemoteRuntimeObservation(DeploymentRemoteRuntimeObservation.StateType.ERROR));
        if ("active".equals(values.get("ActiveState")) && "running".equals(values.get("SubState"))
            && "success".equals(values.get("Result")) && !"0".equals(values.get("MainPID")))
            return success(
                new DeploymentRemoteRuntimeObservation(DeploymentRemoteRuntimeObservation.StateType.RUNNING));
        if ("inactive".equals(values.get("ActiveState")) && "dead".equals(values.get("SubState"))
            && "0".equals(values.get("MainPID")))
            return success(
                new DeploymentRemoteRuntimeObservation(DeploymentRemoteRuntimeObservation.StateType.STOPPED));
        return unknown("DEPLOYMENT_RESTORED_STATE_UNCONFIRMED");
    }

    /** Observes one owned container via the original fixed engine and path checks. / 经原固定引擎和路径检查观测一个所属容器。
     * @param unit original target / 原目标
     * @param engine actual saved engine / 实际保存的引擎
     * @return confirmed container state or unknown / 已确认容器状态或未知
     * @throws ApiException if admission rejects before dispatch / 发送前准入拒绝时 */
    private OperationResult<DeploymentRemoteRuntimeObservation> observeContainer(DeploymentUnitIdentity unit,
                                                                                 String engine) throws ApiException {
        String root = DeploymentBuildCommandChannel.shellQuote(unit.releaseRoot());
        String manifest = DeploymentBuildCommandChannel.shellQuote(unit.ownershipManifestSha256());
        String name = DeploymentBuildCommandChannel.shellQuote("windowstolinux-" + unit.id());
        String autostart = engine.equals("docker")
            ? "docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' " + name + " 2>/dev/null || true"
            : "if test -f "
            + DeploymentBuildCommandChannel.shellQuote("/etc/containers/systemd/windowstolinux-" + unit.id()
            + ".container.d/10-windowstolinux-autostart.conf")
            + "; then printf enabled; else printf no; fi";
        String script = "set -eu\nroot=" + root + "\nowner=0\n"
            + "if [ -L \"$root/current\" ]; then current=$(readlink -f -- \"$root/current\"); "
            + "case \"$current\" in \"$root/releases\"/[0-9a-f]*) "
            + "if [ -f \"$current/.windowstolinux-owner\" ] && [ -f \"$current/.windowstolinux-container-engine\" ] "
            + "&& [ \"$(cat -- \"$current/.windowstolinux-owner\")\" = " + manifest + " ] "
            + "&& [ \"$(cat -- \"$current/.windowstolinux-container-engine\")\" = "
            + DeploymentBuildCommandChannel.shellQuote(engine) + " ]; then owner=1; fi;; esac; fi\n" + "running=$("
            + engine + " inspect --format '{{.State.Running}}' " + name + " 2>/dev/null || true)\nenabled=$("
            + autostart + ")\n"
            + "printf 'OWNER=%s\\nRUNNING=%s\\nENABLED=%s\\n' \"$owner\" \"$running\" \"$enabled\"\n";
        var observed = dispatch("deployment.publication.observe.v1", unit,
            new CommandRequest("/bin/bash -lc " + DeploymentBuildCommandChannel.shellQuote(script), new byte[0],
                Duration.ofSeconds(20), 4096));
        if (observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (observed.outcome() == Outcome.CANCELLED || observed.outcome() == Outcome.UNKNOWN)
            return withoutValue(observed);
        if (!complete(observed))
            return unknown("DEPLOYMENT_CONTAINER_OBSERVATION_UNCONFIRMED");
        var values = DeploymentPublicationReceipt.fields(observed.value().orElseThrow().stdout());
        if (!"1".equals(values.get("OWNER")) || values.get("RUNNING") == null
            || !Set.of("true", "false").contains(values.get("RUNNING")) || !values.containsKey("ENABLED"))
            return unknown("DEPLOYMENT_CONTAINER_OBSERVATION_INVALID");
        return success(new DeploymentRemoteRuntimeObservation("true".equals(values.get("RUNNING"))
            ? DeploymentRemoteRuntimeObservation.StateType.RUNNING
            : DeploymentRemoteRuntimeObservation.StateType.STOPPED));
    }

    /** Rolls back only a known failed original release and requires a complete helper receipt. / 仅回滚已知失败的原发布并要求完整helper回执。
     * @param unit exact owned application / 精确所属应用
     * @param releaseSha256 exact attempted release / 精确尝试的发布摘要
     * @param snapshot original saved rollback token / 原保存回滚令牌
     * @param runtime reviewed runtime kind / 已审阅运行类型
     * @return confirmed rollback or uncertainty / 已确认回滚或未知
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    public OperationResult<Void> rollback(DeploymentUnitIdentity unit, String releaseSha256,
                                          DeploymentRemoteReleaseSnapshot snapshot, DeploymentRuntimeDefinition runtime) throws ApiException {
        requireUnit(unit);
        if (releaseSha256 == null || !releaseSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact attempted release required");
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(runtime);
        boolean container = runtime instanceof DeploymentRuntimeDefinition.Container;
        String verb = (container ? "rollback-container" : "rollback-deployment")
            + (snapshot.token().isPresent() ? "" : "-first");
        var values = new java.util.ArrayList<>(List.of(unit.id(), releaseSha256, unit.ownershipManifestSha256()));
        snapshot.token().ifPresent(values::add);
        var observed = send("deployment.publication.rollback.v1", unit, verb, values, Duration.ofSeconds(120), 4096);
        if (observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (observed.outcome() == Outcome.CANCELLED || observed.outcome() == Outcome.UNKNOWN)
            return withoutValue(observed);
        if (observed.value().isEmpty() || observed.value().orElseThrow().truncated()
            || observed.value().orElseThrow().exitCode().isEmpty())
            return unknown("DEPLOYMENT_ROLLBACK_UNCONFIRMED");
        if (observed.value().orElseThrow().exitCode().orElseThrow() != 0)
            return failure(ProblemCode.IO_FAILURE, "DEPLOYMENT_ROLLBACK_FAILED");
        String output = observed.value().orElseThrow().stdout();
        final String marker = "ROLLED_BACK=1\n";
        int at = output.length() - marker.length();
        if (at < 0 || !output.endsWith(marker) || (at > 0 && output.charAt(at - 1) != '\n')
            || output.indexOf("ROLLED_BACK=") != at)
            return unknown("DEPLOYMENT_ROLLBACK_RECEIPT_INVALID");
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Trims only old owned releases after publication is already verified. / 仅在发布已核实后裁剪旧所属发布。
     * @param unit exact owned target / 精确所属目标
     * @return confirmed optional cleanup or its non-fatal problem / 已确认可选清理或其非致命问题
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前准入拒绝时 */
    public OperationResult<Void> retain(DeploymentUnitIdentity unit) throws ApiException {
        requireUnit(unit);
        var observed = send("deployment.publication.retain.v1", unit, "retain",
            List.of(unit.id(), unit.ownershipManifestSha256()), Duration.ofSeconds(60), 4096);
        if (observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (observed.outcome() == Outcome.CANCELLED || observed.outcome() == Outcome.UNKNOWN)
            return withoutValue(observed);
        if (!complete(observed) || !observed.value().orElseThrow().stdout().matches("RETAINED=[1-3]\\n"))
            return unknown("DEPLOYMENT_RETAIN_UNCONFIRMED");
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Ends only the exact attempted release's helper maintenance marker. / 仅结束精确尝试发布的helper维护标记。
     * @param unit exact owned target / 精确所属目标
     * @param releaseSha256 original attempted release / 原尝试发布摘要
     * @return confirmed marker cleanup or uncertainty / 已确认标记清理或未知
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前准入拒绝时 */
    public OperationResult<Void> endMaintenance(DeploymentUnitIdentity unit, String releaseSha256) throws ApiException {
        requireUnit(unit);
        if (releaseSha256 == null || !releaseSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact maintenance release required");
        var observed = send("deployment.publication.maintenance-end.v1", unit, "application-maintenance",
            List.of("end", unit.id(), unit.ownershipManifestSha256(), "deployment-" + releaseSha256, releaseSha256),
            Duration.ofSeconds(30), 4096);
        if (observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (observed.outcome() == Outcome.CANCELLED || observed.outcome() == Outcome.UNKNOWN)
            return withoutValue(observed);
        if (!complete(observed) || !"MAINTENANCE=end\n".equals(observed.value().orElseThrow().stdout()))
            return unknown("DEPLOYMENT_MAINTENANCE_END_UNCONFIRMED");
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Removes only the candidate bound by the helper to this original task. / 仅删除helper绑定到本原任务的候选。
     * @param unit exact owned application / 精确所属应用
     * @param candidateId original confirmed candidate / 原已确认候选
     * @return verified cleanup or retained uncertainty / 已核实清理或保留未知
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    public OperationResult<Void> cleanupCandidate(DeploymentUnitIdentity unit, String candidateId) throws ApiException {
        requireUnit(unit);
        if (candidateId == null || !candidateId.matches("[a-z0-9][a-z0-9-]{0,62}-[0-9a-f]{16}"))
            throw new IllegalArgumentException("Exact original candidate required");
        var observed = send("deployment.publication.candidate-cleanup.v1", unit, "candidate-cleanup-task",
            List.of(unit.id(), candidateId, task.id().toString()), Duration.ofSeconds(90), 4096);
        if (observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (observed.outcome() == Outcome.CANCELLED || observed.outcome() == Outcome.UNKNOWN)
            return withoutValue(observed);
        if (!complete(observed) || !"CANDIDATE_CLEANED=1\n".equals(observed.value().orElseThrow().stdout()))
            return unknown("DEPLOYMENT_CANDIDATE_CLEANUP_UNCONFIRMED");
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Sends one fixed effect under exact host approval. / 在精确宿主授权下发送一次固定副作用。
     * @param operation stable semantic version / 稳定语义版本
     * @param unit exact release target / 精确发布目标
     * @param verb fixed helper verb / 固定helper动作
     * @param arguments bounded literal arguments / 有界字面量参数
     * @param timeout reviewed deadline / 已审阅期限
     * @param output bounded receipt size / 有界回执长度
     * @return actual host observation / 实际宿主观测
     * @throws ApiException if admission rejects before dispatch / 发送前准入拒绝时 */
    private OperationResult<CommandResult> send(String operation, DeploymentUnitIdentity unit, String verb,
                                                List<String> arguments, Duration timeout, long output) throws ApiException {
        return send(operation, unit, verb, arguments, timeout, output, new byte[0]);
    }

    /** Sends a fixed helper command with an optional copied standard input. / 发送带可选已复制标准输入的固定helper命令。
     * @param operation fixed semantic / 固定语义
     * @param unit exact unit / 精确单元
     * @param verb fixed verb / 固定动作
     * @param arguments reviewed values / 已审阅值
     * @param timeout bounded duration / 有界时间
     * @param output bounded receipt / 有界回执
     * @param input copied request bytes / 已复制请求字节
     * @return actual host observation / 真实宿主观测
     * @throws ApiException if admission fails before dispatch / 发送前准入失败时 */
    private OperationResult<CommandResult> send(String operation, DeploymentUnitIdentity unit, String verb,
                                                List<String> arguments, Duration timeout, long output, byte[] input) throws ApiException {
        StringBuilder script = new StringBuilder(
            DeploymentBuildCommandChannel.shellQuote(DeploymentManagedHelperBundle.PATH)).append(' ')
            .append(DeploymentBuildCommandChannel.shellQuote(verb));
        for (String value : arguments)
            script.append(' ').append(DeploymentBuildCommandChannel.shellQuote(value));
        return dispatch(operation, unit, new CommandRequest(script.toString(), input, timeout, output));
    }

    /** Dispatches one exact fixed command through actual review. / 经真实审核发送一条精确固定命令。
     * @param operation fixed semantic / 固定语义
     * @param unit exact target / 精确目标
     * @param request exact reviewed command / 精确已审阅命令
     * @return actual remote observation / 真实远端观测
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前准入拒绝时 */
    private OperationResult<CommandResult> dispatch(String operation, DeploymentUnitIdentity unit,
                                                    CommandRequest request) throws ApiException {
        try (request) {
            var intent = RemoteActions.command(server, planRevision, request).withSemantics(new ActionSemantics(
                operation, Set.of(new ActionTarget(server.id(), "deployment-release", unit.releaseRoot()))));
            var grant = interaction.approve(intent, task);
            if (grant.outcome() != Outcome.SUCCEEDED)
                return new OperationResult<>(grant.outcome(), Optional.empty(), grant.problem());
            beforeSend.verify();
            try {
                var executed = session.execute(request, grant.value().orElseThrow());
                if (executed.outcome() == Outcome.CANCELLED
                    || executed.outcome() == Outcome.FAILED && executed.value().isEmpty())
                    return unknown("DEPLOYMENT_PUBLICATION_DISPATCH_UNCONFIRMED");
                return executed;
            } catch (ApiException failure) {
                if (failure.problem().code() == ProblemCode.ACCESS_DENIED
                    || failure.problem().code() == ProblemCode.INVALID_INPUT)
                    throw failure;
                return unknown("DEPLOYMENT_PUBLICATION_DISPATCH_UNCONFIRMED");
            }
        }
    }

    /** Checks original server identity. / 核对原服务器身份。
     * @param unit exact unit / 精确单元 */
    private void requireUnit(DeploymentUnitIdentity unit) {
        if (!Objects.requireNonNull(unit).server().equals(server))
            throw new IllegalArgumentException("Publication unit belongs to another server");
    }

}
