package gold.debug.windowstolinux.web.service.contract;

import tools.jackson.databind.JsonNode;

/** Business progress and bounded decisions, independent of HTTP and the task executor. */
public interface TaskInteraction {
    void progress(String code, JsonNode safeDetails) throws Exception;
    JsonNode decide(String kind, JsonNode safePrompt) throws Exception;
    void checkCancelled() throws InterruptedException;
    void completion(OperationCompletionState state);
}
