package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.util.*;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

/** Preserves only this dispatched Agent program's bounded failure evidence. / 仅保留本次已发送 Agent 程序的有界失败依据。 */
public final class DeploymentAgentToolchainFailureEvidence {
    private static final String EVENT = "DEPLOYMENT_AGENT_TOOLCHAIN_EXECUTION_FAILED";

    private static final String FIELD = "agentToolchainFailure";

    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private static final Pattern FAILURE = Pattern.compile("TOOLCHAIN_FAILURE=([a-z_]{1,32}):([A-Z_]{1,96})");

    private static final Set<String> LIFECYCLE_PHASES = Set.of("AGENT_SETUP_UNCONFIRMED", "AGENT_EXECUTION_UNCONFIRMED",
        "AGENT_STOP_UNCONFIRMED", "AGENT_FREEZE_UNCONFIRMED");

    private static final Set<String> CONTROLLER_FUNCTIONS = Set.of(
        "agent_bind", "agent_cleanup_before_start", "agent_cleanup_placeholder", "agent_copy_tree", "agent_create",
        "agent_detach", "agent_execute", "agent_freeze", "agent_identity", "agent_installation_selection",
        "agent_main", "agent_manifest", "agent_os", "agent_prepare", "agent_prepare_selected",
        "agent_probe_records", "agent_promote", "agent_publish_ready", "agent_ready_file", "agent_record",
        "agent_regular", "agent_release_source", "agent_require_space", "agent_scan", "agent_setup",
        "agent_stopped", "agent_tool", "agent_unit_identity", "agent_unit_stopped", "agent_wait_ready",
        "agent_worker", "agent_write", "agent_volume_bytes", "agent_selection_snapshot", "agent_validate_snapshot",
        "agent_verify_selection", "agent_verify_selections", "entry");

    private static final Set<String> LIFECYCLE_STAGES = Set.of(
        "FREEZE", "IDENTITY", "INCOMPLETE", "LAUNCH", "READY",
        "RUN", "SETUP", "STOP");

    private static final Set<String> LIFECYCLE_CATEGORIES = Set.of(
        "installation", "isolation", "none", "other", "unknown");

    private static final Set<String> LIFECYCLE_CODES = Set.of(
        "CGROUP_NOT_EMPTY", "CONTROLLER_BINDING", "DEADLINE_EXHAUSTED", "DUPLICATE_JSON_FIELD", "DYNAMIC_IDENTITY",
        "FREEZE_DIRECTORY_CHANGED", "FREEZE_IDENTITY", "FREEZE_RECEIPT", "FREEZE_REQUIRES_ROOT", "IDENTITY_ALREADY_ASSIGNED",
        "IDENTITY_SHORT_WRITE", "INSTALL_GEM", "INSTALL_LAYOUT", "INSTALL_SCHEMA", "INSTALL_SOURCE",
        "INVALID_JSON", "JSON_CONSTANT", "JSON_LIMIT", "MANAGED_DIRECTORY", "MANIFEST_ACTION",
        "MANIFEST_BINDING", "MANIFEST_DEADLINE", "MANIFEST_DIRECTORIES", "MANIFEST_DIRECTORY_INODE", "MANIFEST_INODE",
        "MANIFEST_LOOP", "MANIFEST_SCHEMA", "PROBE_COUNT", "PROBE_RECORD", "PROBE_SCHEMA",
        "PROGRAM_BINDING", "PROTECTED_DIRECTORY", "PROTECTED_FILE", "PROTECTED_FILE_CHANGED", "READY_ALREADY_PUBLISHED",
        "READY_FILE", "READY_FILE_CHANGED", "READY_SHORT_WRITE", "REQUIRED_SYSTEM_TOOL", "SELECTION",
        "SELECTION_DIRECTORY", "SETUP_NOT_COMPLETED", "SETUP_REQUIRES_ORIGINAL_EMPTY_DIRECTORY", "SETUP_REQUIRES_ROOT", "SYSTEM_COMMAND_FAILED",
        "SYSTEM_COMMAND_OUTPUT_LIMIT", "SYSTEM_COMMAND_TIMEOUT", "UNCLASSIFIED", "UNIT_IDENTITY", "UNIT_IDENTITY_CHANGED",
        "UNIT_NOT_STOPPED", "UNIT_STATE_UNCONFIRMED", "VOLUME_IMAGE", "VOLUME_IMAGE_CHANGED", "VOLUME_LOOP_OWNER",
        "VOLUME_MOUNT", "VOLUME_MOUNT_CHANGED", "VOLUME_NOT_FROZEN", "VOLUME_POLICY", "MANIFEST_SELECTIONS", "INSTALLATION_SELECTION_CHANGED");

    private static final Set<String> LIFECYCLE_EXCEPTIONS = Set.of(
        "KeyError", "NONE", "OSError", "OTHER", "PreparationFailure",
        "StopIteration", "TimeoutExpired", "ValueError");

    private static final Set<String> LIFECYCLE_CHECKS = Set.of(
        "ACTIVE_STATE", "ACTIVE_STATE_INACTIVE", "ALLOCATION", "CGROUP", "FIELDS",
        "ID", "INVOCATION", "LOAD_STATE", "NONE", "PARSE",
        "USER_MODE");

    private DeploymentAgentToolchainFailureEvidence() {
    }

    static final String PRECREATION = "precreationRefusal";

    private static final String PRECREATION_FORMAT = "WTL_AGENT_CONTROLLER_PRECREATION_REFUSAL_1";

    private static final String EXISTING_PRECREATION_FORMAT = "WTL_AGENT_CONTROLLER_EXISTING_PRECREATION_REFUSAL_1";

    private static final String EXISTING_PRECREATION_STDOUT = "TOOLCHAIN_FAILURE=isolation:EXISTING_INSTALLATION_REQUIRED\n";

    private static final String EXISTING_SELECTION_BOUNDARY = "    selections = [agent_selection_snapshot(record)] if record is not None else []\n";

    private static final String EXISTING_PRECREATION_GUARD = "    if not selections:\n        agent_reject('EXISTING_INSTALLATION_REQUIRED')\n";

    // Each literal is an independently reviewed complete default-layout source, never a dynamic manifest alias.
    private static final String ORIGINAL_PRECREATION_SOURCE = "2f82c5c50834f747ff005832edafbc47e9bed72103a98a538a8242d8ed4f3c19";

    private static final String PROBE_PRECREATION_SOURCE = "7ce598b142448c3385f845426f3d61a8564f23ea4fc8e5a4b160f543c2035159";

    private static final String TRACKED_CONTROLLER_SOURCE = "9cd73cbb2a80d77638cf60da0246b391bf4f59935b7982c6662a02f6da3f9e9e";

    private static final Map<String, Integer> PRECREATION_SOURCE_BYTES = Map.of(
        ORIGINAL_PRECREATION_SOURCE, 101904, PROBE_PRECREATION_SOURCE, 108038, TRACKED_CONTROLLER_SOURCE, 154357);

    private static final long INSTALL_REQUIRED_BYTES = 9L * 1024 * 1024 * 1024;

    private static final long PROBE_REQUIRED_BYTES = (1024L + 64) * 1024 * 1024;

    private static boolean precreationThreshold(String sourceSha256, long required) {
        return ORIGINAL_PRECREATION_SOURCE.equals(sourceSha256) && required == INSTALL_REQUIRED_BYTES
            || probeSource(sourceSha256) && (required == INSTALL_REQUIRED_BYTES || required == PROBE_REQUIRED_BYTES);
    }

    private static boolean probeSource(String source) { return PROBE_PRECREATION_SOURCE.equals(source) || TRACKED_CONTROLLER_SOURCE.equals(source); }

    private static final String PRECREATION_MEANING = "ONLY_THIS_OPERATION_PRIVATE_RESOURCES_NOT_CREATED; SHARED_DIRECTORIES_AND_PREPARE_LOCK_RETAINED; NOT_CLEANUP_COMPLETE";

    static final class PrecreationRefusal {
        private final JsonNode evidence;

        private PrecreationRefusal(JsonNode evidence) {
            this.evidence = evidence.deepCopy();
        }

        JsonNode evidence() {
            return evidence.deepCopy();
        }
    }

    /** Mints evidence only from the original live INTENT and the exact pinned invocation's current result. */
    static Optional<PrecreationRefusal> precreationRefusal(DeploymentAgentToolchainProgramState.Action action,
                                                           String phase, String operation, String task, ServerId server,
                                                           DeploymentRemoteLayout layout, CommandRequest request,
                                                           OperationResult<CommandResult> result) {
        if (!Set.of("prepare", "bind").contains(phase) || result.outcome() != TaskResult.FAILED || result.value().isEmpty())
            return Optional.empty();
        var command = result.value().orElseThrow();
        if (!command.exitCode().equals(Optional.of(65)) || command.truncated() || !command.stderr().isEmpty())
            return Optional.empty();
        byte[] stdin = request.stdin();
        try {
            String sourceSha256 = DeploymentAgentToolchainProgramStore.sourceSha256(layout);
            int sourceBytes = PRECREATION_SOURCE_BYTES.getOrDefault(sourceSha256, -1);
            boolean existing = stdin.length != 0;
            if (!action.id().matches("[0-9a-f]{32}") || sourceBytes < 1
                || existing && result.problem().filter(problem -> problem.code() == ProblemCode.IO_FAILURE && problem.eventCode().equals("SSH_COMMAND_NONZERO")).isEmpty()
                || DeploymentAgentToolchainPrograms.controller(layout).getBytes(java.nio.charset.StandardCharsets.UTF_8).length != sourceBytes
                || (existing ? !existingPrecreationInvocation(phase, operation, layout, request, stdin, command.stdout(), sourceSha256)
                    : precreationDiagnostic(command.stdout(), operation, sourceSha256).isEmpty()))
                return Optional.empty();
            var argv = existing ? List.<String>of() : DeploymentAgentToolchainProgramStore.arguments(request.script(), layout);
            if (!existing && (argv.size() != (phase.equals("prepare") ? 11 : 7) || !argv.getFirst().equals("invoke")
                || !argv.get(1).equals(task) || !argv.get(2).equals(operation) || !argv.get(3).equals(phase)
                || !argv.get(phase.equals("prepare") ? 9 : 6).equals(operation)))
                return Optional.empty();
            var snapshot = action.checkpoint().snapshot();
            var payload = JSON.readTree(snapshot.payload());
            var index = payload.path("index");
            var expected = JSON.createObjectNode().put("key", "agent-controller-program." + action.id()).put("task", task)
                .put("server", server.id()).put("layoutSha256", layout.digest()).put("phase", phase.toUpperCase(Locale.ROOT))
                .put("operation", operation).put("version", DeploymentAgentToolchainProgramStore.version(layout))
                .put("path", DeploymentAgentToolchainProgramStore.path(layout)).put("sourceSha256", DeploymentAgentToolchainProgramStore.sourceSha256(layout))
                .put("requestSha256", DeploymentAgentToolchainPrograms.sha256(RemoteActions.command(server, "controller-action-state", request).exactAction()));
            if (snapshot.released() || !payload.path("format").asText().equals("WTL_AGENT_CONTROLLER_ACTION_1")
                || !payload.path("state").asText().equals("INTENT") || !index.equals(expected))
                return Optional.empty();
            var proof = JSON.createObjectNode().put("format", existing ? EXISTING_PRECREATION_FORMAT : PRECREATION_FORMAT).put("action", action.id())
                .put("stdout", command.stdout()).put("meaning", PRECREATION_MEANING).put("sourceBytes", sourceBytes);
            proof.set("index", index.deepCopy());
            if (existing) proof.put("exactAction", RemoteActions.command(server, "controller-action-state", request).exactAction())
                .put("executedSourceSha256", DeploymentAgentToolchainPrograms.sha256(DeploymentAgentToolchainPrograms.existingController(layout)))
                .put("executedSourceBytes", stdin.length);
            return Optional.of(new PrecreationRefusal(proof));
        } catch (ApiException | java.io.IOException | IllegalArgumentException invalid) {
            return Optional.empty();
        } finally {
            Arrays.fill(stdin, (byte) 0);
        }
    }

    /** Revalidates retained evidence without applying today's controller version to a historical record. */
    static boolean releasedPrecreationRefusal(String action, JsonNode index, JsonNode payload) {
        var proof = payload.path(PRECREATION);
        var fields = new HashSet<String>();
        proof.fieldNames().forEachRemaining(fields::add);
        String stdout = proof.path("stdout").asText();
        boolean existing = proof.path("format").asText().equals(EXISTING_PRECREATION_FORMAT);
        return proof.isObject() && fields.equals(existing
            ? Set.of("format", "action", "stdout", "meaning", "index", "sourceBytes", "exactAction", "executedSourceSha256", "executedSourceBytes")
            : Set.of("format", "action", "stdout", "meaning", "index", "sourceBytes"))
            && proof.path("format").isTextual() && (existing || proof.path("format").asText().equals(PRECREATION_FORMAT))
            && proof.path("action").isTextual() && proof.path("action").asText().equals(action)
            && proof.path("stdout").isTextual() && proof.path("meaning").isTextual()
            && proof.path("meaning").asText().equals(PRECREATION_MEANING) && proof.path("index").equals(index)
            && PRECREATION_SOURCE_BYTES.containsKey(index.path("sourceSha256").asText())
            && proof.path("sourceBytes").isIntegralNumber() && proof.path("sourceBytes").canConvertToInt()
            && proof.path("sourceBytes").intValue() == PRECREATION_SOURCE_BYTES.getOrDefault(index.path("sourceSha256").asText(), -1)
            && (existing ? retainedExistingPrecreation(proof, index) && payload.path("problemCode").asText().equals("IO_FAILURE")
                    && payload.path("problemEvent").asText().equals("SSH_COMMAND_NONZERO")
                : Set.of("PREPARE", "BIND").contains(index.path("phase").asText())
                    && precreationDiagnostic(stdout, index.path("operation").asText(), index.path("sourceSha256").asText()).isPresent())
            && payload.path("state").asText().equals("FAILED") && payload.path("transportOutcome").asText().equals("FAILED")
            && payload.path("confirmationMeaning").asText().equals(PRECREATION_MEANING)
            && payload.path("exitObserved").isBoolean() && payload.path("exitObserved").booleanValue()
            && payload.path("exit").isIntegralNumber() && payload.path("exit").canConvertToInt() && payload.path("exit").intValue() == 65
            && payload.path("truncated").isBoolean() && !payload.path("truncated").booleanValue()
            && payload.path("stdoutCharacters").isIntegralNumber() && payload.path("stdoutCharacters").canConvertToLong() && payload.path("stdoutCharacters").longValue() == stdout.length()
            && payload.path("stderrCharacters").isIntegralNumber() && payload.path("stderrCharacters").canConvertToLong() && payload.path("stderrCharacters").longValue() == 0
            && payload.path("stdoutSha256").asText().equals(DeploymentAgentToolchainPrograms.sha256(stdout))
            && payload.path("stderrSha256").asText().equals(DeploymentAgentToolchainPrograms.sha256(""));
    }

    private static boolean existingPrecreationInvocation(String phase, String operation, DeploymentRemoteLayout layout,
                                                          CommandRequest request, byte[] stdin, String stdout, String sourceSha256) {
        if (!phase.equals("prepare") || !probeSource(sourceSha256) || !stdout.equals(EXISTING_PRECREATION_STDOUT))
            return false;
        String source = DeploymentAgentToolchainPrograms.controller(layout);
        String guarded = DeploymentAgentToolchainPrograms.existingController(layout);
        if (source.indexOf(EXISTING_SELECTION_BOUNDARY) < 0 || source.indexOf(EXISTING_SELECTION_BOUNDARY) != source.lastIndexOf(EXISTING_SELECTION_BOUNDARY)
            || !guarded.equals(source.replace(EXISTING_SELECTION_BOUNDARY, EXISTING_SELECTION_BOUNDARY + EXISTING_PRECREATION_GUARD))
            || !Arrays.equals(stdin, guarded.getBytes(java.nio.charset.StandardCharsets.UTF_8)) || request.outputLimitBytes() != 65536)
            return false;
        var argv = DeploymentAgentToolchainPrograms.arguments(request.script());
        if (argv.size() != 8 || !argv.getFirst().equals("prepare") || !argv.get(6).equals(operation)
            || !argv.get(4).matches("[1-9][0-9]{1,3}")) return false;
        var ecosystem = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.valueOf(argv.get(1));
        int seconds = Integer.parseInt(argv.get(4));
        DeploymentAgentToolchainCommands.requirePreparation(ecosystem, argv.get(2), argv.get(3), seconds, argv.get(5), layout);
        DeploymentAgentToolchainRelease.decode(argv.get(7), ecosystem, argv.get(2), argv.get(3), operation, layout);
        return request.timeout().compareTo(java.time.Duration.ofSeconds(seconds + 15L)) <= 0;
    }

    private static boolean retainedExistingPrecreation(JsonNode proof, JsonNode index) {
        if (!index.path("phase").asText().equals("PREPARE") || !probeSource(index.path("sourceSha256").asText())
            || !proof.path("stdout").asText().equals(EXISTING_PRECREATION_STDOUT)
            || !proof.path("exactAction").isTextual() || !proof.path("executedSourceSha256").isTextual()
            || !proof.path("executedSourceSha256").asText().matches("[0-9a-f]{64}")
            || !proof.path("executedSourceBytes").isIntegralNumber() || !proof.path("executedSourceBytes").canConvertToInt()
            || proof.path("executedSourceBytes").intValue() != PRECREATION_SOURCE_BYTES.get(index.path("sourceSha256").asText()) + EXISTING_PRECREATION_GUARD.length()
            || !DeploymentAgentToolchainPrograms.sha256(proof.path("exactAction").asText()).equals(index.path("requestSha256").asText()))
            return false;
        String[] frame = proof.path("exactAction").asText().split("\n", 7);
        try {
            if (frame.length != 7 || !frame[0].equals("WTL-SSH-COMMAND-1") || !frame[1].startsWith("timeout=")
                || !frame[2].equals("outputLimitBytes=65536") || !frame[3].equals("stdinBytes=" + proof.path("executedSourceBytes").intValue())
                || !frame[4].equals("stdinSha256=" + proof.path("executedSourceSha256").asText()) || !frame[5].equals("script:")) return false;
            var timeout = java.time.Duration.parse(frame[1].substring("timeout=".length()));
            var argv = DeploymentAgentToolchainPrograms.arguments(frame[6]);
            return !timeout.isNegative() && !timeout.isZero() && frame[1].equals("timeout=" + timeout)
                && argv.size() == 8 && argv.getFirst().equals("prepare") && argv.get(6).equals(index.path("operation").asText());
        } catch (java.time.format.DateTimeParseException | IllegalArgumentException invalid) {
            return false;
        }
    }

    private static Optional<JsonNode> precreationDiagnostic(String stdout, String operation, String sourceSha256) {
        String prefix = "TOOLCHAIN_FAILURE=isolation:HOST_SPACE_REQUIRED\nTOOLCHAIN_DIAGNOSTIC=";
        if (!operation.matches("[0-9a-f]{32}") || !stdout.startsWith(prefix) || !stdout.endsWith("\n") || stdout.length() > 2048)
            return Optional.empty();
        String text = stdout.substring(prefix.length(), stdout.length() - 1);
        try {
            var value = JSON.readTree(text);
            var fields = new HashSet<String>();
            if (value == null || !value.isObject()) return Optional.empty();
            value.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("operation", "phase", "stage", "availableBytes", "requiredBytes", "reserveBytes"))
                || !value.path("operation").isTextual() || !value.path("operation").asText().equals(operation)
                || !value.path("phase").isTextual() || !value.path("phase").asText().equals("HOST_SPACE_REQUIRED")
                || !value.path("stage").isTextual() || !value.path("stage").asText().equals("VOLUME"))
                return Optional.empty();
            long reserve = 1024L * 1024 * 1024;
            var canonical = new TreeMap<String, Object>();
            for (String field : fields) {
                if (Set.of("availableBytes", "requiredBytes", "reserveBytes").contains(field)) {
                    if (!value.path(field).isIntegralNumber() || !value.path(field).canConvertToLong())
                        return Optional.empty();
                    canonical.put(field, value.path(field).longValue());
                } else canonical.put(field, value.path(field).textValue());
            }
            if (value.path("reserveBytes").longValue() != reserve || !precreationThreshold(sourceSha256, value.path("requiredBytes").longValue())
                || value.path("availableBytes").longValue() < 0 || value.path("availableBytes").longValue() >= value.path("requiredBytes").longValue()
                || !JSON.writeValueAsString(canonical).equals(text))
                return Optional.empty();
            return Optional.of(value);
        } catch (java.io.IOException invalid) {
            return Optional.empty();
        }
    }

    static OperationResult<CommandResult> retain(OperationResult<CommandResult> result, String phase,
                                                 ServerId server, String identity) {
        if (result.value().isEmpty()) return result;
        var command = result.value().orElseThrow();
        if (result.outcome() == TaskResult.FAILED && command.exitCode().isEmpty() && !command.truncated()
            && command.stdout().isEmpty() && command.stderr().isEmpty()
            && result.problem().filter(problem -> problem.code() == ProblemCode.IO_FAILURE
            && problem.eventCode().equals("SSH_COMMAND_NOT_SENT")
            && "false".equals(problem.context().get("commandOpenRequested"))
            && "false".equals(problem.context().get("channelOpenConfirmed"))
            && "false".equals(problem.context().get("exitObserved"))
            && "false".equals(problem.context().get("outputObserved"))).isPresent())
            return result;
        if (result.outcome() == TaskResult.SUCCEEDED && command.exitCode().equals(Optional.of(0)) && !command.truncated())
            return result;
        var facts = new LinkedHashMap<String, Object>();
        facts.put("phase", phase);
        facts.put("server", server.id());
        facts.put("operation", identity);
        facts.put("transportOutcome", result.outcome().name());
        facts.put("actualExit", command.exitCode().orElse(null));
        facts.put("truncated", command.truncated());
        facts.put("stdoutCharacters", command.stdout().length());
        facts.put("stderrCharacters", command.stderr().length());
        TaskResult outcome = result.outcome();
        if (outcome == TaskResult.SUCCEEDED) outcome = TaskResult.FAILED;
        if (outcome != TaskResult.CANCELLED && (outcome == TaskResult.UNKNOWN || command.truncated() || command.exitCode().isEmpty()))
            outcome = TaskResult.UNKNOWN;
        String[] lines = command.stdout().split("\n", -1);
        if (!command.truncated() && command.stderr().isEmpty() && (lines.length == 2 || lines.length == 3)
            && lines[lines.length - 1].isEmpty()) {
            var match = FAILURE.matcher(lines[0]);
            if (match.matches() && (command.exitCode().equals(Optional.of(65)) || command.exitCode().equals(Optional.of(70)))) {
                facts.put("reportedCategory", match.group(1));
                facts.put("reportedCode", match.group(2));
                boolean lifecycle = LIFECYCLE_PHASES.contains(match.group(2));
                if (outcome != TaskResult.CANCELLED && (match.group(1).equals("unknown") || command.exitCode().equals(Optional.of(70)) || lifecycle))
                    outcome = TaskResult.UNKNOWN;
                if (lines.length == 3 && (!lifecycle || match.group(1).equals("unknown") && command.exitCode().equals(Optional.of(70))))
                    diagnostic(lines[1], identity, match.group(2)).ifPresent(value -> facts.put("reportedDiagnostic", value));
            }
        }
        facts.put("productOutcome", outcome.name());
        facts.put("meaning", "Actual current command evidence; diagnostics are data, not permission or proof that retry is safe. UNKNOWN remains unconfirmed.");
        try {
            var context = new LinkedHashMap<String, String>();
            result.problem().ifPresent(problem -> context.putAll(problem.context()));
            context.put(FIELD, JSON.writeValueAsString(facts));
            return new OperationResult<>(outcome, Optional.empty(), Optional.of(new Problem(
                outcome == TaskResult.UNKNOWN ? ProblemCode.UNKNOWN : ProblemCode.IO_FAILURE, EVENT,
                "Current Agent toolchain execution did not complete", context, Optional.empty())));
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("Current failure evidence cannot be encoded", invalid);
        }
    }

    private static Optional<com.fasterxml.jackson.databind.JsonNode> diagnostic(String line, String identity, String code) {
        String prefix = "TOOLCHAIN_DIAGNOSTIC=";
        if (!line.startsWith(prefix) || line.length() > 2048) return Optional.empty();
        try {
            var value = JSON.readTree(line.substring(prefix.length()));
            if (value == null || !value.isObject() || value.size() != (LIFECYCLE_PHASES.contains(code) && value.has("cause") ? 7 : 6) || !value.path("operation").isTextual()
                || !identity.equals(value.path("operation").asText()) || !value.path("phase").isTextual()
                || !code.equals(value.path("phase").asText()))
                return Optional.empty();
            if (code.equals("UNSUPPORTED_APT_BINARY")) {
                if (!value.path("reason").isTextual() || !Set.of("KEY_SHAPE", "VALUE_SHAPE", "PATH_OVERRIDE", "PATH_GUARD").contains(value.path("reason").asText())
                    || !value.path("field").isTextual() || !Set.of("dpkg", "methods", "solvers", "planners", "apt-key", "gpg", "gpgv", "gzip", "bzip2", "xz", "lz4", "zstd", "lzma", "container", "solver", "planner", "unrecognized").contains(value.path("field").asText())
                    || !value.path("valueBytes").isIntegralNumber() || !value.path("valueBytes").canConvertToInt()
                    || value.path("valueBytes").intValue() < 0 || value.path("valueBytes").intValue() > 1024 * 1024
                    || !value.path("valueSha256").isTextual() || !value.path("valueSha256").asText().matches("[a-f0-9]{64}"))
                    return Optional.empty();
                return Optional.of(value);
            }
            if (code.equals("SYSTEM_COMMAND_FAILED")) {
                if (!value.path("command").isTextual() || !Set.of("fallocate", "findmnt", "losetup", "mkfs.ext4", "mke2fs", "mount", "systemctl", "systemd-run", "umount", "UNCLASSIFIED").contains(value.path("command").asText())
                    || !value.path("exitCode").isIntegralNumber() || !value.path("exitCode").canConvertToInt()
                    || value.path("exitCode").intValue() < -255 || value.path("exitCode").intValue() > 255 || value.path("exitCode").intValue() == 0
                    || !value.path("outputBytes").isIntegralNumber() || !value.path("outputBytes").canConvertToInt()
                    || value.path("outputBytes").intValue() < 0 || value.path("outputBytes").intValue() > 65536
                    || !value.path("outputSha256").isTextual() || !value.path("outputSha256").asText().matches("[a-f0-9]{64}"))
                    return Optional.empty();
                return Optional.of(value);
            }
            if (LIFECYCLE_PHASES.contains(code)) {
                if (!value.path("command").isTextual() || !value.path("command").asText().equals("systemd-run")
                    || !(value.path("exitCode").isNull() || value.path("exitCode").isIntegralNumber() && value.path("exitCode").canConvertToInt()
                    && value.path("exitCode").intValue() >= -255 && value.path("exitCode").intValue() <= 255)
                    || !value.path("outputBytes").isIntegralNumber() || !value.path("outputBytes").canConvertToInt()
                    || value.path("outputBytes").intValue() < 0 || value.path("outputBytes").intValue() > 65536
                    || !value.path("outputSha256").isTextual() || !value.path("outputSha256").asText().matches("[a-f0-9]{64}"))
                    return Optional.empty();
                if (value.has("cause") && !validLifecycleCause(value.path("cause"))) return Optional.empty();
                return Optional.of(value);
            }
            if (code.equals("HOST_SPACE_REQUIRED")) {
                long reserve = 1024L * 1024 * 1024;
                if (!value.path("stage").isTextual() || !Set.of("VOLUME", "PROMOTION").contains(value.path("stage").asText())
                    || !value.path("availableBytes").isIntegralNumber() || !value.path("availableBytes").canConvertToLong()
                    || !value.path("requiredBytes").isIntegralNumber() || !value.path("requiredBytes").canConvertToLong()
                    || !value.path("reserveBytes").isIntegralNumber() || !value.path("reserveBytes").canConvertToLong()
                    || value.path("reserveBytes").longValue() != reserve
                    || value.path("availableBytes").longValue() < 0 || value.path("availableBytes").longValue() >= value.path("requiredBytes").longValue()
                    || value.path("requiredBytes").longValue() < reserve || value.path("requiredBytes").longValue() > 1024L * 1024 * 1024 * 1024
                    || value.path("stage").asText().equals("VOLUME") && !Set.of(INSTALL_REQUIRED_BYTES, PROBE_REQUIRED_BYTES).contains(value.path("requiredBytes").longValue()))
                    return Optional.empty();
                return Optional.of(value);
            }
            if (code.equals("AGENT_TOOLCHAIN_STATE_UNCONFIRMED")) {
                if (!value.path("function").isTextual() || !CONTROLLER_FUNCTIONS.contains(value.path("function").asText())
                    || !value.path("exception").isTextual() || !Set.of("OSError", "ValueError", "KeyError", "StopIteration", "TimeoutExpired").contains(value.path("exception").asText())
                    || !(value.path("errno").isNull() || value.path("errno").isIntegralNumber() && value.path("errno").canConvertToInt()
                    && value.path("errno").intValue() >= 1 && value.path("errno").intValue() <= 4095)
                    || !value.path("line").isIntegralNumber() || !value.path("line").canConvertToInt()
                    || value.path("line").intValue() < 0 || value.path("line").intValue() > 16384
                    || value.path("function").asText().equals("entry") != (value.path("line").intValue() == 0))
                    return Optional.empty();
                return Optional.of(value);
            }
            if (code.equals("UNSUPPORTED_DPKG_CONFIGURATION")) {
                if (!value.path("kind").isTextual() || !Set.of("MAIN", "PART").contains(value.path("kind").asText())
                    || !value.path("line").isIntegralNumber() || !value.path("line").canConvertToInt()
                    || value.path("line").intValue() < 1 || value.path("line").intValue() > 1024 * 1024
                    || !value.path("valueBytes").isIntegralNumber() || !value.path("valueBytes").canConvertToInt()
                    || value.path("valueBytes").intValue() < 1 || value.path("valueBytes").intValue() > 1024 * 1024
                    || !value.path("valueSha256").isTextual() || !value.path("valueSha256").asText().matches("[a-f0-9]{64}"))
                    return Optional.empty();
                return Optional.of(value);
            }
            if (!value.path("phase").asText().equals("OS_CONFIGURATION_OWNER")
                || !Set.of("RESPONSE_STRUCTURE", "PATH_MISMATCH", "MULTIPLE_OWNERS", "OWNER_SYNTAX", "OWNER_ABSENT", "OWNER_NOT_ALLOWED", "OUTPUT_LIMIT").contains(value.path("reason").asText())
                || !value.path("path").isTextual() || !value.path("path").asText().matches("/[!-~]{1,511}")
                || Arrays.asList(value.path("path").asText().substring(1).split("/", -1)).stream().anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))
                || !(value.path("owner").isNull() || value.path("owner").isTextual() && value.path("owner").asText().matches("[a-z0-9][a-z0-9+.-]{0,95}(?::[a-z0-9]{1,32})?"))
                || !value.path("allowedOwners").isArray() || value.path("allowedOwners").isEmpty() || value.path("allowedOwners").size() > 16)
                return Optional.empty();
            var names = new HashSet<String>();
            for (var owner : value.path("allowedOwners"))
                if (!owner.isTextual() || !owner.asText().matches("[a-z0-9+.-]{1,96}") || !names.add(owner.asText()))
                    return Optional.empty();
            return Optional.of(value);
        } catch (java.io.IOException invalid) {
            return Optional.empty();
        }
    }

    private static boolean validLifecycleCause(com.fasterxml.jackson.databind.JsonNode value) {
        if (!value.isObject() || value.size() != 8) return false;
        for (var name : List.of("stage", "category", "code", "exception", "function", "check"))
            if (!value.path(name).isTextual()) return false;
        if (!LIFECYCLE_STAGES.contains(value.path("stage").asText()) || !LIFECYCLE_CATEGORIES.contains(value.path("category").asText())
            || !LIFECYCLE_CODES.contains(value.path("code").asText()) || !LIFECYCLE_EXCEPTIONS.contains(value.path("exception").asText())
            || !CONTROLLER_FUNCTIONS.contains(value.path("function").asText()) || !LIFECYCLE_CHECKS.contains(value.path("check").asText())
            || !(value.path("errno").isNull() || value.path("errno").isIntegralNumber() && value.path("errno").canConvertToInt()
            && value.path("errno").intValue() >= 1 && value.path("errno").intValue() <= 4095)
            || !value.path("line").isIntegralNumber() || !value.path("line").canConvertToInt()
            || (value.path("function").asText().equals("entry") ? value.path("line").intValue() != 0
            : value.path("line").intValue() < 1 || value.path("line").intValue() > 16384)) return false;
        String family = value.path("exception").asText();
        if (family.equals("NONE"))
            return value.path("stage").asText().equals("INCOMPLETE") && value.path("category").asText().equals("none")
                && value.path("code").asText().equals("SETUP_NOT_COMPLETED") && value.path("errno").isNull()
                && value.path("function").asText().equals("entry") && value.path("line").intValue() == 0 && value.path("check").asText().equals("NONE");
        if (value.path("stage").asText().equals("INCOMPLETE") || value.path("category").asText().equals("none")
            || value.path("code").asText().equals("SETUP_NOT_COMPLETED") || !family.equals("OSError") && !value.path("errno").isNull())
            return false;
        if (!family.equals("PreparationFailure") && (!value.path("category").asText().equals("other")
            || !value.path("code").asText().equals("UNCLASSIFIED") || !value.path("check").asText().equals("NONE")))
            return false;
        return value.path("check").asText().equals("NONE") || family.equals("PreparationFailure") && value.path("code").asText().equals("UNIT_IDENTITY");
    }

    /** Projects the attachment only for its dedicated Agent failure event. / 仅为专用 Agent 失败事件投影附件。 */
    public static String observation(Optional<Problem> problem) {
        if (problem.isEmpty() || !EVENT.equals(problem.orElseThrow().eventCode())) return "";
        String value = problem.orElseThrow().context().getOrDefault(FIELD, "");
        return value.length() <= 4096 ? " currentToolchainFailure=" + value : "";
    }

    /** Separates the original worker outcome from successful receipt transport and effect settlement. */
    static OperationResult<CommandResult> tracked(OperationResult<CommandResult> actual,
            DeploymentAgentToolchainTracked.Observation observation, String action, boolean released, String originalPayload) {
        if (released && observation == null && actual.value().isEmpty()
            && DeploymentAgentToolchainTracked.parse(originalPayload).path("state").asText().equals("NOT_SENT")) return actual;
        if (actual.outcome() == TaskResult.CANCELLED) return actual;
        if (observation != null && observation.succeeded() && released) return actual;
        boolean knownFailure = observation != null && observation.settled() && released && observation.executionStatus().equals("FAILED");
        TaskResult outcome = knownFailure ? TaskResult.FAILED : TaskResult.UNKNOWN;
        var facts = JSON.createObjectNode().put("formatVersion", 1).put("productOutcome", outcome.name())
            .put("originalAction", action).put("originalResultSha256", DeploymentAgentToolchainPrograms.sha256(originalPayload))
            .put("transportOutcome", actual.outcome().name()).put("effectsSettled", observation != null && observation.settled() && released);
        if (observation != null) facts.put("requestSha256", observation.recipe().sha256())
            .put("originalExecutionStatus", observation.executionStatus())
            .put("settlementProofSha256", DeploymentAgentToolchainPrograms.sha256(observation.value().toString()))
            .put("failureCode", observation.failureCode());
        return new OperationResult<>(outcome, actual.value(), Optional.of(new Problem(
            knownFailure ? ProblemCode.IO_FAILURE : ProblemCode.UNKNOWN, EVENT,
            "Original controller outcome and physical settlement are retained separately", Map.of(FIELD, facts.toString()), Optional.empty())));
    }

    /** Only the dedicated typed adapter emits this after strict receipt and checkpoint settlement. */
    public static boolean settledTrackedFailure(Optional<Problem> problem) {
        try {
            if (problem.isEmpty() || !problem.orElseThrow().eventCode().equals(EVENT)
                || problem.orElseThrow().code() != ProblemCode.IO_FAILURE) return false;
            var value = JSON.readTree(problem.orElseThrow().context().getOrDefault(FIELD, ""));
            var fields = new HashSet<String>(); value.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("formatVersion", "productOutcome", "originalAction", "originalResultSha256", "transportOutcome", "effectsSettled",
                "requestSha256", "originalExecutionStatus", "settlementProofSha256", "failureCode"))) return false;
            return value.path("formatVersion").isInt() && value.path("formatVersion").intValue() == 1
                && value.path("productOutcome").asText().equals("FAILED") && value.path("effectsSettled").isBoolean() && value.path("effectsSettled").booleanValue()
                && value.path("originalExecutionStatus").asText().equals("FAILED") && value.path("transportOutcome").asText().equals("SUCCEEDED")
                && value.path("originalAction").asText().matches("[a-f0-9]{32}")
                && List.of("originalResultSha256", "requestSha256", "settlementProofSha256").stream()
                    .allMatch(key -> value.path(key).asText().matches("[a-f0-9]{64}"));
        } catch (Exception invalid) { return false; }
    }
}
