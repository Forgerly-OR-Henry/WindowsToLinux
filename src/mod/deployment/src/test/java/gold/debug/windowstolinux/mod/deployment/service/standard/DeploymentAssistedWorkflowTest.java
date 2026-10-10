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
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;
import gold.debug.windowstolinux.appapi.ai.model.AiRole;
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
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectPart;
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
                fixedReviews.beforeHost(intent, task.id());
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
                public RemoteSession connect(gold.debug.windowstolinux.appapi.linux.session.RemoteTarget expected, TaskContext task) throws ApiException {
                    return fixedReviews.connect(expected, task, actual, this);
                }

                public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                    connections.incrementAndGet();
                    return fixedReviews.session(peer.session(task, () -> {
                        if (scenario == ScenarioType.CONNECT_DRIFT)
                            revision.set("b".repeat(64));
                    }), task);
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
            var workflow = new DeploymentStaticWorkflow(bound, new DeploymentRefFlow(bound));
            var page = new DeploymentStaticPageService(bound, workflow);
            var original = new AtomicReference<UUID>();
            var reviewer = new DeploymentStaticWorkflow.Reviewer() {
                public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerId server, String sha,
                                                                                  List<DiscoveredProjectPart> found, TaskContext task) {
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

                public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerId server,
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

                public OperationResult<Boolean> confirm(ServerId server, String sha, Map<String, String> archives,
                                                        Map<String, DeploymentStaticWorkflow.Answers> answers,
                                                        Map<String, DeploymentPreparedDatabase> databases,
                                                        Optional<DeploymentStaticWorkflow.GitReview> git, TaskContext task) {
                    assertEquals(original.get(), task.id());
                    assertEquals(1, archives.size());
                    assertEquals("9090", answers.values().iterator().next().values().get("port"));
                    return success(scenario != ScenarioType.REFUSAL);
                }
            };
            var handle = page.submit(new ServerId("server"),
                new DeploymentStaticWorkflow.Draft("demo", 65536, 100, 65536), Optional.empty(), reviewer, true);
            var result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(
                scenario == ScenarioType.REFUSAL
                    ? TaskResult.CANCELLED
                    : scenario == ScenarioType.PUBLICATION ? TaskResult.SUCCEEDED : TaskResult.FAILED,
                result.outcome(), result.toString());
            assertEquals(handle.id(), original.get());
            assertEquals(2, reviews.get());
            assertEquals(3, calls.get());
            assertEquals(1, approvals.stream().filter("deployment.assisted.retry"::equals).count());
            if (scenario == ScenarioType.REFUSAL) {
                assertEquals(0, connections.get());
                assertEquals(0, fixedReviews.sends.get());
            } else {
                assertTrue(fixedReviews.sends.get() > 0);
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
                    return success(new AiResponse("{\"decision\":\"APPROVE\",\"risk\":\"NORMAL\",\"reason\":\"Exact controlled original action\"}",
                        "controlled-approval", "stop", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
                }
                assertEquals(AiPurpose.LANGUAGE, request.purpose());
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
                    new AiResponse(output, "controlled", "stop", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
            }
        };
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
            "deployment.build.run.v1", "deployment.publication.snapshot.v1", "deployment.publication.original-status.v1",
            "deployment.publication.snapshot-discard.v1", "deployment.publication.config.v1", "deployment.publication.publish.v1",
            "deployment.publication.health.v1", "deployment.publication.inspect.v1", "deployment.publication.observe.v1",
            "deployment.publication.observe-restored.v1", "deployment.publication.rollback.v1", "deployment.publication.retain.v1",
            "deployment.publication.maintenance-end.v1", "deployment.publication.candidate-cleanup.v1",
            "deployment.assisted.retry", "deployment.assisted.environment-recovery", "deployment.assisted.build-review");

        private final AtomicReference<UUID> original = new AtomicReference<>();

        private final AtomicReference<String> policy = new AtomicReference<>("MANUAL_REVIEW");

        private final Map<String, Queue<JsonNode>> pending = new ConcurrentHashMap<>();

        final AtomicInteger sends = new AtomicInteger();

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
            sameTask(task);
            assertEquals(AiPurpose.APPROVAL, request.purpose());
            assertEquals(List.of(AiRole.SYSTEM, AiRole.USER), request.messages().stream().map(message -> message.role()).toList());
            assertTrue(OPERATIONS.contains(facts.path("operation").asText()), facts.toString());
            assertTrue(facts.path("targets").isArray());
            assertEquals(1, facts.path("targets").size());
            assertEquals("server", facts.path("targets").get(0).textValue());
            assertEquals(policy.get(), facts.path("policy").asText(), "Independent review preserves the original approval policy");
            assertFalse(facts.path("businessRevision").asText().isBlank());
            String exact = facts.path("exactAction").asText();
            assertFalse(exact.isBlank());
            assertFalse(facts.has("installedHelperScript"), "Executable stdin must appear once in its original representation");
            var keys = new HashSet<String>();
            facts.fieldNames().forEachRemaining(keys::add);
            var expected = new HashSet<>(Set.of("policy", "operation", "targets", "businessRevision", "exactAction"));
            if (exact.startsWith("WTL-SSH-COMMAND-1\n")) {
                expected.add("inputKind");
                if (facts.path("operation").asText().equals("deployment.build.run.v1")) {
                    expected.add("completeExecutableInput");
                    assertEquals("EXECUTABLE_SCRIPT", facts.path("inputKind").asText());
                    assertFalse(facts.path("completeExecutableInput").asText().isBlank());
                } else {
                    assertTrue(Set.of("EMPTY", "OPAQUE_BOUND_DATA").contains(facts.path("inputKind").asText()));
                }
            }
            assertEquals(expected, keys, "Review facts must follow the exact original protocol");
            pending.computeIfAbsent(exact, ignored -> new ConcurrentLinkedQueue<>()).add(facts.deepCopy());
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
                    if (method.getName().equals("execute")) beforeSend((CommandRequest) arguments[0], task);
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
            byte[] input = request.stdin();
            try {
                if (facts.has("completeExecutableInput"))
                    assertArrayEquals(input, facts.path("completeExecutableInput").asText().getBytes(StandardCharsets.UTF_8));
                else
                    assertEquals(input.length == 0 ? "EMPTY" : "OPAQUE_BOUND_DATA", facts.path("inputKind").asText());
            } finally {
                Arrays.fill(input, (byte) 0);
            }
            sends.incrementAndGet();
        }
    }
}
