package gold.debug.windowstolinux.mod.deployment.db.execution;

import java.util.*;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.ref.ServerRef;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentGraphFixture;
import gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefIntent;

public abstract class DeploymentExecutionFixture extends DeploymentGraphFixture {
    public Problem problem(TaskResult outcome, String event) {
        return new Problem(outcome == TaskResult.CANCELLED ? ProblemCode.CANCELLED : ProblemCode.UNKNOWN, event,
            "Retained test outcome", Map.of(), Optional.empty());
    }

    public DeploymentExecutionIdentity identity(TaskContext task, UUID id) {
        return new DeploymentExecutionIdentity(id, task.id(), Optional.empty(), "application", new ServerId("server"),
            DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64));
    }

    public DeploymentRefIntent intent() {
        return DeploymentRefIntent.server(new ServerId("server"), "application:application");
    }

    public DeploymentExecutionStore journal(PlatformRuntime runtime) {
        return new DeploymentExecutionStore(runtime.modContexts().get(OWNER).context().db());
    }

    public ServerRef prepare(PlatformRuntime runtime, TaskContext task, UUID id) throws Exception {
        var journal = journal(runtime);
        journal.begin(task, identity(task, id), "deploy", List.of(intent()), Optional.empty(), OBSERVED);
        var reference = runtime.modContexts().get(OWNER).context().servers().reserve(new ServerId("server"),
            intent().businessKey(), task);
        journal.recordReference(task, id, intent(), reference.id(), OBSERVED.plusSeconds(1));
        journal.startEffects(task, id, OBSERVED.plusSeconds(2));
        return reference;
    }

    public static String requestPayload(DeploymentExecutionIdentity identity, String operation,
                                        java.util.List<gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefIntent> intents,
                                        java.util.Optional<gold.debug.windowstolinux.mod.deployment.db.project.DeploymentProjectRecord> project) throws java.sql.SQLException {
        return DeploymentExecutionCodec.request(identity, operation, intents, project);
    }

    public static String recoveryPayload(DeploymentExecutionIdentity identity, String phase,
                                         java.util.Map<Integer, java.util.UUID> references) throws java.sql.SQLException {
        return DeploymentExecutionCodec.recovery(identity, phase, references);
    }
}
