package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
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
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.DeploymentStandardEntryFixture;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentFileBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStoragePath;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentPreparedDatabase;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;

/** Uses two real hosts and deliberately exits with retained UNKNOWN resources; neither exit is reported as cleanup. / 使用两个真实宿主并刻意带留存UNKNOWN资源退出；任一退出均不冒充收尾。 */
public final class DeploymentAssistedEnvironmentRecoveryWorker extends DeploymentRuntimeFixture {
    private final ObjectMapper json = new ObjectMapper();

    private final AtomicInteger aiCalls = new AtomicInteger(), failures = new AtomicInteger(),
        lost = new AtomicInteger(), independent = new AtomicInteger(), sourceScans = new AtomicInteger(), toolCalls = new AtomicInteger();

    private final DeploymentAssistedWorkflowTest.FixedReviewFixture fixedReviews = new DeploymentAssistedWorkflowTest.FixedReviewFixture();

    private final AtomicReference<String> modelRevision = new AtomicReference<>();

    private DeploymentAssistedEnvironmentRecoveryRestartTest.BoundaryType boundary;

    private boolean recovering;

    private String scenario = "LEGACY", phase;

    private DeploymentAssistedBuildPeer peer;

    private volatile Throwable lastModelFailure;

    private gold.debug.windowstolinux.app.main.mod.PlatformModFlow controlledRecoveryFlow;

    /** Runs only the producer or the separately reopened recovery host. / 仅运行原任务宿主或独立重开恢复宿主。
     * @param args phase, original data root, lost receipt, original SSH endpoint and HTTP sentinel / 阶段、原数据根、丢失回执、原SSH端点及HTTP哨兵 */
    public static void main(String[] args) {
        try {
            assertEquals(6, args.length);
            var fixture = new DeploymentAssistedEnvironmentRecoveryWorker();
            fixture.root = Path.of(args[1]);
            fixture.boundary = DeploymentAssistedEnvironmentRecoveryRestartTest.BoundaryType.valueOf(args[2]);
            fixture.scenario = args[5];
            fixture.phase = args[0];
            if (args[0].equals("produce"))
                fixture.produce(Integer.parseInt(args[3]), Integer.parseInt(args[4]));
            else {
                assertTrue(Set.of("recover", "recover-again").contains(args[0]));
                fixture.recover();
            }
            System.out.println("ENVIRONMENT_" + args[0].toUpperCase(Locale.ROOT) + "_VERIFIED");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    @Override
    protected Path selectedSourceDirectory() {
        if (recovering)
            throw new AssertionError("The original cross-boot task cannot select a new source");
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
                return true;
            }
        };
    }

    private void produce(int port, int httpPort) throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("go.mod"), "module example.test/environment\n\ngo 1.24\n");
        Files.writeString(selectedSourceDirectory().resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
        Files.writeString(selectedSourceDirectory().resolve("main.go"), "package main\nfunc main() {}\n");
        Files.writeString(selectedSourceDirectory().resolve("windowstolinux-application.properties"),
            "version=1\nhealth.mode=TCP\n");
        enable();
        var runtime = open();
        start(runtime, true);
        sentinelModel(runtime, httpPort);
        runtime.servers().saveServerProfile(
            ServerProfile
                .fromStored(new StoredServerProfile("server", "127.0.0.1", port, "root", "fixture-password")),
            "fixture-password".toCharArray());
        var actual = runtime.modContexts().get(OWNER).context();
        runtime.tasks().revokeOwnerServices(OWNER, VERSION);
        var tasks = runtime.tasks().bind(OWNER, VERSION, false);
        peer = new DeploymentAssistedBuildPeer(false);
        var linux = new LinuxService() {
            public RemoteSession connect(gold.debug.windowstolinux.appapi.linux.session.RemoteTarget expected, TaskContext task) throws ApiException {
                return fixedReviews.connect(expected, task, actual, this);
            }

            public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                return interrupted(fixedReviews.session(peer.session(task), task));
            }

            public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                return List.of();
            }
        };
        var models = models(actual.ai());
        var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
            new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("tasks"))
                    return tasks;
                if (method.getName().equals("linux"))
                    return linux;
                if (method.getName().equals("ai"))
                    return models;
                try {
                    return method.invoke(actual, arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        var flow = new DeploymentRefFlow(bound, new DeploymentStandardPublishRecovery(bound));
        tasks.registerRecovery(DeploymentRefFlow.TASK_TYPE, flow);
        var handle = new DeploymentStandardEntryFixture(bound, flow).submit(new ServerId("server"),
            new DeploymentStandardInputs.Draft("", 65536, 100, 65536), Optional.empty(), reviewer(), true,
            "MANUAL_REVIEW");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (handle.snapshot().state() != TaskState.UNKNOWN && handle.snapshot().state() != TaskState.ENDED
            && System.nanoTime() < until)
            Thread.sleep(20);
        assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), () -> handle.snapshot() + " "
            + (handle.snapshot().state() == TaskState.ENDED ? handle.completion().toCompletableFuture().join().problem() : ""));
        assertTrue(handle.snapshot().outcome().isEmpty());
        assertEquals(1, lost.get(), () -> DeploymentAssistedGoModelFixture.diagnostics(runtime, handle.id())
            + "; actual commands=" + peer.commands + "; review failure=" + fixedReviews.lastFailure + "; peer failure=" + peer.lastFailure
            + "; model failure=" + lastModelFailure);
        assertEquals(1, failures.get());
        assertEquals(2, aiCalls.get());
        assertEquals(peer.commands.size(), fixedReviews.sends.get());
        assertTrue(independent.get() >= fixedReviews.sends.get());
        assertEquals(1, peer.creates.size());
        assertEquals(List.of(1), List.copyOf(peer.creates.values()));
        assertEquals(1, peer.uploads.size());
        assertEquals(List.of(1), List.copyOf(peer.uploads.values()));
        assertEquals(List.of(1), List.copyOf(peer.builds.values()));
        assertTrue(peer.publications.isEmpty());
        assertEquals(List.of("1.24", "1.25"), peer.toolBranches);
        assertEquals(1, peer.present.size());
        assertEquals(Set.of(handle.id()), peer.tasks);
        peer.save(record("peer"));
        if (scenario.equals("LEGACY")) removeTrackedExtension(runtime, handle.id());
        var retained = snapshot(runtime, handle.id());
        retained.put("originalTask", handle.id().toString()).put("aiCalls", aiCalls.get()).put("independentReviews", independent.get()).put("builds", 1)
            .put("uploads", 1).put("lost", lost.get());
        retained.put("candidate", peer.present.iterator().next());
        retained.put("scenario", scenario);
        Files.writeString(record("before"), retained.toString());
        assertBusy(runtime);
    }

    private RemoteSession interrupted(RemoteSession original) {
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, arguments) -> {
                Object returned;
                try {
                    returned = method.invoke(original, arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
                if (method.getName().equals("execute")) {
                    var command = (CommandRequest) arguments[0];
                    String script = command.script();
                    boolean target = !recovering && (boundary == DeploymentAssistedEnvironmentRecoveryRestartTest.BoundaryType.PREPARE
                        ? script.contains("prepare-toolchains 'prepare-tracked'")
                        : script.contains("prepare-toolchains 'bind-tracked'"));
                    boolean cleanupLost = recovering && phase.equals("recover") && scenario.equals("CLEANUP_LOST")
                        && script.contains("prepare-toolchains 'cleanup-tracked'");
                    if (target || cleanupLost) {
                        assertEquals(1, lost.incrementAndGet(),
                            "Only the original corrected send loses its physical receipt");
                        assertEquals(TaskResult.SUCCEEDED, ((OperationResult<?>) returned).outcome(),
                            "Physical effect precedes the lost acknowledgement");
                        var tracked = DeploymentTrackedToolchains.decode(command.stdin());
                        if (target)
                            peer.acknowledgementLost(tracked, boundary == DeploymentAssistedEnvironmentRecoveryRestartTest.BoundaryType.PREPARE);
                        return new OperationResult<CommandResult>(TaskResult.UNKNOWN, Optional.empty(),
                            Optional.of(new Problem(ProblemCode.UNKNOWN,
                                "TEST_ORIGINAL_" + boundary + "_ACK_LOST",
                                "Original fixed tool effect receipt lost", Map.of(), Optional.empty())));
                    }
                }
                return returned;
            });
    }

    private AiService models(AiService original) {
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

            public String configurationRevision(AiPurpose purpose, TaskContext task) throws ApiException {
                assertTrue(Set.of(AiPurpose.LANGUAGE, AiPurpose.APPROVAL).contains(purpose));
                fixedReviews.sameTask(task);
                String revision = original.configurationRevision(purpose, task);
                assertTrue(revision.matches("[a-f0-9]{64}"));
                modelRevision.compareAndSet(null, revision);
                assertEquals(modelRevision.get(), revision, "Both original roles bind the actual complete configured route");
                return revision;
            }

            public OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException {
                return call(request, configurationRevision(request.purpose(), task), task);
            }

            public OperationResult<AiResponse> call(AiRequest request, String expected, TaskContext task) {
                assertEquals(modelRevision.get(), expected);
                try {
                    var facts = json.readTree(request.messages().getLast().text());
                    fixedReviews.sameTask(task);
                    if (request.purpose() == AiPurpose.APPROVAL) {
                        fixedReviews.review(request, facts, task);
                        independent.incrementAndGet();
                        return success(DeploymentAssistedGoModelFixture.nativeResponse(request, "controlled-approval",
                            "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Exact controlled original action\"}"));
                    }
                    assertFalse(recovering, "Cross-boot recovery cannot ask LANGUAGE for source, build or publication decisions");
                    assertEquals(AiPurpose.LANGUAGE, request.purpose());
                    if (facts.has("projectTypes")) {
                        int turn = sourceScans.getAndIncrement();
                        String output = turn == 0
                            ? "{\"tool\":\"read\",\"arguments\":{\"path\":\"go.mod\",\"offset\":0,\"limit\":10}}"
                            : "{\"tool\":\"identify\",\"arguments\":{\"components\":[{\"id\":\"app\",\"root\":\".\",\"type\":\"GO_SERVICE\",\"evidence\":[\"go.mod\"]}]}}";
                        return success(DeploymentAssistedGoModelFixture.nativeResponse(request, "controlled-scan", output));
                    }
                    if (facts.path("state").path("availableTools").isArray()) {
                        toolCalls.incrementAndGet();
                        return success(DeploymentAssistedGoModelFixture.nativeResponse(request, "controlled-tools",
                            DeploymentAssistedGoModelFixture.publicationTool(request, facts)));
                    }
                    aiCalls.incrementAndGet();
                    String response;
                    if (facts.path("phase").asText().equals("BUILD")) {
                        failures.incrementAndGet();
                        response = "{\"decision\":\"RETRY\",\"reason\":\"Only exact human tool correction\",\"suggestions\":{}}";
                    } else
                        response = "{\"action\":\"ADVISE\",\"sourceRevision\":\""
                            + facts.path("sourceRevision").asText()
                            + "\",\"arguments\":{\"suggestions\":[],\"summary\":\"Keep human review\",\"unresolved\":[]}}";
                    return success(DeploymentAssistedGoModelFixture.nativeResponse(request, "controlled-purpose", response));
                } catch (Throwable failure) {
                    lastModelFailure = failure;
                    if (failure instanceof RuntimeException runtime) throw runtime;
                    if (failure instanceof Error error) throw error;
                    throw new AssertionError(failure);
                }
            }
        };
    }

    private DeploymentStandardInputs.Reviewer reviewer() {
        return new DeploymentStandardInputs.Reviewer() {
            public OperationResult<DeploymentStandardInputs.Selection> select(ServerId server, String sha,
                                                                              List<DiscoveredProjectPart> components, TaskContext task) {
                assertEquals(1, components.size());
                String id = components.getFirst().id();
                return success(new DeploymentStandardInputs.Selection(id,
                    Map.of(id, new DeploymentStandardInputs.Choice(DeploymentProjectKind.GO_SERVICE, Set.of())),
                    Optional.empty()));
            }

            public OperationResult<Map<String, DeploymentStandardInputs.Answers>> review(ServerId server, String sha,
                                                                                         List<DeploymentStandardInputs.Prompt> prompts, TaskContext task) {
                assertEquals(1, prompts.size());
                var prompt = prompts.getFirst();
                var values = new LinkedHashMap<>(prompt.defaults());
                values.put("primary", prompt.componentId());
                values.put("secondary", "main.go");
                values.put("version", "1.24");
                values.put("jvmTarget", "");
                values.put("port", "8080");
                values.put("healthMode", "TCP");
                values.put("exposure", "INTERNAL");
                var file = new DeploymentFileBinding("uploads",
                    new DeploymentStoragePath(prompt.componentId() + "-uploads",
                        DeploymentStoragePath.AccessMode.READ_WRITE, "uploads-v1", true),
                    DeploymentStorageLocation.defaults(), DeploymentStorageLocation.StorageResourceType.FILE);
                return success(Map.of(prompt.componentId(), new DeploymentStandardInputs.Answers(values, List.of(file),
                    new DeploymentBuildLimits(600, 32, 512, 65536, 128L * 1024 * 1024, false), false, true)));
            }

            public OperationResult<DeploymentStandardInputs.Answers> reviewBuildCorrection(ServerId server, String sha,
                                                                                           DeploymentStandardInputs.Prompt prompt, DeploymentStandardInputs.Answers original,
                                                                                           TaskContext task) {
                var values = new LinkedHashMap<>(original.values());
                values.put("version", "1.25");
                return success(new DeploymentStandardInputs.Answers(values, original.files(), original.limits(),
                    original.containerRisk(), original.adapterRisk()));
            }

            public OperationResult<Boolean> confirm(ServerId server, String sha, Map<String, String> archives,
                                                    Map<String, DeploymentStandardInputs.Answers> answers,
                                                    Map<String, DeploymentPreparedDatabase> databases, Optional<DeploymentStandardInputs.GitReview> git,
                                                    TaskContext task) {
                return success(true);
            }
        };
    }

    private void recover() throws Exception {
        recovering = true;
        var before = json.readTree(Files.readString(record("before")));
        UUID id = UUID.fromString(before.path("originalTask").textValue());
        modelRevision.set(before.path("execution").path("request").path("buildIntent").path("modelRevision").textValue());
        assertTrue(modelRevision.get().matches("[a-f0-9]{64}"));
        var runtime = open();
        if (!scenario.equals("LEGACY")) {
            peer = DeploymentAssistedBuildPeer.reopen(record("peer"));
            if (scenario.equals("HOST_CONFLICT")) peer.fingerprint = "SHA256:" + "C".repeat(43);
            if (scenario.equals("ACTIVE_INTERRUPTED")) peer.activeTrackedOperation = true;
        }
        var report = scenario.equals("LEGACY") ? runtime.startDevelopmentMods(workspace(), pages()) : startControlledRecovery(runtime);
        assertTrue(report.running().isEmpty());
        assertEquals(List.of("MOD_RECOVERY_READY"), report.problems().stream().map(Problem::eventCode).toList());
        assertEquals(List.of(id), new TaskLedger(runtime.persistence().database()).queries().unfinished());
        var original = snapshot(runtime, id);
        assertEquals(before.path("modelRoutes"), original.path("modelRoutes"),
            "Every actual routing-revision field and both usable original roles survive the restart");
        if (!scenario.equals("LEGACY")) {
            recoverTracked(runtime, id, before, original);
            return;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = runtime.tasks().reconcile(id).toCompletableFuture().get(25, TimeUnit.SECONDS);
            assertEquals(id, result.id());
            assertEquals(TaskState.UNKNOWN, result.state(), result.toString());
            assertTrue(result.outcome().isEmpty());
            var after = snapshot(runtime, id);
            assertEquals(original, after,
                "Original formal intent, reference UUIDs and retained FILE/checkpoint identities cannot change");
        }
        assertEquals(before.path("execution"), original.path("execution"));
        assertEquals(before.path("references"), original.path("references"));
        assertEquals(before.path("resources"), original.path("resources"));
        Files.writeString(record("after"), original.toString());
        assertBusy(runtime);
    }

    /** Explicitly models a pre-extension envelope, preserving the original UNKNOWN result and all prior evidence. */
    private void removeTrackedExtension(PlatformRuntime runtime, UUID task) throws Exception {
        try (var access = scope(runtime, false); var connection = access.openConnection();
             var read = connection.prepareStatement("SELECT execution_id,request_payload,result_payload FROM deployment_execution WHERE platform_task_id=?")) {
            read.setObject(1, task);
            try (var rows = read.executeQuery()) {
                assertTrue(rows.next());
                var request = (ObjectNode) json.readTree(rows.getBytes(2));
                assertTrue(request.remove("trackedToolchains").isObject());
                byte[] originalResult = rows.getBytes(3);
                try (var write = connection.prepareStatement("UPDATE deployment_execution SET request_payload=? WHERE execution_id=?")) {
                    write.setBytes(1, json.writeValueAsBytes(request));
                    write.setObject(2, rows.getObject(1, UUID.class));
                    assertEquals(1, write.executeUpdate());
                }
                Files.write(root.resolve("legacy-original-unknown-result.json"), originalResult);
            }
        }
    }

    private void recoverTracked(PlatformRuntime runtime, UUID id, JsonNode before, JsonNode original) throws Exception {
        int originalCommands = peer.commands.size();
        int prepares = peer.trackedSends.getOrDefault("prepare-tracked", 0), binds = peer.trackedSends.getOrDefault("bind-tracked", 0);
        var result = runtime.tasks().reconcile(id).toCompletableFuture().get(25, TimeUnit.SECONDS);
        assertEquals(id, result.id());
        assertEquals(0, aiCalls.get(), "Recovery never asks LANGUAGE to plan or replay a build");
        assertEquals(0, failures.get());
        assertEquals(0, sourceScans.get());
        assertEquals(0, toolCalls.get());
        assertEquals(prepares, peer.trackedSends.getOrDefault("prepare-tracked", 0));
        assertEquals(binds, peer.trackedSends.getOrDefault("bind-tracked", 0));
        assertEquals(List.of(1), List.copyOf(peer.builds.values()));
        assertEquals(List.of(1), List.copyOf(peer.uploads.values()));
        assertTrue(peer.publications.isEmpty());
        assertEquals(peer.commands.size() - originalCommands, fixedReviews.sends.get());
        assertTrue(independent.get() >= fixedReviews.sends.get());
        peer.save(record("peer"));
        if (scenario.equals("HOST_CONFLICT") || scenario.equals("ACTIVE_INTERRUPTED") || scenario.equals("CLEANUP_LOST") && phase.equals("recover")) {
            assertEquals(TaskState.UNKNOWN, result.state(), result.toString());
            assertTrue(result.outcome().isEmpty());
            if (scenario.equals("HOST_CONFLICT")) {
                assertEquals(0, fixedReviews.sends.get());
                assertEquals(0, independent.get());
                assertEquals(original, snapshot(runtime, id));
            } else if (scenario.equals("ACTIVE_INTERRUPTED")) {
                assertEquals(0, peer.trackedSends.getOrDefault("cleanup-tracked", 0));
                assertEquals(0, binds);
                assertTrue(peer.trackedSends.getOrDefault("observe-tracked", 0) > 0);
                assertEquals(1, peer.present.size());
                assertTrue(peer.cleanups.isEmpty());
                var retained = snapshot(runtime, id);
                assertEquals(original.path("execution").path("resultRaw"), retained.path("execution").path("resultRaw"));
                assertEquals(original.path("execution").path("request").path("buildIntent"), retained.path("execution").path("request").path("buildIntent"));
                assertEquals(original.path("references"), retained.path("references"));
            } else {
                assertEquals(1, lost.get());
                assertEquals(1, peer.trackedSends.getOrDefault("cleanup-tracked", 0));
            }
            Files.writeString(record(phase.equals("recover-again") ? "after-again" : "after"), snapshot(runtime, id).toString());
            assertBusy(runtime);
            return;
        }
        assertEquals(TaskState.ENDED, result.state(), () -> result + " " + DeploymentAssistedGoModelFixture.diagnostics(runtime, id)
            + "; review failure=" + fixedReviews.lastFailure + "; peer failure=" + peer.lastFailure + "; model failure=" + lastModelFailure);
        assertEquals(Optional.of(TaskResult.FAILED), result.outcome(), "Recovering the environment settles the original failed build; it never publishes success");
        assertTrue(peer.present.isEmpty(), "Only the original candidate is cleaned");
        assertEquals(List.of(1), List.copyOf(peer.cleanups.values()));
        assertTrue(fixedReviews.sends.get() > 0);
        if (boundary == DeploymentAssistedEnvironmentRecoveryRestartTest.BoundaryType.PREPARE) {
            assertEquals(0, binds);
            assertEquals(1, peer.trackedSends.getOrDefault("cleanup-tracked", 0));
        }
        var after = completedSnapshot(runtime, id, before);
        Files.writeString(record(phase.equals("recover-again") ? "after-again" : "after"), after.toString());
        controlledRecoveryFlow.close();
        runtime.close();
    }

    /** Substitutes transport ports before the real original Mod registers its recovery; ownership stays with its host lease. */
    private gold.debug.windowstolinux.app.main.mod.PlatformModFlow.Report startControlledRecovery(PlatformRuntime runtime) throws Exception {
        var originalHost = runtime.capabilities();
        var sessions = new LinkedHashMap<String, gold.debug.windowstolinux.app.mod.run.ModRuntimeSession>();
        var host = (gold.debug.windowstolinux.app.main.mod.PlatformModHost) Proxy.newProxyInstance(
            gold.debug.windowstolinux.app.main.mod.PlatformModHost.class.getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.app.main.mod.PlatformModHost.class}, (proxy, method, args) -> {
                if (method.getName().equals("modSessions")) return Map.copyOf(sessions);
                if (method.getName().equals("modSession")) {
                    var loader = (gold.debug.windowstolinux.app.mod.load.ModClassLoader) args[0];
                    @SuppressWarnings("unchecked")
                    var schema = (Optional<gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity>) args[1];
                    var mode = (gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode) args[2];
                    var pageRegistry = (ModPageRegistry) args[3];
                    var api = (gold.debug.windowstolinux.app.mod.version.SemanticVersion) args[4];
                    assertEquals(gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode.RECOVERY, mode);
                    var originalLease = originalHost.modContext(loader, schema, mode, pageRegistry);
                    var originalContext = originalLease.context();
                    var selected = loader.manifest().id().equals(OWNER) ? controlledRecoveryContext(originalContext) : originalContext;
                    var lease = (gold.debug.windowstolinux.app.mod.run.ModRuntimeSession.ContextLease) Proxy.newProxyInstance(
                        gold.debug.windowstolinux.app.mod.run.ModRuntimeSession.ContextLease.class.getClassLoader(),
                        new Class<?>[]{gold.debug.windowstolinux.app.mod.run.ModRuntimeSession.ContextLease.class}, (leaseProxy, leaseMethod, leaseArgs) -> {
                            if (leaseMethod.getName().equals("context")) return selected;
                            try {
                                return leaseMethod.invoke(originalLease, leaseArgs);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
                    var session = new gold.debug.windowstolinux.app.mod.run.ModRuntimeSession(loader, lease, api,
                        originalLease.journal(() -> sessions.remove(loader.manifest().id())));
                    assertNull(sessions.putIfAbsent(loader.manifest().id(), session));
                    return session;
                }
                try {
                    return method.invoke(originalHost, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        var build = new Properties();
        try (var input = ModContext.class.getResourceAsStream("/gold/debug/windowstolinux/appapi/build.properties")) {
            assertNotNull(input);
            build.load(input);
        }
        controlledRecoveryFlow = new gold.debug.windowstolinux.app.main.mod.PlatformModFlow(host, workspace(), true,
            gold.debug.windowstolinux.app.db.mod.ModRestartLedger.initiallyDisabled(runtime.persistence().database()), pages(),
            gold.debug.windowstolinux.app.mod.version.SemanticVersion.parse(build.getProperty("platform.api.version")));
        return controlledRecoveryFlow.start();
    }

    private ModContext controlledRecoveryContext(ModContext actual) {
        var linux = new LinuxService() {
            public RemoteSession connect(gold.debug.windowstolinux.appapi.linux.session.RemoteTarget expected, TaskContext task) throws ApiException {
                return fixedReviews.connect(expected, task, actual, this);
            }

            public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                return interrupted(fixedReviews.session(peer.session(task), task));
            }

            public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                return List.of();
            }
        };
        var ai = models(actual.ai());
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("linux")) return linux;
            if (method.getName().equals("ai")) return ai;
            try {
                return method.invoke(actual, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
    }

    private ObjectNode completedSnapshot(PlatformRuntime runtime, UUID task, JsonNode before) throws Exception {
        var result = json.createObjectNode();
        try (var access = scope(runtime, true); var connection = access.openConnection();
             var statement = connection.prepareStatement("SELECT * FROM deployment_execution WHERE platform_task_id=?")) {
            statement.setObject(1, task);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                var record = DeploymentExecutionStore.row(rows);
                assertEquals(TaskResult.FAILED, record.outcome().orElseThrow());
                assertEquals("FINISHED", record.phase());
                assertTrue(record.cleanupComplete());
                assertFalse(record.businessCommitted());
                var request = json.readTree(record.requestJson());
                var state = gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentTrackedToolchainState.read(record,
                    DeploymentTrackedToolchains.sha256(before.path("execution").path("request").path("buildIntent").toString().getBytes(StandardCharsets.UTF_8))).orElseThrow();
                assertTrue(state.allSettled());
                assertEquals(before.path("execution").path("recovery").path("referenceIds"), json.readTree(record.recoveryJson()).path("referenceIds"));
                if (boundary == DeploymentAssistedEnvironmentRecoveryRestartTest.BoundaryType.BIND) {
                    assertEquals(1, request.path("buildReviews").size());
                    assertTrue(request.path("buildIntent").isNull());
                    var review = request.path("buildReviews").get(0);
                    assertEquals(before.path("execution").path("review").asText(), review.path("previousRoot").asText());
                    assertEquals(record.identity().reviewedRevision(), review.path("nextRoot").asText());
                    assertEquals(before.path("execution").path("request").path("buildIntent").path("failureEvidence"), review.path("failureEvidence"));
                } else {
                    assertEquals(before.path("execution").path("request").path("buildIntent"), request.path("buildIntent"));
                    assertTrue(request.path("buildReviews").isEmpty());
                }
                result.set("request", request);
                result.put("outcome", record.outcome().orElseThrow().name());
                result.put("phase", record.phase());
                result.put("originalUnknownResult", before.path("execution").path("resultRaw").textValue());
            }
        }
        try (var connection = runtime.persistence().database().openConnection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM platform_task_resource WHERE task_id=? AND released_at IS NULL AND resource_kind IN ('FILE','CUSTOM')")) {
            statement.setObject(1, task);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
        assertTrue(new TaskLedger(runtime.persistence().database()).queries().unfinished().isEmpty());
        return result;
    }

    private ObjectNode snapshot(PlatformRuntime runtime, UUID task) throws Exception {
        var result = json.createObjectNode();
        result.set("modelRoutes", modelRoutes(runtime));
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.prepareStatement(
                 "SELECT execution_id,platform_task_id,server_profile_id,phase,outcome,reviewed_revision,request_payload,recovery_payload,business_committed,cleanup_complete,result_payload FROM deployment_execution WHERE platform_task_id=?")) {
            statement.setObject(1, task);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                var execution = result.putObject("execution");
                execution.put("id", rows.getString(1)).put("task", rows.getString(2)).put("server", rows.getString(3))
                    .put("phase", rows.getString(4)).put("outcome", rows.getString(5))
                    .put("review", rows.getString(6));
                assertEquals("EXECUTING", rows.getString(4));
                assertEquals("UNKNOWN", rows.getString(5));
                execution.set("request", json.readTree(rows.getBytes(7)));
                execution.set("recovery", json.readTree(rows.getBytes(8)));
                execution.put("businessCommitted", rows.getBoolean(9)).put("cleanupComplete", rows.getBoolean(10));
                execution.put("resultRaw", new String(rows.getBytes(11), StandardCharsets.UTF_8));
                assertFalse(rows.getBoolean(9));
                assertFalse(rows.getBoolean(10));
                assertFalse(rows.next());
                assertTrue(execution.path("request").path("buildIntent").isObject());
                assertEquals(0, execution.path("request").path("buildReviews").size());
                var intent = execution.path("request").path("buildIntent");
                assertEquals(1, intent.path("attempt").intValue());
                assertEquals(execution.path("review").textValue(), intent.path("previousRoot").textValue());
                assertNotEquals(intent.path("previousRoot").textValue(), intent.path("nextRoot").textValue());
                assertNotEquals(intent.path("previousPlan").textValue(), intent.path("nextPlan").textValue());
                assertEquals(1, json.readTree(intent.path("failureEvidence").textValue()).path("exit").intValue());
                assertEquals("1.24.1", DeploymentToolchainBindingCodec.decode(intent.path("previousTools").textValue())
                    .selections().getFirst().version().text());
                assertEquals(modelRevision.get(), intent.path("modelRevision").textValue());
            }
        }
        var references = result.putArray("references");
        try (var connection = runtime.persistence().database().openConnection();
             var statement = connection.prepareStatement(
                 "SELECT reference_id,server_profile_id,mod_id,schema_version,schema_generation,business_key,state FROM mod_server_reference WHERE mod_id=? ORDER BY reference_id")) {
            statement.setString(1, OWNER);
            try (var rows = statement.executeQuery()) {
                while (rows.next())
                    references.add(row(rows, 7));
            }
        }
        var originalIds = result.path("execution").path("recovery").path("referenceIds");
        assertTrue(originalIds.isArray());
        assertTrue(originalIds.size() > 0);
        var ids = new HashSet<String>();
        originalIds.forEach(value -> ids.add(value.path("id").textValue()));
        assertEquals(ids.size(), references.size());
        var keys = new HashSet<String>();
        result.path("execution").path("request").path("references").forEach(value -> {
            assertEquals("SERVER", value.path("kind").textValue());
            assertEquals("server", value.path("resourceId").textValue());
            keys.add(value.path("businessKey").textValue());
        });
        for (var reference : references) {
            assertTrue(ids.remove(reference.get(0).textValue()));
            assertEquals("server", reference.get(1).textValue());
            assertEquals(OWNER, reference.get(2).textValue());
            assertTrue(keys.remove(reference.get(5).textValue()));
            assertEquals("UNKNOWN", reference.get(6).textValue());
        }
        assertTrue(ids.isEmpty());
        assertTrue(keys.isEmpty());
        var resources = result.putArray("resources");
        boolean files = false, checkpoint = false;
        try (var connection = runtime.persistence().database().openConnection();
             var statement = connection.prepareStatement(
                 "SELECT lease_id,resource_kind,resource_id,owner_id,access_mode,recovery_json FROM platform_task_resource WHERE task_id=? AND released_at IS NULL AND resource_kind IN ('FILE','CUSTOM') ORDER BY lease_id")) {
            statement.setObject(1, task);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var value = row(rows, 5);
                    value.add(rows.getString(6));
                    resources.add(value);
                    if (rows.getString(2).equals("FILE"))
                        files = true;
                    if (rows.getString(3).equals(OWNER + "/checkpoint/deployment-publication-remote")) {
                        checkpoint = true;
                        var envelope = json.readTree(rows.getString(6));
                        String payload = envelope.path("fields").path("payload").textValue();
                        assertNotNull(payload);
                        var facts = json.readTree(payload);
                        assertEquals("MAY_HAVE_RUN", facts.path("environmentRecovery").textValue());
                        assertFalse(facts.has("environmentTools"));
                        assertTrue(payload.getBytes(StandardCharsets.UTF_8).length <= 12000);
                    }
                }
            }
        }
        assertTrue(files, "Original frozen source/archive FILE resources remain held");
        assertTrue(checkpoint, "Original author checkpoint remains held");
        return result;
    }

    private ObjectNode modelRoutes(PlatformRuntime runtime) throws Exception {
        var state = runtime.persistence().aiProfiles().routingState();
        assertFalse(state.routes().get(AiPurposeType.DEPLOYMENT).isEmpty());
        assertFalse(state.routes().get(AiPurposeType.APPROVAL).isEmpty());
        var result = json.createObjectNode().put("actualRevision", modelRevision.get());
        for (var purpose : AiPurposeType.values()) {
            var members = result.putArray(purpose.name());
            for (var member : state.members().get(purpose)) {
                var configured = member.configuration();
                var profile = configured.profile();
                var value = members.addObject().put("id", profile.id())
                    .put("endpoint", profile.endpoint()).put("model", profile.model())
                    .put("credentialKey", profile.credentialKey()).put("revision", configured.revision())
                    .put("outputLimit", runtime.persistence().aiProfiles().outputLimit(profile.id()).orElse(-1));
                for (var capability : AiCapabilityType.values())
                    value.put(capability.name(), configured.verified(capability));
            }
        }
        // Compare the same serialized representation used by the original cross-process evidence.
        // 与原跨进程依据使用相同序列化表示，避免整数节点宽度造成伪漂移。
        return (ObjectNode) json.readTree(json.writeValueAsBytes(result));
    }

    private ArrayNode row(ResultSet rows, int columns) throws SQLException {
        var values = json.createArrayNode();
        for (int i = 1; i <= columns; i++)
            values.add(rows.getString(i));
        return values;
    }

    private static void sentinelModel(PlatformRuntime runtime, int httpPort) throws Exception {
        String credential = "ai/environment-recovery-sentinel";
        try (var operation = runtime.security().enterOperation(); var store = runtime.security().openCredentials()) {
            char[] key = "environment-recovery-key".toCharArray();
            try {
                store.save(credential, key);
            } finally {
                Arrays.fill(key, '\0');
            }
        }
        var profile = new StoredAiModelProfile("environment-sentinel", "http://127.0.0.1:" + httpPort + "/chat", "fixture/environment", credential);
        runtime.persistence().aiProfiles().saveVerified(profile,
            "Environment recovery sentinel", java.time.Instant.now(), AiCapabilityType.TEXT);
        var configuration = runtime.persistence().aiProfiles().listConfigured().stream()
            .filter(value -> value.profile().id().equals(profile.id())).findFirst().orElseThrow();
        // The controlled native transport supplies this fixture evidence; no network capability probe is claimed.
        runtime.persistence().aiProfiles().recordVerification(profile, configuration.revision(), AiCapabilityType.TOOL_CALLING, java.time.Instant.now());
        assertTrue(runtime.persistence().aiProfiles().listConfigured().stream().filter(value -> value.profile().id().equals(profile.id()))
            .allMatch(value -> value.verified(AiCapabilityType.TEXT) && value.verified(AiCapabilityType.TOOL_CALLING)));
        runtime.persistence().aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT,
            List.of(new AiPurposeAssignment("environment-sentinel")));
        runtime.persistence().aiProfiles().purposes().save(AiPurposeType.APPROVAL,
            List.of(new AiPurposeAssignment("environment-sentinel")));
    }

    private Path record(String phase) {
        return root.resolve("environment-" + boundary + "-" + phase + ".json");
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    private void assertBusy(PlatformRuntime runtime) throws Exception {
        var originals = new TaskLedger(runtime.persistence().database()).queries().unfinished();
        assertFalse(originals.isEmpty(), "Actual original UNKNOWN ownership must remain before close");
        var retained = busyResources(runtime);
        assertFalse(retained.isEmpty(), "Actual retained FILE/checkpoint identities must exist before close");
        if (controlledRecoveryFlow != null) {
            var busy = assertThrows(SQLException.class, controlledRecoveryFlow::close);
            assertEquals("Original Mod recovery still owns resources", busy.getMessage());
            assertEquals(originals, new TaskLedger(runtime.persistence().database()).queries().unfinished());
            assertEquals(retained, busyResources(runtime), "Closing the original recovery graph cannot clear UNKNOWN ownership");
        }
        try {
            runtime.close();
            fail("Held UNKNOWN root resources cannot be reported as closed");
        } catch (ApiException busy) {
            assertEquals(ProblemCode.BUSY, busy.problem().code());
        } catch (SQLException busy) {
            assertEquals(controlledRecoveryFlow == null ? "Original Mod recovery still owns resources"
                : "Mod sessions and their original contexts must close before platform shutdown", busy.getMessage());
        }
        assertEquals(originals, new TaskLedger(runtime.persistence().database()).queries().unfinished(),
            "A failed platform close cannot release or replace the authentic UNKNOWN roots");
        assertEquals(retained, busyResources(runtime),
            "Failed close preserves every original FILE/checkpoint lease and recovery payload");
    }

    private static List<List<String>> busyResources(PlatformRuntime runtime) throws SQLException {
        var retained = new ArrayList<List<String>>();
        try (var connection = runtime.persistence().database().openConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                 "SELECT lease_id,task_id,resource_kind,resource_id,owner_id,access_mode,recovery_json FROM platform_task_resource WHERE released_at IS NULL AND resource_kind IN ('FILE','CUSTOM') ORDER BY lease_id")) {
            while (rows.next()) {
                var value = new ArrayList<String>();
                for (int column = 1; column <= 7; column++)
                    value.add(rows.getString(column));
                retained.add(value);
            }
        }
        return retained;
    }

    private static ModPageRegistry pages() {
        return new ModPageRegistry(new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, javax.swing.JComponent component) {
                throw new AssertionError("Recovery cannot open a new page");
            }

            public void focus(ModPageRegistry.PageKey key) {
            }

            public void remove(ModPageRegistry.PageKey key) {
            }

            public void environment(UiEnvironment environment) {
            }
        }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
    }
}
