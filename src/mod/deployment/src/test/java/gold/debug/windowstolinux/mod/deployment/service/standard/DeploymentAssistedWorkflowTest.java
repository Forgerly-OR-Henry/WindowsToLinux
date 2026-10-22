package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiRole;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferProgress;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStall;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.DeploymentStandardEntryFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentPreparedDatabase;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Uses the actual page workflow, frozen source and original H2 task for bounded assistance. / 使用真实页面流程、冻结来源及原 H2 任务验证有界辅助。 */
class DeploymentAssistedWorkflowTest extends DeploymentRuntimeFixture {
    @Test
    void actualAssistedPublisherForwardsPartialReceiptWithoutFinishingPublication() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var finished = new CompletableFuture<Void>();
            var handle = actual.tasks().submit(new gold.debug.windowstolinux.appapi.task.model.TaskRequest(
                "assisted-partial-local", "Controlled partial command wiring", List.of(), false), task -> {
                try {
                    var server = new ServerId("controlled-partial");
                    var command = new AtomicReference<Map<String, Object>>();
                    var sends = new AtomicInteger();
                    var queries = new AtomicInteger();
                    var turns = new AtomicInteger();
                    var grants = new HashMap<UUID, String>();
                    var json = new ObjectMapper();
                    var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class}, (proxy, method, args) -> {
                        if (method.getName().equals("configurationRevision")) return "a".repeat(64);
                        assertEquals("call", method.getName());
                        var request = (AiRequest) args[0];
                        assertEquals(AiPurpose.LANGUAGE, request.purpose());
                        int turn = turns.getAndIncrement();
                        List<AiToolCall> calls;
                        if (turn == 0) {
                            calls = List.of(new AiToolCall("partial-command", "supplement", json.writeValueAsString(Map.of(
                                "script", "printf controlled", "seconds", 1, "missingCapability", "controlled-gap", "reason", "Controlled partial diagnostic"))));
                        } else {
                            var feedback = request.messages().stream().flatMap(message -> message.toolResult().stream()).reduce((first, last) -> last).orElseThrow();
                            if (turn == 1) {
                                assertTrue(feedback.text().contains("EXIT=0"));
                                assertTrue(feedback.text().contains("OUTPUT_TRUNCATED=1"));
                                assertTrue(feedback.text().contains("not proof of publication, health, sealed artifacts or acceptance"));
                                assertTrue(feedback.text().contains("HEALTHY=1"));
                                calls = List.of(new AiToolCall("unverified-finish", "finish", "{}"));
                            } else {
                                assertTrue(feedback.error());
                                calls = List.of();
                            }
                        }
                        return success(new AiResponse("", "controlled", calls.isEmpty() ? "stop" : "tool_calls",
                            new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), calls, Optional.empty()));
                    });
                    var review = (gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview) Proxy.newProxyInstance(
                        gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview.class.getClassLoader(),
                        new Class<?>[]{gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview.class}, (proxy, method, args) -> {
                            assertEquals("approveCommand", method.getName());
                            assertSame(task, args[2]);
                            var intent = (ActionIntent) args[0];
                            var grant = new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID());
                            grants.put(grant.id(), intent.exactAction());
                            return success(grant);
                        });
                    var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                        if (method.getName().equals("ai")) return ai;
                        if (method.getName().equals("interaction")) return review;
                        try {
                            return method.invoke(actual, args);
                        } catch (InvocationTargetException failed) {
                            throw failed.getCause();
                        }
                    });
                    var remote = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                        if (method.getName().equals("facts"))
                            return new gold.debug.windowstolinux.appapi.linux.session.RemoteFacts("linux", "x86_64", "SHA256:controlled");
                        assertEquals("execute", method.getName());
                        var request = (CommandRequest) args[0];
                        var grant = (gold.debug.windowstolinux.appapi.task.action.ApprovalGrant) args[1];
                        assertEquals(RemoteActions.command(server, "a".repeat(64), request).exactAction(), grants.remove(grant.id()));
                        if (request.script().equals(gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.LAUNCHER)) {
                            command.set(gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.executableFacts(new String(request.stdin(), StandardCharsets.UTF_8)));
                            assertEquals(task.id().toString(), command.get().get("task"));
                            sends.incrementAndGet();
                            return success(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0), "HEALTHY=1\n", "partial stderr", true));
                        }
                        assertEquals(0, request.stdin().length);
                        assertTrue(request.script().contains((String) command.get().get("operation")));
                        queries.incrementAndGet();
                        String receipt = gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.header(task.id(),
                            UUID.fromString((String) command.get().get("operation")), (String) command.get().get("bodySha256")) + "EXIT=0\n";
                        return success(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0), receipt, "", false));
                    });
                    var stopped = assertThrows(IllegalStateException.class, () ->
                        gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish.DeploymentAssistedDeployment.run(context, () -> remote,
                            server, task, () -> {
                                throw new AssertionError("No inspection plan is needed for this controlled command");
                            }, Map::of, Map::of));
                    assertTrue(stopped.getMessage().contains("text cannot finish deployment"));
                    assertEquals(3, turns.get());
                    assertEquals(1, sends.get());
                    assertEquals(1, queries.get());
                    var checkpoint = actual.tasks().findCheckpoint(task, "deployment-model-commands").orElseThrow().snapshot();
                    assertEquals("ENDED", json.readTree(checkpoint.payload()).path("commands").get(0).path("phase").asText());
                    assertTrue(checkpoint.released());
                    try (var connection = actual.db().openConnection(task); var query = connection.createStatement();
                         var rows = query.executeQuery("SELECT COUNT(*) FROM managed_application WHERE graph_ready=TRUE")) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1));
                    }
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError failed) {
                    failure.set(failed);
                    throw failed;
                } finally {
                    finished.complete(null);
                }
            });
            finished.get(30, TimeUnit.SECONDS);
            if (failure.get() != null)
                throw new AssertionError("Actual assisted partial-command wiring failed", failure.get());
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
        }
    }

    enum ScenarioType {
        REFUSAL, PUBLICATION, CONNECT_DRIFT, SOURCE_REJECTED, SOURCE_UNSAFE
    }

    private boolean quotaScenario, quotaAssisted = true, quotaAutomatic;

    private long quotaHostBytes = 1000000000L * 4096;

    private int quotaPadding;

    private long confirmedQuota;

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,0,20", "true,67108864,20", "true,0,3", "false,0,20"})
    void realStaticPathUsesTheConfirmedDynamicQuota(boolean automatic, int padding, int hostGiB) throws Exception {
        runQuota(false, automatic, padding, hostGiB);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,0,20", "true,67108864,20", "false,0,20"})
    void independentAssistedPathUsesTheConfirmedDynamicQuota(boolean automatic, int padding, int hostGiB) throws Exception {
        runQuota(true, automatic, padding, hostGiB);
    }

    private void runQuota(boolean assisted, boolean automatic, int padding, int hostGiB) throws Exception {
        quotaScenario = true;
        quotaAssisted = assisted;
        quotaAutomatic = automatic;
        quotaPadding = padding;
        quotaHostBytes = hostGiB * 1024L * 1024 * 1024;
        verifyScenario(ScenarioType.PUBLICATION);
    }

    private final AtomicInteger calls = new AtomicInteger();

    private final AtomicInteger comparisonCalls = new AtomicInteger();

    private final AtomicInteger toolCalls = new AtomicInteger();

    private final AtomicInteger reviews = new AtomicInteger();

    private final AtomicInteger nativeCalls = new AtomicInteger();

    private final FixedReviewFixture fixedReviews = new FixedReviewFixture();

    private final List<String> approvals = new CopyOnWriteArrayList<>();

    private final AtomicReference<String> revision = new AtomicReference<>("a".repeat(64));

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict c, ServerId s, TaskSnapshot t,
                                                           CancelToken token) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall c, ServerId s, TaskSnapshot t,
                                                       CancelToken token) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress p) {
                throw new AssertionError();
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot t, CancelToken token) {
                throw new AssertionError();
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken token) {
                if (!quotaScenario || quotaAssisted) fixedReviews.beforeHost(intent, task.id());
                approvals.add(intent.type());
                return true;
            }
        };
    }

    /** AI proposals remain human-editable, and a confirmed pure form failure gets exactly one approved re-review. / AI 建议仍由人工编辑，已确认纯表单失败恰好获得一次批准重审。
     * @throws Exception if actual workflow or source validation fails / 实际流程或来源校验失败时 */
    @ParameterizedTest
    @EnumSource(value = ScenarioType.class, names = {"PUBLICATION", "CONNECT_DRIFT"})
    void sourceAdviceHumanPriorityAndPurePreflightRetryShareOneOriginalTask(ScenarioType scenario) throws Exception {
        verifyScenario(scenario);
    }

    @Test
    void refusedPublicationPreservesOriginalTaskAndHumanPriority() throws Exception {
        verifyScenario(ScenarioType.REFUSAL);
    }

    @Test
    void rejectedSourceModelEndsTheOriginalTaskWithoutSshOrRetainedPolicy() throws Exception {
        verifyScenario(ScenarioType.SOURCE_REJECTED);
    }

    @Test
    void unsafeModelSourcePathFailsWithoutSshOrRetainedPolicy() throws Exception {
        verifyScenario(ScenarioType.SOURCE_UNSAFE);
    }

    private void verifyScenario(ScenarioType scenario) throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("go.mod"), "module example.test/demo\n\ngo 1.24\n");
        Files.writeString(selectedSourceDirectory().resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
        Files.writeString(selectedSourceDirectory().resolve("main.go"), "package main\nfunc main() {}\n");
        Files.writeString(selectedSourceDirectory().resolve("README.md"), "HTTP service default port is 8080.\n");
        Files.writeString(selectedSourceDirectory().resolve("windowstolinux-application.properties"),
            "version=1\nhealth.mode=TCP\n");
        if (quotaScenario && quotaPadding > 0)
            Files.write(selectedSourceDirectory().resolve("payload.bin"), new byte[quotaPadding]);
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            var ai = ai(scenario);
            var peer = new DeploymentAssistedTestPeer();
            if (quotaScenario) peer.availableBytes = quotaHostBytes;
            var connections = new AtomicInteger();
            LinuxService linux = new LinuxService() {
                public RemoteSession connect(gold.debug.windowstolinux.appapi.linux.session.RemoteTarget expected, TaskContext task) throws ApiException {
                    return fixedReviews.connect(expected, task, actual, this);
                }

                public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                    connections.incrementAndGet();
                    var remote = peer.session(task, () -> {
                        if (scenario == ScenarioType.CONNECT_DRIFT)
                            revision.set("b".repeat(64));
                    });
                    return quotaScenario && !quotaAssisted ? remote : fixedReviews.session(remote, task);
                }

                public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                    return List.of();
                }
            };
            var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("ai"))
                        return ai;
                    if (method.getName().equals("linux"))
                        return linux;
                    try {
                        return method.invoke(actual, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var workflow = new DeploymentStandardEntryFixture(bound, new DeploymentRefFlow(bound));
            var page = DeploymentStandardEntryFixture.pages(bound, workflow);
            var original = new AtomicReference<UUID>();
            var reviewer = new DeploymentStandardInputs.Reviewer() {
                public OperationResult<DeploymentStandardInputs.Selection> select(ServerId server, String sha,
                                                                                  List<DiscoveredProjectPart> found, TaskContext task) {
                    original.set(task.id());
                    assertEquals(1, found.size());
                    return success(
                        new DeploymentStandardInputs.Selection(
                            found.getFirst().id(), Map
                            .of(found.getFirst().id(),
                                new DeploymentStandardInputs.Choice(
                                    DeploymentProjectKind.GO_SERVICE, Set.of())),
                            Optional.empty()));
                }

                public OperationResult<Map<String, DeploymentStandardInputs.Answers>> review(ServerId server,
                                                                                             String sha, List<DeploymentStandardInputs.Prompt> prompts, TaskContext task) {
                    assertEquals(original.get(), task.id());
                    int round = reviews.getAndIncrement();
                    var prompt = prompts.getFirst();
                    if (!quotaScenario || quotaAssisted)
                        assertEquals("8080", prompt.fields().stream().filter(field -> field.id().endsWith("/port"))
                            .findFirst().orElseThrow().value());
                    var values = new HashMap<>(prompt.defaults());
                    values.put("primary", prompt.componentId());
                    values.put("secondary", "main.go");
                    values.put("version", "1.24");
                    values.put("healthMode", "TCP");
                    values.put("port", quotaScenario && !quotaAssisted || round > 0 ? "9090" : "0");
                    values.put("exposure", "INTERNAL");
                    return success(Map.of(prompt.componentId(), new DeploymentStandardInputs.Answers(values, List.of(),
                        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().limits(), false, true,
                        quotaScenario && quotaAutomatic ? gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request.automatic(0, 0)
                            : gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request.explicit(4294967296L), Optional.empty())));
                }

                public OperationResult<Boolean> confirm(ServerId server, String sha, Map<String, String> archives,
                                                        Map<String, DeploymentStandardInputs.Answers> answers,
                                                        Map<String, DeploymentPreparedDatabase> databases,
                                                        Optional<DeploymentStandardInputs.GitReview> git, TaskContext task) {
                    assertEquals(original.get(), task.id());
                    assertEquals(1, archives.size());
                    assertEquals("9090", answers.values().iterator().next().values().get("port"));
                    if (quotaScenario) {
                        var answer = answers.values().iterator().next();
                        confirmedQuota = answer.limits().maxWorkspaceBytes();
                        assertEquals(confirmedQuota, answer.workspaceSelection().orElseThrow().bytes());
                        assertEquals(quotaAutomatic ? "AUTO" : "EXPLICIT", answer.workspaceSelection().orElseThrow().request().mode().name());
                        if (!quotaAutomatic) assertEquals(4294967296L, confirmedQuota);
                        else if (quotaPadding > 0) assertTrue(confirmedQuota > 3355443200L);
                        else if (quotaHostBytes == 3 * 1024L * 1024 * 1024)
                            assertEquals(2 * 1024L * 1024 * 1024, confirmedQuota);
                        else assertEquals(3355443200L, confirmedQuota);
                    }
                    return success(scenario != ScenarioType.REFUSAL);
                }
            };
            var handle = page.submit(new ServerId("server"),
                new DeploymentStandardInputs.Draft("demo", quotaScenario ? 100 * 1024 * 1024 : 65536, 100, quotaScenario ? 2 * 1024 * 1024 : 65536), Optional.empty(), reviewer, !quotaScenario || quotaAssisted);
            var result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            if (scenario == ScenarioType.SOURCE_REJECTED || scenario == ScenarioType.SOURCE_UNSAFE) {
                assertEquals(TaskResult.FAILED, result.outcome());
                assertEquals(scenario == ScenarioType.SOURCE_REJECTED ? "AI_HTTP_REJECTED" : "DEPLOYMENT_STATIC_SOURCE_REJECTED",
                    result.problem().orElseThrow().eventCode());
                assertEquals(0, connections.get());
                assertEquals(0, reviews.get());
                assertEquals(0, toolCalls.get());
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                return;
            }
            assertEquals(
                scenario == ScenarioType.REFUSAL
                    ? TaskResult.CANCELLED
                    : scenario == ScenarioType.PUBLICATION ? TaskResult.SUCCEEDED : TaskResult.FAILED,
                result.outcome(), result.toString());
            assertEquals(handle.id(), original.get());
            assertEquals(quotaScenario && !quotaAssisted ? 1 : 2, reviews.get());
            assertEquals(quotaScenario && !quotaAssisted ? 0 : 3, calls.get());
            assertEquals(quotaScenario && !quotaAssisted ? 0 : 2, comparisonCalls.get(), "Every assisted source is independently scanned");
            assertEquals(scenario == ScenarioType.PUBLICATION && (!quotaScenario || quotaAssisted) ? 3 : 0, toolCalls.get());
            assertEquals(quotaScenario && !quotaAssisted ? 0 : 1, approvals.stream().filter("deployment.assisted.retry"::equals).count());
            if (scenario == ScenarioType.REFUSAL) {
                assertEquals(0, connections.get());
                assertEquals(0, fixedReviews.sends.get());
            } else {
                if (!quotaScenario || quotaAssisted) assertTrue(fixedReviews.sends.get() > 0);
                if (quotaScenario) {
                    assertEquals(List.of(confirmedQuota), peer.createdWorkspaceBytes);
                    assertEquals(1, peer.buildScripts.size());
                    assertTrue(peer.buildScripts.getFirst().contains(Long.toString(confirmedQuota)), "Build envelope keeps exact approved disk budget");
                }
                try (var access = scope(runtime, true);
                     var connection = access.openConnection();
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery(
                         "SELECT platform_task_id,mode,outcome,business_committed FROM deployment_execution")) {
                    assertTrue(rows.next());
                    assertEquals(handle.id(), rows.getObject(1, UUID.class));
                    assertEquals(quotaScenario && !quotaAssisted ? "STATIC" : "ASSISTED", rows.getString(2));
                    assertEquals(scenario == ScenarioType.PUBLICATION ? "SUCCEEDED" : "FAILED", rows.getString(3));
                    assertEquals(scenario == ScenarioType.PUBLICATION, rows.getBoolean(4));
                    assertFalse(rows.next());
                }
                assertEquals(scenario == ScenarioType.PUBLICATION ? 1 : 0, peer.publications.get());
                assertEquals(scenario == ScenarioType.PUBLICATION ? 3 : 1, connections.get(),
                    "Frozen guard rejects before admitting a second SSH session");
                if (scenario == ScenarioType.CONNECT_DRIFT)
                    assertEquals(ProblemCode.ACCESS_DENIED, result.problem().orElseThrow().code());
            }
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    private AiService ai(ScenarioType scenario) {
        return new AiService() {
            @Override
            public gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse> call(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest request, gold.debug.windowstolinux.appapi.task.run.TaskContext task) {
                throw new AssertionError("Voice is outside this fixture");
            }

            @Override
            public gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse> call(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest request, String expectedRevision,
                gold.debug.windowstolinux.appapi.task.run.TaskContext task) {
                throw new AssertionError("Voice is outside this fixture");
            }

            @Override
            public gold.debug.windowstolinux.appapi.ai.voice.VoiceSession openVoice(
                gold.debug.windowstolinux.appapi.ai.voice.VoiceOptions options, gold.debug.windowstolinux.appapi.ai.voice.VoiceListener listener,
                gold.debug.windowstolinux.appapi.task.run.TaskContext task) {
                throw new AssertionError("Voice is outside this fixture");
            }

            public String configurationRevision(AiPurpose purpose, TaskContext task) {
                assertTrue(Set.of(AiPurpose.LANGUAGE, AiPurpose.APPROVAL).contains(purpose));
                fixedReviews.sameTask(task);
                return revision.get();
            }

            public OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException {
                return call(request, "a".repeat(64), task);
            }

            public OperationResult<AiResponse> call(AiRequest request, String expected, TaskContext task) {
                assertEquals("a".repeat(64), expected);
                JsonNode facts;
                try {
                    facts = new ObjectMapper().readTree(request.messages().getLast().text());
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                fixedReviews.sameTask(task);
                if (request.purpose() == AiPurpose.APPROVAL) {
                    fixedReviews.review(request, facts, task);
                    return nativeResponse(request, "controlled-approval", "submit_result",
                        "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Exact controlled original action\"}");
                }
                assertEquals(AiPurpose.LANGUAGE, request.purpose());
                if (facts.has("projectTypes")) {
                    int scan = comparisonCalls.getAndIncrement();
                    if (scenario == ScenarioType.SOURCE_REJECTED)
                        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(
                            new gold.debug.windowstolinux.appapi.task.result.Problem(ProblemCode.IO_FAILURE,
                                "AI_HTTP_REJECTED", "The selected provider rejected the request",
                                Map.of("httpStatus", "400"), Optional.empty())));
                    String selected = scenario == ScenarioType.SOURCE_UNSAFE ? "list" : scan == 0 ? "read" : "identify";
                    String arguments = scenario == ScenarioType.SOURCE_UNSAFE
                        ? "{\"path\":\"../\",\"offset\":0,\"limit\":10}"
                        : scan == 0
                        ? "{\"path\":\"go.mod\",\"offset\":0,\"limit\":10}"
                        : "{\"components\":[{\"id\":\"app\",\"root\":\".\",\"type\":\"GO_SERVICE\",\"evidence\":[\"go.mod\"]}]}";
                    return nativeResponse(request, "controlled-scan", selected, arguments);
                }
                if (request.tools().stream().anyMatch(tool -> Set.of("prepare", "build", "publish").contains(tool.name()))) {
                    int turn = toolCalls.getAndIncrement();
                    String selected = List.of("prepare", "build", "publish").get(turn);
                    assertTrue(facts.path("state").isObject());
                    return nativeResponse(request, "controlled-tools", selected, "{}");
                }
                int turn = calls.getAndIncrement();
                String output;
                if (turn == 2) {
                    assertEquals("PREFLIGHT", facts.path("phase").textValue());
                    assertTrue(facts.path("fields").isEmpty(), "Human answers cannot be replaced by model suggestions");
                    output = "{\"decision\":\"RETRY\",\"reason\":\"Ask the human to correct the rejected port\",\"suggestions\":{}}";
                } else {
                    String revision = facts.path("sourceRevision").textValue();
                    String field = "";
                    for (var offered : facts.path("fields"))
                        if (offered.path("id").textValue().endsWith("/port"))
                            field = offered.path("id").textValue();
                    assertFalse(field.isBlank());
                    output = "{\"action\":\"" + (turn == 0 ? "READ" : "ADVISE") + "\",\"sourceRevision\":\"" + revision
                        + "\",\"arguments\":"
                        + (turn == 0
                        ? "{\"path\":\"README.md\",\"offset\":0,\"limit\":10}"
                        : "{\"suggestions\":[{\"field\":\"" + field
                        + "\",\"candidate\":\"8080\",\"evidence\":[\"source/0\"]}],\"summary\":\"README default\",\"unresolved\":[]}")
                        + "}";
                }
                return nativeResponse(request, "controlled", "submit_result", output);
            }
        };
    }

    private OperationResult<AiResponse> nativeResponse(AiRequest request, String model, String name, String arguments) {
        assertTrue(request.tools().stream().anyMatch(tool -> tool.name().equals(name)), "The response uses an actually offered native tool");
        return success(new AiResponse("", model, "tool_calls",
            new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(new AiToolCall("controlled_call_" + nativeCalls.getAndIncrement(), name, arguments)), Optional.empty()));
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    /** Captures finite actual reviews and checks them before real host questions and controlled sends. / 捕获有限实际审核，在真实宿主问题和受控发送前校验。 */
    static final class FixedReviewFixture {
        private static final Set<String> OPERATIONS = Set.of("deployment.helper.probe.v1",
            "deployment.candidate.host-probe.v1", "deployment.candidate.capacity.v1", "deployment.candidate.create.v1",
            "deployment.candidate.query.v1", "deployment.candidate.upload.v1", "deployment.candidate.verify.v1",
            "deployment.toolchain.prepare.v1", "deployment.toolchain.bind.v1", "deployment.build.preflight.v1",
            "deployment.toolchain.prepare.tracked.v1", "deployment.toolchain.bind.tracked.v1",
            "deployment.toolchain.observe.tracked.v1", "deployment.toolchain.cleanup.tracked.v1",
            "deployment.build.run.v1", "deployment.publication.snapshot.v1", "deployment.publication.original-status.v1",
            "deployment.publication.snapshot-discard.v1", "deployment.publication.config.v1", "deployment.publication.publish.v1",
            "deployment.publication.health.v1", "deployment.publication.inspect.v1", "deployment.publication.observe.v1",
            "deployment.publication.observe-restored.v1", "deployment.publication.rollback.v1", "deployment.publication.retain.v1",
            "deployment.publication.maintenance-end.v1", "deployment.publication.candidate-cleanup.v1",
            "deployment.assisted.retry", "deployment.assisted.environment-recovery", "deployment.assisted.build-review");

        private final AtomicReference<UUID> original = new AtomicReference<>();

        private final AtomicReference<String> policy = new AtomicReference<>("MANUAL_REVIEW");

        private final Map<String, Queue<JsonNode>> pending = new ConcurrentHashMap<>();

        private final AtomicReference<JsonNode> selectedSource = new AtomicReference<>();

        private record ArchiveOrigin(String snapshot, String relativeRoot) {
        }

        private final Map<ArchiveOrigin, JsonNode> sourceArchives = new ConcurrentHashMap<>();

        private final Map<String, ArchiveOrigin> archiveSnapshots = new ConcurrentHashMap<>(), archivePaths = new ConcurrentHashMap<>();

        private final Map<String, JsonNode> applicationSources = new ConcurrentHashMap<>(), candidateArchives = new ConcurrentHashMap<>();

        final AtomicInteger sends = new AtomicInteger();

        volatile Throwable lastFailure;

        void policy(String selected) {
            assertTrue(Set.of("MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL").contains(selected));
            policy.set(selected);
        }

        void sameTask(TaskContext task) {
            original.compareAndSet(null, task.id());
            assertEquals(original.get(), task.id(), "Every review retains the original platform task");
        }

        RemoteSession connect(gold.debug.windowstolinux.appapi.linux.session.RemoteTarget expected,
                              TaskContext task, ModContext actual, LinuxService controlled) throws ApiException {
            sameTask(task);
            assertEquals(task.id(), expected.taskId());
            var server = actual.servers().get(expected.server(), task);
            assertEquals(new ServerId("server"), expected.server());
            assertEquals(server.ref(), expected.server());
            assertEquals(server.host(), expected.host(), "A saved endpoint change must fail before the controlled connection");
            assertEquals(server.port(), expected.port());
            assertEquals(server.username(), expected.username());
            var session = controlled.connect(expected.server(), task);
            try {
                if (expected.fingerprint().isPresent() && !expected.fingerprint().orElseThrow().equals(session.facts().fingerprint()))
                    throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
                        gold.debug.windowstolinux.appapi.task.result.ProblemCode.ACCESS_DENIED, "DEPLOYMENT_TEST_WIRE_CHANGED",
                        "The controlled transport rejects a different actual wire before any command", Map.of(), Optional.of(task.id())));
                return session;
            } catch (ApiException failure) {
                try {
                    session.close();
                } catch (ApiException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            } catch (RuntimeException | Error failure) {
                try {
                    session.close();
                } catch (ApiException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        void review(AiRequest request, JsonNode facts, TaskContext task) {
            try {
            sameTask(task);
            assertEquals(AiPurpose.APPROVAL, request.purpose());
            assertEquals(List.of(AiRole.SYSTEM, AiRole.USER), request.messages().stream().map(message -> message.role()).toList());
            assertTrue(OPERATIONS.contains(facts.path("operation").asText()), facts.toString());
            assertTrue(facts.path("serverTargets").isArray());
            assertEquals(1, facts.path("serverTargets").size());
            assertEquals("server", facts.path("serverTargets").get(0).textValue());
            assertFalse(facts.has("policy"), "Independent criteria do not depend on human policy");
            assertFalse(facts.path("businessRevision").asText().isBlank());
            String exact = facts.path("exactAction").asText();
            assertFalse(exact.isBlank());
            if (facts.has("expectedHelperSha256"))
                assertFalse(facts.has("installedHelperScript"), "Installer already contains the full helper executable body");
            var keys = new HashSet<String>();
            facts.fieldNames().forEachRemaining(keys::add);
            var expected = new HashSet<>(Set.of("operation", "serverTargets", "resourceTargets", "businessRevision", "exactAction"));
            for (String field : List.of("authorizationPolicy", "businessRevisionMeaning"))
                if (facts.has(field)) expected.add(field);
            if (facts.has("authorizationPolicy")) assertEquals(policy.get(), facts.path("authorizationPolicy").asText());
            if (facts.has("currentCommandEvidence")) {
                expected.add("currentCommandEvidence");
                currentEvidence(facts, task);
            }
            for (String field : List.of("platformProbe", "capacityProbe", "toolchainPreparation", "toolchainSubcommand", "buildCollector",
                "managedApplication", "workloadInput", "publicationSnapshot", "originalPublicationStatus", "publicationMaintenance",
                "stagedConfiguration", "candidateMavenBootstrap", "ownershipIdentityMeaning", "snapshotTaskMatchesOriginalContext"))
                if (facts.has(field)) expected.add(field);
            if (facts.has("snapshotTaskMatchesOriginalContext")) {
                assertTrue(facts.path("snapshotTaskMatchesOriginalContext").booleanValue());
                assertEquals(task.id().toString(), facts.path("publicationSnapshot").path("task").asText());
            }
            if (facts.has("trackedToolchain")) {
                expected.add("trackedToolchain");
                var tracked = facts.path("trackedToolchain");
                var originalRequest = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.decode(
                    tracked.path("originalRequestUtf8").asText().getBytes(StandardCharsets.UTF_8));
                assertEquals(task.id().toString(), originalRequest.taskId());
                assertEquals("server", originalRequest.serverId());
                String verb = tracked.path("verb").asText();
                assertEquals(facts.path("operation").asText(),
                    gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.operation(verb));
                var values = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.reviewFacts(
                    facts.path("operation").asText(), gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.command(verb, originalRequest),
                    originalRequest.bytes(), "server", Set.of(
                        new gold.debug.windowstolinux.appapi.task.action.ActionTarget("server", "task", task.id().toString()),
                        new gold.debug.windowstolinux.appapi.task.action.ActionTarget("server", "deployment-build", originalRequest.layout().toolchains())), originalRequest.layout());
                assertEquals(new ObjectMapper().valueToTree(values), tracked);
            }
            if (facts.has("decodedToolchainBinding")) {
                assertTrue(Set.of("deployment.toolchain.bind.v1", "deployment.build.run.v1").contains(facts.path("operation").asText()));
                expected.add("decodedToolchainBinding");
                var binding = facts.path("decodedToolchainBinding");
                assertTrue(binding.path("bindingSha256").asText().matches("[a-f0-9]{64}"));
                assertTrue(binding.path("toolchains").isArray());
                assertFalse(binding.path("toolchains").isEmpty());
            }
            if (facts.has("expectedHelperSha256")) {
                expected.addAll(Set.of("expectedHelperPath", "expectedHelperSha256"));
                assertEquals(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.EXPECTED_SHA256,
                    facts.path("expectedHelperSha256").asText());
            }
            if (facts.has("installedHelperPath")) {
                expected.addAll(Set.of("platformContractRevision", "installedHelperSha256", "installedHelperPath"));
                assertEquals("managed-helper-review.v2", facts.path("platformContractRevision").asText());
                assertFalse(facts.has("installedHelperScript"));
                assertEquals(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.EXPECTED_SHA256,
                    facts.path("installedHelperSha256").asText());
            }
            if (facts.has("installedBuildEntryPath")) {
                expected.addAll(Set.of("installedBuildEntrySha256", "installedBuildEntryPath"));
                assertFalse(facts.has("installedBuildEntryScript"));
                assertEquals(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.buildEntrySha256(),
                    facts.path("installedBuildEntrySha256").asText());
                assertEquals(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.BUILD_ENTRY_PATH,
                    facts.path("installedBuildEntryPath").asText());
            }
            if (exact.startsWith("WTL-SSH-COMMAND-1\n")) {
                expected.add("authorizationPolicy");
                expected.add("effectSummary");
                expected.add("businessRevisionMeaning");
                expected.add("presentationLocale");
                expected.add("inputKind");
                if (facts.path("operation").asText().equals("deployment.build.run.v1")) {
                    expected.add("completeExecutableInput");
                    assertEquals("EXECUTABLE_SCRIPT", facts.path("inputKind").asText());
                    assertFalse(facts.path("completeExecutableInput").asText().isBlank());
                    assertFalse(facts.has("installedHelperScript"), "The checked fixed helper uses a source-bound platform contract");
                    assertFalse(facts.has("installedBuildEntryScript"), "The fixed entry identity remains in build review");
                } else {
                    assertTrue(Set.of("EMPTY", "OPAQUE_BOUND_DATA", "TRACKED_TOOLCHAIN_REQUEST").contains(facts.path("inputKind").asText()));
                    assertEquals(facts.has("trackedToolchain"), facts.path("inputKind").asText().equals("TRACKED_TOOLCHAIN_REQUEST"));
                }
            }
            assertEquals(expected, keys, "Review facts must follow the exact original protocol");
            pending.computeIfAbsent(exact, ignored -> new ConcurrentLinkedQueue<>()).add(facts.deepCopy());
            } catch (RuntimeException | Error failure) { lastFailure = failure; throw failure; }
        }

        private void currentEvidence(JsonNode facts, TaskContext task) {
            var current = facts.path("currentCommandEvidence");
            var allowed = Set.of("workflowAuthorization", "sourceSelection", "sourceArchiveObservations", "reviewedApplicationSources",
                "toolchainPreparationObservations", "hostPrerequisiteObservations", "toolchainPreparationContract", "currentToolchainPlan",
                "currentBuildArchive", "currentSourceArchiveTransfer", "configurationStagingImplementation", "originalPublication");
            assertTrue(current.isObject());
            current.fieldNames().forEachRemaining(key -> assertTrue(allowed.contains(key), "Unexpected current evidence: " + key));
            var workflow = current.path("workflowAuthorization");
            keys(workflow, Set.of("task", "serverTargets", "modelRevision", "approvalPolicy", "rememberAuthorization", "frozenWorkflowRequest",
                "recovery", "hostHandoffOpened", "meaning"));
            assertEquals(task.id().toString(), workflow.path("task").asText());
            assertEquals(List.of("server"), new ObjectMapper().convertValue(workflow.path("serverTargets"), List.class));
            assertEquals(policy.get(), workflow.path("approvalPolicy").asText());
            assertTrue(workflow.path("modelRevision").asText().matches("[a-f0-9]{64}"));
            assertTrue(workflow.path("hostHandoffOpened").booleanValue());
            assertFalse(workflow.path("frozenWorkflowRequest").asText().isBlank());
            var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
            if (current.has("sourceSelection")) {
                var source = current.path("sourceSelection");
                keys(source, Set.of("server", "snapshotId", "sourceRevision", "displayName", "includedFiles", "meaning"));
                assertEquals("server", source.path("server").asText());
                assertEquals(source.path("snapshotId").asText(), UUID.fromString(source.path("snapshotId").asText()).toString());
                assertTrue(source.path("sourceRevision").asText().matches("[a-f0-9]{64}"));
                assertTrue(source.path("includedFiles").intValue() > 0);
                selectedSource.compareAndSet(null, source.deepCopy());
                assertEquals(selectedSource.get(), source, "Source selection stays fixed within the original task");
            }
            if (current.has("sourceArchiveObservations")) {
                var archives = current.path("sourceArchiveObservations"); keys(archives, Set.of("archives", "meaning"));
                assertTrue(archives.path("archives").isArray()); assertFalse(archives.path("archives").isEmpty());
                for (var archive : archives.path("archives")) {
                    keys(archive, Set.of("originSnapshotId", "sourceManifestSha256", "archiveSha256", "archiveSnapshotId", "localArchivePath", "archiveBytes",
                        "relativeRoot", "fileCount", "fileNames", "fileNamesTruncated", "sourceToolchainRequirements"));
                    assertNotNull(selectedSource.get());
                    assertEquals(selectedSource.get().path("snapshotId"), archive.path("originSnapshotId"));
                    assertEquals(selectedSource.get().path("sourceRevision"), archive.path("sourceManifestSha256"));
                    assertTrue(archive.path("archiveSha256").asText().matches("[a-f0-9]{64}"));
                    assertTrue(archive.path("archiveBytes").longValue() > 0);
                    try {
                        byte[] bytes = Files.readAllBytes(Path.of(archive.path("localArchivePath").asText()));
                        assertEquals(bytes.length, archive.path("archiveBytes").longValue());
                        assertEquals(gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.sha256(bytes), archive.path("archiveSha256").asText());
                    } catch (java.io.IOException missing) { throw new AssertionError("The actual original archive must remain available for review", missing); }
                    var origin = archiveOrigin(archive);
                    assertEquals(archive.path("archiveSnapshotId").asText(), UUID.fromString(archive.path("archiveSnapshotId").asText()).toString());
                    var priorSnapshot = archiveSnapshots.putIfAbsent(archive.path("archiveSnapshotId").asText(), origin);
                    if (priorSnapshot != null) assertEquals(priorSnapshot, origin);
                    var priorPath = archivePaths.putIfAbsent(archive.path("localArchivePath").asText(), origin);
                    if (priorPath != null) assertEquals(priorPath, origin);
                    var prior = sourceArchives.putIfAbsent(origin, archive.deepCopy());
                    if (prior != null) assertEquals(prior, archive);
                }
            }
            if (current.has("reviewedApplicationSources")) {
                var group = current.path("reviewedApplicationSources"); keys(group, Set.of("applications", "meaning"));
                for (var application : group.path("applications")) {
                    keys(application, Set.of("application", "server", "task", "originSnapshotId", "sourceManifestSha256", "archiveSha256",
                        "relativeRoot", "remoteLayoutSha256", "archiveSnapshotId", "localArchivePath", "archiveBytes"));
                    assertEquals(task.id().toString(), application.path("task").asText()); assertEquals("server", application.path("server").asText());
                    assertEquals(layout.digest(), application.path("remoteLayoutSha256").asText());
                    capturedArchive(application);
                    assertTrue(application.path("application").asText().matches("[a-z0-9][a-z0-9-]{0,62}"));
                    var prior = applicationSources.putIfAbsent(application.path("application").asText(), application.deepCopy());
                    if (prior != null) assertEquals(prior, application, "One reviewed application cannot change its original source association");
                }
            }
            if (current.has("currentToolchainPlan")) {
                var plan = current.path("currentToolchainPlan");
                keys(plan, Set.of("reviewedPlanSha256", "server", "application", "sourceManifestSha256", "archiveSha256", "relativeRoot", "runtimeKind", "requirements", "meaning"));
                assertEquals(facts.path("businessRevision"), plan.path("reviewedPlanSha256")); assertEquals("server", plan.path("server").asText());
                var application = applicationSources.get(plan.path("application").asText()); assertNotNull(application);
                capturedArchive(application);
                for (String key : List.of("server", "application", "sourceManifestSha256", "archiveSha256", "relativeRoot"))
                    assertEquals(application.path(key), plan.path(key));
            }
            if (current.has("toolchainPreparationContract")) {
                var contract = current.path("toolchainPreparationContract");
                keys(contract, Set.of("contractRevision", "helperPath", "helperSha256", "remoteLayout", "remoteLayoutSha256", "server", "arguments", "meaning"));
                assertEquals("deployment.toolchain.prepare.v1", facts.path("operation").asText());
                assertEquals("server", contract.path("server").asText()); assertEquals(layout.canonical(), contract.path("remoteLayout").asText());
                assertEquals(layout.digest(), contract.path("remoteLayoutSha256").asText()); assertEquals(layout.helperPath(), contract.path("helperPath").asText());
                assertEquals(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.helperSha256(layout), contract.path("helperSha256").asText());
                assertEquals(facts.path("toolchainPreparation"), contract.path("arguments"));
            }
            if (current.has("toolchainPreparationObservations")) {
                var group = current.path("toolchainPreparationObservations"); keys(group, Set.of("bindings", "meaning"));
                for (var binding : group.path("bindings")) {
                    keys(binding, Set.of("bindingSha256", "selectionCount", "recordedAt", "server", "task"));
                    assertEquals(task.id().toString(), binding.path("task").asText()); assertEquals("server", binding.path("server").asText());
                    assertTrue(binding.path("bindingSha256").asText().matches("[a-f0-9]{64}")); assertTrue(binding.path("selectionCount").intValue() > 0);
                }
            }
            if (current.has("currentBuildArchive")) {
                var build = current.path("currentBuildArchive");
                keys(build, Set.of("expectedArchiveSha256", "sha256Characters", "archive", "candidate", "candidateRoot", "comparison", "meaning"));
                assertEquals("deployment.build.run.v1", facts.path("operation").asText()); assertEquals(64, build.path("sha256Characters").intValue());
                var archive = capturedArchive(build.path("archive"));
                assertEquals(archive, build.path("archive"));
                assertEquals(archive.path("archiveSha256"), build.path("expectedArchiveSha256"));
                var application = applicationSources.get(facts.path("buildCollector").path("application").asText());
                assertNotNull(application, "The complete collector must use a reviewed application source");
                assertEquals(capturedArchive(application), archive);
                assertEquals(facts.path("buildCollector").path("candidate"), build.path("candidate"));
                assertEquals(layout.candidateRoot(build.path("candidate").asText()), build.path("candidateRoot").asText());
                assertEquals(candidateArchives.get(build.path("candidate").asText()), archive,
                    "The collector must consume the exact original archive uploaded for this candidate");
            }
            if (current.has("currentSourceArchiveTransfer")) {
                var transfer = current.path("currentSourceArchiveTransfer");
                keys(transfer, Set.of("archive", "server", "literalCandidate", "remoteArchivePath", "canonicalTransferMatched", "meaning"));
                assertEquals("deployment.candidate.upload.v1", facts.path("operation").asText()); assertEquals("server", transfer.path("server").asText());
                assertTrue(transfer.path("canonicalTransferMatched").booleanValue());
                assertEquals(layout.candidateRoot(transfer.path("literalCandidate").asText()) + "/mutable/source.tar.gz", transfer.path("remoteArchivePath").asText());
                var archive = capturedArchive(transfer.path("archive"));
                assertEquals(archive, transfer.path("archive"));
                var prior = candidateArchives.putIfAbsent(transfer.path("literalCandidate").asText(), archive.deepCopy());
                if (prior != null) assertEquals(prior, archive, "An original candidate cannot switch its archive source binding");
            }
            if (current.has("configurationStagingImplementation")) {
                assertEquals(new ObjectMapper().valueToTree(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.configurationStagingImplementationFacts(layout)),
                    current.path("configurationStagingImplementation"));
            }
            if (current.has("originalPublication")) {
                var publication = current.path("originalPublication");
                keys(publication, Set.of("task", "execution", "server", "workflowPlanSha256", "application", "literalCandidate", "releaseSha256",
                    "ownershipManifestSha256", "configurationSha256", "meaning"));
                assertEquals(task.id().toString(), publication.path("task").asText()); assertEquals("server", publication.path("server").asText());
                assertEquals("deployment.publication.publish.v1", facts.path("operation").asText());
                assertTrue(publication.path("workflowPlanSha256").asText().matches("[a-f0-9]{64}"));
                assertTrue(publication.path("releaseSha256").asText().matches("[a-f0-9]{64}"));
            }
            assertFalse(current.has("hostPrerequisiteObservations"), "The controlled Go host has no missing noncatalog prerequisite");
        }

        private static void keys(JsonNode node, Set<String> expected) {
            var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
            assertTrue(node.isObject()); assertEquals(expected, actual);
        }

        private static ArchiveOrigin archiveOrigin(JsonNode value) {
            assertTrue(value.path("originSnapshotId").isTextual());
            assertTrue(value.path("relativeRoot").isTextual());
            return new ArchiveOrigin(value.path("originSnapshotId").textValue(), value.path("relativeRoot").textValue());
        }

        private JsonNode capturedArchive(JsonNode binding) {
            var archive = sourceArchives.get(archiveOrigin(binding));
            assertNotNull(archive, "The exact original snapshot and component root must have a captured archive");
            for (String key : List.of("originSnapshotId", "sourceManifestSha256", "archiveSha256", "relativeRoot", "archiveSnapshotId", "localArchivePath", "archiveBytes"))
                assertEquals(archive.path(key), binding.path(key), "Captured archive association changed at " + key);
            return archive;
        }

        void beforeHost(ActionIntent intent, UUID task) {
            if (!intent.type().equals("platform.ssh.execute")) return;
            assertEquals(original.get(), task);
            var reviewed = pending.get(intent.exactAction());
            assertNotNull(reviewed, "Independent AI must finish before the real host question");
            assertFalse(reviewed.isEmpty());
            assertEquals(intent.revision(), reviewed.element().path("businessRevision").asText());
            assertEquals(intent.semantics().orElseThrow().operation(), reviewed.element().path("operation").asText());
        }

        RemoteSession session(RemoteSession originalSession, TaskContext task) {
            return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                new Class<?>[]{RemoteSession.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("execute")) {
                        try { beforeSend((CommandRequest) arguments[0], task); }
                        catch (RuntimeException | Error failure) { lastFailure = failure; throw failure; }
                    }
                    try {
                        return method.invoke(originalSession, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        }

        private void beforeSend(CommandRequest request, TaskContext task) {
            sameTask(task);
            String exact = RemoteActions.command(new ServerId("server"), "fixture-binding", request).exactAction();
            var reviews = pending.get(exact);
            assertNotNull(reviews, "Every actual command needs an independent complete prior review");
            var facts = reviews.poll();
            assertNotNull(facts, "Each actual send consumes its own independent review");
            try {
                var targets = new HashSet<gold.debug.windowstolinux.appapi.task.action.ActionTarget>();
                for (var target : facts.path("resourceTargets")) targets.add(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(
                    target.path("serverId").asText(), target.path("kind").asText(), target.path("id").asText()));
                var actualIntent = RemoteActions.command(new ServerId("server"), facts.path("businessRevision").asText(), request);
                var intent = new ActionIntent(actualIntent.type(), actualIntent.serverIds(), actualIntent.target(), facts.path("businessRevision").asText(),
                    exact, facts.path("effectSummary").asText(), Optional.of(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(facts.path("operation").asText(), targets)));
                var layout = facts.has("trackedToolchain") ? gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.decode(
                    request.stdin()).layout() : gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
                var computed = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandEvidence.facts(intent, request, layout);
                var json = new ObjectMapper();
                for (var entry : computed.entrySet()) assertEquals(json.readTree(json.writeValueAsString(entry.getValue())), facts.path(entry.getKey()),
                    "Prior independent review must describe the exact actual send field " + entry.getKey());
            } catch (java.io.IOException invalid) {
                throw new AssertionError(invalid);
            }
            byte[] input = request.stdin();
            try {
                if (facts.has("completeExecutableInput"))
                    assertArrayEquals(input, facts.path("completeExecutableInput").asText().getBytes(StandardCharsets.UTF_8));
                else if (facts.has("trackedToolchain"))
                    assertArrayEquals(input, facts.path("trackedToolchain").path("originalRequestUtf8").asText().getBytes(StandardCharsets.UTF_8));
                else
                    assertEquals(input.length == 0 ? "EMPTY" : "OPAQUE_BOUND_DATA", facts.path("inputKind").asText());
            } finally {
                Arrays.fill(input, (byte) 0);
            }
            sends.incrementAndGet();
        }
    }
}
