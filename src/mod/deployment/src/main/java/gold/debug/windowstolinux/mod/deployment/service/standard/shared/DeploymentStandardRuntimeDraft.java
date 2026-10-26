package gold.debug.windowstolinux.mod.deployment.service.standard.shared;

import java.util.LinkedHashMap;
import java.util.Map;

import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;

/** Applies explicit non-secret runtime prefill after source suggestions, preserving component resource scope. / 在来源建议之后应用明确非秘密运行预填，保留组件资源范围。 */
public final class DeploymentStandardRuntimeDraft {
    /** No instances. / 不创建实例。 */
    private DeploymentStandardRuntimeDraft() {
    }

    /** Keeps explicit user text ahead of source-derived defaults; author review remains mandatory. / 使明确用户文本优先于来源默认值，作者审阅仍必需。
     * @param prompt actual component prompt / 实际组件问题
     * @param draft captured non-secret text / 已抓取非秘密文本
     * @return source-bound prompt with explicit prefill / 带明确预填的来源绑定问题 */
    public static DeploymentStandardInputs.Prompt apply(DeploymentStandardInputs.Prompt prompt, Map<String, String> draft) {
        var values = new LinkedHashMap<>(prompt.defaults());
        if (!DeploymentStandardInputs.SourceOptions.RUNTIME_FIELDS.containsAll(draft.keySet()))
            throw new IllegalArgumentException("Runtime prefill differs from this component's editable scope");
        var overrides = overrides(draft, prompt.componentId());
        if (overrides.containsKey("applicationDeclaration")) {
            gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.ApplicationSpec
                .applyText(overrides.get("applicationDeclaration"), values);
            overrides = new LinkedHashMap<>(overrides);
            overrides.remove("applicationDeclaration");
        }
        values.remove("applicationDeclaration");
        String oldPort = values.getOrDefault("port", "");
        String advancedConfiguration = overrides.remove("configuration");
        values.putAll(overrides);
        if (overrides.containsKey("commandEntrypoint"))
            values.put("application.command.entrypoint", overrides.get("commandEntrypoint"));
        if (overrides.containsKey("commandArguments")) {
            values.keySet().removeIf(key -> key.startsWith("application.command.arg."));
            var arguments = gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentCommandText.parse(overrides.get("commandArguments"));
            for (int index = 0; index < arguments.size(); index++)
                values.put("application.command.arg." + index, arguments.get(index));
        }
        String port = values.getOrDefault("port", "");
        if (!port.isBlank() && !port.equals(oldPort)) {
            values.replaceAll((key, value) -> key.matches("application\\.endpoint\\.[a-z0-9-]+\\.(port|targetPort)")
                && value.equals(oldPort) ? port : value);
            values.replaceAll((key, value) -> key.matches("application\\.endpoint\\.[a-z0-9-]+\\.url") || key.equals("accessUrl")
                ? withPort(value, oldPort, port) : value);
            if (!overrides.containsKey("healthEndpoint") && values.containsKey("healthEndpoint")) {
                var endpoint = java.net.URI.create(values.get("healthEndpoint"));
                if (Integer.toString(endpoint.getPort()).equals(oldPort))
                    try {
                        values.put("healthEndpoint", new java.net.URI(endpoint.getScheme(), endpoint.getUserInfo(), endpoint.getHost(),
                            Integer.parseInt(port), endpoint.getPath(), endpoint.getQuery(), endpoint.getFragment()).toString());
                    } catch (java.net.URISyntaxException invalid) {
                        throw new IllegalArgumentException("Invalid health endpoint", invalid);
                    }
            }
            String configuration = gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigInput
                .parse(values.getOrDefault("configuration", "")).stream()
                .map(entry -> entry.scope().name() + ":" + entry.key() + "="
                    + (entry.scope() == gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigScopeType.RUNTIME
                        && java.util.Set.of("PORT", "SERVER_PORT").contains(entry.key()) ? port : entry.value().canonicalValue()))
                .collect(java.util.stream.Collectors.joining("\n"));
            values.put("configuration", configuration);
        }
        if (overrides.containsKey("accessUrl")) {
            String access = overrides.get("accessUrl");
            var endpoint = gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.ApplicationSpec.resolve(values)
                .endpoints().stream().filter(value -> value.exposure() == gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentEndpoint.ExposureType.EXTERNAL
                    && java.util.Set.of(gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentEndpoint.ProtocolType.HTTP,
                        gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentEndpoint.ProtocolType.HTTPS).contains(value.protocol())).findFirst();
            endpoint.ifPresent(value -> {
                if (values.containsKey("application.endpoints")) values.put("application.endpoint." + value.id() + ".url", access);
            });
        }
        if (advancedConfiguration != null) {
            values.put("configuration", mergeConfiguration(values.getOrDefault("configuration", ""), advancedConfiguration));
            overrides.put("configuration", values.get("configuration"));
        }
        var explicit = Map.copyOf(overrides);
        var fields = new LinkedHashMap<String, gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField>();
        prompt.fields().forEach(field -> {
            String key = field.id().substring(field.id().lastIndexOf('/') + 1);
            fields.put(field.id(), explicit.containsKey(key) ? new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField(
                field.id(), field.titleKey(), field.helpKey(), explicit.get(key), field.choices()) : field);
        });
        new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentStandardRuntimeResolver()
            .corrections(prompt.componentId(), values).stream()
            .filter(field -> explicit.containsKey(field.id().substring(field.id().lastIndexOf('/') + 1)))
            .forEach(field -> fields.putIfAbsent(field.id(), field));
        return new DeploymentStandardInputs.Prompt(prompt.componentId(), prompt.applicationId(), prompt.projectType(), values, java.util.List.copyOf(fields.values()));
    }

    private static final java.util.Set<String> GRAPH_FIELDS = java.util.Set.of("applicationId", "healthComponent", "componentOverrides");

    private static String withPort(String value, String oldPort, String port) {
        if (value.isBlank()) return value;
        var uri = java.net.URI.create(value);
        int current = uri.getPort() >= 1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        if (!Integer.toString(current).equals(oldPort)) return value;
        try {
            return new java.net.URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), Integer.parseInt(port),
                uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
        } catch (java.net.URISyntaxException invalid) {
            throw new IllegalArgumentException("Invalid access URL", invalid);
        }
    }

    private static String mergeConfiguration(String automatic, String advanced) {
        var entries = new LinkedHashMap<String, String>();
        for (String input : java.util.List.of(automatic, advanced))
            gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigInput.parse(input).forEach(entry ->
                entries.put(entry.scope().name() + ":" + entry.key(), entry.value().canonicalValue()));
        return entries.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static Map<String, String> overrides(Map<String, String> draft, String component) {
        var result = new LinkedHashMap<String, String>();
        draft.forEach((key, value) -> {
            if (!GRAPH_FIELDS.contains(key) && !value.isBlank()) result.put(key, value.trim());
        });
        componentOverrides(draft).forEach((key, value) -> {
            int separator = key.indexOf('.');
            if (key.substring(0, separator).equals(component) && !value.isBlank()) {
                String field = key.substring(separator + 1);
                result.put(field, field.equals("configuration")
                    ? mergeConfiguration(result.getOrDefault(field, ""), value.trim()) : value.trim());
            }
        });
        return result;
    }

    static void validateComponents(Map<String, String> draft, java.util.Set<String> components) {
        componentOverrides(draft).keySet().forEach(key -> {
            if (!components.contains(key.substring(0, key.indexOf('.'))))
                throw new IllegalArgumentException("Advanced override refers to an undiscovered component: " + key);
        });
    }

    private static Map<String, String> componentOverrides(Map<String, String> draft) {
        var result = new LinkedHashMap<String, String>();
        for (String line : draft.getOrDefault("componentOverrides", "").split("\\R")) {
            if (line.isBlank()) continue;
            int equals = line.indexOf('=');
            String key = equals < 0 ? "" : line.substring(0, equals).trim();
            int dot = key.indexOf('.');
            if (dot < 1 || !key.substring(0, dot).matches("[a-z0-9][a-z0-9-]{0,62}")
                || !DeploymentStandardInputs.SourceOptions.RUNTIME_FIELDS.contains(key.substring(dot + 1))
                || GRAPH_FIELDS.contains(key.substring(dot + 1)) || result.containsKey(key))
                throw new IllegalArgumentException("Advanced component override requires one component.field=value per line: " + key);
            result.put(key, line.substring(equals + 1));
        }
        return result;
    }
}
