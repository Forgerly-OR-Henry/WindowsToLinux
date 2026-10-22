package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.util.List;
import java.util.Map;
import gold.debug.windowstolinux.appapi.ai.model.AiMessage;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiToolResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Mode-owned review transcript; the shared AI transport never interprets its business protocol. */
public interface DeploymentApprovalConversation {
    Map<String, ?> facts(String instruction, Map<String, ?> current, String revision, TaskContext task) throws Exception;
    List<AiMessage> messages(String current, String revision, TaskContext task) throws Exception;
    void retain(String current, AiResponse response, AiToolResult result, String revision, TaskContext task) throws Exception;
}
