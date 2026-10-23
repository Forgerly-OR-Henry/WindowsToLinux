package gold.debug.windowstolinux.mod.deployment;

import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStandardAssembly;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.DeploymentStandardPageService;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.staticmode.DeploymentStaticWorkflow;

/** Test-only assembly preserving captured mode choices while exercising independent entries. */
public final class DeploymentStandardEntryFixture {
    private final Entry staticMode;

    private final Entry assistedMode;

    public DeploymentStandardEntryFixture(ModContext context, DeploymentRefFlow references) {
        var assembly = new DeploymentStandardAssembly();
        staticMode = new DeploymentStaticWorkflow(context, references, assembly);
        assistedMode = new DeploymentAssistedWorkflow(context, references, assembly);
    }

    public static DeploymentStandardPageService pages(ModContext context, DeploymentStandardEntryFixture modes) {
        return new DeploymentStandardPageService(context, modes.staticMode, modes.assistedMode);
    }

    public TaskHandle<Void> submit(ServerId server, Draft draft, Reviewer reviewer) throws ApiException {
        return submit(server, draft, Optional.empty(), reviewer);
    }

    public TaskHandle<Void> submit(ServerId server, Draft draft, Optional<GitSource> git, Reviewer reviewer) throws ApiException {
        return submit(server, draft, git, reviewer, false);
    }

    public TaskHandle<Void> submit(ServerId server, Draft draft, Optional<GitSource> git, Reviewer reviewer, boolean assisted) throws ApiException {
        return submit(server, draft, git, reviewer, assisted, "MANUAL_REVIEW");
    }

    public TaskHandle<Void> submit(ServerId server, Draft draft, Optional<GitSource> git, Reviewer reviewer, boolean assisted, String approval) throws ApiException {
        return submit(server, draft, git, reviewer, assisted, approval, true);
    }

    public TaskHandle<Void> submit(ServerId server, Draft draft, Optional<GitSource> git, Reviewer reviewer, boolean assisted, String approval, boolean remember) throws ApiException {
        return (assisted ? assistedMode : staticMode).submit(server, draft, git, reviewer, approval, remember);
    }

    public void validateReview(Prompt prompt, Answers answer) {
        staticMode.validateReview(prompt, answer);
    }
}
