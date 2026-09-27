package gold.debug.windowstolinux.mod.deployment.db;

import java.util.*;

import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.appapi.task.*;

abstract class DeploymentExecutionFixture extends DeploymentGraphFixture {
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
        journal.begin(task, identity(task, id), "deploy", List.of(intent()), OBSERVED);
        var reference = runtime.modContexts().get(OWNER).context().servers().reserve(new ServerRef("server"),
                intent().businessKey(), task);
        journal.recordReference(task, id, intent(), reference.id(), OBSERVED.plusSeconds(1));
        journal.startEffects(task, id, OBSERVED.plusSeconds(2));
        return reference;
    }

}
