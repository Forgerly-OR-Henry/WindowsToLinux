package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import gold.debug.windowstolinux.appapi.linux.CommandResult;
import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelper;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentNativeDatabaseWire;

/** Installs a missing managed helper once under an original, recoverable publication task. / 在可恢复的原发布任务下只安装一次缺失的受管 helper。 */
public final class DeploymentHelperBootstrap {
    /** Original task service. / 原任务服务。 */
    private final TaskService tasks;

    /** Original task. / 原任务。 */
    private final TaskContext task;

    /** Exact admitted server. / 精确已准入服务器。 */
    private final ServerRef server;

    /** Borrowed reviewed helper transport. / 借用经审核的 helper 传输。 */
    private final DeploymentManagedHelper helper;

    /** Binds the original task and borrowed server session. / 绑定原任务与借用服务器会话。
     * @param tasks original checkpoint service / 原检查点服务
     * @param task original publication task / 原发布任务
     * @param server admitted server / 已准入服务器
     * @param session borrowed remote session / 借用远端会话
     * @param interaction host review capability / 宿主审核能力 */
    public DeploymentHelperBootstrap(TaskService tasks, TaskContext task, ServerRef server, RemoteSession session,
            InteractionService interaction) {
        this.tasks = Objects.requireNonNull(tasks);
        this.task = Objects.requireNonNull(task);
        this.server = Objects.requireNonNull(server);
        this.helper = new DeploymentManagedHelper(Objects.requireNonNull(session), Objects.requireNonNull(interaction),
                server, task, "deployment-helper-bootstrap-v1");
    }

    /** Verifies an already installed helper or performs one original tracked installation. / 核实既有 helper 或执行一次原任务留痕安装。
     * @return verified identity or an exact refusal or retained unknown / 已核身份、明确拒绝或留存未知
     * @throws ApiException if original task evidence cannot be persisted / 原任务依据无法持久化时 */
    public OperationResult<DeploymentManagedHelper.Identity> ensure() throws ApiException {
        var retained = DeploymentHelperBootstrapJournal.find(tasks, task);
        if (retained.isPresent())
            return reconcile(retained.orElseThrow());
        var observed = helper.probe();
        if (observed.outcome() == Outcome.SUCCEEDED || observed.outcome() == Outcome.CANCELLED
                || observed.outcome() == Outcome.UNKNOWN)
            return observed;
        UUID operationId = UUID.randomUUID();
        String serverDigest = digest(server.id().getBytes(StandardCharsets.UTF_8));
        String bodyDigest = digest(DeploymentHelperInstallerScript.render().getBytes(StandardCharsets.UTF_8));
        byte[] script = DeploymentHelperInstallerScript.renderTracked(task.id(), operationId, serverDigest, bodyDigest)
                .getBytes(StandardCharsets.UTF_8);
        try {
            var journal = DeploymentHelperBootstrapJournal.begin(tasks, task, server, operationId, serverDigest,
                    bodyDigest, digest(script));
            return send(journal, script);
        } finally {
            Arrays.fill(script, (byte) 0);
        }
    }

    /** Reconciles only the retained original installer; it never sends the installation again. / 仅核对留存原安装，绝不重发安装。
     * @param journal original installation journal / 原安装日志
     * @return verified identity or retained uncertainty / 已核身份或留存未知
     * @throws ApiException if original persistence fails / 原持久化失败时 */
    public OperationResult<DeploymentManagedHelper.Identity> reconcile(DeploymentHelperBootstrapJournal journal)
            throws ApiException {
        var facts = journal.facts();
        if (!server.id().equals(facts.serverId()))
            return unknown("DEPLOYMENT_HELPER_BOOTSTRAP_SERVER_CHANGED");
        String bodyDigest = digest(DeploymentHelperInstallerScript.render().getBytes(StandardCharsets.UTF_8));
        byte[] script = DeploymentHelperInstallerScript
                .renderTracked(task.id(), facts.operationId(), facts.serverDigest(), facts.bodyDigest())
                .getBytes(StandardCharsets.UTF_8);
        try {
            if (!bodyDigest.equals(facts.bodyDigest()) || !digest(script).equals(facts.requestDigest())
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
            var observed = helper.probe();
            if (observed.outcome() == Outcome.SUCCEEDED)
                journal.close();
            return observed.outcome() == Outcome.SUCCEEDED
                    ? observed
                    : unknown("DEPLOYMENT_HELPER_BOOTSTRAP_IDENTITY_UNCONFIRMED");
        }
        return settle(journal);
    }

    /** Sends exactly the persisted installer request, including an unsent approval path. / 仅发送已持久安装请求，并处理未发送审核路径。
     * @param journal original installation journal / 原安装日志
     * @param script owned exact command input / 所属精确命令输入
     * @return verified identity or retained uncertainty / 已核身份或留存未知
     * @throws ApiException if original persistence fails / 原持久化失败时 */
    private OperationResult<DeploymentManagedHelper.Identity> send(DeploymentHelperBootstrapJournal journal,
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
                    failure.problem().code() == ProblemCode.CANCELLED ? Outcome.CANCELLED : Outcome.FAILED,
                    Optional.empty(), Optional.of(failure.problem()));
        }
        if (!dispatched.get()) {
            journal.close();
            return new OperationResult<>(observed.outcome(), Optional.empty(), observed.problem());
        }
        if (observed.outcome() == Outcome.UNKNOWN)
            return unknown("DEPLOYMENT_HELPER_BOOTSTRAP_DISPATCH_UNCONFIRMED");
        if (knownFailedExit(observed)) {
            journal.terminal(false);
            journal.close();
            return failed("DEPLOYMENT_HELPER_BOOTSTRAP_FAILED");
        }
        return settle(journal);
    }

    /** Checks the root-owned terminal receipt and actual helper bytes before releasing occupancy. / 释放占用前核对 root 回执与实际 helper 字节。
     * @param journal original installation journal / 原安装日志
     * @return verified identity or retained uncertainty / 已核身份或留存未知
     * @throws ApiException if original persistence fails / 原持久化失败时 */
    private OperationResult<DeploymentManagedHelper.Identity> settle(DeploymentHelperBootstrapJournal journal)
            throws ApiException {
        var facts = journal.facts();
        final OperationResult<CommandResult> observed;
        try {
            observed = helper.installationStatus(facts.operationId());
        } catch (ApiException unavailable) {
            return unknown("DEPLOYMENT_HELPER_BOOTSTRAP_STATUS_UNAVAILABLE");
        }
        if (!knownSuccessfulExit(observed))
            return unknown("DEPLOYMENT_HELPER_BOOTSTRAP_STATUS_UNCONFIRMED");
        String expected = DeploymentHelperInstallerScript.receipt(task.id(), facts.operationId(), facts.serverDigest(),
                facts.bodyDigest(), "SUCCEEDED");
        if (!expected.equals(observed.value().orElseThrow().stdout()))
            return unknown("DEPLOYMENT_HELPER_BOOTSTRAP_RECEIPT_CONFLICT");
        var identity = helper.probe();
        if (identity.outcome() != Outcome.SUCCEEDED)
            return unknown("DEPLOYMENT_HELPER_BOOTSTRAP_IDENTITY_UNCONFIRMED");
        journal.terminal(true);
        journal.close();
        return identity;
    }

    /** Checks a known clean original command exit. / 检查已知干净的原命令退出。
     * @param observed platform observation / 平台观察
     * @return whether exact success was observed / 是否观察到精确成功 */
    private static boolean knownSuccessfulExit(OperationResult<CommandResult> observed) {
        if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty())
            return false;
        var value = observed.value().orElseThrow();
        return value.exitCode().equals(Optional.of(0)) && !value.truncated() && value.stderr().isEmpty();
    }

    /** Distinguishes a returned process failure from timeout and signal uncertainty. / 区分已返回进程失败与超时、信号未知。
     * @param observed platform observation / 平台观察
     * @return whether a bounded nonzero process exit was observed / 是否观察到有界非零进程退出 */
    private static boolean knownFailedExit(OperationResult<CommandResult> observed) {
        if (observed.outcome() != Outcome.SUCCEEDED && observed.outcome() != Outcome.FAILED
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
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                event, "Original managed helper installation needs exact receipt review", Map.of(), Optional.empty())));
    }

    /** Reports an original installation known to have failed. / 报告确知失败的原安装。
     * @param event stable event code / 稳定事件码
     * @return failed result / 失败结果 */
    private static OperationResult<DeploymentManagedHelper.Identity> failed(String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
                event, "Managed helper installation may require manual review", Map.of(), Optional.empty())));
    }
}
