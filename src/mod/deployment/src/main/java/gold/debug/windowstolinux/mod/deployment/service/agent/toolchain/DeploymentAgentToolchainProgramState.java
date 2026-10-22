package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.nio.charset.StandardCharsets;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;

/** Agent-only original action evidence; released records remain readable and unknown records are never replaced. */
public final class DeploymentAgentToolchainProgramState {
    public static final String FIELD = "agentControllerProgramState";

    private static final String INDEX = "CONTROLLER_PROGRAM_ACTION";

    private static final String RESULT = "CONTROLLER_PROGRAM_RESULT";

    private static final String DISPATCH = "CONTROLLER_PROGRAM_DISPATCH";
    private static final String RECOVERY = "CONTROLLER_PROGRAM_RECOVERY";
    private static final String SETTLED = "CONTROLLER_PROGRAM_SETTLED";

    private static final String PREFIX = "agent-controller-program.";

    private static final Set<String> TRANSPORT_EVENTS = Set.of("SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT", "SSH_COMMAND_CANCELLED",
        "SSH_COMMAND_EXIT_UNKNOWN", "SSH_COMMAND_NONZERO", "SSH_COMMAND_NOT_SENT", "SSH_COMMAND_TIMEOUT", "SSH_COMMAND_TRANSPORT",
        "DEPLOYMENT_BUILD_BUDGET_EXHAUSTED", "DEPLOYMENT_BUILD_DISPATCH_UNCONFIRMED");

    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    @FunctionalInterface
    interface Recorder {
        void event(String type, String action, String detail) throws Exception;
    }

    private final TaskService tasks;

    private final TaskContext task;

    private final ServerId server;

    private final DeploymentRemoteLayout layout;

    private final Recorder recorder;

    private final java.util.function.Supplier<List<DeploymentAgentActionLog.Event>> events;

    public DeploymentAgentToolchainProgramState(ModContext context, TaskContext task, ServerId server, DeploymentRemoteLayout layout) {
        this(context.tasks(), task, server, layout, (type, action, detail) -> new DeploymentAgentActionLog(context.db()).event(task, type, action, detail),
            () -> {
                try {
                    return new DeploymentAgentActionLog(context.db()).events(task);
                } catch (Exception failure) {
                    throw new IllegalStateException("Original controller action index unavailable", failure);
                }
            });
    }

    DeploymentAgentToolchainProgramState(TaskService tasks, TaskContext task, ServerId server, DeploymentRemoteLayout layout,
                                         Recorder recorder, java.util.function.Supplier<List<DeploymentAgentActionLog.Event>> events) {
        this.tasks = Objects.requireNonNull(tasks);
        this.task = Objects.requireNonNull(task);
        this.server = Objects.requireNonNull(server);
        this.layout = Objects.requireNonNull(layout);
        this.recorder = Objects.requireNonNull(recorder);
        this.events = Objects.requireNonNull(events);
    }

    public String taskId() {
        return task.id().toString();
    }

    record Action(String id, TaskCheckpoint checkpoint) {
    }

    Action begin(String phase, String operation, String exactAction, JsonNode installation) throws ApiException {
        return begin(phase, operation, exactAction, installation, null, null);
    }

    Action begin(String phase, String operation, String exactAction, JsonNode installation,
                 DeploymentAgentToolchainTracked.Recipe recipe, JsonNode storeFrame) throws ApiException {
        try {
            if (!Set.of("READINESS", "INSTALL", "PREPARE", "BIND").contains(phase) || !operation.matches("[0-9a-f]{32}"))
                throw new IllegalArgumentException("Original controller operation required");
            if (hasUnconfirmed(tasks, task, server.id(), layout, events.get()))
                throw new IllegalStateException("Original controller action remains unconfirmed");
            if (recipe != null && events.get().stream().filter(event -> event.type().equals(INDEX))
                .map(event -> DeploymentAgentToolchainTracked.parse(event.detail()))
                .anyMatch(index -> index.path("operation").asText().equals(operation)))
                throw new IllegalArgumentException("Original controller operation identity cannot be reused");
            String id = UUID.randomUUID().toString().replace("-", "");
            JsonNode recipeRef = recipe == null ? null : blob(id, "RECIPE", recipe.bytes());
            var index = JSON.createObjectNode().put("key", PREFIX + id).put("task", taskId()).put("server", server.id())
                .put("layoutSha256", layout.digest()).put("phase", phase).put("operation", operation)
                .put("version", DeploymentAgentToolchainProgramStore.version(layout)).put("path", DeploymentAgentToolchainProgramStore.path(layout))
                .put("sourceSha256", DeploymentAgentToolchainProgramStore.sourceSha256(layout))
                .put("requestSha256", DeploymentAgentToolchainPrograms.sha256(exactAction));
            recorder.event(INDEX, id, JSON.writeValueAsString(index));
            var body = JSON.createObjectNode().put("format", "WTL_AGENT_CONTROLLER_ACTION_1").put("state", "INTENT");
            body.set("index", index);
            body.set("installation", installation == null ? JSON.createObjectNode() : installation.deepCopy());
            if (recipeRef != null) body.set("trackedRequestRef", recipeRef);
            if (storeFrame != null) body.set("storeFrame", storeFrame.deepCopy());
            return new Action(id, tasks.beginCheckpoint(task, PREFIX + id, bounded(body)));
        } catch (ApiException failure) {
            throw failure;
        } catch (Exception failure) {
            throw unconfirmed();
        }
    }

    void dispatched(Action action) throws ApiException {
        try {
            if (events.get().stream().anyMatch(event -> event.type().equals(DISPATCH) && event.action().equals(action.id())))
                throw new IllegalArgumentException("Original controller action cannot be dispatched twice");
            recorder.event(DISPATCH, action.id(), "{\"formatVersion\":1}");
        } catch (ApiException failure) { throw failure; }
        catch (Exception failure) { throw unconfirmed(); }
    }

    void transport(Action action, OperationResult<CommandResult> result) throws ApiException {
        try {
            var value = JSON.createObjectNode().put("formatVersion", 1).put("outcome", result.outcome().name());
            result.problem().ifPresent(problem -> problemEvidence(value, problem));
            result.value().ifPresent(command -> {
                channelEvidence(value, "stdout", command.stdout()); channelEvidence(value, "stderr", command.stderr());
                value.put("truncated", command.truncated());
                command.exitCode().ifPresentOrElse(exit -> value.put("exit", exit), () -> value.putNull("exit"));
            });
            if (whitelistedReceipt(action.id(), result)) value.put("stdout", result.value().orElseThrow().stdout());
            blob(action.id(), "TRANSPORT", value.toString().getBytes(StandardCharsets.UTF_8));
        } catch (ApiException failure) { throw failure; }
        catch (Exception failure) { throw unconfirmed(); }
    }

    private static void problemEvidence(ObjectNode value, Problem problem) {
        value.put("problemCode", problem.code().name());
        if (TRANSPORT_EVENTS.contains(problem.eventCode())) value.put("problemEvent", problem.eventCode());
        else value.put("problemEventSha256", DeploymentAgentToolchainPrograms.sha256(problem.eventCode()));
    }

    private static void channelEvidence(ObjectNode value, String channel, String text) {
        value.put(channel + "Sha256", DeploymentAgentToolchainPrograms.sha256(text))
            .put(channel + "Bytes", text.getBytes(StandardCharsets.UTF_8).length).put(channel + "Characters", text.length());
    }

    private boolean whitelistedReceipt(String action, OperationResult<CommandResult> result) {
        if (!DeploymentAgentToolchainProgramStore.successful(result) || !result.value().orElseThrow().stderr().isEmpty()
            || result.value().orElseThrow().stdout().getBytes(StandardCharsets.UTF_8).length > 32768) return false;
        try {
            var history = events.get(); String original = action, verb = null;
            for (var event : history) if (event.type().equals(RECOVERY)) {
                var value = DeploymentAgentToolchainTracked.parse(event.detail());
                if (value.path("recoveryId").asText().equals(action)) { original = event.action(); verb = value.path("verb").asText(); }
            }
            var payload = DeploymentAgentToolchainTracked.parse(tasks.findCheckpoint(task, PREFIX + original).orElseThrow().snapshot().payload());
            if (payload.has("trackedRequestRef")) {
                var recipe = recipe(history, original, payload);
                DeploymentAgentToolchainTracked.receipt(result, recipe, verb == null ? recipe.phase().toLowerCase(Locale.ROOT) + "-tracked" : verb);
            } else {
                validateStoreFrame(payload); var frame = payload.path("storeFrame");
                DeploymentAgentToolchainProgramStore.receipt(result, frame.path("task").asText(), frame.path("operation").asText(),
                    DeploymentRemoteLayout.decode(frame.path("layout").asText()), verb == null && frame.path("phase").asText().equals("install"));
            }
            return true;
        } catch (Exception invalid) { return false; }
    }

    private ObjectNode blob(String id, String kind, byte[] bytes) throws Exception {
        if (bytes.length > 1048576) throw new IllegalArgumentException("Controller evidence exceeds bound");
        String encoded = Base64.getEncoder().encodeToString(bytes);
        int count = (encoded.length() + 2999) / 3000;
        for (int part = 0; part < count; part++) recorder.event("CONTROLLER_BLOB_PART", id,
            JSON.createObjectNode().put("formatVersion", 1).put("kind", kind).put("part", part)
                .put("base64", encoded.substring(part * 3000, Math.min(encoded.length(), (part + 1) * 3000))).toString());
        var ref = JSON.createObjectNode().put("formatVersion", 1).put("kind", kind).put("parts", count)
            .put("bytes", bytes.length).put("sha256", DeploymentAgentToolchainTracked.hash(bytes));
        recorder.event("CONTROLLER_BLOB_END", id, ref.toString());
        if (!Arrays.equals(bytes, readBlob(events.get(), id, ref))) throw new IllegalArgumentException("Controller evidence readback changed");
        return ref;
    }

    static byte[] readBlob(List<DeploymentAgentActionLog.Event> events, String id, JsonNode ref) throws Exception {
        DeploymentAgentToolchainTracked.fields(ref, Set.of("formatVersion", "kind", "parts", "bytes", "sha256"));
        if (!ref.path("formatVersion").isInt() || ref.path("formatVersion").intValue() != 1
            || !ref.path("parts").isInt() || !ref.path("bytes").isInt() || ref.path("bytes").intValue() < 1
            || ref.path("bytes").intValue() > 1048576 || ref.path("parts").intValue() < 1 || ref.path("parts").intValue() > 467)
            throw new IllegalArgumentException("Invalid retained blob reference");
        String kind = ref.path("kind").textValue();
        var ends = events.stream().filter(event -> event.type().equals("CONTROLLER_BLOB_END") && event.action().equals(id))
            .filter(event -> DeploymentAgentToolchainTracked.parse(event.detail()).path("kind").asText().equals(kind)).toList();
        if (ends.size() != 1 || !JSON.readTree(ends.getFirst().detail()).equals(ref)) throw new IllegalArgumentException("Incomplete controller blob");
        var parts = events.stream().filter(event -> event.type().equals("CONTROLLER_BLOB_PART") && event.action().equals(id))
            .filter(event -> DeploymentAgentToolchainTracked.parse(event.detail()).path("kind").asText().equals(kind)).toList();
        if (parts.size() != ref.path("parts").intValue() || parts.isEmpty()) throw new IllegalArgumentException("Missing controller blob parts");
        StringBuilder encoded = new StringBuilder();
        long previous = 0;
        for (int part = 0; part < parts.size(); part++) {
            var event = parts.get(part); var value = JSON.readTree(event.detail());
            DeploymentAgentToolchainTracked.fields(value, Set.of("formatVersion", "kind", "part", "base64"));
            if (!value.path("formatVersion").isInt() || value.path("formatVersion").intValue() != 1 || !value.path("part").isInt()
                || value.path("part").intValue() != part || event.id() <= previous || event.id() >= ends.getFirst().id()
                || !value.path("base64").isTextual() || value.path("base64").textValue().isEmpty()
                || value.path("base64").textValue().length() > 3000) throw new IllegalArgumentException("Invalid blob ordering");
            encoded.append(value.path("base64").textValue()); previous = event.id();
        }
        byte[] bytes = Base64.getDecoder().decode(encoded.toString());
        if (bytes.length != ref.path("bytes").intValue() || !Base64.getEncoder().encodeToString(bytes).equals(encoded.toString())
            || !DeploymentAgentToolchainTracked.hash(bytes).equals(ref.path("sha256").asText())) throw new IllegalArgumentException("Controller blob digest differs");
        return bytes;
    }

    public static DeploymentAgentToolchainTracked.Recipe recipe(List<DeploymentAgentActionLog.Event> events, String action, JsonNode payload) throws Exception {
        if (!payload.has("trackedRequestRef") || !payload.path("trackedRequestRef").path("kind").asText().equals("RECIPE"))
            throw new IllegalArgumentException("Legacy controller proof cannot be reconstructed");
        return DeploymentAgentToolchainTracked.decode(readBlob(events, action, payload.path("trackedRequestRef")));
    }

    private static long blobEndBefore(List<DeploymentAgentActionLog.Event> events, String action, JsonNode ref, long before) {
        var ends = events.stream().filter(event -> event.type().equals("CONTROLLER_BLOB_END") && event.action().equals(action))
            .filter(event -> DeploymentAgentToolchainTracked.parse(event.detail()).equals(ref)).toList();
        if (ends.size() != 1 || ends.getFirst().id() >= before) throw new IllegalArgumentException("Controller proof is not causally durable");
        return ends.getFirst().id();
    }

    private static boolean completeTransport(JsonNode actual) {
        return actual.path("outcome").asText().equals("SUCCEEDED") && actual.path("exit").isInt() && actual.path("exit").intValue() == 0
            && actual.path("truncated").isBoolean() && !actual.path("truncated").booleanValue()
            && actual.path("stdout").isTextual() && actual.path("stderrBytes").asInt(-1) == 0 && actual.path("stderrCharacters").asInt(-1) == 0
            && actual.path("stderrSha256").asText().equals(DeploymentAgentToolchainPrograms.sha256(""));
    }

    private static void validateTransportEnvelope(JsonNode actual) {
        var expected = new HashSet<>(Set.of("formatVersion", "outcome"));
        if (!actual.path("formatVersion").isInt() || actual.path("formatVersion").intValue() != 1) throw new IllegalArgumentException("Unsupported actual transport evidence");
        var outcome = TaskResult.valueOf(DeploymentAgentToolchainTracked.text(actual, "outcome"));
        if (actual.has("problemCode")) {
            expected.add("problemCode");
            ProblemCode.valueOf(DeploymentAgentToolchainTracked.text(actual, "problemCode"));
            if (actual.has("problemEvent") && !actual.has("problemEventSha256")) {
                expected.add("problemEvent");
                if (!TRANSPORT_EVENTS.contains(DeploymentAgentToolchainTracked.text(actual, "problemEvent"))) throw new IllegalArgumentException("Uncontrolled transport event");
            } else {
                expected.add("problemEventSha256");
                if (!DeploymentAgentToolchainTracked.text(actual, "problemEventSha256").matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid event digest");
            }
        } else if (outcome == TaskResult.FAILED || outcome == TaskResult.UNKNOWN) throw new IllegalArgumentException("Actual failure problem missing");
        if (actual.has("stdoutSha256") || actual.has("stderrSha256") || actual.has("truncated") || actual.has("exit")) {
            expected.addAll(Set.of("stdoutSha256", "stdoutBytes", "stdoutCharacters", "stderrSha256", "stderrBytes", "stderrCharacters", "truncated", "exit"));
            for (String channel : List.of("stdout", "stderr"))
                if (!DeploymentAgentToolchainTracked.text(actual, channel + "Sha256").matches("[a-f0-9]{64}")
                    || !actual.path(channel + "Bytes").isInt() || actual.path(channel + "Bytes").intValue() < 0
                    || !actual.path(channel + "Characters").isInt() || actual.path(channel + "Characters").intValue() < 0)
                    throw new IllegalArgumentException("Invalid actual channel digest");
            if (!actual.path("truncated").isBoolean() || !(actual.path("exit").isInt() || actual.path("exit").isNull()))
                throw new IllegalArgumentException("Actual command result is incomplete");
        }
        if (actual.has("stdout")) {
            expected.add("stdout"); String stdout = DeploymentAgentToolchainTracked.text(actual, "stdout");
            if (!completeTransport(actual) || stdout.getBytes(StandardCharsets.UTF_8).length > 32768
                || !DeploymentAgentToolchainPrograms.sha256(stdout).equals(actual.path("stdoutSha256").asText())
                || stdout.getBytes(StandardCharsets.UTF_8).length != actual.path("stdoutBytes").asInt(-1)
                || stdout.length() != actual.path("stdoutCharacters").asInt(-1)) throw new IllegalArgumentException("Retained receipt bytes changed");
        }
        DeploymentAgentToolchainTracked.fields(actual, expected);
        if (outcome == TaskResult.SUCCEEDED && actual.has("problemCode")) throw new IllegalArgumentException("Successful actual transport cannot contain a problem");

    }

    private static void validateOriginalTransport(List<DeploymentAgentActionLog.Event> events, String original, JsonNode payload) throws Exception {
        if (!payload.path("rawResultRef").path("kind").asText().equals("TRANSPORT")) throw new IllegalArgumentException("Actual original result proof missing");
        var actual = DeploymentAgentToolchainTracked.parse(DeploymentAgentToolchainTracked.utf8(readBlob(events, original, payload.path("rawResultRef"))));
        validateTransportEnvelope(actual);
        if (!actual.path("outcome").asText().equals(payload.path("transportOutcome").asText())
            || !actual.path("problemCode").equals(payload.path("problemCode")) || !actual.path("problemEvent").equals(payload.path("problemEvent"))
            || !actual.path("problemEventSha256").equals(payload.path("problemEventSha256")))
            throw new IllegalArgumentException("Original transport outcome changed");
        for (String channel : List.of("stdout", "stderr"))
            if (!actual.path(channel + "Sha256").equals(payload.path(channel + "Sha256"))
                || !actual.path(channel + "Characters").equals(payload.path(channel + "Characters")))
                throw new IllegalArgumentException("Original transport bytes changed");
        if (actual.has("stdoutSha256") && (!payload.path("exitObserved").isBoolean()
            || payload.path("exitObserved").booleanValue() != !actual.path("exit").isNull()
            || !actual.path("exit").isNull() && !actual.path("exit").equals(payload.path("exit"))
            || !actual.path("truncated").equals(payload.path("truncated")))) throw new IllegalArgumentException("Original actual SDK result changed");
        boolean confirmed = payload.path("state").asText().equals("CONFIRMED");
        if (confirmed && (!completeTransport(actual) || !payload.has("receipt"))) throw new IllegalArgumentException("Confirmed result lacks complete actual transport");
        if (payload.has("receipt")) {
            if (!completeTransport(actual)) throw new IllegalArgumentException("Physical receipt did not arrive in complete actual transport");
            JsonNode receipt;
            if (payload.has("trackedRequestRef")) {
                var recipe = recipe(events, original, payload);
                var observed = DeploymentAgentToolchainTracked.receipt(actual.path("stdout").textValue(), recipe,
                    recipe.phase().toLowerCase(Locale.ROOT) + "-tracked");
                if (confirmed && !observed.settled()) throw new IllegalArgumentException("Confirmed original effects remain unsettled");
                receipt = observed.value();
            } else {
                validateStoreFrame(payload);
                var frame = payload.path("storeFrame");
                receipt = DeploymentAgentToolchainProgramStore.receipt(actual.path("stdout").textValue(), frame.path("task").asText(),
                    frame.path("operation").asText(), DeploymentRemoteLayout.decode(frame.path("layout").asText()), frame.path("phase").asText().equals("install"));
            }
            if (!receipt.equals(payload.path("receipt"))) throw new IllegalArgumentException("Retained receipt differs from actual original stdout");
        }
    }

    void finish(Action action, OperationResult<CommandResult> result, boolean confirmed, JsonNode receipt) throws ApiException {
        finish(action, result, confirmed, receipt, false);
    }

    void finish(Action action, OperationResult<CommandResult> result, boolean confirmed, JsonNode receipt, boolean notDispatched) throws ApiException {
        finish(action, result, confirmed, receipt, notDispatched, null);
    }

    void finish(Action action, OperationResult<CommandResult> result, boolean confirmed, JsonNode receipt, boolean notDispatched,
                DeploymentAgentToolchainFailureEvidence.PrecreationRefusal refusal) throws ApiException {
        try {
            var before = action.checkpoint().snapshot();
            var body = (ObjectNode) JSON.readTree(before.payload());
            if (before.released() || !body.path("state").asText().equals("INTENT"))
                throw new IllegalStateException("Original action is already ended");
            if (confirmed && !DeploymentAgentToolchainProgramStore.successful(result))
                throw new IllegalArgumentException("Unconfirmed SDK command cannot release occupancy");
            if (notDispatched && (confirmed || result.value().isPresent()))
                throw new IllegalArgumentException("A dispatched result cannot be marked not sent");
            if (refusal != null && (confirmed || notDispatched || receipt != null || result.outcome() != TaskResult.FAILED))
                throw new IllegalArgumentException("A precreation refusal is neither success nor an undispatched action");
            String phase = body.path("index").path("phase").asText();
            if (body.has("trackedRequestRef")) {
                var original = recipe(events.get(), action.id(), body);
                if (receipt != null) {
                    var observation = DeploymentAgentToolchainTracked.receipt("AGENT_TOOLCHAIN_TRACKED=" + receipt + "\n", original,
                        phase.toLowerCase(Locale.ROOT) + "-tracked");
                    if (confirmed != observation.settled()) throw new IllegalArgumentException("Controller settlement proof differs");
                } else if (confirmed) throw new IllegalArgumentException("Tracked action requires physical proof");
            }
            if (body.has("trackedRequestRef") || body.has("storeFrame")) {
                var raw = events.get().stream().filter(event -> event.type().equals("CONTROLLER_BLOB_END") && event.action().equals(action.id()))
                    .map(event -> DeploymentAgentToolchainTracked.parse(event.detail())).filter(ref -> ref.path("kind").asText().equals("TRANSPORT")).toList();
                if (raw.size() != 1) throw new IllegalArgumentException("Actual original SDK result must be durable first");
                readBlob(events.get(), action.id(), raw.getFirst()); body.set("rawResultRef", raw.getFirst());
            }
            if (confirmed && Set.of("READINESS", "INSTALL").contains(phase) && receipt == null)
                throw new IllegalArgumentException("Controller store identity requires its complete original receipt");
            body.put("state", notDispatched ? "NOT_SENT" : confirmed ? "CONFIRMED" : result.outcome() == TaskResult.SUCCEEDED ? "UNKNOWN" : result.outcome().name());
            body.put("confirmationMeaning", notDispatched ? "CURRENT_LIVE_SEND_ENDED_BEFORE_SESSION_EXECUTE_BOUNDARY; original approval/guard outcome retained"
                : body.has("trackedRequestRef") ? "STRICT_ORIGINAL_TRACKED_EFFECT_SETTLEMENT; original worker executionStatus remains independent of actual SDK transport"
                : Set.of("READINESS", "INSTALL").contains(phase)
                ? "STRICT_ORIGINAL_STORE_IDENTITY_RECEIPT_ONLY"
                : "ORIGINAL_SDK_COMMAND_ENDED_ONLY; toolchain asset/binding success still requires DeploymentToolchainSetup's original strict receipt validation");
            body.put("transportOutcome", result.outcome().name());
            result.problem().ifPresent(problem -> problemEvidence(body, problem));
            result.value().ifPresent(value -> {
                body.put("exitObserved", value.exitCode().isPresent());
                value.exitCode().ifPresent(exit -> body.put("exit", exit));
                body.put("truncated", value.truncated());
                body.put("stdoutSha256", DeploymentAgentToolchainPrograms.sha256(value.stdout()));
                body.put("stderrSha256", DeploymentAgentToolchainPrograms.sha256(value.stderr()));
                body.put("stdoutCharacters", value.stdout().length()).put("stderrCharacters", value.stderr().length());
            });
            if (receipt != null) body.set("receipt", receipt.deepCopy());
            if (refusal != null) {
                var proof = refusal.evidence();
                body.set(DeploymentAgentToolchainFailureEvidence.PRECREATION, proof);
                body.put("confirmationMeaning", proof.path("meaning").asText());
                if (!DeploymentAgentToolchainFailureEvidence.releasedPrecreationRefusal(action.id(), body.path("index"), body))
                    throw new IllegalArgumentException("Precreation refusal differs from this original action and result");
            }
            if (body.has("trackedRequestRef") || body.has("storeFrame")) {
                if (notDispatched && events.get().stream().anyMatch(event -> event.type().equals(DISPATCH) && event.action().equals(action.id())))
                    throw new IllegalArgumentException("Original dispatch conflicts with NOT_SENT");
                validateOriginalTransport(events.get(), action.id(), body);
            }
            String payload = bounded(body);
            action.checkpoint().advance(before, payload);
            recorder.event(RESULT, action.id(), JSON.writeValueAsString(Map.of("state", body.path("state").asText(),
                "payloadSha256", DeploymentAgentToolchainPrograms.sha256(payload))));
            if (confirmed || notDispatched || refusal != null) action.checkpoint().close();
        } catch (ApiException failure) {
            throw failure;
        } catch (Exception failure) {
            throw unconfirmed();
        }
    }

    private static String bounded(JsonNode value) throws java.io.IOException {
        String body = JSON.writeValueAsString(value);
        if (body.getBytes(StandardCharsets.UTF_8).length > 12000)
            throw new IllegalArgumentException("Controller action evidence exceeds checkpoint bound");
        return body;
    }

    public static List<Map<String, Object>> snapshot(TaskService tasks, TaskContext task, String server, DeploymentRemoteLayout layout,
                                                     List<DeploymentAgentActionLog.Event> events) throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        var identities = new HashSet<String>();
        for (var event : events) {
            if (!event.type().equals(INDEX)) continue;
            if (!event.action().matches("[0-9a-f]{32}") || !identities.add(event.action()))
                throw new IllegalArgumentException("Duplicate controller index identity");
            var index = JSON.readTree(event.detail());
            var fields = new HashSet<String>();
            index.fieldNames().forEachRemaining(fields::add);
            if (!index.isObject() || !fields.equals(Set.of("key", "task", "server", "layoutSha256", "phase", "operation", "version", "path", "sourceSha256", "requestSha256"))
                || fields.stream().anyMatch(field -> !index.path(field).isTextual())
                || !index.path("key").asText().equals(PREFIX + event.action())
                || !index.path("task").asText().equals(task.id().toString()) || !index.path("server").asText().equals(server)
                || !index.path("layoutSha256").asText().equals(layout.digest()) || !Set.of("READINESS", "INSTALL", "PREPARE", "BIND").contains(index.path("phase").asText())
                || !index.path("operation").asText().matches("[0-9a-f]{32}") || !index.path("version").asText().matches("[0-9a-f]{64}")
                || !index.path("requestSha256").asText().matches("[0-9a-f]{64}")
                || !index.path("sourceSha256").asText().matches("[0-9a-f]{64}")
                || !index.path("path").asText().equals(layout.toolchains() + "/agent-controller-programs/" + index.path("version").asText()))
                throw new IllegalArgumentException("Foreign or invalid controller index");
            var retained = tasks.findCheckpoint(task, PREFIX + event.action());
            if (retained.isEmpty()) {
                result.add(Map.of("action", event.action(), "eventId", event.id(), "index", index, "missing", true, "released", false));
                continue;
            }
            var saved = retained.orElseThrow().snapshot();
            var payload = JSON.readTree(saved.payload());
            if (!payload.path("format").asText().equals("WTL_AGENT_CONTROLLER_ACTION_1") || !payload.path("index").equals(index))
                throw new IllegalArgumentException("Original controller checkpoint identity changed");
            boolean completeOriginal = payload.has("trackedRequestRef") || payload.has("storeFrame");
            if (payload.has("trackedRequestRef")) blobEndBefore(events, event.action(), payload.path("trackedRequestRef"), event.id());
            if (completeOriginal && !payload.path("state").asText().equals("INTENT")) validateOriginalTransport(events, event.action(), payload);
            var ended = events.stream().filter(e -> e.type().equals(RESULT) && e.action().equals(event.action())).toList();
            if (completeOriginal && !ended.isEmpty()) {
                if (ended.size() != 1 || ended.getFirst().id() <= event.id()) throw new IllegalArgumentException("Invalid original result ordering");
                var detail = DeploymentAgentToolchainTracked.parse(ended.getFirst().detail());
                DeploymentAgentToolchainTracked.fields(detail, Set.of("state", "payloadSha256"));
                if (!detail.path("state").equals(payload.path("state"))
                    || !detail.path("payloadSha256").asText().equals(DeploymentAgentToolchainPrograms.sha256(saved.payload())))
                    throw new IllegalArgumentException("Original result differs from retained checkpoint");
                if (payload.has("rawResultRef")) blobEndBefore(events, event.action(), payload.path("rawResultRef"), ended.getFirst().id());
            }
            if (saved.released()) {
                boolean refusal = DeploymentAgentToolchainFailureEvidence.releasedPrecreationRefusal(event.action(), index, payload);
                boolean settlement = settledProof(events, event.action(), payload);
                if (((!Set.of("CONFIRMED", "NOT_SENT").contains(payload.path("state").asText()) && !refusal) && !settlement)
                    || payload.has(DeploymentAgentToolchainFailureEvidence.PRECREATION) && !refusal
                    || !settlement && (ended.size() != 1 || ended.getFirst().id() <= event.id()
                    || JSON.readTree(ended.getFirst().detail()).size() != 2
                    || !JSON.readTree(ended.getFirst().detail()).path("state").asText().equals(payload.path("state").asText())
                    || !JSON.readTree(ended.getFirst().detail()).path("payloadSha256").asText().equals(DeploymentAgentToolchainPrograms.sha256(saved.payload()))))
                    throw new IllegalArgumentException("Released controller action lacks its original result");
            }
            var row = new LinkedHashMap<String, Object>(Map.of("action", event.action(), "eventId", event.id(), "index", index, "missing", false,
                "released", saved.released(), "payload", payload));
            row.put("settled", settledProof(events, event.action(), payload));
            if (payload.has("trackedRequestRef")) {
                var recipe = recipe(events, event.action(), payload);
                if (!recipe.taskId().equals(task.id().toString()) || !recipe.serverId().equals(server) || !recipe.layout().equals(layout)
                    || !recipe.operationId().equals(index.path("operation").asText()) || !recipe.phase().equals(index.path("phase").asText())
                    || !recipe.version().equals(index.path("version").asText()) || !recipe.sourceSha256().equals(index.path("sourceSha256").asText()))
                    throw new IllegalArgumentException("Original tracked recipe differs from checkpoint identity");
                row.put("trackedRequest", recipe.value());
                if (payload.has("receipt")) {
                    var observed = DeploymentAgentToolchainTracked.receipt("AGENT_TOOLCHAIN_TRACKED=" + payload.path("receipt") + "\n", recipe,
                        recipe.phase().toLowerCase(Locale.ROOT) + "-tracked");
                    if (saved.released() && payload.path("state").asText().equals("CONFIRMED") && !observed.settled())
                        throw new IllegalArgumentException("Released tracked action retains unsettled effects");
                }
            }
            var dispatches = events.stream().filter(e -> e.type().equals(DISPATCH) && e.action().equals(event.action())).toList();
            if (dispatches.size() > 1) throw new IllegalArgumentException("Duplicate original dispatch");
            if (completeOriginal && !dispatches.isEmpty()) {
                if (dispatches.getFirst().id() <= event.id() || payload.path("state").asText().equals("NOT_SENT"))
                    throw new IllegalArgumentException("Original dispatch conflicts with retained action");
                if (payload.has("rawResultRef") && dispatches.getFirst().id() >= blobEndBefore(events, event.action(), payload.path("rawResultRef"), Long.MAX_VALUE))
                    throw new IllegalArgumentException("Original result precedes its dispatch");
            }
            row.put("dispatched", !dispatches.isEmpty());
            row.put("recovery", recoveryEvents(events, event.action()));
            result.add(Map.copyOf(row));
        }
        return List.copyOf(result);
    }

    static List<JsonNode> recoveryEvents(List<DeploymentAgentActionLog.Event> events, String original) {
        var result = new ArrayList<JsonNode>();
        var active = new HashMap<String, JsonNode>();
        boolean cleanupConsumed = false;
        for (var event : events) {
            if (!event.type().equals(RECOVERY) || !event.action().equals(original)) continue;
            var value = DeploymentAgentToolchainTracked.parse(event.detail());
            var expected = new HashSet<>(Set.of("formatVersion", "recoveryId", "verb", "state", "exactActionSha256"));
            if (value.has("receipt")) expected.add("receipt");
            DeploymentAgentToolchainTracked.fields(value, expected);
            String id = value.path("recoveryId").asText(), verb = value.path("verb").asText(), state = value.path("state").asText();
            if (!value.path("formatVersion").isInt() || value.path("formatVersion").intValue() != 1 || !id.matches("[a-f0-9]{32}")
                || !Set.of("observe-tracked", "cleanup-tracked", "readiness").contains(verb)
                || !value.path("exactActionSha256").asText().matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid recovery event");
            var prior = active.get(id);
            if (state.equals("INTENT")) {
                if (prior != null || verb.equals("cleanup-tracked") && cleanupConsumed || value.has("receipt"))
                    throw new IllegalArgumentException("Recovery intent cannot replay");
                if (verb.equals("cleanup-tracked")) cleanupConsumed = true;
            } else {
                if (prior == null || !prior.path("verb").asText().equals(verb)
                    || !prior.path("exactActionSha256").equals(value.path("exactActionSha256"))) throw new IllegalArgumentException("Foreign recovery transition");
                if (state.equals("DISPATCHED")) {
                    if (!prior.path("state").asText().equals("INTENT") || value.has("receipt")) throw new IllegalArgumentException("Duplicate recovery dispatch");
                } else if (state.equals("OBSERVED") || state.equals("UNKNOWN") || state.equals("NOT_SENT")) {
                    if (!prior.path("state").asText().equals(state.equals("NOT_SENT") ? "INTENT" : "DISPATCHED")
                        || state.equals("OBSERVED") != value.has("receipt")) throw new IllegalArgumentException("Invalid recovery observation");
                    try {
                        var refs = events.stream().filter(e -> e.type().equals("CONTROLLER_BLOB_END") && e.action().equals(id) && e.id() < event.id())
                            .map(e -> DeploymentAgentToolchainTracked.parse(e.detail())).filter(ref -> ref.path("kind").asText().equals("TRANSPORT")).toList();
                        if (refs.size() != 1) throw new IllegalArgumentException("Recovery actual result is missing");
                        var actual = JSON.readTree(readBlob(events, id, refs.getFirst()));
                        validateTransportEnvelope(actual);
                        if (state.equals("OBSERVED")) {
                            if (!completeTransport(actual))
                                throw new IllegalArgumentException("Observed recovery transport was incomplete");
                            String stdout = actual.path("stdout").asText(), prefix = verb.equals("readiness") ? "AGENT_CONTROLLER_STORE=" : "AGENT_TOOLCHAIN_TRACKED=";
                            if (!stdout.startsWith(prefix) || !stdout.endsWith("\n") || stdout.indexOf('\n') != stdout.length() - 1
                                || !JSON.readTree(stdout.substring(prefix.length(), stdout.length() - 1)).equals(value.path("receipt")))
                                throw new IllegalArgumentException("Recovery receipt differs from actual raw stdout");
                        }
                    } catch (java.io.IOException failure) { throw new IllegalArgumentException("Recovery result unavailable", failure); }
                    catch (Exception failure) { throw new IllegalArgumentException("Recovery result proof incomplete", failure); }
                } else throw new IllegalArgumentException("Unknown recovery transition");
            }
            active.put(id, value); result.add(value);
        }
        if (result.size() > 64) throw new IllegalArgumentException("Controller recovery bound exceeded");
        return List.copyOf(result);
    }

    String recoveryIntent(String original, String verb, String exactAction) throws Exception {
        var history = recoveryEvents(events.get(), original);
        if (history.size() >= 64 || verb.equals("cleanup-tracked") && history.stream()
            .anyMatch(value -> value.path("verb").asText().equals(verb) && Set.of("INTENT", "DISPATCHED").contains(value.path("state").asText())))
            throw new IllegalArgumentException("Original cleanup is consumed and cannot be resent");
        String id = UUID.randomUUID().toString().replace("-", "");
        recoveryEvent(original, id, verb, "INTENT", DeploymentAgentToolchainPrograms.sha256(exactAction), null);
        return id;
    }

    void recoveryEvent(String original, String id, String verb, String state, String exactSha, JsonNode receipt) throws Exception {
        var value = JSON.createObjectNode().put("formatVersion", 1).put("recoveryId", id).put("verb", verb)
            .put("state", state).put("exactActionSha256", exactSha);
        if (receipt != null) value.set("receipt", receipt.deepCopy());
        recorder.event(RECOVERY, original, value.toString());
        recoveryEvents(events.get(), original);
    }

    void recoveredTransport(String recoveryId, OperationResult<CommandResult> result) throws ApiException {
        transport(new Action(recoveryId, null), result);
    }

    void settle(String original, DeploymentAgentToolchainTracked.Observation observation) throws Exception {
        var retained = tasks.findCheckpoint(task, PREFIX + original).orElseThrow();
        var saved = retained.snapshot();
        if (saved.released() || !observation.settled() || !observation.verb().equals("observe-tracked"))
            throw new IllegalArgumentException("Fresh physical observation required for settlement");
        var payload = JSON.readTree(saved.payload());
        var recipe = recipe(events.get(), original, payload);
        if (!recipe.sha256().equals(observation.recipe().sha256())) throw new IllegalArgumentException("Foreign settlement");
        recorder.event(SETTLED, original, JSON.createObjectNode().put("formatVersion", 1)
            .put("payloadSha256", DeploymentAgentToolchainPrograms.sha256(saved.payload())).put("requestSha256", recipe.sha256())
            .set("observation", observation.value()).toString());
        if (!settledProof(events.get(), original, payload)) throw new IllegalArgumentException("Settlement readback unavailable");
        retained.close();
    }

    private static boolean settledProof(List<DeploymentAgentActionLog.Event> events, String original, JsonNode payload) throws Exception {
        var unsent = events.stream().filter(event -> event.type().equals("CONTROLLER_UNSENT_SETTLED") && event.action().equals(original)).toList();
        if (!unsent.isEmpty()) {
            if (unsent.size() != 1 || !payload.path("state").asText().equals("INTENT")
                || events.stream().anyMatch(event -> event.type().equals(DISPATCH) && event.action().equals(original)))
                throw new IllegalArgumentException("Unsent controller proof conflicts with dispatch");
            if (payload.has("trackedRequestRef")) recipe(events, original, payload); else validateStoreFrame(payload);
            var proof = DeploymentAgentToolchainTracked.parse(unsent.getFirst().detail());
            DeploymentAgentToolchainTracked.fields(proof, Set.of("formatVersion", "payloadSha256"));
            requireSettlementOrder(events, original, payload, unsent.getFirst());
            return proof.path("formatVersion").isInt() && proof.path("formatVersion").intValue() == 1
                && proof.path("payloadSha256").asText().equals(DeploymentAgentToolchainPrograms.sha256(payload.toString()));
        }
        var proofs = events.stream().filter(event -> event.type().equals(SETTLED) && event.action().equals(original)).toList();
        if (proofs.isEmpty()) return false;
        if (proofs.size() != 1) throw new IllegalArgumentException("Duplicate controller settlement");
        requireSettlementOrder(events, original, payload, proofs.getFirst());
        var proof = JSON.readTree(proofs.getFirst().detail());
        if (proof.has("storeFrameSha256")) {
            DeploymentAgentToolchainTracked.fields(proof, Set.of("formatVersion", "payloadSha256", "storeFrameSha256", "observation"));
            validateStoreFrame(payload);
            var observation = proof.path("observation");
            var history = recoveryEvents(events, original);
            return proof.path("formatVersion").isInt() && proof.path("formatVersion").intValue() == 1
                && proof.path("payloadSha256").asText().equals(DeploymentAgentToolchainPrograms.sha256(payload.toString()))
                && proof.path("storeFrameSha256").asText().equals(DeploymentAgentToolchainPrograms.sha256(payload.path("storeFrame").toString()))
                && validStoreObservation(payload, observation) && !history.isEmpty()
                && history.getLast().path("state").asText().equals("OBSERVED") && history.getLast().path("receipt").equals(observation);
        }
        DeploymentAgentToolchainTracked.fields(proof, Set.of("formatVersion", "payloadSha256", "requestSha256", "observation"));
        var recipe = recipe(events, original, payload);
        var observed = DeploymentAgentToolchainTracked.receipt("AGENT_TOOLCHAIN_TRACKED=" + proof.path("observation") + "\n", recipe, "observe-tracked");
        var history = recoveryEvents(events, original);
        return proof.path("formatVersion").isInt() && proof.path("formatVersion").intValue() == 1
            && proof.path("payloadSha256").asText().equals(DeploymentAgentToolchainPrograms.sha256(payload.toString()))
            && proof.path("requestSha256").asText().equals(recipe.sha256()) && observed.settled()
            && !history.isEmpty() && history.getLast().path("state").asText().equals("OBSERVED")
            && history.getLast().path("receipt").equals(observed.value());
    }

    private static void requireSettlementOrder(List<DeploymentAgentActionLog.Event> events, String original, JsonNode payload,
                                               DeploymentAgentActionLog.Event proof) {
        var indexes = events.stream().filter(event -> event.type().equals(INDEX) && event.action().equals(original)).toList();
        if (indexes.size() != 1 || indexes.getFirst().id() >= proof.id()
            || !DeploymentAgentToolchainTracked.parse(indexes.getFirst().detail()).equals(payload.path("index")))
            throw new IllegalArgumentException("Settlement lacks its original preceding action");
        if (payload.has("trackedRequestRef")) blobEndBefore(events, original, payload.path("trackedRequestRef"), indexes.getFirst().id());
        if (payload.has("rawResultRef")) blobEndBefore(events, original, payload.path("rawResultRef"), proof.id());
        if (events.stream().anyMatch(event -> event.action().equals(original) && Set.of(RESULT, RECOVERY).contains(event.type()) && event.id() >= proof.id()))
            throw new IllegalArgumentException("Settlement precedes its original result or recovery observation");
    }

    void settleNotSent(String original) throws Exception {
        var retained = tasks.findCheckpoint(task, PREFIX + original).orElseThrow(); var saved = retained.snapshot();
        var payload = JSON.readTree(saved.payload());
        if (saved.released() || !payload.path("state").asText().equals("INTENT")
            || events.get().stream().anyMatch(event -> event.type().equals(DISPATCH) && event.action().equals(original)))
            throw new IllegalArgumentException("Original dispatch is not proven absent");
        if (payload.has("trackedRequestRef")) recipe(events.get(), original, payload); else validateStoreFrame(payload);
        recorder.event("CONTROLLER_UNSENT_SETTLED", original, JSON.createObjectNode().put("formatVersion", 1)
            .put("payloadSha256", DeploymentAgentToolchainPrograms.sha256(saved.payload())).toString());
        if (!settledProof(events.get(), original, payload)) throw new IllegalArgumentException("Unsent readback failed");
        retained.close();
    }

    void closeSettled(String original) throws Exception {
        var retained = tasks.findCheckpoint(task, PREFIX + original).orElseThrow(); var saved = retained.snapshot();
        if (!settledProof(events.get(), original, JSON.readTree(saved.payload()))) throw new IllegalArgumentException("Complete original settlement is required");
        if (!saved.released()) retained.close();
    }

    static void validateStoreFrame(JsonNode payload) {
        var frame = payload.path("storeFrame"); var index = payload.path("index");
        DeploymentAgentToolchainTracked.fields(frame, Set.of("formatVersion", "task", "server", "fingerprintSha256", "layout", "phase", "operation", "version", "sourceSha256", "scriptSha256", "stdinSha256", "stdinBytes", "timeout", "outputLimit"));
        var layout = DeploymentRemoteLayout.decode(frame.path("layout").asText());
        String phase = frame.path("phase").asText(), task = frame.path("task").asText(), op = frame.path("operation").asText();
        if (!frame.path("formatVersion").isInt() || frame.path("formatVersion").intValue() != 1 || !Set.of("readiness", "install").contains(phase)
            || !task.equals(index.path("task").asText()) || !frame.path("server").equals(index.path("server"))
            || !op.equals(index.path("operation").asText()) || !phase.toUpperCase(Locale.ROOT).equals(index.path("phase").asText())
            || !frame.path("version").asText().equals(DeploymentAgentToolchainProgramStore.version(layout))
            || !frame.path("version").equals(index.path("version")) || !frame.path("sourceSha256").equals(index.path("sourceSha256"))
            || !frame.path("sourceSha256").asText().equals(DeploymentAgentToolchainProgramStore.sourceSha256(layout))
            || !frame.path("fingerprintSha256").asText().matches("[a-f0-9]{64}") || !layout.digest().equals(index.path("layoutSha256").asText()))
            throw new IllegalArgumentException("Original store request frame changed");
        String script = phase.equals("install") ? DeploymentAgentToolchainPrograms.command(List.of(task, op))
            : DeploymentAgentToolchainProgramStore.command("readiness", task, op, List.of(), layout);
        byte[] input = phase.equals("install") ? DeploymentAgentToolchainProgramStore.installer(layout).getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!frame.path("scriptSha256").asText().equals(DeploymentAgentToolchainPrograms.sha256(script))
            || !frame.path("stdinSha256").asText().equals(DeploymentAgentToolchainTracked.hash(input))
            || !frame.path("stdinBytes").isInt() || frame.path("stdinBytes").intValue() != input.length
            || !frame.path("outputLimit").isIntegralNumber() || frame.path("outputLimit").longValue() != 65536)
            throw new IllegalArgumentException("Original store source/bytes changed");
        var timeout = java.time.Duration.parse(frame.path("timeout").asText());
        if (timeout.getNano() != 0 || timeout.getSeconds() < 1 || timeout.getSeconds() > 60) throw new IllegalArgumentException("Store timeout changed");
        try (var request = new gold.debug.windowstolinux.appapi.linux.command.CommandRequest(script, input, timeout, 65536)) {
            String exact = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId(frame.path("server").asText()), "controller-action-state", request).exactAction();
            if (!DeploymentAgentToolchainPrograms.sha256(exact).equals(index.path("requestSha256").asText())) throw new IllegalArgumentException("Original store exact request changed");
        }
    }

    private static boolean validStoreObservation(JsonNode payload, JsonNode observation) {
        var frame = payload.path("storeFrame");
        var strict = DeploymentAgentToolchainProgramStore.receipt("AGENT_CONTROLLER_STORE=" + observation + "\n",
            frame.path("task").asText(), frame.path("operation").asText(), DeploymentRemoteLayout.decode(frame.path("layout").asText()), false);
        if (!strict.equals(observation)) return false;
        if (!observation.path("status").asText().equals("READY") || !observation.path("version").equals(frame.path("version"))
            || !observation.path("sourceSha256").equals(frame.path("sourceSha256")) || !observation.path("task").equals(frame.path("task"))
            || !observation.path("action").equals(frame.path("operation"))) return false;
        return !frame.path("phase").asText().equals("install") || observation.path("installTask").equals(frame.path("task"))
            && observation.path("installOperation").equals(frame.path("operation"));
    }

    void settleStore(String original, JsonNode observation) throws Exception {
        var retained = tasks.findCheckpoint(task, PREFIX + original).orElseThrow(); var saved = retained.snapshot();
        var payload = JSON.readTree(saved.payload()); validateStoreFrame(payload);
        if (saved.released() || !validStoreObservation(payload, observation)) throw new IllegalArgumentException("Original store installation not proven");
        recorder.event(SETTLED, original, JSON.createObjectNode().put("formatVersion", 1)
            .put("payloadSha256", DeploymentAgentToolchainPrograms.sha256(saved.payload()))
            .put("storeFrameSha256", DeploymentAgentToolchainPrograms.sha256(payload.path("storeFrame").toString()))
            .set("observation", observation.deepCopy()).toString());
        if (!settledProof(events.get(), original, payload)) throw new IllegalArgumentException("Store settlement readback unavailable");
        retained.close();
    }

    public static boolean hasUnconfirmed(TaskService tasks, TaskContext task, String server, DeploymentRemoteLayout layout,
                                         List<DeploymentAgentActionLog.Event> events) {
        try {
            return snapshot(tasks, task, server, layout, events).stream().anyMatch(row -> !Boolean.TRUE.equals(row.get("released")));
        } catch (Exception failure) {
            return true;
        }
    }

    static JsonNode current(JsonNode receipts, String task, String server, DeploymentRemoteLayout layout, String requestSha256) {
        if (!receipts.path(FIELD).isArray())
            throw new IllegalArgumentException("Original controller action snapshot unavailable");
        JsonNode found = null;
        for (var row : receipts.path(FIELD)) {
            if (row.path("released").asBoolean()) continue;
            var payload = row.path("payload");
            var index = payload.path("index");
            if (found != null || row.path("missing").asBoolean() || !payload.path("state").asText().equals("INTENT")
                || !index.path("task").asText().equals(task) || !index.path("server").asText().equals(server)
                || !index.path("layoutSha256").asText().equals(layout.digest()) || !index.path("requestSha256").asText().equals(requestSha256))
                throw new IllegalArgumentException("Current controller action is not the original pending request");
            found = payload;
        }
        if (found == null) throw new IllegalArgumentException("Original controller action intent unavailable");
        return found;
    }

    static ApiException unconfirmed() {
        return new ApiException(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_CONTROLLER_STATE_UNCONFIRMED",
            "Original controller program action remains unconfirmed", Map.of(), Optional.empty()));
    }
}
