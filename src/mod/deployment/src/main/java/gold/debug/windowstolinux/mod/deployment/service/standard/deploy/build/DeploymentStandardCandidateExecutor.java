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
        Objects.requireNonNull(plan);
        Objects.requireNonNull(session);
        Objects.requireNonNull(interaction);
        Objects.requireNonNull(task);
        if (cancelled(task))
            return cancelledResult();
        var rendered = planner.render(plan, task);
        if (rendered.outcome() != Outcome.SUCCEEDED)
            return withoutValue(rendered);
        var request = plan.request();
        long footprint, requiredSpace;
        try {
            footprint = Math.addExact(request.archive().byteCount(), request.archive().uncompressedByteCount());
            requiredSpace = Math.max(Math.multiplyExact(request.archive().byteCount(), 3),
                    request.limits().maxWorkspaceBytes());
        } catch (ArithmeticException overflow) {
            return failed(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SOURCE_SIZE_OVERFLOW");
        }
        if (footprint > request.limits().maxWorkspaceBytes())
            return failed(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_SOURCE_WORKSPACE_LIMIT");
        var budget = new DeploymentBuildBudget(request.limits().timeoutSeconds());
        var channel = new DeploymentCandidateChannel(session, interaction, request.server(), task,
                rendered.value().orElseThrow().reviewedPlanSha256(), budget);
        var helper = new DeploymentManagedHelper(session, interaction, request.server(), task,
                rendered.value().orElseThrow().reviewedPlanSha256()).probe();
        if (helper.outcome() != Outcome.SUCCEEDED)
            return withoutValue(helper);
        var facts = channel.hostFacts();
        if (facts.outcome() != Outcome.SUCCEEDED)
            return withoutValue(facts);
        if (DeploymentHostSupportEvaluator.evaluatePlatform(facts.value().orElseThrow())
                .support() != DeploymentHostSupportStatus.READY_FOR_RUNTIME_VALIDATION)
            return failed(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_HOST_UNSUPPORTED");
        var capacity = channel.availableBytes();
        if (capacity.outcome() != Outcome.SUCCEEDED)
            return withoutValue(capacity);
        if (capacity.value().orElseThrow() < requiredSpace)
            return failed(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_TARGET_SPACE");
        var prepared = builds.prepareForUpload(plan, session, interaction, task, budget);
        if (prepared.outcome() != Outcome.SUCCEEDED)
            return withoutValue(prepared);
        var selection = prepared.value().orElseThrow();
        if (!selection.rendered.reviewedPlanSha256().equals(rendered.value().orElseThrow().reviewedPlanSha256()))
            return unknown("DEPLOYMENT_PLAN_IDENTITY_CHANGED");
        var preparedFacts = channel.hostFacts();
        if (preparedFacts.outcome() != Outcome.SUCCEEDED)
            return withoutValue(preparedFacts);
        if (DeploymentHostSupportEvaluator
                .evaluate(preparedFacts.value().orElseThrow(), request.facts(), request.runtime(), selection.tools)
                .support() != DeploymentHostSupportStatus.READY_FOR_RUNTIME_VALIDATION)
            return failed(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_RUNTIME_UNSUPPORTED");
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
