package gold.debug.windowstolinux.mod.deployment.service.core.setup;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.database.DeploymentNativeDatabaseWire;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishChannel;

/** Installs a missing managed helper once under an original, recoverable publication task. / 在可恢复的原发布任务下只安装一次缺失的受管 helper。 */
public final class DeploymentProtocolBootstrap {
    /** Exact local absence evidence required before retrying only the receipt read. / 仅重读回执前必须具备的本地未发送证据。 */
    private static final Map<String, String> UNSENT_RECEIPT_QUERY = Map.ofEntries(
        Map.entry("installerObservedExitCode", "0"),
        Map.entry("statusOutcome", "FAILED"),
        Map.entry("statusProblemCode", "IO_FAILURE"),
        Map.entry("statusProblemEvent", "SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT"),
        Map.entry("commandPhase", "CHANNEL_OPENING"),
        Map.entry("commandOpenRequested", "true"),
        Map.entry("channelOpenWriteState", "WRITTEN"),
        Map.entry("channelOpenReply", "NOT_OBSERVED"),
        Map.entry("channelOpenConfirmed", "false"),
        Map.entry("channelPeerAssigned", "false"),
        Map.entry("inputState", "NOT_STARTED"),
        Map.entry("inputSenderCreated", "false"),
        Map.entry("authorPayloadState", "NOT_SENT"),
        Map.entry("outputObserved", "false"),
        Map.entry("exitObserved", "false"),
        Map.entry("statusOutputOrTruncation", "false"));

    private final gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout;
    /** Retains one original installer in a standalone or existing recovery checkpoint. / 在独立或既有恢复检查点内保留一项原安装。 */
    public interface Journal {
        /** Reads exact retained installer facts. / 读取精确保留安装依据。
         * @return original facts / 原依据
         * @throws ApiException if durable evidence is unavailable / 持久依据不可用时 */
        DeploymentProtocolBootstrapActionLog.Facts facts() throws ApiException;

        /** Persists dispatch before execute. / 执行前持久发送阶段。
         * @throws ApiException if the transition is unconfirmed / 转换未确认时 */
        void dispatched() throws ApiException;

        /** Persists a known terminal receipt. / 持久已知终态回执。
         * @param success confirmed success / 已确认成功
         * @throws ApiException if the transition is unconfirmed / 转换未确认时 */
        void terminal(boolean success) throws ApiException;

        /** Releases only an unsent or known terminal installer. / 仅释放未发送或已知终结安装。
         * @throws ApiException if release is unconfirmed / 释放未确认时 */
        void close() throws ApiException;
    }

    /** Original task service. / 原任务服务。 */
    private final TaskService tasks;

    /** Original task. / 原任务。 */
    private final TaskContext task;

    /** Exact admitted server. / 精确已准入服务器。 */
    private final ServerId server;

    /** Borrowed reviewed helper transport. / 借用经审核的 helper 传输。 */
    private final DeploymentManagedHelper helper;

    /** Binds the original task and borrowed server session. / 绑定原任务与借用服务器会话。
     * @param tasks original checkpoint service / 原检查点服务
     * @param task original publication task / 原发布任务
     * @param server admitted server / 已准入服务器
     * @param session borrowed remote session / 借用远端会话
     * @param interaction host review capability / 宿主审核能力 */
    public DeploymentProtocolBootstrap(TaskService tasks, TaskContext task, ServerId server, RemoteSession session,
                                       InteractionService interaction) {
        this(tasks, task, server, session, interaction, null,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    /** Keeps original installer journaling while reviewing complete actual commands under the original mode. / 保留原安装日志，同时在原模式审核完整实际命令。
     * @param tasks original checkpoint service / 原检查点服务
     * @param task original task / 原任务
     * @param server original server / 原服务器
     * @param session original borrowed session / 原借用会话
     * @param interaction original host review / 原宿主审核
     * @param review optional original-mode actual command review / 可选原模式实际命令审核 */
    public DeploymentProtocolBootstrap(TaskService tasks, TaskContext task, ServerId server, RemoteSession session,
                                       InteractionService interaction, DeploymentManagedHelper.CommandReview review) {
        this(tasks, task, server, session, interaction, review,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    public DeploymentProtocolBootstrap(TaskService tasks, TaskContext task, ServerId server, RemoteSession session,
                                       InteractionService interaction,
                                       gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        this(tasks, task, server, session, interaction, null, layout);
    }

    public DeploymentProtocolBootstrap(TaskService tasks, TaskContext task, ServerId server, RemoteSession session,
                                       InteractionService interaction, DeploymentManagedHelper.CommandReview review,
                                       gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.tasks = Objects.requireNonNull(tasks);
        this.task = Objects.requireNonNull(task);
        this.server = Objects.requireNonNull(server);
        this.helper = new DeploymentManagedHelper(Objects.requireNonNull(session), Objects.requireNonNull(interaction),
            server, task, "deployment-helper-bootstrap-v1", gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard.NONE, review, layout);
    }

    /** Gives the initial publication one fresh, separately approved read after a proven unsent receipt query.
     * / 初次发布中，确认回执查询未发送后，用新连接和独立审批再读一次。
     * @param tasks original checkpoint service / 原检查点服务
     * @param task original publication task / 原发布任务
     * @param server original admitted server / 原准入服务器
     * @param linux publication capability pinned to the original task and authenticated wire key / 绑定原任务与认证 wire 密钥的发布能力
     * @param interaction original-mode command review and host grant capability / 原模式命令审核与宿主授权能力
     * @param layout original remote layout / 原远端布局
     * @return verified identity or retained original uncertainty / 已核身份或留存原不确定性
     * @throws ApiException if initial access or persistence fails / 初始访问或持久化失败时 */
    public static OperationResult<DeploymentManagedHelper.Identity> ensureForPublication(
        TaskService tasks, TaskContext task, ServerId server, LinuxService linux, InteractionService interaction,
        DeploymentRemoteLayout layout) throws ApiException {
        boolean initial = tasks.findCheckpoint(task, DeploymentProtocolBootstrapActionLog.KEY).isEmpty();
        OperationResult<DeploymentManagedHelper.Identity> first = null;
        try (var session = linux.connect(server, task)) {
            first = new DeploymentProtocolBootstrap(tasks, task, server, session, interaction, layout).ensure();
        } catch (ApiException failure) {
            if (first == null || first.outcome() != TaskResult.UNKNOWN) throw failure;
            return withSecondProblem(first, "firstSessionCloseEvent", failure.problem());
        }
        if (!initial || task.cancelToken().requested() || Thread.currentThread().isInterrupted()
            || !receiptQueryUnsent(first))
            return first;
        try {
            var journal = DeploymentProtocolBootstrapActionLog.find(tasks, task);
            if (journal.isEmpty() || !journal.orElseThrow().facts().phase().equals("DISPATCHED"))
                return first;
            try (var fresh = linux.connect(server, task)) {
                var observed = new DeploymentProtocolBootstrap(tasks, task, server, fresh, interaction, layout)
                    .reconcile(journal.orElseThrow());
                if (observed.outcome() == TaskResult.SUCCEEDED) return observed;
                return withSecondProblem(first, "receiptRetryEvent", observed.problem().orElseThrow());
            }
        } catch (ApiException unavailable) {
            return withSecondProblem(first, "receiptRetryEvent", unavailable.problem());
        }
    }

    /** Checks typed evidence for the specific receipt read before admitting a fresh observation. / 准入新观测前核对该回执读取的类型化证据。
     * @param result original bootstrap outcome / 原 bootstrap 结果
     * @return whether the read is proven unsent after a zero installer exit / 安装退出零后是否已证实读取未发送 */
    private static boolean receiptQueryUnsent(OperationResult<DeploymentManagedHelper.Identity> result) {
        return result.outcome() == TaskResult.UNKNOWN && result.problem().filter(problem -> {
            var facts = problem.context();
            return problem.eventCode().equals("DEPLOYMENT_HELPER_BOOTSTRAP_STATUS_UNCONFIRMED")
                && facts.entrySet().containsAll(UNSENT_RECEIPT_QUERY.entrySet())
                && !facts.containsKey("statusExitCode");
        }).isPresent();
    }

    /** Adds a secondary diagnostic while retaining the first receipt failure. / 保留第一次回执失败并追加后续诊断。
     * @param first original unresolved receipt observation / 原未决回执观测
     * @param key diagnostic key for the later failure / 后续失败的诊断键
     * @param second later typed problem / 后续类型化问题
     * @return original unknown outcome with both observations / 保留两次观测的原未知结果 */
    private static OperationResult<DeploymentManagedHelper.Identity> withSecondProblem(
        OperationResult<DeploymentManagedHelper.Identity> first, String key, Problem second) {
        var problem = first.problem().orElseThrow();
        var details = new HashMap<>(problem.context());
        if (second.eventCode().matches("[A-Z][A-Z0-9_]{0,119}")) details.put(key, second.eventCode());
        var cause = second.context().get("statusProblemEvent");
        if (cause != null && cause.matches("[A-Z][A-Z0-9_]{0,119}")) details.put(key + "Cause", cause);
        second.diagnosticId().ifPresent(id -> details.put(key + "DiagnosticId", id.toString()));
        return unknown(problem.eventCode(), details, problem.diagnosticId());
    }

    /** Verifies an already installed helper or performs one original tracked installation. / 核实既有 helper 或执行一次原任务留痕安装。
     * @return verified identity or an exact refusal or retained unknown / 已核身份、明确拒绝或留存未知
     * @throws ApiException if original task evidence cannot be persisted / 原任务依据无法持久化时 */
    public OperationResult<DeploymentManagedHelper.Identity> ensure() throws ApiException {
        var retained = DeploymentProtocolBootstrapActionLog.find(tasks, task);
        if (retained.isPresent())
            return reconcile(retained.orElseThrow());
        var observed = helper.probePlatform();
        if (!DeploymentManagedHelper.installationRequired(observed))
            return observed;
        UUID operationId = UUID.randomUUID();
        String serverDigest = digest(server.id().getBytes(StandardCharsets.UTF_8));
        String bodyDigest = digest(DeploymentHelperInstallerScript.render(layout).getBytes(StandardCharsets.UTF_8));
        byte[] script = DeploymentHelperInstallerScript.renderTracked(task.id(), operationId, serverDigest, bodyDigest, layout)
            .getBytes(StandardCharsets.UTF_8);
        try {
            var journal = DeploymentProtocolBootstrapActionLog.begin(tasks, task, server, operationId, serverDigest,
                bodyDigest, gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks.installerRequestDigest(new String(script, StandardCharsets.UTF_8), task), layout);
            return send(journal, script);
        } finally {
            Arrays.fill(script, (byte) 0);
        }
    }

    /** Reconciles only the retained original installer; it never sends the installation again. / 仅核对留存原安装，绝不重发安装。
     * @param journal original installation journal / 原安装日志
     * @return verified identity or retained uncertainty / 已核身份或留存未知
     * @throws ApiException if original persistence fails / 原持久化失败时 */
    public OperationResult<DeploymentManagedHelper.Identity> reconcile(Journal journal)
        throws ApiException {
        var facts = journal.facts();
        if (!server.id().equals(facts.serverId()))
            return unknown("DEPLOYMENT_HELPER_BOOTSTRAP_SERVER_CHANGED");
        String bodyDigest = digest(DeploymentHelperInstallerScript.render(layout).getBytes(StandardCharsets.UTF_8));
        byte[] script = DeploymentHelperInstallerScript
            .renderTracked(task.id(), facts.operationId(), facts.serverDigest(), facts.bodyDigest(), layout)
            .getBytes(StandardCharsets.UTF_8);
        try {
            if (!bodyDigest.equals(facts.bodyDigest()) || !gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks.installerRequestDigest(new String(script, StandardCharsets.UTF_8), task).equals(facts.requestDigest())
                || !digest(server.id().getBytes(StandardCharsets.UTF_8)).equals(facts.serverDigest()))
                return unknown("DEPLOYMENT_HELPER_BOOTSTRAP_REQUEST_CONFLICT");
        } finally {
            Arrays.fill(script, (byte) 0);
        }
        if ("INTENT".equals(facts.phase())) {
            journal.close();
            return failed("DEPLOYMENT_HELPER_BOOTSTRAP_NOT_SENT");
        }
        if ("FAILED".equals(facts.phase())) {
            journal.close();
            return failed("DEPLOYMENT_HELPER_BOOTSTRAP_FAILED");
        }
        if ("SUCCEEDED".equals(facts.phase())) {
            var observed = helper.probePlatform();
            if (observed.outcome() == TaskResult.SUCCEEDED)
                journal.close();
            return observed.outcome() == TaskResult.SUCCEEDED
                ? observed
                : unknown("DEPLOYMENT_HELPER_BOOTSTRAP_IDENTITY_UNCONFIRMED");
        }
        return settle(journal);
    }

    /** Sends a newly retained repair intent after validating its complete immutable installer binding. / 校验完整不可变安装绑定后发送新留存修复意图。
     * @param journal newly persisted original repair intent / 新持久原修复意图
     * @return verified identity or retained uncertainty / 已核身份或保留未知
     * @throws ApiException if original persistence or approval fails / 原持久化或审批失败时 */
    public OperationResult<DeploymentManagedHelper.Identity> install(Journal journal) throws ApiException {
        var facts = journal.facts();
        byte[] script = DeploymentHelperInstallerScript.renderTracked(task.id(), facts.operationId(), facts.serverDigest(), facts.bodyDigest(), layout)
            .getBytes(StandardCharsets.UTF_8);
        try {
            if (!facts.phase().equals("INTENT") || !server.id().equals(facts.serverId())
                || !digest(server.id().getBytes(StandardCharsets.UTF_8)).equals(facts.serverDigest())
                || !digest(DeploymentHelperInstallerScript.render(layout).getBytes(StandardCharsets.UTF_8)).equals(facts.bodyDigest())
                || !gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks.installerRequestDigest(new String(script, StandardCharsets.UTF_8), task).equals(facts.requestDigest()))
                return unknown("DEPLOYMENT_HELPER_REPAIR_REQUEST_CONFLICT");
            return send(journal, script);
        } finally {
            Arrays.fill(script, (byte) 0);
        }
    }

    /** Sends exactly the persisted installer request, including an unsent approval path. / 仅发送已持久安装请求，并处理未发送审核路径。
     * @param journal original installation journal / 原安装日志
     * @param script owned exact command input / 所属精确命令输入
     * @return verified identity or retained uncertainty / 已核身份或留存未知
     * @throws ApiException if original persistence fails / 原持久化失败时 */
    private OperationResult<DeploymentManagedHelper.Identity> send(Journal journal,
                                                                   byte[] script) throws ApiException {
        var transitionEntered = new AtomicBoolean();
        var dispatched = new AtomicBoolean();
        final OperationResult<CommandResult> observed;
        try {
            observed = helper.installTracked(script, () -> {
                transitionEntered.set(true);
                journal.dispatched();
                dispatched.set(true);
            });
        } catch (ApiException failure) {
            if (transitionEntered.get())
                return unknown("DEPLOYMENT_HELPER_BOOTSTRAP_DISPATCH_UNCONFIRMED");
            journal.close();
            return new OperationResult<>(
                failure.problem().code() == ProblemCode.CANCELLED ? TaskResult.CANCELLED : TaskResult.FAILED,
                Optional.empty(), Optional.of(failure.problem()));
        }
        if (!dispatched.get()) {
            journal.close();
            return new OperationResult<>(observed.outcome(), Optional.empty(), observed.problem());
        }
        if (observed.outcome() == TaskResult.UNKNOWN)
            return unknown("DEPLOYMENT_HELPER_BOOTSTRAP_DISPATCH_UNCONFIRMED");
        if (knownFailedExit(observed)) {
            journal.terminal(false);
            journal.close();
            return failed("DEPLOYMENT_HELPER_BOOTSTRAP_FAILED");
        }
        var settled = settle(journal);
        if (settled.outcome() != TaskResult.UNKNOWN || !installerExitZero(observed)) return settled;
        var problem = settled.problem().orElseThrow();
        var details = new HashMap<>(problem.context());
        details.put("installerObservedExitCode", "0");
        return unknown(problem.eventCode(), details, problem.diagnosticId());
    }

    /** Checks the bounded installer exit independently of receipt and helper verification. / 独立于回执和 helper 核验检查有界安装退出。
     * @param observed original installer command outcome / 原安装命令结果
     * @return whether the actual installer process exited zero with complete output / 实际安装进程是否退出零且输出完整 */
    private static boolean installerExitZero(OperationResult<CommandResult> observed) {
        return observed.outcome() == TaskResult.SUCCEEDED && observed.value()
            .filter(value -> !value.truncated() && value.exitCode().equals(Optional.of(0))).isPresent();
    }

    /** Checks the root-owned terminal receipt and actual helper bytes before releasing occupancy. / 释放占用前核对 root 回执与实际 helper 字节。
     * @param journal original installation journal / 原安装日志
     * @return verified identity or retained uncertainty / 已核身份或留存未知
     * @throws ApiException if original persistence fails / 原持久化失败时 */
    private OperationResult<DeploymentManagedHelper.Identity> settle(Journal journal)
        throws ApiException {
        var facts = journal.facts();
        final OperationResult<CommandResult> observed;
        try {
            observed = helper.installationStatus(facts.operationId());
        } catch (ApiException unavailable) {
            return unknownObservation("DEPLOYMENT_HELPER_BOOTSTRAP_STATUS_UNAVAILABLE",
                new OperationResult<CommandResult>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(unavailable.problem())));
        }
        if (!knownSuccessfulExit(observed))
            return unknownObservation("DEPLOYMENT_HELPER_BOOTSTRAP_STATUS_UNCONFIRMED", observed);
        String expected = DeploymentHelperInstallerScript.receipt(task.id(), facts.operationId(), facts.serverDigest(),
            facts.bodyDigest(), "SUCCEEDED", layout);
        if (!expected.equals(observed.value().orElseThrow().stdout()))
            return unknown("DEPLOYMENT_HELPER_BOOTSTRAP_RECEIPT_CONFLICT");
        var identity = helper.probePlatform();
        if (identity.outcome() != TaskResult.SUCCEEDED)
            return unknownObservation("DEPLOYMENT_HELPER_BOOTSTRAP_IDENTITY_UNCONFIRMED", identity);
        journal.terminal(true);
        journal.close();
        return identity;
    }

    /** Checks a known clean original command exit. / 检查已知干净的原命令退出。
     * @param observed platform observation / 平台观察
     * @return whether exact success was observed / 是否观察到精确成功 */
    private static boolean knownSuccessfulExit(OperationResult<CommandResult> observed) {
        if (observed.outcome() != TaskResult.SUCCEEDED || observed.value().isEmpty())
            return false;
        var value = observed.value().orElseThrow();
        return value.exitCode().equals(Optional.of(0)) && !value.truncated() && value.stderr().isEmpty();
    }

    /** Distinguishes a returned process failure from timeout and signal uncertainty. / 区分已返回进程失败与超时、信号未知。
     * @param observed platform observation / 平台观察
     * @return whether a bounded nonzero process exit was observed / 是否观察到有界非零进程退出 */
    private static boolean knownFailedExit(OperationResult<CommandResult> observed) {
        if (observed.outcome() != TaskResult.SUCCEEDED && observed.outcome() != TaskResult.FAILED
            || observed.value().isEmpty())
            return false;
        var value = observed.value().orElseThrow();
        return value.exitCode().filter(code -> code > 0 && code < 128).isPresent() && !value.truncated();
    }

    /** Computes an exact lower-case digest without taking ownership of caller bytes. / 不取得调用方字节所有权地计算精确小写摘要。
     * @param value borrowed original bytes / 借用的原字节
     * @return SHA-256 digest / SHA-256 摘要 */
    private static String digest(byte[] value) {
        return DeploymentNativeDatabaseWire.digest(value);
    }

    /** Retains an unresolved original installation. / 保留未决原安装。
     * @param event stable event code / 稳定事件码
     * @return unknown result / 未知结果 */
    private static OperationResult<DeploymentManagedHelper.Identity> unknown(String event) {
        return unknown(event, Map.of(), Optional.empty());
    }

    /** Projects typed observation facts from a receipt or identity read. / 从回执或身份读取中投影类型化观测事实。
     * @param event original bootstrap event code / 原 bootstrap 事件码
     * @param observed read outcome / 读取结果
     * @return unresolved installation with the observed diagnostic facts / 带观测诊断事实的未决安装 */
    private static OperationResult<DeploymentManagedHelper.Identity> unknownObservation(String event, OperationResult<?> observed) {
        var details = new HashMap<String, String>();
        observed.problem().ifPresent(problem -> {
            details.putAll(DeploymentPublishChannel.runtimeObservationDiagnostics(problem));
            details.put("statusProblemCode", problem.code().name());
            if (problem.eventCode().matches("[A-Z][A-Z0-9_]{0,119}"))
                details.put("statusProblemEvent", problem.eventCode());
        });
        details.put("statusOutcome", observed.outcome().name());
        if (observed.value().orElse(null) instanceof CommandResult result) {
            result.exitCode().ifPresent(exit -> details.put("statusExitCode", Integer.toString(exit)));
            details.put("statusOutputOrTruncation",
                Boolean.toString(result.truncated() || !result.stdout().isEmpty() || !result.stderr().isEmpty()));
        }
        return unknown(event, details, observed.problem().flatMap(Problem::diagnosticId));
    }

    /** Reports original uncertainty with its safe evidence and diagnostic reference. / 保留原不确定性、安全证据及诊断引用。
     * @param event original bootstrap event code / 原 bootstrap 事件码
     * @param details bounded typed facts / 有界类型化事实
     * @param diagnosticId original diagnostic reference / 原诊断引用
     * @return unresolved original installation / 未决原安装 */
    private static OperationResult<DeploymentManagedHelper.Identity> unknown(String event, Map<String, String> details,
                                                                                Optional<UUID> diagnosticId) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Original managed helper installation needs exact receipt review", details, diagnosticId)));
    }

    /** Reports an original installation known to have failed. / 报告确知失败的原安装。
     * @param event stable event code / 稳定事件码
     * @return failed result / 失败结果 */
    private static OperationResult<DeploymentManagedHelper.Identity> failed(String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
            event, "Managed helper installation may require manual review", Map.of(), Optional.empty())));
    }
}
