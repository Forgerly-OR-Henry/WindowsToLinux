package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;

import java.nio.charset.StandardCharsets;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentModelToolLoop;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishArguments;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishChannel;
import gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentContainerArguments;
import gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentRuntimeArguments;
import gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentWorkloadArguments;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.skill.DeploymentAgentSkills;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** Autonomous graph publication using shared verified delivery primitives. / 使用共用已核实交付能力的自主整图发布。 */
public final class DeploymentAgentPublishRunner {
    /** Bundled fixed flow instruction. / 随包固定流程说明。 */
    private static final String POLICY = DeploymentSkillResources.text(DeploymentAgentPublishRunner.class, "autonomous-publication.md") + gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelExecutionContract.EXECUTION_CONTRACT;

    /** Releases known original resource children at the verified finish boundary. / 在核实完成边界释放确知原资源子项。 */
    @FunctionalInterface
    public interface ResourceRelease {
        /** Settles original evidence without replay. / 不重放地结清原依据。
         * @throws Exception unconfirmed resource / 未确认资源 */
        void releaseKnown() throws Exception;
    }

    /** Fixed original neutral application graph root identity. / 固定原中立应用图根身份。 */
    private static final String ROOT = "/gold/debug/windowstolinux/mod/deployment/linux/helper/";

    /** Supported original typed build and publication contracts. / 支持的原类型化构建及发布合同。 */
    private static final Set<String> CONTRACTS = Set.of(
        "00-protocol-foundation.sh",
        "input/15-deployment-input.sh",
        "input/16-application-input.sh",
        "input/17-managed-content.sh",
        "ecosystem/35-ecosystem-dispatch.sh",
        "runtime/41-application-runtime.sh",
        "runtime/42-application-job.sh",
        "runtime/43-application-container.sh",
        "runtime/44-application-health.sh",
        "runtime/45-application-container-contract.sh",
        "runtime/systemd/61-service-identity.sh",
        "runtime/systemd/systemd-manager-isolation.sh",
        "runtime/container/55-podman-quadlet.sh");

    private enum FinishPhase {RESOURCE_RELEASE, MODEL_COMMAND_RELEASE, CLEANUP_EVIDENCE, CANDIDATE_CLEANUP, FINALLY_MODEL_COMMAND_RELEASE}

    private static final Set<String> SAFE_API_EVENTS = Set.of("TASK_CHECKPOINT_READ", "TASK_CHECKPOINT_RELEASE",
        "TASK_CHECKPOINT_UPDATE", "SERVICE_WORKER", "MOD_TASK_BINDING_CLOSED");

    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。 */
    private DeploymentAgentPublishRunner() {
    }

    /** Advances the original bounded tool session using verified feedback. / 使用已核实反馈推进原有界工具会话。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param session original session bound to this operation / 当前操作绑定的原session值
     * @param server exact original target server / 精确原目标服务器
     * @param task original admitted task / 原已准入任务
     * @param revision captured original model revision / 已捕获原模型修订
     * @param proposal original reviewed publication template / 原已审阅发布模板
     * @param candidates shared verified candidate preparation runner / 共用已核实候选准备执行器
     * @param journal original captured policy or publication journal / 原已捕获策略或发布日志
     * @param guard original independent command review adapter / 原独立命令审核适配
     * @param remainingDecisions original remaining Decisions bound to this operation / 当前操作绑定的原remainingDecisions值
     * @return verified terminal tool-loop result / 已核实终态工具循环结果
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时
     * @param releaseResources Original known-resource settlement / 原已知资源结清
     */
    public static OperationResult<DeploymentPublishResult> run(ModContext context, RemoteSession session, ServerId server,
                                                               TaskContext task, String revision, DeploymentPublishResult proposal,
                                                               Map<String, DeploymentGraphPublisher.Candidate> candidates, DeploymentAgentActionLog journal,
                                                               DeploymentSendGuard guard, int remainingDecisions, ResourceRelease releaseResources) throws Exception {
        return run(context, session, server, task, revision, proposal, candidates, journal, guard, remainingDecisions,
            releaseResources, accepted -> {
            });
    }

    static OperationResult<DeploymentPublishResult> run(ModContext context, RemoteSession session, ServerId server,
                                                        TaskContext task, String revision, DeploymentPublishResult proposal,
                                                        Map<String, DeploymentGraphPublisher.Candidate> candidates, DeploymentAgentActionLog journal,
                                                        DeploymentSendGuard guard, int remainingDecisions, ResourceRelease releaseResources,
                                                        java.util.function.Consumer<DeploymentAgentFailedPublication> acceptFailure) throws Exception {
        var remoteLayout = proposal.graph().components().getFirst().unit().layout();
        var receipts = new DeploymentAgentPublicationReceipts(session, server, revision, proposal);
        var publisher = new DeploymentGraphPublisher(context, receipts, server, task,
            (type, action, detail) -> {
                var prior = journal.events(task);
                long previous = prior.isEmpty() ? 0 : prior.getLast().id();
                journal.event(task, type, action, detail);
                var retained = journal.events(task).stream().filter(event -> event.id() > previous && event.type().equals(type)
                    && event.action().equals(action) && event.detail().equals(detail)).toList();
                if (retained.size() != 1) throw new IllegalStateException("Publication event identity is not unique");
                receipts.journaled(retained.getFirst());
            }, guard);
        var stagedDigests = new HashMap<String, Map<gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef, String>>();
        var verifiedCurrent = new java.util.concurrent.atomic.AtomicBoolean();
        var verificationFailure = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>(Map.of());
        var stagedCurrent = new java.util.concurrent.atomic.AtomicBoolean();
        var standardSent = new java.util.concurrent.atomic.AtomicBoolean();
        var failedNative = new java.util.concurrent.atomic.AtomicReference<OperationResult<DeploymentPublishResult>>();
        var modifiedAfterPublish = new java.util.concurrent.atomic.AtomicBoolean();
        var finishPhase = new java.util.concurrent.atomic.AtomicReference<FinishPhase>();
        var commandBoundary = new java.util.concurrent.atomic.AtomicReference<Optional<gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot>>(Optional.empty());
        var judgement = new DeploymentAgentPublicationJudgement(
            gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.hash(proposal.toString()), task, journal);
        journal.event(task, "AI_PUBLICATION", proposal.graph().applicationId(), "MODEL_GENERATED_COMMANDS");
        var skills = new DeploymentAgentSkills(task, DeploymentAgentSkills.Role.DEPLOYMENT);
        var loop = new DeploymentModelToolLoop<DeploymentPublishResult>(context, task, revision, POLICY,
            Math.min(remainingDecisions, gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution().localToolTurns()), judgement, skills::system,
            () -> gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoalBudget.beforeModel(task, false), judgement::feedback);
        var commandSchema = gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas.object("script", "seconds:integer", "reason");
        ((com.fasterxml.jackson.databind.node.ObjectNode) commandSchema.at("/properties/seconds")).put("minimum", 1).put("maximum", 7200);
        try {
            return loop.run(() -> {
                var offered = new LinkedHashMap<String, DeploymentModelToolLoop.Tool<DeploymentPublishResult>>();
                offered.putAll(Map.of(
                    "skill", new DeploymentModelToolLoop.Tool<>("Read and activate a product skill from current skills metadata. No execution authority.",
                        gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas.object("id").toString(), args -> {
                        DeploymentJsonFields.exact(args, Set.of("id"));
                        return DeploymentModelToolLoop.Feedback.observed(DeploymentAiJson.facts(
                            skills.read(DeploymentJsonFields.text(args, "id", 63), task), 10000));
                    }),
                    "contract", contractTool(remoteLayout),
                    "command", new DeploymentModelToolLoop.Tool<>(DeploymentSkillResources.description(DeploymentAgentPublishRunner.class, "agent-publish-runner-tools.properties", "command"),
                        commandSchema.toString(), args -> {
                        DeploymentJsonFields.exact(args, Set.of("script", "seconds", "reason"));
                        String script = DeploymentJsonFields.text(args, "script", 24000);
                        if (!stagedCurrent.get())
                            throw new IllegalArgumentException("Stage exact publication inputs before custom commands");
                        verifiedCurrent.set(false);
                        guard.verify();
                        try {
                            return DeploymentModelToolLoop.Feedback.observed(DeploymentModelCommandRunner.execute(context,
                                gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoalBudget.timedHostSession(session, task,
                                    DeploymentJsonFields.number(args, "seconds", 1, 7200)),
                                server, task, revision, script, DeploymentJsonFields.number(args, "seconds", 1, 7200),
                                DeploymentJsonFields.text(args, "reason", 2000)));
                        } catch (
                            gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException refused) {
                            return DeploymentModelToolLoop.Feedback.observed("APPROVAL_DENIED\n" + refused.feedback());
                        }
                    }),
                    "stage", new DeploymentModelToolLoop.Tool<>(DeploymentSkillResources.description(DeploymentAgentPublishRunner.class, "agent-publish-runner-tools.properties", "stage"), args -> {
                        DeploymentJsonFields.exact(args, Set.of());
                        guard.verify();
                        if (standardSent.get()) modifiedAfterPublish.set(true);
                        verifiedCurrent.set(false);
                        for (var commit : proposal.units()) {
                            var component = proposal.graph().components().stream().filter(p -> p.unit().equals(commit.unit())).findFirst().orElseThrow();
                            var candidate = candidates.get(component.componentId());
                            var channel = new DeploymentPublishChannel(session, context.interaction(), server, task, candidate.artifactSha256(), guard, component.unit().layout());
                            var staged = gold.debug.windowstolinux.mod.deployment.service.deploy.publish.DeploymentPublishStaging.stage(
                                context, commit.configuration(), commit.secrets(), commit.unit(), candidate.credentials(), channel, task);
                            if (staged.outcome() != TaskResult.SUCCEEDED)
                                return DeploymentModelToolLoop.Feedback.ended(new OperationResult<>(staged.outcome(), Optional.empty(), staged.problem()));
                            stagedDigests.put(component.componentId(), staged.value().orElseThrow());
                        }
                        stagedCurrent.set(true);
                        return DeploymentModelToolLoop.Feedback.observed("EXACT_PUBLICATION_INPUTS_STAGED: inspect bindings before activation");
                    }),
                    "publish", new DeploymentModelToolLoop.Tool<>(DeploymentSkillResources.description(DeploymentAgentPublishRunner.class, "agent-publish-runner-tools.properties", "publish"), args -> {
                        DeploymentJsonFields.exact(args, Set.of());
                        if (!standardSent.compareAndSet(false, true))
                            throw new IllegalArgumentException("Same sealed publication transaction cannot be replayed");
                        guard.verify();
                        verifiedCurrent.set(false);
                        var commands = context.tasks().findCheckpoint(task, DeploymentModelCommandRunner.KEY);
                        commandBoundary.set(commands.isPresent() ? Optional.of(commands.orElseThrow().snapshot()) : Optional.empty());
                        receipts.begin("publish");
                        var published = publisher.publish(proposal, candidates);
                        nativeResult(journal, task, "publish", proposal.graph().applicationId(), receipts, published);
                        if (published.outcome() == TaskResult.UNKNOWN)
                            return DeploymentModelToolLoop.Feedback.ended(published);
                        if (published.outcome() == TaskResult.FAILED && Boolean.TRUE.equals(receipts.observation(published).get("rollbackConfirmed")))
                            failedNative.set(published);
                        stagedCurrent.set(false);
                        return DeploymentModelToolLoop.Feedback.observed("PUBLICATION_RESULT\n"
                            + DeploymentAiJson.facts(receipts.observation(published), 5700));
                    }),
                    "input", inputTool(context, task),
                    "finish_failure", new DeploymentModelToolLoop.Tool<>(DeploymentSkillResources.description(DeploymentAgentPublishRunner.class, "agent-publish-runner-tools.properties", "finish_failure"),
                        gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas.object("reason").toString(), args -> {
                        DeploymentJsonFields.exact(args, Set.of("reason"));
                        DeploymentJsonFields.text(args, "reason", 2000);
                        guard.verify();
                        var failed = failedNative.get();
                        if (failed == null || modifiedAfterPublish.get() || verifiedCurrent.get())
                            throw new IllegalArgumentException("Current native FAILED and complete rollback without later staging required");
                        var accepted = DeploymentAgentFailedPublication.accept(context, session, server, task, revision, proposal,
                            candidates, journal, receipts.publicationEvent(), receipts.rollbackEvent(), failed.problem().orElseThrow(), commandBoundary.get());
                        acceptFailure.accept(accepted);
                        return DeploymentModelToolLoop.Feedback.ended(failed);
                    }),
                    "unable", new DeploymentModelToolLoop.Tool<>(DeploymentSkillResources.description(DeploymentAgentPublishRunner.class, "agent-publish-runner-tools.properties", "unable"),
                        gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas.object("reason").toString(), args -> {
                        DeploymentJsonFields.exact(args, Set.of("reason"));
                        task.progress("AI_PUBLICATION_UNABLE " + DeploymentJsonFields.text(args, "reason", 2000), Optional.empty());
                        return DeploymentModelToolLoop.Feedback.ended(unknown("DEPLOYMENT_AGENT_MODEL_UNABLE"));
                    }),
                    "verify", new DeploymentModelToolLoop.Tool<>(DeploymentSkillResources.description(DeploymentAgentPublishRunner.class, "agent-publish-runner-tools.properties", "verify"), args -> {
                        DeploymentJsonFields.exact(args, Set.of());
                        guard.verify();
                        receipts.begin("verify");
                        verificationFailure.set(Map.of());
                        var verified = publisher.verifyPublished(proposal, candidates,
                            unavailable -> verificationFailure.set(verificationFailure(unavailable, task)));
                        nativeResult(journal, task, "verify", proposal.graph().applicationId(), receipts, verified);
                        verifiedCurrent.set(verified.outcome() == TaskResult.SUCCEEDED);
                        if (verified.outcome() != TaskResult.SUCCEEDED)
                            return DeploymentModelToolLoop.Feedback.observed("PUBLICATION_NOT_VERIFIED\n"
                                + DeploymentAiJson.facts(receipts.observation(verified), 5700));
                        journal.event(task, "AI_PUBLICATION_VERIFIED", proposal.graph().applicationId(), "HEALTHY");
                        return DeploymentModelToolLoop.Feedback.observed("VERIFIED_CURRENT_GRAPH_AND_APPLICATION_HEALTH\n"
                            + DeploymentAiJson.facts(receipts.observation(verified), 5700));
                    }),
                    "finish", new DeploymentModelToolLoop.Tool<>(DeploymentSkillResources.description(DeploymentAgentPublishRunner.class, "agent-publish-runner-tools.properties", "finish"), args -> {
                        DeploymentJsonFields.exact(args, Set.of());
                        guard.verify();
                        if (!verifiedCurrent.get())
                            throw new IllegalArgumentException("Current independent publication verification required");
                        journal.event(task, "AI_PUBLICATION_FINISHED", proposal.graph().applicationId(), "MODEL_ACCEPTED_VERIFIED_RESULT");
                        finishPhase.set(FinishPhase.RESOURCE_RELEASE);
                        phase(journal, task, finishPhase.get(), false);
                        releaseResources.releaseKnown();
                        phase(journal, task, finishPhase.get(), true);
                        finishPhase.set(FinishPhase.MODEL_COMMAND_RELEASE);
                        phase(journal, task, finishPhase.get(), false);
                        DeploymentModelCommandRunner.releaseKnown(context, task);
                        phase(journal, task, finishPhase.get(), true);
                        finishPhase.set(FinishPhase.CLEANUP_EVIDENCE);
                        phase(journal, task, finishPhase.get(), false);
                        var cleanupEvidence = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentCleanupEvidence(
                            context, receipts, server, task, revision, proposal, candidates, journal, guard, true);
                        phase(journal, task, finishPhase.get(), true);
                        finishPhase.set(FinishPhase.CANDIDATE_CLEANUP);
                        phase(journal, task, finishPhase.get(), false);
                        var cleanupPublisher = new DeploymentGraphPublisher(cleanupEvidence.context(), cleanupEvidence, server, task,
                            (type, action, detail) -> journal.event(task, type, action, detail), guard);
                        var cleaned = cleanupPublisher.cleanup(proposal, candidates, true);
                        phase(journal, task, finishPhase.get(), true);
                        if (cleaned.outcome() != TaskResult.SUCCEEDED)
                            return DeploymentModelToolLoop.Feedback.ended(unknown("DEPLOYMENT_AGENT_CANDIDATE_CLEANUP_UNCONFIRMED"));
                        journal.event(task, "AI_PUBLICATION_CLEANED", proposal.graph().applicationId(), "ABSENT");
                        return DeploymentModelToolLoop.Feedback.ended(new OperationResult<>(TaskResult.SUCCEEDED,
                            Optional.of(proposal), Optional.empty()));
                    })));
                gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal.currentWorker().ifPresent(worker ->
                    offered.put("continue_deployment", new DeploymentModelToolLoop.Tool<>(DeploymentSkillResources.description(
                        DeploymentAgentPublishRunner.class, "agent-publish-runner-tools.properties", "continue_deployment"),
                        gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas.object("reason").toString(), args -> {
                        DeploymentJsonFields.exact(args, Set.of("reason"));
                        DeploymentJsonFields.text(args, "reason", 2000);
                        guard.verify();
                        var failed = failedNative.get();
                        if (failed == null || modifiedAfterPublish.get() || verifiedCurrent.get())
                            throw new IllegalArgumentException("Current native FAILED and complete rollback without later mutations required");
                        try (var prepared = worker.prepare(proposal, DeploymentAiJson.facts(receipts.observation(failed), 5700))) {
                            var accepted = DeploymentAgentFailedPublication.accept(context, session, server, task, revision, proposal,
                                candidates, journal, receipts.publicationEvent(), receipts.rollbackEvent(), failed.problem().orElseThrow(), commandBoundary.get());
                            acceptFailure.accept(accepted);
                            prepared.accept(accepted);
                            return DeploymentModelToolLoop.Feedback.ended(failed);
                        }
                    })));
                return Map.copyOf(offered);
            }, () -> Map.ofEntries(Map.entry("publication", layout(proposal, candidates, stagedDigests)),
                Map.entry("contractFiles", CONTRACTS.stream().sorted().toList()), Map.entry("task", task.id().toString()),
                Map.entry("server", server.id()), Map.entry("applicationHealth", proposal.graph().healthCheck().toString()),
                Map.entry("skills", DeploymentAgentSkills.catalog(DeploymentAgentSkills.Role.DEPLOYMENT)), Map.entry("activeSkills", skills.active()),
                Map.entry("healthComponent", proposal.graph().healthComponentId()), Map.entry("stage", verifiedCurrent.get()
                    ? "VERIFIED" : stagedCurrent.get() ? "STAGED" : standardSent.get() ? "PUBLICATION_ATTEMPTED" : "SEALED"),
                Map.entry("staged", stagedCurrent.get()), Map.entry("standardSent", standardSent.get()),
                Map.entry("verified", verifiedCurrent.get()), Map.entry("budget", loop.budgetState()),
                Map.entry("lastVerificationFailure", verificationFailure.get()),
                Map.entry("nativeFailureWithRollback", failedNative.get() != null),
                Map.entry("changedAfterNativePublication", modifiedAfterPublish.get()),
                Map.entry("nextStep", publicationNextStep(stagedCurrent.get(), standardSent.get(), verifiedCurrent.get(),
                    failedNative.get() != null && !modifiedAfterPublish.get(),
                    gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal.currentWorker().isPresent()))));
        } catch (Exception uncertain) {
            error(journal, task, finishPhase.get(), uncertain);
            throw uncertain;
        } finally {
            finishPhase.set(FinishPhase.FINALLY_MODEL_COMMAND_RELEASE);
            phase(journal, task, finishPhase.get(), false);
            try {
                DeploymentModelCommandRunner.releaseKnown(context, task);
            } catch (Exception releaseFailure) {
                error(journal, task, finishPhase.get(), releaseFailure);
                throw releaseFailure;
            }
            phase(journal, task, finishPhase.get(), true);
        }
    }

    private static void nativeResult(DeploymentAgentActionLog journal, TaskContext task, String phase, String application,
                                     DeploymentAgentPublicationReceipts receipts, OperationResult<?> result) {
        try {
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("phase", phase);
            metadata.put("outcome", result.outcome().name());
            metadata.put("problemCode", result.problem().map(problem -> problem.code().name()).orElse("ABSENT"));
            String event = result.problem().map(Problem::eventCode).orElse(null);
            if (event != null && SAFE_API_EVENTS.contains(event)) metadata.put("problemEventCode", event);
            else metadata.put("problemEventCodeSha256", diagnosticHash(event));
            metadata.put("rollbackConfirmed", Boolean.TRUE.equals(receipts.observation(result).get("rollbackConfirmed")));
            metadata.put("task", task.id().toString());
            metadata.put("publicationEvent", receipts.publicationEvent());
            metadata.put("rollbackEvent", receipts.rollbackEvent());
            journal.event(task, "AI_PUBLICATION_NATIVE_RESULT", application,
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata));
        } catch (Exception ignored) {
            // Diagnostic storage cannot change the original native result or cleanup. / 诊断存储不能改变原生结果或清理。
        }
    }

    private static void phase(DeploymentAgentActionLog journal, TaskContext task, FinishPhase phase, boolean returned) {
        try {
            journal.event(task, "AI_PUBLICATION_FINISH_STAGE", phase.name(), returned ? "RETURNED" : "CALLING");
        } catch (Exception ignored) {
            // Diagnostic storage cannot prevent the original release or cleanup call. / 诊断存储不能阻止原释放或清理调用。
        }
    }

    private static void error(DeploymentAgentActionLog journal, TaskContext task, FinishPhase phase, Exception failure) {
        try {
            String name = failure.getClass().getSimpleName();
            if (name.isEmpty()) name = failure instanceof ApiException ? "ApiException" : "Exception";
            name = name.substring(0, Math.min(name.length(), 128));
            errorEvent(journal, task, "AI_PUBLICATION_ERROR", name, failure);
            errorEvent(journal, task, "AI_PUBLICATION_ERROR_CAUSE", failureCause(failure), failure);
            var metadata = new LinkedHashMap<String, String>();
            metadata.put("phase", phase == null ? "NONE" : phase.name());
            metadata.put("exceptionClass", name);
            metadata.put("messageSha256", diagnosticHash(failure.getMessage()));
            if (failure instanceof ApiException api) {
                metadata.put("problemCode", api.problem().code().name());
                String event = api.problem().eventCode();
                if (event != null && SAFE_API_EVENTS.contains(event)) metadata.put("problemEventCode", event);
                else metadata.put("problemEventCodeSha256", diagnosticHash(event));
            }
            errorEvent(journal, task, "AI_PUBLICATION_ERROR_METADATA", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata), failure);
        } catch (Exception diagnosticFailure) {
            if (diagnosticFailure != failure) failure.addSuppressed(diagnosticFailure);
        }
    }

    private static void errorEvent(DeploymentAgentActionLog journal, TaskContext task, String type, String detail, Exception failure) {
        try {
            journal.event(task, type, "", detail);
        } catch (Exception diagnosticFailure) {
            if (diagnosticFailure != failure) failure.addSuppressed(diagnosticFailure);
        }
    }

    private static String diagnosticHash(String value) {
        return value == null ? "ABSENT" : gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.hash(value);
    }

    /** Offers bounded reads of fixed lifecycle contracts / 提供固定生命周期合同的有界读取。
     * @return checked current result / 已校验当前结果
     */
    private static DeploymentModelToolLoop.Tool<DeploymentPublishResult> contractTool(DeploymentRemoteLayout remoteLayout) {
        var schema = gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas.object("path", "offset:integer", "limit:integer");
        var pathSchema = (com.fasterxml.jackson.databind.node.ObjectNode) schema.at("/properties/path");
        pathSchema.put("maxLength", 500);
        CONTRACTS.stream().sorted().forEach(pathSchema.putArray("enum")::add);
        ((com.fasterxml.jackson.databind.node.ObjectNode) schema.at("/properties/limit")).put("maximum", 100);
        return new DeploymentModelToolLoop.Tool<>(DeploymentSkillResources.description(DeploymentAgentPublishRunner.class, "agent-publish-runner-tools.properties", "contract"),
            schema.toString(), args -> {
            DeploymentJsonFields.exact(args, Set.of("path", "offset", "limit"));
            String path = DeploymentJsonFields.text(args, "path", 500);
            if (!CONTRACTS.contains(path))
                throw new IllegalArgumentException("Unsupported publication contract; select an offered path from contractFiles");
            int offset = DeploymentJsonFields.number(args, "offset", 0, 1000000);
            int limit = DeploymentJsonFields.number(args, "limit", 1, 100);
            try (var input = DeploymentAgentPublishRunner.class.getResourceAsStream(ROOT + path)) {
                if (input == null) throw new IllegalStateException("Publication contract missing");
                var lines = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayoutTemplate.render(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8), remoteLayout).lines().toList();
                int start = Math.min(offset, lines.size());
                int end = Math.min(start + limit, lines.size());
                String output;
                do {
                    output = DeploymentAiJson.facts(Map.of("path", path, "offset", start, "totalLines", lines.size(),
                        "nextLine", end, "hasMore", end < lines.size(), "requestedLimit", limit,
                        "returnedLines", end - start, "complete", true, "lines", lines.subList(start, end),
                        "continuation", "When hasMore=true, continue contract at line offset nextLine. Observation reads this retained page only."), 42000);
                    if (output.length() <= 5700) break;
                    if (--end <= start)
                        throw new IllegalArgumentException("Contract line exceeds complete feedback page capacity");
                } while (true);
                return DeploymentModelToolLoop.Feedback.observed(output);
            }
        });
    }

    private static Map<String, Object> publicationNextStep(boolean staged, boolean standardSent, boolean verified, boolean nativeFailure,
                                                           boolean continuationOffered) {
        if (verified)
            return Map.of("routes", List.of("finish"), "condition", "Assess the actual successful verify observation before finish.");
        var routes = new ArrayList<String>();
        if (!standardSent) routes.add("publish -> assess result -> verify -> finish");
        if (nativeFailure) {
            routes.add("finish_failure ends only this attempt as FAILED after assessing native failure; it does not accomplish the deployment goal; unchanged bindings and legal cleanup still required");
            if (continuationOffered)
                routes.add("continue_deployment is optional after assessing native failure; original source, resource and goal budget admission plus unchanged bindings are still verified by the tool before any fresh successor");
        }
        routes.add(staged ? "command -> assess result -> verify -> finish" : "stage -> assess staged bindings -> command -> assess result -> verify -> finish");
        return Map.of("routes", List.copyOf(routes), "condition",
            "Choose a route from applicability and actual feedback. Native publish stages its own inputs; custom command requires staged=true. Read contracts only to resolve a specific custom lifecycle question. No route grants command approval or proves health.");
    }

    private static String failureCause(Exception failure) {
        return switch (String.valueOf(failure.getMessage())) {
            case "Native model route changed" -> "MODEL_ROUTE_CHANGED";
            case "Model tool decision or attempt budget exhausted" -> "TOOL_BUDGET_EXHAUSTED";
            case "A complete native batch exceeds context budget", "Current Agent evidence exceeds bounded context" ->
                "NATIVE_BATCH_TOO_LARGE";
            case "Unsupported publication contract; select an offered path from contractFiles",
                 "Contract line exceeds complete feedback page capacity" -> "CONTRACT_PARAMETER_INVALID";
            default -> "OTHER";
        };
    }

    private static Map<String, Object> verificationFailure(Exception failure, TaskContext task) {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("scope", "CAUGHT_FAILURE_DURING_LAST_VERIFY");
        facts.put("trust", "DATA");
        facts.put("authority", "NONE");
        gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException refusal = null;
        if (failure instanceof gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException typed
            && task.id().toString().equals(typed.problem().context().get("task"))) refusal = typed;
        else if (failure instanceof ApiException typed && typed.problem().code() == ProblemCode.ACCESS_DENIED
            && typed.problem().eventCode().equals("DEPLOYMENT_COMMAND_REVIEW_DENIED")
            && task.id().toString().equals(typed.problem().context().get("task")))
            refusal = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException.fromReview(task, typed);
        if (refusal != null) {
            facts.put("kind", "APPROVAL_DENIED");
            facts.put("pendingInvocation", "NOT_SENT_AT_ORIGINAL_REVIEW_BOUNDARY");
            String reason = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReview.denialReason(refusal.problem().message());
            facts.put("reason", reason.substring(0, Math.min(384, reason.length())));
            facts.put("reasonTruncated", reason.length() > 384);
            String missing = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReview.denialReason(
                refusal.problem().context().getOrDefault("missingEvidence", ""));
            facts.put("missingEvidence", missing.substring(0, Math.min(128, missing.length())));
            facts.put("missingEvidenceTruncated", missing.length() > 128);
            String binding = refusal.problem().context().getOrDefault("reviewBinding", "");
            facts.put("reviewBinding", binding.matches("[0-9a-f]{64}") ? binding : "");
            facts.put("discussionStatus", refusal.discussionOpen() ? "OPEN"
                : "CLOSED".equals(refusal.problem().context().get("discussionStatus")) ? "CLOSED" : "UNKNOWN");
            facts.put("meaning", "Only the refused invocation was not sent. Existing publication and other verify invocations remain as recorded; no health, whole-verify settlement or replacement authorization is implied.");
        } else {
            facts.put("kind", "VERIFICATION_UNAVAILABLE");
            facts.put("pendingInvocation", "UNCONFIRMED");
            if (failure instanceof ApiException typed) {
                facts.put("code", typed.problem().code().name());
                String event = typed.problem().eventCode();
                facts.put("event", event.matches("[A-Z][A-Z0-9_]{0,95}") ? event : "OTHER");
            } else facts.put("cause", failureCause(failure));
        }
        return Collections.unmodifiableMap(facts);
    }

    /** Requests only missing nonsecret business intent / 只请求缺失非秘密业务意图。
     * @param context Original platform capabilities / 原平台能力
     * @param task Original admitted task / 原已准入任务
     * @return checked current result / 已校验当前结果
     */
    private static DeploymentModelToolLoop.Tool<DeploymentPublishResult> inputTool(ModContext context, TaskContext task) {
        return new DeploymentModelToolLoop.Tool<>(DeploymentSkillResources.description(DeploymentAgentPublishRunner.class, "agent-publish-runner-tools.properties", "input"),
            gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas.object("question").toString(), args -> {
            DeploymentJsonFields.exact(args, Set.of("question"));
            var answer = context.interaction().requestText(gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.text(context, "agent.publishInputTitle"),
                DeploymentJsonFields.text(args, "question", 2000), false, task);
            if (answer.outcome() != TaskResult.SUCCEEDED)
                throw new DeploymentAiJson.CapabilityOutcomeException(answer.outcome(), answer.problem());
            return DeploymentModelToolLoop.Feedback.observed(answer.value().orElseThrow());
        });
    }

    /** Builds the neutral application graph from original reviewed resource facts. / 从原已审阅资源事实建立中立应用图。
     * @param proposal original reviewed publication template / 原已审阅发布模板
     * @param candidates shared verified candidate preparation runner / 共用已核实候选准备执行器
     * @return neutral reviewed application graph / 中立已审阅应用图
     * @param stagedDigests Exact protected input digests / 精确保护输入摘要
     */
    private static List<Map<String, Object>> layout(DeploymentPublishResult proposal,
                                                    Map<String, DeploymentGraphPublisher.Candidate> candidates,
                                                    Map<String, Map<gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef, String>> stagedDigests) {
        var layout = new ArrayList<Map<String, Object>>();
        for (var part : proposal.graph().components()) {
            var commit = proposal.units().stream().filter(u -> u.unit().equals(part.unit())).findFirst().orElseThrow();
            var runtime = part.reviewedRuntime();
            String format = runtime instanceof DeploymentRuntimeDefinition.Container ? "container" : "systemd";
            var parameters = new ArrayList<String>(List.of("identity-v2", runtime.identityPolicy().name(),
                DeploymentWorkloadArguments.payload(runtime)));
            parameters.addAll(DeploymentPublishArguments.inputs(commit.configuration().sha256(),
                stagedDigests.getOrDefault(part.componentId(), Map.of())));
            parameters.addAll(DeploymentPublishArguments.content(part.unit().id(), part.resources()));
            if (runtime instanceof DeploymentRuntimeDefinition.Container container)
                parameters.addAll(DeploymentContainerArguments.from(container));
            else parameters.addAll(DeploymentRuntimeArguments.from(null, runtime));
            layout.add(Map.ofEntries(Map.entry("component", part.componentId()),
                Map.entry("unit", part.unit().id()), Map.entry("systemdUnit", part.unit().systemdUnit()),
                Map.entry("releaseRoot", part.unit().releaseRoot()), Map.entry("release", commit.release().sha256()),
                Map.entry("owner", part.unit().ownershipManifestSha256()), Map.entry("candidate", Map.of("id", candidates.get(part.componentId()).candidateId(),
                    "artifact", candidates.get(part.componentId()).artifactSha256(), "sourceRevision", candidates.get(part.componentId()).sourceRevision(),
                    "tools", candidates.get(part.componentId()).toolchainBindingSha256())),
                Map.entry("candidateRoot", part.unit().layout().candidateRoot(candidates.get(part.componentId()).candidateId())),
                Map.entry("runtimeParameters", parameters), Map.entry("runtime", runtime.toString()),
                Map.entry("resources", part.resources().toString()), Map.entry("dependencies", part.dependencies()),
                Map.entry("configurationDigest", commit.configuration().sha256()),
                Map.entry("configuration", new String(DeploymentPublishArguments.configuration(commit.configuration(), format), StandardCharsets.UTF_8)),
                Map.entry("requiredFiles", List.of("releases/<release>/.windowstolinux-owner", "releases/<release>/.windowstolinux-deployment-parameters",
                    "current (symlink to owned releases/<release>)", "systemd or reviewed container definition matching declared runtime"))));
        }
        return List.copyOf(layout);
    }

    /** Preserves an unresolved original effect as UNKNOWN for reconciliation. / 将未解决原效果保留为 UNKNOWN 待核对。
     * @param event stable structured failure event / 稳定结构化失败事件
     * @return original UNKNOWN operation result / 原 UNKNOWN 操作结果 */
    private static OperationResult<DeploymentPublishResult> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Model-authored publication remains unconfirmed", Map.of(), Optional.empty())));
    }
}
