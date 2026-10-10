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
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
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
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentPreparedDatabase;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication.DeploymentStandardPublishRecovery;

/** Uses two real hosts and deliberately exits with retained UNKNOWN resources; neither exit is reported as cleanup. / 使用两个真实宿主并刻意带留存UNKNOWN资源退出；任一退出均不冒充收尾。 */
public final class DeploymentAssistedEnvironmentRecoveryWorker extends DeploymentRuntimeFixture {
    private final ObjectMapper json = new ObjectMapper();

    private final AtomicInteger aiCalls = new AtomicInteger(), failures = new AtomicInteger(),
        lost = new AtomicInteger(), independent = new AtomicInteger();

    private final DeploymentAssistedWorkflowTest.FixedReviewFixture fixedReviews = new DeploymentAssistedWorkflowTest.FixedReviewFixture();

    private final AtomicReference<String> modelRevision = new AtomicReference<>();

    private DeploymentAssistedEnvironmentRecoveryRestartTest.BoundaryType boundary;

    private boolean recovering;

    /** Runs only the producer or the separately reopened recovery host. / 仅运行原任务宿主或独立重开恢复宿主。
     * @param args phase, original data root, lost receipt, original SSH endpoint and HTTP sentinel / 阶段、原数据根、丢失回执、原SSH端点及HTTP哨兵 */
    public static void main(String[] args) {
        try {
            assertEquals(5, args.length);
            var fixture = new DeploymentAssistedEnvironmentRecoveryWorker();
            fixture.root = Path.of(args[1]);
            fixture.boundary = DeploymentAssistedEnvironmentRecoveryRestartTest.BoundaryType.valueOf(args[2]);
            if (args[0].equals("produce"))
                fixture.produce(Integer.parseInt(args[3]), Integer.parseInt(args[4]));
            else {
                assertEquals("recover", args[0]);
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
        var peer = new DeploymentAssistedBuildPeer(false);
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
        var handle = new DeploymentStaticWorkflow(bound, flow).submit(new ServerId("server"),
            new DeploymentStaticWorkflow.Draft("", 65536, 100, 65536), Optional.empty(), reviewer(), true,
            "MANUAL_REVIEW");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (handle.snapshot().state() != TaskState.UNKNOWN && handle.snapshot().state() != TaskState.ENDED
            && System.nanoTime() < until)
            Thread.sleep(20);
        assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), handle.snapshot().toString());
        assertTrue(handle.snapshot().outcome().isEmpty());
        assertEquals(1, lost.get());
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
        var retained = snapshot(runtime, handle.id());
        retained.put("originalTask", handle.id().toString()).put("aiCalls", aiCalls.get()).put("independentReviews", independent.get()).put("builds", 1)
            .put("uploads", 1).put("lost", lost.get());
        retained.put("candidate", peer.present.iterator().next());
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
                    String script = ((CommandRequest) arguments[0]).script();
                    boolean target = boundary == DeploymentAssistedEnvironmentRecoveryRestartTest.BoundaryType.PREPARE
                        ? script.contains("prepare-toolchains 'prepare'") && script.contains("'1.25'")
                        : script.contains("prepare-toolchains 'bind'") && correctedBinding(script);
                    if (target) {
                        assertEquals(1, lost.incrementAndGet(),
                            "Only the original corrected send loses its physical receipt");
                        assertEquals(TaskResult.SUCCEEDED, ((OperationResult<?>) returned).outcome(),
                            "Physical effect precedes the lost acknowledgement");
                        return new OperationResult<CommandResult>(TaskResult.UNKNOWN, Optional.empty(),
                            Optional.of(new Problem(ProblemCode.UNKNOWN,
                                "TEST_ORIGINAL_" + boundary + "_ACK_LOST",
                                "Original fixed tool effect receipt lost", Map.of(), Optional.empty())));
                    }
                }
                return returned;
            });
    }

    private static boolean correctedBinding(String script) {
        String encoded = script.substring(script.indexOf("'bind' '") + 8);
        encoded = encoded.substring(0, encoded.indexOf('\''));
        var tools = DeploymentToolchainBindingCodec
            .decode(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        return tools.selections().stream()
            .anyMatch(tool -> tool.version().text().equals("1.25.1") && tool.sha256().equals("d".repeat(64)));
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
                        return success(new AiResponse("{\"decision\":\"APPROVE\",\"risk\":\"NORMAL\",\"reason\":\"Exact controlled original action\"}",
                            "controlled-approval", "stop", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
                    }
                    assertEquals(AiPurpose.LANGUAGE, request.purpose());
                    aiCalls.incrementAndGet();
                    String response;
                    if (facts.path("phase").asText().equals("BUILD")) {
                        failures.incrementAndGet();
                        response = "{\"decision\":\"RETRY\",\"reason\":\"Only exact human tool correction\",\"suggestions\":{}}";
                    } else
                        response = "{\"action\":\"ADVISE\",\"sourceRevision\":\""
                            + facts.path("sourceRevision").asText()
                            + "\",\"arguments\":{\"suggestions\":[],\"summary\":\"Keep human review\",\"unresolved\":[]}}";
                    return success(new AiResponse(response, "controlled-purpose", "stop",
                        new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
                } catch (Exception invalid) {
                    throw new AssertionError(invalid);
                }
            }
        };
    }

    private DeploymentStaticWorkflow.Reviewer reviewer() {
        return new DeploymentStaticWorkflow.Reviewer() {
            public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerId server, String sha,
                                                                              List<DiscoveredProjectPart> components, TaskContext task) {
                assertEquals(1, components.size());
                String id = components.getFirst().id();
                return success(new DeploymentStaticWorkflow.Selection(id,
                    Map.of(id, new DeploymentStaticWorkflow.Choice(DeploymentProjectKind.GO_SERVICE, Set.of())),
                    Optional.empty()));
            }

            public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerId server, String sha,
                                                                                         List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) {
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
                return success(Map.of(prompt.componentId(), new DeploymentStaticWorkflow.Answers(values, List.of(file),
                    new DeploymentBuildLimits(600, 32, 512, 65536, 128L * 1024 * 1024, false), false, true)));
            }

            public OperationResult<DeploymentStaticWorkflow.Answers> reviewBuildCorrection(ServerId server, String sha,
                                                                                           DeploymentStaticWorkflow.Prompt prompt, DeploymentStaticWorkflow.Answers original,
                                                                                           TaskContext task) {
                var values = new LinkedHashMap<>(original.values());
                values.put("version", "1.25");
                return success(new DeploymentStaticWorkflow.Answers(values, original.files(), original.limits(),
                    original.containerRisk(), original.adapterRisk()));
            }

            public OperationResult<Boolean> confirm(ServerId server, String sha, Map<String, String> archives,
                                                    Map<String, DeploymentStaticWorkflow.Answers> answers,
                                                    Map<String, DeploymentPreparedDatabase> databases, Optional<DeploymentStaticWorkflow.GitReview> git,
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
        var report = runtime.startDevelopmentMods(workspace(), pages());
        assertTrue(report.running().isEmpty());
        assertEquals(List.of("MOD_RECOVERY_READY"), report.problems().stream().map(Problem::eventCode).toList());
        assertEquals(List.of(id), new TaskLedger(runtime.persistence().database()).queries().unfinished());
        var original = snapshot(runtime, id);
        assertEquals(before.path("modelRoutes"), original.path("modelRoutes"),
            "Every actual routing-revision field and both usable original roles survive the restart");
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

    private ObjectNode snapshot(PlatformRuntime runtime, UUID task) throws Exception {
        var result = json.createObjectNode();
        result.set("modelRoutes", modelRoutes(runtime));
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.prepareStatement(
                 "SELECT execution_id,platform_task_id,server_profile_id,phase,outcome,reviewed_revision,request_payload,recovery_payload,business_committed,cleanup_complete FROM deployment_execution WHERE platform_task_id=?")) {
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
                var value = members.addObject().put("enabled", member.enabled()).put("id", profile.id())
                    .put("endpoint", profile.endpoint()).put("fileEndpoint", profile.fileEndpoint())
                    .put("realtimeEndpoint", profile.realtimeEndpoint()).put("model", profile.model())
                    .put("credentialKey", profile.credentialKey()).put("revision", configured.revision());
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
        runtime.persistence().aiProfiles().saveVerified(
            new StoredAiModelProfile("environment-sentinel", "http://127.0.0.1:" + httpPort + "/chat",
                "fixture/environment", credential),
            "Environment recovery sentinel", java.time.Instant.now(), AiCapabilityType.TEXT);
        runtime.persistence().aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT,
            List.of(new AiPurposeAssignment("environment-sentinel", true)));
        runtime.persistence().aiProfiles().purposes().save(AiPurposeType.APPROVAL,
            List.of(new AiPurposeAssignment("environment-sentinel", true)));
    }

    private Path record(String phase) {
        return root.resolve("environment-" + boundary + "-" + phase + ".json");
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    private static void assertBusy(PlatformRuntime runtime) throws Exception {
        var originals = new TaskLedger(runtime.persistence().database()).queries().unfinished();
        assertFalse(originals.isEmpty(), "Actual original UNKNOWN ownership must remain before close");
        var retained = busyResources(runtime);
        assertFalse(retained.isEmpty(), "Actual retained FILE/checkpoint identities must exist before close");
        try {
            runtime.close();
            fail("Held UNKNOWN root resources cannot be reported as closed");
        } catch (ApiException busy) {
            assertEquals(ProblemCode.BUSY, busy.problem().code());
        } catch (SQLException busy) {
            assertEquals("Original Mod recovery still owns resources", busy.getMessage());
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
