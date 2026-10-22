package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentHostCapabilityStatus;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.deploy.support.DeploymentHostMatcher;

/** Joins one original frozen archive, pre-upload tool selection, task-owned transfer and restricted build. / 串接一份原冻结归档、上传前工具选择、任务所属传输及受限构建。 */
public final class DeploymentStandardCandidateRunner {
    /** A single-use original selection before any candidate upload. / 任何候选上传前的一次性原选择。 */
    public static final class Prepared {
        /** Selected exact tool identities. / 已选精确工具身份。 */
        private final DeploymentStandardBuildRunner.Prepared selection;

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
        private Prepared(DeploymentStandardBuildRunner.Prepared selection, DeploymentCandidateChannel channel,
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

        /** Returns the actual plan of this exact selection. / 返回此精确选择的实际计划。
         * @return original source-bound plan / 原来源绑定计划 */
        public ReviewedDeploymentPlan plan() {
            return selection.plan;
        }

        /** Returns complete tools actually used by the last build. / 返回最近构建实际使用的完整工具。
         * @return actual installed binding / 实际安装绑定 */
        public gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains tools() {
            return selection.executedTools;
        }

        /** Returns the exact fixed build input for this selection. / 返回此选择的精确固定构建输入。
         * @return complete immutable script / 完整不可变脚本 */
        public String script() {
            return DeploymentStandardBuildRunner.script(selection.rendered.buildScript(), selection.plan.request(),
                selection.environment, selection.executedTools);
        }

        /** Returns remaining original cumulative remote execution time. / 返回原累计远端执行剩余时间。
         * @return remaining whole seconds / 剩余整秒 */
        public long remainingSeconds() {
            return selection.budget.remainingSeconds();
        }

        /** Carries the same monotonic budget into a later explicitly approved operation. / 将同一单调预算带入后续明确审批操作。
         * @return authentic original cumulative budget / 真实原累计预算 */
        public DeploymentBuildBudget budget() {
            return selection.budget;
        }

        /** Returns the exact source and operation bound candidate. / 返回精确来源及操作绑定候选。
         * @return actual prepared candidate identity / 实际准备候选身份 */
        public DeploymentRemoteWorkspace workspace() {
            return selection.workspace;
        }

        /** Reports whether the original catalog minimum already consumed another branch. / 报告原目录最低版本是否已消费另一分支。
         * @return zero or one original branch / 零或一原分支 */
        public int branch() {
            return selection.attempt;
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
    private final DeploymentStandardBuildRunner builds;

    private final gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentPreparationObserver preparationObserver;

    /** Existing original source-bound renderer. / 既有原来源绑定渲染器。 */
    private final DeploymentStandardPlanner planner = new DeploymentStandardPlanner();

    /** Creates the fixed candidate executor. / 创建固定候选执行器。 */
    public DeploymentStandardCandidateRunner() {
        this(gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentPreparationObserver.NONE);
    }

    public DeploymentStandardCandidateRunner(gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentPreparationObserver preparationObserver) {
        this.preparationObserver = Objects.requireNonNull(preparationObserver);
        builds = new DeploymentStandardBuildRunner(preparationObserver);
    }

    public DeploymentStandardCandidateRunner observing(gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentPreparationObserver observer) {
        return new DeploymentStandardCandidateRunner(observer);
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
        return prepared.outcome() == TaskResult.SUCCEEDED
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
        return prepare(plan, session, interaction, task, true);
    }

    /** Admits the original minimum-version fallback only when the publication's shared retry allowance permits it. / 仅在发布共用重试额度允许时准入原最低版本回退。
     * @param plan original reviewed component / 原审阅组件
     * @param session authentic original connection / 真实原连接
     * @param interaction exact original approval / 精确原审批
     * @param task authentic original task / 真实原任务
     * @param fallback whether a remaining shared retry permits the fixed fallback / 剩余共用重试是否允许固定回退
     * @return actual prepared selection / 实际准备选择
     * @throws ApiException if original authority rejects / 原权限拒绝时 */
    public OperationResult<Prepared> prepare(ReviewedDeploymentPlan plan, RemoteSession session,
                                             InteractionService interaction, TaskContext task, boolean fallback) throws ApiException {
        return prepare(plan, session, interaction, task, fallback, 0,
            new DeploymentBuildBudget(plan.request().limits().timeoutSeconds()), new DeploymentRemoteWorkspace(
                plan.request().facts().applicationId(), plan.request().archive().sha256(), plan.request().layout()));
    }

    /** Prepares a later explicit operation without resetting source identity, branch or time. / 准备后续明确操作，不重置来源身份、分支或时间。
     * @param plan full newly approved plan / 完整新审批计划
     * @param session authentic current borrowed session / 真实当前借用会话
     * @param interaction original exact approval / 原精确审批
     * @param task authentic unchanged task / 真实不变任务
     * @param fallback whether the shared retry permits fixed fallback / 共用重试是否允许固定回退
     * @param branch explicitly reviewed original catalog branch / 明确审阅原目录分支
     * @param budget unchanged original cumulative time / 不变原累计时间
     * @param workspace exact new operation and original archive / 精确新操作及原归档
     * @return confirmed actual preparation / 已确认实际准备
     * @throws ApiException if original host authority rejects / 原宿主权限拒绝时 */
    public OperationResult<Prepared> prepare(ReviewedDeploymentPlan plan, RemoteSession session,
                                             InteractionService interaction, TaskContext task, boolean fallback, int branch,
                                             DeploymentBuildBudget budget, DeploymentRemoteWorkspace workspace) throws ApiException {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(session);
        Objects.requireNonNull(interaction);
        Objects.requireNonNull(task);
        if (cancelled(task))
            return cancelledPreparation();
        var rendered = planner.render(plan, task, plan.request().layout());
        if (rendered.outcome() != TaskResult.SUCCEEDED)
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
        Objects.requireNonNull(budget);
        if (!Objects.requireNonNull(workspace).applicationId().equals(request.facts().applicationId())
            || !workspace.archiveSha256().equals(request.archive().sha256()) || !workspace.layout().equals(request.layout()))
            throw new IllegalArgumentException("Candidate operation differs from original reviewed source");
        var channel = new DeploymentCandidateChannel(session, interaction, request.server(), task,
            rendered.value().orElseThrow().reviewedPlanSha256(), budget, request.layout());
        var helper = new DeploymentManagedHelper(session, interaction, request.server(), task,
            rendered.value().orElseThrow().reviewedPlanSha256(), request.layout()).probe();
        if (helper.outcome() != TaskResult.SUCCEEDED)
            return withoutPreparedValue(helper);
        var facts = channel.hostFacts(request.runtime());
        if (facts.outcome() != TaskResult.SUCCEEDED)
            return withoutPreparedValue(facts);
        if (DeploymentHostMatcher.evaluatePlatform(facts.value().orElseThrow())
            .support() != DeploymentHostCapabilityStatus.READY_FOR_RUNTIME_VALIDATION)
            return failedPreparation(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_HOST_UNSUPPORTED");
        var capacity = channel.availableBytes();
        if (capacity.outcome() != TaskResult.SUCCEEDED)
            return withoutPreparedValue(capacity);
        if (capacity.value().orElseThrow() < Math.addExact(requiredSpace, request.limits().hostReserveBytes()))
            return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_TARGET_SPACE",
                "Verified target capacity is insufficient for the reviewed workspace", Map.of("availableBytes", Long.toString(capacity.value().orElseThrow()),
                "requiredBytes", Long.toString(Math.addExact(requiredSpace, request.limits().hostReserveBytes())), "workspaceBytes", Long.toString(request.limits().maxWorkspaceBytes())), Optional.of(task.id()))));
        var prepared = builds.prepareForUpload(plan, session, interaction, task, budget, branch, fallback, workspace);
        if (prepared.outcome() != TaskResult.SUCCEEDED)
            return withoutPreparedValue(prepared);
        var selection = prepared.value().orElseThrow();
        if (!selection.rendered.reviewedPlanSha256().equals(rendered.value().orElseThrow().reviewedPlanSha256()))
            return unknownPreparation("DEPLOYMENT_PLAN_IDENTITY_CHANGED");
        var preparedFacts = channel.hostFacts(request.runtime());
        if (preparedFacts.outcome() != TaskResult.SUCCEEDED)
            return withoutPreparedValue(preparedFacts);
        preparationObserver.hostObserved(selection.rendered.reviewedPlanSha256(),
            preparedFacts.value().orElseThrow(), selection.tools, task);
        if (DeploymentHostMatcher
            .evaluate(preparedFacts.value().orElseThrow(), request.facts(), request.runtime(), selection.tools)
            .support() != DeploymentHostCapabilityStatus.READY_FOR_RUNTIME_VALIDATION) {
            var runtime = gold.debug.windowstolinux.mod.deployment.service.deploy.support.runtime.DeploymentRuntimeCapabilityMatcher
                .evaluate(preparedFacts.value().orElseThrow(), request.facts(), request.runtime(), selection.tools);
            if (runtime.missingHostPrerequisite() && DeploymentHostMatcher.evaluatePlatform(preparedFacts.value().orElseThrow())
                .support() == DeploymentHostCapabilityStatus.READY_FOR_RUNTIME_VALIDATION)
                return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
                    ProblemCode.INCOMPATIBLE, "DEPLOYMENT_HOST_PREREQUISITE_MISSING",
                    "Reviewed host prerequisites could not be validated",
                    Map.of("requiredCapability", runtime.detail().orElseThrow()), Optional.empty())));
            return failedPreparation(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_RUNTIME_UNSUPPORTED");
        }
        if (cancelled(task))
            return cancelledPreparation();
        return new OperationResult<>(TaskResult.SUCCEEDED,
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
        return build(prepared, session, task, true);
    }

    /** Uploads once and leaves assisted fallback to the original bounded review loop. / 仅上传一次，将辅助回退交给原有界审阅循环。
     * @param prepared original selection / 原选择
     * @param session original session / 原会话
     * @param task original task / 原任务
     * @param fallback whether the original static compiler fallback is allowed / 是否允许原静态编译器回退
     * @return actual original build outcome / 实际原构建结果
     * @throws ApiException if exact host authority rejects / 精确宿主权限拒绝时 */
    public OperationResult<DeploymentVerifiedBuildResult> build(Prepared prepared, RemoteSession session,
                                                                TaskContext task, boolean fallback) throws ApiException {
        Objects.requireNonNull(prepared).consume(session, task);
        var selection = prepared.selection;
        var channel = prepared.channel;
        var request = selection.plan.request();
        if (cancelled(task))
            return cancelledResult();
        var workspace = selection.workspace;
        var capacity = channel.availableBytes();
        if (capacity.outcome() != TaskResult.SUCCEEDED) return withoutValue(capacity);
        long required = Math.addExact(prepared.requiredSpace, request.limits().hostReserveBytes());
        if (capacity.value().orElseThrow() < required)
            return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_TARGET_SPACE",
                "Current capacity cannot fit the unchanged reviewed candidate budget", Map.of("availableBytes", Long.toString(capacity.value().orElseThrow()),
                "requiredBytes", Long.toString(required), "workspaceBytes", Long.toString(request.limits().maxWorkspaceBytes())), Optional.of(task.id()))));
        var created = channel.create(workspace, request.limits().maxWorkspaceBytes());
        if (created.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(created);
        if (cancelled(task))
            return unknown("DEPLOYMENT_CANDIDATE_RETAINED_AFTER_CANCEL");
        var uploaded = channel.upload(request.archive(), workspace);
        if (uploaded.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(uploaded);
        var verified = channel.verify(workspace, request.archive());
        if (verified.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(verified);
        if (cancelled(task))
            return unknown("DEPLOYMENT_CANDIDATE_RETAINED_AFTER_CANCEL");
        return builds.buildPrepared(selection, verified.value().orElseThrow(), session, task, fallback);
    }

    /** Proves the exact original candidate is owned and its build has actually exited. / 证明精确原候选仍属本任务且构建已实际退出。
     * @param prepared actually executed preparation / 实际执行准备
     * @param session original session / 原会话
     * @param task original task / 原任务
     * @return exact owned inactive candidate / 精确所属非活动候选
     * @throws ApiException if original observation is unavailable / 原观察不可用时 */
    public OperationResult<DeploymentRemoteWorkspace> verifyRetained(Prepared prepared, RemoteSession session,
                                                                     TaskContext task) throws ApiException {
        if (!prepared.consumed || prepared.selection.session != session || prepared.selection.task != task)
            throw new IllegalArgumentException("Retry observation does not belong to the executed original candidate");
        return prepared.channel.verify(prepared.selection.workspace, prepared.selection.plan.request().archive());
    }

    /** Recovers only a reviewed fixed tool branch under the same candidate and time budget, without uploading or building. / 在同候选及时间预算内仅恢复已审固定工具分支，不上传或构建。
     * @param previous actual failed selection / 实际失败选择
     * @param plan full typed newly reviewed plan / 完整类型化新审阅计划
     * @param branch explicitly reviewed fixed catalog branch / 明确审阅固定目录分支
     * @param session original session / 原会话
     * @param interaction original exact approval / 原精确审批
     * @param task original task / 原任务
     * @return installed actual new selection / 实际新安装选择
     * @throws ApiException if original authority refuses / 原权限拒绝时 */
    public OperationResult<Prepared> recoverSelection(Prepared previous, ReviewedDeploymentPlan plan, int branch,
                                                      RemoteSession session, InteractionService interaction, TaskContext task) throws ApiException {
        if (!previous.consumed || previous.selection.session != session || previous.selection.task != task)
            throw new IllegalArgumentException("Recovery selection differs from original task or session");
        var selected = builds.prepareForUpload(plan, session, interaction, task, previous.selection.budget, branch,
            false, previous.selection.workspace);
        if (selected.outcome() != TaskResult.SUCCEEDED)
            return withoutPreparedValue(selected);
        var actual = selected.value().orElseThrow();
        if (!actual.workspace.equals(previous.selection.workspace))
            throw new IllegalArgumentException("Recovery changed original candidate identity");
        var channel = new DeploymentCandidateChannel(session, interaction, plan.request().server(), task,
            actual.rendered.reviewedPlanSha256(), actual.budget, plan.request().layout());
        return new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new Prepared(actual, channel, previous.requiredSpace, previous.availableBytes)),
            Optional.empty());
    }

    /** Builds an existing verified candidate after formal review confirmation; never recreates or uploads it. / 正式审阅确认后构建已有已核候选，绝不重建或上传候选。
     * @param prepared newly confirmed installed selection / 新确认安装选择
     * @param session original session / 原会话
     * @param task original task / 原任务
     * @return actual bounded build outcome / 实际有界构建结果
     * @throws ApiException if host authority rejects / 宿主权限拒绝时 */
    public OperationResult<DeploymentVerifiedBuildResult> rebuild(Prepared prepared, RemoteSession session,
                                                                  TaskContext task) throws ApiException {
        prepared.consume(session, task);
        var verified = prepared.channel.verify(prepared.selection.workspace,
            prepared.selection.plan.request().archive());
        if (verified.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(verified);
        return builds.buildPrepared(prepared.selection, verified.value().orElseThrow(), session, task, false);
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
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(problem(code, event)));
    }

    /** Retains an unconfirmed preparation side effect. / 保留未确认准备副作用。
     * @param event stable event / 稳定事件
     * @return unknown result / 未知结果 */
    private static OperationResult<Prepared> unknownPreparation(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Cancels before a candidate exists. / 在候选存在前取消。
     * @return cancelled preparation / 已取消准备 */
    private static OperationResult<Prepared> cancelledPreparation() {
        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(),
            Optional.of(problem(ProblemCode.CANCELLED, "DEPLOYMENT_CANDIDATE_CANCELLED")));
    }

    /** Detects original cooperative cancellation. / 检测原协作取消。
     * @param task original task / 原任务
     * @return whether cancellation is pending / 是否待取消 */
    private static boolean cancelled(TaskContext task) {
        return task.cancelToken().requested() || Thread.currentThread().isInterrupted();
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
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(problem(code, event)));
    }

    /** Retains an unconfirmed remote effect. / 保留未确认远端副作用。
     * @param event stable event / 稳定事件
     * @return unknown outcome / 未知结果 */
    private static OperationResult<DeploymentVerifiedBuildResult> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Stops before candidate creation. / 在候选创建前停止。
     * @return cancelled result / 已取消结果 */
    private static OperationResult<DeploymentVerifiedBuildResult> cancelledResult() {
        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(),
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
