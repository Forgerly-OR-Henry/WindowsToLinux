package gold.debug.windowstolinux.mod.deployment.service.standard;

import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskHandle;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.app.db.ai.StoredAiProviderProfile;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.assistance.DeploymentAssistedAdvisor;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentReviewField;
import gold.debug.windowstolinux.mod.deployment.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Uses real HTTP and original CLASS/H2 capabilities for source advice and explicit manual continuation. / 经真实 HTTP 与原 CLASS/H2 能力验证来源建议及明确人工继续。 */
class DeploymentRetainedAssistanceContractsTest extends DeploymentRuntimeFixture {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<ActionIntent> approvals = new CopyOnWriteArrayList<>();

    private boolean acceptManual;

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken token) {
                assertEquals("deployment.assisted.manual-continuation", intent.type());
                assertEquals(OWNER, task.ownerId());
                assertEquals(List.of("server"), intent.serverIds());
                assertEquals("server", intent.target());
                assertTrue(intent.revision().matches("[0-9a-f]{64}"));
                assertEquals("phase=PREFLIGHT\nsource=" + intent.revision() + "\nproblem=AI_HTTP_REJECTED",
                    intent.exactAction());
                approvals.add(intent);
                return acceptManual;
            }

            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerRef server,
                                                           TaskSnapshot task, CancelToken token) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskSnapshot task,
                                                       CancelToken token) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError();
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken token) {
                throw new AssertionError();
            }
        };
    }

    @Test
    void sourceAnalysisUsesLanguageRoleAndObservedEvidenceWithoutReplacingHumanInputOrSendingSecrets()
        throws Exception {
        source();
        enable();
        try (var http = new ModelPeer(false); var runtime = open()) {
            start(runtime, true);
            configure(runtime, http);
            var context = runtime.modContexts().get(OWNER).context();
            var failures = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest("legacy-assistance-role",
                "Inspect original source evidence", List.of("server"), true), task -> {
                try {
                    var selected = context.file().selectDirectory("Original source", task);
                    try (var material = new DeploymentSourcePreparer(context.file()).prepare(selected, 65536,
                        100, task)) {
                        assertFalse(Files.exists(material.snapshot().directory().resolve(".env")));
                        var advisor = new DeploymentAssistedAdvisor(context);
                        advisor.humanAnswers(Map.of("app/port", "9090"));
                        var browser = new DeploymentSourceBrowser(material.snapshot().directory(),
                            material.snapshot().sha256());
                        var advice = advisor.analyze(browser, fields(),
                            Map.of("component", "app", "projectType", "NODE_SERVICE"), task);
                        assertEquals(Map.of("app/version", "21"), advice.values());
                        assertEquals(Map.of("source/0", "README.md:0@" + material.snapshot().sha256()),
                            advice.locations());
                        assertTrue(advice.unresolved().isEmpty());
                        assertTrue(advisor.humanSupplied("app/port"));
                        assertEquals(2, http.requests.size());
                        var observed = JSON.readTree(
                            http.requests.get(1).path("messages").get(1).path("content").textValue());
                        assertEquals("source/0",
                            observed.path("observations").get(0).path("reference").textValue());
                        assertTrue(observed.path("observations").get(0).path("content").textValue()
                            .contains("Node service requires version 21."));
                        assertEquals(material.snapshot().sha256(), observed.path("sourceRevision").textValue());
                        for (var sent : http.requests) {
                            assertEquals("fixture/selected", sent.path("model").textValue());
                            assertEquals(1200, sent.path("max_tokens").intValue());
                            assertEquals("json_object", sent.path("response_format").path("type").textValue());
                            assertEquals("system", sent.path("messages").get(0).path("role").textValue());
                            assertTrue(sent.path("messages").get(0).path("content").textValue()
                                .startsWith("You are a bounded read-only deployment analyst."));
                            assertEquals("user", sent.path("messages").get(1).path("role").textValue());
                            var data = JSON.readTree(sent.path("messages").get(1).path("content").textValue());
                            assertEquals(List.of("app/version"), ids(data.path("fields")));
                            assertFalse(sent.toString().contains("NEVER_SEND_SECRET"));
                            assertFalse(sent.toString().contains("9090"));
                        }
                    }
                    return known(Outcome.SUCCEEDED);
                } catch (Exception | AssertionError failure) {
                    failures.set(failure);
                    throw failure;
                }
            });
            assertEquals(Outcome.SUCCEEDED,
                handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS).outcome(),
                () -> String.valueOf(failures.get()));
            if (failures.get() != null)
                throw new AssertionError("Original role/evidence callback failed", failures.get());
            http.assertHealthy();
            assertEquals(List.of("Bearer fixture-selected-key", "Bearer fixture-selected-key"), http.authorization);
            assertTrue(approvals.isEmpty());
            assertNoPublicationOrResources(runtime);
        }
    }

    @Test
    void invalidModelChoiceCannotReplaceDeterministicOrHumanInputs() throws Exception {
        verifyReadBoundary(true);
    }

    @Test
    void actualReadPayloadExcludesPasswordTokenBearerAndApiKeyValues() throws Exception {
        verifyReadBoundary(false);
    }

    private void verifyReadBoundary(boolean invalidChoice) throws Exception {
        source();
        Files.writeString(selectedSourceDirectory().resolve("README.md"),
            "Node service requires version 21.\npassword=READ_PASSWORD_VALUE\ntoken=READ_TOKEN_VALUE\n"
                + "Authorization: Bearer READ_BEARER_VALUE\napi_key=READ_API_VALUE\n");
        enable();
        try (var http = new ModelPeer(false, invalidChoice ? "99" : "21"); var runtime = open()) {
            start(runtime, true);
            configure(runtime, http);
            var context = runtime.modContexts().get(OWNER).context();
            var originalFields = fields();
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(
                new TaskRequest("source-read-boundary", "Review original bounded source", List.of("server"), true),
                task -> {
                    try {
                        var selected = context.file().selectDirectory("Original source", task);
                        try (var material = new DeploymentSourcePreparer(context.file()).prepare(selected, 65536,
                            100, task)) {
                            var advisor = new DeploymentAssistedAdvisor(context);
                            advisor.humanAnswers(Map.of("app/port", "9090"));
                            var browser = new DeploymentSourceBrowser(material.snapshot().directory(),
                                material.snapshot().sha256());
                            if (invalidChoice)
                                assertThrows(SecurityException.class, () -> advisor.analyze(browser, originalFields,
                                    Map.of("component", "app", "projectType", "NODE_SERVICE"), task));
                            else
                                assertEquals(Map.of("app/version", "21"),
                                    advisor.analyze(browser, originalFields,
                                            Map.of("component", "app", "projectType", "NODE_SERVICE"), task)
                                        .values());
                            assertEquals(fields(), originalFields);
                            assertTrue(advisor.humanSupplied("app/port"));
                        }
                        return known(Outcome.SUCCEEDED);
                    } catch (Exception | AssertionError caught) {
                        failure.set(caught);
                        throw caught;
                    }
                });
            assertEquals(Outcome.SUCCEEDED,
                handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS).outcome(),
                () -> String.valueOf(failure.get()));
            if (failure.get() != null)
                throw new AssertionError("Original READ boundary failed", failure.get());
            http.assertHealthy();
            assertEquals(2, http.requests.size());
            if (!invalidChoice)
                for (var sent : http.requests)
                    for (var sensitive : List.of("READ_PASSWORD_VALUE", "READ_TOKEN_VALUE", "READ_BEARER_VALUE",
                        "READ_API_VALUE", "NEVER_SEND_SECRET", "9090"))
                        assertFalse(sent.toString().contains(sensitive), "Actual HTTP payload leaked " + sensitive);
            assertTrue(approvals.isEmpty());
            assertNoPublicationOrResources(runtime);
        }
    }

    @Test
    void knownHttpFailureCanContinueOnlyAfterExactHumanAcceptanceWithoutAnotherModelCall() throws Exception {
        verifyManualContinuation(true);
    }

    @ParameterizedTest(name = "manual continuation accepted={0}")
    @ValueSource(booleans = {false})
    void knownHttpFailureRequiresExactHumanDecisionAndNeverRetriesAfterManualContinuation(boolean accepted)
        throws Exception {
        verifyManualContinuation(accepted);
    }

    private void verifyManualContinuation(boolean accepted) throws Exception {
        acceptManual = accepted;
        source();
        enable();
        try (var http = new ModelPeer(true); var runtime = open()) {
            start(runtime, true);
            configure(runtime, http);
            var context = runtime.modContexts().get(OWNER).context();
            var failures = new AtomicReference<Throwable>();
            var phase = new AtomicReference<>("ADMITTED");
            var handle = context.tasks().submit(new TaskRequest("legacy-assistance-manual",
                "Review actual known model failure", List.of("server"), true), task -> {
                try {
                    var selected = context.file().selectDirectory("Original source", task);
                    phase.set("SOURCE_SELECTED");
                    try (var material = new DeploymentSourcePreparer(context.file()).prepare(selected, 65536,
                        100, task)) {
                        phase.set("SOURCE_PREPARED");
                        var advisor = new DeploymentAssistedAdvisor(context);
                        advisor.humanAnswers(Map.of("app/port", "9090"));
                        var prompt = new DeploymentStaticWorkflow.Prompt("app", "demo",
                            DeploymentProjectKind.NODE_SERVICE, Map.of("port", "9090"), fields());
                        var assistance = new DeploymentStaticAssistance(context,
                            new DeploymentStaticWorkflow(context, new DeploymentReferenceFlow(context)));
                        if (accepted) {
                            assertSame(prompt, assistance.prompt(new ServerRef("server"), prompt, advisor,
                                material, task));
                            assertEquals(prompt, assistance.prompt(new ServerRef("server"), prompt, advisor,
                                material, task));
                            assertEquals("9090", prompt.defaults().get("port"));
                            assertTrue(advisor.humanSupplied("app/port"));
                        } else {
                            assertThrows(CancellationException.class, () -> assistance
                                .prompt(new ServerRef("server"), prompt, advisor, material, task));
                            phase.set("HUMAN_REFUSAL_CONFIRMED");
                        }
                        assertEquals(1, approvals.size());
                        assertEquals(material.snapshot().sha256(), approvals.getFirst().revision());
                        assertEquals(1, http.requests.size(),
                            "Known HTTP rejection cannot select an unassigned inventory model or retry after manual consent");
                    }
                    phase.set("MATERIAL_CLOSED_RETURNING_" + (accepted ? "SUCCEEDED" : "CANCELLED"));
                    return known(accepted ? Outcome.SUCCEEDED : Outcome.CANCELLED);
                } catch (Exception | AssertionError failure) {
                    failures.set(failure);
                    throw failure;
                }
            });
            final OperationResult<Void> result;
            try {
                result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            } catch (TimeoutException unconfirmed) {
                throw new AssertionError(manualDiagnostic(runtime, handle, phase.get(), failures.get()), unconfirmed);
            }
            assertEquals(accepted ? Outcome.SUCCEEDED : Outcome.CANCELLED, result.outcome(),
                () -> String.valueOf(failures.get()));
            if (failures.get() != null)
                throw new AssertionError("Original manual continuation callback failed", failures.get());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            http.assertHealthy();
            assertNoPublicationOrResources(runtime);
        }
    }

    /** Captures only original task and non-secret resource evidence before the fixture closes. / 在夹具关闭前仅捕获原任务及非秘密资源依据。 */
    private String manualDiagnostic(PlatformRuntime runtime, TaskHandle<?> handle, String phase, Throwable failure)
        throws Exception {
        var diagnostic = new StringBuilder(
            "phase=" + phase + " callback=" + failure + " snapshot=" + handle.snapshot());
        try (var connection = runtime.persistence().database().openConnection()) {
            for (String sql : List.of("SELECT state,outcome,result_json FROM platform_task WHERE task_id=?",
                "SELECT lease_id,resource_kind,resource_id,owner_id,access_mode,released_at FROM platform_task_resource WHERE task_id=? ORDER BY lease_id")) {
                try (var statement = connection.prepareStatement(sql)) {
                    statement.setObject(1, handle.id());
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            diagnostic.append('\n');
                            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                                diagnostic.append(rows.getMetaData().getColumnName(i)).append('=')
                                    .append(rows.getString(i)).append(' ');
                        }
                    }
                }
            }
        }
        diagnostic.append("\napprovals=").append(approvals.size());
        return diagnostic.toString();
    }

    private void source() throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("README.md"), "Node service requires version 21.\n");
        Files.writeString(selectedSourceDirectory().resolve(".env"), "PASSWORD=NEVER_SEND_SECRET\n");
    }

    private static List<DeploymentReviewField> fields() {
        return List.of(new DeploymentReviewField("app/version", "version", "help", "", List.of("17", "21")),
            new DeploymentReviewField("app/port", "port", "help", "", List.of()),
            new DeploymentReviewField("app/password", "password", "help", "NEVER_SEND_SECRET", List.of()));
    }

    private static List<String> ids(JsonNode fields) {
        var result = new ArrayList<String>();
        fields.forEach(field -> result.add(field.path("id").textValue()));
        return List.copyOf(result);
    }

    private static void configure(PlatformRuntime runtime, ModelPeer http) throws Exception {
        runtime.servers().saveServerProfile(
            ServerProfile
                .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
            "fixture-password".toCharArray());
        for (String id : List.of("selected", "unassigned")) {
            String credential = "ai/legacy/" + id;
            try (var operation = runtime.security().enterOperation();
                 var store = runtime.security().openCredentials()) {
                char[] key = ("fixture-" + id + "-key").toCharArray();
                try {
                    store.save(credential, key);
                } finally {
                    Arrays.fill(key, '\0');
                }
            }
            runtime.persistence().aiProfiles().saveVerified(
                new StoredAiProviderProfile(id, http.endpoint(), "fixture/" + id, credential), id, Instant.now(),
                AiCapabilityType.TEXT);
            runtime.persistence().aiProfiles().saveOutputLimit(id, 1600);
        }
        runtime.persistence().aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT,
            List.of(new AiPurposeAssignment("selected", true)));
        assertEquals(2, runtime.persistence().aiProfiles().listConfigured().size());
    }

    private void assertNoPublicationOrResources(PlatformRuntime runtime) throws Exception {
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_execution")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getLong(1));
        }
        assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
    }

    private static OperationResult<Void> known(Outcome outcome) {
        return new OperationResult<>(outcome, Optional.empty(), Optional.empty());
    }

    /** Actual loopback HTTP responses remain distinct from deployment protocol interpretation. / 真实回环 HTTP 响应与部署协议解析保持分离。 */
    private static final class ModelPeer implements AutoCloseable {
        private final HttpServer http;

        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

        private final List<String> authorization = new CopyOnWriteArrayList<>();

        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private ModelPeer(boolean reject) throws Exception {
            this(reject, "21");
        }

        private ModelPeer(boolean reject, String candidate) throws Exception {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(executor);
            http.createContext("/chat", exchange -> {
                try {
                    var sent = JSON.readTree(exchange.getRequestBody());
                    requests.add(sent);
                    authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    if (reject) {
                        exchange.sendResponseHeaders(503, -1);
                        return;
                    }
                    var facts = JSON.readTree(sent.path("messages").get(1).path("content").textValue());
                    var response = JSON.createObjectNode().put("sourceRevision",
                        facts.path("sourceRevision").textValue());
                    if (requests.size() == 1) {
                        response.put("action", "READ");
                        response.putObject("arguments").put("path", "README.md").put("offset", 0).put("limit", 10);
                    } else {
                        assertEquals(2, requests.size());
                        response.put("action", "ADVISE");
                        var arguments = response.putObject("arguments").put("summary",
                            "Observed version in original README");
                        arguments.putArray("unresolved");
                        arguments.putArray("suggestions").addObject().put("field", "app/version")
                            .put("candidate", candidate).putArray("evidence").add("source/0");
                    }
                    var body = JSON.createObjectNode();
                    var choice = body.putArray("choices").addObject().put("finish_reason", "stop");
                    choice.putObject("message").put("role", "assistant").put("content", response.toString());
                    body.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 20);
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Exception | AssertionError error) {
                    failure.set(error);
                } finally {
                    exchange.close();
                }
            });
            http.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + http.getAddress().getPort() + "/chat";
        }

        private void assertHealthy() {
            if (failure.get() != null)
                throw new AssertionError("Actual model HTTP peer failed", failure.get());
        }

        @Override
        public void close() throws Exception {
            http.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
