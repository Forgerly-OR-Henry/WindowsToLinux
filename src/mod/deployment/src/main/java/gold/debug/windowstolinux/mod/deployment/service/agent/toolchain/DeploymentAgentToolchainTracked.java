package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;

/** Exact original controller request bytes and independently checked physical observations. */
public final class DeploymentAgentToolchainTracked {
    public static final int REQUEST_LIMIT = 131072;
    public static final String FIELD = "agentTrackedToolchain";
    public static final Set<String> VERBS = Set.of("prepare-tracked", "bind-tracked", "observe-tracked", "cleanup-tracked");
    static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final List<String> REQUEST_FIELDS = List.of("formatVersion", "taskId", "serverId", "fingerprintSha256", "remoteLayout",
        "phase", "operationId", "version", "sourceSha256", "executedSourceSha256", "invocationKind", "originalArgv");
    private static final Set<String> RESOURCE_FIELDS = Set.of("unit", "cgroup", "image", "loop", "mount", "placeholder", "promotionTemporary", "bindingTemporary", "children");
    private DeploymentAgentToolchainTracked() { }

    public static final class Recipe {
        private final byte[] bytes;
        private final ObjectNode value;
        private final DeploymentRemoteLayout layout;
        private Recipe(byte[] bytes, ObjectNode value) {
            this.bytes = bytes.clone(); this.value = value.deepCopy();
            layout = DeploymentRemoteLayout.decode(text(value, "remoteLayout"));
        }
        public byte[] bytes() { return bytes.clone(); }
        public ObjectNode value() { return value.deepCopy(); }
        public String sha256() { return hash(bytes); }
        public String taskId() { return text(value, "taskId"); }
        public String serverId() { return text(value, "serverId"); }
        public String operationId() { return text(value, "operationId"); }
        public String phase() { return text(value, "phase"); }
        public String version() { return text(value, "version"); }
        public String sourceSha256() { return text(value, "sourceSha256"); }
        public String executedSourceSha256() { return text(value, "executedSourceSha256"); }
        public String invocationKind() { return text(value, "invocationKind"); }
        public String fingerprintSha256() { return text(value, "fingerprintSha256"); }
        public DeploymentRemoteLayout layout() { return layout; }
        public List<String> originalArgv() {
            var result = new ArrayList<String>(); value.path("originalArgv").forEach(v -> result.add(v.textValue())); return List.copyOf(result);
        }
        public List<String> frame(String verb) {
            require(VERBS.contains(verb), "Unknown tracked verb");
            require(!verb.equals("prepare-tracked") || phase().equals("PREPARE"), "Foreign preparation phase");
            require(!verb.equals("bind-tracked") || phase().equals("BIND"), "Foreign binding phase");
            return List.of(verb, taskId(), operationId(), sha256());
        }
    }

    public static Recipe create(String task, String server, String fingerprint, DeploymentRemoteLayout layout,
                                String phase, String operation, List<String> argv, boolean inline) {
        var value = JSON.createObjectNode().put("formatVersion", 1).put("taskId", task).put("serverId", server)
            .put("fingerprintSha256", hash(fingerprint.getBytes(StandardCharsets.UTF_8))).put("remoteLayout", layout.canonical())
            .put("phase", phase).put("operationId", operation).put("version", DeploymentAgentToolchainProgramStore.version(layout))
            .put("sourceSha256", DeploymentAgentToolchainProgramStore.sourceSha256(layout))
            .put("executedSourceSha256", DeploymentAgentToolchainPrograms.sha256(inline
                ? DeploymentAgentToolchainPrograms.existingController(layout) : DeploymentAgentToolchainPrograms.controller(layout)))
            .put("invocationKind", inline ? "EXISTING_INLINE" : "INSTALLED");
        var args = value.putArray("originalArgv"); argv.forEach(args::add);
        return decode(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static Recipe decode(byte[] raw) {
        require(raw != null && raw.length > 0 && raw.length <= REQUEST_LIMIT, "Original recipe bound exceeded");
        var value = parse(utf8(raw)); fields(value, Set.copyOf(REQUEST_FIELDS));
        require(value.path("formatVersion").isInt() && value.path("formatVersion").intValue() == 1, "Unsupported recipe version");
        for (String key : REQUEST_FIELDS) if (!Set.of("formatVersion", "originalArgv").contains(key)) text(value, key);
        require(UUID.fromString(text(value, "taskId")).toString().equals(text(value, "taskId")), "Noncanonical task");
        String server = text(value, "serverId");
        require(!server.isBlank() && server.getBytes(StandardCharsets.UTF_8).length <= 512 && noAsciiControls(server), "Invalid server");
        for (String key : List.of("fingerprintSha256", "version", "sourceSha256", "executedSourceSha256")) digest(text(value, key));
        String op = text(value, "operationId"); require(op.matches("[a-f0-9]{32}"), "Invalid operation identity");
        require(Set.of("PREPARE", "BIND").contains(text(value, "phase")), "Invalid original phase");
        require(Set.of("INSTALLED", "EXISTING_INLINE").contains(text(value, "invocationKind")), "Invalid invocation kind");
        require(!text(value, "invocationKind").equals("INSTALLED") || text(value, "sourceSha256").equals(text(value, "executedSourceSha256")), "Installed execution source changed");
        require(!text(value, "invocationKind").equals("EXISTING_INLINE") || text(value, "phase").equals("PREPARE"), "Inline binding forbidden");
        var layout = DeploymentRemoteLayout.decode(text(value, "remoteLayout"));
        var args = value.path("originalArgv"); require(args.isArray(), "Original argv missing");
        var argv = new ArrayList<String>();
        args.forEach(arg -> { require(arg.isTextual() && noAsciiControls(arg.textValue()), "Invalid original argv"); argv.add(arg.textValue()); });
        if (text(value, "phase").equals("PREPARE")) {
            require(argv.size() == 8 && argv.getFirst().equals("prepare") && argv.get(6).equals(op), "Preparation argv changed");
            var ecosystem = DeploymentToolchainType.valueOf(argv.get(1));
            DeploymentAgentToolchainCommands.requirePreparation(ecosystem, argv.get(2), argv.get(3), seconds(argv.get(4)), argv.get(5), layout);
            require(argv.get(7).length() <= 5464, "Release bound exceeded");
            DeploymentAgentToolchainRelease.decode(argv.get(7), ecosystem, argv.get(2), argv.get(3), op, layout);
        } else {
            require(argv.size() == 4 && argv.getFirst().equals("bind") && argv.get(3).equals(op), "Binding argv changed");
            seconds(argv.get(2));
            byte[] binding = Base64.getDecoder().decode(argv.get(1));
            require(binding.length <= 65536 && Base64.getEncoder().encodeToString(binding).equals(argv.get(1)), "Binding encoding changed");
            DeploymentAgentToolchainCommands.requireBinding(argv.get(1), layout);
        }
        var ordered = JSON.createObjectNode(); REQUEST_FIELDS.forEach(key -> ordered.set(key, value.get(key)));
        require(Arrays.equals(raw, ordered.toString().getBytes(StandardCharsets.UTF_8)), "Recipe must retain canonical original bytes");
        return new Recipe(raw, ordered);
    }

    public record Observation(Recipe recipe, String verb, ObjectNode value) {
        public Observation { value = value.deepCopy(); }
        @Override public ObjectNode value() { return value.deepCopy(); }
        public boolean settled() { return !value.path("effectState").asText().equals("UNSETTLED"); }
        public boolean succeeded() { return settled() && value.path("executionStatus").asText().equals("SUCCEEDED"); }
        public boolean cleanupRequired() { return value.path("cleanupState").asText().equals("REQUIRED"); }
        public String executionStatus() { return text(value, "executionStatus"); }
        public String failureCode() { return value.path("failureCode").isNull() ? null : text(value, "failureCode"); }
        public String bindingSha256() { return value.path("bindingSha256").isNull() ? null : text(value, "bindingSha256"); }
        public DeploymentResolvedToolchains.OriginType origin() { return DeploymentResolvedToolchains.OriginType.valueOf(value.path("selection").path("origin").asText()); }
    }

    public static Observation receipt(OperationResult<CommandResult> result, Recipe recipe, String verb) {
        require(result.value().isPresent(), "Missing actual SDK result");
        var command = result.value().orElseThrow();
        require(result.outcome() == TaskResult.SUCCEEDED && command.exitCode().equals(Optional.of(0)) && !command.truncated()
            && command.stderr().isEmpty(), "Incomplete tracked transport");
        return receipt(command.stdout(), recipe, verb);
    }

    public static Observation receipt(String stdout, Recipe recipe, String verb) {
        require(stdout != null && stdout.getBytes(StandardCharsets.UTF_8).length <= 32768
            && stdout.startsWith("AGENT_TOOLCHAIN_TRACKED=") && stdout.endsWith("\n")
            && stdout.indexOf('\n') == stdout.length() - 1, "Invalid tracked receipt frame");
        var value = parse(stdout.substring("AGENT_TOOLCHAIN_TRACKED=".length(), stdout.length() - 1));
        fields(value, Set.of("formatVersion", "verb", "taskId", "serverId", "fingerprintSha256", "layoutSha256", "phase", "operationId", "version", "sourceSha256", "executedSourceSha256", "invocationKind", "requestSha256", "proofState", "executionStatus", "effectState", "cleanupState", "selection", "bindingSha256", "resources", "retainedEffects", "failureCode", "creationSha256", "stateSha256"));
        require(value.path("formatVersion").isInt() && value.path("formatVersion").intValue() == 1 && VERBS.contains(verb)
            && text(value, "verb").equals(verb), "Tracked receipt version/verb changed");
        for (String key : List.of("taskId", "serverId", "fingerprintSha256", "phase", "operationId", "version", "sourceSha256", "executedSourceSha256", "invocationKind"))
            require(value.path(key).equals(recipe.value.path(key)), "Tracked receipt identity changed: " + key);
        require(text(value, "requestSha256").equals(recipe.sha256()) && text(value, "layoutSha256").equals(recipe.layout().digest()), "Original request binding changed");
        for (String key : List.of("creationSha256", "stateSha256", "bindingSha256")) if (!value.path(key).isNull()) digest(text(value, key));
        enumValue(value, "proofState", "VERIFIED", "MISSING", "CONFLICT");
        enumValue(value, "executionStatus", "UNKNOWN", "SUCCEEDED", "FAILED");
        enumValue(value, "effectState", "UNSETTLED", "SETTLED_RETAINED_EFFECTS", "SETTLED_NO_EFFECTS");
        enumValue(value, "cleanupState", "REQUIRED", "VERIFIED", "NOT_NEEDED");
        if (!value.path("failureCode").isNull()) enumValue(value, "failureCode", "REQUEST_INVALID", "IDENTITY_MISMATCH", "OPERATION_MISSING", "LEGACY_PROOF_MISSING", "LOCK_BUSY", "ACTIVE_CHILD", "RESOURCE_UNCONFIRMED", "RESOURCE_CHANGED", "EXECUTION_FAILED", "CLEANUP_FAILED", "INTERRUPTED");
        var resources = value.path("resources"); fields(resources, RESOURCE_FIELDS);
        for (String key : RESOURCE_FIELDS) {
            var allowed = new HashSet<>(Set.of("ABSENT", "OWNED_PRESENT", "UNKNOWN"));
            if (Set.of("unit", "cgroup", "children").contains(key)) allowed.add("QUIESCENT");
            if (key.equals("placeholder")) allowed.add("RETAINED_SHARED");
            require(allowed.contains(text(resources, key)), "Invalid physical resource state");
        }
        var effects = value.path("retainedEffects"); require(effects.isArray() && effects.size() <= 3, "Invalid retained effects");
        var retained = new ArrayList<String>();
        effects.forEach(effect -> { require(effect.isTextual() && Set.of("SHARED_INSTALLATION", "IMMUTABLE_BINDING", "SYSTEM_DEPENDENCIES").contains(effect.textValue()), "Invalid retained effect"); retained.add(effect.textValue()); });
        require(retained.equals(retained.stream().distinct().sorted().toList()), "Effects must be sorted and unique");
        boolean settled = !text(value, "effectState").equals("UNSETTLED");
        if (settled) {
            require(text(value, "proofState").equals("VERIFIED") && !text(value, "cleanupState").equals("REQUIRED")
                && !value.path("creationSha256").isNull() && !value.path("stateSha256").isNull(), "Settlement lacks original proof");
            for (String key : RESOURCE_FIELDS) require(key.equals("placeholder") ? Set.of("ABSENT", "RETAINED_SHARED").contains(text(resources, key))
                : Set.of("unit", "cgroup", "children").contains(key) ? Set.of("ABSENT", "QUIESCENT").contains(text(resources, key))
                : text(resources, key).equals("ABSENT"), "Settlement retains a private resource");
            require(text(value, "effectState").equals(retained.isEmpty() ? "SETTLED_NO_EFFECTS" : "SETTLED_RETAINED_EFFECTS"), "Settlement effects disagree");
        } else require(text(value, "cleanupState").equals("REQUIRED"), "Unsettled effects require reconciliation");
        if (recipe.phase().equals("BIND")) {
            require(value.path("selection").isNull(), "Binding cannot select a toolchain");
            if (!value.path("bindingSha256").isNull()) require(text(value, "bindingSha256").equals(hash(Base64.getDecoder().decode(recipe.originalArgv().get(1)))), "Original binding changed");
        } else {
            require(value.path("bindingSha256").isNull(), "Preparation cannot seal a binding");
            if (!value.path("selection").isNull()) {
                var selection = value.path("selection"); fields(selection, Set.of("ecosystem", "version", "directory", "origin", "source", "sha256"));
                for (String key : List.of("ecosystem", "version", "directory", "origin", "source", "sha256")) text(selection, key);
                require(text(selection, "ecosystem").equals(recipe.originalArgv().get(1)), "Selected ecosystem changed");
                DeploymentToolchainType.valueOf(text(selection, "ecosystem"));
                DeploymentResolvedToolchains.OriginType.valueOf(text(selection, "origin")); digest(text(selection, "sha256"));
                var version = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion.parse(
                    DeploymentToolchainType.valueOf(text(selection, "ecosystem")), text(selection, "version")).orElseThrow();
                require(version.branch().equals(recipe.originalArgv().get(2)) && (recipe.originalArgv().get(3).equals("-")
                    || text(selection, "version").equals(recipe.originalArgv().get(3))), "Selected original version changed");
                require(text(selection, "directory").matches(java.util.regex.Pattern.quote(recipe.layout().toolchains()) + "/versions/[a-z_]+-[0-9a-f]{64}")
                    && text(selection, "source").length() <= 2048 && !text(selection, "source").isBlank()
                    && text(selection, "source").chars().noneMatch(c -> c == 0 || c == '\n' || c == '\r'), "Selected source escaped original bounds");
            }
        }
        if (settled && text(value, "executionStatus").equals("SUCCEEDED")) require(recipe.phase().equals("BIND")
            ? !value.path("bindingSha256").isNull() && retained.contains("IMMUTABLE_BINDING")
            : !value.path("selection").isNull() && retained.contains("SHARED_INSTALLATION"), "Successful original effect lacks its retained physical binding");
        return new Observation(recipe, verb, (ObjectNode) value);
    }

    static ObjectNode parse(String value) {
        try { var node = JSON.readTree(value); require(node != null && node.isObject(), "Object required"); return (ObjectNode) node; }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid strict tracked JSON", invalid); }
    }
    static void fields(JsonNode node, Set<String> expected) {
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        require(node.isObject() && actual.equals(expected), "Unexpected tracked fields");
    }
    static String text(JsonNode node, String key) { require(node.path(key).isTextual(), "Text required: " + key); return node.path(key).textValue(); }
    private static boolean noAsciiControls(String value) { return value.chars().noneMatch(c -> c < 32 || c == 127); }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void enumValue(JsonNode node, String key, String... values) { require(Set.of(values).contains(text(node, key)), "Invalid " + key); }
    private static void digest(String value) { require(value.matches("[0-9a-f]{64}"), "Invalid SHA256"); }
    private static int seconds(String value) {
        require(value.matches("[1-9][0-9]{1,3}"), "Noncanonical seconds"); int result = Integer.parseInt(value);
        require(result >= 30 && result <= 7200, "Seconds outside original budget"); return result;
    }
    static String utf8(byte[] value) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid original UTF8", invalid); }
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
