package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;

/** Bounded syntax facts, without rejected arguments, source paths or private field names. */
final class DeploymentLiveSubmissionShape {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static String describe(AiToolCall call) {
        var result = JSON.createObjectNode();
        try {
            var node = JSON.readTree(call.arguments());
            result.put("syntax", "VALID").put("rootType", node == null ? "NULL" : node.getNodeType().name());
            if (node != null && node.isObject()) {
                result.put("fieldCount", node.size());
                var types = result.putObject("knownFieldTypes");
                for (String key : Set.of("action", "sourceRevision", "arguments"))
                    types.put(key, node.path(key).getNodeType().name());
                String action = node.path("action").asText();
                result.put("action", Set.of("LIST", "READ", "SEARCH", "ADVISE").contains(action) ? action : "OTHER");
            }
        } catch (JsonProcessingException invalid) {
            result.put("syntax", "INVALID").put("errorType", invalid.getClass().getSimpleName());
            result.put("duplicateField", invalid.getOriginalMessage().startsWith("Duplicate field"));
            var location = invalid.getLocation();
            if (location != null)
                result.put("line", location.getLineNr()).put("column", location.getColumnNr());
        }
        return result.toString();
    }
}
