package gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish;

import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentModelToolLoop;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentAssistedBuildCorrection;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.DeploymentStandardCandidateRunner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.DeploymentVerifiedBuildResult;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.MultiPartDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup.DeploymentPublishAuthorSession;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup.DeploymentPublishCleanupActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph.DeploymentStandardGraphPublishActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph.DeploymentStandardGraphPublishRunner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph.DeploymentStandardGraphPublishTransaction;

/** Assisted tool loop on shared verified publication primitives. / 在共用已核实发布能力上的辅助工具循环。 */
public final class DeploymentAssistedGraphPublishRunner extends DeploymentStandardGraphPublishRunner {
    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param references original formal publication and reference flow / 原正式发布及引用流程 */
    public DeploymentAssistedGraphPublishRunner(ModContext context, DeploymentRefFlow references) {
        super(context, references);
    }

    /** Offers dependency-checked graph tools under the original publication session. / 在原发布会话下提供经依赖核对的整图工具。
     * @param ordering reviewed original component dependency order / 已审阅原组件依赖顺序
     * @param originalPlans original reviewed component plans before correction / 纠正前原已审阅组件计划
     * @param units exact original component unit identities / 精确原组件单元身份
     * @param credentials exact original secret revision bindings / 精确原秘密修订绑定
     * @param healthComponent original reviewed graph health owner / 原已审阅整图健康检查所有者
     * @param health original deterministic health contract / 原确定性健康检查合同
     * @param owned owner of the original publication session / 原发布会话所有者
     * @param task original admitted task / 原已准入任务
     * @param root original whole-graph journal or source root / 原整图日志或来源根
     * @param journals original component cleanup journals / 原组件清理日志
     * @param corrections original task-bound bounded correction adapters / 原任务绑定有界纠正适配
     * @return classified original graph publication result / 分类原整图发布结果
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    protected OperationResult<DeploymentPublishResult> assistedTools(MultiPartDeploymentPlan ordering,
                                                                     Map<String, ReviewedDeploymentPlan> originalPlans,
                                                                     Map<String, DeploymentUnitIdentity> units, Map<DeploymentSecretRef, SecretId> credentials,
                                                                     String healthComponent, DeploymentHealthCheck health, DeploymentPublishAuthorSession owned, TaskContext task,
                                                                     DeploymentStandardGraphPublishActionLog root, Map<String, DeploymentPublishCleanupActionLog> journals,
                                                                     Map<String, DeploymentAssistedBuildCorrection> corrections) throws Exception {
        var plans = new LinkedHashMap<>(originalPlans);
        var loop = new DeploymentAssistedPublishBuild(context, candidates);
        var prepared = new LinkedHashMap<String, DeploymentStandardCandidateRunner.Prepared>();
        var builds = new LinkedHashMap<String, DeploymentVerifiedBuildResult>();
        var preparationGaps = new LinkedHashMap<String, OperationResult<DeploymentStandardCandidateRunner.Prepared>>();
        var restored = new AtomicReference<OperationResult<DeploymentPublishResult>>();
        return DeploymentAssistedDeployment.run(
            context, owned::current, units.get(ordering.startOrder().getFirst()).server(), task,
            () -> plans.get(ordering.startOrder().getFirst()), () -> {
                var tools = new LinkedHashMap<String, DeploymentModelToolLoop.Tool<DeploymentPublishResult>>();
                if (!preparationGaps.isEmpty())
                    tools.put("stop", new DeploymentModelToolLoop.Tool<>(
                        DeploymentSkillResources.description(DeploymentAssistedGraphPublishRunner.class, "assisted-graph-publish-runner-tools.properties", "stop"), args -> {
                        DeploymentJsonFields.exact(args, java.util.Set.of());
                        return DeploymentModelToolLoop.Feedback.ended(
                            beforeCandidateFailure(ordering, plans, units, owned.current(), task, root, journals,
                                preparationGaps.values().iterator().next()));
                    }));
                if (restored.get() != null) {
                    tools.put("finish", new DeploymentModelToolLoop.Tool<>(
                        DeploymentSkillResources.description(DeploymentAssistedGraphPublishRunner.class, "assisted-graph-publish-runner-tools.properties", "finish"), args -> {
                        DeploymentJsonFields.exact(args, java.util.Set.of());
                        return DeploymentModelToolLoop.Feedback.ended(restored.get());
                    }));
                    tools.put("retry", new DeploymentModelToolLoop.Tool<>(
                        DeploymentSkillResources.description(DeploymentAssistedGraphPublishRunner.class, "assisted-graph-publish-runner-tools.properties", "retry"), args -> {
                        DeploymentJsonFields.exact(args, java.util.Set.of());
                        var failure = restored.get().problem().orElseThrow();
                        String selectedUnit = failure.context().get("publicationHealthFailure");
                        String selected = units.entrySet().stream().filter(entry -> entry.getValue().id().equals(selectedUnit)).findFirst().orElseThrow().getKey();
                        var next = new DeploymentAssistedPublishAttempts(context, loop, candidates).advance(plans,
                            units, corrections, selected, graphDescription(ordering, plans, healthComponent, health),
                            root, journals, failure, task);
                        if (next.outcome() != TaskResult.SUCCEEDED)
                            return DeploymentModelToolLoop.Feedback.ended(
                                new OperationResult<DeploymentPublishResult>(next.outcome(), Optional.empty(), next.problem()));
                        var continuation = next.value().orElseThrow();
                        owned.next(continuation.session());
                        plans.clear();
                        plans.putAll(continuation.plans());
                        prepared.clear();
                        prepared.putAll(continuation.prepared());
                        builds.clear();
                        restored.set(null);
                        return DeploymentModelToolLoop.Feedback.observed("Distinct corrected graph operation prepared; dependency-checked builds are available");
                    }));
                    return tools;
                }
                registerComponentTools(tools, ordering, plans, units, owned, task, root, journals,
                    corrections, healthComponent, health, loop, prepared, builds, preparationGaps);
                if (builds.size() == plans.size())
                    tools.put("publish", new DeploymentModelToolLoop.Tool<>(
                        DeploymentSkillResources.description(DeploymentAssistedGraphPublishRunner.class, "assisted-graph-publish-runner-tools.properties", "publish"), args -> {
                        DeploymentJsonFields.exact(args, java.util.Set.of());
                        var result = new DeploymentStandardGraphPublishTransaction(context).publish(ordering, plans, units, credentials,
                            builds, healthComponent, health, owned.current(), task, root, journals);
                        boolean retired = owned.retire();
                        if (retired && result.outcome() == TaskResult.FAILED && result.problem().isPresent()
                            && units.values().stream().anyMatch(unit -> unit.id().equals(result.problem().orElseThrow().context().get("publicationHealthFailure")))) {
                            restored.set(result);
                            return DeploymentModelToolLoop.Feedback.observed("Graph health failed; standard rollback and cleanup verified. " + result.problem().orElseThrow().eventCode());
                        }
                        return DeploymentModelToolLoop.Feedback.ended(result);
                    }));
                return tools;
            }, () -> Map.of("application", ordering.applicationId(), "dependencies", ordering.dependencies(),
                "prepared", List.copyOf(prepared.keySet()), "built", List.copyOf(builds.keySet()),
                "restoredFailure", restored.get() == null ? "" : restored.get().problem().orElseThrow().eventCode(),
                "reviewedGraph", graphDescription(ordering, plans, healthComponent, health)));
    }

    /** Registers preparation and build tools with the original graph dependency guards. / 按原整图依赖防护登记准备和构建工具。
     * @param tools original ordered tool set / 原有序工具集合
     * @param ordering original reviewed dependency order / 原已审阅依赖顺序
     * @param plans current original reviewed plans / 当前原已审阅计划
     * @param units exact original unit identities / 精确原单元身份
     * @param owned original publication session owner / 原发布会话所有者
     * @param task original admitted task / 原已准入任务
     * @param root original graph journal / 原整图日志
     * @param journals original component cleanup journals / 原组件清理日志
     * @param corrections original bounded correction adapters / 原有界纠正适配
     * @param healthComponent original health owner / 原健康检查所有者
     * @param health original health contract / 原健康检查合同
     * @param loop original assisted build loop / 原辅助构建循环
     * @param prepared verified original preparations / 已核实原准备
     * @param builds verified original builds / 已核实原构建
     * @param preparationGaps verified original prerequisite gaps / 已核实原前置缺口 */
    private void registerComponentTools(Map<String, DeploymentModelToolLoop.Tool<DeploymentPublishResult>> tools,
                                        MultiPartDeploymentPlan ordering,
                                        Map<String, ReviewedDeploymentPlan> plans,
                                        Map<String, DeploymentUnitIdentity> units, DeploymentPublishAuthorSession owned, TaskContext task,
                                        DeploymentStandardGraphPublishActionLog root, Map<String, DeploymentPublishCleanupActionLog> journals,
                                        Map<String, DeploymentAssistedBuildCorrection> corrections, String healthComponent, DeploymentHealthCheck health,
                                        DeploymentAssistedPublishBuild loop, Map<String, DeploymentStandardCandidateRunner.Prepared> prepared,
                                        Map<String, DeploymentVerifiedBuildResult> builds,
                                        Map<String, OperationResult<DeploymentStandardCandidateRunner.Prepared>> preparationGaps) {
        var prepareActions = new LinkedHashMap<String, DeploymentModelToolLoop.Action<DeploymentPublishResult>>();
        var buildActions = new LinkedHashMap<String, DeploymentModelToolLoop.Action<DeploymentPublishResult>>();
        for (String id : ordering.startOrder()) {
            if (!prepared.containsKey(id)) prepareActions.put(id, args -> {
                DeploymentJsonFields.exact(args, java.util.Set.of());
                var observed = loop.prepare(plans.get(id), owned.current(), task);
                if (DeploymentAssistedPublishBuild.preparationGap(observed)) {
                    preparationGaps.put(id, observed);
                    return DeploymentModelToolLoop.Feedback.observed(
                        "Component " + id + " " + DeploymentAssistedPublishBuild.preparationObservation(observed));
                }
                if (observed.outcome() != TaskResult.SUCCEEDED)
                    return DeploymentModelToolLoop.Feedback.ended(
                        observed.outcome() == TaskResult.UNKNOWN ? unknown("DEPLOYMENT_GRAPH_PREPARE_UNCONFIRMED")
                            : beforeCandidateFailure(ordering, plans, units, owned.current(), task, root, journals, observed));
                var selection = observed.value().orElseThrow();
                if (!loop.prepared(selection))
                    throw new IllegalStateException("Assisted preparation budget unconfirmed");
                prepared.put(id, selection);
                preparationGaps.remove(id);
                if (prepared.size() == plans.size()) {
                    long required = 0, available = Long.MAX_VALUE;
                    for (var value : prepared.values()) {
                        required = Math.addExact(required, value.requiredSpace());
                        available = Math.min(available, value.availableBytes());
                    }
                    if (Math.addExact(required, plans.values().stream().mapToLong(value -> value.request().limits().hostReserveBytes()).max().orElseThrow()) > available)
                        throw new IllegalStateException("Graph target capacity insufficient");
                    if (!root.mark("ALL_PREPARED"))
                        throw new IllegalStateException("Preparation checkpoint unconfirmed");
                }
                return DeploymentModelToolLoop.Feedback.observed("Component " + id + " prepared");
            });
            if (prepared.size() == plans.size() && !builds.containsKey(id)
                && builds.keySet().containsAll(ordering.dependencies().get(id)))
                buildActions.put(id, args -> {
                    DeploymentJsonFields.exact(args, java.util.Set.of());
                    var completed = loop.build(prepared.get(id), corrections.get(id), units.get(id), journals.get(id),
                        root, () -> graphDescription(ordering, plans, healthComponent, health), owned.current(), task);
                    plans.put(id, completed.plan());
                    var observed = completed.result();
                    if (observed.outcome() == TaskResult.UNKNOWN || observed.outcome() == TaskResult.CANCELLED)
                        gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.candidateTransferFailure(context, task, observed);
                    if (observed.outcome() != TaskResult.SUCCEEDED)
                        return DeploymentModelToolLoop.Feedback.ended(
                            observed.outcome() == TaskResult.UNKNOWN || observed.outcome() == TaskResult.CANCELLED
                                ? unknown("DEPLOYMENT_GRAPH_CANDIDATE_UNCONFIRMED")
                                : candidateFailure(ordering, plans, units, owned.current(), task, root, journals, observed));
                    builds.put(id, observed.value().orElseThrow());
                    if (builds.size() == plans.size() && !root.mark("ALL_BUILT"))
                        throw new IllegalStateException("Build checkpoint unconfirmed");
                    return DeploymentModelToolLoop.Feedback.observed("Component " + id + " build verified");
                });
        }
        if (!prepareActions.isEmpty())
            tools.put("prepare", componentTool("Prepare an explicitly selected available component: " + prepareActions.keySet(), prepareActions));
        if (!buildActions.isEmpty())
            tools.put("build", componentTool("Build an explicitly selected available component: " + buildActions.keySet(), buildActions));
    }

    private static DeploymentModelToolLoop.Tool<DeploymentPublishResult> componentTool(String description,
                                                                                       Map<String, DeploymentModelToolLoop.Action<DeploymentPublishResult>> actions) {
        return new DeploymentModelToolLoop.Tool<>(description,
            gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas.object("component").toString(), args -> {
            DeploymentJsonFields.exact(args, java.util.Set.of("component"));
            String component = DeploymentJsonFields.text(args, "component", 63);
            var action = actions.get(component);
            if (action == null) throw new IllegalArgumentException("Component is unavailable for this action");
            return action.run(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        });
    }
}
