package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReviewSource;

/** Explicit test authorization, separate from model requests and production configuration. */
public final class DeploymentAgentMainOnlyFixture implements DeploymentAgentReviewSource {
    public static final String REASON = "USER_AUTHORIZED_DIAGNOSTIC: independent model judgment is suspended for this bound test task; exact platform authorization remains required";

    private final ModContext owner;

    private final ServerId server;

    private final AtomicInteger decisions = new AtomicInteger();

    private final AtomicInteger approvalAttempts = new AtomicInteger();

    public DeploymentAgentMainOnlyFixture(ModContext owner, ServerId server) {
        this.owner = Objects.requireNonNull(owner);
        this.server = Objects.requireNonNull(server);
    }

    public DeploymentAgentWorkflow workflow() {
        return new DeploymentAgentWorkflow(owner, this);
    }

    public int decisions() {
        return decisions.get();
    }

    public int approvalAttempts() {
        return approvalAttempts.get();
    }

    public void beforeRequest(AiRequest request) {
        if (request.purpose() == AiPurpose.APPROVAL) {
            approvalAttempts.incrementAndGet();
            throw new AssertionError("Main-only diagnostic forbids every APPROVAL request before provider transport");
        }
    }

    @Override
    public Bound bind(ModContext actualOwner, TaskContext task, ServerId actualServer, String sourceRevision, String configurationRevision) {
        if (actualOwner != owner || !server.equals(actualServer) || !sourceRevision.matches("[0-9a-f]{64}")
            || !configurationRevision.matches("[0-9a-f]{64}"))
            throw new SecurityException("Unapproved diagnostic binding");
        var identity = Objects.requireNonNull(owner.identity());
        Objects.requireNonNull(task);
        var taskId = Objects.requireNonNull(task.id());
        return new Bound() {
            private void sameOwnerTask(ModContext actual, TaskContext supplied, ServerId target, String revision) {
                if (actual != owner || supplied != task || !taskId.equals(supplied.id()) || !identity.equals(owner.identity())
                    || !server.equals(target) || !configurationRevision.equals(revision) || task.cancelToken().requested())
                    throw new SecurityException("Diagnostic permit differs from its original owner, task, target or configuration");
                try {
                    if (!revision.equals(owner.ai().configurationRevision(AiPurpose.LANGUAGE, task))
                        || !revision.equals(owner.ai().configurationRevision(AiPurpose.APPROVAL, task)))
                        throw new SecurityException("Diagnostic model configuration changed");
                } catch (gold.debug.windowstolinux.appapi.task.result.ApiException failure) {
                    throw new SecurityException("Diagnostic model configuration is unavailable", failure);
                }
            }

            @Override
            public void verify(ModContext actual, TaskContext supplied, ServerId target, String source, String revision) {
                sameOwnerTask(actual, supplied, target, revision);
                if (!sourceRevision.equals(source)) throw new SecurityException("Diagnostic source binding changed");
            }

            @Override
            public DeploymentCommandReviewModel.Result review(String instructions, Map<String, ?> facts, ActionIntent intent,
                                                              CommandRequest request, String revision, TaskContext supplied) {
                sameOwnerTask(owner, supplied, server, revision);
                if (!intent.serverIds().equals(List.of(server.id())))
                    throw new SecurityException("Foreign diagnostic command target");
                decisions.incrementAndGet();
                System.out.println("USER_AUTHORIZED_DIAGNOSTIC_COMMAND task=" + taskId + " operation="
                    + intent.semantics().orElseThrow().operation());
                return new DeploymentCommandReviewModel.Result(new DeploymentReviewDecision("APPROVE", true, REASON), Map.of());
            }

            @Override
            public DeploymentReviewDecision action(ModContext actual, String type, String action, Map<String, ?> facts,
                                                   ServerId target, String revision, TaskContext supplied) {
                sameOwnerTask(actual, supplied, target, revision);
                if (!List.of("COMMAND", "PATCH").contains(type) || !action.matches("[0-9a-f]{64}"))
                    throw new SecurityException("Invalid diagnostic action binding");
                decisions.incrementAndGet();
                System.out.println("USER_AUTHORIZED_DIAGNOSTIC_ACTION task=" + taskId + " type=" + type);
                return new DeploymentReviewDecision("APPROVE", true, REASON);
            }
        };
    }
}
