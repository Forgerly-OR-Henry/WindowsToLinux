package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.linux.transfer.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.appapi.ui.page.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.linux.build.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;

/** Controlled ports retain remote effects independently of the real platform/H2 task journal. */
public final class DeploymentAgentControllerRecoveryWorker extends DeploymentRuntimeFixture {
    private static final String TYPE = "deployment-controller-recovery-fixture";
    private static final ServerId SERVER = DeploymentAgentToolchainTrackedTest.SERVER;
    private static final DeploymentRemoteLayout LAYOUT = DeploymentRemoteLayout.original();
    private final ObjectMapper json = new ObjectMapper();
    private String phase, boundary, scenario;
    private ObjectNode peer;
    private ModContext controlled;
    private gold.debug.windowstolinux.app.main.mod.PlatformModFlow flow;
    private final Queue<String> approved = new ArrayDeque<>();
    private final Map<String, Review> reviews = new HashMap<>();
    private int approvals, sends, connections;
    private Throwable failure;
    private Throwable recoveryFailure;
    private boolean recoveryReturned;
    private Boolean recoverySettled;
    private String recoveryDiagnostic = "No original recovery callback report";

    public static void main(String[] args) {
        try {
            assertEquals(4, args.length);
            var fixture = new DeploymentAgentControllerRecoveryWorker(); fixture.phase = args[0]; fixture.root = Path.of(args[1]);
            fixture.boundary = args[2]; fixture.scenario = args[3]; fixture.run();
            System.out.println("CONTROLLER_" + args[0].toUpperCase(Locale.ROOT) + "_VERIFIED"); System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }

    @Override protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict a, ServerId b, TaskSnapshot c, CancelToken d) { throw new AssertionError(); }
            public TransferStallChoice transferStalled(TransferStall a, ServerId b, TaskSnapshot c, CancelToken d) { throw new AssertionError(); }
            public String transferProgress(TransferProgress progress) { throw new AssertionError(); }
            public char[] text(String a, String b, boolean c, TaskSnapshot d, CancelToken e) { throw new AssertionError(); }
            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancel) {
                if (intent.type().equals("platform.ssh.execute")) assertEquals(intent.exactAction(), approved.peek(), "Independent native review precedes the original host grant");
                return true;
            }
        };
    }

    private void run() throws Exception {
        boolean producer = phase.equals("produce");
        if (producer) enable();
        peer = producer ? json.createObjectNode().put("creates", 0).put("observes", 0).put("cleanups", 0) : (ObjectNode) json.readTree(Files.readString(root.resolve("peer.json")));
        if (producer) peer.putArray("grants");
        var runtime = open();
        if (producer) {
            runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile(SERVER.id(), "127.0.0.1", 22, "root", "fixture-password")), "fixture-password".toCharArray());
            try (var operation = runtime.security().enterOperation(); var credentials = runtime.security().openCredentials()) { credentials.save("controller-fixture", "controlled-key".toCharArray()); }
            for (String role : List.of("language", "approval")) {
                var model = new StoredAiModelProfile(role, "http://127.0.0.1:9/controlled", "controlled/" + role, "controller-fixture");
                runtime.persistence().aiProfiles().saveVerified(model, role, Instant.now(), AiCapabilityType.TEXT);
                runtime.persistence().aiProfiles().saveVerified(model, role, Instant.now(), AiCapabilityType.TOOL_CALLING);
            }
            runtime.persistence().aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT, List.of(new AiPurposeAssignment("language")));
            runtime.persistence().aiProfiles().purposes().save(AiPurposeType.APPROVAL, List.of(new AiPurposeAssignment("approval")));
        }
        var report = startControlled(runtime);
        assertTrue(report.problems().isEmpty() || report.problems().stream().allMatch(problem -> problem.eventCode().equals("MOD_RECOVERY_READY")), report.problems().toString());
        if (phase.equals("admit")) {
            assertEquals(gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode.NORMAL, controlled.launchMode());
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertOriginalEndedEvidence(runtime);
            assertTargetOccupied(runtime, false);
            assertOriginalEndedEvidence(runtime);
            assertEquals(0, connections); assertEquals(0, approvals); assertEquals(0, sends);
            flow.close(); runtime.close(); return;
        } else if (producer) {
            var callback = new CompletableFuture<Void>();
            var handle = controlled.tasks().submit(new TaskRequest(TYPE, "Original controller ACK loss", List.of(SERVER.id()), true), task -> {
                try {
                var journal = new DeploymentAgentActionLog(controlled.db());
                String revision = controlled.ai().configurationRevision(AiPurpose.LANGUAGE, task);
                String target;
                try (var session = controlled.linux().connect(SERVER, task)) { target = DeploymentAgentInputs.targetDigest(SERVER, session); }
                journal.create(task, new DeploymentAgentActionLog.Identity(SERVER.id(), target, revision, "MANUAL_REVIEW", true));
                journal.event(task, "SOURCE", "e".repeat(64), "f".repeat(64));
                journal.event(task, "TARGET_PROFILE", "", DeploymentAgentInputs.targetProfile(controlled.servers().get(SERVER, task)));
                DeploymentConfigCapture.remoteLayout(controlled.tasks(), task, LAYOUT);
                if (scenario.equals("LEGACY")) {
                    var state = new DeploymentAgentToolchainProgramState(controlled, task, SERVER, LAYOUT);
                    var action = state.begin(boundary, "a".repeat(32), "original legacy request without complete recipe", null);
                    state.finish(action, unknown(), false, null);
                } else {
                    var scope = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.scope(controlled, task, "MANUAL_REVIEW", true,
                        () -> revision, gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE);
                    try (var permission = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.open(controlled, task, SERVER,
                            revision, "MANUAL_REVIEW", true, "controller-fixture=" + task.id());
                         var session = controlled.linux().connect(SERVER, task)) {
                        var channel = new DeploymentBuildCommandChannel(session, scope.interaction(), SERVER, task, revision, new DeploymentBuildBudget(600),
                            gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard.NONE, LAYOUT);
                        var commands = new DeploymentAgentToolchainCommands(channel, SERVER, controlled, task, LAYOUT);
                        var result = boundary.equals("PREPARE") ? commands.prepareVerified(gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.GO,
                            "1.24", "1.24.13", 90, "-") : commands.bindVerified(DeploymentAgentToolchainCommandsTest.binding());
                        assertEquals(TaskResult.UNKNOWN, result.outcome(), result.toString());
                    }
                }
                journal.state(task, "UNKNOWN"); Files.writeString(root.resolve("task.txt"), task.id().toString());
                var originalRows = DeploymentAgentToolchainProgramState.snapshot(controlled.tasks(), task, SERVER.id(), LAYOUT, journal.events(task));
                Files.writeString(root.resolve("original-controller.json"), json.writeValueAsString(originalRows));
                Files.writeString(root.resolve("original-controller-payloads.json"), json.writeValueAsString(originalPayloads(task, json.valueToTree(originalRows))));
                Files.writeString(root.resolve("original-results.json"), json.writeValueAsString(journal.events(task).stream().filter(event -> event.type().equals("CONTROLLER_PROGRAM_RESULT")).toList()));
                callback.complete(null);
                return unknown();
                } catch (Exception | Error error) {
                    error.printStackTrace(); callback.completeExceptionally(error); throw error;
                }
            });
            awaitUnknown(handle);
            try { callback.get(60, TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException error) { throw new AssertionError("Original callback failed: " + handle.snapshot(), error.getCause()); }
            catch (java.util.concurrent.TimeoutException error) { throw new AssertionError("Original callback did not finish: " + handle.snapshot(), error); }
            for (String file : List.of("task.txt", "original-controller.json", "original-controller-payloads.json", "original-results.json"))
                assertTrue(Files.isRegularFile(root.resolve(file)), () -> "Original callback did not persist " + file + ": " + handle.snapshot());
            assertEquals(handle.id().toString(), Files.readString(root.resolve("task.txt")));
            assertNull(failure); assertEquals(sends, approvals); savePeer();
        } else {
            UUID task = UUID.fromString(Files.readString(root.resolve("task.txt")));
            assertTargetOccupied(runtime, true);
            var result = runtime.tasks().reconcile(task).toCompletableFuture().get(60, TimeUnit.SECONDS);
            assertNull(recoveryFailure, () -> "Original recovery callback failed: " + recoveryFailure);
            assertTrue(recoveryReturned, "The registered original recovery callback must return normally");
            assertNull(failure, () -> "Controlled review/transport failed: " + failure);
            boolean blocked = scenario.equals("LEGACY") || scenario.equals("FOREIGN") || scenario.equals("CLEANUP_LOST") && phase.equals("recover");
            assertEquals(Boolean.valueOf(!blocked), recoverySettled, recoveryDiagnostic);
            assertEquals(blocked ? TaskState.UNKNOWN : TaskState.ENDED, result.state(), result + "\n" + recoveryDiagnostic);
            if (!blocked) assertEquals(Optional.of(TaskResult.FAILED), result.outcome());
            assertEquals(sends, approvals); assertTrue(approved.isEmpty());
            if (scenario.equals("LEGACY") || scenario.equals("FOREIGN")) { assertEquals(0, sends); assertEquals(0, approvals); }
            if (scenario.equals("LEGACY")) assertEquals(0, connections);
            assertEquals(scenario.equals("LEGACY") ? 0 : 1, peer.path("creates").intValue());
            assertEquals(boundary.equals("PREPARE") && !scenario.equals("LEGACY") && !scenario.equals("FOREIGN") ? 1 : 0, peer.path("cleanups").intValue());
            savePeer();
            if (!blocked) { flow.close(); runtime.close(); return; }
            assertTargetOccupied(runtime, true);
        }
        long held = scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL");
        assertTrue(held > 0);
        if (controlled.launchMode() == gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode.RECOVERY) {
            var busy = assertThrows(java.sql.SQLException.class, flow::close);
            assertEquals("Original Mod recovery still owns resources", busy.getMessage());
        } else {
            var busy = assertThrows(ApiException.class, flow::close);
            assertEquals(ProblemCode.BUSY, busy.problem().code());
        }
        assertEquals(held, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        var shutdown = assertThrows(java.sql.SQLException.class, runtime::close);
        assertEquals("Mod sessions and their original contexts must close before platform shutdown", shutdown.getMessage());
        assertEquals(held, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
    }

    private OperationResult<Void> reconcile(TaskContext task) throws Exception {
        var journal = new DeploymentAgentActionLog(controlled.db()); var saved = journal.find(task).orElseThrow();
        String revision = controlled.ai().configurationRevision(AiPurpose.APPROVAL, task);
        assertEquals(saved.identity().configurationDigest(), revision);
        boolean settled = DeploymentAgentControllerRecovery.reconcile(controlled, task, saved, journal, LAYOUT, revision);
        recoverySettled = settled;
        var original = json.readTree(Files.readString(root.resolve("original-controller.json")));
        var current = json.valueToTree(DeploymentAgentToolchainProgramState.snapshot(controlled.tasks(), task, SERVER.id(), LAYOUT, journal.events(task)));
        var transports = new ArrayList<JsonNode>();
        var events = journal.events(task);
        for (var event : events) if (event.type().equals("CONTROLLER_BLOB_END")) {
            var ref = DeploymentAgentToolchainTracked.parse(event.detail());
            if (ref.path("kind").asText().equals("TRANSPORT")) transports.add(DeploymentAgentToolchainTracked.parse(
                DeploymentAgentToolchainTracked.utf8(DeploymentAgentToolchainProgramState.readBlob(events, event.action(), ref))));
        }
        recoveryDiagnostic = json.writeValueAsString(Map.of("settled", settled, "sends", sends, "approvals", approvals, "connections", connections,
            "controllerState", current, "peer", peer, "transportEvidence", transports));
        Files.writeString(root.resolve(phase + "-controller-diagnostic.json"), recoveryDiagnostic);
        assertEquals(original.size(), current.size());
        for (int index = 0; index < original.size(); index++) assertEquals(original.get(index).path("payload"), current.get(index).path("payload"), "Original checkpoint bytes remain immutable");
        assertEquals(json.readTree(Files.readString(root.resolve("original-controller-payloads.json"))),
            json.valueToTree(originalPayloads(task, current)), "Exact original checkpoint payload strings remain immutable");
        assertEquals(json.readTree(Files.readString(root.resolve("original-results.json"))), json.readTree(json.writeValueAsBytes(
            journal.events(task).stream().filter(event -> event.type().equals("CONTROLLER_PROGRAM_RESULT")).toList())));
        if (!settled) return unknown();
        assertTrue(current.findValues("released").stream().allMatch(JsonNode::asBoolean));
        journal.state(task, "FAILED"); return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
            "CONTROLLER_FIXTURE_ORIGINAL_BUSINESS_FAILED", "Controller effects settled; original business did not succeed", Map.of(), Optional.empty())));
    }

    private Map<String, String> originalPayloads(TaskContext task, JsonNode rows) throws Exception {
        var result = new LinkedHashMap<String, String>();
        for (var row : rows) result.put(row.path("action").asText(), controlled.tasks().findCheckpoint(task,
            row.path("index").path("key").asText()).orElseThrow().snapshot().payload());
        return result;
    }

    private void assertOriginalEndedEvidence(PlatformRuntime runtime) throws Exception {
        UUID original = UUID.fromString(Files.readString(root.resolve("task.txt")));
        var snapshot = new gold.debug.windowstolinux.app.db.task.TaskLedger(runtime.persistence().database()).queries().archived(original, OWNER, VERSION).orElseThrow();
        assertEquals(TaskState.ENDED, snapshot.state()); assertEquals(Optional.of(TaskResult.FAILED), snapshot.outcome());
        assertTrue(snapshot.blockers().isEmpty());
        var payloads = json.readTree(Files.readString(root.resolve("original-controller-payloads.json")));
        var originals = json.readTree(Files.readString(root.resolve("original-controller.json")));
        try (var access = scope(runtime, true); var connection = access.openConnection()) {
            var events = DeploymentAgentActionLog.events(connection, original);
            assertEquals(json.readTree(Files.readString(root.resolve("original-results.json"))), json.readTree(json.writeValueAsBytes(
                events.stream().filter(event -> event.type().equals("CONTROLLER_PROGRAM_RESULT")).toList())));
            for (var row : originals) {
                String action = row.path("action").asText(), originalPayload = payloads.path(action).textValue();
                var body = DeploymentAgentToolchainTracked.parse(originalPayload);
                assertEquals(row.path("payload"), body);
                var results = events.stream().filter(event -> event.type().equals("CONTROLLER_PROGRAM_RESULT") && event.action().equals(action)).toList();
                assertEquals(1, results.size());
                assertEquals(DeploymentAgentToolchainPrograms.sha256(originalPayload), DeploymentAgentToolchainTracked.parse(results.getFirst().detail()).path("payloadSha256").asText());
                var transport = DeploymentAgentToolchainTracked.parse(DeploymentAgentToolchainTracked.utf8(
                    DeploymentAgentToolchainProgramState.readBlob(events, action, body.path("rawResultRef"))));
                assertEquals(body.path("transportOutcome").asText(), transport.path("outcome").asText());
                for (String channel : List.of("stdout", "stderr"))
                    for (String field : List.of("Sha256", "Characters")) assertEquals(body.path(channel + field), transport.path(channel + field));
                if (body.has("trackedRequestRef")) {
                    var recipe = DeploymentAgentToolchainProgramState.recipe(events, action, body);
                    if (transport.has("stdout")) assertEquals(body.path("receipt"), DeploymentAgentToolchainTracked.receipt(transport.path("stdout").textValue(), recipe,
                        recipe.phase().toLowerCase(Locale.ROOT) + "-tracked").value());
                    if (!body.path("state").asText().equals("CONFIRMED")) {
                        var settlements = events.stream().filter(event -> event.type().equals("CONTROLLER_PROGRAM_SETTLED") && event.action().equals(action)).toList();
                        assertEquals(1, settlements.size());
                        var proof = DeploymentAgentToolchainTracked.parse(settlements.getFirst().detail());
                        assertEquals(DeploymentAgentToolchainPrograms.sha256(originalPayload), proof.path("payloadSha256").asText());
                        assertEquals(recipe.sha256(), proof.path("requestSha256").asText());
                        assertTrue(DeploymentAgentToolchainTracked.receipt("AGENT_TOOLCHAIN_TRACKED=" + proof.path("observation") + "\n", recipe, "observe-tracked").settled());
                    }
                } else assertEquals(body.path("receipt"), DeploymentAgentToolchainProgramStore.receipt(transport.path("stdout").textValue(), original.toString(),
                    body.path("index").path("operation").asText(), LAYOUT, body.path("index").path("phase").asText().equals("INSTALL")));
            }
            try (var query = connection.prepareStatement("SELECT server_profile_id,task_state FROM deployment_agent_task WHERE task_id=?")) {
                query.setString(1, original.toString());
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next()); assertEquals(SERVER.id(), rows.getString(1)); assertEquals("FAILED", rows.getString(2)); assertFalse(rows.next());
                }
            }
        }
        assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE task_id='" + original + "'"));
        assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + original + "'"));
    }

    private void assertTargetOccupied(PlatformRuntime runtime, boolean occupied) throws Exception {
        if (controlled.launchMode() == gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode.RECOVERY) {
            assertTrue(occupied);
            UUID original = UUID.fromString(Files.readString(root.resolve("task.txt")));
            long held = scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND task_id='" + original + "'");
            long tasks = scalar(runtime, "SELECT COUNT(*) FROM platform_task");
            assertTrue(held > 0);
            try (var access = scope(runtime, true); var connection = access.openConnection();
                 var query = connection.prepareStatement("SELECT server_profile_id,target_digest,task_state FROM deployment_agent_task WHERE task_id=?")) {
                query.setString(1, original.toString());
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next()); assertEquals(SERVER.id(), rows.getString(1));
                    assertEquals(DeploymentAgentToolchainPrograms.sha256(SERVER.id() + ":" + DeploymentAgentToolchainTrackedTest.FINGERPRINT), rows.getString(2));
                    assertEquals("UNKNOWN", rows.getString(3)); assertFalse(rows.next());
                }
            }
            var refused = assertThrows(ApiException.class, () -> controlled.tasks().submit(
                new TaskRequest("controller-target-probe", "Verify original target occupancy", List.of(SERVER.id()), true), task -> { throw new AssertionError("Recovery-only activation cannot admit new business"); }));
            assertEquals(ProblemCode.UNAVAILABLE, refused.problem().code()); assertEquals("TASK_ADMISSION_CLOSED", refused.problem().eventCode());
            assertEquals(tasks, scalar(runtime, "SELECT COUNT(*) FROM platform_task"));
            assertEquals(held, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND task_id='" + original + "'"));
            return;
        }
        var result = controlled.tasks().submit(new TaskRequest("controller-target-probe", "Verify original target occupancy", List.of(SERVER.id()), true), task -> {
            var journal = new DeploymentAgentActionLog(controlled.db());
            var original = journal.find(task, UUID.fromString(Files.readString(root.resolve("task.txt")))).orElseThrow();
            if (occupied) assertThrows(ApiException.class, () -> journal.create(task, original.identity()));
            else { journal.create(task, original.identity()); journal.state(task, "FAILED"); }
            return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
        }).completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
        assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
    }

    private static void awaitUnknown(TaskHandle<?> handle) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (handle.snapshot().state() != TaskState.UNKNOWN && handle.snapshot().state() != TaskState.ENDED && System.nanoTime() < until) Thread.sleep(20);
        assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), handle.snapshot().toString());
    }

    private static <T> OperationResult<T> unknown() {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_ACK_LOST", "Original actual transport acknowledgement was lost", Map.of(), Optional.empty())));
    }

    private void savePeer() throws Exception { Files.writeString(root.resolve("peer.json"), peer.toString()); }

    private ModContext bind(ModContext actual) throws Exception {
        var linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(), new Class<?>[]{LinuxService.class}, (proxy, method, args) -> {
            if (method.getName().equals("pendingTransfers")) return List.of();
            if (!method.getName().equals("connect")) throw new AssertionError(method.getName());
            TaskContext task = (TaskContext) args[1]; connections++;
            if (args[0] instanceof RemoteTarget expected) {
                var server = actual.servers().get(SERVER, task);
                assertEquals(task.id(), expected.taskId()); assertEquals(server.host(), expected.host()); assertEquals(server.port(), expected.port());
            } else assertEquals(SERVER, args[0]);
            var retained = task.retain("controller_fixture_session", UUID.randomUUID().toString(), "Controlled original authenticated session");
            return Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class}, (session, operation, arguments) -> {
                if (operation.getName().equals("facts")) return new RemoteFacts("linux", "x86_64", scenario.equals("FOREIGN") && !phase.equals("produce")
                    ? "SHA256:" + "C".repeat(43) : DeploymentAgentToolchainTrackedTest.FINGERPRINT);
                if (operation.getName().equals("close")) { retained.close(); return null; }
                assertEquals("execute", operation.getName());
                try {
                    var request = (CommandRequest) arguments[0]; assertNotNull(arguments[1]);
                    String grant = arguments[1].toString();
                    for (var previous : peer.path("grants")) assertNotEquals(previous.textValue(), grant, "Each original or recovery send requires a new grant");
                    ((ArrayNode) peer.path("grants")).add(grant);
                    assertEquals(approved.remove(), gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(SERVER, "fixture", request).exactAction());
                    sends++; return execute(request);
                } catch (Throwable error) { failure = error; throw error; }
            });
        });
        var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class}, (proxy, method, args) -> {
            if (method.getName().equals("configurationRevision")) return invoke(actual.ai(), method, args);
            assertEquals("call", method.getName()); assertTrue(args[0] instanceof AiRequest);
            try { return review((AiRequest) args[0], (TaskContext) args[args.length - 1]); }
            catch (Throwable error) { failure = error; throw error; }
        });
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("linux")) return linux; if (method.getName().equals("ai")) return ai; return invoke(actual, method, args);
        });
    }

    private OperationResult<CommandResult> execute(CommandRequest request) throws Exception {
        String script = request.script();
        if (script.contains(" 'readiness' '") && script.equals(DeploymentAgentToolchainProgramStore.command("readiness", extractTask(script), extractOperation(script), List.of(), LAYOUT))) {
            var receipt = DeploymentAgentToolchainProgramStoreTest.observation(extractTask(script), extractOperation(script), true);
            receipt.put("installTask", "11111111-1111-1111-1111-111111111111").put("installOperation", "b".repeat(32));
            return DeploymentAgentToolchainProgramStateTest.success("AGENT_CONTROLLER_STORE=" + receipt + "\n");
        }
        if (script.startsWith(DeploymentAgentToolchainPrograms.command(List.of("resolve")))) {
            var args = DeploymentAgentToolchainPrograms.arguments(script);
            assertEquals(DeploymentAgentToolchainPrograms.resolver(LAYOUT), new String(request.stdin(), StandardCharsets.UTF_8));
            return DeploymentAgentToolchainProgramStateTest.success("TOOLCHAIN_RELEASE=" + DeploymentAgentToolchainReleaseTest.encoded(args.get(1), args.get(2), args.get(3), args.get(5)) + "\n");
        }
        var recipe = DeploymentAgentToolchainTracked.decode(request.stdin());
        var frame = DeploymentAgentToolchainProgramStore.arguments(script, LAYOUT); String verb = frame.getFirst();
        assertEquals(recipe.frame(verb), frame); assertEquals(DeploymentAgentToolchainProgramStore.trackedCommand(verb, recipe), script);
        if (verb.equals("prepare-tracked") || verb.equals("bind-tracked")) {
            assertEquals(0, peer.path("creates").intValue()); peer.put("creates", 1).put("recipe", Base64.getEncoder().encodeToString(recipe.bytes()));
            var observation = DeploymentAgentToolchainTrackedTest.observation(recipe, verb,
                scenario.equals("FAILED") ? "FAILED" : boundary.equals("BIND") ? "SUCCEEDED" : "UNKNOWN", boundary.equals("BIND"));
            peer.set("observation", observation); savePeer();
            return scenario.equals("FAILED") ? DeploymentAgentToolchainTrackedTest.result(observation)
                : new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), unknown().problem());
        }
        assertArrayEquals(Base64.getDecoder().decode(peer.path("recipe").asText()), recipe.bytes());
        var observation = (ObjectNode) peer.path("observation").deepCopy(); observation.put("verb", verb);
        if (verb.equals("observe-tracked")) peer.put("observes", peer.path("observes").intValue() + 1);
        else {
            assertEquals("cleanup-tracked", verb); assertEquals(0, peer.path("cleanups").intValue(), "Unacknowledged cleanup cannot be resent");
            peer.put("cleanups", 1); observation.put("effectState", "SETTLED_RETAINED_EFFECTS").put("cleanupState", "VERIFIED");
            ((ObjectNode) observation.path("resources")).put("image", "ABSENT"); peer.set("observation", observation); savePeer();
            if (scenario.equals("CLEANUP_LOST") && phase.equals("recover")) return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), unknown().problem());
        }
        savePeer(); return DeploymentAgentToolchainTrackedTest.result(observation);
    }

    private String extractTask(String script) { return DeploymentAgentToolchainProgramStore.arguments(script, LAYOUT).get(1); }
    private String extractOperation(String script) { return DeploymentAgentToolchainProgramStore.arguments(script, LAYOUT).get(2); }

    private OperationResult<AiResponse> review(AiRequest request, TaskContext task) throws Exception {
        assertEquals(AiPurpose.APPROVAL, request.purpose(), "Recovery cannot call the LANGUAGE planner");
        var initial = json.readTree(request.messages().get(1).text());
        var review = reviews.computeIfAbsent(initial.path("reviewBinding").asText(), ignored -> new Review(initial));
        if (review.pending != null) {
            var result = request.messages().stream().flatMap(message -> message.toolResult().stream()).filter(value -> value.callId().equals(review.pending)).findFirst().orElseThrow();
            assertFalse(result.error(), result.text()); var page = json.readTree(result.text()).path("value");
            assertEquals(review.current.sha, page.path("sha256").asText()); assertEquals(review.current.content.length(), page.path("offset").intValue());
            review.current.content.append(page.path("text").asText());
            assertEquals(review.current.content.length(), page.path("next").intValue()); review.pending = null;
        }
        String name, arguments;
        review.current = review.pages.stream().filter(page -> page.content.length() < page.total).findFirst().orElse(null);
        if (review.current != null) {
            name = review.current.tool; var args = review.current.arguments.deepCopy();
            args.put("offset", review.current.content.length()).put("limit", Math.min(8000, review.current.total - review.current.content.length()));
            arguments = args.toString(); review.pending = "page_" + UUID.randomUUID().toString().replace("-", "");
        } else {
            for (var page : review.pages) assertEquals(page.sha, DeploymentAgentToolchainPrograms.sha256(page.content.toString()));
            String exact = initial.path("currentRequest").path("exactAction").isTextual() ? initial.path("currentRequest").path("exactAction").textValue()
                : review.pages.stream().filter(page -> page.arguments.path("pointer").asText().equals("/exactAction")).findFirst().orElseThrow().content.toString();
            approved.add(exact); approvals++; name = "submit_result";
            arguments = "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Exact controlled original controller request and complete source reviewed\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[]}";
        }
        final String selected = name;
        var tool = request.tools().stream().filter(value -> value.name().equals(selected)).findFirst().orElseThrow();
        var schema = json.readTree(tool.inputSchema()); var args = json.readTree(arguments);
        if (selected.equals("submit_result")) {
            var required = new HashSet<String>(); schema.path("required").forEach(field -> required.add(field.textValue()));
            var actual = new HashSet<String>(); args.fieldNames().forEachRemaining(actual::add); assertEquals(required, actual);
            assertEquals("boolean", schema.path("properties").path("requiresUserConfirmation").path("type").asText());
            var basic = json.createObjectNode();
            for (String field : List.of("decision", "requiresUserConfirmation", "reason")) basic.set(field, args.get(field));
            var decision = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision.parse(basic);
            assertEquals("APPROVE", decision.decision()); assertFalse(decision.requiresUserConfirmation());
            for (String field : List.of("missingEvidence", "adjustments", "candidateCommands")) {
                assertEquals("array", schema.path("properties").path(field).path("type").asText());
                assertTrue(args.path(field).isArray()); assertTrue(args.path(field).isEmpty());
            }
        } else gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas.validate(schema, args);
        String id = review.pending == null ? "verdict_" + UUID.randomUUID().toString().replace("-", "") : review.pending;
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled/approval", "tool_calls",
            new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), List.of(new AiToolCall(id, name, arguments)), Optional.empty())), Optional.empty());
    }

    private static final class Page {
        final String tool, sha; final ObjectNode arguments; final int total; final StringBuilder content = new StringBuilder();
        Page(JsonNode node) { tool = node.path("tool").asText(); sha = node.path("sha256").asText(); arguments = (ObjectNode) node.path("arguments").deepCopy();
            total = node.has("total") ? node.path("total").intValue() : node.path("characters").intValue(); if (node.path("text").isTextual()) content.append(node.path("text").textValue()); }
    }
    private static final class Review {
        final List<Page> pages = new ArrayList<>(); Page current; String pending;
        Review(JsonNode initial) { add(initial.path("executableInput")); initial.path("evidencePages").elements().forEachRemaining(this::add); }
        void add(JsonNode value) {
            if (value.isObject() && value.path("tool").isTextual() && value.path("arguments").isObject() && value.path("sha256").isTextual()
                && (value.has("total") || value.has("characters"))) {
                var page = new Page(value); if (pages.stream().noneMatch(old -> old.tool.equals(page.tool) && old.arguments.equals(page.arguments))) pages.add(page);
            } else value.elements().forEachRemaining(this::add);
        }
    }

    private gold.debug.windowstolinux.app.main.mod.PlatformModFlow.Report startControlled(PlatformRuntime runtime) throws Exception {
        var originalHost = runtime.capabilities(); var sessions = new LinkedHashMap<String, gold.debug.windowstolinux.app.mod.run.ModRuntimeSession>();
        var host = (gold.debug.windowstolinux.app.main.mod.PlatformModHost) Proxy.newProxyInstance(gold.debug.windowstolinux.app.main.mod.PlatformModHost.class.getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.app.main.mod.PlatformModHost.class}, (proxy, method, args) -> {
                if (method.getName().equals("modSessions")) return Map.copyOf(sessions);
                if (!method.getName().equals("modSession")) return invoke(originalHost, method, args);
                var loader = (gold.debug.windowstolinux.app.mod.load.ModClassLoader) args[0];
                @SuppressWarnings("unchecked") var schema = (Optional<gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity>) args[1];
                var lease = originalHost.modContext(loader, schema, (gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode) args[2], (ModPageRegistry) args[3]);
                var context = lease.context();
                if (loader.manifest().id().equals(OWNER)) {
                    controlled = bind(context); context = controlled;
                    context.tasks().registerRecovery(TYPE, (previous, task) -> {
                        try { var result = reconcile(task); recoveryReturned = true; return result; }
                        catch (Exception | Error error) { recoveryFailure = error; error.printStackTrace(); throw error; }
                    });
                }
                final ModContext selected = context;
                var wrapped = (gold.debug.windowstolinux.app.mod.run.ModRuntimeSession.ContextLease) Proxy.newProxyInstance(
                    gold.debug.windowstolinux.app.mod.run.ModRuntimeSession.ContextLease.class.getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.app.mod.run.ModRuntimeSession.ContextLease.class},
                    (p, m, a) -> m.getName().equals("context") ? selected : invoke(lease, m, a));
                var session = new gold.debug.windowstolinux.app.mod.run.ModRuntimeSession(loader, wrapped,
                    (gold.debug.windowstolinux.app.mod.version.SemanticVersion) args[4], lease.journal(() -> sessions.remove(loader.manifest().id())));
                assertNull(sessions.putIfAbsent(loader.manifest().id(), session)); return session;
            });
        var build = new Properties(); try (var input = ModContext.class.getResourceAsStream("/gold/debug/windowstolinux/appapi/build.properties")) { build.load(input); }
        var pages = new ModPageRegistry(new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, javax.swing.JComponent component) { }
            public void focus(ModPageRegistry.PageKey key) { } public void remove(ModPageRegistry.PageKey key) { } public void environment(UiEnvironment environment) { }
        }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
        flow = new gold.debug.windowstolinux.app.main.mod.PlatformModFlow(host, workspace(), true,
            gold.debug.windowstolinux.app.db.mod.ModRestartLedger.initiallyDisabled(runtime.persistence().database()), pages,
            gold.debug.windowstolinux.app.mod.version.SemanticVersion.parse(build.getProperty("platform.api.version")));
        return flow.start();
    }

    private static Object invoke(Object owner, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(owner, arguments); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
