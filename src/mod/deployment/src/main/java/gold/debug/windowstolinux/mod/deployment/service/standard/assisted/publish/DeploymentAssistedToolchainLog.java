package gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains;

/** Writes exact original inputs before admission and possible dispatch, never an execution permission. */
final class DeploymentAssistedToolchainLog implements DeploymentTrackedToolchains.OperationLog {
    private final DeploymentExecutionStore executions;
    private final TaskContext task;
    private final UUID execution;
    private final DeploymentTrackedToolchains.Identity identity;

    DeploymentAssistedToolchainLog(DeploymentExecutionStore executions, TaskContext task, UUID execution,
                                   DeploymentTrackedToolchains.Identity identity) {
        this.executions = executions; this.task = task; this.execution = execution; this.identity = identity;
    }

    @Override public void intent(DeploymentTrackedToolchains.Request request, String verb) throws ApiException {
        record(request, verb, "INTENT", null, null);
    }
    @Override public void dispatched(DeploymentTrackedToolchains.Request request, String verb) throws ApiException {
        record(request, verb, "DISPATCHED", null, null);
    }
    @Override public void observed(DeploymentTrackedToolchains.Request request, String verb,
                                   OperationResult<DeploymentTrackedToolchains.Observation> observation) throws ApiException {
        record(request, verb, "OBSERVED", observation.value().map(DeploymentTrackedToolchains::encodeObservation).orElse(null), null);
    }
    @Override public void recipe(DeploymentTrackedToolchains.Identity supplied,
                                 DeploymentTrackedToolchains.ScriptRecipe recipe) throws ApiException {
        if (!identity.equals(supplied)) throw new IllegalArgumentException("Original tracked recipe identity differs");
        record(null, null, "RECIPE", null, recipe);
    }
    private void record(DeploymentTrackedToolchains.Request request, String verb, String event,
                        com.fasterxml.jackson.databind.JsonNode observation, DeploymentTrackedToolchains.ScriptRecipe recipe) throws ApiException {
        try {
            executions.recordTracked(task, execution, identity, request, verb, event, observation, recipe, Instant.now());
        } catch (java.sql.SQLException invalid) {
            throw new ApiException(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_TRACKED_PERSISTENCE_UNCONFIRMED",
                "Original tracked environment evidence was not durably confirmed", Map.of(), Optional.of(task.id())));
        }
    }
}
