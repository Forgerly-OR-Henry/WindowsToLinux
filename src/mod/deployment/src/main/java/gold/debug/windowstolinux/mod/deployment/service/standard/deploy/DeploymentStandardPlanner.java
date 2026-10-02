package gold.debug.windowstolinux.mod.deployment.service.standard.deploy;

import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.assessment.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.DeploymentStandardAnalyzer.SnapshotAssessment;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.extension.registry.DeploymentBuildRendererRegistry;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan.*;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;

/** Plans all deterministic single and mixed deployments against the original still-owned source. / 对原仍受管源码规划全部确定性单组件及混合部署。 */
public final class DeploymentStandardPlanner {
    /** Original complete single-component adapter registry. / 原完整单组件适配器登记。 */
    private final ReviewedDeploymentPlanner single = new ReviewedDeploymentPlanner();

    /** Original deterministic component graph planner. / 原确定性组件图规划器。 */
    private final MultiComponentDeploymentPlanner mixed = new MultiComponentDeploymentPlanner();

    /** All supported fixed build renderers. / 全部受支持的固定构建渲染器。 */
    private final DeploymentBuildRendererRegistry renderers = DeploymentBuildRendererRegistry.defaults();

    /** Creates the stateless planner. / 创建无状态规划器。 */
    public DeploymentStandardPlanner() {
    }

    /** A complete component plan with the original graph ordering. / 带原图排序的完整组件计划。
     * @param ordering dependency-safe build and runtime order / 依赖安全的构建和运行顺序
     * @param components exact plans by component ID / 按组件ID索引的精确计划 */
    public record ComponentPlans(MultiComponentDeploymentPlan ordering,
                                 Map<String, ReviewedDeploymentPlan> components) {
        /** Retains each original component once. / 各原组件仅保留一次。
         * @param ordering deterministic graph / 确定性图
         * @param components complete plans / 完整计划 */
        public ComponentPlans {
            Objects.requireNonNull(ordering);
            components = Map.copyOf(components);
            if (!ordering.candidateNamespaces().keySet().equals(components.keySet()))
                throw new IllegalArgumentException("Component plans differ from the admitted graph");
        }
    }

    /** One reviewed plan and its non-executed, fixed build script. / 单个已审阅计划及未执行的固定构建脚本。
     * @param plan original reviewed plan / 原已审阅计划
     * @param reviewedPlanSha256 new-domain pre-execution review identity / 新域执行前审阅身份
     * @param buildScript fixed script text, not an execution grant / 固定脚本文本，非执行授权 */
    public record RenderedPlan(ReviewedDeploymentPlan plan, String reviewedPlanSha256, String buildScript) {
        /** Retains exact plan and rendered content. / 保留精确计划及渲染内容。
         * @param plan original reviewed plan / 原已审阅计划
         * @param reviewedPlanSha256 review digest / 审阅摘要
         * @param buildScript fixed script / 固定脚本 */
        public RenderedPlan {
            Objects.requireNonNull(plan);
            Objects.requireNonNull(reviewedPlanSha256);
            Objects.requireNonNull(buildScript);
            if (buildScript.isBlank())
                throw new IllegalArgumentException("Fixed build script is empty");
        }
    }

    /** Renders a previously planned original source through its exact fixed architecture renderer. / 经精确固定架构渲染器渲染此前规划的原源码。
     * @param plan reviewed single-component plan / 已审阅单组件计划
     * @param task original admitted task / 原准入任务
     * @return fixed script and separate pre-execution identity, without remote effects / 固定脚本与独立执行前身份，不产生远端效果 */
    public OperationResult<RenderedPlan> render(ReviewedDeploymentPlan plan, TaskContext task) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(task);
        if (cancelled(task))
            return cancellation();
        var request = plan.request();
        var source = request.material().snapshot();
        if (!requireOriginal(request.material(), source.id(), request.archive().sourceManifestSha256(), request))
            return cancelled(task) ? cancellation() : unavailable();
        var workspace = new DeploymentRemoteWorkspace(request.facts().applicationId(), request.archive().sha256());
        var renderer = renderers.require(request.facts().projectType(), request.facts().buildTool());
        String script = renderer.render(request.facts(), request.runtime(), workspace, request.limits());
        if (cancelled(task))
            return cancellation();
        if (!requireOriginal(request.material(), source.id(), request.archive().sourceManifestSha256(), request))
            return cancelled(task) ? cancellation() : unavailable();
        return new OperationResult<>(Outcome.SUCCEEDED,
            Optional.of(new RenderedPlan(plan, ReviewedPlanIdentity.from(request), script)), Optional.empty());
    }

    /** Plans one assessment only while its original snapshot and archive remain owned. / 仅在原快照和归档仍受管时规划单组件评估。
     * @param material original source / 原源码
     * @param analysis actual analysis tied to that source / 绑定该源码的实际分析
     * @param request complete reviewed request / 完整审阅请求
     * @param task original admitted task / 原准入任务
     * @return original deterministic plan or cancellation / 原确定性计划或取消 */
    public OperationResult<ReviewedDeploymentPlan> plan(DeploymentSourceMaterial material,
                                                        SnapshotAssessment<DeploymentProjectAssessment> analysis, ReviewedDeploymentRequest request,
                                                        TaskContext task) {
        Objects.requireNonNull(material);
        Objects.requireNonNull(analysis);
        Objects.requireNonNull(request);
        Objects.requireNonNull(task);
        if (cancelled(task))
            return cancellation();
        if (!requireOriginal(material, analysis.snapshotId(), analysis.snapshotSha256(), request))
            return cancelled(task) ? cancellation() : unavailable();
        if (analysis.assessment().facts().isEmpty()
            || !analysis.assessment().facts().orElseThrow().equals(request.facts()))
            throw new IllegalArgumentException("Reviewed facts differ from the actual source assessment");
        var plan = single.plan(request);
        if (cancelled(task))
            return cancellation();
        if (!requireOriginal(material, analysis.snapshotId(), analysis.snapshotSha256(), request))
            return cancelled(task) ? cancellation() : unavailable();
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(plan), Optional.empty());
    }

    /** Plans all deployable components with independent candidates and one dependency order. / 以独立候选和同一依赖顺序规划全部可部署组件。
     * @param material original source / 原源码
     * @param analysis actual mixed assessment / 实际混合评估
     * @param requests complete reviewed component requests / 完整审阅组件请求
     * @param task original admitted task / 原准入任务
     * @return complete graph and component plans or cancellation / 完整图及组件计划或取消 */
    public OperationResult<ComponentPlans> planComponents(DeploymentSourceMaterial material,
                                                          SnapshotAssessment<MultiComponentProjectAssessment> analysis,
                                                          Map<String, ReviewedDeploymentRequest> requests, TaskContext task) {
        Objects.requireNonNull(material);
        Objects.requireNonNull(analysis);
        Objects.requireNonNull(requests);
        Objects.requireNonNull(task);
        if (cancelled(task))
            return cancellation();
        var graph = mixed.plan(analysis.assessment());
        if (!graph.candidateNamespaces().keySet().equals(requests.keySet()))
            throw new IllegalArgumentException("Reviewed requests differ from deployable components");
        var components = new TreeMap<String, ReviewedDeploymentPlan>();
        String serverId = null;
        for (var component : analysis.assessment().components()) {
            if (component.runtime().isEmpty())
                continue;
            if (cancelled(task))
                return cancellation();
            var request = Objects.requireNonNull(requests.get(component.componentId()));
            if (!requireOriginal(material, analysis.snapshotId(), analysis.snapshotSha256(), request))
                return cancelled(task) ? cancellation() : unavailable();
            if (!request.facts().equals(component.facts())
                || !request.runtime().equals(component.runtime().orElseThrow()) || !graph.candidateNamespaces()
                .get(component.componentId()).equals(request.facts().applicationId()))
                throw new IllegalArgumentException("Reviewed component differs from analyzed graph");
            if (serverId != null && !serverId.equals(request.server().id()))
                throw new IllegalArgumentException("One application graph requires one server");
            serverId = request.server().id();
            components.put(component.componentId(), single.plan(request));
        }
        if (cancelled(task))
            return cancellation();
        for (var request : requests.values())
            if (!requireOriginal(material, analysis.snapshotId(), analysis.snapshotSha256(), request))
                return cancelled(task) ? cancellation() : unavailable();
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(new ComponentPlans(graph, components)),
            Optional.empty());
    }

    /** Requires one original frozen source and its own measured tar bytes. / 要求同一原冻结源码及其实际测量的 tar 字节。
     * @param material original still-open material / 原仍开放素材
     * @param snapshotId observed platform UUID / 已观察平台UUID
     * @param manifestSha observed source digest / 已观察源码摘要
     * @param request candidate request / 候选请求
     * @return true while the exact original source and archive remain available / 精确原素材和归档仍可用时为真 */
    private static boolean requireOriginal(DeploymentSourceMaterial material, UUID snapshotId, String manifestSha,
                                           ReviewedDeploymentRequest request) {
        try {
            var source = material.snapshot();
            source.directory();
            request.archive().path();
            if (!source.id().equals(snapshotId) || !source.sha256().equals(manifestSha)
                || !source.id().equals(request.material().snapshot().id())
                || !source.sha256().equals(request.material().snapshot().sha256())
                || !source.id().equals(request.archive().originSnapshotId())
                || !source.sha256().equals(request.archive().sourceManifestSha256()))
                throw new IllegalArgumentException("Reviewed source or archive does not belong to this assessment");
            return true;
        } catch (IllegalStateException closed) {
            return false;
        }
    }

    /** Checks original cooperative cancellation. / 检查原任务协作取消。
     * @param task original task / 原任务
     * @return true only after a cancellation request / 仅请求取消后为真 */
    private static boolean cancelled(TaskContext task) {
        return task.cancelToken().requested() || Thread.currentThread().isInterrupted();
    }

    /** Returns a safe cancelled plan without claiming a remote effect. / 返回安全取消的计划，不声称远端效果。
     * @param <T> plan type / 计划类型
     * @return cancelled result / 已取消结果 */
    private static <T> OperationResult<T> cancellation() {
        return new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional.of(new Problem(ProblemCode.CANCELLED,
            "DEPLOYMENT_PLAN_CANCELLED", "Standard planning was cancelled", Map.of(), Optional.empty())));
    }

    /** Returns a fixed source-unavailable result for an already closed original handle. / 原句柄已关闭时返回固定来源不可用结果。
     * @param <T> plan type / 计划类型
     * @return failed source observation / 失败的来源观测 */
    private static <T> OperationResult<T> unavailable() {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
            Optional.of(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_PLAN_SOURCE_UNAVAILABLE",
                "Original source material is unavailable", Map.of(), Optional.empty())));
    }
}
