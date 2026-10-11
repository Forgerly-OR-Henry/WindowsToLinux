package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.util.Map;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Optional current observations; cannot replace command facts or grant host authority. */
@FunctionalInterface
public interface DeploymentCurrentCommandEvidence {
    DeploymentCurrentCommandEvidence NONE = (intent, request, task) -> Map.of();

    Map<String, ?> facts(ActionIntent intent, CommandRequest request, TaskContext task) throws ApiException;

    /** Fixed product-owned instructions; never derived from model input or observations, and never execution permission. */
    default String reviewInstructions() throws ApiException {
        return "";
    }

    default void reviewed(ActionIntent intent, CommandRequest request, TaskContext task,
                          DeploymentReviewDecision decision) throws ApiException {
    }

    default void verify(ActionIntent intent, CommandRequest request, TaskContext task) throws ApiException {
    }
}
