package gold.debug.windowstolinux.mod.deployment.db;

import java.util.*;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.ref.ServerRef;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

abstract class DeploymentExecutionFixture extends DeploymentGraphFixture {
    Problem problem(TaskResult outcome, String event) {
        return new Problem(outcome == TaskResult.CANCELLED ? ProblemCode.CANCELLED : ProblemCode.UNKNOWN, event,
            "Retained test outcome", Map.of(), Optional.empty());
    }

    DeploymentExecutionIdentity identity(TaskContext task, UUID id) {
        return new DeploymentExecutionIdentity(id, task.id(), Optional.empty(), "application", new ServerId("server"),
            DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64));
    }

    DeploymentRefIntent intent() {
        return DeploymentRefIntent.server(new ServerId("server"), "application:application");
    }

    DeploymentExecutionStore journal(PlatformRuntime runtime) {
        return new DeploymentExecutionStore(runtime.modContexts().get(OWNER).context().db());
    }

    ServerRef prepare(PlatformRuntime runtime, TaskContext task, UUID id) throws Exception {
        var journal = journal(runtime);
        journal.begin(task, identity(task, id), "deploy", List.of(intent()), Optional.empty(), OBSERVED);
        var reference = runtime.modContexts().get(OWNER).context().servers().reserve(new ServerId("server"),
            intent().businessKey(), task);
        journal.recordReference(task, id, intent(), reference.id(), OBSERVED.plusSeconds(1));
        journal.startEffects(task, id, OBSERVED.plusSeconds(2));
        return reference;
    }

}
