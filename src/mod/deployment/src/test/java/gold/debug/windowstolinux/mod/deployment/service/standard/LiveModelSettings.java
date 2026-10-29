package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import gold.debug.windowstolinux.app.ai.http.HttpUrlChecker;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.app.ai.provider.ModelProviderType;
import gold.debug.windowstolinux.app.service.ai.PlatformModelService;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiReasoningMode;
import gold.debug.windowstolinux.app.service.ai.AiModelProfile;

/** Test-only explicit provider selection; diagnostics never render credential-bearing input. */
final class LiveModelSettings {
    private static final Set<String> FIELDS = Set.of("base_url", "api_key", "model", "protocol");

    private final String provider;

    private final URI endpoint;

    private final String model;

    private final String key;

    private final ModelProtocol protocol;

    private LiveModelSettings(String provider, Map<String, String> values) {
        this.provider = provider;
        String base = required(values, "base_url");
        model = required(values, "model");
        key = required(values, "api_key");
        if (!model.matches("[A-Za-z0-9._:/-]{1,128}")) throw invalid();
        String wire = values.getOrDefault("protocol", "chat");
        protocol = switch (wire) {
            case "chat" -> ModelProtocol.OPENAI_CHAT_COMPLETIONS;
            case "responses" -> ModelProtocol.OPENAI_RESPONSES;
            default -> throw invalid();
        };
        try {
            var origin = URI.create(base);
            HttpUrlChecker.validate(origin);
            endpoint = URI.create(base.replaceAll("/+$", "")
                + (protocol == ModelProtocol.OPENAI_RESPONSES ? "/responses" : "/chat/completions"));
            HttpUrlChecker.validate(endpoint);
        } catch (RuntimeException rejected) {
            throw invalid(); // URI parser causes can contain the original credential-bearing URL.
        }
    }

    static LiveModelSettings parse(List<String> lines) {
        var flat = new LinkedHashMap<String, String>();
        var sections = new LinkedHashMap<String, Map<String, String>>();
        Map<String, String> current = flat;
        String active = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.endsWith(":") && line.indexOf('=') < 0) {
                String name = providerName(line.substring(0, line.length() - 1));
                current = new LinkedHashMap<>();
                if (sections.putIfAbsent(name, current) != null) throw invalid();
                continue;
            }
            int separator = line.indexOf('=');
            if (separator < 1) throw invalid();
            String name = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();
            if (name.equals("active_provider")) {
                if (active != null) throw invalid();
                active = providerName(value);
            } else if (!FIELDS.contains(name) || current.putIfAbsent(name, value) != null) {
                throw invalid();
            }
        }
        if (sections.isEmpty()) {
            if (active != null) throw invalid();
            return new LiveModelSettings("flat", flat);
        }
        if (active == null || !flat.isEmpty() || !sections.containsKey(active)) throw invalid();
        required(sections.get(active), "protocol");
        return new LiveModelSettings(active, sections.get(active));
    }

    private static String providerName(String value) {
        String name = value.strip().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z][a-z0-9_-]{0,31}")) throw invalid();
        return name;
    }

    private static String required(Map<String, String> values, String field) {
        String value = values.get(field);
        if (value == null || value.isBlank()) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid live model configuration; selected provider requires complete explicit settings");
    }

    String provider() {
        return provider;
    }

    String model() {
        return model;
    }

    ModelProtocol protocol() {
        return protocol;
    }

    char[] credential() {
        return key.toCharArray();
    }

    AiModelProfile profile(String id, String credentialReference) {
        var adapter = switch (provider) {
            case "gpt", "openai" -> ModelProviderType.OPENAI;
            case "deepseek" -> ModelProviderType.DEEPSEEK;
            default -> ModelProviderType.THIRD_PARTY;
        };
        return new AiModelProfile(id, endpoint, model, credentialReference, ModelReasoningLevel.DEFAULT,
            protocol, adapter, Map.of());
    }

    AiReasoningMode reasoning(String requested) {
        boolean luna = model.equals("gpt-6-luna");
        var selected = requested == null ? (luna ? AiReasoningMode.HIGH : AiReasoningMode.DEFAULT)
            : requested.equals("NONE") ? AiReasoningMode.STANDARD : AiReasoningMode.valueOf(requested);
        if (luna && selected != AiReasoningMode.HIGH)
            throw new IllegalArgumentException("The Luna live test requires HIGH reasoning for both purposes");
        return selected;
    }

    void configureReasoning(PlatformModelService models, String id, AiReasoningMode language,
                            AiReasoningMode approval) throws java.sql.SQLException {
        var saved = models.listAiConfigurations().stream().filter(value -> value.profile().id().equals(id))
            .findFirst().orElseThrow();
        var defaults = new java.util.EnumMap<AiPurpose, AiReasoningMode>(AiPurpose.class);
        defaults.putAll(saved.profile().reasoningDefaults());
        defaults.put(AiPurpose.LANGUAGE, language);
        defaults.put(AiPurpose.APPROVAL, approval);
        models.saveReasoning(id, saved.revision(), defaults);
    }

    @Override
    public String toString() {
        return "LiveModelSettings[endpoint=REDACTED, credential=REDACTED]";
    }
}
