package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentPreparationAbsence;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval;

/** Observes one original pre-readiness gap; neither starts preparation nor installs a helper. */
final class DeploymentAgentPreparationReconciliation {
    private DeploymentAgentPreparationReconciliation() {
    }

    static boolean settle(ModContext context, TaskContext task, DeploymentAgentActionLog journal,
                          DeploymentAgentActionLog.Snapshot saved, DeploymentRemoteLayout layout,
                          List<DeploymentAgentActionLog.Event> events) throws Exception {
        if (DeploymentAgentPreparationAbsence.capture(task.id(), saved, layout, events).isEmpty()
            || DeploymentModelCommandRunner.unconfirmed(context, task)) return false;
        var server = new ServerId(saved.identity().server());
        var profile = context.servers().get(server, task);
        var first = DeploymentAgentPreparationAbsence.capture(task.id(), saved, layout, events).orElseThrow();
        if (!first.profile().equals(DeploymentAgentInputs.targetProfile(profile))
            || !first.endpoint().equals(DeploymentAgentInputs.physicalTarget(profile))) return false;
        String revision = DeploymentAgentRecoveryReview.revision(context, task, saved, journal);
        var captured = DeploymentAgentPreparationAbsence.capture(task.id(), saved, layout, journal.events(task)).orElseThrow();
        var evidence = new DeploymentCurrentCommandEvidence() {
            public Map<String, ?> facts(ActionIntent intent, CommandRequest request, TaskContext actual) throws ApiException {
                verify(intent, request, actual);
                return Map.of("originalPreparationEvent", captured.preparation().id(), "originalPreparationAction", captured.preparation().action(),
                    "originalTask", task.id().toString(), "originalSourceSha256", captured.source(), "originalArchiveSha256", captured.workspace().archiveSha256(),
                    "originalLayoutSha256", layout.digest(), "originalEventsSha256", DeploymentAgentPreparationAbsence.digest(captured.events()),
                    "purpose", "OBSERVE_ORIGINAL_ABSENCE_ONLY_NO_HELPER_NO_MUTATION");
            }

            public String reviewInstructions() {
                return "Review only this fixed product read-only pre-readiness recovery observation. It cannot install a helper, execute project code, "
                    + "prepare or delete a candidate, or alter applications, shared objects or persistent data. The original PREPARE remains uncertain "
                    + "until an exact absent path/build/mount/loop observation is durably committed. Never infer NOT_SENT from absence.";
            }

            public void verify(ActionIntent intent, CommandRequest request, TaskContext actual) throws ApiException {
                try {
                    if (actual != task || !journal.find(task).orElseThrow().equals(saved) || !journal.events(task).equals(captured.events())
                        || new DeploymentExecutionStore(context.db()).findForTask(task).isPresent()
                        || !DeploymentAgentLayouts.original(context, task, saved.identity(), captured.events()).equals(layout))
                        throw new IllegalStateException("Original preparation binding changed");
                    var current = context.servers().get(server, task);
                    if (!captured.profile().equals(DeploymentAgentInputs.targetProfile(current))
                        || !captured.endpoint().equals(DeploymentAgentInputs.physicalTarget(current)) || task.cancelToken().requested())
                        throw new IllegalStateException("Original preparation target or cancellation changed");
                } catch (Exception changed) {
                    var failure = new ApiException(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_PREPARATION_OBSERVATION_CHANGED",
                        "Original preparation observation cannot be confirmed", Map.of(), Optional.of(task.id())));
                    failure.initCause(changed);
                    throw failure;
                }
            }
        };
        var reviewed = DeploymentAgentApproval.scope(context, task, saved.identity().approvalMode(), saved.identity().rememberAuthorization(),
            () -> revision, evidence);
        try (var permission = DeploymentWorkflowAuthorization.recover(context, task, server, revision, saved.identity().approvalMode(),
            saved.identity().rememberAuthorization(), "original=" + task.id() + "\npurpose=observe-original-pre-readiness-absence-only"
                + "\nprepareEvent=" + captured.preparation().id() + "\neventsSha256=" + DeploymentAgentPreparationAbsence.digest(captured.events()));
             var session = context.linux().connect(server, task);
             var request = DeploymentAgentPreparationProbe.request(captured)) {
            if (!saved.identity().targetDigest().equals(DeploymentAgentInputs.targetDigest(server, session)))
                return false;
            var intent = RemoteActions.command(server, revision, request).withSemantics(new ActionSemantics(DeploymentAgentPreparationProbe.OPERATION, Set.of(
                new ActionTarget(server.id(), "directory", captured.workspace().candidateRoot()),
                new ActionTarget(server.id(), "systemd-service", "windowstolinux-build-" + captured.workspace().candidateId() + ".service"),
                new ActionTarget(server.id(), "host-prerequisites", "fixed-system-metadata-only"))));
            var grant = DeploymentCommandReviewRouter.approve(reviewed.interaction(), intent, request, task);
            if (grant.outcome() != TaskResult.SUCCEEDED || grant.value().isEmpty()) return false;
            evidence.verify(intent, request, task);
            var result = session.execute(request, grant.value().orElseThrow());
            if (result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty()
                || !DeploymentAgentPreparationProbe.absent(captured, result.value().orElseThrow())) return false;
            evidence.verify(intent, request, task);
            journal.preparationAbsent(task, captured, DeploymentAgentPreparationAbsence.proof(captured,
                DeploymentAgentPreparationProbe.requestDigest(captured), DeploymentAgentPreparationAbsence.hash(result.value().orElseThrow().stdout())));
            return true;
        }
    }
}
