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
public final class DeploymentAgentPublicationRecoveryPreflightProgram {
    public static final String OPERATION = "deployment.agent.publication-recovery-preflight.v2";
    public static final String FORMAT = "WTL_AGENT_PUBLICATION_RECOVERY_PREFLIGHT_2";
    public static final List<String> CHECK_IDS = List.of("ENVIRONMENT", "ROOT_IDENTITY", "LOCK_DIRECTORY", "ADMISSION_LOCK", "RUN_LOCK", "LOCK_ACQUIRED",
        "JOB_ABSENT", "CANDIDATE_OWNER", "CANDIDATE_TASK", "BUILD_IDLE", "BUILD_MAIN_PID", "BUILD_CGROUP_EMPTY",
        "SNAPSHOTS_ABSENT", "MAINTENANCE_OWNER", "SOLE_RELEASE", "CURRENT_RAW_LINK", "RELEASE_OWNER",
        "PARAMETERS_FILE", "DAEMON_DECLARATION", "UNIT_FILE", "UNIT_FRAGMENT", "UNIT_DROPINS_ABSENT",
        "CONTAINER_UNIT_ABSENT", "FULL_PARAMETER_VALIDATION", "UNIT_RENDER_MATCH", "MANAGED_DATA_LINKS", "STABILITY");
    private static final Set<String> UNKNOWN_CHECKS = Set.of("FULL_PARAMETER_VALIDATION", "UNIT_RENDER_MATCH", "MANAGED_DATA_LINKS");
    private static final Set<String> REASONS = Set.of("NOT_CHECKED", "MATCH", "MISMATCH", "MISSING", "UNSAFE", "BUSY", "QUERY_FAILED", "LIMIT", "DRIFT", "NOT_IMPLEMENTED");
    private static final String RESOURCE = "/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/publication-recovery-preflight.py";
    private static final String ARGUMENT_PREFIX = "wtl_argument=";
    private static final String FRAME = "PUBLICATION_RECOVERY_PREFLIGHT=";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final long OUTPUT_LIMIT = 16384;
    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final DeploymentRemoteLayout layout;
    private final String source;

    public record PriorDiagnostic(UUID action, long requestEvent, long endEvent, String requestSha256,
                                  String receiptSha256, long pendingResultEvent, String pendingResultSha256) {
        public PriorDiagnostic {
            Objects.requireNonNull(action);
            if (pendingResultEvent <= 0 || requestEvent <= pendingResultEvent || endEvent <= requestEvent) throw invalid();
            digest(requestSha256); digest(receiptSha256); digest(pendingResultSha256);
        }
    }

    public record Binding(DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding original, PriorDiagnostic prior) {
        public Binding {
            Objects.requireNonNull(original); Objects.requireNonNull(prior);
            if (prior.pendingResultEvent() <= original.pendingIntentEvent() || prior.action().equals(original.pendingAction())) throw invalid();
        }
    }

    public record Request(CommandRequest command, Set<ActionTarget> resourceTargets, Binding binding, UUID action) implements AutoCloseable {
        public Request { Objects.requireNonNull(command); resourceTargets = Set.copyOf(resourceTargets); Objects.requireNonNull(binding); Objects.requireNonNull(action); }
        @Override public void close() { command.close(); }
    }

    public record Receipt(String canonicalJson) { public Receipt { Objects.requireNonNull(canonicalJson); } }

    public DeploymentAgentPublicationRecoveryPreflightProgram(DeploymentRemoteLayout layout) {
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

    private String script(String argument, ObjectNode envelope) {
        var rejected = JSON.createObjectNode().put("format", FORMAT).put("sourceVersion", sourceVersion())
            .put("action", text(envelope, "action"));
        rejected.set("binding", envelope.path("binding")); rejected.put("stage", "SHELL_ENVIRONMENT_REJECTED");
        rejected.set("environment", JSON.createObjectNode().put("bashEnv", "@WTL_BE@").put("env", "@WTL_ENV@")
            .put("ldPreload", "@WTL_LDP@").put("ldLibraryPath", "@WTL_LDL@").put("exportedFunctions", "@WTL_FUNCTIONS@")
            .put("snapshot", "@WTL_SNAPSHOT@").put("sanitizedSentinels", false).put("pythonStarted", false));
        var checks = rejected.putArray("checks");
        for (String id : CHECK_IDS) {
            boolean environment = id.equals("ENVIRONMENT");
            var item = checks.addObject().put("id", id).put("status", environment ? "FAIL" : "UNKNOWN")
                .put("reason", environment ? "MISMATCH" : UNKNOWN_CHECKS.contains(id) ? "NOT_IMPLEMENTED" : "NOT_CHECKED");
            item.putNull("number"); if (environment) item.put("flag", false); else item.putNull("flag"); item.putNull("sha256");
        }
        rejected.put("stable", false);
        String body = FRAME + rejected;
        StringBuilder emit = new StringBuilder("wtl_emit_rejection() {\n builtin printf '%s'");
        var markers = List.of("@WTL_BE@", "@WTL_ENV@", "@WTL_LDP@", "@WTL_LDL@", "@WTL_FUNCTIONS@", "@WTL_SNAPSHOT@");
        var variables = List.of("wtl_be", "wtl_env", "wtl_ldp", "wtl_ldl", "wtl_functions", "wtl_snapshot");
        int cursor = 0;
        for (int i = 0; i < markers.size(); i++) {
            int at = body.indexOf(markers.get(i), cursor);
            if (at < 0 || body.indexOf(markers.get(i), at + 1) >= 0) throw invalid();
            emit.append(' ').append(quote(body.substring(cursor, at))).append(" \"$").append(variables.get(i)).append('"');
            cursor = at + markers.get(i).length();
        }
        emit.append(' ').append(quote(body.substring(cursor))).append("\n builtin printf '\\n'\n exit 0\n}\n");
        return ARGUMENT_PREFIX + quote(argument) + "\n" + ENVIRONMENT_BOUNDARY + emit + ENVIRONMENT_GATE
            + "exec /usr/bin/python3 -I -S - \"$wtl_argument\" 'SDK_DEV_NULL_SENTINELS_REMOVED_V2'\n";
    }

    private static final String ENVIRONMENT_BOUNDARY = """
        set -fu
        LC_ALL=C
        builtin export LC_ALL
        wtl_be=ABSENT
        wtl_env=ABSENT
        wtl_ldp=ABSENT
        wtl_ldl=ABSENT
        wtl_functions=UNKNOWN
        wtl_snapshot=UNAVAILABLE
        if [[ ${BASH_ENV+x} ]]; then
          if [[ -z "$BASH_ENV" ]]; then wtl_be=EMPTY; elif [[ "$BASH_ENV" = /dev/null ]]; then wtl_be=DEV_NULL; else wtl_be=OTHER; fi
        fi
        if [[ ${ENV+x} ]]; then
          if [[ -z "$ENV" ]]; then wtl_env=EMPTY; elif [[ "$ENV" = /dev/null ]]; then wtl_env=DEV_NULL; else wtl_env=OTHER; fi
        fi
        if [[ ${LD_PRELOAD+x} ]]; then wtl_ldp=PRESENT; fi
        if [[ ${LD_LIBRARY_PATH+x} ]]; then wtl_ldl=PRESENT; fi
        """;

    private static final String ENVIRONMENT_GATE = """
        if [[ "$wtl_be" != DEV_NULL || "$wtl_env" != DEV_NULL || "$wtl_ldp" != ABSENT || "$wtl_ldl" != ABSENT ]]; then wtl_emit_rejection; fi
        builtin unset BASH_ENV ENV || wtl_emit_rejection
        """;

    public Request request(Binding binding, UUID action) {
        Objects.requireNonNull(binding); Objects.requireNonNull(action);
        if (!binding.original().layoutSha256().equals(layout.digest())) throw invalid();
        var envelope = JSON.createObjectNode();
        envelope.set("binding", bindingNode(binding)); envelope.put("action", action.toString());
        try { envelope.set("layout", JSON.readTree(layout.canonical())); } catch (IOException impossible) { throw invalid(); }
        envelope.put("sourceVersion", sourceVersion());
        String argument = envelope.toString();
        if (argument.getBytes(StandardCharsets.UTF_8).length > 16384) throw invalid();
        return new Request(new CommandRequest(script(argument, envelope), source.getBytes(StandardCharsets.UTF_8), TIMEOUT, OUTPUT_LIMIT),
            targets(binding), binding, action);
    }

    private Set<ActionTarget> targets(Binding binding) {
        String server = binding.original().server(), unit = binding.original().unit();
        return Set.of(new ActionTarget(server, "task", binding.original().task().toString()),
            new ActionTarget(server, "agent-publication-readonly-preflight", sourceVersion()),
            new ActionTarget(server, "application", unit),
            new ActionTarget(server, "deployment-release", layout.applicationRoot(unit) + "/releases/" + binding.original().releaseSha256()),
            new ActionTarget(server, "deployment-build", layout.candidateRoot(binding.original().candidate())),
            new ActionTarget(server, "deployment-build", layout.applicationLockRoot(unit)),
            new ActionTarget(server, "deployment-snapshot", layout.snapshotsRoot()),
            new ActionTarget(server, "systemd-unit", "windowstolinux-" + unit + ".service"),
            new ActionTarget(server, "systemd-unit", "windowstolinux-build-" + binding.original().candidate() + ".service"));
    }

    public static Map<String, Object> reviewFacts(CommandRequest command, DeploymentRemoteLayout layout) {
        var program = new DeploymentAgentPublicationRecoveryPreflightProgram(layout);
        try (var decoded = program.decodeRequest(command)) {
            return Map.of("operation", OPERATION, "binding", bindingNode(decoded.binding()), "action", decoded.action().toString(),
                "expectedSourceVersion", program.sourceVersion(), "sourceVersionMeaning", "SHA256_OF_PRODUCT_BOUND_ACTUAL_STDIN_NOT_INDEPENDENT_REMOTE_MEASUREMENT",
                "executableInputReference", "commandEvidence.completeExecutableInput", "unknownChecks", UNKNOWN_CHECKS,
                "scope", "V2 checks SDK BASH_ENV/ENV exact /dev/null before removing only those two sentinels. LD presence is rejected before Python, never cleared. Exported-function classification is a complete bounded Python os.environ enumeration before any Probe/file/lock/systemctl, not before Python startup. Python-started/environment-rejected is explicit. Current existing-only bounded observations are not repair/replay/settlement authority. Stable=false preserves intrinsic environment/root facts; unperformed checks are not drift. DAEMON is declaration-only; three intrinsic unsupported checks stay UNKNOWN.");
        }
    }

    public static Set<ActionTarget> reviewedResourceTargets(CommandRequest command, DeploymentRemoteLayout layout, ServerId server) {
        var program = new DeploymentAgentPublicationRecoveryPreflightProgram(layout);
        try (var decoded = program.decodeRequest(command)) {
            if (!decoded.binding().original().server().equals(server.id())) throw invalid();
            return decoded.resourceTargets();
        }
    }

    private Request decodeRequest(CommandRequest command) {
        if (!command.script().startsWith(ARGUMENT_PREFIX) || !command.timeout().equals(TIMEOUT) || command.outputLimitBytes() != OUTPUT_LIMIT) throw invalid();
        int lineEnd = command.script().indexOf('\n');
        if (lineEnd < 0) throw invalid();
        String suffix = command.script().substring(ARGUMENT_PREFIX.length(), lineEnd);
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
            fields(node, Set.of("format", "sourceVersion", "action", "binding", "stage", "environment", "checks", "stable"));
            if (!node.toString().equals(canonicalJson) || !text(node, "format").equals(FORMAT)
                || !text(node, "sourceVersion").equals(sourceVersion()) || !text(node, "action").equals(request.action().toString())
                || !readBinding(node.path("binding")).equals(request.binding()) || !node.path("stable").isBoolean()) throw invalid();
            validateEnvironment(node);
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
                    || !node.path("stable").booleanValue() && status.equals("PASS") && !Set.of("ENVIRONMENT", "ROOT_IDENTITY").contains(id)) throw invalid();
            }
            var stability = checks.get(CHECK_IDS.size() - 1);
            if (node.path("stable").booleanValue() != text(stability, "status").equals("PASS")) throw invalid();
            boolean rejected = !text(node, "stage").equals("GUARD_CHECKS");
            var environmentCheck = checks.get(0);
            if (rejected) {
                if (!text(environmentCheck, "status").equals("FAIL") || node.path("stable").booleanValue()) throw invalid();
                for (int i = 1; i < checks.size(); i++) if (!text(checks.get(i), "status").equals("UNKNOWN")) throw invalid();
            } else if (!text(environmentCheck, "status").equals("PASS")) throw invalid();
            return new Receipt(node.toString());
        } catch (IOException failure) { throw invalid(); }
    }

    private static void validateEnvironment(JsonNode node) {
        String stage = text(node, "stage");
        if (!Set.of("SHELL_ENVIRONMENT_REJECTED", "PYTHON_ENVIRONMENT_REJECTED", "GUARD_CHECKS").contains(stage)) throw invalid();
        var env = node.path("environment");
        fields(env, Set.of("bashEnv", "env", "ldPreload", "ldLibraryPath", "exportedFunctions", "snapshot", "sanitizedSentinels", "pythonStarted"));
        for (String key : List.of("bashEnv", "env")) if (!Set.of("ABSENT", "EMPTY", "DEV_NULL", "OTHER").contains(text(env, key))) throw invalid();
        for (String key : List.of("ldPreload", "ldLibraryPath")) if (!Set.of("ABSENT", "PRESENT").contains(text(env, key))) throw invalid();
        if (!Set.of("ABSENT", "PRESENT", "UNKNOWN").contains(text(env, "exportedFunctions"))
            || !Set.of("COMPLETE", "LIMIT", "UNAVAILABLE").contains(text(env, "snapshot")) || !env.path("sanitizedSentinels").isBoolean() || !env.path("pythonStarted").isBoolean()) throw invalid();
        boolean clean = text(env, "bashEnv").equals("DEV_NULL") && text(env, "env").equals("DEV_NULL")
            && text(env, "ldPreload").equals("ABSENT") && text(env, "ldLibraryPath").equals("ABSENT")
            && text(env, "exportedFunctions").equals("ABSENT") && text(env, "snapshot").equals("COMPLETE") && env.path("sanitizedSentinels").booleanValue();
        if (stage.equals("GUARD_CHECKS") != clean || stage.equals("SHELL_ENVIRONMENT_REJECTED") == env.path("pythonStarted").booleanValue()) throw invalid();
        if (stage.equals("SHELL_ENVIRONMENT_REJECTED") && (env.path("sanitizedSentinels").booleanValue()
            || !text(env, "snapshot").equals("UNAVAILABLE") || !text(env, "exportedFunctions").equals("UNKNOWN"))) throw invalid();
    }

    private static ObjectNode originalNode(DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding value) {
        return JSON.createObjectNode().put("server", value.server()).put("task", value.task().toString())
            .put("execution", value.execution().toString()).put("pendingAction", value.pendingAction().toString())
            .put("pendingIntentEvent", value.pendingIntentEvent()).put("pendingIntentSha256", value.pendingIntentSha256())
            .put("planSha256", value.planSha256()).put("unit", value.unit()).put("ownerSha256", value.ownerSha256())
            .put("candidate", value.candidate()).put("releaseSha256", value.releaseSha256()).put("layoutSha256", value.layoutSha256());
    }

    private static ObjectNode bindingNode(Binding binding) {
        var node = JSON.createObjectNode(); node.set("original", originalNode(binding.original()));
        var prior = binding.prior();
        node.set("prior", JSON.createObjectNode().put("action", prior.action().toString()).put("requestEvent", prior.requestEvent())
            .put("endEvent", prior.endEvent()).put("requestSha256", prior.requestSha256()).put("receiptSha256", prior.receiptSha256())
            .put("pendingResultEvent", prior.pendingResultEvent()).put("pendingResultSha256", prior.pendingResultSha256()));
        return node;
    }

    private static long positive(JsonNode node, String key) {
        if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToLong() || node.path(key).longValue() <= 0) throw invalid();
        return node.path(key).longValue();
    }

    private static Binding readBinding(JsonNode node) {
        fields(node, Set.of("original", "prior")); var original = node.path("original"); var prior = node.path("prior");
        fields(original, Set.of("server", "task", "execution", "pendingAction", "pendingIntentEvent", "pendingIntentSha256", "planSha256", "unit", "ownerSha256", "candidate", "releaseSha256", "layoutSha256"));
        fields(prior, Set.of("action", "requestEvent", "endEvent", "requestSha256", "receiptSha256", "pendingResultEvent", "pendingResultSha256"));
        return new Binding(new DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding(text(original, "server"), uuid(text(original, "task")), uuid(text(original, "execution")), uuid(text(original, "pendingAction")),
            positive(original, "pendingIntentEvent"), text(original, "pendingIntentSha256"), text(original, "planSha256"), text(original, "unit"),
            text(original, "ownerSha256"), text(original, "candidate"), text(original, "releaseSha256"), text(original, "layoutSha256")),
            new PriorDiagnostic(uuid(text(prior, "action")), positive(prior, "requestEvent"), positive(prior, "endEvent"), text(prior, "requestSha256"),
                text(prior, "receiptSha256"), positive(prior, "pendingResultEvent"), text(prior, "pendingResultSha256")));
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
