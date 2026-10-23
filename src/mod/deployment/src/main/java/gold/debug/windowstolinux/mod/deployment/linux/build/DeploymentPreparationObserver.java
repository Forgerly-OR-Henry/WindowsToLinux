package gold.debug.windowstolinux.mod.deployment.linux.build;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;

/** Receives actual preparation facts; it cannot authorize a command or supply a business plan. */
public interface DeploymentPreparationObserver {
    DeploymentPreparationObserver NONE = new DeploymentPreparationObserver() { };

    default void diagnosticRequested(DeploymentToolchainDiagnostics.Request request, TaskContext task) throws ApiException {
    }

    default void toolsPrepared(ServerId server, DeploymentResolvedToolchains tools, TaskContext task) throws ApiException {
    }

    default void hostObserved(String revision, DeploymentLinuxCapabilityFacts host,
                              DeploymentResolvedToolchains tools, TaskContext task) throws ApiException {
    }
}
