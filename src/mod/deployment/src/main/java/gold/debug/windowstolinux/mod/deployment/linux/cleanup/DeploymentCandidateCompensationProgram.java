package gold.debug.windowstolinux.mod.deployment.linux.cleanup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.DeploymentServerIdentity;

/** Exact independent candidate compensation protocol; never a replay of the helper cleanup request. */
public final class DeploymentCandidateCompensationProgram {
    public static final String LAUNCHER = "exec /usr/bin/python3 -I -";
    public static final String OPERATION_PREFIX = "deployment.candidate.compensation.";
    public static final int OUTPUT_LIMIT = 8192;
    private static final String RESOURCE = "/gold/debug/windowstolinux/mod/deployment/linux/cleanup/candidate_compensation.py";
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> BINDING_FIELDS = Set.of("formatVersion", "taskId", "operationId", "applicationId", "unitId",
        "candidateId", "planSha256", "ownerManifestSha256", "archiveSha256", "layout", "helperSha256");
    private static final Set<String> RESPONSE_FIELDS = Set.of("formatVersion", "mode", "taskId", "operationId", "bindingSha256",
        "status", "reason", "candidatePresent", "quiescent", "observationSha256", "receiptSha256");
    private static final Set<String> EXCLUSION_FIELDS = Set.of("role", "pid", "parentPid", "startTicks", "bootIdSha256",
        "executableSha256", "executableDevice", "executableInode", "scriptSha256", "unitSha256", "argvSha256",
        "unitInvocationId", "controlGroupSha256", "startedAtEpochMillis");

    private DeploymentCandidateCompensationProgram() { }

    public enum Mode {
        OBSERVE, EXECUTE, RECEIPT;
        public String wire() { return name().toLowerCase(Locale.ROOT); }
        public String operation() { return OPERATION_PREFIX + wire() + ".v1"; }
    }

    public record Binding(UUID taskId, UUID operationId, String applicationId, String unitId, String candidateId,
                          String planSha256, String ownerManifestSha256, String archiveSha256,
                          DeploymentRemoteLayout layout, String helperSha256) {
        public Binding {
            Objects.requireNonNull(taskId); Objects.requireNonNull(operationId); Objects.requireNonNull(layout);
            if (!DeploymentServerIdentity.requireIdentifier(applicationId, "application").equals(applicationId)
                || !DeploymentServerIdentity.requireIdentifier(unitId, "unit").equals(unitId)
                || !unitId.startsWith(applicationId + "-"))
                throw new IllegalArgumentException("Compensation application or component differs");
            for (String value : List.of(planSha256, ownerManifestSha256, archiveSha256, helperSha256))
                if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Compensation digest differs");
            if (!candidateId.equals(unitId + "-" + archiveSha256.substring(0, 16))
                || !helperSha256.equals(DeploymentManagedHelperBundle.helperSha256(layout)))
                throw new IllegalArgumentException("Compensation candidate or pinned helper differs");
            layout.candidateRoot(candidateId);
        }

        public ObjectNode json() {
            var value = JSON.createObjectNode().put("formatVersion", 1).put("taskId", taskId.toString())
                .put("operationId", operationId.toString()).put("applicationId", applicationId).put("unitId", unitId)
                .put("candidateId", candidateId).put("planSha256", planSha256).put("ownerManifestSha256", ownerManifestSha256)
                .put("archiveSha256", archiveSha256).put("helperSha256", helperSha256);
            try { value.set("layout", JSON.readTree(layout.canonical())); }
            catch (IOException impossible) { throw new IllegalStateException(impossible); }
            return value;
        }

        public String sha256() { return DeploymentModelCommandIdentity.digest(canonical(json())); }
        public String receiptRoot() { return layout.paths().stateRoot() + "/candidate-compensations/" + taskId + "/" + operationId; }
        public String lockPath() { return layout.paths().stateRoot() + "/candidate-compensation-locks/" + candidateId + ".lock"; }
        public String claimRoot() { return layout.paths().stateRoot() + "/candidate-compensation-claims"; }
        public String claimTaskRoot() { return claimRoot() + "/" + taskId; }
        public String claimPath() { return claimTaskRoot() + "/" + candidateId + ".json"; }
    }

    public record Request(Mode mode, Binding binding, String expectedObservationSha256, long originalExecutionCreatedAtEpochMillis) {
        public Request {
            Objects.requireNonNull(mode); Objects.requireNonNull(binding); Objects.requireNonNull(expectedObservationSha256);
            if (mode == Mode.EXECUTE ? !expectedObservationSha256.matches("[0-9a-f]{64}") : !expectedObservationSha256.isEmpty())
                throw new IllegalArgumentException("Compensation observation binding differs");
            if (originalExecutionCreatedAtEpochMillis <= 0) throw new IllegalArgumentException("Original execution time differs");
        }
        public String json() {
            var node = JSON.createObjectNode().put("mode", mode.wire()).put("expectedObservationSha256", expectedObservationSha256);
            node.put("originalExecutionCreatedAtEpochMillis", originalExecutionCreatedAtEpochMillis);
            node.set("binding", binding.json());
            return canonical(node);
        }
        public Duration timeout() { return Duration.ofSeconds(mode == Mode.EXECUTE ? 120 : 45); }
    }

    public record Observation(String status, String reason, boolean candidatePresent, boolean quiescent,
                              String observationSha256, String receiptSha256, String canonicalResponse,
                              JsonNode systemProcessExclusion) {
        public boolean ready() { return status.equals("OBSERVED") && candidatePresent && quiescent && !observationSha256.isEmpty(); }
        public boolean completed() { return status.equals("SUCCEEDED") && !candidatePresent && quiescent && !receiptSha256.isEmpty(); }
    }

    public static CommandRequest command(Request request) {
        return new CommandRequest(LAUNCHER, stdin(request), request.timeout(), OUTPUT_LIMIT);
    }

    public static byte[] stdin(Request request) {
        String encoded = Base64.getEncoder().encodeToString(request.json().getBytes(StandardCharsets.UTF_8));
        return (body() + "\nmain(json.loads(base64.b64decode('" + encoded + "')))\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Reconstructs the entire executable input, launcher, limits and exact declared resources. */
    public static Request verify(ActionIntent intent, CommandRequest command, DeploymentRemoteLayout layout) {
        byte[] input = command.stdin();
        try {
            String value = new String(input, StandardCharsets.UTF_8);
            String prefix = body() + "\nmain(json.loads(base64.b64decode('", suffix = "')))\n";
            if (!value.startsWith(prefix) || !value.endsWith(suffix) || value.length() > prefix.length() + 16384)
                throw new IllegalArgumentException("Compensation executable input differs");
            String encoded = value.substring(prefix.length(), value.length() - suffix.length());
            if (!encoded.matches("[A-Za-z0-9+/]+={0,2}")) throw new IllegalArgumentException("Compensation input encoding differs");
            var request = decodeRequest(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            if (!request.binding().layout().equals(layout) || intent.serverIds().size() != 1)
                throw new IllegalArgumentException("Compensation layout or server differs");
            try (var expected = command(request)) {
                if (!Arrays.equals(input, expected.stdin()) || !command.script().equals(expected.script())
                    || !command.timeout().equals(expected.timeout()) || command.outputLimitBytes() != expected.outputLimitBytes()
                    || !intent.equals(intent(new ServerId(intent.serverIds().getFirst()), request, expected)))
                    throw new IllegalArgumentException("Compensation complete command contract differs");
            }
            return request;
        } finally { Arrays.fill(input, (byte) 0); }
    }

    public static ActionIntent intent(ServerId server, Request request, CommandRequest command) {
        return RemoteActions.command(server, request.binding().planSha256(), command).withSemantics(new ActionSemantics(
            request.mode().operation(), targets(server, request)));
    }

    public static Set<ActionTarget> targets(ServerId server, Request request) {
        var binding = request.binding(); var layout = binding.layout(); var targets = new HashSet<ActionTarget>();
        targets.add(new ActionTarget(server.id(), "task", binding.taskId().toString()));
        targets.add(new ActionTarget(server.id(), "deployment-candidate", layout.candidateRoot(binding.candidateId())));
        targets.add(new ActionTarget(server.id(), "deployment-helper", layout.helperPath()));
        targets.add(new ActionTarget(server.id(), "systemd-unit", "windowstolinux-build-" + binding.candidateId() + ".service"));
        targets.add(new ActionTarget(server.id(), "candidate-volume", layout.candidateRoot(binding.candidateId()) + "/.workspace.ext4"));
        for (String path : List.of("/proc", "/sys/fs/cgroup", "/sys/block", "/etc/systemd/system/windowstolinux-" + binding.unitId() + ".service",
            "/usr/share/unattended-upgrades/unattended-upgrade-shutdown", "/usr/lib/systemd/system/unattended-upgrades.service",
            "/lib/systemd/system/unattended-upgrades.service",
            layout.applicationRoot(binding.unitId()), layout.snapshotRoot(binding.unitId(), binding.taskId()),
            layout.backupsRoot(), layout.applicationLockRoot(binding.unitId()), layout.paths().dataRoot(),
            layout.paths().configurationRoot())) targets.add(new ActionTarget(server.id(), "filesystem-read", path));
        String kind = request.mode() == Mode.EXECUTE ? "filesystem-write" : "filesystem-read";
        targets.add(new ActionTarget(server.id(), kind, binding.receiptRoot()));
        targets.add(new ActionTarget(server.id(), kind, binding.lockPath()));
        for (String path : List.of(binding.claimRoot(), binding.claimTaskRoot(), binding.claimPath()))
            targets.add(new ActionTarget(server.id(), kind, path));
        if (request.mode() == Mode.EXECUTE) {
            targets.add(new ActionTarget(server.id(), kind, layout.paths().stateRoot() + "/candidate-compensations"));
            targets.add(new ActionTarget(server.id(), kind, layout.paths().stateRoot() + "/candidate-compensations/" + binding.taskId()));
            targets.add(new ActionTarget(server.id(), kind, layout.paths().stateRoot() + "/candidate-compensation-locks"));
            targets.add(new ActionTarget(server.id(), "filesystem-write", layout.candidateRoot(binding.candidateId())));
        }
        return Set.copyOf(targets);
    }

    public static Observation parse(Request request, String stdout) {
        try {
            if (stdout == null || stdout.getBytes(StandardCharsets.UTF_8).length > OUTPUT_LIMIT
                || !stdout.endsWith("\n") || stdout.substring(0, stdout.length() - 1).contains("\n"))
                throw new IllegalArgumentException("Compensation response is not one bounded line");
            var node = JSON.readTree(stdout);
            var expectedFields = new HashSet<>(RESPONSE_FIELDS); expectedFields.add("systemProcessExclusion");
            fields(node, expectedFields);
            if (!node.path("formatVersion").isInt() || node.path("formatVersion").asInt() != 2
                || !text(node, "mode").equals(request.mode().wire())
                || !text(node, "taskId").equals(request.binding().taskId().toString())
                || !text(node, "operationId").equals(request.binding().operationId().toString())
                || !text(node, "bindingSha256").equals(request.binding().sha256())
                || !node.path("candidatePresent").isBoolean() || !node.path("quiescent").isBoolean())
                throw new IllegalArgumentException("Compensation response binding differs");
            String status = text(node, "status"), reason = text(node, "reason");
            if (!Set.of("OBSERVED", "REFUSED", "ABSENT", "SUCCEEDED", "UNKNOWN", "NOT_DISPATCHED").contains(status)
                || !reason.matches("[a-z0-9][a-z0-9-]{0,79}")) throw new IllegalArgumentException("Compensation status differs");
            for (String field : List.of("observationSha256", "receiptSha256"))
                if (!text(node, field).matches("(?:[0-9a-f]{64})?")) throw new IllegalArgumentException("Compensation response digest differs");
            verifyExclusion(node.path("systemProcessExclusion"), request.originalExecutionCreatedAtEpochMillis());
            var result = new Observation(status, reason, node.path("candidatePresent").booleanValue(), node.path("quiescent").booleanValue(),
                text(node, "observationSha256"), text(node, "receiptSha256"), canonical(node), node.path("systemProcessExclusion").deepCopy());
            if (status.equals("OBSERVED") && (request.mode() != Mode.OBSERVE || !result.ready())
                || status.equals("SUCCEEDED") && (request.mode() == Mode.OBSERVE || !result.completed())
                || status.equals("ABSENT") && result.candidatePresent())
                throw new IllegalArgumentException("Compensation response postconditions differ");
            return result;
        } catch (IOException invalid) { throw new IllegalArgumentException("Compensation response is invalid", invalid); }
    }

    public static Request decodeRequest(String value) {
        try {
            var node = JSON.readTree(value); fields(node, Set.of("mode", "binding", "expectedObservationSha256", "originalExecutionCreatedAtEpochMillis"));
            var request = new Request(Mode.valueOf(text(node, "mode").toUpperCase(Locale.ROOT)), decodeBinding(node.path("binding")),
                text(node, "expectedObservationSha256"), integer(node, "originalExecutionCreatedAtEpochMillis"));
            if (!value.equals(request.json())) throw new IllegalArgumentException("Compensation request is not canonical");
            return request;
        } catch (IOException invalid) { throw new IllegalArgumentException("Compensation request is invalid", invalid); }
    }

    /** Only this immutable legacy observation refusal can justify the single deterministic successor. */
    public static void verifyLegacyObservationRefusal(Binding binding, String response) {
        try {
            if (response.getBytes(StandardCharsets.UTF_8).length > OUTPUT_LIMIT || response.contains("\n") || response.contains("\r"))
                throw new IllegalArgumentException("Legacy compensation response differs");
            var node = JSON.readTree(response); fields(node, RESPONSE_FIELDS);
            if (!node.path("formatVersion").isInt() || node.path("formatVersion").intValue() != 1
                || !text(node, "mode").equals("observe") || !text(node, "taskId").equals(binding.taskId().toString())
                || !text(node, "operationId").equals(binding.operationId().toString())
                || !text(node, "bindingSha256").equals(binding.sha256()) || !text(node, "status").equals("REFUSED")
                || !text(node, "reason").equals("other-root-execution-chain")
                || !node.path("candidatePresent").isBoolean() || !node.path("quiescent").isBoolean()
                || node.path("quiescent").booleanValue() || !text(node, "observationSha256").isEmpty()
                || !text(node, "receiptSha256").isEmpty() || !response.equals(canonical(node)))
                throw new IllegalArgumentException("Legacy compensation refusal is outside successor scope");
        } catch (IOException invalid) { throw new IllegalArgumentException("Legacy compensation refusal is invalid", invalid); }
    }

    private static void verifyExclusion(JsonNode proof, long originalCreatedAt) {
        if (!proof.isObject()) throw new IllegalArgumentException("System process exclusion proof is missing");
        if (proof.isEmpty()) return;
        fields(proof, EXCLUSION_FIELDS);
        if (!text(proof, "role").equals("unattended-upgrade-shutdown-v1") || integer(proof, "pid") <= 0 || integer(proof, "pid") > Integer.MAX_VALUE
            || integer(proof, "parentPid") != 1 || integer(proof, "startTicks") <= 0
            || integer(proof, "executableDevice") < 0 || integer(proof, "executableInode") <= 0
            || integer(proof, "startedAtEpochMillis") <= 0 || integer(proof, "startedAtEpochMillis") >= originalCreatedAt
            || !text(proof, "unitInvocationId").matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("System process exclusion identity differs");
        for (String field : List.of("bootIdSha256", "executableSha256", "scriptSha256", "unitSha256", "argvSha256", "controlGroupSha256"))
            if (!text(proof, field).matches("[0-9a-f]{64}")) throw new IllegalArgumentException("System process exclusion digest differs");
    }

    private static long integer(JsonNode node, String field) {
        if (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToLong())
            throw new IllegalArgumentException("Compensation integer differs");
        return node.path(field).longValue();
    }

    public static Binding decodeBinding(JsonNode node) {
        fields(node, BINDING_FIELDS);
        if (!node.path("formatVersion").isInt() || node.path("formatVersion").asInt() != 1)
            throw new IllegalArgumentException("Compensation binding version differs");
        var layout = node.path("layout"); fields(layout, Set.of("formatVersion", "remote"));
        if (!layout.path("formatVersion").isInt() || layout.path("formatVersion").asInt() != 1)
            throw new IllegalArgumentException("Compensation layout version differs");
        var remote = layout.path("remote"); fields(remote, Set.of("helperDirectory", "applicationsRoot", "stateRoot", "configurationRoot", "dataRoot"));
        return new Binding(uuid(node, "taskId"), uuid(node, "operationId"), text(node, "applicationId"), text(node, "unitId"),
            text(node, "candidateId"), text(node, "planSha256"), text(node, "ownerManifestSha256"), text(node, "archiveSha256"),
            new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(text(remote, "helperDirectory"), text(remote, "applicationsRoot"),
                text(remote, "stateRoot"), text(remote, "configurationRoot"), text(remote, "dataRoot"))), text(node, "helperSha256"));
    }

    public static String canonical(JsonNode node) {
        if (node.isObject()) { var sorted = JSON.createObjectNode(); var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            for (String name : names) sorted.set(name, ordered(node.get(name))); return sorted.toString(); }
        return ordered(node).toString();
    }
    private static JsonNode ordered(JsonNode node) {
        if (node.isObject()) { var value = JSON.createObjectNode(); var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            for (String name : names) value.set(name, ordered(node.get(name))); return value; }
        if (node.isArray()) { var value = JSON.createArrayNode(); node.forEach(item -> value.add(ordered(item))); return value; }
        return node;
    }
    private static UUID uuid(JsonNode node, String field) { String text = text(node, field); UUID id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw new IllegalArgumentException("Compensation UUID is not canonical"); return id; }
    private static String text(JsonNode node, String field) { if (!node.path(field).isTextual()) throw new IllegalArgumentException("Missing compensation text"); return node.path(field).textValue(); }
    private static void fields(JsonNode node, Set<String> expected) { if (node == null || !node.isObject()) throw new IllegalArgumentException("Compensation object is missing");
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException("Compensation fields differ"); }
    public static String bodySha256() { return DeploymentModelCommandIdentity.digest(body()); }

    private static String body() {
        try (var stream = DeploymentCandidateCompensationProgram.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Fixed compensation resource is missing");
            byte[] bytes = stream.readNBytes(262145); if (bytes.length > 262144) throw new IllegalStateException("Compensation resource exceeds bound");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException invalid) { throw new IllegalStateException("Fixed compensation resource is unavailable", invalid); }
    }
}
