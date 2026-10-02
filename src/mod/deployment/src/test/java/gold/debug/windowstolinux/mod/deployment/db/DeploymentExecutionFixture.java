package gold.debug.windowstolinux.mod.deployment.db;

import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.util.*;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.server.*;


abstract class DeploymentExecutionFixture extends DeploymentGraphFixture {
    Problem problem(Outcome outcome, String event) {
        return new Problem(outcome == Outcome.CANCELLED ? ProblemCode.CANCELLED : ProblemCode.UNKNOWN, event,
            "Retained test outcome", Map.of(), Optional.empty());
    }

    DeploymentExecutionIdentity identity(TaskContext task, UUID id) {
        return new DeploymentExecutionIdentity(id, task.id(), Optional.empty(), "application", new ServerRef("server"),
            DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64));
    }

    DeploymentReferenceIntent intent() {
        return DeploymentReferenceIntent.server(new ServerRef("server"), "application:application");
    }

    DeploymentExecutionRepository journal(PlatformRuntime runtime) {
        return new DeploymentExecutionRepository(runtime.modContexts().get(OWNER).context().db());
    }

    ServerReference prepare(PlatformRuntime runtime, TaskContext task, UUID id) throws Exception {
        var journal = journal(runtime);
        journal.begin(task, identity(task, id), "deploy", List.of(intent()), Optional.empty(), OBSERVED);
        var reference = runtime.modContexts().get(OWNER).context().servers().reserve(new ServerRef("server"),
            intent().businessKey(), task);
        journal.recordReference(task, id, intent(), reference.id(), OBSERVED.plusSeconds(1));
        journal.startEffects(task, id, OBSERVED.plusSeconds(2));
        return reference;
    }

}
