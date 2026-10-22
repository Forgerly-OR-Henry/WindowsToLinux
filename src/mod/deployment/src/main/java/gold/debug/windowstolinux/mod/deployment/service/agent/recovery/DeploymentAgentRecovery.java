package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentResourceRecovery;

import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.log.LogCategory;
import gold.debug.windowstolinux.appapi.task.log.LogEvent;
import gold.debug.windowstolinux.appapi.task.log.LogLevel;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentPreparationAbsence;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentPreparationCompensation;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentMultiPreparationCompensation;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentProjectChannel;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentPrepareNotSentException;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentManagedUnitResolver;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublishCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** Reconciles only an agent's retained original proposal and remote effects. / 仅核对自主任务留存的原提案与远端影响。 */
public final class DeploymentAgentRecovery {
    /** Actual original owner capabilities. / 实际原所有者能力。 */
    private final ModContext context;

    /** Dedicated original task identity and action evidence ledger. / 专有原任务身份及动作依据账本。 */
    private final DeploymentAgentActionLog journal;

    /** Original formal execution evidence repository. / 原正式执行依据仓库。 */
    private final DeploymentExecutionStore executions;

    /** Binds original persistence and platform capabilities. / 绑定原持久层与平台能力。
     * @param context active Mod / 活动 Mod
     * @param journal agent-only original ledger / 自主专有原账本 */
    public DeploymentAgentRecovery(ModContext context, DeploymentAgentActionLog journal) {
        this.context = Objects.requireNonNull(context);
        this.journal = Objects.requireNonNull(journal);
        this.executions = new DeploymentExecutionStore(context.db());
    }

    /** Reads original remote status and completes only a proven result. / 读取原远端状态并仅完成已证实结果。
     * @param task original recovery task / 原恢复任务
     * @param saved frozen agent identity / 冻结自主身份
     * @return known result or retained uncertainty / 已知结果或保留未知
     * @throws Exception if original evidence cannot be accessed / 原依据无法访问时 */
    public OperationResult<Void> reconcile(TaskContext task, DeploymentAgentActionLog.Snapshot saved) throws Exception {
        task.progress("AI_RECOVERY_ORIGINAL_EVIDENCE", Optional.empty());
        var events = journal.events(task);
        var retained = executions.findForTask(task);
        boolean compensationPlan = events.stream().anyMatch(e -> e.type().equals(DeploymentAgentPublicationCompensation.PLAN));
        boolean incompletePublication = events.stream().anyMatch(e -> e.type().equals("AI_PUBLICATION"))
            && events.stream().noneMatch(e -> e.type().equals("AI_PUBLICATION_FINISHED"));
        if (compensationPlan) {
            try {
                var state = DeploymentAgentPublicationCompensation.read(events, task.id());
                if (state.verified().isPresent()) {
                    if (retained.isEmpty()) return unknown("DEPLOYMENT_AGENT_COMPENSATION_FORMAL_MISSING");
                    return finishCompensatedLocal(task, saved, retained.orElseThrow());
                }
            } catch (Exception changed) { return unknown("DEPLOYMENT_AGENT_COMPENSATION_PROOF_UNCONFIRMED"); }
        }
        if (incompletePublication && retained.isPresent()
            && retained.orElseThrow().outcome().filter(v -> v != TaskResult.UNKNOWN).isPresent())
            return unknown("DEPLOYMENT_AGENT_COMPENSATION_KNOWN_RESULT_WITHOUT_VERIFICATION");
        var layout = gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts.original(context, task, saved.identity(), events);
        if (gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainProgramState.hasUnconfirmed(
            context.tasks(), task, saved.identity().server(), layout, events)) {
            var originalServer = new ServerId(saved.identity().server());
            requireOriginalProfile(originalServer, events, task);
            String controllerRevision = DeploymentAgentRecoveryReview.revision(context, task, saved, journal);
            try {
                if (!gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentControllerRecovery.reconcile(
                    context, task, saved, journal, layout, controllerRevision))
                    return unknown("DEPLOYMENT_AGENT_CONTROLLER_PROGRAM_UNCONFIRMED");
            } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException invalidEvidence) {
                return unknown("DEPLOYMENT_AGENT_CONTROLLER_PROGRAM_UNCONFIRMED");
            }
            events = journal.events(task);
        }
        if (retained.isEmpty()) {
            if (events.stream().anyMatch(e -> e.type().equals(DeploymentAgentMultiPreparationCompensation.EVENT))) {
                var set = DeploymentAgentMultiPreparationCompensation.retained(task.id(), saved, layout, events);
                if (set.isEmpty()) return unknown("DEPLOYMENT_AGENT_PREPARATION_SET_UNCONFIRMED");
                try {
                    if (!journal.find(task).orElseThrow().equals(saved) || !journal.events(task).equals(events))
                        return unknown("DEPLOYMENT_AGENT_PREPARATION_SET_CHANGED");
                    try (var connection = context.db().openConnection(task)) {
                        DeploymentAgentMultiPreparationCompensation.requireNoBusinessReference(connection, set.orElseThrow());
                    }
                    var progress = DeploymentAgentMultiPreparationCompensation.Progress.open(context, task, set.orElseThrow(), true);
                    progress.release(events.stream().filter(e -> e.type().equals(DeploymentAgentMultiPreparationCompensation.EVENT)).findFirst().orElseThrow(), events);
                } catch (Exception unavailable) {
                    return unknown("DEPLOYMENT_AGENT_PREPARATION_SET_UNCONFIRMED");
                }
            } else if (DeploymentAgentMultiPreparationCompensation.capture(task.id(), saved, layout, events).isPresent()) {
                try {
                    if (!compensateMultiplePreparations(task, saved, layout, events))
                        return unknown("DEPLOYMENT_AGENT_PREPARATION_SET_UNCONFIRMED");
                    events = journal.events(task);
                } catch (Exception unavailable) {
                    gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentFailure(context, task, unavailable);
                    return unknown("DEPLOYMENT_AGENT_PREPARATION_SET_UNCONFIRMED");
                }
            }
            if (originalWorkspaces(task.id(), saved, events, layout).isEmpty())
                return unknown("DEPLOYMENT_AGENT_PREFORMAL_IDENTITY_MISSING");
            var commands = context.tasks().findCheckpoint(task, gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.KEY);
            if (commands.isPresent() && !commands.orElseThrow().snapshot().released()) {
                if (DeploymentAgentPreparationCompensation.capture(task.id(), saved, layout, events).isPresent())
                    return unknown("DEPLOYMENT_AGENT_PARTIAL_PREPARATION_BRANCH_UNCONFIRMED");
                var server = new ServerId(saved.identity().server());
                requireOriginalProfile(server, events, task);
                String reviewRevision = DeploymentAgentRecoveryReview.revision(context, task, saved, journal);
                var reviewed = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.scope(context,
                    task, saved.identity().approvalMode(), saved.identity().rememberAuthorization(), () -> reviewRevision,
                    gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE);
                try (var permission = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.recover(
                    context, task, server, reviewRevision, saved.identity().approvalMode(),
                    saved.identity().rememberAuthorization(), "original=" + task.id())) {
                    if (!gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.reconcile(reviewed, task, reviewRevision))
                        return unknown("DEPLOYMENT_AGENT_MODEL_COMMAND_UNCONFIRMED");
                }
            }
            return beforePublication(task, saved, events, layout);
        }
        var record = retained.orElseThrow();
        if (record.identity().mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT
            || !record.identity().server().id().equals(saved.identity().server())
            || !record.identity().taskId().equals(task.id()))
            return unknown("DEPLOYMENT_AGENT_IDENTITY_CHANGED");
        var template = executions.publicationTemplate(record);
        if (template.isEmpty())
            return unknown("DEPLOYMENT_AGENT_PROPOSAL_MISSING");
        var original = template.orElseThrow();
        var proposal = new DeploymentPublishResult(original.graph(), original.units());
        if (proposal.graph().components().stream().anyMatch(part -> !layout.equals(part.unit().layout())))
            return unknown("DEPLOYMENT_AGENT_LAYOUT_CHANGED");
        var candidates = candidates(task, proposal, events);
        if (candidates.isEmpty())
            return unknown("DEPLOYMENT_AGENT_CANDIDATE_EVIDENCE_MISSING");
        var server = record.identity().server();
        var credentials = new HashMap<gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef, gold.debug.windowstolinux.appapi.secret.model.SecretId>();
        candidates.orElseThrow().values().forEach(v -> credentials.putAll(v.credentials()));
        if (!executions.references(record).keySet().equals(Set.copyOf(DeploymentOriginalPublishCommit.references(server, proposal, credentials))))
            return unknown("DEPLOYMENT_AGENT_ORIGINAL_REFERENCES_CHANGED");
        boolean observeOnly = false;
        if (incompletePublication) {
            try {
                if (compensationPlan) DeploymentAgentPublicationCompensation.requireCurrent(context, task, journal,
                    DeploymentAgentPublicationCompensation.read(events, task.id()).plan());
                else {
                    try { observeOnly = !DeploymentAgentPublicationObservation.readLatest(events, task.id()).observationComplete(); }
                    catch (Exception missingInitial) { observeOnly = true; }
                    if (!observeOnly) DeploymentAgentPublicationCompensation.requireInitial(task.id(), saved, record, original, events);
                }
            } catch (Exception outsideScope) { return unknown("DEPLOYMENT_AGENT_FIRST_PARTIAL_PUBLICATION_UNCONFIRMED"); }
        }
        requireOriginalProfile(server, events, task);
        String reviewRevision = DeploymentAgentRecoveryReview.revision(context, task, saved, journal);
        var reviewed = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.scope(context,
            task, saved.identity().approvalMode(), saved.identity().rememberAuthorization(), () -> reviewRevision,
            gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE);
        boolean compensated = false;
        try (var permission = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.recover(
            context, task, server, reviewRevision, saved.identity().approvalMode(), saved.identity().rememberAuthorization(),
            "original=" + task.id() + "\nexecution=" + record.identity().executionId()
                + (incompletePublication && !observeOnly
                    ? "\npurpose=compensate-original-first-native-partial-publication-only"
                        + "\ncompensationEffects=fixed-program-readiness;at-most-one-immutable-version-install;guarded-native-first-rollback-under-original-application-locks;remove-only-rechecked-exact-owned-maintenance-marker;original-task-candidate-cleanup-task;read-only-complete-post-state"
                        + "\nunknownPolicy=unknown-install-only-reads-the-same-version-owner-operation-receipt;unknown-mutation-only-status-observation-never-resend"
                        + "\nreadOnlyDiagnostic=at-most-one-fixed-source-diagnostic-attempt-per-failed-UNKNOWN-guarded-rollback-intent;requires-later-complete-unmet-goal-status;independent-APP-and-fresh-grant;this-invocation-remains-UNKNOWN-without-cleanup-or-settlement"
                        + "\nreadOnlyPreflightV2=at-most-one-new-fixed-source-preflight-per-complete-verified-v1-environment-failure;bind-original-pending-result-and-v1-request-end-receipt;Bash-verifies-and-removes-only-exact-SDK-dev-null-sentinels-and-refuses-LD-before-Python;isolated-Python-bounded-complete-environment-refuses-exported-functions-or-incomplete-classification-before-diagnostic-guards;independent-APP-and-fresh-grant;this-invocation-remains-UNKNOWN-without-mutation-cleanup-ACK-replacement-or-settlement"
                        + "\ncurrentStateContinuation=at-most-one-new-fixed-current-state-mutation-per-original-FAILED-UNKNOWN-rollback-after-complete-verified-v2-preflight-with-24-PASS-3-intrinsic-UNKNOWN-and-six-file-fingerprints;bind-original-intent-result-plan-and-preflight-request-end-receipt;independent-APP-full-source-and-fresh-exact-grant;no-old-ACK-overwrite-or-install-replay;new-wholegraph-observation-after-any-attempt-before-OBSERVED_ONLY;each-candidate-cleanup-independent-APP-new-grant;final-wholegraph-before-verified-FAILED-local-tail;UNKNOWN-never-resent"
                        + "\ncontractDiagnostic=at-most-one-fixed-readonly-native-contract-diagnostic-per-original-pending-and-complete-prior-continuation;bind-original-pending-result-continuation-request-end-receipt-and-later-complete-unmet-goal-wholegraph;independent-APP-full-source-and-fresh-exact-grant;this-invocation-always-UNKNOWN-no-mutation-ACK-cleanup-or-settlement"
                        + "\ncontractDiagnosticFollowup=at-most-one-new-fixed-readonly-control-classification-followup-per-complete-original-contract-diagnostic-REQUEST-END-with-UNKNOWN-CONTROL-UNCONFIRMED-receipt;bind-all-old-pending-continuation-diagnostic-source-receipt-identities-and-later-complete-unmet-goal-wholegraph;version-action-observation-and-DENY-NOT_SENT-UNKNOWN-never-reset-REQUEST-consumption;independent-APP-full-source-and-fresh-exact-grant;this-invocation-always-UNKNOWN-no-mutation-install-ACK-cleanup-or-settlement"
                        + "\npublicationCorrection=at-most-one-new-fixed-current-state-correction-per-original-pending-and-original-continuation-REQUEST-after-complete-immutable-OBSERVED-three-PASS-followup-and-newer-complete-unmet-goal-wholegraph;full-history-source-receipt-binding-and-two-lock-six-fingerprint-three-contract-guard;new-independent-APP-complete-source-and-fresh-exact-grant;REQUEST-DENY-UNKNOWN-interruption-version-or-new-followup-never-reset-consumption;this-invocation-always-UNKNOWN-no-original-ACK-replacement-reinstall-cleanup-or-settlement;later-fresh-goal-and-independent-cleanups-final-wholegraph-required-for-FAILED-settlement"
                        + "\npublicationReconsideration=one-consumed-human-ASK-per-original-task-pending-continuation-and-complete-DENY-NOT_SENT-correction-with-no-grant-dispatch-result;exact-current-question-and-exact-human-answer-only-admit-new-independent-APP-review-not-grant;historical-denied-graph-is-not-current;new-complete-wholegraph-after-answer-before-any-new-request;goal-already-met-uses-original-observed-only-and-independent-cleanups;otherwise-same-fixed-program-full-source-exact-targets-new-APP-and-fresh-grant;any-new-command-result-this-invocation-UNKNOWN-no-old-ACK-or-replay;cancel-crash-deny-and-version-never-reset-ASK"
                    : "\npurpose=verify-original-publication-and-settle-original-candidates"
                        + "\nhealthEffects=original-app-current-and-health-validation;fixed-app-admission-locks;verified-inactive-orphan-cleanup")
                + "\npermission=each-command-still-requires-independent-review-and-current-single-use-host-grant");
             RemoteSession session = context.linux().connect(server, task)) {
            if (!saved.identity().targetDigest().equals(DeploymentAgentInputs.targetDigest(server, session)))
                return unknown("DEPLOYMENT_AGENT_TARGET_CHANGED");
            var helper = new gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper(session,
                reviewed.interaction(), server, task, saved.identity().configurationDigest(), layout).probe();
            if (helper.outcome() != TaskResult.SUCCEEDED) {
                new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(context)
                    .effectResult(task.id(), helper);
                return unknown("DEPLOYMENT_AGENT_HELPER_CHANGED");
            }
            task.progress("AI_RECOVERY_ORIGINAL_HOST_VERIFIED", Optional.empty());
            if (!gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.reconcile(reviewed, task, reviewRevision))
                return unknown("DEPLOYMENT_AGENT_MODEL_COMMAND_UNCONFIRMED");
            if (!DeploymentAgentResourceRecovery.settle(reviewed, task, server, events,
                new gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseChannel(session, reviewed.interaction(), server, task, layout)))
                return unknown("DEPLOYMENT_AGENT_RESOURCE_UNCONFIRMED");
            boolean publicationStarted = events.stream().anyMatch(event -> event.type().equals("AI_PUBLICATION"));
            if (publicationStarted) {
                if (incompletePublication) {
                    if (observeOnly)
                        return DeploymentAgentPublicationObservation.observe(reviewed, session, server, task, reviewRevision,
                            record, proposal, candidates.orElseThrow(), journal);
                    try {
                        compensated = new DeploymentAgentPublicationRecovery(context, reviewed, session, server, task, reviewRevision,
                            saved, proposal, candidates.orElseThrow(), journal).run();
                    } catch (Exception unconfirmed) {
                        gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentFailure(context, task, unconfirmed);
                        return unknown("DEPLOYMENT_AGENT_FIRST_PUBLICATION_COMPENSATION_UNCONFIRMED");
                    }
                    if (!compensated) return unknown("DEPLOYMENT_AGENT_FIRST_PUBLICATION_COMPENSATION_UNCONFIRMED");
                } else {
                task.progress("AI_RECOVERY_COMMAND_RECEIPTS_SETTLED", Optional.empty());
                var healthEvidence = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentHealthEvidence(
                    reviewed, session, server, task, reviewRevision, proposal, candidates.orElseThrow(), journal);
                var healthPublisher = new DeploymentGraphPublisher(healthEvidence.context(), healthEvidence, server, task,
                    (type, action, detail) -> journal.event(task, type, action, detail));
                var verified = healthPublisher.verifyPublished(proposal, candidates.orElseThrow(), unavailable ->
                    gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentFailure(context, task, unavailable));
                if (verified.outcome() != TaskResult.SUCCEEDED) {
                    task.progress("AI_RECOVERY_PUBLICATION_NOT_VERIFIED " + verified.problem().map(Problem::eventCode).orElse("UNKNOWN"), Optional.empty());
                    return unknown("DEPLOYMENT_AGENT_MODEL_PUBLICATION_UNCONFIRMED");
                }
                if (record.outcome().isPresent() && record.outcome().orElseThrow() != TaskResult.UNKNOWN
                    && record.outcome().orElseThrow() != TaskResult.SUCCEEDED)
                    return unknown("DEPLOYMENT_AGENT_FORMAL_REMOTE_CONFLICT");
                if (record.outcome().isEmpty() || record.outcome().orElseThrow() == TaskResult.UNKNOWN)
                    executions.commitApplication(task, record.identity().executionId(), proposal.graph(), proposal.units(), Instant.now());
                }
            } else {
                if (record.businessCommitted()) return unknown("DEPLOYMENT_AGENT_PUBLICATION_EVIDENCE_MISSING");
                if (record.outcome().isEmpty() || record.outcome().orElseThrow() == TaskResult.UNKNOWN)
                    executions.recordUncommittedOutcome(task, record.identity().executionId(), TaskResult.FAILED,
                        problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_AGENT_PUBLICATION_NOT_STARTED"), Instant.now());
            }
            if (!compensated) {
            var cleanupEvidence = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentCleanupEvidence(
                reviewed, session, server, task, reviewRevision, proposal, candidates.orElseThrow(), journal,
                gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard.NONE, publicationStarted);
            var cleanupPublisher = new DeploymentGraphPublisher(cleanupEvidence.context(), cleanupEvidence, server, task,
                (type, action, detail) -> journal.event(task, type, action, detail));
            var cleaned = cleanupPublisher.cleanup(proposal, candidates.orElseThrow(), publicationStarted);
            if (cleaned.outcome() != TaskResult.SUCCEEDED)
                return unknown("DEPLOYMENT_AGENT_CANDIDATE_CLEANUP_UNCONFIRMED");
            journal.event(task, "AI_PUBLICATION_CLEANED", proposal.graph().applicationId(), "ABSENT");
            }
        }
        var latest = executions.findForTask(task).orElseThrow();
        // The original Linux session and workflow lease are closed before source/reference/resource settlement.
        if (compensated) return finishCompensatedLocal(task, journal.find(task).orElseThrow(), latest);
        return finishRecovered(task, latest);
    }

    private OperationResult<Void> finishCompensatedLocal(TaskContext task, DeploymentAgentActionLog.Snapshot expected,
                                                         gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionRecord original) throws Exception {
        try {
            var saved = journal.find(task).orElseThrow();
            if (!saved.identity().equals(expected.identity()) || saved.executionSemantics() != expected.executionSemantics())
                return unknown("DEPLOYMENT_AGENT_COMPENSATION_IDENTITY_CHANGED");
            var events = journal.events(task);
            DeploymentAgentPublicationCompensation.verified(task.id(), saved, original, executions.publicationTemplate(original).orElseThrow(), events);
            if (original.businessCommitted() || !original.identity().taskId().equals(task.id()))
                return unknown("DEPLOYMENT_AGENT_COMPENSATION_FORMAL_CONFLICT");
            var commit = new DeploymentOriginalPublishCommit(context);
            if (original.phase().equals("EXECUTING")) {
                var completed = commit.completeUncommittedVerified(task, original.identity().executionId(), TaskResult.FAILED,
                    DeploymentAgentPublicationCompensation.failure());
                if (completed.outcome() != TaskResult.FAILED) return unknown("DEPLOYMENT_AGENT_COMPENSATION_REFERENCES_UNCONFIRMED");
            } else if (!original.phase().equals("FINISHED") || !original.cleanupComplete() || !original.outcome().equals(Optional.of(TaskResult.FAILED)))
                return unknown("DEPLOYMENT_AGENT_COMPENSATION_FORMAL_CONFLICT");
            var latest = executions.findForTask(task).orElseThrow();
            var problem = executions.problem(latest).orElseThrow();
            if (problem.code() != ProblemCode.IO_FAILURE || !problem.eventCode().equals(DeploymentAgentPublicationCompensation.FAILURE))
                return unknown("DEPLOYMENT_AGENT_COMPENSATION_PROBLEM_CHANGED");
            if (commit.finishKnown(task, latest).outcome() != TaskResult.FAILED)
                return unknown("DEPLOYMENT_AGENT_COMPENSATION_RESOURCES_UNCONFIRMED");
            latest = executions.findForTask(task).orElseThrow();
            var verified = DeploymentAgentPublicationCompensation.verified(task.id(), journal.find(task).orElseThrow(), latest,
                executions.publicationTemplate(latest).orElseThrow(), journal.events(task));
            long tail = journal.events(task).stream().filter(e -> e.id() > verified.event()).count();
            if (tail == 0) { journal.event(task, "AI_PUBLICATION_CLEANED", latest.identity().applicationId(), "ABSENT"); tail++; }
            if (tail == 1) { journal.event(task, "AI_RECOVERY_SETTLED", task.id().toString(), "FAILED:COMMAND_NATIVE_DATABASE_SECRET_CANDIDATE_SOURCE_SETTLED"); tail++; }
            if (tail == 2) journal.state(task, "FAILED");
            var finalSaved = journal.find(task).orElseThrow();
            if (!finalSaved.state().equals("FAILED")) return unknown("DEPLOYMENT_AGENT_COMPENSATION_TAIL_CHANGED");
            DeploymentAgentPublicationCompensation.verified(task.id(), finalSaved, latest, executions.publicationTemplate(latest).orElseThrow(), journal.events(task));
            return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(problem));
        } catch (Exception unconfirmed) {
            gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentFailure(context, task, unconfirmed);
            return unknown("DEPLOYMENT_AGENT_COMPENSATION_LOCAL_TAIL_UNCONFIRMED");
        }
    }

    private OperationResult<Void> finishRecovered(TaskContext task,
                                                  gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionRecord original) throws Exception {
        var finished = new DeploymentOriginalPublishCommit(context).finishKnown(task, original);
        if (finished.outcome() == TaskResult.SUCCEEDED) {
            var latest = executions.findForTask(task).orElseThrow();
            if (!latest.identity().equals(original.identity()))
                return unknown("DEPLOYMENT_AGENT_ORIGINAL_DELIVERY_CHANGED");
            try {
                DeploymentAgentActionLog.originalDeliveryProof(task.id(), journal.find(task).orElseThrow(), latest,
                    executions.publicationTemplate(latest).orElseThrow(), journal.events(task));
            } catch (Exception unconfirmed) {
                gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentFailure(context, task, unconfirmed);
                return unknown("DEPLOYMENT_AGENT_ORIGINAL_DELIVERY_UNCONFIRMED");
            }
        }
        if (finished.outcome() != TaskResult.UNKNOWN) {
            journal.event(task, "AI_RECOVERY_SETTLED", task.id().toString(),
                finished.outcome().name() + ":COMMAND_NATIVE_DATABASE_SECRET_CANDIDATE_SOURCE_SETTLED");
            journal.state(task, finished.outcome().name());
        }
        return finished;
    }

    /** Keeps a pre-publication candidate for exact later inspection. / 保留发布前候选以供后续精确检查。
     * @param task original task / 原任务
     * @param saved original row / 原记录
     * @param events chronological evidence / 按序依据
     * @return known no-effect result or uncertainty / 已知无影响结果或未知
     * @throws Exception if exact original evidence cannot be read or released / 无法读取或释放精确原依据时 */
    private OperationResult<Void> beforePublication(TaskContext task, DeploymentAgentActionLog.Snapshot saved,
                                                    List<DeploymentAgentActionLog.Event> events, DeploymentRemoteLayout layout) throws Exception {
        if (saved.state().equals("SUCCEEDED"))
            return unknown("DEPLOYMENT_AGENT_FORMAL_SUCCESS_MISSING");
        var endedEvents = events;
        if (Set.of("FAILED", "CANCELLED").contains(saved.state()) && endedEvents.stream().anyMatch(event ->
            (event.type().equals(DeploymentAgentPreparationCompensation.EVENT)
                && DeploymentAgentPreparationCompensation.matches(task.id(), saved.identity(), layout, event, endedEvents)
                || event.type().equals(DeploymentAgentMultiPreparationCompensation.EVENT)
                && DeploymentAgentMultiPreparationCompensation.retained(task.id(), saved, layout, endedEvents).isPresent()))
            && events.size() >= 2 && events.get(events.size() - 2).type().equals("AI_RECOVERY_SETTLED")
            && preparationsSettled(events) && context.tasks().resourcesReleased(task)) {
            TaskResult outcome = TaskResult.valueOf(saved.state());
            return new OperationResult<>(outcome, Optional.empty(), Optional.of(problem(outcome == TaskResult.CANCELLED ? ProblemCode.CANCELLED : ProblemCode.IO_FAILURE,
                "DEPLOYMENT_AGENT_PREFORMAL_RECONCILED")));
        }
        var partial = DeploymentAgentPreparationCompensation.capture(task.id(), saved, layout, events);
        if (partial.isPresent() && (gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.unconfirmed(context, task)
            || gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseActionLog.find(context.tasks(), task).isPresent()))
            return unknown("DEPLOYMENT_AGENT_PARTIAL_PREPARATION_BRANCH_UNCONFIRMED");
        if (partial.isPresent()) {
            try (var connection = context.db().openConnection(task)) {
                DeploymentAgentPreparationCompensation.requireNoBusinessReference(connection, partial.orElseThrow());
            }
        }
        if (!preparationsSettled(events) && partial.isEmpty()) {
            try {
                if (!DeploymentAgentPreparationReconciliation.settle(context, task, journal, saved, layout, events))
                    return unknown("DEPLOYMENT_AGENT_ORIGINAL_PREPARATION_UNCONFIRMED");
                events = journal.events(task);
            } catch (Exception unavailable) {
                gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentFailure(context, task, unavailable);
                return unknown("DEPLOYMENT_AGENT_ORIGINAL_PREPARATION_UNCONFIRMED");
            }
        }
        var workspaces = partial.isPresent() ? Optional.of(List.of(partial.orElseThrow().workspace()))
            : originalWorkspaces(task.id(), saved, events, layout);
        if (workspaces.isEmpty())
            return unknown("DEPLOYMENT_AGENT_PREFORMAL_IDENTITY_MISSING");
        if (!workspaces.orElseThrow().isEmpty()) {
            var server = new ServerId(saved.identity().server());
            requireOriginalProfile(server, events, task);
            String reviewRevision = DeploymentAgentRecoveryReview.revision(context, task, saved, journal);
            var reviewed = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.scope(context,
                task, saved.identity().approvalMode(), saved.identity().rememberAuthorization(), () -> reviewRevision,
                gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE);
            try (var permission = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.recover(
                context, task, server, reviewRevision, saved.identity().approvalMode(), saved.identity().rememberAuthorization(),
                "original=" + task.id() + "\nworkspaces=" + workspaces.orElseThrow());
                 RemoteSession session = context.linux().connect(server, task)) {
                if (!saved.identity().targetDigest().equals(DeploymentAgentInputs.targetDigest(server, session)))
                    return unknown("DEPLOYMENT_AGENT_TARGET_CHANGED");
                var helper = new gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper(session,
                    reviewed.interaction(), server, task, saved.identity().configurationDigest(), layout).probe();
                if (helper.outcome() != TaskResult.SUCCEEDED) {
                    new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(context)
                        .effectResult(task.id(), helper);
                    return unknown("DEPLOYMENT_AGENT_HELPER_CHANGED");
                }
                if (partial.isEmpty() && !DeploymentAgentResourceRecovery.settle(reviewed, task, server, events,
                    new gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseChannel(session, reviewed.interaction(), server, task, layout)))
                    return unknown("DEPLOYMENT_AGENT_RESOURCE_UNCONFIRMED");
                if (!DeploymentAgentActionLog.effectsSettled(journal.events(task).stream().filter(v -> v.type().startsWith("CAPABILITY_")).toList()))
                    return unknown("DEPLOYMENT_AGENT_ORIGINAL_CAPABILITY_UNCONFIRMED");
                var channel = new DeploymentAgentProjectChannel(session, reviewed.interaction(), server, task,
                    saved.identity().configurationDigest(), () -> {
                    try {
                        return context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
                    } catch (ApiException unavailable) {
                        throw new IllegalStateException("Original AI configuration unavailable", unavailable);
                    }
                }, layout);
                if (partial.isPresent()) {
                    var captured = DeploymentAgentPreparationCompensation.capture(task.id(), saved, layout, journal.events(task)).orElseThrow();
                    if (!compensatePreparation(task, captured, session, server, reviewRevision))
                        return unknown("DEPLOYMENT_AGENT_ORIGINAL_PREPARATION_UNCONFIRMED");
                } else for (var workspace : workspaces.orElseThrow()) {
                    var status = channel.candidateStatus(workspace);
                    if (status.buildActive())
                        return unknown("DEPLOYMENT_AGENT_ORIGINAL_BUILD_ACTIVE");
                    if (status.present()) {
                        journal.event(task, "CANDIDATE_CLEANUP_EXECUTING", workspace.candidateId(), "original");
                        channel.cleanupOriginalCandidate(workspace);
                        journal.event(task, "CANDIDATE_CLEANUP_RESULT", workspace.candidateId(), "absent");
                    }
                }
            } catch (Exception unavailable) {
                gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentFailure(
                    context, task, unavailable);
                return unknown("DEPLOYMENT_AGENT_CANDIDATE_CLEANUP_UNCONFIRMED");
            }
        }
        if (workspaces.orElseThrow().isEmpty() && !DeploymentAgentResourceRecovery.settle(context, task,
            new ServerId(saved.identity().server()), events, null))
            return unknown("DEPLOYMENT_AGENT_RESOURCE_UNCONFIRMED");
        if (!DeploymentAgentActionLog.effectsSettled(journal.events(task).stream().filter(v -> v.type().startsWith("CAPABILITY_")).toList()))
            return unknown("DEPLOYMENT_AGENT_ORIGINAL_CAPABILITY_UNCONFIRMED");
        if (!preparationsSettled(journal.events(task)))
            return unknown("DEPLOYMENT_AGENT_ORIGINAL_PREPARATION_UNCONFIRMED");
        context.file().closeTaskResources(task);
        if (!context.tasks().resourcesReleased(task))
            return unknown("DEPLOYMENT_AGENT_SOURCE_RETAINED");
        TaskResult outcome = saved.state().equals("CANCELLED") ? TaskResult.CANCELLED : TaskResult.FAILED;
        var settledEvents = journal.events(task);
        boolean committedSettlement = !settledEvents.isEmpty() && settledEvents.getLast().type().equals("AI_RECOVERY_SETTLED")
            && settledEvents.getLast().action().equals(task.id().toString())
            && settledEvents.getLast().detail().equals(outcome.name() + ":COMMAND_NATIVE_DATABASE_SECRET_CANDIDATE_SOURCE_SETTLED")
            && settledEvents.stream().anyMatch(event -> (event.type().equals(DeploymentAgentPreparationCompensation.EVENT)
            && DeploymentAgentPreparationCompensation.matches(task.id(), saved.identity(), layout, event, settledEvents)
            || event.type().equals(DeploymentAgentMultiPreparationCompensation.EVENT)
            && DeploymentAgentMultiPreparationCompensation.retained(task.id(), saved, layout, settledEvents).isPresent()));
        if (!committedSettlement)
            journal.event(task, "AI_RECOVERY_SETTLED", task.id().toString(),
                outcome.name() + ":COMMAND_NATIVE_DATABASE_SECRET_CANDIDATE_SOURCE_SETTLED");
        journal.state(task, outcome.name());
        return new OperationResult<>(outcome, Optional.empty(),
            Optional.of(problem(outcome == TaskResult.CANCELLED ? ProblemCode.CANCELLED : ProblemCode.IO_FAILURE,
                "DEPLOYMENT_AGENT_PREFORMAL_RECONCILED")));
    }

    /** Settles one frozen complete set, retaining an actual-send checkpoint until its whole proof commits. */
    private boolean compensateMultiplePreparations(TaskContext task, DeploymentAgentActionLog.Snapshot saved,
                                                   DeploymentRemoteLayout layout, List<DeploymentAgentActionLog.Event> original) throws Exception {
        if (gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.unconfirmed(context, task)
            || gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseActionLog.find(context.tasks(), task).isPresent())
            return false;
        var initial = DeploymentAgentMultiPreparationCompensation.capture(task.id(), saved, layout, original).orElseThrow();
        try (var connection = context.db().openConnection(task)) {
            DeploymentAgentMultiPreparationCompensation.requireNoBusinessReference(connection, initial);
        }
        var server = new ServerId(saved.identity().server());
        requireOriginalProfile(server, original, task);
        String revision = DeploymentAgentRecoveryReview.revision(context, task, saved, journal);
        var resourceReview = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.scope(context,
            task, saved.identity().approvalMode(), saved.identity().rememberAuthorization(), () -> revision,
            gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE);
        try (var permission = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.recover(context, task,
            server, revision, saved.identity().approvalMode(), saved.identity().rememberAuthorization(), "original=" + task.id() + "\npurpose=original-candidate-set-compensation");
             RemoteSession session = context.linux().connect(server, task)) {
            if (!saved.identity().targetDigest().equals(DeploymentAgentInputs.targetDigest(server, session)))
                return false;
            var helper = new gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper(session, resourceReview.interaction(),
                server, task, saved.identity().configurationDigest(), layout).probe();
            if (helper.outcome() != TaskResult.SUCCEEDED) return false;
            if (!DeploymentAgentResourceRecovery.settle(resourceReview, task, server, journal.events(task),
                new gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseChannel(session, resourceReview.interaction(), server, task, layout)))
                return false;
            var captured = DeploymentAgentMultiPreparationCompensation.capture(task.id(), saved, layout, journal.events(task)).orElseThrow();
            var progress = DeploymentAgentMultiPreparationCompensation.Progress.open(context, task, captured);
            var guard = (gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard) () -> {
                try {
                    progress.verify();
                    if (task.cancelToken().requested() || !journal.find(task).orElseThrow().equals(saved) || !journal.events(task).equals(captured.events())
                        || !revision.equals(context.ai().configurationRevision(AiPurpose.LANGUAGE, task))
                        || !layout.equals(gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts.original(context, task, saved.identity(), captured.events()))
                        || gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainProgramState.hasUnconfirmed(context.tasks(), task, server.id(), layout, captured.events())
                        || gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.unconfirmed(context, task)
                        || gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseActionLog.find(context.tasks(), task).isPresent()
                        || !DeploymentAgentActionLog.effectsSettled(captured.events().stream().filter(e -> e.type().startsWith("CAPABILITY_")).toList()))
                        throw new SQLException();
                    for (var indexed : captured.events().stream().filter(e -> Set.of("AGENT_DATABASE_INDEX", "AGENT_SECRET_INDEX").contains(e.type())).toList()) {
                        var child = context.tasks().findCheckpoint(task, indexed.action());
                        if (child.isPresent() && !child.orElseThrow().snapshot().released()) throw new SQLException();
                    }
                    var current = context.servers().get(server, task);
                    var view = captured.view(captured.pending());
                    if (!view.profile().equals(DeploymentAgentInputs.targetProfile(current)) || !view.endpoint().equals(DeploymentAgentInputs.physicalTarget(current))
                        || !saved.identity().targetDigest().equals(DeploymentAgentInputs.targetDigest(server, session)))
                        throw new SQLException();
                    try (var connection = context.db().openConnection(task)) {
                        DeploymentAgentMultiPreparationCompensation.requireNoBusinessReference(connection, captured);
                    }
                } catch (Exception changed) {
                    throw new ApiException(problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_PREPARATION_SET_CHANGED"));
                }
            };
            guard.verify();
            var allowed = new HashSet<String>();
            for (var member : captured.members()) {
                var view = captured.view(member);
                allowed.add(member.pending() ? DeploymentAgentPartialPreparationProbe.requestDigest(view, false)
                    : DeploymentAgentProjectChannel.originalStatusRequestDigest(task.id(), server.id(), saved.identity().configurationDigest(), member.workspace()));
                allowed.add(DeploymentAgentPartialPreparationProbe.requestDigest(view, true));
                allowed.add(DeploymentAgentProjectChannel.originalCleanupRequestDigest(task.id(), server.id(), saved.identity().configurationDigest(), member.workspace()));
            }
            allowed.add(DeploymentAgentPartialPreparationProbe.fileMetadataRequestDigest(captured.view(captured.pending())));
            allowed.add(DeploymentAgentMultiPreparationCompensation.typedRequestDigest(captured));
            var evidence = new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence() {
                public Map<String, ?> facts(gold.debug.windowstolinux.appapi.task.action.ActionIntent intent,
                                            gold.debug.windowstolinux.appapi.linux.command.CommandRequest request, TaskContext actual) throws ApiException {
                    verify(intent, request, actual);
                    return Map.of("originalTask", task.id().toString(), "originalEventsSha256", DeploymentAgentPreparationAbsence.digest(captured.events()),
                        "originalBusinessPrefixSha256", captured.originSha256(), "pendingPrepareEvent", captured.pending().preparation().id(),
                        "candidateSet", captured.members().stream().map(m -> Map.of("component", m.preparation().detail(), "candidate", m.workspace().candidateId(),
                            "prepareEvent", m.preparation().id(), "prepareAction", m.preparation().action(), "plan", m.plan().detail(),
                            "quotaSha256", DeploymentAgentPreparationAbsence.hash(m.quota().detail()))).toList());
                }

                public String reviewInstructions() {
                    return "Review this complete exact original-task candidate-set observation or compensation. Each action requires its own independent decision and new grant. "
                        + "Only bounded original ownership/absence observations, a separately reviewed companion file-metadata observation, and original candidate cleanup are eligible; active or unconfirmed ownership remains UNKNOWN. "
                        + "Companion metadata describes only its new observation time and cannot settle an earlier refusal or authorize cleanup. Archive SHA, frozen source SHA and original plan/configuration have distinct roles. No prepare, build, publication or persistent/shared data deletion is authorized. "
                        + "The new typed observation distinguishes uploaded, created-without-archive and absent only through its complete strict consumer. "
                        + "EXPECTED_ARCHIVE_SHA256 is the original source binding; created-without-archive does not assert remote archive content. "
                        + "The bound set and product consumer do not assert remote success or authorize an action.";
                }

                public void verify(gold.debug.windowstolinux.appapi.task.action.ActionIntent intent,
                                   gold.debug.windowstolinux.appapi.linux.command.CommandRequest request, TaskContext actual) throws ApiException {
                    guard.verify();
                    String exact = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, saved.identity().configurationDigest(), request).exactAction();
                    if (actual != task || !intent.exactAction().equals(exact) || !allowed.contains(DeploymentAgentPreparationAbsence.hash(exact)))
                        throw new ApiException(problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_AGENT_PREPARATION_SET_REQUEST_CHANGED"));
                }
            };
            var reviewed = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.scope(context, task,
                saved.identity().approvalMode(), saved.identity().rememberAuthorization(), () -> revision, evidence);
            var channel = new DeploymentAgentProjectChannel(session, reviewed.interaction(), server, task,
                saved.identity().configurationDigest(), () -> revision, layout);
            // An uncertain prior send is settled only by its original after observation, never another cleanup.
            for (var member : captured.members())
                if (progress.requiresAfter(member)) {
                    guard.verify();
                    progress.after(member, observePartial(task, captured.view(member), session, server, reviewed, guard, true));
                }
            progress.enableTyped();
            var present = new LinkedHashMap<DeploymentAgentMultiPreparationCompensation.Member, Boolean>();
            // Freeze every before observation before the first cleanup; no completed candidate is omitted.
            for (var member : captured.members()) {
                if (progress.finished(member)) continue;
                guard.verify();
                var before = member.pending() ? progress.typed()
                    ? observePreparationStage(task, captured.view(member), DeploymentAgentMultiPreparationCompensation.pendingWorkspaceBytes(captured), session, server, reviewed, guard)
                    : observePartial(task, captured.view(member), session, server, reviewed, guard, false)
                    : channel.candidateStatusReceipt(task.id(), member.workspace(), guard);
                present.put(member, progress.before(member, before));
            }
            for (var member : captured.members()) {
                if (progress.finished(member)) continue;
                if (present.get(member)) {
                    var receipt = channel.compensateOriginalCandidate(member.workspace(), guard, () -> {
                        guard.verify();
                        try {
                            progress.dispatched(member);
                        } catch (ApiException unavailable) {
                            throw unavailable;
                        } catch (Exception unavailable) {
                            throw new ApiException(problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_PREPARATION_SET_CHECKPOINT_UNCONFIRMED"));
                        }
                    });
                    progress.result(member, receipt.succeeded());
                }
                progress.after(member, observePartial(task, captured.view(member), session, server, reviewed, guard, true));
            }
            guard.verify();
            journal.preparationsCompensated(task, captured, progress, progress.proof());
            var retainedEvents = journal.events(task);
            if (!journal.find(task).orElseThrow().equals(saved)) return false;
            try (var connection = context.db().openConnection(task)) {
                DeploymentAgentMultiPreparationCompensation.requireNoBusinessReference(connection, captured);
            }
            progress.release(retainedEvents.stream().filter(e -> e.type().equals(DeploymentAgentMultiPreparationCompensation.EVENT)).findFirst().orElseThrow(), retainedEvents);
            return true;
        }
    }

    private static boolean preparationsSettled(List<DeploymentAgentActionLog.Event> events) {
        return DeploymentAgentActionLog.preparationsSettled(events);
    }

    /** Closes only the original temporary effects, never resumes its preparation or model session. */
    private boolean compensatePreparation(TaskContext task, DeploymentAgentPreparationCompensation.Captured captured,
                                          RemoteSession session, ServerId server, String revision) throws Exception {
        var guard = (gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard) () -> {
            try {
                if (!journal.find(task).orElseThrow().equals(captured.snapshot()) || !journal.events(task).equals(captured.events())
                    || !gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts.original(context, task,
                    captured.snapshot().identity(), captured.events()).equals(captured.layout())
                    || !revision.equals(context.ai().configurationRevision(AiPurpose.LANGUAGE, task))
                    || gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.unconfirmed(context, task)
                    || task.cancelToken().requested())
                    throw new SQLException("Original partial preparation binding changed");
                var nativeReceipt = gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseActionLog.find(context.tasks(), task);
                if (nativeReceipt.isPresent())
                    throw new SQLException("Partial preparation has a native database branch");
                try (var connection = context.db().openConnection(task)) {
                    DeploymentAgentPreparationCompensation.requireNoBusinessReference(connection, captured);
                }
                var current = context.servers().get(server, task);
                if (!captured.profile().equals(DeploymentAgentInputs.targetProfile(current))
                    || !captured.endpoint().equals(DeploymentAgentInputs.physicalTarget(current))
                    || !captured.snapshot().identity().targetDigest().equals(DeploymentAgentInputs.targetDigest(server, session)))
                    throw new SQLException("Original partial preparation target changed");
            } catch (Exception changed) {
                var failure = new ApiException(problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_PARTIAL_PREPARATION_CHANGED"));
                failure.initCause(changed);
                throw failure;
            }
        };
        guard.verify();
        var evidence = new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence() {
            public Map<String, ?> facts(gold.debug.windowstolinux.appapi.task.action.ActionIntent intent,
                                        gold.debug.windowstolinux.appapi.linux.command.CommandRequest request, TaskContext actual) throws ApiException {
                verify(intent, request, actual);
                return Map.of("originalTask", task.id().toString(), "originalPrepareEvent", captured.preparation().id(),
                    "originalPrepareAction", captured.preparation().action(), "originalArchiveSha256", captured.workspace().archiveSha256(),
                    "originalSnapshotSha256", captured.source(), "originalConfigurationSha256", captured.snapshot().identity().configurationDigest(),
                    "originalEventsSha256", DeploymentAgentPreparationAbsence.digest(captured.events()),
                    "purpose", "COMPENSATE_ONLY_ORIGINAL_COMPLETELY_UPLOADED_INACTIVE_PREPARATION");
            }

            public String reviewInstructions() {
                return "Review the complete fixed product request for this single original failed preparation. Archive SHA identifies tar.gz bytes; "
                    + "snapshot SHA identifies the frozen source tree; configuration SHA identifies model policy. They have distinct roles. "
                    + "Only read-only bounded ownership/absence observations, a separately reviewed new-time file-metadata observation that cannot authorize cleanup, and the exact original candidate cleanup are admitted. "
                    + "No preparation, build, publication, application or persistent/shared data changes are authorized. "
                    + "Missing/partial/mismatched archive, active build or unknown ownership remains UNKNOWN.";
            }

            public void verify(gold.debug.windowstolinux.appapi.task.action.ActionIntent intent,
                               gold.debug.windowstolinux.appapi.linux.command.CommandRequest request, TaskContext actual) throws ApiException {
                guard.verify();
                String digest = DeploymentAgentPreparationAbsence.hash(gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(
                    server, captured.snapshot().identity().configurationDigest(), request).exactAction());
                if (actual != task || !Set.of(DeploymentAgentPartialPreparationProbe.requestDigest(captured, false),
                    DeploymentAgentPartialPreparationProbe.fileMetadataRequestDigest(captured),
                    DeploymentAgentPartialPreparationProbe.requestDigest(captured, true), DeploymentAgentProjectChannel.originalCleanupRequestDigest(
                        task.id(), server.id(), captured.snapshot().identity().configurationDigest(), captured.workspace())).contains(digest))
                    throw new ApiException(problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_AGENT_PARTIAL_REQUEST_CHANGED"));
            }
        };
        var reviewed = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.scope(context, task,
            captured.snapshot().identity().approvalMode(), captured.snapshot().identity().rememberAuthorization(), () -> revision, evidence);
        var before = observePartial(task, captured, session, server, reviewed, guard, false);
        boolean present = DeploymentAgentPartialPreparationProbe.present(captured, before);
        if (!present && !DeploymentAgentPartialPreparationProbe.absent(captured, before)) return false;
        var cleanup = DeploymentAgentPreparationCompensation.Cleanup.NOT_REQUIRED;
        String cleanupRequest = "";
        if (present) {
            var channel = new DeploymentAgentProjectChannel(session, reviewed.interaction(), server, task,
                captured.snapshot().identity().configurationDigest(), () -> revision, captured.layout());
            var receipt = channel.compensateOriginalCandidate(captured.workspace(), guard);
            cleanup = receipt.succeeded() ? DeploymentAgentPreparationCompensation.Cleanup.SUCCEEDED : DeploymentAgentPreparationCompensation.Cleanup.UNCONFIRMED;
            cleanupRequest = receipt.requestSha256();
        }
        if (!DeploymentAgentPartialPreparationProbe.absent(captured, observePartial(task, captured, session, server, reviewed, guard, true)))
            return false;
        guard.verify();
        journal.preparationCompensated(task, captured, DeploymentAgentPreparationCompensation.proof(captured, present, cleanup, cleanupRequest));
        return true;
    }

    /** Typed observation has its own complete request, decision and single-use grant; no companion retry. */
    private gold.debug.windowstolinux.appapi.linux.command.CommandResult observePreparationStage(TaskContext task,
                                                                                                 DeploymentAgentPreparationCompensation.Captured captured, long workspaceBytes, RemoteSession session, ServerId server, ModContext reviewed,
                                                                                                 gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard guard) throws Exception {
        try (var request = DeploymentAgentPartialPreparationProbe.stageRequest(captured, workspaceBytes)) {
            var intent = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, captured.snapshot().identity().configurationDigest(), request)
                .withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPartialPreparationProbe.OPERATION, Set.of(
                    new gold.debug.windowstolinux.appapi.task.action.ActionTarget(server.id(), "directory", captured.workspace().candidateRoot()),
                    new gold.debug.windowstolinux.appapi.task.action.ActionTarget(server.id(), "host-prerequisites", "fixed-system-metadata-only"))));
            OperationResult<?> approved = null;
            OperationResult<gold.debug.windowstolinux.appapi.linux.command.CommandResult> result = null;
            boolean invoked = false;
            String step = "APPROVE";
            Exception failure = null;
            try {
                guard.verify();
                var grant = gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter.approve(reviewed.interaction(), intent, request, task);
                approved = grant;
                if (grant.outcome() != TaskResult.SUCCEEDED || grant.value().isEmpty()) return null;
                step = "GUARD";
                guard.verify();
                step = "EXECUTE";
                invoked = true;
                result = session.execute(request, grant.value().orElseThrow());
                step = "POST_GUARD";
                guard.verify();
                return result != null && result.outcome() == TaskResult.SUCCEEDED ? result.value().orElse(null) : null;
            } catch (Exception unavailable) {
                failure = unavailable;
                throw unavailable;
            } finally {
                partialObservationDiagnostic(task, captured, false, DeploymentAgentPreparationAbsence.hash(intent.exactAction()),
                    approved, invoked, result, step, failure);
            }
        }
    }

    private gold.debug.windowstolinux.appapi.linux.command.CommandResult observePartial(TaskContext task,
                                                                                        DeploymentAgentPreparationCompensation.Captured captured, RemoteSession session, ServerId server, ModContext reviewed,
                                                                                        gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard guard, boolean after) throws Exception {
        try (var request = DeploymentAgentPartialPreparationProbe.request(captured, after)) {
            var intent = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, captured.snapshot().identity().configurationDigest(), request)
                .withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPartialPreparationProbe.OPERATION, Set.of(
                    new gold.debug.windowstolinux.appapi.task.action.ActionTarget(server.id(), "directory", captured.workspace().candidateRoot()),
                    new gold.debug.windowstolinux.appapi.task.action.ActionTarget(server.id(), "host-prerequisites", "fixed-system-metadata-only"))));
            OperationResult<?> approved = null;
            OperationResult<gold.debug.windowstolinux.appapi.linux.command.CommandResult> result = null;
            boolean executeInvoked = false;
            String step = "APPROVE";
            Exception failure = null;
            try {
                var grant = gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter.approve(reviewed.interaction(), intent, request, task);
                approved = grant;
                if (grant.outcome() != TaskResult.SUCCEEDED || grant.value().isEmpty()) return null;
                step = "GUARD";
                guard.verify();
                step = "EXECUTE";
                executeInvoked = true;
                result = session.execute(request, grant.value().orElseThrow());
            } catch (Exception unavailable) {
                failure = unavailable;
                throw unavailable;
            } finally {
                partialObservationDiagnostic(task, captured, after, DeploymentAgentPreparationAbsence.hash(intent.exactAction()),
                    approved, executeInvoked, result, step, failure);
            }
            if (!after && result != null && result.outcome() == TaskResult.FAILED && result.value().isPresent()
                && !task.cancelToken().requested() && !Thread.currentThread().isInterrupted()
                && DeploymentAgentPartialPreparationProbe.rejection(result.value().orElseThrow())
                .filter(value -> value.stage() == DeploymentAgentPartialPreparationProbe.Stage.FILES
                    && value.reason() == DeploymentAgentPartialPreparationProbe.Reason.NON_PRIVATE_FILE).isPresent())
                observePrivateFileMetadata(task, captured, session, server, reviewed, guard, DeploymentAgentPreparationAbsence.hash(intent.exactAction()));
            return result != null && result.outcome() == TaskResult.SUCCEEDED ? result.value().orElse(null) : null;
        }
    }

    /** A separately reviewed new-time observation; never changes the original result or progress. */
    private void observePrivateFileMetadata(TaskContext task, DeploymentAgentPreparationCompensation.Captured captured, RemoteSession session,
                                            ServerId server, ModContext reviewed,
                                            gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard guard,
                                            String originalRequestSha256) {
        OperationResult<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant> approved = null;
        OperationResult<gold.debug.windowstolinux.appapi.linux.command.CommandResult> result = null;
        boolean executeInvoked = false;
        boolean bindingVerified = false;
        String requestSha256 = "UNCONFIRMED";
        try (var request = DeploymentAgentPartialPreparationProbe.fileMetadataRequest(captured)) {
            var intent = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, captured.snapshot().identity().configurationDigest(), request)
                .withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPartialPreparationProbe.OPERATION, Set.of(
                    new gold.debug.windowstolinux.appapi.task.action.ActionTarget(server.id(), "directory", captured.workspace().candidateRoot()),
                    new gold.debug.windowstolinux.appapi.task.action.ActionTarget(server.id(), "host-prerequisites", "fixed-system-metadata-only"))));
            requestSha256 = DeploymentAgentPreparationAbsence.hash(intent.exactAction());
            guard.verify();
            approved = gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter.approve(reviewed.interaction(), intent, request, task);
            if (approved.outcome() != TaskResult.SUCCEEDED || approved.value().isEmpty() || task.cancelToken().requested() || Thread.currentThread().isInterrupted())
                return;
            guard.verify();
            executeInvoked = true;
            var grant = approved.value().orElseThrow();
            result = session.execute(request, grant);
            guard.verify();
            bindingVerified = true;
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            // Diagnostic failure cannot replace the original refusal or authorize cleanup.
        } finally {
            try {
                var details = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                details.put("task", task.id().toString()).put("prepareEvent", captured.preparation().id())
                    .put("originalRequestSha256", originalRequestSha256).put("requestSha256", requestSha256)
                    .put("observationTime", "NEW_COMPANION_OBSERVATION").put("cleanupEvidence", false)
                    .put("approveOutcome", approved == null ? "NOT_RETURNED" : approved.outcome().name())
                    .put("executeInvoked", executeInvoked).put("executeOutcome", result == null ? "NOT_RETURNED" : result.outcome().name())
                    .put("bindingVerified", bindingVerified);
                if (bindingVerified && result != null && (result.outcome() == TaskResult.SUCCEEDED || result.outcome() == TaskResult.FAILED) && result.value().isPresent()) {
                    var observed = result.value().orElseThrow();
                    details.put("stdoutSha256", DeploymentAgentPreparationAbsence.hash(observed.stdout()))
                        .put("stderrSha256", DeploymentAgentPreparationAbsence.hash(observed.stderr()));
                    DeploymentAgentPartialPreparationProbe.fileMetadata(observed).ifPresent(rows -> {
                        var items = details.putArray("files");
                        for (var row : rows)
                            items.addObject().put("role", row.role().name()).put("status", row.status().name())
                                .put("kind", row.kind().name()).put("owner", row.owner().name()).put("groupOtherWrite", row.groupOtherWrite().name()).put("links", row.links().name());
                    });
                }
                var target = context.logs().register(new LogCategory("deployment", "Deployment", Optional.of("deployment_log")));
                context.logs().write(target, new LogEvent(LogLevel.INFO, "DEPLOYMENT_AGENT_PARTIAL_FILE_METADATA", "partial.metadata",
                    Optional.of(details.toString()), Optional.of(task.id()), Optional.of(task.id()),
                    result == null ? Optional.empty() : Optional.of(result.outcome())));
            } catch (Exception unavailable) {
                // Do not alter original refusal, cancellation or progress when storage fails.
            }
        }
    }

    /** Diagnostic storage cannot change original evidence, transport outcomes or cleanup admission. */
    private void partialObservationDiagnostic(TaskContext task, DeploymentAgentPreparationCompensation.Captured captured, boolean after,
                                              String requestSha256, OperationResult<?> approved, boolean executeInvoked,
                                              OperationResult<gold.debug.windowstolinux.appapi.linux.command.CommandResult> result,
                                              String step, Exception failure) {
        try {
            var details = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            details.put("phase", after ? "POST" : "PRE").put("task", task.id().toString())
                .put("prepareEvent", captured.preparation().id()).put("prepareAction", captured.preparation().action())
                .put("requestSha256", requestSha256).put("eventsSha256", DeploymentAgentPreparationAbsence.digest(captured.events()))
                .put("approveReturned", approved != null).put("approveOutcome", approved == null ? "NOT_RETURNED" : approved.outcome().name())
                .put("approveValuePresent", approved != null && approved.value().isPresent())
                .put("approveProblem", approved == null ? "NONE" : approved.problem().map(p -> p.code().name()).orElse("NONE"))
                .put("approveProblemEvent", approved == null ? "NONE" : approved.problem().map(p -> observationProblemEvent(p.eventCode())).orElse("NONE"))
                .put("executeInvoked", executeInvoked).put("executeReturned", result != null)
                .put("executeOutcome", result == null ? "NOT_RETURNED" : result.outcome().name())
                .put("executeValuePresent", result != null && result.value().isPresent())
                .put("executeProblem", result == null ? "NONE" : result.problem().map(p -> p.code().name()).orElse("NONE"))
                .put("executeProblemEvent", result == null ? "NONE" : result.problem().map(p -> observationProblemEvent(p.eventCode())).orElse("NONE"))
                .put("exceptionStep", failure == null ? "NONE" : step)
                .put("exceptionKind", failure == null ? "NONE" : failure instanceof ApiException ? "API_EXCEPTION" : "EXCEPTION")
                .put("exceptionProblem", failure instanceof ApiException api ? api.problem().code().name() : "NONE")
                .put("exceptionProblemEvent", failure instanceof ApiException api ? observationProblemEvent(api.problem().eventCode()) : "NONE");
            var command = result == null ? Optional.<gold.debug.windowstolinux.appapi.linux.command.CommandResult>empty() : result.value();
            if (command.isPresent()) {
                var observed = command.orElseThrow();
                details.put("exitPresent", observed.exitCode().isPresent());
                observed.exitCode().ifPresent(exit -> details.put("exitCode", exit));
                details.put("truncated", observed.truncated())
                    .put("stdoutUtf8Bytes", observed.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .put("stderrUtf8Bytes", observed.stderr().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .put("stdoutSha256", DeploymentAgentPreparationAbsence.hash(observed.stdout()))
                    .put("stderrSha256", DeploymentAgentPreparationAbsence.hash(observed.stderr()))
                    .put("strictPresent", DeploymentAgentPartialPreparationProbe.present(captured, observed))
                    .put("strictAbsent", DeploymentAgentPartialPreparationProbe.absent(captured, observed));
                boolean presentShape = DeploymentAgentPartialPreparationProbe.presentOutput(captured).equals(observed.stdout());
                var shape = DeploymentAgentPreparationProbe.stdoutShape(captured.absenceBinding(), observed.stdout());
                details.put("stdoutShape", presentShape ? "EXACT_PRESENT" : shape.kind().name())
                    .put("stdoutMismatch", presentShape ? "NONE" : shape.mismatch().name());
                shape.unknown().ifPresent(unknown -> details.put("absenceStage", unknown.stage().name()).put("absenceReason", unknown.reason().name()));
                DeploymentAgentPartialPreparationProbe.rejection(observed).ifPresent(rejection ->
                    details.put("partialStage", rejection.stage().name()).put("partialReason", rejection.reason().name()));
            }
            var target = context.logs().register(new LogCategory("deployment", "Deployment", Optional.of("deployment_log")));
            context.logs().write(target, new LogEvent(LogLevel.INFO, "DEPLOYMENT_AGENT_PARTIAL_OBSERVATION", after ? "partial.POST" : "partial.PRE",
                Optional.of(details.toString()), Optional.of(task.id()), Optional.of(task.id()),
                result != null ? Optional.of(result.outcome()) : approved != null ? Optional.of(approved.outcome()) : Optional.empty()));
        } catch (Exception unavailable) {
            // Ordinary diagnostics must never replace the original result or exception.
        }
    }

    private static String observationProblemEvent(String eventCode) {
        return eventCode.length() <= 80 && eventCode.matches("(?:DEPLOYMENT|COMMAND|SSH|REMOTE|LINUX|TASK|ACTION|REVIEWED|WORKFLOW|PROCESS)_[A-Z0-9_]+")
            ? eventCode : "OTHER";
    }

    /** Verifies the original endpoint before handoff, authentication or any remote observation. / 在交权、认证或任何远端观察前核对原端点。
     * @param server original server / 原服务器
     * @param events original event stream / 原事件流
     * @param task authentic original recovery / 真实原恢复
     * @throws Exception if original endpoint evidence is absent or changed / 原端点依据缺失或变化时 */
    private void requireOriginalProfile(ServerId server, List<DeploymentAgentActionLog.Event> events, TaskContext task) throws Exception {
        var values = events.stream().filter(event -> event.type().equals("TARGET_PROFILE")).map(DeploymentAgentActionLog.Event::detail).distinct().toList();
        if (values.size() != 1 || !values.getFirst().equals(DeploymentAgentInputs.targetProfile(context.servers().get(server, task))))
            throw new SQLException("Original recovery endpoint is unconfirmed");
    }

    /** Reconstructs only task-bound candidate identities from original archived bytes and plan evidence. / 仅从原归档字节与计划依据重建任务绑定候选身份。
     * @param events original ordered evidence / 原有序依据
     * @return all possibly prepared original candidates or missing identity / 所有可能准备的原候选或身份缺失 */
    static Optional<List<DeploymentRemoteWorkspace>> originalWorkspaces(UUID task, DeploymentAgentActionLog.Identity identity,
                                                                        List<DeploymentAgentActionLog.Event> events) {
        return originalWorkspaces(task, identity, events, DeploymentRemoteLayout.original());
    }

    static Optional<List<DeploymentRemoteWorkspace>> originalWorkspaces(UUID task, DeploymentAgentActionLog.Identity identity,
                                                                        List<DeploymentAgentActionLog.Event> events, DeploymentRemoteLayout layout) {
        return originalWorkspaces(task, new DeploymentAgentActionLog.Snapshot(identity, "UNKNOWN", 4), events, layout);
    }

    static Optional<List<DeploymentRemoteWorkspace>> originalWorkspaces(UUID task, DeploymentAgentActionLog.Snapshot saved,
                                                                        List<DeploymentAgentActionLog.Event> events, DeploymentRemoteLayout layout) {
        var identity = saved.identity();
        try {
            gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentWorkspaceBinding.find(task, saved, events);
        } catch (Exception unknown) {
            return Optional.empty();
        }
        String archive = null;
        String originalSource = null;
        String application = null;
        var components = new LinkedHashSet<String>();
        var observed = new HashMap<String, String>();
        var attempts = new HashMap<String, Integer>();
        var unsent = new HashMap<String, Integer>();
        var absent = new HashMap<String, Integer>();
        var executing = new HashMap<String, ArrayDeque<DeploymentAgentActionLog.Event>>();
        var proofs = new HashMap<String, DeploymentAgentActionLog.Event>();
        var completed = new HashSet<String>();
        var preparedBindings = new HashSet<String>();
        for (var event : events) {
            switch (event.type()) {
                case "SOURCE" -> {
                    archive = event.action();
                    originalSource = event.detail();
                }
                case "PLAN_APPLICATION" -> application = event.detail();
                case "PREPARE_EXECUTING" -> {
                    components.add(event.detail());
                    attempts.merge(event.detail(), 1, Integer::sum);
                    executing.computeIfAbsent(event.action(), ignored -> new ArrayDeque<>()).addLast(event);
                    proofs.remove(event.action());
                }
                case "PREPARE_DISPATCH_NOT_SENT" -> proofs.put(event.action(), event);
                case "PREPARE_RESULT" -> {
                    preparedBindings.add(event.action());
                    var pending = executing.get(event.action());
                    if (pending != null && !pending.isEmpty()) completed.add(pending.removeFirst().detail());
                    proofs.remove(event.action());
                }
                case
                    gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCandidateCapacityException.EVENT -> {
                    if (!gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCandidateCapacityException.matches(event, events))
                        return Optional.empty();
                    var pending = executing.get(event.action());
                    var attempt = pending == null ? null : pending.pollFirst();
                    if (attempt == null) return Optional.empty();
                    absent.merge(attempt.detail(), 1, Integer::sum);
                }
                case DeploymentAgentPreparationAbsence.EVENT, DeploymentAgentPreparationCompensation.EVENT -> {
                    if (!(event.type().equals(DeploymentAgentPreparationAbsence.EVENT)
                        ? DeploymentAgentPreparationAbsence.matches(task, identity, layout, event, events)
                        : DeploymentAgentPreparationCompensation.matches(task, identity, layout, event, events)))
                        return Optional.empty();
                    var pending = executing.get(event.action());
                    var attempt = pending == null ? null : pending.pollFirst();
                    if (attempt == null) return Optional.empty();
                    absent.merge(attempt.detail(), 1, Integer::sum);
                }
                case "PREPARE_NOT_SENT" -> {
                    if (!Set.of("ALTERNATIVE_REQUIRED", DeploymentAgentPrepareNotSentException.DETAIL).contains(event.detail()))
                        break;
                    var pending = executing.get(event.action());
                    var attempt = pending == null ? null : pending.pollFirst();
                    var proof = proofs.remove(event.action());
                    if (event.detail().equals(DeploymentAgentPrepareNotSentException.DETAIL)) {
                        if (attempt == null || proof == null || originalSource == null || proof.id() <= attempt.id() || event.id() <= proof.id()
                            || !DeploymentAgentPrepareNotSentException.matchesProof(proof.detail(), task, attempt.detail(), originalSource,
                            identity.server(), identity.configurationDigest()))
                            return Optional.empty();
                        unsent.merge(attempt.detail(), 1, Integer::sum);
                    }
                }
                case "CANDIDATE" -> {
                    if (!candidateQuota(task, identity, layout, event, events)) return Optional.empty();
                    components.add(event.action());
                    completed.add(event.action());
                    observed.put(event.action(), event.detail());
                }
                default -> {
                }
            }
        }
        if (events.stream().anyMatch(e -> e.type().equals(DeploymentAgentMultiPreparationCompensation.EVENT))) {
            var set = DeploymentAgentMultiPreparationCompensation.retained(task, saved, layout, events);
            if (set.isEmpty()) return Optional.empty();
            for (var member : set.orElseThrow().members()) components.remove(member.preparation().detail());
        }
        events.stream().filter(event -> event.type().equals("PREPARE_EXECUTING") && preparedBindings.contains(event.action()))
            .forEach(event -> completed.add(event.detail()));
        components.removeIf(component -> !completed.contains(component) && attempts.getOrDefault(component, 0) > 0
            && attempts.get(component).equals(unsent.getOrDefault(component, 0) + absent.getOrDefault(component, 0)));
        if (components.isEmpty())
            return Optional.of(List.of());
        if (archive == null || application == null)
            return Optional.empty();
        var workspaces = new ArrayList<DeploymentRemoteWorkspace>();
        try {
            for (String component : components) {
                String id = DeploymentManagedUnitResolver.managedId(application, component);
                var workspace = gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentWorkspaceBinding.workspace(task, saved, events, id, archive, layout);
                if (observed.containsKey(component) && !observed.get(component).equals(workspace.candidateId()))
                    return Optional.empty();
                workspaces.add(workspace);
            }
            return Optional.of(List.copyOf(workspaces));
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    private static boolean candidateQuota(UUID task, DeploymentAgentActionLog.Identity identity,
                                          gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout, DeploymentAgentActionLog.Event candidate,
                                          List<DeploymentAgentActionLog.Event> events) {
        try {
            var quota = events.stream().filter(event -> event.id() < candidate.id() && event.type().equals("CANDIDATE_QUOTA")
                && event.action().equals(candidate.action())).reduce((first, last) -> last).orElseThrow();
            var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(quota.detail());
            var selected = events.stream().filter(event -> event.id() < quota.id() && event.type().equals("WORKSPACE_QUOTA")
                && event.action().equals(candidate.action())).reduce((first, last) -> last).orElseThrow();
            var declared = new com.fasterxml.jackson.databind.ObjectMapper().readTree(selected.detail());
            for (String key : List.of("task", "server", "source", "configuration", "layout", "plan", "workspaceBytes", "reserveBytes"))
                if (!data.path(key).equals(declared.path(key))) return false;
            var originalSource = events.stream().filter(event -> event.id() < quota.id() && event.type().equals("SOURCE")).reduce((first, last) -> last).orElseThrow();
            var acceptedPlan = events.stream().filter(event -> event.id() < quota.id() && event.type().equals("PLAN")).reduce((first, last) -> last).orElseThrow();
            if (!data.path("source").asText().equals(originalSource.detail()) || !data.path("plan").asText().equals(acceptedPlan.detail())
                || !data.path("workspaceBytes").isIntegralNumber() || !data.path("workspaceBytes").canConvertToLong()
                || !data.path("reserveBytes").isIntegralNumber() || !data.path("reserveBytes").canConvertToLong()
                || data.path("reserveBytes").longValue() < 1073741824L
                || data.path("reserveBytes").longValue() > gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.MAXIMUM)
                return false;
            long bytes = data.path("workspaceBytes").longValue();
            return data.path("server").asText().equals(identity.server()) && data.path("task").asText().equals(task.toString()) && data.path("configuration").asText().equals(identity.configurationDigest())
                && data.path("layout").asText().equals(layout.digest()) && data.path("candidate").asText().equals(candidate.detail())
                && bytes >= gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.UNIT
                && bytes <= gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.MAXIMUM
                && data.path("prepareAction").asText().equals(gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCandidateCapacityException.binding(task,
                candidate.action(), data.path("source").asText(), data.path("plan").asText(), bytes));
        } catch (Exception invalid) {
            return false;
        }
    }

    /** Rebuilds only complete original candidate facts, never a new candidate. / 仅重建完整原候选事实，绝不新建候选。
     * @param proposal saved formal graph / 已存正式图
     * @param events original events / 原事件
     * @return complete candidate set or absence / 完整候选集合或缺失
     * @param task Original admitted task / 原已准入任务
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    private Optional<Map<String, DeploymentGraphPublisher.Candidate>> candidates(TaskContext task, DeploymentPublishResult proposal,
                                                                                 List<DeploymentAgentActionLog.Event> events) throws Exception {
        var ids = new HashMap<String, String>();
        var sources = new HashMap<String, String>();
        var artifacts = new HashMap<String, String>();
        var tools = new HashMap<String, String>();
        for (var event : events) {
            switch (event.type()) {
                case "CANDIDATE" -> ids.put(event.action(), event.detail());
                case "CURRENT_SOURCE" -> sources.put(event.action(), event.detail());
                case "SEALED_ARTIFACT" -> artifacts.put(event.action(), event.detail());
                case "SEALED_TOOLS" -> tools.put(event.action(), event.detail());
                case "ARTIFACT_INVALIDATED" -> artifacts.remove(event.action());
                default -> {
                }
            }
        }
        var result = new LinkedHashMap<String, DeploymentGraphPublisher.Candidate>();
        try {
            for (var component : proposal.graph().components()) {
                String id = component.componentId();
                String binding = tools.getOrDefault(id, "0".repeat(64) + ":false");
                if (!binding.matches("[0-9a-f]{64}:(true|false)")) return Optional.empty();
                var credentials = new HashMap<gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef, gold.debug.windowstolinux.appapi.secret.model.SecretId>();
                var unit = proposal.units().stream().filter(v -> v.unit().equals(component.unit())).findFirst().orElseThrow();
                var store = new gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentSecretStore(context.db());
                for (var ref : unit.secrets()) {
                    var metadata = store.findRevision(task, ref).orElseThrow();
                    credentials.put(ref, new gold.debug.windowstolinux.appapi.secret.model.SecretId(metadata.credentialId(), metadata.purpose().orElseThrow()));
                }
                result.put(id, new DeploymentGraphPublisher.Candidate(ids.get(id), artifacts.get(id), sources.get(id),
                    binding.substring(0, 64), binding.endsWith(":true"), credentials));
            }
            return Optional.of(Map.copyOf(result));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    /** Builds a bounded public recovery problem. / 构造有界公开恢复问题。
     * @param code category / 类别
     * @param event stable event / 稳定事件
     * @return original-task diagnostic / 原任务诊断 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Original agent task requires review", Map.of(), Optional.empty());
    }

    /** Preserves unknown without any replay. / 不重放任何动作地保留未知。
     * @param event stable event / 稳定事件
     * @return unknown result / 未知结果 */
    private static OperationResult<Void> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }
}
