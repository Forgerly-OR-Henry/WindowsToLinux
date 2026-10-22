package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.io.IOException;
import java.util.Map;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

/** Mandatory mode-owned protocol validation, independent of optional current observations or authorization. */
public interface DeploymentCommandProtocol {
    boolean registered(ActionIntent intent);
    Map<String, Object> inspect(ActionIntent intent, CommandRequest request, DeploymentRemoteLayout layout,
                                TaskContext task) throws IOException, ApiException;
}
