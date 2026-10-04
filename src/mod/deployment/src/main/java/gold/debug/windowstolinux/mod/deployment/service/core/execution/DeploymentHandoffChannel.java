package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;

/** Sends only fixed deployment-owned collection and restore helper verbs on a borrowed session. / 仅在借用会话上发送固定的部署所属采集与恢复helper动作。 */
public final class DeploymentHandoffChannel {
    /** Only exact deployment-owned verbs accepted by this channel. / 此频道仅接受的精确部署所属动作。 */
    private static final Set<String> VERBS = Set.of("application-maintenance", "backup-create", "backup-discard",
        "handoff-backup-status", "handoff-operation-close", "handoff-operation-close-status",
        "handoff-maintenance-status", "restore-preflight", "restore-prepare", "restore-application-health",
        "restore-start-candidate", "restore-stop-candidate", "restore-mark-quiesced", "restore-quiesce-recovery",
        "restore-snapshot", "restore-start-formal", "restore-recover", "snapshot-retained", "activate-retained",
        "rollback-retained", "finish-retained", "retained-status", "handoff-database-export",
        "handoff-database-status", "handoff-database-discard", "managed-current-status", "managed-target-status",
        "handoff-restore-create", "handoff-restore-file", "handoff-restore-step", "handoff-restore-step-status",
        "handoff-restore-discard", "handoff-restore-discard-status", "handoff-restore-cleanup",
        "handoff-restore-cleanup-status", "handoff-restore-preflight");

    /** Original borrowed session closed by its owning coordinator. / 由所属编排器关闭的原借用会话。 */
    private final RemoteSession session;

    /** Current provider review capability. / 当前提供方审阅能力。 */
    private final InteractionService interaction;

    /** Exact authorized target endpoint. / 精确授权目标端点。 */
    private final ServerId server;

    /** Host-certified original caller task. / 宿主认证原调用方任务。 */
    private final TaskContext task;

    /** Exact reviewed business revision for each approval. / 每次审批的精确已审阅业务修订。 */
    private final String revision;

    /** Binds one original task and authenticated remote connection. / 绑定一项原任务及已认证远端连接。
     * @param session borrowed remote session / 借用的远端会话
     * @param interaction host review capability / 宿主审核能力
     * @param server exact server / 精确服务器
     * @param task original task / 原任务
     * @param revision reviewed business revision / 已审阅业务修订 */
    public DeploymentHandoffChannel(RemoteSession session, InteractionService interaction, ServerId server,
                                    TaskContext task, String revision) {
        this.session = java.util.Objects.requireNonNull(session);
        this.interaction = java.util.Objects.requireNonNull(interaction);
        this.server = java.util.Objects.requireNonNull(server);
        this.task = java.util.Objects.requireNonNull(task);
        this.revision = java.util.Objects.requireNonNull(revision);
    }

    /** Sends one allowed exact helper operation after provider-owned review. / 提供方所属审核后发送一项允许的精确helper操作。
     * @param verb fixed deployment helper verb / 固定部署helper动作
     * @param args literal bounded arguments / 字面有界参数
     * @param input optional exact stdin bytes / 可选精确标准输入字节
     * @param timeout bounded deadline / 有界时限
     * @param outputLimit bounded output bytes / 有界输出字节
     * @return actual remote command result / 实际远端命令结果
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    public OperationResult<CommandResult> execute(String verb, List<String> args, byte[] input, Duration timeout,
                                                  long outputLimit) throws ApiException {
        if (!VERBS.contains(verb) || args.size() > 64 || input.length > 65536 || outputLimit < 1
            || outputLimit > 1_048_576 || timeout.isNegative() || timeout.isZero()
            || timeout.compareTo(Duration.ofHours(2)) > 0)
            throw new IllegalArgumentException("Invalid fixed handoff command");
        StringBuilder script = new StringBuilder(quote(DeploymentManagedHelperBundle.PATH)).append(' ')
            .append(quote(verb));
        for (String value : args) {
            if (value == null || value.length() > 4096 || value.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid helper argument");
            script.append(' ').append(quote(value));
        }
        try (var request = new CommandRequest(script.toString(), input, timeout, outputLimit)) {
            var intent = RemoteActions.command(server, revision, request).withSemantics(
                new ActionSemantics("deployment.handoff." + verb + ".v1", Set.of(new ActionTarget(server.id(),
                    "deployment-handoff", args.isEmpty() ? "" : args.getFirst()))));
            var approved = interaction.approve(intent, task);
            if (approved.outcome() != TaskResult.SUCCEEDED)
                return new OperationResult<>(approved.outcome(), Optional.empty(), approved.problem());
            try {
                var result = session.execute(request, approved.value().orElseThrow());
                if (result.outcome() == TaskResult.UNKNOWN)
                    return result;
                if (result.outcome() == TaskResult.FAILED && result.value().isEmpty())
                    return unknown("DEPLOYMENT_HANDOFF_DISPATCH_UNCONFIRMED");
                return result;
            } catch (ApiException failure) {
                if (failure.problem().code() == ProblemCode.ACCESS_DENIED
                    || failure.problem().code() == ProblemCode.INVALID_INPUT)
                    throw failure;
                return unknown("DEPLOYMENT_HANDOFF_DISPATCH_UNCONFIRMED");
            }
        }
    }

    /** Checks an exact single-line receipt without accepting truncated stdout. / 核对精确单行回执且不接受截断标准输出。
     * @param result actual command result / 实际命令结果
     * @param receipt exact expected receipt / 精确预期回执
     * @return true only after known exit and exact output / 仅已知退出与精确输出时为真 */
    public static boolean receipt(OperationResult<CommandResult> result, String receipt) {
        return result.outcome() == TaskResult.SUCCEEDED && result.value().isPresent()
            && result.value().orElseThrow().exitCode().equals(Optional.of(0))
            && !result.value().orElseThrow().truncated() && result.value().orElseThrow().stdout().equals(receipt);
    }

    /** Checks actual current bytes against one exact component release before dispatch. / 发送前按一个精确组件发布核对实际当前字节。
     * @param component original reviewed component / 原已审阅组件
     * @param expected original component release digest / 原组件发布摘要
     * @return verified match or explicit drift / 已核实匹配或明确漂移
     * @throws ApiException if platform authorization fails / 平台授权失败时 */
    public OperationResult<Void> current(
        gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentApplicationGraph.Part component,
        String expected) throws ApiException {
        String kind = component
            .reviewedRuntime() instanceof gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition.Container
            ? "container"
            : "deployment";
        var result = execute("managed-current-status",
            List.of(component.unit().id(), component.unit().ownershipManifestSha256(), kind), new byte[0],
            Duration.ofMinutes(2), 1024);
        if (receipt(result, "MANAGED_CURRENT=1\nKIND=" + kind + "\nRELEASE_SHA256=" + expected + "\n"))
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
        if (result.outcome() == TaskResult.SUCCEEDED && result.value().isPresent()
            && result.value().orElseThrow().exitCode().isPresent() && !result.value().orElseThrow().truncated())
            return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_CURRENT_RELEASE_CHANGED",
                    "Current component release or owner differs from review", Map.of(), Optional.empty())));
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_CURRENT_RELEASE_UNCONFIRMED",
                "Current component release cannot be confirmed", Map.of(), Optional.empty())));
    }

    /** Shell-quotes a caller-validated literal without expansion. / 不展开地引用经调用方校验的字面量。
     * @param value original literal argument / 原字面参数
     * @return safely quoted original argument / 安全引用的原参数 */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Retains an unconfirmed effect with a stable non-secret event. / 以稳定无秘密事件保留未确认副作用。
     * @param event stable dispatch uncertainty / 稳定发送未知
     * @return retained original uncertainty / 留存原未知 */
    private static OperationResult<CommandResult> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Remote handoff effect requires reconciliation", Map.of(), Optional.empty())));
    }
}
