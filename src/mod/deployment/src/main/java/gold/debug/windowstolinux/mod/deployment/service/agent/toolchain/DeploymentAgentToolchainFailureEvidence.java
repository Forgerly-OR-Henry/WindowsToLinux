package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.util.*;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.*;

/** Preserves only this dispatched Agent program's bounded failure evidence. / 仅保留本次已发送 Agent 程序的有界失败依据。 */
public final class DeploymentAgentToolchainFailureEvidence {
    private static final String EVENT = "DEPLOYMENT_AGENT_TOOLCHAIN_EXECUTION_FAILED";

    private static final String FIELD = "agentToolchainFailure";

    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private static final Pattern FAILURE = Pattern.compile("TOOLCHAIN_FAILURE=([a-z_]{1,32}):([A-Z_]{1,96})");

    private DeploymentAgentToolchainFailureEvidence() {
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
                if (outcome != TaskResult.CANCELLED && (match.group(1).equals("unknown") || command.exitCode().equals(Optional.of(70))))
                    outcome = TaskResult.UNKNOWN;
                if (lines.length == 3)
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
            if (value == null || !value.isObject() || value.size() != 6 || !value.path("operation").isTextual()
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
            if (code.equals("HOST_SPACE_REQUIRED")) {
                long reserve = 1024L * 1024 * 1024;
                if (!value.path("stage").isTextual() || !Set.of("VOLUME", "PROMOTION").contains(value.path("stage").asText())
                    || !value.path("availableBytes").isIntegralNumber() || !value.path("availableBytes").canConvertToLong()
                    || !value.path("requiredBytes").isIntegralNumber() || !value.path("requiredBytes").canConvertToLong()
                    || !value.path("reserveBytes").isIntegralNumber() || !value.path("reserveBytes").canConvertToLong()
                    || value.path("reserveBytes").longValue() != reserve
                    || value.path("availableBytes").longValue() < 0 || value.path("availableBytes").longValue() >= value.path("requiredBytes").longValue()
                    || value.path("requiredBytes").longValue() < reserve || value.path("requiredBytes").longValue() > 1024L * 1024 * 1024 * 1024
                    || value.path("stage").asText().equals("VOLUME") && value.path("requiredBytes").longValue() != 9 * reserve)
                    return Optional.empty();
                return Optional.of(value);
            }
            if (code.equals("AGENT_TOOLCHAIN_STATE_UNCONFIRMED")) {
                if (!value.path("function").isTextual() || !Set.of("agent_main", "agent_prepare", "agent_create", "agent_prepare_selected",
                    "agent_execute", "agent_bind", "agent_setup", "agent_worker", "agent_freeze", "agent_release_source", "agent_probe_records",
                    "agent_promote", "agent_detach", "agent_cleanup_before_start", "agent_cleanup_placeholder", "agent_os", "agent_tool",
                    "agent_regular", "agent_write", "agent_record", "agent_identity", "agent_scan", "agent_copy_tree", "agent_require_space", "entry").contains(value.path("function").asText())
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

    /** Projects the attachment only for its dedicated Agent failure event. / 仅为专用 Agent 失败事件投影附件。 */
    public static String observation(Optional<Problem> problem) {
        if (problem.isEmpty() || !EVENT.equals(problem.orElseThrow().eventCode())) return "";
        String value = problem.orElseThrow().context().getOrDefault(FIELD, "");
        return value.length() <= 4096 ? " currentToolchainFailure=" + value : "";
    }
}
