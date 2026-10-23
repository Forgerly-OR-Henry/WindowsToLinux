package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandApproval;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;

/** Freezes a separately authorized recovery reviewer without changing original effects. / 单独授权并冻结恢复审核者，不改变原影响。 */
final class DeploymentAgentRecoveryReview {
    private static final String EVENT = "RECOVERY_REVIEW_BOUND";
    private static final String REBOUND = "RECOVERY_REVIEW_REBOUND";

    private DeploymentAgentRecoveryReview() {
    }

    /** Freezes an explicitly authorized route for this recovery, retaining prior bindings. / 冻结本次明确授权的恢复路由，保留既有绑定。 */
    static String revision(ModContext context, TaskContext task, DeploymentAgentActionLog.Snapshot saved,
                           DeploymentAgentActionLog journal) throws Exception {
        if (!journal.find(task).orElseThrow().equals(saved))
            throw denied(task, "DEPLOYMENT_AGENT_RECOVERY_IDENTITY_CHANGED");
        String original = saved.identity().configurationDigest();
        gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.requireRecoveryIdentity(
            context, task, new gold.debug.windowstolinux.appapi.server.model.ServerId(saved.identity().server()), original);
        var bindings = bindings(journal.events(task), task);
        var previous = lastBinding(bindings, original, task);
        String selected = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
        if (!selected.matches("[0-9a-f]{64}"))
            throw denied(task, "DEPLOYMENT_AGENT_RECOVERY_BINDING_INVALID");
        DeploymentFixedCommandApproval.requireRoute(context, task, selected);
        if (previous.map(value -> value.revision().equals(selected)).orElse(original.equals(selected))) return selected;
        var intent = new ActionIntent("deployment.agent.recovery.review", List.of(saved.identity().server()),
            task.id().toString(), selected,
            "originalTask=" + task.id() + "\nserver=" + saved.identity().server()
                + "\ntargetDigest=" + saved.identity().targetDigest() + "\noriginalModelRevision=" + original
                + "\npreviousBindingEvent=" + previous.map(value -> Long.toString(value.id())).orElse("none")
                + "\npreviousReviewRevision=" + previous.map(Binding::revision).orElse(original)
                + "\nrecoveryReviewRevision=" + selected
                + "\nallowed=original-status,original-settlement,original-cleanup",
            "Authorize the current AI route to review only this original task's status queries, settlement and cleanup; "
                + "retain its execution, command, source and candidate identities. The old deployment session will not resume or replay.");
        var approved = context.interaction().approveRequired(intent, task);
        if (approved.outcome() != TaskResult.SUCCEEDED || approved.value().isEmpty())
            throw denied(task, "DEPLOYMENT_AGENT_RECOVERY_BINDING_REFUSED");
        DeploymentFixedCommandApproval.requireRoute(context, task, selected);
        if (!journal.find(task).orElseThrow().equals(saved))
            throw denied(task, "DEPLOYMENT_AGENT_RECOVERY_IDENTITY_CHANGED");
        gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.requireRecoveryIdentity(
            context, task, new gold.debug.windowstolinux.appapi.server.model.ServerId(saved.identity().server()), original);
        if (!bindings(journal.events(task), task).equals(bindings))
            throw denied(task, "DEPLOYMENT_AGENT_RECOVERY_BINDING_CHANGED");
        if (previous.isEmpty()) journal.event(task, EVENT, original, selected);
        else journal.event(task, REBOUND, original, rebound(previous.orElseThrow(), selected));
        return selected;
    }

    private record Binding(long id, String revision) {
    }

    private static List<DeploymentAgentActionLog.Event> bindings(List<DeploymentAgentActionLog.Event> events,
                                                                TaskContext task) throws ApiException {
        var selected = events.stream().filter(event -> event.type().startsWith("RECOVERY_REVIEW_")).toList();
        if (selected.stream().anyMatch(event -> !event.type().equals(EVENT) && !event.type().equals(REBOUND)))
            throw denied(task, "DEPLOYMENT_AGENT_RECOVERY_BINDING_INVALID");
        return selected;
    }

    private static Optional<Binding> lastBinding(List<DeploymentAgentActionLog.Event> events, String original,
                                               TaskContext task) throws ApiException {
        Binding previous = null;
        for (var event : events) {
            if (!event.action().equals(original) || event.id() <= (previous == null ? 0 : previous.id()))
                throw denied(task, "DEPLOYMENT_AGENT_RECOVERY_BINDING_INVALID");
            if (previous == null) {
                if (!event.type().equals(EVENT) || !event.detail().matches("[0-9a-f]{64}"))
                    throw denied(task, "DEPLOYMENT_AGENT_RECOVERY_BINDING_INVALID");
                previous = new Binding(event.id(), event.detail());
            } else {
                String prefix = "previousEventId=" + previous.id() + "\npreviousReviewRevision=" + previous.revision() + "\nreviewRevision=";
                if (!event.type().equals(REBOUND) || !event.detail().startsWith(prefix))
                    throw denied(task, "DEPLOYMENT_AGENT_RECOVERY_BINDING_INVALID");
                String revision = event.detail().substring(prefix.length());
                if (!revision.matches("[0-9a-f]{64}") || revision.equals(previous.revision()))
                    throw denied(task, "DEPLOYMENT_AGENT_RECOVERY_BINDING_INVALID");
                previous = new Binding(event.id(), revision);
            }
        }
        return Optional.ofNullable(previous);
    }

    private static String rebound(Binding previous, String selected) {
        return "previousEventId=" + previous.id() + "\npreviousReviewRevision=" + previous.revision() + "\nreviewRevision=" + selected;
    }

    private static ApiException denied(TaskContext task, String event) {
        return new ApiException(new Problem(ProblemCode.ACCESS_DENIED, event,
            "Original recovery reviewer requires explicit unchanged authorization", Map.of(), Optional.of(task.id())));
    }
}
