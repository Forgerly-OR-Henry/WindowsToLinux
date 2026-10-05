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
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;
import gold.debug.windowstolinux.appapi.linux.*;
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
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildEvidenceAssertions;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildReviewCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentPreparedDatabase;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication.DeploymentStandardPublishRecovery;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests physical health rollback before a separately approved original-task operation. / 验证物理健康回滚先于另行批准的原任务操作。 */
class DeploymentAssistedHealthWorkflowTest extends DeploymentRuntimeFixture {
    private final AtomicInteger language = new AtomicInteger(), independent = new AtomicInteger(),
        recoveries = new AtomicInteger();

    private final Set<String> reviewedOperations = ConcurrentHashMap.newKeySet();

    private final DeploymentAssistedWorkflowTest.FixedReviewFixture fixedReviews = new DeploymentAssistedWorkflowTest.FixedReviewFixture();

    private final List<ActionIntent> hostApprovals = new CopyOnWriteArrayList<>();

    private boolean returnToOriginal;

    private final AtomicReference<JsonNode> lastModelFacts = new AtomicReference<>();

    private java.util.function.Consumer<ActionIntent> reviewFault = ignored -> {
    };

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

            public String transferProgress(TransferProgress progress) {
                return "Transferred " + progress.completedBytes();
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken token) {
                throw new AssertionError();
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken token) {
                fixedReviews.beforeHost(intent, task.id());
                hostApprovals.add(intent);
                reviewFault.accept(intent);
                return true;
            }
        };
    }

    /** Restored health failures consume one root budget across distinct operations and all children. / 恢复后的健康失败在独立操作和全部子项之间消费同一根预算。
     * @param count complete source-backed component count / 完整源码组件数
     * @param failures physically confirmed health failures / 物理确认健康失败数
     * @param changed whether the human changes the actual typed runtime / 人工是否更改实际类型化运行配置
     * @throws Exception if original workflow or durable evidence fails / 原流程或持久证据失败时 */
    @ParameterizedTest
    @CsvSource({"1,1,true", "2,1,true", "1,3,true", "2,3,true", "1,1,false", "2,1,false"})
    void restoredHealthFailureUsesDistinctOperationsAndAtMostTwoOriginalRetries(int count, int failures,
                                                                                boolean changed) throws Exception {
        source(count);
        enable();
        returnToOriginal = true;
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            var peer = new DeploymentAssistedBuildPeer(false);
            peer.publicationAttempts = true;
            peer.healthFailuresRemaining = failures;
            var ai = models(false);
            LinuxService linux = new LinuxService() {
                public RemoteSession connect(gold.debug.windowstolinux.appapi.linux.session.RemoteTarget expected, TaskContext task) throws ApiException {
                    return fixedReviews.connect(expected, task, actual, this);
                }

                public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                    return fixedReviews.session(peer.session(task), task);
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
            var original = new AtomicReference<UUID>();
            var archives = new AtomicReference<Map<String, String>>();
            var workflow = new DeploymentStaticWorkflow(bound, new DeploymentRefFlow(bound));
            var handle = workflow.submit(new ServerId("server"),
                new DeploymentStaticWorkflow.Draft("", 65536, 100, 65536), Optional.empty(),
                reviewer(original, archives, count, changed), true, "MANUAL_REVIEW");
            OperationResult<Void> result;
            try {
                result = handle.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
            } catch (TimeoutException unfinished) {
                throw new AssertionError(handle.snapshot() + "; retained actual evidence="
                    + diagnostic(runtime, handle.id()) + "; last actual model facts=" + lastModelFacts.get()
                    + "; actual sends=" + peer.commands, unfinished);
            }
            int retries = changed ? Math.min(failures, 2) : 0;
            boolean success = changed && failures == 1;
            assertEquals(success ? TaskResult.SUCCEEDED : TaskResult.FAILED, result.outcome(), result.toString());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(handle.id(), original.get());
            assertEquals(Set.of(handle.id()), peer.tasks);
            assertEquals(count * (retries + 1), peer.creates.size());
            assertEquals(peer.creates.keySet(), peer.uploads.keySet());
            assertEquals(peer.creates.keySet(), peer.builds.keySet());
            peer.creates.values().forEach(value -> assertEquals(1, value));
            peer.uploads.values().forEach(value -> assertEquals(1, value));
            peer.builds.values().forEach(value -> assertEquals(1, value));
            assertTrue(peer.present.isEmpty());
            assertEquals(peer.sessionsOpened, peer.sessionsClosed);
            assertTrue(peer.statusReads >= count * retries,
                "Every retired child must receive a fresh exact status read");
            assertEquals(retries, hostApprovals.stream()
                .filter(intent -> intent.type().equals("deployment.assisted.environment-recovery")).count());
            assertEquals(changed ? retries : 1, recoveries.get());
            assertTrue(independent.get() >= fixedReviews.sends.get());
            assertEquals(peer.commands.size(), fixedReviews.sends.get());
            assertTrue(reviewedOperations.containsAll(Set.of("deployment.build.run.v1",
                "deployment.publication.publish.v1", "deployment.publication.health.v1", "deployment.publication.rollback.v1")));
            try (var access = scope(runtime, true);
                 var connection = access.openConnection();
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                     "SELECT platform_task_id,phase,business_committed,reviewed_revision,request_payload FROM deployment_execution")) {
                assertTrue(rows.next());
                assertEquals(handle.id(), rows.getObject(1, UUID.class));
                assertEquals("FINISHED", rows.getString(2));
                assertEquals(success, rows.getBoolean(3));
                var request = new ObjectMapper().readTree(new String(rows.getBytes(5), StandardCharsets.UTF_8));
                assertEquals(rows.getString(4), request.path("reviewedRevision").asText());
                assertEquals(retries, request.path("attemptReviews").size());
                assertEquals(retries, request.path("attemptProofs").size());
                assertEquals(11, request.path("formatVersion").intValue());
                if (retries == 0) {
                    assertTrue(request.path("attemptIntent").isNull());
                    assertTrue(request.path("attemptPendingProof").isNull());
                    assertEquals(handle.id().toString(), request.path("attemptOperation").textValue());
                } else {
                    assertTrue(request.path("attemptIntent").isNull());
                    assertTrue(request.path("attemptPendingProof").isNull());
                    gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedHealthEvidenceAssertions
                        .verify(request, handle.id(), count, retries, archives.get(), peer.archives);
                    gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedHealthEvidenceAssertions
                        .verifyConfigurationPrecision(connection);
                }
                assertFalse(rows.next());
            }
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    private String diagnostic(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime, UUID task)
        throws Exception {
        var mapper = new ObjectMapper();
        var report = mapper.createObjectNode();
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.prepareStatement(
                 "SELECT phase,outcome,reviewed_revision,recovery_payload,result_payload,request_payload FROM deployment_execution WHERE platform_task_id=?")) {
            statement.setObject(1, task);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var actual = report.putObject("execution");
                    actual.put("phase", rows.getString(1)).put("outcome", rows.getString(2)).put("reviewedRevision",
                        rows.getString(3));
                    for (int column = 4; column <= 5; column++)
                        if (rows.getBytes(column) != null)
                            actual.set(column == 4 ? "recovery" : "result", mapper.readTree(rows.getBytes(column)));
                    var request = mapper.readTree(rows.getBytes(6));
                    for (String key : List.of("formatVersion", "reviewedRevision", "attemptOperation", "attemptIntent",
                        "attemptPendingProof", "attemptTemplate", "attemptReviews", "attemptProofs"))
                        if (request.has(key))
                            actual.set(key, request.get(key));
                }
            }
        }
        var checkpoints = report.putArray("checkpoints");
        try (var connection = runtime.persistence().database().openConnection();
             var statement = connection.prepareStatement(
                 "SELECT resource_id,recovery_json FROM platform_task_resource WHERE task_id=? AND resource_kind='CUSTOM' AND released_at IS NULL ORDER BY resource_id")) {
            statement.setObject(1, task);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var checkpoint = checkpoints.addObject().put("resource", rows.getString(1));
                    String retained = rows.getString(2);
                    if (retained == null) {
                        checkpoint.putNull("envelope");
                        continue;
                    }
                    var envelope = mapper.readTree(retained);
                    String payload = envelope.path("fields").path("payload").asText();
                    if (!payload.isEmpty())
                        checkpoint.set("payload", mapper.readTree(payload));
                    else
                        checkpoint.set("envelope", envelope);
                }
            }
        }
        return report.toString();
    }

    private DeploymentStaticWorkflow.Reviewer reviewer(AtomicReference<UUID> taskId,
                                                       AtomicReference<Map<String, String>> archives, int count, boolean changed) {
        var corrections = new HashMap<String, Integer>();
        return new DeploymentStaticWorkflow.Reviewer() {
            public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerId server, String sha,
                                                                              List<DiscoveredProjectPart> components, TaskContext task) {
                assertEquals(count, components.size());
                taskId.set(task.id());
                var choices = new LinkedHashMap<String, DeploymentStaticWorkflow.Choice>();
                components.forEach(component -> choices.put(component.id(),
                    new DeploymentStaticWorkflow.Choice(DeploymentProjectKind.GO_SERVICE, Set.of())));
                return success(new DeploymentStaticWorkflow.Selection(
                    count == 1 ? components.getFirst().id() : "reviewed-application", choices,
                    count == 1 ? Optional.empty() : Optional.of(components.getFirst().id())));
            }

            public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerId server, String sha,
                                                                                         List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) {
                assertEquals(taskId.get(), task.id());
                var values = new LinkedHashMap<String, DeploymentStaticWorkflow.Answers>();
                int port = 8080;
                for (var prompt : prompts) {
                    var answer = new LinkedHashMap<>(prompt.defaults());
                    answer.put("primary", prompt.componentId());
                    answer.put("secondary", "main.go");
                    answer.put("version", "1.24");
                    answer.put("jvmTarget", "");
                    answer.put("port", Integer.toString(port++));
                    answer.put("healthMode", "TCP");
                    answer.put("exposure", "INTERNAL");
                    var file = new DeploymentFileBinding("uploads",
                        new DeploymentStoragePath(prompt.componentId() + "-uploads",
                            DeploymentStoragePath.AccessMode.READ_WRITE, "uploads-v1", true),
                        DeploymentStorageLocation.defaults(), DeploymentStorageLocation.StorageResourceType.FILE);
                    values.put(prompt.componentId(), new DeploymentStaticWorkflow.Answers(answer, List.of(file),
                        new DeploymentBuildLimits(600, 32, 512, 65536, 128L * 1024 * 1024, false), false, true));
                }
                return success(Map.copyOf(values));
            }

            public OperationResult<DeploymentStaticWorkflow.Answers> reviewBuildCorrection(ServerId server, String sha,
                                                                                           DeploymentStaticWorkflow.Prompt prompt, DeploymentStaticWorkflow.Answers original,
                                                                                           TaskContext task) {
                assertEquals(taskId.get(), task.id());
                assertEquals(Set.of("primary", "secondary", "version", "jvmTarget"),
                    prompt.fields().stream().map(field -> field.id().substring(field.id().lastIndexOf('/') + 1))
                        .collect(java.util.stream.Collectors.toSet()));
                assertEquals(600, original.limits().timeoutSeconds());
                assertTrue(original.adapterRisk());
                assertEquals(1, original.files().size());
                assertEquals("uploads", original.files().getFirst().bindingId());
                var answer = new LinkedHashMap<>(original.values());
                int attempt = corrections.merge(prompt.componentId(), 1, Integer::sum);
                answer.put("version", changed && (!returnToOriginal || attempt == 1) ? "1.25" : "1.24");
                return success(new DeploymentStaticWorkflow.Answers(answer, original.files(), original.limits(),
                    original.containerRisk(), original.adapterRisk()));
            }

            public OperationResult<Boolean> confirm(ServerId server, String sha, Map<String, String> tar,
                                                    Map<String, DeploymentStaticWorkflow.Answers> answers,
                                                    Map<String, DeploymentPreparedDatabase> databases, Optional<DeploymentStaticWorkflow.GitReview> git,
                                                    TaskContext task) {
                assertEquals(taskId.get(), task.id());
                assertEquals(count, tar.size());
                archives.set(Map.copyOf(tar));
                return success(true);
            }
        };
    }

    private AiService models(boolean stop) {
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
                return "a".repeat(64);
            }

            public OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException {
                return call(request, "a".repeat(64), task);
            }

            public OperationResult<AiResponse> call(AiRequest request, String expected, TaskContext task)
                throws ApiException {
                assertEquals("a".repeat(64), expected);
                JsonNode facts;
                try {
                    facts = new ObjectMapper().readTree(request.messages().getLast().text());
                } catch (Exception invalid) {
                    throw new AssertionError(invalid);
                }
                lastModelFacts.set(facts.deepCopy());
                String response;
                if (request.purpose() == AiPurpose.APPROVAL) {
                    fixedReviews.review(request, facts, task);
                    independent.incrementAndGet();
                    reviewedOperations.add(facts.path("operation").asText());
                    response = "{\"decision\":\"APPROVE\",\"risk\":\"NORMAL\",\"reason\":\"Exact registered fixed action\"}";
                } else {
                    assertEquals(AiPurpose.LANGUAGE, request.purpose());
                    fixedReviews.sameTask(task);
                    language.incrementAndGet();
                    if (Set.of("BUILD", "ROLLBACK_VERIFIED").contains(facts.path("phase").asText())) {
                        assertEquals("ROLLBACK_VERIFIED", facts.path("phase").asText(),
                            "This fixture has successful compilation and diagnoses only a physically restored health failure");
                        recoveries.incrementAndGet();
                        assertTrue(facts.path("fields").isEmpty(),
                            "Source and human answers cannot be overwritten by AI");
                        response = "{\"decision\":\"" + (stop ? "STOP" : "RETRY")
                            + "\",\"reason\":\"Only the original human may correct typed fields\",\"suggestions\":{}}";
                    } else
                        response = "{\"action\":\"ADVISE\",\"sourceRevision\":\""
                            + facts.path("sourceRevision").asText()
                            + "\",\"arguments\":{\"suggestions\":[],\"summary\":\"Keep explicit human review\",\"unresolved\":[]}}";
                }
                return success(new AiResponse(response, "controlled-purpose", "stop",
                    new AiUsage(Optional.empty(), Optional.empty())));
            }
        };
    }

    private void source(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            var directory = count == 1 ? selectedSourceDirectory() : selectedSourceDirectory().resolve("component" + i);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("go.mod"), "module example.test/component" + i + "\n\ngo 1.24\n");
            Files.writeString(directory.resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
            Files.writeString(directory.resolve("main.go"), "package main\nfunc main() {}\n");
            Files.writeString(directory.resolve("windowstolinux-application.properties"),
                "version=1\nhealth.mode=TCP\n");
        }
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }
}
