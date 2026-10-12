package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import java.util.Map;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision;

/** Supplies task-bound judgments while command validation and host authority retain their original owners. */
@FunctionalInterface
public interface DeploymentAgentReviewSource {
    Bound bind(ModContext owner, TaskContext task, ServerId server, String sourceRevision, String configurationRevision);

    interface Bound extends DeploymentCommandReviewModel {
        void verify(ModContext owner, TaskContext task, ServerId server, String sourceRevision, String configurationRevision);

        DeploymentReviewDecision action(ModContext owner, String type, String action, Map<String, ?> facts, ServerId server,
                                        String configurationRevision, TaskContext task) throws Exception;
    }
}
