package gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish;

import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;

import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentModelToolLoop;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentAssistedBuildCorrection;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.DeploymentStandardCandidateRunner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.DeploymentVerifiedBuildResult;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup.DeploymentPublishAuthorSession;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup.DeploymentPublishCleanupActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup.DeploymentPublishCleanupAttempt;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.single.DeploymentStandardSinglePublishRunner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.single.DeploymentStandardSinglePublishTransaction;

/** Assisted tool loop on shared verified publication primitives. / 在共用已核实发布能力上的辅助工具循环。 */
public final class DeploymentAssistedSinglePublishRunner extends DeploymentStandardSinglePublishRunner {
    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param references original formal publication and reference flow / 原正式发布及引用流程 */
    public DeploymentAssistedSinglePublishRunner(ModContext context, DeploymentRefFlow references) {
        super(context, references);
    }

    /** Identifies whether the captured entry permits assisted extensions. / 标识已捕获入口是否允许辅助扩展。
     * @param plan original deterministically reviewed build or deployment plan / 原确定性审阅构建或部署计划
     * @param unit original reviewed unit identity / 原已审阅单元身份
     * @param credentials exact original secret revision bindings / 精确原秘密修订绑定
     * @param task original admitted task / 原已准入任务
     * @param session original session bound to this operation / 当前操作绑定的原session值
     * @param journal original captured policy or publication journal / 原已捕获策略或发布日志
     * @param correction original task-bound bounded correction adapter / 原任务绑定有界纠正适配
     * @return classified original assisted publication result / 分类原辅助发布结果
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    protected OperationResult<DeploymentPublishResult> assisted(ReviewedDeploymentPlan plan, DeploymentUnitIdentity unit,
                                                                Map<DeploymentSecretRef, SecretId> credentials, TaskContext task, gold.debug.windowstolinux.appapi.linux.session.RemoteSession session,
                                                                DeploymentPublishCleanupActionLog journal, DeploymentAssistedBuildCorrection correction) throws Exception {
        var candidates = new DeploymentStandardCandidateRunner();
        var loop = new DeploymentAssistedPublishBuild(context, candidates);
        /** Mutable facts confined to one original assisted publication. / 限于一项原辅助发布的可变事实。 */
        class State {
            /** Current reviewed plan of this original publication. / 本次原发布的当前已审阅计划。 */
            ReviewedDeploymentPlan current = plan;

            /** Verified candidate preparation for this original attempt. / 本次原尝试的已核实候选准备。 */
            DeploymentStandardCandidateRunner.Prepared prepared;

            /** Verified build result for this original candidate. / 本次原候选的已核实构建结果。 */
            DeploymentVerifiedBuildResult built;

            /** Verified failure retained after original rollback. / 原回滚后保留的已核实失败。 */
            OperationResult<DeploymentPublishResult> restoredFailure;

            /** Verified host prerequisite gap before candidate upload. / 候选上传前的已核实主机前置缺口。 */
            OperationResult<DeploymentStandardCandidateRunner.Prepared> preparationGap;
        }
        var state = new State();
        var journals = Map.of(unit.id(), journal);
        try (var owned = new DeploymentPublishAuthorSession(session, journals, null)) {
            return DeploymentAssistedDeployment.run(
                context, owned::current, unit.server(), task, () -> state.current, () -> {
                    var tools = new java.util.LinkedHashMap<String, DeploymentModelToolLoop.Tool<DeploymentPublishResult>>();
                    if (state.preparationGap != null)
                        tools.put("stop", new DeploymentModelToolLoop.Tool<>(
                            DeploymentSkillResources.description(DeploymentAssistedSinglePublishRunner.class, "assisted-single-publish-runner-tools.properties", "stop"), args -> {
                            DeploymentJsonFields.exact(args, java.util.Set.of());
                            if (!journal.verifiedBusiness(TaskResult.FAILED, state.preparationGap.problem())
                                || !journal.remoteClean() || !owned.retire())
                                return DeploymentModelToolLoop.Feedback.ended(unknown("DEPLOYMENT_ASSISTED_PREPARE_RESULT_UNCONFIRMED"));
                            return DeploymentModelToolLoop.Feedback.ended(
                                new OperationResult<DeploymentPublishResult>(TaskResult.FAILED, Optional.empty(), state.preparationGap.problem()));
                        }));
                    if (state.restoredFailure != null) {
                        tools.put("finish", new DeploymentModelToolLoop.Tool<>(
                            DeploymentSkillResources.description(DeploymentAssistedSinglePublishRunner.class, "assisted-single-publish-runner-tools.properties", "finish"), args -> {
                            DeploymentJsonFields.exact(args, java.util.Set.of());
                            return DeploymentModelToolLoop.Feedback.ended(state.restoredFailure);
                        }));
                        tools.put("retry", new DeploymentModelToolLoop.Tool<>(
                            DeploymentSkillResources.description(DeploymentAssistedSinglePublishRunner.class, "assisted-single-publish-runner-tools.properties", "retry"), args -> {
                            DeploymentJsonFields.exact(args, java.util.Set.of());
                            var next = new DeploymentAssistedPublishAttempts(context, loop, candidates).advance(
                                Map.of(unit.id(), state.current), Map.of(unit.id(), unit), Map.of(unit.id(), correction),
                                unit.id(), "", null, journals, state.restoredFailure.problem().orElseThrow(), task);
                            if (next.outcome() != TaskResult.SUCCEEDED)
                                return DeploymentModelToolLoop.Feedback.ended(
                                    new OperationResult<DeploymentPublishResult>(next.outcome(), Optional.empty(), next.problem()));
                            var continuation = next.value().orElseThrow();
                            owned.next(continuation.session());
                            state.current = continuation.plans().get(unit.id());
                            state.prepared = continuation.prepared().get(unit.id());
                            state.built = null;
                            state.restoredFailure = null;
                            return DeploymentModelToolLoop.Feedback.observed("Distinct corrected operation prepared; standard build is available");
                        }));
                    } else if (state.prepared == null)
                        tools.put("prepare", new DeploymentModelToolLoop.Tool<>(
                            DeploymentSkillResources.description(DeploymentAssistedSinglePublishRunner.class, "assisted-single-publish-runner-tools.properties", "prepare"), args -> {
                            DeploymentJsonFields.exact(args, java.util.Set.of());
                            var prepared = loop.prepare(state.current, owned.current(), task);
                            if (prepared.outcome() != TaskResult.SUCCEEDED) {
                                if (DeploymentAssistedPublishBuild.preparationGap(prepared)) {
                                    state.preparationGap = prepared;
                                    return DeploymentModelToolLoop.Feedback.observed(
                                        DeploymentAssistedPublishBuild.preparationObservation(prepared));
                                }
                                if (prepared.outcome() == TaskResult.UNKNOWN || prepared.outcome() == TaskResult.CANCELLED)
                                    return DeploymentModelToolLoop.Feedback.ended(unknown("DEPLOYMENT_ASSISTED_PREPARE_UNCONFIRMED"));
                                if (!journal.verifiedBusiness(TaskResult.FAILED, prepared.problem()) || !journal.remoteClean() || !owned.retire())
                                    return DeploymentModelToolLoop.Feedback.ended(unknown("DEPLOYMENT_ASSISTED_PREPARE_RESULT_UNCONFIRMED"));
                                return DeploymentModelToolLoop.Feedback.ended(new OperationResult<DeploymentPublishResult>(TaskResult.FAILED, Optional.empty(), prepared.problem()));
                            }
                            state.prepared = prepared.value().orElseThrow();
                            state.preparationGap = null;
                            if (!loop.prepared(state.prepared))
                                throw new IllegalStateException("Assisted preparation budget unconfirmed");
                            return DeploymentModelToolLoop.Feedback.observed("Prepared verified toolchains; ready for standard build");
                        }));
                    else if (state.built == null)
                        tools.put("build", new DeploymentModelToolLoop.Tool<>(
                            DeploymentSkillResources.description(DeploymentAssistedSinglePublishRunner.class, "assisted-single-publish-runner-tools.properties", "build"), args -> {
                            DeploymentJsonFields.exact(args, java.util.Set.of());
                            var completed = loop.build(state.prepared, correction, unit, journal, null, () -> "", owned.current(), task);
                            if (completed.result().outcome() != TaskResult.SUCCEEDED)
                                return DeploymentModelToolLoop.Feedback.ended(assistedBuildFailure(completed, unit, task, journal, owned));
                            state.current = completed.plan();
                            state.built = completed.result().value().orElseThrow();
                            return DeploymentModelToolLoop.Feedback.observed("Build verified; publication is available");
                        }));
                    else
                        tools.put("publish", new DeploymentModelToolLoop.Tool<>(
                            DeploymentSkillResources.description(DeploymentAssistedSinglePublishRunner.class, "assisted-single-publish-runner-tools.properties", "publish"), args -> {
                            DeploymentJsonFields.exact(args, java.util.Set.of());
                            var result = new DeploymentStandardSinglePublishTransaction(context).publish(state.current, unit,
                                state.built, credentials, owned.current(), task, journal);
                            boolean retired = owned.retire();
                            if (retired && result.outcome() == TaskResult.FAILED && result.problem().isPresent()
                                && unit.id().equals(result.problem().orElseThrow().context().get("publicationHealthFailure"))) {
                                state.restoredFailure = result;
                                return DeploymentModelToolLoop.Feedback.observed("Publication health failed; standard rollback and cleanup verified. " + result.problem().orElseThrow().eventCode());
                            }
                            return DeploymentModelToolLoop.Feedback.ended(result);
                        }));
                    return tools;
                }, () -> Map.of("application", unit.id(), "plan", state.current.request().runtime().toString(),
                    "reviewedSource", Map.of("archiveSha256", state.current.request().archive().sha256(),
                        "buildTool", state.current.request().facts().buildTool().name(), "buildDirectory", state.current.request().facts().buildDirectory(),
                        "handling", "Standard build uploads and verifies this frozen archive and executes the reviewed recipe; source discovery is already complete"),
                    "prepared", state.prepared != null, "built", state.built != null,
                    "restoredFailure", state.restoredFailure == null ? "" : state.restoredFailure.problem().orElseThrow().eventCode()));
        }
    }

    /** Classifies a verified build failure before bounded correction or cleanup. / 在有界纠正或清理前分类已核实构建失败。
     * @param completed verified build result for the original attempt / 原尝试的已核实构建结果
     * @param unit original reviewed unit identity / 原已审阅单元身份
     * @param task original admitted task / 原已准入任务
     * @param journal original captured policy or publication journal / 原已捕获策略或发布日志
     * @param owned owner of the original publication session / 原发布会话所有者
     * @return classified original failure after verified cleanup / 已核实清理后的分类原失败
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    private OperationResult<DeploymentPublishResult> assistedBuildFailure(DeploymentAssistedPublishBuild.Completed completed,
                                                                          DeploymentUnitIdentity unit, TaskContext task, DeploymentPublishCleanupActionLog journal,
                                                                          DeploymentPublishAuthorSession owned) throws Exception {
        if (completed.result().outcome() != TaskResult.FAILED)
            return unknown("DEPLOYMENT_ASSISTED_CANDIDATE_UNCONFIRMED");
        var channel = new DeploymentPublishChannel(owned.current(), context.interaction(), unit.server(), task, journal.facts().path("planSha256").asText(), unit.layout());
        if (!journal.verifiedBusiness(TaskResult.FAILED, completed.result().problem())
            || !DeploymentPublishCleanupAttempt.run(journal, "CANDIDATE_CLEANUP_MAY_HAVE_RUN", () -> channel.cleanupCandidate(unit, journal.facts().path("candidate").asText()))
            || !journal.mark("CANDIDATE_CLEANED") || !journal.remoteClean() || !owned.retire())
            return unknown("DEPLOYMENT_ASSISTED_CANDIDATE_CLEANUP_UNCONFIRMED");
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), completed.result().problem());
    }
}
