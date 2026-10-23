package gold.debug.windowstolinux.mod.deployment.ai.agent;

import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

/** Explicit argument contracts; owning business parsers perform evidence and permission checks. */
public final class DeploymentToolSchemas {
    private static final ObjectMapper JSON = new ObjectMapper();
    private DeploymentToolSchemas() { }
    public static ObjectNode object(String... fields) {
        var schema = JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
        var properties = schema.putObject("properties"); var required = schema.putArray("required");
        for (String field : fields) {
            String[] parts = field.split(":", 2); String name = parts[0], type = parts.length == 2 ? parts[1] : "string";
            required.add(name); var property = properties.putObject(name).put("type", type);
            if (type.equals("string")) property.put("minLength", 1).put("maxLength", name.equals("script") ? 24000 : 4000);
            if (type.equals("integer")) property.put("minimum", name.equals("offset") ? 0 : 1)
                .put("maximum", name.equals("limit") ? 6000 : 1000000);
            if (type.equals("array")) property.put("maxItems", 64).putObject("items");
        }
        return schema;
    }
    public static ObjectNode assessment() {
        var schema = object("basis:array", "suitability", "differences", "resolution", "decision");
        ((ObjectNode)schema.at("/properties/basis")).put("minItems", 1).put("maxItems", 32)
            .set("items", JSON.createObjectNode().put("type", "string").put("pattern", "o[1-9][0-9]{0,5}"));
        ((ObjectNode)schema.at("/properties/resolution")).putArray("enum").add("CONSISTENT").add("RESOLVED").add("UNRESOLVED");
        return schema;
    }
    public static String withAssessment(String schema) {
        try {
            var value = (ObjectNode) JSON.readTree(schema); ((ObjectNode)value.get("properties")).set("assessment", assessment());
            ((ArrayNode)value.get("required")).add("assessment"); return value.toString();
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid tool schema"); }
    }
    public static void validate(JsonNode schema, JsonNode arguments) {
        if (!arguments.isObject()) throw new IllegalArgumentException("Tool arguments must be an object");
        var properties = schema.path("properties");
        for (var key : schema.path("required")) if (!arguments.has(key.asText())) throw new IllegalArgumentException("Missing parameter: " + key.asText());
        var fields = arguments.fields();
        while (fields.hasNext()) {
            var field = fields.next(); var definition = properties.path(field.getKey());
            if (definition.isMissingNode()) throw new IllegalArgumentException("Unexpected parameter: " + field.getKey());
            var value = field.getValue();
            boolean valid = switch (definition.path("type").asText()) {
                case "string" -> value.isTextual() && value.asText().length() >= definition.path("minLength").asInt(0)
                    && value.asText().length() <= definition.path("maxLength").asInt(Integer.MAX_VALUE);
                case "integer" -> value.isIntegralNumber() && value.canConvertToInt()
                    && value.asInt() >= definition.path("minimum").asInt(Integer.MIN_VALUE)
                    && value.asInt() <= definition.path("maximum").asInt(Integer.MAX_VALUE);
                case "array" -> value.isArray() && value.size() <= definition.path("maxItems").asInt(Integer.MAX_VALUE);
                case "object" -> value.isObject();
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Invalid parameter type or bound: " + field.getKey());
        }
    }
}
