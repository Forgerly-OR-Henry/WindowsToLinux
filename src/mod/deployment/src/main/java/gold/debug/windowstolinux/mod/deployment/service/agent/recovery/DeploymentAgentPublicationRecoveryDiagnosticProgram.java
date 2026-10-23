package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

/** One independently reviewed, existing-only observation; grants no repair, replay or settlement authority. */
public final class DeploymentAgentPublicationRecoveryDiagnosticProgram {
    public static final String OPERATION = "deployment.agent.publication-recovery-diagnostic.v1";
    public static final String FORMAT = "WTL_AGENT_PUBLICATION_RECOVERY_DIAGNOSTIC_1";
    public static final List<String> CHECK_IDS = List.of("ENVIRONMENT", "ROOT_IDENTITY", "LOCK_DIRECTORY", "ADMISSION_LOCK", "RUN_LOCK", "LOCK_ACQUIRED",
        "JOB_ABSENT", "CANDIDATE_OWNER", "CANDIDATE_TASK", "BUILD_IDLE", "BUILD_MAIN_PID", "BUILD_CGROUP_EMPTY",
        "SNAPSHOTS_ABSENT", "MAINTENANCE_OWNER", "SOLE_RELEASE", "CURRENT_RAW_LINK", "RELEASE_OWNER",
        "PARAMETERS_FILE", "DAEMON_DECLARATION", "UNIT_FILE", "UNIT_FRAGMENT", "UNIT_DROPINS_ABSENT",
        "CONTAINER_UNIT_ABSENT", "FULL_PARAMETER_VALIDATION", "UNIT_RENDER_MATCH", "MANAGED_DATA_LINKS", "STABILITY");
    private static final Set<String> UNKNOWN_CHECKS = Set.of("FULL_PARAMETER_VALIDATION", "UNIT_RENDER_MATCH", "MANAGED_DATA_LINKS");
    private static final Set<String> REASONS = Set.of("NOT_CHECKED", "MATCH", "MISMATCH", "MISSING", "UNSAFE", "BUSY", "QUERY_FAILED", "LIMIT", "DRIFT", "NOT_IMPLEMENTED");
    private static final String RESOURCE = "/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/publication-recovery-diagnostic.py";
    private static final String LAUNCHER = "/usr/bin/python3 -I -S - ";
    private static final String FRAME = "PUBLICATION_RECOVERY_DIAGNOSTIC=";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final long OUTPUT_LIMIT = 16384;
    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final DeploymentRemoteLayout layout;
    private final String source;

    public record Binding(String server, UUID task, UUID execution, UUID pendingAction, long pendingIntentEvent,
                          String pendingIntentSha256, String planSha256, String unit, String ownerSha256,
                          String candidate, String releaseSha256, String layoutSha256) {
        public Binding {
            if (server == null || !server.matches("[A-Za-z0-9._-]{1,128}")) throw invalid();
            Objects.requireNonNull(task); Objects.requireNonNull(execution); Objects.requireNonNull(pendingAction);
            if (pendingIntentEvent <= 0 || unit == null || !unit.matches("[a-z0-9][a-z0-9-]{0,62}")
                || candidate == null || !candidate.matches(java.util.regex.Pattern.quote(unit) + "-[a-f0-9]{16}")) throw invalid();
            for (String value : Arrays.asList(pendingIntentSha256, planSha256, ownerSha256, releaseSha256, layoutSha256)) digest(value);
        }
    }

    public record Request(CommandRequest command, Set<ActionTarget> resourceTargets, Binding binding, UUID action) implements AutoCloseable {
        public Request { Objects.requireNonNull(command); resourceTargets = Set.copyOf(resourceTargets); Objects.requireNonNull(binding); Objects.requireNonNull(action); }
        @Override public void close() { command.close(); }
    }

    public record Receipt(String canonicalJson) { public Receipt { Objects.requireNonNull(canonicalJson); } }

    public DeploymentAgentPublicationRecoveryDiagnosticProgram(DeploymentRemoteLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        for (String root : List.of(layout.paths().helperDirectory(), layout.paths().applicationsRoot(), layout.paths().stateRoot(),
            layout.paths().configurationRoot(), layout.paths().dataRoot()))
            if (!root.matches("/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+") || root.contains("..")) throw invalid();
        try (var input = getClass().getResourceAsStream(RESOURCE)) {
            if (input == null) throw invalid();
            byte[] bytes = input.readNBytes(65537);
            if (bytes.length > 65536) throw invalid();
            source = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (IOException failure) { throw new IllegalArgumentException("Fixed diagnostic resource unavailable", failure); }
    }

    public String source() { return source; }
    /** Expected identity computed by the product from actual stdin bytes, not an independent remote measurement. */
    public String sourceVersion() { return sha(source.getBytes(StandardCharsets.UTF_8)); }

    public Request request(Binding binding, UUID action) {
        Objects.requireNonNull(binding); Objects.requireNonNull(action);
        if (!binding.layoutSha256().equals(layout.digest())) throw invalid();
        var envelope = JSON.createObjectNode();
        envelope.set("binding", bindingNode(binding)); envelope.put("action", action.toString());
        try { envelope.set("layout", JSON.readTree(layout.canonical())); } catch (IOException impossible) { throw invalid(); }
        envelope.put("sourceVersion", sourceVersion());
        String argument = envelope.toString();
        if (argument.getBytes(StandardCharsets.UTF_8).length > 16384) throw invalid();
        return new Request(new CommandRequest(LAUNCHER + quote(argument), source.getBytes(StandardCharsets.UTF_8), TIMEOUT, OUTPUT_LIMIT),
            targets(binding), binding, action);
    }

    private Set<ActionTarget> targets(Binding binding) {
        String server = binding.server(), unit = binding.unit();
        return Set.of(new ActionTarget(server, "task", binding.task().toString()),
            new ActionTarget(server, "agent-publication-readonly-diagnostic", sourceVersion()),
            new ActionTarget(server, "application", unit),
            new ActionTarget(server, "deployment-release", layout.applicationRoot(unit) + "/releases/" + binding.releaseSha256()),
            new ActionTarget(server, "deployment-build", layout.candidateRoot(binding.candidate())),
            new ActionTarget(server, "deployment-build", layout.applicationLockRoot(unit)),
            new ActionTarget(server, "deployment-snapshot", layout.snapshotsRoot()),
            new ActionTarget(server, "systemd-unit", "windowstolinux-" + unit + ".service"),
            new ActionTarget(server, "systemd-unit", "windowstolinux-build-" + binding.candidate() + ".service"));
    }

    public static Map<String, Object> reviewFacts(CommandRequest command, DeploymentRemoteLayout layout) {
        var program = new DeploymentAgentPublicationRecoveryDiagnosticProgram(layout);
        try (var decoded = program.decodeRequest(command)) {
            return Map.of("operation", OPERATION, "binding", bindingNode(decoded.binding()), "action", decoded.action().toString(),
                "expectedSourceVersion", program.sourceVersion(), "sourceVersionMeaning", "SHA256_OF_PRODUCT_BOUND_ACTUAL_STDIN_NOT_INDEPENDENT_REMOTE_MEASUREMENT",
                "executableInputReference", "commandEvidence.completeExecutableInput", "unknownChecks", UNKNOWN_CHECKS,
                "scope", "Current existing-only no-follow bounded observation under existing read-only-opened advisory locks; no create/truncate/write/lifecycle/helper/repair/replay/settlement. DAEMON is only the bounded original parameter declaration, not full native validation.");
        }
    }

    public static Set<ActionTarget> reviewedResourceTargets(CommandRequest command, DeploymentRemoteLayout layout, ServerId server) {
        var program = new DeploymentAgentPublicationRecoveryDiagnosticProgram(layout);
        try (var decoded = program.decodeRequest(command)) {
            if (!decoded.binding().server().equals(server.id())) throw invalid();
            return decoded.resourceTargets();
        }
    }

    private Request decodeRequest(CommandRequest command) {
        if (!command.script().startsWith(LAUNCHER) || !command.timeout().equals(TIMEOUT) || command.outputLimitBytes() != OUTPUT_LIMIT) throw invalid();
        String suffix = command.script().substring(LAUNCHER.length());
        if (suffix.length() < 2 || suffix.charAt(0) != '\'' || suffix.charAt(suffix.length() - 1) != '\'') throw invalid();
        String argument = suffix.substring(1, suffix.length() - 1).replace("'\\''", "'");
        if (!quote(argument).equals(suffix) || argument.getBytes(StandardCharsets.UTF_8).length > 16384) throw invalid();
        try {
            JsonNode node = JSON.readTree(argument);
            fields(node, Set.of("binding", "action", "layout", "sourceVersion"));
            if (!node.toString().equals(argument) || !text(node, "sourceVersion").equals(sourceVersion())
                || !node.path("layout").toString().equals(layout.canonical())) throw invalid();
            var binding = readBinding(node.path("binding"));
            var rebuilt = request(binding, uuid(text(node, "action")));
            byte[] input = command.stdin(), expected = rebuilt.command().stdin();
            try {
                if (!Arrays.equals(input, expected) || !rebuilt.command().script().equals(command.script())) { rebuilt.close(); throw invalid(); }
            } finally { Arrays.fill(input, (byte) 0); Arrays.fill(expected, (byte) 0); }
            return rebuilt;
        } catch (IOException failure) { throw invalid(); }
    }

    public Receipt parse(Request request, OperationResult<CommandResult> result) {
        if (result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty()) throw invalid();
        var command = result.value().orElseThrow();
        if (command.truncated() || command.exitCode().isEmpty() || command.exitCode().orElseThrow() != 0 || !command.stderr().isEmpty()) throw invalid();
        String output = command.stdout();
        if (!output.startsWith(FRAME) || !output.endsWith("\n") || output.getBytes(StandardCharsets.UTF_8).length > OUTPUT_LIMIT
            || output.indexOf('\n') != output.length() - 1 || output.indexOf('\r') >= 0) throw invalid();
        return validateReceipt(request, output.substring(FRAME.length(), output.length() - 1));
    }

    /** Pure validation of a previously stored safe receipt; does not create an ACK or a current observation. */
    public Receipt validateReceipt(Request request, String canonicalJson) {
        try (var checked = decodeRequest(request.command())) {
            if (!request.binding().equals(checked.binding()) || !request.action().equals(checked.action())
                || !request.resourceTargets().equals(checked.resourceTargets()) || canonicalJson == null
                || canonicalJson.getBytes(StandardCharsets.UTF_8).length + FRAME.length() + 1 > OUTPUT_LIMIT) throw invalid();
            var node = JSON.readTree(canonicalJson);
            fields(node, Set.of("format", "sourceVersion", "action", "binding", "checks", "stable"));
            if (!node.toString().equals(canonicalJson) || !text(node, "format").equals(FORMAT)
                || !text(node, "sourceVersion").equals(sourceVersion()) || !text(node, "action").equals(request.action().toString())
                || !readBinding(node.path("binding")).equals(request.binding()) || !node.path("stable").isBoolean()) throw invalid();
            var checks = node.path("checks");
            if (!checks.isArray() || checks.size() != CHECK_IDS.size()) throw invalid();
            for (int i = 0; i < CHECK_IDS.size(); i++) {
                var item = checks.get(i); fields(item, Set.of("id", "status", "reason", "number", "flag", "sha256"));
                String id = text(item, "id"), status = text(item, "status"), reason = text(item, "reason");
                if (!id.equals(CHECK_IDS.get(i)) || !Set.of("PASS", "FAIL", "UNKNOWN").contains(status) || !REASONS.contains(reason)) throw invalid();
                if (!item.path("number").isNull() && (!item.path("number").isIntegralNumber() || !item.path("number").canConvertToLong()
                    || item.path("number").longValue() < 0 || item.path("number").longValue() > Integer.MAX_VALUE)) throw invalid();
                if (!item.path("flag").isNull() && !item.path("flag").isBoolean()) throw invalid();
                if (!item.path("sha256").isNull()) digest(text(item, "sha256"));
                if (status.equals("PASS") && (!reason.equals("MATCH") || !item.path("flag").isBoolean() || !item.path("flag").booleanValue())
                    || status.equals("FAIL") && (!reason.equals("MISMATCH") || !item.path("flag").isBoolean() || item.path("flag").booleanValue())
                    || status.equals("UNKNOWN") && Set.of("MATCH", "MISMATCH").contains(reason)) throw invalid();
                if (UNKNOWN_CHECKS.contains(id) && (!status.equals("UNKNOWN") || !reason.equals("NOT_IMPLEMENTED"))
                    || !node.path("stable").booleanValue() && status.equals("PASS")) throw invalid();
            }
            var stability = checks.get(CHECK_IDS.size() - 1);
            if (node.path("stable").booleanValue() != text(stability, "status").equals("PASS")) throw invalid();
            return new Receipt(node.toString());
        } catch (IOException failure) { throw invalid(); }
    }

    private static ObjectNode bindingNode(Binding binding) {
        return JSON.createObjectNode().put("server", binding.server()).put("task", binding.task().toString())
            .put("execution", binding.execution().toString()).put("pendingAction", binding.pendingAction().toString())
            .put("pendingIntentEvent", binding.pendingIntentEvent()).put("pendingIntentSha256", binding.pendingIntentSha256())
            .put("planSha256", binding.planSha256()).put("unit", binding.unit()).put("ownerSha256", binding.ownerSha256())
            .put("candidate", binding.candidate()).put("releaseSha256", binding.releaseSha256()).put("layoutSha256", binding.layoutSha256());
    }

    private static Binding readBinding(JsonNode node) {
        fields(node, Set.of("server", "task", "execution", "pendingAction", "pendingIntentEvent", "pendingIntentSha256", "planSha256", "unit", "ownerSha256", "candidate", "releaseSha256", "layoutSha256"));
        if (!node.path("pendingIntentEvent").isIntegralNumber() || !node.path("pendingIntentEvent").canConvertToLong()) throw invalid();
        return new Binding(text(node, "server"), uuid(text(node, "task")), uuid(text(node, "execution")), uuid(text(node, "pendingAction")),
            node.path("pendingIntentEvent").longValue(), text(node, "pendingIntentSha256"), text(node, "planSha256"), text(node, "unit"),
            text(node, "ownerSha256"), text(node, "candidate"), text(node, "releaseSha256"), text(node, "layoutSha256"));
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw invalid();
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add); if (!actual.equals(expected)) throw invalid();
    }
    private static String text(JsonNode node, String key) { if (!node.path(key).isTextual()) throw invalid(); return node.path(key).textValue(); }
    private static UUID uuid(String value) { var id = UUID.fromString(value); if (!id.toString().equals(value)) throw invalid(); return id; }
    private static void digest(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw invalid(); }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static String sha(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Fixed read-only publication diagnostic evidence invalid"); }
}
