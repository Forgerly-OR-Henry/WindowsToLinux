package gold.debug.windowstolinux.web.service.contract;

import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/** Server-prepared work; resource IDs and physical target locks are resolved before persistence. */
public record PreparedWebOperation(String kind, JsonNode request, List<String> serverIds, List<String> lockKeys,
                                   boolean mutating, String sourceId, String applicationId, String backupId, WebWork work) {
    public PreparedWebOperation {
        request = request.deepCopy(); serverIds = List.copyOf(serverIds); lockKeys = List.copyOf(lockKeys); Objects.requireNonNull(work);
    }
    @FunctionalInterface public interface WebWork { JsonNode execute(TaskInteraction interaction) throws Exception; }
}
