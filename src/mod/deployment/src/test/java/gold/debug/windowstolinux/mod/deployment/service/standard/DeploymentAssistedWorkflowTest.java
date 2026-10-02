package gold.debug.windowstolinux.mod.deployment.service.standard;

import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
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
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectComponent;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentPreparedDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Uses the actual page workflow, frozen source and original H2 task for bounded assistance. / 使用真实页面流程、冻结来源及原 H2 任务验证有界辅助。 */
class DeploymentAssistedWorkflowTest extends DeploymentRuntimeFixture {
    enum ScenarioType {
        REFUSAL, PUBLICATION, CONNECT_DRIFT
    }

    private final AtomicInteger calls = new AtomicInteger();

    private final AtomicInteger reviews = new AtomicInteger();

    private final List<String> approvals = new CopyOnWriteArrayList<>();

    private final AtomicReference<String> revision = new AtomicReference<>("a".repeat(64));

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict c, ServerRef s, TaskSnapshot t,
                                                           CancelToken token) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall c, ServerRef s, TaskSnapshot t,
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

    private void verifyScenario(ScenarioType scenario) throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("go.mod"), "module example.test/demo\n\ngo 1.24\n");
        Files.writeString(selectedSourceDirectory().resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
        Files.writeString(selectedSourceDirectory().resolve("main.go"), "package main\nfunc main() {}\n");
        Files.writeString(selectedSourceDirectory().resolve("README.md"), "HTTP service default port is 8080.\n");
        Files.writeString(selectedSourceDirectory().resolve("windowstolinux-application.properties"),
            "version=1\nhealth.mode=TCP\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            var ai = ai();
            var peer = new DeploymentAssistedTestPeer();
            var connections = new AtomicInteger();
            LinuxService linux = new LinuxService() {
                public RemoteSession connect(ServerRef server, TaskContext task) throws ApiException {
                    connections.incrementAndGet();
                    return peer.session(task, () -> {
                        if (scenario == ScenarioType.CONNECT_DRIFT)
                            revision.set("b".repeat(64));
                    });
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
            var workflow = new DeploymentStaticWorkflow(bound, new DeploymentReferenceFlow(bound));
            var page = new DeploymentStaticPageService(bound, workflow);
            var original = new AtomicReference<UUID>();
            var reviewer = new DeploymentStaticWorkflow.Reviewer() {
                public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerRef server, String sha,
                                                                                  List<DiscoveredProjectComponent> found, TaskContext task) {
                    original.set(task.id());
                    assertEquals(1, found.size());
                    return success(
                        new DeploymentStaticWorkflow.Selection(
                            found.getFirst().id(), Map
                            .of(found.getFirst().id(),
                                new DeploymentStaticWorkflow.Choice(
                                    DeploymentProjectKind.GO_SERVICE, Set.of())),
                            Optional.empty()));
                }

                public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerRef server,
                                                                                             String sha, List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) {
                    assertEquals(original.get(), task.id());
                    int round = reviews.getAndIncrement();
                    var prompt = prompts.getFirst();
                    assertEquals("8080", prompt.fields().stream().filter(field -> field.id().endsWith("/port"))
                        .findFirst().orElseThrow().value());
                    var values = new HashMap<>(prompt.defaults());
                    values.put("primary", prompt.componentId());
                    values.put("secondary", "main.go");
                    values.put("version", "1.24");
                    values.put("healthMode", "TCP");
                    values.put("port", round == 0 ? "0" : "9090");
                    values.put("exposure", "INTERNAL");
                    return success(Map.of(prompt.componentId(), new DeploymentStaticWorkflow.Answers(values, List.of(),
                        DeploymentBuildLimits.defaultNonRoot(), false, true)));
                }

                public OperationResult<Boolean> confirm(ServerRef server, String sha, Map<String, String> archives,
                                                        Map<String, DeploymentStaticWorkflow.Answers> answers,
                                                        Map<String, DeploymentPreparedDatabase> databases,
                                                        Optional<DeploymentStaticWorkflow.GitReview> git, TaskContext task) {
                    assertEquals(original.get(), task.id());
                    assertEquals(1, archives.size());
                    assertEquals("9090", answers.values().iterator().next().values().get("port"));
                    return success(scenario != ScenarioType.REFUSAL);
                }
            };
            var handle = page.submit(new ServerRef("server"),
                new DeploymentStaticWorkflow.Draft("demo", 65536, 100, 65536), Optional.empty(), reviewer, true);
            var result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(
                scenario == ScenarioType.REFUSAL
                    ? Outcome.CANCELLED
                    : scenario == ScenarioType.PUBLICATION ? Outcome.SUCCEEDED : Outcome.FAILED,
                result.outcome(), result.toString());
            assertEquals(handle.id(), original.get());
            assertEquals(2, reviews.get());
            assertEquals(3, calls.get());
            assertEquals(1, approvals.stream().filter("deployment.assisted.retry"::equals).count());
            if (scenario == ScenarioType.REFUSAL) {
                assertEquals(0, connections.get());
            } else {
                try (var access = scope(runtime, true);
                     var connection = access.openConnection();
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery(
                         "SELECT platform_task_id,mode,outcome,business_committed FROM deployment_execution")) {
                    assertTrue(rows.next());
                    assertEquals(handle.id(), rows.getObject(1, UUID.class));
                    assertEquals("ASSISTED", rows.getString(2));
                    assertEquals(scenario == ScenarioType.PUBLICATION ? "SUCCEEDED" : "FAILED", rows.getString(3));
                    assertEquals(scenario == ScenarioType.PUBLICATION, rows.getBoolean(4));
                    assertFalse(rows.next());
                }
                assertEquals(scenario == ScenarioType.PUBLICATION ? 1 : 0, peer.publications.get());
                assertEquals(scenario == ScenarioType.PUBLICATION ? 2 : 1, connections.get(),
                    "Frozen guard rejects before admitting a second SSH session");
                if (scenario == ScenarioType.CONNECT_DRIFT)
                    assertEquals(ProblemCode.ACCESS_DENIED, result.problem().orElseThrow().code());
            }
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    private AiService ai() {
        return new AiService() {
            @Override
            public gold.debug.windowstolinux.appapi.task.OperationResult<gold.debug.windowstolinux.appapi.ai.VoiceResponse> call(
                gold.debug.windowstolinux.appapi.ai.VoiceRequest request, gold.debug.windowstolinux.appapi.task.TaskContext task) {
                throw new AssertionError("Voice is outside this fixture");
            }

            @Override
            public gold.debug.windowstolinux.appapi.task.OperationResult<gold.debug.windowstolinux.appapi.ai.VoiceResponse> call(
                gold.debug.windowstolinux.appapi.ai.VoiceRequest request, String expectedRevision,
                gold.debug.windowstolinux.appapi.task.TaskContext task) {
                throw new AssertionError("Voice is outside this fixture");
            }

            @Override
            public gold.debug.windowstolinux.appapi.ai.VoiceSession openVoice(
                gold.debug.windowstolinux.appapi.ai.VoiceOptions options, gold.debug.windowstolinux.appapi.ai.VoiceListener listener,
                gold.debug.windowstolinux.appapi.task.TaskContext task) {
                throw new AssertionError("Voice is outside this fixture");
            }

            public String configurationRevision(AiPurpose purpose, TaskContext task) {
                assertEquals(AiPurpose.LANGUAGE, purpose);
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
                return success(
                    new AiResponse(output, "controlled", "stop", new AiUsage(Optional.empty(), Optional.empty())));
            }
        };
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(value), Optional.empty());
    }
}
