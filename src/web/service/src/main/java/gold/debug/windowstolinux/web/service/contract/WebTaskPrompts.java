package gold.debug.windowstolinux.web.service.contract;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Typed task decisions; answers are validated against the exact requested fields. */
public final class WebTaskPrompts {
    private WebTaskPrompts() { }
    public static void validateNonSecretInputs(JsonNode values) {
        for(var field:values.properties()) {
            String id=field.getKey(),value=field.getValue().asText();
            if(id.equals("configuration") || id.endsWith("/configuration")) gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser.parse(value);
            if(id.equals("secrets") || id.endsWith("/secrets")) gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser.secrets(value);
        }
    }
    public static void approve(TaskInteraction interaction, String code, JsonNode details) throws Exception {
        JsonNode answer = interaction.decide("CONFIRM", WebJson.object().put("code", code).set("details", details));
        WebJson.fields(answer, "accepted");
        if (!answer.path("accepted").isBoolean()) throw new IllegalArgumentException("A decision is required");
        if (!answer.path("accepted").asBoolean()) throw new CancellationException("User declined the operation");
    }
    public static Map<String, String> inputs(TaskInteraction interaction, List<DeploymentInputField> fields) throws Exception {
        if (fields.isEmpty()) return Map.of();
        JsonNode answer = interaction.decide("INPUTS", WebJson.object().set("fields", WebJson.tree(fields)));
        WebJson.fields(answer, "values");
        JsonNode values = answer.path("values");
        WebJson.fields(values, fields.stream().map(DeploymentInputField::id).toArray(String[]::new));
        var result = new LinkedHashMap<String, String>();
        for (var field : fields) {
            if (!values.path(field.id()).isTextual()) throw new IllegalArgumentException("A field answer is missing");
            String value = values.path(field.id()).asText();
            int limit = field.id().equals("applicationDeclaration") || field.id().endsWith("/applicationDeclaration") ? 65536 : 4096;
            if (value.length() > limit || value.indexOf('\0') >= 0 || (!field.choices().isEmpty() && !field.choices().contains(value)))
                throw new IllegalArgumentException("Invalid field answer");
            result.put(field.id(), value);
        }
        return result;
    }
    public static void progress(TaskInteraction interaction, String code, JsonNode details) {
        try { interaction.checkCancelled(); interaction.progress(code, details); }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        catch (Exception failure) { throw new IllegalStateException("Cannot persist task progress", failure); }
    }
    public static boolean confirm(TaskInteraction interaction, String code, JsonNode details) {
        try { approve(interaction, code, details); return true; }
        catch (CancellationException declined) { return false; }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        catch (Exception failure) { throw new IllegalStateException("Cannot obtain task decision", failure); }
    }
}
