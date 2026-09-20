package gold.debug.windowstolinux.web.service.contract;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.cfg.DateTimeFeature;
import java.util.Set;

/** Bounded strict JSON, with no polymorphic class loading or browser-controlled type names. */
public final class WebJson {
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(40).maxStringLength(1_048_576).maxNumberLength(100).build()).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private WebJson() { }
    public static JsonNode read(String json) {
        try { return MAPPER.readTree(json); } catch (JacksonException failure) { throw new IllegalArgumentException("Invalid JSON request"); }
    }
    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); } catch (JacksonException failure) { throw new IllegalArgumentException("Invalid JSON value"); }
    }
    public static ObjectNode object() { return MAPPER.createObjectNode(); }
    public static JsonMapper mapper() { return MAPPER; }
    public static JsonNode tree(Object value) { return MAPPER.valueToTree(value); }
    public static <T> T convert(JsonNode value, Class<T> type) { return MAPPER.convertValue(value, type); }
    public static void fields(JsonNode body, String... permitted) {
        if (!body.isObject()) throw new IllegalArgumentException("Expected a JSON object");
        Set<String> allowed = Set.of(permitted);
        body.propertyNames().forEach(name -> { if (!allowed.contains(name)) throw new IllegalArgumentException("Unexpected request field"); });
    }
    public static String text(JsonNode node, String key, int limit) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > limit
                || value.textValue().chars().anyMatch(ch -> ch < 32)) throw new IllegalArgumentException("Invalid " + key);
        return value.textValue();
    }
}
