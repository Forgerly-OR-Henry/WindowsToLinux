package gold.debug.windowstolinux.mod.deployment.linux.build;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentToolchainPreparationEvidence;

/** Independent, original-operation diagnostics; the existing preparation receipt stays unchanged. */
public final class DeploymentToolchainDiagnostics {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static final int MAX_OUTPUT_BYTES = 262144;
    private static final Set<String> REQUEST_FIELDS = Set.of("formatVersion", "operationId", "taskId", "serverId",
        "fingerprintSha256", "planRevision", "remoteLayout", "arguments");
    private static final Set<String> DIAGNOSTIC_FIELDS = Set.of("formatVersion", "operationId", "taskId", "serverId",
        "fingerprintSha256", "planRevision", "requestSha256", "preparationStatus", "failureCategory", "stage", "executable",
        "actualExitCode", "timedOut", "cancelled", "stdout", "stderr", "stdoutTruncated", "stderrTruncated");

    private DeploymentToolchainDiagnostics() { }

    public static String checkpointKey(UUID operationId) { return "deployment-toolchain-diagnostic-" + operationId.toString().replace("-", ""); }

    /** Reads retained original request bytes in its authenticated task/recovery scope, without creating a new operation. */
    public static java.util.Optional<Request> retained(gold.debug.windowstolinux.appapi.task.TaskService tasks,
            gold.debug.windowstolinux.appapi.task.run.TaskContext task, UUID operationId)
            throws gold.debug.windowstolinux.appapi.task.result.ApiException {
        var checkpoint = tasks.findCheckpoint(task, checkpointKey(operationId));
        if (checkpoint.isEmpty()) return java.util.Optional.empty();
        var request = decode(checkpoint.orElseThrow().snapshot().payload().getBytes(StandardCharsets.UTF_8));
        if (!request.operationId().equals(operationId) || !request.taskId().equals(task.id().toString())) throw invalid();
        return java.util.Optional.of(request);
    }

    public record Request(UUID operationId, String taskId, String serverId, String fingerprintSha256,
                          String planRevision, DeploymentRemoteLayout layout, List<String> arguments) {
        public Request {
            java.util.Objects.requireNonNull(operationId); uuid(taskId); digest(fingerprintSha256); digest(planRevision);
            if (serverId == null || serverId.isEmpty() || serverId.length() > 512 || serverId.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
            java.util.Objects.requireNonNull(layout); arguments = List.copyOf(arguments);
            if (arguments.size() != 6 || !arguments.getFirst().equals("prepare")) throw invalid();
            DeploymentToolchainPreparationEvidence.decode(legacyCommand(layout, arguments), layout).orElseThrow(DeploymentToolchainDiagnostics::invalid);
            if (!arguments.get(5).equals("-") && !arguments.get(5).matches(java.util.regex.Pattern.quote(layout.toolchains() + "/versions/java-") + "[a-f0-9]{64}")) throw invalid();
        }
        public byte[] bytes() {
            var root = JSON.createObjectNode().put("formatVersion", 1).put("operationId", operationId.toString())
                .put("taskId", taskId).put("serverId", serverId).put("fingerprintSha256", fingerprintSha256)
                .put("planRevision", planRevision).put("remoteLayout", layout.canonical());
            root.set("arguments", JSON.valueToTree(arguments));
            return root.toString().getBytes(StandardCharsets.UTF_8);
        }
        public String sha256() { return DeploymentTrackedToolchains.sha256(bytes()); }
        public String target() { return layout.toolchains() + "/diagnostics/" + operationId; }
        public Map<String, Object> preparationArguments() {
            return DeploymentToolchainPreparationEvidence.decode(legacyCommand(layout, arguments), layout).orElseThrow(DeploymentToolchainDiagnostics::invalid);
        }
    }

    public static String command(String verb, Request request) {
        if (!Set.of("prepare-diagnosed", "diagnose-only").contains(verb)) throw invalid();
        return DeploymentBuildCommandChannel.shellQuote(request.layout().helperPath()) + " prepare-toolchains "
            + DeploymentBuildCommandChannel.shellQuote(verb) + " " + DeploymentBuildCommandChannel.shellQuote(request.operationId().toString())
            + " " + DeploymentBuildCommandChannel.shellQuote(request.sha256());
    }

    private static String legacyCommand(DeploymentRemoteLayout layout, List<String> arguments) {
        return DeploymentBuildCommandChannel.shellQuote(layout.helperPath()) + " prepare-toolchains "
            + arguments.stream().map(DeploymentBuildCommandChannel::shellQuote).collect(java.util.stream.Collectors.joining(" "));
    }

    public static Request decode(byte[] bytes) {
        var value = parse(bytes, 16384); fields(value, REQUEST_FIELDS); version(value);
        var arguments = value.get("arguments");
        if (!arguments.isArray() || arguments.size() != 6) throw invalid();
        var list = new java.util.ArrayList<String>();
        for (var argument : arguments) { if (!argument.isTextual()) throw invalid(); list.add(argument.textValue()); }
        var request = new Request(uuid(text(value, "operationId")), text(value, "taskId"), text(value, "serverId"),
            text(value, "fingerprintSha256"), text(value, "planRevision"), DeploymentRemoteLayout.decode(text(value, "remoteLayout")), list);
        // This dedicated wire format has one canonical encoding, including the digest's exact byte scope.
        if (!java.util.Arrays.equals(bytes, request.bytes())) throw invalid();
        return request;
    }

    public static Map<String, Object> reviewFacts(String verb, String invocation, byte[] input, String server,
                                                 Set<ActionTarget> targets, DeploymentRemoteLayout layout) {
        var request = decode(input);
        var expected = verb.equals("prepare-diagnosed") ? Set.of(new ActionTarget(server, "deployment-build", layout.toolchains()))
            : Set.of(new ActionTarget(server, "deployment-toolchain-diagnostic", request.target()), new ActionTarget(server, "task", request.taskId()));
        if (!request.serverId().equals(server) || !request.layout().equals(layout) || !command(verb, request).equals(invocation)
            || !targets.equals(expected)) throw invalid();
        return Map.of("operationId", request.operationId().toString(), "taskId", request.taskId(), "serverId", server,
            "fingerprintSha256", request.fingerprintSha256(), "planRevision", request.planRevision(),
            "originalRequestSha256", request.sha256(), "originalRequestUtf8", new String(input, StandardCharsets.UTF_8),
            "diagnosticTarget", request.target(), "verb", verb,
            "meaning", "Digest covers the exact canonical UTF-8 original preparation request. This attachment retains the last actual installation command's exit status and bounded redacted stdout/stderr. diagnose-only reads the original protected attachment without preparing or retrying anything. No successful execution or permission is asserted.");
    }

    /** Validates the independently fetched attachment against every original identity field. */
    public static String attachment(byte[] bytes, Request request) {
        var value = parse(bytes, MAX_OUTPUT_BYTES); fields(value, DIAGNOSTIC_FIELDS); version(value);
        for (var entry : Map.of("operationId", request.operationId().toString(), "taskId", request.taskId(), "serverId", request.serverId(),
            "fingerprintSha256", request.fingerprintSha256(), "planRevision", request.planRevision(), "requestSha256", request.sha256()).entrySet())
            if (!text(value, entry.getKey()).equals(entry.getValue())) throw invalid();
        if (!Set.of("UNKNOWN", "SUCCEEDED", "FAILED").contains(text(value, "preparationStatus"))
            || !(value.get("failureCategory").isNull() || value.get("failureCategory").isTextual())
            || !text(value, "stage").matches("[a-z-]{1,64}") || !text(value, "executable").matches("[A-Za-z0-9._+-]{0,128}")) throw invalid();
        var exit = value.get("actualExitCode");
        if (!(exit.isNull() || exit.isIntegralNumber() && exit.canConvertToInt())) throw invalid();
        for (String key : List.of("timedOut", "cancelled", "stdoutTruncated", "stderrTruncated")) if (!value.get(key).isBoolean()) throw invalid();
        if (text(value, "preparationStatus").equals("SUCCEEDED") && (!value.get("failureCategory").isNull()
            || !exit.isNull() && exit.intValue() != 0 || value.get("timedOut").booleanValue() || value.get("cancelled").booleanValue())) throw invalid();
        for (String key : List.of("stdout", "stderr")) if (text(value, key).getBytes(StandardCharsets.UTF_8).length > 16384) throw invalid();
        return value.toString();
    }

    private static JsonNode parse(byte[] bytes, int limit) {
        if (bytes.length == 0 || bytes.length > limit) throw invalid();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\ufeff")) throw invalid();
            return JSON.readTree(text);
        } catch (java.io.IOException failure) { throw invalid(); }
    }
    private static void fields(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject()) throw invalid();
        var actual = new java.util.HashSet<String>(); value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw invalid();
    }
    private static void version(JsonNode value) { if (!value.get("formatVersion").isInt() || value.get("formatVersion").intValue() != 1) throw invalid(); }
    private static String text(JsonNode value, String key) { if (!value.get(key).isTextual()) throw invalid(); return value.get(key).textValue(); }
    private static UUID uuid(String value) { UUID uuid = UUID.fromString(value); if (!uuid.toString().equals(value)) throw invalid(); return uuid; }
    private static void digest(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid original toolchain diagnostic evidence"); }
}
