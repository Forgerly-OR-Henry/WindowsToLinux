package gold.debug.windowstolinux.mod.deployment.service.standard.shared.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.build.*;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy.DeploymentPublishPolicyFacts;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.source.DeploymentStandardSetupActionLog;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Real host recovery and retained checkpoints with controlled original diagnostic reads. */
class DeploymentStandardPreparationDiagnosticRecoveryTest extends DeploymentRuntimeFixture {
    private static final String FINGERPRINT = "SHA256:" + "a".repeat(43);
    private static final String REVISION = "b".repeat(64);
    private static final UUID OPERATION = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private String scenario;
    private final AtomicReference<TaskContext> originalTask = new AtomicReference<>();
    private final AtomicInteger prompts = new AtomicInteger();
    private final AtomicInteger diagnosticReviews = new AtomicInteger();
    private final AtomicInteger conclusionPrompts = new AtomicInteger();

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return (PlatformInteractionService.Prompts) Proxy.newProxyInstance(PlatformInteractionService.Prompts.class.getClassLoader(),
            new Class<?>[]{PlatformInteractionService.Prompts.class}, (proxy, method, args) -> {
                if (method.getName().equals("text")) {
                    if (args[0].equals(DeploymentStandardPreparationDiagnosticRecovery.CONCLUSION_TITLE)) {
                        assertEquals(originalTask.get().id(), ((TaskSnapshot) args[3]).id());
                        assertTrue(((String) args[1]).contains("Task=" + originalTask.get().id()));
                        assertTrue(((String) args[1]).contains("Operation=" + OPERATION));
                        assertTrue(((String) args[1]).contains("PreparationStatus=SUCCEEDED"));
                        conclusionPrompts.incrementAndGet();
                        return Set.of("CONCLUDE", "EXTRA_RELEASED_OPERATION", "EXTRA_RESOURCE").contains(scenario)
                            ? DeploymentStandardPreparationDiagnosticRecovery.CONCLUSION_CONFIRMATION.toCharArray() : new char[0];
                    }
                    assertEquals(DeploymentStandardPreparationDiagnosticRecovery.SELECTION_TITLE, args[0]);
                    assertEquals(DeploymentStandardPreparationDiagnosticRecovery.SELECTION_PROMPT, args[1]);
                    assertEquals(false, args[2]);
                    assertEquals(originalTask.get().id(), ((TaskSnapshot) args[3]).id());
                    prompts.incrementAndGet();
                    return switch (scenario) {
                        case "EMPTY" -> new char[0];
                        case "CANCELLED" -> null;
                        case "INVALID" -> "not-an-operation".toCharArray();
                        case "MISSING" -> UUID.randomUUID().toString().toCharArray();
                        default -> OPERATION.toString().toCharArray();
                    };
                }
                assertEquals("approve", method.getName());
                var intent = (ActionIntent) args[0];
                String operation = intent.semantics().orElseThrow().operation();
                assertTrue(Set.of("deployment.workflow.v1", "deployment.toolchain.diagnostic.v1").contains(operation),
                    "Diagnostic recovery must never approve installation, preparation, helper repair or publication");
                if (operation.equals("deployment.toolchain.diagnostic.v1")) {
                    diagnosticReviews.incrementAndGet();
                    assertEquals(scenario.equals("LARGE") ? "d".repeat(64) : REVISION, intent.revision());
                    return !scenario.equals("DENIED");
                }
                return true;
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "SUCCEEDED", "FAILED", "EMPTY", "CANCELLED", "INVALID", "MISSING",
        "DENIED", "HOST", "MODEL", "LAYOUT", "ATTACHMENT", "CLOSE"})
    void selectedOriginalReadAlwaysRetainsUnknownAndNeverFallsThroughPreparationCleanup(String selectedScenario) throws Exception {
        originalDiagnosticScenario(selectedScenario);
    }

    @Test
    void largeEscapedAttachmentFitsActualLedgerWithItsIndependentOriginalPlan() throws Exception {
        originalDiagnosticScenario("LARGE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONCLUDE", "EXTRA_RELEASED_OPERATION", "EXTRA_RESOURCE", "DECLINE", "CONCLUDE_CLOSE", "SUCCEEDED"})
    void conclusionRequiresSingleCompleteScopeFreshSuccessAndActualResourceRelease(String selectedScenario) throws Exception {
        originalDiagnosticScenario(selectedScenario);
    }

    private void originalDiagnosticScenario(String selectedScenario) throws Exception {
        scenario = selectedScenario;
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = new ServerId("diagnostic-original-server");
            runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile(server.id(), "127.0.0.1", 22,
                "root", "fixture-password")), "fixture-password".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            runtime.tasks().revokeOwnerServices(OWNER, VERSION);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var request = new AtomicReference<DeploymentToolchainDiagnostics.Request>();
            var commands = new AtomicInteger();
            var connections = new AtomicInteger();
            var closed = new AtomicInteger();
            var authorCalls = new AtomicInteger();
            var extraResource = new AtomicReference<gold.debug.windowstolinux.appapi.task.run.ResourceLease>();
            var observation = new AtomicReference<OperationResult<Void>>();
            var failure = new AtomicReference<Throwable>();
            var linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(), new Class<?>[]{LinuxService.class},
                (proxy, method, args) -> {
                    assertEquals("connect", method.getName());
                    connections.incrementAndGet();
                    return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class},
                        (session, operation, values) -> switch (operation.getName()) {
                            case "facts" -> new RemoteFacts("Linux", "x86_64", scenario.equals("HOST") ? "SHA256:" + "c".repeat(43) : FINGERPRINT);
                            case "close" -> {
                                if (Set.of("CLOSE", "CONCLUDE_CLOSE").contains(scenario)) throw new ApiException(problem("TEST_CLOSE_UNCONFIRMED"));
                                closed.incrementAndGet(); yield null;
                            }
                            case "execute" -> {
                                var command = (CommandRequest) values[0];
                                assertTrue(command.script().contains("diagnose-only"));
                                assertFalse(command.script().contains("prepare-diagnosed"));
                                assertFalse(command.script().contains("prepare-tracked"));
                                assertFalse(command.script().contains("observe-tracked"));
                                assertEquals(30, command.timeout().toSeconds());
                                assertArrayEquals(request.get().bytes(), command.stdin());
                                commands.incrementAndGet();
                                yield new OperationResult<>(TaskResult.SUCCEEDED,
                                    Optional.of(new CommandResult(Optional.of(0), attachment(request.get()), "", false)), Optional.empty());
                            }
                            default -> throw new AssertionError("Unexpected remote action " + operation.getName());
                        });
                });
            var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("tasks")) return tasks;
                    if (method.getName().equals("linux")) return linux;
                    try { return method.invoke(actual, args); }
                    catch (InvocationTargetException failed) { throw failed.getCause(); }
                });
            var mode = (DeploymentStandardModeSupport) Proxy.newProxyInstance(DeploymentStandardModeSupport.class.getClassLoader(),
                new Class<?>[]{DeploymentStandardModeSupport.class}, (proxy, method, args) -> {
                    assertEquals("recover", method.getName());
                    assertEquals(REVISION, args[2], "Recovery uses the original captured model route");
                    if (scenario.equals("MODEL")) throw new ApiException(problem("TEST_ORIGINAL_MODEL_CHANGED"));
                    return args[0];
                });
            var support = (DeploymentStandardRecoverySupport) Proxy.newProxyInstance(DeploymentStandardRecoverySupport.class.getClassLoader(),
                new Class<?>[]{DeploymentStandardRecoverySupport.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "mode" -> { assertEquals("ASSISTED", args[0]); yield mode; }
                    case "permits" -> true;
                    case "publication" -> (DeploymentRemoteRecovery) (task, execution) -> { throw new AssertionError("No publication recovery"); };
                    case "cleanupReady" -> {
                        assertEquals("CONCLUDE", scenario, "Only the explicitly confirmed complete single preparation can close");
                        yield true;
                    }
                    default -> throw new AssertionError(method.getName());
                });
            var recovery = new DeploymentStandardTaskRecovery(context, new DeploymentRefFlow(context), support);
            try (var registration = tasks.registerRecovery(DeploymentRefFlow.TASK_TYPE, (previous, task) -> {
                try {
                    assertSame(originalTask.get(), task);
                    var observed = recovery.reconcile(previous, task);
                    observation.set(observed);
                    return observed;
                } catch (Throwable failed) { failure.set(failed); throw failed; }
            })) {
                var handle = tasks.submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE, "Original interrupted preparation", List.of(server.id()), true), task -> {
                    originalTask.set(task); authorCalls.incrementAndGet();
                    DeploymentStandardSetupActionLog.begin(tasks, task, server);
                    var policy = DeploymentPublishPolicyFacts.begin(context, task, server, "ASSISTED", "MANUAL_REVIEW", true, REVISION);
                    policy.authenticated(FINGERPRINT);
                    var layout = scenario.equals("LAYOUT")
                        ? new DeploymentRemoteLayout(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths("/usr/local/lib/other-helper", "/opt/windowstolinux/apps", "/var/lib/windowstolinux", "/etc/opt/windowstolinux/apps", "/var/opt/windowstolinux/apps"))
                        : DeploymentRemoteLayout.original();
                    var retained = new DeploymentToolchainDiagnostics.Request(OPERATION, task.id().toString(), server.id(),
                        DeploymentTrackedToolchains.sha256(FINGERPRINT.getBytes(StandardCharsets.UTF_8)), scenario.equals("LARGE") ? "d".repeat(64) : REVISION, layout,
                        List.of("prepare", "PHP", "8.3", "-", "1785", "-"));
                    request.set(retained);
                    tasks.beginCheckpoint(task, DeploymentToolchainDiagnostics.checkpointKey(OPERATION),
                        new String(retained.bytes(), StandardCharsets.UTF_8)).close();
                    if (scenario.equals("EXTRA_RELEASED_OPERATION")) {
                        var second = new DeploymentToolchainDiagnostics.Request(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                            task.id().toString(), server.id(), retained.fingerprintSha256(), retained.planRevision(), retained.layout(), retained.arguments());
                        tasks.beginCheckpoint(task, DeploymentToolchainDiagnostics.checkpointKey(second.operationId()),
                            new String(second.bytes(), StandardCharsets.UTF_8)).close();
                    }
                    if (scenario.equals("EXTRA_RESOURCE")) extraResource.set(task.retain("fixture", "unresolved-original-effect", "Extra unresolved original resource"));
                    return unknown();
                });
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < until) Thread.sleep(10);
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                var recovered = reconcileReady(runtime, handle, 30);
                if (failure.get() != null) throw new AssertionError("Original diagnostic recovery failed", failure.get());
                boolean concluded = scenario.equals("CONCLUDE");
                assertEquals(concluded ? TaskState.ENDED : TaskState.UNKNOWN, recovered.state(), recovered.toString());
                assertEquals(concluded ? TaskResult.FAILED : TaskResult.UNKNOWN, observation.get().outcome());
                assertEquals(concluded ? "DEPLOYMENT_STATIC_PREPARATION_INTERRUPTED" : "DEPLOYMENT_PREPARATION_DIAGNOSTIC_OBSERVATION",
                    observation.get().problem().orElseThrow().eventCode());
                assertEquals(1, prompts.get());
                assertEquals(1, authorCalls.get(), "Preparation is never replayed");
                assertEquals(concluded ? 0 : 2, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + handle.id()
                    + "' AND resource_id IN ('" + OWNER + "/checkpoint/" + DeploymentStandardSetupActionLog.KEY + "','"
                    + OWNER + "/checkpoint/" + DeploymentPublishPolicyFacts.KEY + "') AND released_at IS NULL"));
                assertEquals(concluded ? 0 : 1, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + handle.id() + "' AND resource_kind='SERVER' AND released_at IS NULL"));
                var details = observation.get().problem().orElseThrow().context();
                if (Set.of("UNKNOWN", "SUCCEEDED", "FAILED", "CLOSE", "CONCLUDE", "EXTRA_RELEASED_OPERATION", "EXTRA_RESOURCE", "DECLINE", "CONCLUDE_CLOSE").contains(scenario)) {
                    assertEquals("SUCCEEDED", details.get("diagnosticReadOutcome"), "Attachment availability is separate from original preparation status");
                    assertEquals(Set.of("UNKNOWN", "FAILED", "CLOSE").contains(scenario) ? (scenario.equals("CLOSE") ? "UNKNOWN" : scenario) : "SUCCEEDED",
                        new ObjectMapper().readTree(details.get("toolchainDiagnostic")).path("preparationStatus").asText());
                    assertEquals(request.get().sha256(), details.get("diagnosticRequestSha256"));
                    assertEquals(Set.of("CLOSE", "CONCLUDE_CLOSE").contains(scenario) ? "false" : "true", details.get("diagnosticSessionCloseConfirmed"));
                }
                if (Set.of("UNKNOWN", "SUCCEEDED", "FAILED", "ATTACHMENT", "CLOSE", "LARGE", "CONCLUDE", "EXTRA_RELEASED_OPERATION", "EXTRA_RESOURCE", "DECLINE", "CONCLUDE_CLOSE").contains(scenario)) {
                    assertEquals(1, commands.get()); assertEquals(1, diagnosticReviews.get());
                } else assertEquals(0, commands.get());
                if (Set.of("EMPTY", "CANCELLED", "INVALID", "MISSING", "MODEL", "LAYOUT").contains(scenario)) assertEquals(0, connections.get());
                if (scenario.equals("DENIED")) assertEquals(1, diagnosticReviews.get());
                if (scenario.equals("ATTACHMENT")) assertFalse(details.containsKey("toolchainDiagnostic"));
                if (scenario.equals("LARGE")) {
                    assertEquals("SUCCEEDED", details.get("diagnosticReadOutcome"));
                    assertEquals("true", details.get("diagnosticAttachmentExportTruncated"));
                    assertFalse(details.containsKey("toolchainDiagnostic"));
                    assertEquals("UNKNOWN", new ObjectMapper().readTree(details.get("toolchainDiagnosticExcerpt")).path("preparationStatus").asText());
                    assertEquals("d".repeat(64), details.get("diagnosticPlanRevision"));
                    try (var connection = runtime.persistence().database().openConnection();
                         var query = connection.prepareStatement("SELECT result_json FROM platform_task WHERE task_id=?")) {
                        query.setObject(1, handle.id());
                        try (var row = query.executeQuery()) {
                            assertTrue(row.next());
                            String persisted = row.getString(1);
                            assertTrue(persisted.length() < 65536);
                            assertEquals("true", new ObjectMapper().readTree(persisted)
                                .at("/problem/context/diagnosticAttachmentExportTruncated").asText());
                        }
                    }
                }
                if (concluded) {
                    assertEquals(Optional.of(TaskResult.FAILED), recovered.outcome());
                    assertEquals(1, conclusionPrompts.get());
                    var next = tasks.submit(new TaskRequest("post-conclusion-probe", "Same server admission after failed preparation", List.of(server.id()), true),
                        task -> new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty()));
                    assertEquals(TaskResult.SUCCEEDED, next.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
                    return;
                }
                if (Set.of("EXTRA_RELEASED_OPERATION", "EXTRA_RESOURCE", "DECLINE", "SUCCEEDED").contains(scenario)) assertEquals(1, conclusionPrompts.get());
                if (scenario.equals("CONCLUDE_CLOSE")) assertEquals(0, conclusionPrompts.get());
                registration.close();
                // Local fixture teardown only, after production retention assertions; no remote operation is represented.
                try (var teardown = tasks.registerRecovery(DeploymentRefFlow.TASK_TYPE, (previous, task) -> {
                    if (extraResource.get() != null) extraResource.get().close();
                    tasks.findCheckpoint(task, DeploymentStandardSetupActionLog.KEY).orElseThrow().close();
                    tasks.findCheckpoint(task, DeploymentPublishPolicyFacts.KEY).orElseThrow().close();
                    return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(problem("TEST_FIXTURE_TEARDOWN")));
                })) {
                    assertEquals(TaskState.ENDED, runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20, TimeUnit.SECONDS).state());
                }
            }
        }
    }

    private String attachment(DeploymentToolchainDiagnostics.Request request) {
        String status = Set.of("SUCCEEDED", "FAILED").contains(scenario) ? scenario
            : Set.of("CONCLUDE", "EXTRA_RELEASED_OPERATION", "EXTRA_RESOURCE", "DECLINE", "CONCLUDE_CLOSE").contains(scenario) ? "SUCCEEDED" : "UNKNOWN";
        var value = new ObjectMapper().createObjectNode().put("formatVersion", 1).put("operationId", OPERATION.toString())
            .put("taskId", request.taskId()).put("serverId", request.serverId()).put("fingerprintSha256", request.fingerprintSha256())
            .put("planRevision", request.planRevision()).put("requestSha256", scenario.equals("ATTACHMENT") ? "f".repeat(64) : request.sha256())
            .put("preparationStatus", status).putNull("failureCategory").put("stage", "preparing").put("executable", "apt-get")
            .put("timedOut", false).put("cancelled", false).put("stdout", "bounded original stdout").put("stderr", "bounded original stderr")
            .put("stdoutTruncated", false).put("stderrTruncated", false);
        if (status.equals("UNKNOWN")) value.putNull("actualExitCode"); else value.put("actualExitCode", status.equals("SUCCEEDED") ? 0 : 100);
        if (scenario.equals("LARGE")) { value.put("stdout", "\u0001".repeat(16384)); value.put("stderr", "\u0002".repeat(16384)); }
        return value + "\n";
    }

    private static Problem problem(String event) { return new Problem(ProblemCode.UNKNOWN, event, "Controlled original diagnostic fixture", Map.of(), Optional.empty()); }
    private static OperationResult<Void> unknown() { return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(problem("TEST_PREPARATION_UNKNOWN"))); }
}
