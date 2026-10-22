package gold.debug.windowstolinux.mod.deployment.linux.build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.ToolchainBuildEnvironment;

/** Typed original requests and observations for the four fixed tracked helper operations. */
public final class DeploymentTrackedToolchains {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static final int MAX_REQUEST_BYTES = 131072;
    public static final int MAX_RECEIPT_BYTES = 32768;
    private static final Set<String> REQUEST_FIELDS = Set.of("formatVersion", "operationId", "taskId", "executionId",
        "unitId", "recoveryAttempt", "originalIntentSha256", "serverId", "fingerprintSha256", "remoteLayout", "kind", "parameters");
    private static final Set<String> RECEIPT_FIELDS = Set.of("formatVersion", "operationId", "taskId", "requestSha256",
        "originalIntentSha256", "kind", "executionStatus", "effectState", "phase", "selection", "bindingSha256",
        "failureCategory", "cleanupState", "retainedEffects");
    public enum Kind { PREPARE, BIND }
    public enum ExecutionStatus { UNKNOWN, SUCCEEDED, FAILED }
    public enum EffectState { UNSETTLED, PREPARED_VERIFIED, BINDING_VERIFIED, SETTLED_RETAINED_EFFECTS, SETTLED_NO_EFFECTS }
    public enum Phase { INTENT, SELECTED, PREPARING, BIND_PUBLISHING, COMPLETED, FAILED, CLEANED }
    public enum CleanupState { NOT_NEEDED, REQUIRED, VERIFIED }
    public enum RetainedEffect { SHARED_INSTALLATION, IMMUTABLE_BINDING, SYSTEM_DEPENDENCIES }

    public record Identity(String executionId, String unitId, int recoveryAttempt, String originalIntentSha256,
                           String fingerprintSha256) {
        public Identity {
            uuid(executionId);
            if (unitId == null || !unitId.matches("[a-z0-9][a-z0-9-]{0,62}") || recoveryAttempt < 1 || recoveryAttempt > 2) throw invalid();
            digest(originalIntentSha256); digest(fingerprintSha256);
        }
    }

    /** All implementations must durably complete each call or fail before the following remote dispatch. */
    public interface OperationLog {
        void intent(Request request, String verb) throws ApiException;
        void dispatched(Request request, String verb) throws ApiException;
        void observed(Request request, String verb, OperationResult<Observation> observation) throws ApiException;
        void recipe(Identity identity, ScriptRecipe recipe) throws ApiException;
    }

    public record Session(Identity identity, OperationLog log) {
        public Session { Objects.requireNonNull(identity); Objects.requireNonNull(log); }
    }

    /** Frozen non-model script fragments, persisted before the first environment effect. */
    public record ScriptRecipe(int version, int toolchainEnvironmentFormatVersion, String prefix, String suffix) {
        public ScriptRecipe {
            if (version != 1 || toolchainEnvironmentFormatVersion != 1) throw invalid();
            Objects.requireNonNull(prefix); Objects.requireNonNull(suffix);
            if (prefix.getBytes(StandardCharsets.UTF_8).length + suffix.getBytes(StandardCharsets.UTF_8).length > 1048576)
                throw invalid();
        }
        public String render(DeploymentResolvedToolchains tools) {
            return prefix + ToolchainBuildEnvironment.render(Objects.requireNonNull(tools)) + suffix;
        }
    }

    /** Retains the exact UTF-8 bytes; observation and cleanup never regenerate JSON. */
    public static final class Request {
        private final byte[] bytes;
        private final UUID operationId;
        private final String taskId, serverId, sha256;
        private final Identity identity;
        private final DeploymentRemoteLayout layout;
        private final Kind kind;
        private final Map<String, Object> parameters;
        private Request(byte[] bytes, UUID operationId, String taskId, Identity identity, String serverId,
                        DeploymentRemoteLayout layout, Kind kind, Map<String, Object> parameters) {
            this.bytes = bytes.clone(); this.operationId = operationId; this.taskId = taskId; this.identity = identity;
            this.serverId = serverId; this.layout = layout; this.kind = kind; this.parameters = Map.copyOf(parameters);
            this.sha256 = DeploymentTrackedToolchains.sha256(bytes);
        }
        public byte[] bytes() { return bytes.clone(); }
        public UUID operationId() { return operationId; }
        public String taskId() { return taskId; }
        public Identity identity() { return identity; }
        public String serverId() { return serverId; }
        public DeploymentRemoteLayout layout() { return layout; }
        public Kind kind() { return kind; }
        public Map<String, Object> parameters() { return parameters; }
        public String sha256() { return sha256; }
        public DeploymentResolvedToolchains binding() {
            if (kind != Kind.BIND) throw invalid();
            return bindingValue((String) parameters.get("bindingBase64"));
        }
    }

    public record Selection(DeploymentToolchainType ecosystem, String version, String directory,
                            DeploymentResolvedToolchains.OriginType origin, String source, String sha256) {
        public Selection {
            Objects.requireNonNull(ecosystem); Objects.requireNonNull(origin);
            var parsed = DeploymentToolchainVersion.parse(ecosystem, version).orElseThrow(DeploymentTrackedToolchains::invalid);
            if (parsed.preview() || !parsed.text().equals(version)) throw invalid();
            if (directory == null || !directory.matches("/(?:[A-Za-z0-9_+.-]+/)*[A-Za-z0-9_+.-]+")
                || directory.contains("/../") || directory.contains("/./") || directory.endsWith("/..")) throw invalid();
            token(source, 2048); digest(sha256);
        }
    }

    /** Original execution status and currently verified effect deliberately remain separate. */
    public record Observation(UUID operationId, String taskId, String requestSha256, String originalIntentSha256,
                              Kind kind, ExecutionStatus executionStatus, EffectState effectState, Phase phase,
                              Selection selection, String bindingSha256, String failureCategory,
                              CleanupState cleanupState, Set<RetainedEffect> retainedEffects) {
        public Observation { retainedEffects = Set.copyOf(retainedEffects); }
        public boolean prepared() { return kind == Kind.PREPARE && effectState == EffectState.PREPARED_VERIFIED && selection != null; }
        public boolean bound() { return kind == Kind.BIND && effectState == EffectState.BINDING_VERIFIED && bindingSha256 != null; }
    }

    public static Request prepare(UUID operation, String task, Identity identity, String server, DeploymentRemoteLayout layout,
                                  DeploymentToolchainType ecosystem, String branch, String exact, int seconds, String javaHome) {
        var values = new LinkedHashMap<String, Object>();
        values.put("ecosystem", ecosystem.name()); values.put("branch", branch); values.put("exact", exact);
        values.put("seconds", seconds); values.put("javaHome", javaHome);
        return create(operation, task, identity, server, layout, Kind.PREPARE, values);
    }
    public static Request bind(UUID operation, String task, Identity identity, String server, DeploymentRemoteLayout layout,
                               String bindingBase64) {
        return create(operation, task, identity, server, layout, Kind.BIND, Map.of("bindingBase64", bindingBase64));
    }
    private static Request create(UUID operation, String task, Identity identity, String server, DeploymentRemoteLayout layout,
                                  Kind kind, Map<String, Object> parameters) {
        var root = JSON.createObjectNode().put("formatVersion", 1).put("operationId", operation.toString()).put("taskId", task)
            .put("executionId", identity.executionId()).put("unitId", identity.unitId()).put("recoveryAttempt", identity.recoveryAttempt())
            .put("originalIntentSha256", identity.originalIntentSha256()).put("serverId", server)
            .put("fingerprintSha256", identity.fingerprintSha256()).put("remoteLayout", layout.canonical()).put("kind", kind.name());
        root.set("parameters", JSON.valueToTree(parameters));
        return decode(root.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static Request decode(byte[] bytes) {
        var root = parse(bytes, MAX_REQUEST_BYTES, false); fields(root, REQUEST_FIELDS); version(root);
        UUID operation = uuid(text(root, "operationId")); String task = text(root, "taskId"); uuid(task);
        var identity = new Identity(text(root, "executionId"), text(root, "unitId"), integer(root, "recoveryAttempt"),
            text(root, "originalIntentSha256"), text(root, "fingerprintSha256"));
        String server = text(root, "serverId"); token(server, 512);
        var layout = DeploymentRemoteLayout.decode(text(root, "remoteLayout"));
        var kind = Kind.valueOf(text(root, "kind")); var parameters = root.get("parameters");
        var values = new LinkedHashMap<String, Object>();
        if (kind == Kind.PREPARE) {
            fields(parameters, Set.of("ecosystem", "branch", "exact", "seconds", "javaHome"));
            var ecosystem = DeploymentToolchainType.valueOf(text(parameters, "ecosystem"));
            String branch = text(parameters, "branch"), exact = text(parameters, "exact"), javaHome = text(parameters, "javaHome");
            int seconds = integer(parameters, "seconds");
            if (!branch.matches("[0-9]{1,3}(?:\\.[0-9]{1,3})?") || seconds < 30 || seconds > 7200
                || !(exact.equals("-") || DeploymentToolchainVersion.parse(ecosystem, exact)
                    .filter(v -> !v.preview() && v.text().equals(exact) && v.branch().equals(branch)).isPresent())
                || !(javaHome.equals("-") || javaHome.matches(java.util.regex.Pattern.quote(layout.toolchains() + "/versions/java-") + "[a-f0-9]{64}"))
                || javaHome.length() > 2048) throw invalid();
            values.put("ecosystem", ecosystem.name()); values.put("branch", branch); values.put("exact", exact);
            values.put("seconds", seconds); values.put("javaHome", javaHome);
        } else {
            fields(parameters, Set.of("bindingBase64")); String encoded = text(parameters, "bindingBase64");
            bindingValue(encoded); values.put("bindingBase64", encoded);
        }
        return new Request(bytes, operation, task, identity, server, layout, kind, values);
    }

    public static Observation observation(byte[] bytes, Request request) {
        var root = parse(bytes, MAX_RECEIPT_BYTES, true); fields(root, RECEIPT_FIELDS); version(root);
        UUID operation = uuid(text(root, "operationId")); String task = text(root, "taskId"), requestSha = text(root, "requestSha256"),
            originalSha = text(root, "originalIntentSha256"); Kind kind = Kind.valueOf(text(root, "kind"));
        if (!operation.equals(request.operationId()) || !task.equals(request.taskId()) || !requestSha.equals(request.sha256())
            || !originalSha.equals(request.identity().originalIntentSha256()) || kind != request.kind()) throw invalid();
        var execution = ExecutionStatus.valueOf(text(root, "executionStatus")); var effect = EffectState.valueOf(text(root, "effectState"));
        var phase = Phase.valueOf(text(root, "phase")); var cleanup = CleanupState.valueOf(text(root, "cleanupState"));
        Selection selection = null; var selected = root.get("selection");
        if (!selected.isNull()) {
            fields(selected, Set.of("ecosystem", "version", "directory", "origin", "source", "sha256"));
            selection = new Selection(DeploymentToolchainType.valueOf(text(selected, "ecosystem")), text(selected, "version"),
                text(selected, "directory"), DeploymentResolvedToolchains.OriginType.valueOf(text(selected, "origin")),
                text(selected, "source"), text(selected, "sha256"));
            if (kind != Kind.PREPARE || !selection.ecosystem().name().equals(request.parameters().get("ecosystem"))
                || !DeploymentToolchainVersion.parse(selection.ecosystem(), selection.version()).orElseThrow().branch().equals(request.parameters().get("branch"))
                || !request.parameters().get("exact").equals("-") && !selection.version().equals(request.parameters().get("exact"))) throw invalid();
        }
        String bindingSha = nullableText(root, "bindingSha256"), failure = nullableText(root, "failureCategory");
        if (bindingSha != null) {
            digest(bindingSha);
            if (kind != Kind.BIND || !bindingSha.equals(DeploymentToolchainBindingCodec.identity(request.binding()))) throw invalid();
        }
        if (failure != null && !Set.of("request", "ownership", "integrity", "permission", "unavailable", "metadata", "network", "archive",
            "installation", "probe", "platform", "timeout", "cancelled", "operation_missing", "lock_busy", "interrupted").contains(failure)) throw invalid();
        var retained = root.get("retainedEffects"); if (!retained.isArray() || retained.size() > 3) throw invalid();
        var effects = new HashSet<RetainedEffect>();
        for (var value : retained) if (!value.isTextual() || !effects.add(RetainedEffect.valueOf(value.textValue()))) throw invalid();
        if (effect == EffectState.PREPARED_VERIFIED && (kind != Kind.PREPARE || selection == null)
            || effect == EffectState.BINDING_VERIFIED && (kind != Kind.BIND || bindingSha == null)
            || execution == ExecutionStatus.SUCCEEDED && phase != Phase.CLEANED && (phase != Phase.COMPLETED || cleanup != CleanupState.NOT_NEEDED || failure != null
                || kind == Kind.PREPARE && effect != EffectState.PREPARED_VERIFIED || kind == Kind.BIND && effect != EffectState.BINDING_VERIFIED)) throw invalid();
        return new Observation(operation, task, requestSha, originalSha, kind, execution, effect, phase, selection,
            bindingSha, failure, cleanup, effects);
    }

    /** Serializes an already parsed observation for durable original-operation evidence. */
    public static com.fasterxml.jackson.databind.node.ObjectNode encodeObservation(Observation value) {
        var node = JSON.createObjectNode().put("formatVersion", 1).put("operationId", value.operationId().toString())
            .put("taskId", value.taskId()).put("requestSha256", value.requestSha256())
            .put("originalIntentSha256", value.originalIntentSha256()).put("kind", value.kind().name())
            .put("executionStatus", value.executionStatus().name()).put("effectState", value.effectState().name())
            .put("phase", value.phase().name()).put("bindingSha256", value.bindingSha256())
            .put("failureCategory", value.failureCategory()).put("cleanupState", value.cleanupState().name());
        if (value.selection() == null) node.putNull("selection");
        else {
            var selected = value.selection();
            node.putObject("selection").put("ecosystem", selected.ecosystem().name()).put("version", selected.version())
                .put("directory", selected.directory()).put("origin", selected.origin().name())
                .put("source", selected.source()).put("sha256", selected.sha256());
        }
        var effects = node.putArray("retainedEffects");
        value.retainedEffects().stream().sorted().forEach(effect -> effects.add(effect.name()));
        return node;
    }

    public static String operation(String verb) {
        return switch (verb) {
            case "prepare-tracked" -> "deployment.toolchain.prepare.tracked.v1";
            case "bind-tracked" -> "deployment.toolchain.bind.tracked.v1";
            case "observe-tracked" -> "deployment.toolchain.observe.tracked.v1";
            case "cleanup-tracked" -> "deployment.toolchain.cleanup.tracked.v1";
            default -> throw invalid();
        };
    }
    public static String command(String verb, Request request) {
        operation(verb);
        if (verb.equals("prepare-tracked") && request.kind() != Kind.PREPARE || verb.equals("bind-tracked") && request.kind() != Kind.BIND) throw invalid();
        return DeploymentBuildCommandChannel.shellQuote(request.layout().helperPath()) + " prepare-toolchains "
            + DeploymentBuildCommandChannel.shellQuote(verb) + " " + DeploymentBuildCommandChannel.shellQuote(request.operationId().toString())
            + " " + DeploymentBuildCommandChannel.shellQuote(request.sha256());
    }
    public static Map<String, Object> reviewFacts(String operation, String invocation, byte[] input, String server,
                                                 Set<gold.debug.windowstolinux.appapi.task.action.ActionTarget> targets,
                                                 DeploymentRemoteLayout layout) {
        String verb = switch (operation) {
            case "deployment.toolchain.prepare.tracked.v1" -> "prepare-tracked";
            case "deployment.toolchain.bind.tracked.v1" -> "bind-tracked";
            case "deployment.toolchain.observe.tracked.v1" -> "observe-tracked";
            case "deployment.toolchain.cleanup.tracked.v1" -> "cleanup-tracked";
            default -> throw invalid();
        };
        var request = decode(input);
        if (!layout.equals(request.layout()) || !server.equals(request.serverId()) || !invocation.equals(command(verb, request))
            || !targets.equals(Set.of(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(server, "deployment-build", layout.toolchains()),
                new gold.debug.windowstolinux.appapi.task.action.ActionTarget(server, "task", request.taskId())))) throw invalid();
        var facts = new LinkedHashMap<String, Object>();
        facts.put("operationId", request.operationId().toString()); facts.put("taskId", request.taskId()); facts.put("requestSha256", request.sha256());
        facts.put("executionId", request.identity().executionId()); facts.put("unitId", request.identity().unitId());
        facts.put("recoveryAttempt", request.identity().recoveryAttempt()); facts.put("originalIntentSha256", request.identity().originalIntentSha256());
        facts.put("serverId", request.serverId()); facts.put("fingerprintSha256", request.identity().fingerprintSha256());
        facts.put("remoteLayout", request.layout().canonical()); facts.put("kind", request.kind().name()); facts.put("verb", verb);
        facts.put("originalRequestUtf8", utf8(input));
        facts.put("parameters", request.kind() == Kind.BIND ? Map.of("canonicalBinding", DeploymentToolchainBindingCodec.encode(request.binding())) : request.parameters());
        facts.put("meaning", "Exact fixed tracked helper request. Observation and cleanup reference the original bytes and operation; they never select a new version, repeat preparation or publish an unsent binding. Original execution status and currently verified effects remain separate. This validates input identity, not remote effect or permission.");
        return Map.copyOf(facts);
    }

    private static DeploymentResolvedToolchains bindingValue(String encoded) {
        if (encoded == null || encoded.length() > 90000 || !encoded.matches("[A-Za-z0-9+/]+={0,2}")) throw invalid();
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); } catch (IllegalArgumentException invalid) { throw invalid(); }
        if (!Base64.getEncoder().encodeToString(bytes).equals(encoded) || bytes.length > 65536) throw invalid();
        String text = utf8(bytes); var binding = DeploymentToolchainBindingCodec.decode(text);
        if (!DeploymentToolchainBindingCodec.encode(binding).equals(text)) throw invalid();
        return binding;
    }
    private static JsonNode parse(byte[] bytes, int limit, boolean line) {
        if (bytes == null || bytes.length == 0 || bytes.length > limit) throw invalid();
        String text = utf8(bytes);
        if (text.charAt(0) == '\ufeff' || line && (text.indexOf('\r') >= 0 || text.indexOf('\n') != text.length() - 1)) throw invalid();
        try { return JSON.readTree(text); } catch (IOException invalid) { throw invalid(); }
    }
    private static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) { throw invalid(); }
    }
    private static void version(JsonNode root) { if (integer(root, "formatVersion") != 1) throw invalid(); }
    private static int integer(JsonNode node, String field) {
        var value = node.get(field); if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        return value.intValue();
    }
    private static String text(JsonNode node, String field) {
        var value = node.get(field); if (value == null || !value.isTextual()) throw invalid(); return value.textValue();
    }
    private static String nullableText(JsonNode node, String field) { return node.get(field).isNull() ? null : text(node, field); }
    private static void fields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) throw invalid();
        node.fieldNames().forEachRemaining(field -> { if (!fields.contains(field)) throw invalid(); });
    }
    private static UUID uuid(String text) { var id = UUID.fromString(text); if (!id.toString().equals(text)) throw invalid(); return id; }
    private static void token(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
    }
    private static void digest(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid(); }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid fixed tracked toolchain evidence"); }
    private DeploymentTrackedToolchains() { }
}
