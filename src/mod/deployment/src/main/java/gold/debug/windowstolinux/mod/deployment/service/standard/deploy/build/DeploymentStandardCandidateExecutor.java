package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentCandidateChannel;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelper;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentHostSupportStatus;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.support.DeploymentHostSupportEvaluator;

/** Joins one original frozen archive, pre-upload tool selection, task-owned transfer and restricted build. / 串接一份原冻结归档、上传前工具选择、任务所属传输及受限构建。 */
public final class DeploymentStandardCandidateExecutor {
    /** A single-use original selection before any candidate upload. / 任何候选上传前的一次性原选择。 */
    public static final class Prepared {
        /** Selected exact tool identities. / 已选精确工具身份。 */
        private final DeploymentStandardBuildExecutor.Prepared selection;

        /** Original fixed helper channel. / 原固定helper通道。 */
        private final DeploymentCandidateChannel channel;

        /** Bounded whole-candidate space requirement. / 有界的完整候选空间需求。 */
        private final long requiredSpace;

        /** Last measured available bytes on the same target filesystem. / 同一目标文件系统上最近测得的可用字节。 */
        private final long availableBytes;

        /** Whether this exact preparation was used for an upload. / 此精确准备是否已用于上传。 */
        private boolean consumed;

        /** Retains the actual prepared operation. / 保留实际已准备操作。
         * @param selection original tool selection / 原工具选择
         * @param channel original fixed channel / 原固定通道
         * @param requiredSpace measured required bytes / 已测量所需字节
         * @param availableBytes measured target bytes / 已测量目标字节 */
        private Prepared(DeploymentStandardBuildExecutor.Prepared selection, DeploymentCandidateChannel channel,
                long requiredSpace, long availableBytes) {
            this.selection = selection;
            this.channel = channel;
            this.requiredSpace = requiredSpace;
            this.availableBytes = availableBytes;
        }

        /** Returns the requirement to be summed before any graph upload. / 返回整图上传前须求和的空间要求。
         * @return bounded bytes / 有界字节数 */
        public long requiredSpace() {
            return requiredSpace;
        }

        /** Returns the last target-space observation. / 返回最近目标空间观察。
         * @return available target bytes / 可用目标字节数 */
        public long availableBytes() {
            return availableBytes;
        }

        /** Allows the original task and session to consume this preparation once. / 仅允许原任务及会话消费一次。
         * @param session actual borrowed session / 实际借用会话
         * @param task actual original task / 实际原任务 */
        private synchronized void consume(RemoteSession session, TaskContext task) {
            if (consumed || selection.session != session || selection.task != task)
                throw new IllegalStateException("Prepared candidate belongs to another operation or was consumed");
            consumed = true;
        }
    }
    /** Existing exact build implementation. / 既有精确构建实现。 */
    private final DeploymentStandardBuildExecutor builds = new DeploymentStandardBuildExecutor();

    /** Existing original source-bound renderer. / 既有原来源绑定渲染器。 */
    private final DeploymentStandardPlanner planner = new DeploymentStandardPlanner();

    /** Creates the fixed candidate executor. / 创建固定候选执行器。 */
    public DeploymentStandardCandidateExecutor() {
    }

    /** Executes one reviewed candidate under the original task and borrowed session, without publishing a release. / 在原任务及借用会话下执行一个已审阅候选，不发布版本。
     * @param plan original reviewed plan with open material and archive / 持有开放素材及归档的原已审阅计划
     * @param session borrowed authenticated connection, closed by caller / 借用的已认证连接，由调用方关闭
     * @param interaction actual host approval capability / 真实宿主授权能力
     * @param task original admitted task / 原准入任务
     * @return verified compressed source and installed tool identity only after a confirmed build / 仅确认构建后返回已核验压缩来源及安装工具身份
     * @throws ApiException if host admission fails before dispatch / 宿主发送前准入失败时 */
    public OperationResult<DeploymentVerifiedBuildResult> execute(ReviewedDeploymentPlan plan, RemoteSession session,
            InteractionService interaction, TaskContext task) throws ApiException {
        var prepared = prepare(plan, session, interaction, task);
        return prepared.outcome() == Outcome.SUCCEEDED
                ? build(prepared.value().orElseThrow(), session, task)
                : withoutValue(prepared);
    }

    /** Prepares one component and checks its source, host and tools before any candidate upload. / 在任何候选上传前准备并核对一个组件的来源、主机及工具。
     * @param plan original reviewed component / 原已审阅组件
     * @param session borrowed original connection / 借用原连接
     * @param interaction actual host approval / 真实宿主授权
     * @param task original task / 原任务
     * @return single-use prepared candidate / 一次性已准备候选
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    public OperationResult<Prepared> prepare(ReviewedDeploymentPlan plan, RemoteSession session,
            InteractionService interaction, TaskContext task) throws ApiException {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(session);
        Objects.requireNonNull(interaction);
        Objects.requireNonNull(task);
        if (cancelled(task))
            return cancelledPreparation();
        var rendered = planner.render(plan, task);
        if (rendered.outcome() != Outcome.SUCCEEDED)
            return withoutPreparedValue(rendered);
        var request = plan.request();
        long footprint, requiredSpace;
        try {
            footprint = Math.addExact(request.archive().byteCount(), request.archive().uncompressedByteCount());
            requiredSpace = Math.max(Math.multiplyExact(request.archive().byteCount(), 3),
                    request.limits().maxWorkspaceBytes());
        } catch (ArithmeticException overflow) {
            return failedPreparation(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SOURCE_SIZE_OVERFLOW");
        }
        if (footprint > request.limits().maxWorkspaceBytes())
            return failedPreparation(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_SOURCE_WORKSPACE_LIMIT");
        var budget = new DeploymentBuildBudget(request.limits().timeoutSeconds());
        var channel = new DeploymentCandidateChannel(session, interaction, request.server(), task,
                rendered.value().orElseThrow().reviewedPlanSha256(), budget);
        var helper = new DeploymentManagedHelper(session, interaction, request.server(), task,
                rendered.value().orElseThrow().reviewedPlanSha256()).probe();
        if (helper.outcome() != Outcome.SUCCEEDED)
            return withoutPreparedValue(helper);
        var facts = channel.hostFacts();
        if (facts.outcome() != Outcome.SUCCEEDED)
            return withoutPreparedValue(facts);
        if (DeploymentHostSupportEvaluator.evaluatePlatform(facts.value().orElseThrow())
                .support() != DeploymentHostSupportStatus.READY_FOR_RUNTIME_VALIDATION)
            return failedPreparation(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_HOST_UNSUPPORTED");
        var capacity = channel.availableBytes();
        if (capacity.outcome() != Outcome.SUCCEEDED)
            return withoutPreparedValue(capacity);
        if (capacity.value().orElseThrow() < requiredSpace)
            return failedPreparation(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_TARGET_SPACE");
        var prepared = builds.prepareForUpload(plan, session, interaction, task, budget);
        if (prepared.outcome() != Outcome.SUCCEEDED)
            return withoutPreparedValue(prepared);
        var selection = prepared.value().orElseThrow();
        if (!selection.rendered.reviewedPlanSha256().equals(rendered.value().orElseThrow().reviewedPlanSha256()))
            return unknownPreparation("DEPLOYMENT_PLAN_IDENTITY_CHANGED");
        var preparedFacts = channel.hostFacts();
        if (preparedFacts.outcome() != Outcome.SUCCEEDED)
            return withoutPreparedValue(preparedFacts);
        if (DeploymentHostSupportEvaluator
                .evaluate(preparedFacts.value().orElseThrow(), request.facts(), request.runtime(), selection.tools)
                .support() != DeploymentHostSupportStatus.READY_FOR_RUNTIME_VALIDATION)
            return failedPreparation(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_RUNTIME_UNSUPPORTED");
        if (cancelled(task))
            return cancelledPreparation();
        return new OperationResult<>(Outcome.SUCCEEDED,
                Optional.of(new Prepared(selection, channel, requiredSpace, capacity.value().orElseThrow())),
                Optional.empty());
    }

    /** Uploads and builds one already prepared original candidate exactly once. / 精确一次上传并构建已准备的原候选。
     * @param prepared original preparation / 原准备
     * @param session same borrowed connection / 同一借用连接
     * @param task same original task / 同一原任务
     * @return verified candidate build / 已核实候选构建
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    public OperationResult<DeploymentVerifiedBuildResult> build(Prepared prepared, RemoteSession session,
            TaskContext task) throws ApiException {
        Objects.requireNonNull(prepared).consume(session, task);
        var selection = prepared.selection;
        var channel = prepared.channel;
        var request = selection.plan.request();
        if (cancelled(task))
            return cancelledResult();
        var workspace = new DeploymentRemoteWorkspace(request.facts().applicationId(), request.archive().sha256());
        var created = channel.create(workspace, request.limits().maxWorkspaceBytes());
        if (created.outcome() != Outcome.SUCCEEDED)
            return withoutValue(created);
        if (cancelled(task))
            return unknown("DEPLOYMENT_CANDIDATE_RETAINED_AFTER_CANCEL");
        var uploaded = channel.upload(request.archive(), workspace);
        if (uploaded.outcome() != Outcome.SUCCEEDED)
            return withoutValue(uploaded);
        var verified = channel.verify(workspace, request.archive());
        if (verified.outcome() != Outcome.SUCCEEDED)
            return withoutValue(verified);
        if (cancelled(task))
            return unknown("DEPLOYMENT_CANDIDATE_RETAINED_AFTER_CANCEL");
        return builds.buildPrepared(selection, verified.value().orElseThrow(), session, task);
    }

    /** Converts a result to an empty prepared value. / 转换为无准备值结果。
     * @param result observed result / 已观察结果
     * @return preserved outcome and problem / 保留的结果与问题 */
    private static OperationResult<Prepared> withoutPreparedValue(OperationResult<?> result) {
        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
    }

    /** Reports a known preparation rejection. / 报告已知准备拒绝。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @return known failure / 已知失败 */
    private static OperationResult<Prepared> failedPreparation(ProblemCode code, String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(problem(code, event)));
    }

    /** Retains an unconfirmed preparation side effect. / 保留未确认准备副作用。
     * @param event stable event / 稳定事件
     * @return unknown result / 未知结果 */
    private static OperationResult<Prepared> unknownPreparation(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Cancels before a candidate exists. / 在候选存在前取消。
     * @return cancelled preparation / 已取消准备 */
    private static OperationResult<Prepared> cancelledPreparation() {
        return new OperationResult<>(Outcome.CANCELLED, Optional.empty(),
                Optional.of(problem(ProblemCode.CANCELLED, "DEPLOYMENT_CANDIDATE_CANCELLED")));
    }

    /** Detects original cooperative cancellation. / 检测原协作取消。
     * @param task original task / 原任务
     * @return whether cancellation is pending / 是否待取消 */
    private static boolean cancelled(TaskContext task) {
        return task.cancellation().requested() || Thread.currentThread().isInterrupted();
    }

    /** Preserves the exact non-success without a build value. / 保留原非成功结果而不虚构构建值。
     * @param result original result / 原结果
     * @return same outcome and problem / 相同结果与问题 */
    private static OperationResult<DeploymentVerifiedBuildResult> withoutValue(OperationResult<?> result) {
        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
    }

    /** Reports a known pre-upload rejection. / 报告已知上传前拒绝。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @return known failure / 已知失败 */
    private static OperationResult<DeploymentVerifiedBuildResult> failed(ProblemCode code, String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(problem(code, event)));
    }

    /** Retains an unconfirmed remote effect. / 保留未确认远端副作用。
     * @param event stable event / 稳定事件
     * @return unknown outcome / 未知结果 */
    private static OperationResult<DeploymentVerifiedBuildResult> unknown(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Stops before candidate creation. / 在候选创建前停止。
     * @return cancelled result / 已取消结果 */
    private static OperationResult<DeploymentVerifiedBuildResult> cancelledResult() {
        return new OperationResult<>(Outcome.CANCELLED, Optional.empty(),
                Optional.of(problem(ProblemCode.CANCELLED, "DEPLOYMENT_CANDIDATE_CANCELLED")));
    }

    /** Creates a bounded non-secret result. / 创建有界且不含秘密的结果。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @return public problem / 公开问题 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Reviewed candidate could not be confirmed", Map.of(), Optional.empty());
    }
}
