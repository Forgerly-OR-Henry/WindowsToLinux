package gold.debug.windowstolinux.web.service.deployment;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.shared.analyze.component.*;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.deploy.input.*;
import gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.project.*;
import gold.debug.windowstolinux.web.service.contract.*;
import java.nio.file.Path;
import java.util.*;

/** Resolves the same deterministic runtime inputs used by the desktop automatic flow. */
public final class WebDeploymentInputs {
    private static final Set<String> KEYS = Set.of("applicationDeclaration", "executionMode", "exposure", "requestHex", "responseHex", "type", "primary", "secondary", "version", "port", "healthMode", "healthEndpoint",
            "expectedStatus", "timeout", "stability", "accessUrl", "jvmArguments", "arguments", "ports", "volumes", "containerEngine",
            "jvmTarget", "nodeBuild", "configuration", "secrets", "databaseMode", "databaseDetails", "dependencies");
    private final AutomaticRuntimeResolver resolver = new AutomaticRuntimeResolver();
    private final gold.debug.windowstolinux.web.service.ai.WebAiService ai;
    private final WebRequestContext context;
    public WebDeploymentInputs(gold.debug.windowstolinux.web.service.ai.WebAiService ai,WebRequestContext context) { this.ai=ai;this.context=context; }

    public static Map<String, String> overrides(JsonNode node) {
        if (node.isMissingNode()) return Map.of();
        if (!node.isObject() || node.size() > 1024) throw new IllegalArgumentException("Invalid advanced inputs");
        Map<String,String> result = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            String key = entry.getKey(), leaf = key.substring(key.lastIndexOf('/') + 1);
            if (!key.matches("[a-zA-Z0-9-]+(?:/[a-zA-Z0-9]+)?") || !KEYS.contains(leaf)
                    || !entry.getValue().isTextual() || entry.getValue().asText().length() > (entry.getKey().equals("applicationDeclaration") ? 65536 : 4096) || entry.getValue().asText().indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid advanced input");
            if (!entry.getValue().asText().isBlank()) result.put(key, entry.getValue().asText());
        });
        return Map.copyOf(result);
    }

    public Map<String,Map<String,String>> resolve(Path root, List<DiscoveredProjectComponent> components, Map<String,String> overrides,
                                                 String host, TaskInteraction interaction) throws Exception {
        if (components.isEmpty() || components.size() > 64) throw new IllegalArgumentException("No bounded deployable components found");
        for (String key : overrides.keySet()) if (key.contains("/") && components.stream().noneMatch(c -> key.startsWith(c.id() + "/")))
            throw new IllegalArgumentException("Unknown component input");
        var choices = new ArrayList<DeploymentInputField>(); var selected = new LinkedHashMap<String,String>();
        for (var component : components) {
            String type = overrides.getOrDefault(component.id() + "/type", overrides.getOrDefault("type", ""));
            if (type.isEmpty() && component.types().size() == 1) type = component.types().getFirst().name();
            if (type.isEmpty()) choices.add(AutomaticRuntimeResolver.field(component.id(), "type", "", component.types().stream().map(Enum::name).toList()));
            else selected.put(component.id() + "/type", type);
        }
        selected.putAll(ai.inputs(context, choices,interaction));
        Map<String,Map<String,String>> result = new LinkedHashMap<>(); choices.clear();
        for (var component : components) {
            var type = DeploymentProjectType.valueOf(selected.get(component.id() + "/type"));
            if (!component.types().contains(type)) throw new IllegalArgumentException("Type is not supported by the source");
            var assessment = new DeploymentAnalysisCoordinator().analyzeForDatabaseReview(root.resolve(component.relativeRoot()), type);
            var facts = assessment.facts().orElseThrow(() -> new IllegalArgumentException("Source analysis failed"));
            if (!facts.conflicts().isEmpty() || facts.missingInformation().stream().anyMatch(message -> !message.key().equals("analysis.db.reviewRequired")))
                throw new IllegalArgumentException("Source facts are incomplete");
            var values = resolver.defaults(type, assessment.runtimeSuggestion().orElse(null), root.resolve(component.relativeRoot()));
            overrides.forEach((key, value) -> { if (!key.contains("/")) values.put(key, value); });
            overrides.forEach((key, value) -> { if (key.startsWith(component.id() + "/")) values.put(key.split("/",2)[1], value); });
            values.put("type", type.name()); result.put(component.id(), values);
            choices.addAll(resolver.missing(component.id(), type, values));
            if (components.size() > 1 && !values.containsKey("dependencies")) choices.add(AutomaticRuntimeResolver.field(component.id(), "dependencies", "", List.of()));
        }
        ai.inputs(context, choices,interaction).forEach((key,value) -> result.get(key.split("/",2)[0]).put(key.split("/",2)[1],value));
        for (var entry : result.entrySet()) {
            var values = entry.getValue();
            for (int attempt = 0; ; attempt++) {
                try {
                    resolver.runtime(DeploymentProjectType.valueOf(values.get("type")), values); resolver.access(values, host);
                    DeploymentConfigurationParser.parse(values.getOrDefault("configuration", ""));
                    DeploymentConfigurationParser.secrets(values.getOrDefault("secrets", ""));
                    DeploymentRuntimeParser.databaseBindings(DatabaseReviewMode.valueOf(values.getOrDefault("databaseMode", "NONE")), values.getOrDefault("databaseDetails", ""));
                    break;
                } catch (IllegalArgumentException invalid) {
                    if (attempt >= 4) throw invalid;
                    interaction.progress("INPUT_CORRECTION_REQUIRED", WebJson.object().put("component", entry.getKey()));
                    WebTaskPrompts.inputs(interaction, resolver.corrections(entry.getKey(), values)).forEach((key,value) -> values.put(key.split("/",2)[1], value));
                    for (String key : List.of("healthEndpoint", "accessUrl", "jvmArguments", "arguments", "volumes", "databaseDetails", "configuration", "secrets"))
                        if (values.getOrDefault(key, "").isBlank()) values.remove(key);
                }
            }
        }
        return result;
    }
}
