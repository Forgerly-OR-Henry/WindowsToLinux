package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.main.start.LoopbackSshServer;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferProgress;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStall;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchiver;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceSetup;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationObservation;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.api.Test;

/** Reopens constructed original state with real CLASS/H2, fixed-command AI approval and loopback SSH, without a new plan. / 以真实CLASS/H2、固定命令AI审批及回环SSH重开构造的原状态，不生成新计划。 */
class DeploymentAgentRecoveryRestartTest extends DeploymentRuntimeFixture {
    @Test
    void publicationObservationCodecPreservesUnknownAndRejectsMalformedFrames() throws Exception {
        var events = observationFrames();
        var header = new ObjectMapper().readTree(events.get(3).detail());
        UUID task = UUID.fromString(header.path("task").textValue());
        var decoded = DeploymentAgentPublicationObservation.readLatest(events, task);
        assertFalse(decoded.observationComplete());
        assertEquals(0, decoded.observedComponents());
        assertEquals(TaskResult.UNKNOWN, DeploymentAgentPublicationObservation.unknown(true).outcome());
        assertEquals(TaskResult.UNKNOWN, DeploymentAgentPublicationObservation.unknown(false).outcome());
        for (String invalid : List.of(
            events.get(3).detail().replace("\"kind\":\"BEGIN\"", "\"kind\":\"BEGIN\",\"kind\":\"BEGIN\""),
            events.get(3).detail().replace("\"formatVersion\":1", "\"formatVersion\":2"),
            events.get(3).detail().replace("\"prefixEndEvent\":3", "\"prefixEndEvent\":\"3\""),
            events.get(3).detail().replace("\"server\":\"controlled\",", ""),
            events.get(3).detail().replace("\"kind\":\"BEGIN\"", "\"kind\":\"BEGIN\",\"unknown\":true")))
            assertThrows(Exception.class, () -> DeploymentAgentPublicationObservation.validateFrame(invalid));
        assertThrows(Exception.class, () -> DeploymentAgentPublicationObservation.readLatest(events, UUID.randomUUID()));
    }

    @Test
    void publicationObservationRejectsReplayPrefixDriftAndNeverFallsBackToEarlierCompleteFrames() throws Exception {
        var original = observationFrames();
        UUID task = UUID.fromString(new ObjectMapper().readTree(original.get(3).detail()).path("task").textValue());
        for (String change : List.of("prefix", "candidate", "invocation", "duplicate", "order", "complete", "new-begin")) {
            var events = new ArrayList<>(original);
            int at = change.equals("prefix") ? 0 : change.equals("complete") ? 6 : 5;
            var old = events.get(at);
            String changed = switch (change) {
                case "prefix" -> old.detail() + "-changed";
                case "candidate" -> old.detail().replace("candidate-web", "candidate-other");
                case "invocation" -> old.detail().replace(old.action(), UUID.randomUUID().toString());
                case "duplicate" -> old.detail().replace("\"component\":\"web\"", "\"component\":\"backend\"");
                case "complete" ->
                    old.detail().replace("\"observationComplete\":false", "\"observationComplete\":true");
                default -> old.detail();
            };
            events.set(at, new DeploymentAgentActionLog.Event(old.id(), old.type(), old.action(), changed));
            if (change.equals("order")) Collections.swap(events, 4, 5);
            if (change.equals("new-begin")) {
                var begin = events.get(3);
                events.add(new DeploymentAgentActionLog.Event(8, begin.type(), begin.action(), begin.detail()));
            }
            assertThrows(Exception.class, () -> DeploymentAgentPublicationObservation.readLatest(events, task), change);
        }
    }

    private static List<DeploymentAgentActionLog.Event> observationFrames() throws Exception {
        var events = new ArrayList<DeploymentAgentActionLog.Event>();
        events.add(new DeploymentAgentActionLog.Event(1, "AI_PUBLICATION", "controlled", "MODEL_GENERATED_COMMANDS"));
        events.add(new DeploymentAgentActionLog.Event(2, "CANDIDATE", "backend", "candidate-backend"));
        events.add(new DeploymentAgentActionLog.Event(3, "CANDIDATE", "web", "candidate-web"));
        var json = new ObjectMapper();
        String invocation = UUID.randomUUID().toString(), digest = "a".repeat(64);
        var begin = json.createObjectNode().put("formatVersion", 1).put("kind", "BEGIN").put("invocation", invocation)
            .put("task", UUID.randomUUID().toString()).put("execution", UUID.randomUUID().toString())
            .put("server", "controlled").put("application", "controlled").put("reviewRevision", digest)
            .put("templateSha256", digest).put("proposalSha256", digest).put("prefixEndEvent", 3)
            .put("prefixSha256", DeploymentAgentPublicationObservation.prefixSha256(events));
        begin.putArray("components").add("backend").add("web");
        String header = DeploymentAgentPublicationObservation.encodeFrame(begin);
        events.add(new DeploymentAgentActionLog.Event(4, DeploymentAgentPublicationObservation.BEGIN, invocation, header));
        int id = 5;
        for (String component : List.of("backend", "web")) {
            var row = json.createObjectNode().put("formatVersion", 1).put("kind", "COMPONENT").put("invocation", invocation)
                .put("beginEvent", 4).put("beginSha256", DeploymentAgentInputs.hash(header)).put("component", component)
                .put("unit", "controlled-" + component).put("expectedOwnerSha256", digest).put("candidate", "candidate-" + component)
                .put("releaseSha256", digest).put("layoutSha256", digest).put("missingReason", "NOT_ATTEMPTED_AFTER_STOP");
            row.putArray("originalSnapshotEvents");
            row.putNull("receipt");
            events.add(new DeploymentAgentActionLog.Event(id++, DeploymentAgentPublicationObservation.COMPONENT, invocation,
                DeploymentAgentPublicationObservation.encodeFrame(row)));
        }
        var end = json.createObjectNode().put("formatVersion", 1).put("kind", "END").put("invocation", invocation)
            .put("beginEvent", 4).put("beginSha256", DeploymentAgentInputs.hash(header)).put("observationComplete", false)
            .put("outcome", "UNKNOWN").put("authority", "NONE");
        end.putArray("componentEvents").add(5).add(6);
        events.add(new DeploymentAgentActionLog.Event(7, DeploymentAgentPublicationObservation.END, invocation,
            DeploymentAgentPublicationObservation.encodeFrame(end)));
        return List.copyOf(events);
    }

    private final AtomicInteger stage = new AtomicInteger();

    private final AtomicInteger cleanup = new AtomicInteger();

    private final AtomicInteger replay = new AtomicInteger();

    private volatile boolean present = true;

    private final Queue<String> approvedScripts = new ConcurrentLinkedQueue<>();

    private final List<String> reviewedScripts = new CopyOnWriteArrayList<>();

    private final List<String> sentScripts = new CopyOnWriteArrayList<>();

    private final AtomicReference<Throwable> peerFailure = new AtomicReference<>();

    private final AtomicBoolean allowRecoveryBinding = new AtomicBoolean(true);

    private final AtomicInteger bindingQuestions = new AtomicInteger();

    private volatile String reviewModel = "fixture/approval";

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
                throw new AssertionError();
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken token) {
                throw new AssertionError();
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken token) {
                if (intent.type().equals("deployment.agent.recovery.review")) {
                    bindingQuestions.incrementAndGet();
                    assertTrue(intent.exactAction().contains("originalTask=" + task.id()));
                    assertTrue(intent.exactAction().contains("allowed=original-status,original-settlement,original-cleanup"));
                    return allowRecoveryBinding.get();
                }
                return true;
            }
        };
    }

    /** Incomplete original preparation stays unknown even when a candidate could report inactivity. / 原准备不完整时，即使候选可能报告非活动也保持未知。
     * @throws Exception if isolated child or real recovery fails / 隔离子进程或真实恢复失败时 */
    @Test
    void restartKeepsUnconfirmedPreparationDespiteInactiveCandidateEvidence() throws Exception {
        try (var approval = new ApprovalPeer(); var ssh = new LoopbackSshServer(root, server -> {
            server.setPasswordAuthenticator(
                (user, password, session) -> user.equals("root") && password.equals("fixture-password"));
            server.setCommandFactory((channel, command) -> new FramedReply());
        })) {
            constructOriginal(ssh.server().getPort(), approval.endpoint(), false);
            try (var runtime = open()) {
                var report = runtime.startDevelopmentMods(workspace(), pages());
                assertTrue(report.running().isEmpty());
                assertEquals(List.of("MOD_RECOVERY_READY"),
                    report.problems().stream().map(Problem::eventCode).toList());
                UUID id = new TaskLedger(runtime.persistence().database()).queries().unfinished().getFirst();
                assertEquals(UUID.fromString(Files.readAllLines(root.resolve("original-candidate")).get(1)), id,
                    "Reopening must retain the exact original task UUID");
                var evidence = originalEvidence(runtime, id);
                assertFalse(DeploymentAgentActionLog.effectsSettled(evidence));
                assertEquals(1, evidence.stream().filter(event -> event.type().equals("PREPARE_EXECUTING")).count());
                assertTrue(evidence.stream().noneMatch(event -> Set.of("PREPARE_RESULT", "PREPARE_NOT_SENT").contains(event.type())));
                long retainedFiles = scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL");
                assertTrue(retainedFiles > 0);
                for (int status = 0; status < 3; status++) {
                    stage.set(status);
                    var retained = runtime.tasks().reconcile(id).toCompletableFuture().get(25, TimeUnit.SECONDS);
                    assertEquals(id, retained.id());
                    assertEquals(TaskState.UNKNOWN, retained.state(), retained.toString());
                    approval.healthy(0);
                    assertEquals(0, cleanup.get());
                    assertEquals(0, replay.get());
                    assertTrue(present, "Missing preparation result cannot authorize deletion of the original candidate");
                    assertTrue(sentScripts.isEmpty(), "Recovery cannot probe, query or clean before original preparation settles");
                    assertTrue(reviewedScripts.isEmpty());
                    assertEquals(0, bindingQuestions.get());
                    assertEquals(evidence, originalEvidence(runtime, id), "Recovery cannot backfill a missing original preparation receipt");
                    assertEquals(retainedFiles, scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
                }
                printRecoveryDiagnostics(runtime, id);
                var busy = assertThrows(java.sql.SQLException.class, runtime::close);
                assertEquals("Original Mod recovery still owns resources", busy.getMessage());
                assertEquals(evidence, originalEvidence(runtime, id));
                assertEquals(retainedFiles, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
            } catch (java.sql.SQLException busy) {
                assertEquals("Original Mod recovery still owns resources", busy.getMessage());
            }
        }
    }

    @Test
    void explicitRecoveryBindingPreservesOriginalAndRejectsLaterRouteChanges() throws Exception {
        try (var approval = new ApprovalPeer(); var ssh = new LoopbackSshServer(root, server -> {
            server.setPasswordAuthenticator(
                (user, password, session) -> user.equals("root") && password.equals("fixture-password"));
            server.setCommandFactory((channel, command) -> new FramedReply());
        })) {
            constructOriginal(ssh.server().getPort(), approval.endpoint(), true);
            try (var runtime = open()) {
                assertTrue(runtime.startDevelopmentMods(workspace(), pages()).running().isEmpty());
                UUID id = new TaskLedger(runtime.persistence().database()).queries().unfinished().getFirst();
                var evidence = originalEvidence(runtime, id);
                assertTrue(DeploymentAgentActionLog.effectsSettled(evidence), "Route recovery starts only from a constructed complete preparation");
                var source = evidence.stream().filter(event -> event.type().equals("SOURCE")).findFirst().orElseThrow();
                var executing = evidence.stream().filter(event -> event.type().equals("PREPARE_EXECUTING")).findFirst().orElseThrow();
                var prepared = evidence.stream().filter(event -> event.type().equals("PREPARE_RESULT")).findFirst().orElseThrow();
                assertEquals(DeploymentAgentInputs.hash(id + ":api:" + source.detail()), executing.action());
                assertEquals(executing.action(), prepared.action());
                assertEquals(source.detail(), prepared.detail(), "Constructed prepared source is the actual frozen fixture source");
                assertTrue(prepared.id() > executing.id());
                assertEquals(new DeploymentRemoteWorkspace("demo-api", source.action()).candidateId(),
                    evidence.stream().filter(event -> event.type().equals("CANDIDATE")).findFirst().orElseThrow().detail());
                String originalRevision = Files.readString(root.resolve("original-configuration"));
                reviewModel = "fixture/approval-recovery";
                runtime.persistence().aiProfiles().saveVerified(new StoredAiModelProfile("approval", approval.endpoint(),
                    reviewModel, "original-model"), "approval", Instant.now(), AiCapabilityType.TEXT);
                runtime.persistence().aiProfiles().saveVerified(new StoredAiModelProfile("approval", approval.endpoint(),
                    reviewModel, "original-model"), "approval", Instant.now(), AiCapabilityType.TOOL_CALLING);
                allowRecoveryBinding.set(false);
                assertEquals(TaskState.UNKNOWN, runtime.tasks().reconcile(id).toCompletableFuture().get(25, TimeUnit.SECONDS).state());
                approval.healthy(0);
                assertEquals(1, bindingQuestions.get());
                assertEquals(0, bindingCount(runtime));
                allowRecoveryBinding.set(true);
                stage.set(1);
                assertEquals(TaskState.UNKNOWN, runtime.tasks().reconcile(id).toCompletableFuture().get(25, TimeUnit.SECONDS).state());
                approval.healthy(2);
                assertEquals(2, bindingQuestions.get());
                assertEquals(0, cleanup.get());
                assertEquals(1, bindingCount(runtime));
                try (var access = scope(runtime, true);
                     var connection = access.openConnection();
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT configuration_digest FROM deployment_agent_task")) {
                    assertTrue(rows.next());
                    assertEquals(originalRevision, rows.getString(1));
                }
                allowRecoveryBinding.set(false);
                runtime.persistence().aiProfiles().purposes().save(AiPurposeType.APPROVAL, List.of(new AiPurposeAssignment("language")));
                assertEquals(TaskState.UNKNOWN, runtime.tasks().reconcile(id).toCompletableFuture().get(25, TimeUnit.SECONDS).state());
                approval.healthy(2);
                assertEquals(3, bindingQuestions.get(), "A changed route requires a new explicit authorization");
                assertEquals(1, bindingCount(runtime), "Refusing reauthorization retains the original binding");
                assertEquals(0, cleanup.get());
                assertTrue(scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL") > 0);
                allowRecoveryBinding.set(true);
                runtime.persistence().aiProfiles().purposes().save(AiPurposeType.APPROVAL, List.of(new AiPurposeAssignment("approval")));
                stage.set(2);
                var settled = runtime.tasks().reconcile(id).toCompletableFuture().get(25, TimeUnit.SECONDS);
                approval.healthy(7);
                assertEquals(TaskState.ENDED, settled.state(), settled.toString());
                assertEquals(Optional.of(TaskResult.FAILED), settled.outcome());
                assertEquals(1, cleanup.get());
                assertEquals(0, replay.get());
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                runtime.modRecovery().release();
            }
        }
    }

    private List<DeploymentAgentActionLog.Event> originalEvidence(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime, UUID task) throws Exception {
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.prepareStatement("SELECT event_id,event_type,action_id,event_detail FROM deployment_agent_event WHERE task_id=? ORDER BY event_id")) {
            statement.setString(1, task.toString());
            var events = new ArrayList<DeploymentAgentActionLog.Event>();
            try (var rows = statement.executeQuery()) {
                while (rows.next())
                    events.add(new DeploymentAgentActionLog.Event(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4)));
            }
            return List.copyOf(events);
        }
    }

    private long bindingCount(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime) throws Exception {
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_agent_event WHERE event_type='RECOVERY_REVIEW_BOUND'")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void printRecoveryDiagnostics(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime, UUID id) throws Exception {
        for (var category : runtime.logs().inventory(false).categories()) {
            runtime.logs().inspect(category.target().key()).records().stream()
                .filter(record -> record.event().taskId().filter(id::equals).isPresent())
                .forEach(record -> System.out.println("RECOVERY_FIXTURE_DIAGNOSTIC " + record));
        }
    }

    private void constructOriginal(int port, String endpoint, boolean completedPreparation) throws Exception {
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-cp", System.getProperty("java.class.path"), getClass().getName(), "--original", root.toString(),
            Integer.toString(port), endpoint, Boolean.toString(completedPreparation)).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(60, TimeUnit.SECONDS));
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains("ORIGINAL_AGENT_STATE_RETAINED"), output);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    /** Constructs a new pending or completed preparation fixture in an isolated JVM; no historical receipt is repaired. / 在隔离 JVM 新建未完成或已完成准备夹具，不修补历史回执。
     * @param args marker, exact data directory, SSH port, approval endpoint and explicit completed-fixture mode / 标记、数据目录、SSH端口、审批端点及明确完成夹具模式 */
    public static void main(String[] args) {
        if (args.length != 5 || !args[0].equals("--original") || !Set.of("true", "false").contains(args[4]))
            throw new IllegalArgumentException();
        try {
            var fixture = new DeploymentAgentRecoveryRestartTest();
            fixture.root = Path.of(args[1]);
            fixture.original(Integer.parseInt(args[2]), args[3], Boolean.parseBoolean(args[4]));
            System.out.println("ORIGINAL_AGENT_STATE_RETAINED");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private void original(int port, String approvalEndpoint, boolean completedPreparation) throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("README.md"), "Original candidate source\n");
        enable();
        var runtime = open();
        try (var operation = runtime.security().enterOperation(); var credentials = runtime.security().openCredentials()) {
            credentials.save("original-model", "fixture-model-key".toCharArray());
        }
        for (String role : List.of("language", "approval")) {
            runtime.persistence().aiProfiles().saveVerified(new StoredAiModelProfile(role, approvalEndpoint,
                "fixture/" + role, "original-model"), role, Instant.now(), AiCapabilityType.TEXT);
            runtime.persistence().aiProfiles().saveVerified(new StoredAiModelProfile(role, approvalEndpoint,
                "fixture/" + role, "original-model"), role, Instant.now(), AiCapabilityType.TOOL_CALLING);
        }
        runtime.persistence().aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT, List.of(new AiPurposeAssignment("language")));
        runtime.persistence().aiProfiles().purposes().save(AiPurposeType.APPROVAL, List.of(new AiPurposeAssignment("approval")));
        start(runtime, true);
        var server = new ServerId("server");
        runtime.servers().saveServerProfile(
            ServerProfile.fromStored(
                new StoredServerProfile(server.id(), "127.0.0.1", port, "root", "fixture-password")),
            "fixture-password".toCharArray());
        var context = runtime.modContexts().get(OWNER).context();
        var handle = context.tasks().submit(new TaskRequest(DeploymentAgentWorkflow.TASK_TYPE,
            completedPreparation ? "Constructed completed agent preparation before interruption" : "Constructed interrupted agent preparation", List.of(server.id()), true), task -> {
            String target;
            try (var session = context.linux().connect(server, task)) {
                target = DeploymentAgentInputs.targetDigest(server, session);
            }
            var selected = context.file().sources().selectDirectory("Original agent source", task);
            var material = new DeploymentSourceSetup(context.file()).prepare(selected, 65536, 100, task);
            var archive = new DeploymentSourceArchiver(context.file()).prepare(material, 65536, task);
            var journal = new DeploymentAgentActionLog(context.db());
            String configuration = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
            assertEquals(configuration, context.ai().configurationRevision(AiPurpose.APPROVAL, task));
            journal.create(task,
                new DeploymentAgentActionLog.Identity(server.id(), target, configuration, "MANUAL_REVIEW", true));
            Files.writeString(root.resolve("original-configuration"), configuration);
            journal.event(task, "SOURCE", archive.sha256(), material.snapshot().sha256());
            journal.event(task, "TARGET_PROFILE", "", DeploymentAgentInputs.targetProfile(context.servers().get(server, task)));
            journal.event(task, "PLAN_APPLICATION", "", "demo");
            String sourceRevision = material.snapshot().sha256();
            String preparation = DeploymentAgentInputs.hash(task.id() + ":api:" + sourceRevision);
            journal.event(task, "PREPARE_EXECUTING", preparation, "api");
            String candidate = new DeploymentRemoteWorkspace("demo-api", archive.sha256()).candidateId();
            if (completedPreparation) {
                // This newly constructed state represents completion on the same frozen fixture source, not a repaired old execution.
                journal.event(task, "PREPARE_RESULT", preparation, sourceRevision);
            }
            journal.event(task, "CANDIDATE", "api", candidate);
            if (completedPreparation)
                journal.event(task, "CURRENT_SOURCE", "api", sourceRevision);
            Files.writeString(root.resolve("original-candidate"), candidate + "\n" + task.id());
            journal.state(task, "UNKNOWN");
            return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "CONSTRUCTED_AGENT_INTERRUPTED",
                    "Constructed original preparation", Map.of(), Optional.empty())));
        });
        for (int i = 0; i < 100 && handle.snapshot().state() != TaskState.UNKNOWN; i++)
            Thread.sleep(50);
        assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
    }

    private String response(String script) throws Exception {
        requireOriginalScript(script);
        assertEquals(script, approvedScripts.remove(), "Actual SSH dispatch must follow its own complete independent AI review");
        sentScripts.add(script);
        if (script.equals(probeScript()))
            return "HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n";
        if (script.contains("candidate-query-task") || script.contains("candidate-cleanup-task")) {
            var original = Files.readAllLines(root.resolve("original-candidate"));
            assertTrue(script.contains("'" + original.get(0) + "'"));
            assertTrue(script.contains("'" + original.get(1) + "'"));
            if (script.contains("candidate-cleanup-task")) {
                assertEquals(2, stage.get());
                cleanup.incrementAndGet();
                present = false;
                return "CANDIDATE_CLEANED=1\n";
            }
            if (stage.get() == 0)
                return "";
            return "CANDIDATE_STATE=" + (present ? "owned" : "absent") + "\nBUILD_ACTIVE="
                + (stage.get() == 1 ? "yes" : "no") + "\n";
        }
        replay.incrementAndGet();
        throw new AssertionError("Recovery attempted new work: " + script);
    }

    private void requireOriginalScript(String script) throws Exception {
        if (script.equals(probeScript()))
            return;
        script = DeploymentManagedHelperBundle.verifiedBody(script);
        var original = Files.readAllLines(root.resolve("original-candidate"));
        String prefix = "'" + DeploymentManagedHelperBundle.PATH + "' '";
        String suffix = "' 'demo-api' '" + original.get(0) + "' '" + original.get(1) + "'";
        assertTrue(script.equals(prefix + "candidate-query-task" + suffix) || script.equals(prefix + "candidate-cleanup-task" + suffix),
            "Recovery may review only the complete original candidate query or cleanup command");
        if (script.equals(prefix + "candidate-cleanup-task" + suffix))
            assertEquals(2, stage.get(), "Cleanup cannot be approved while original evidence is missing or active");
    }

    private static String probeScript() {
        return DeploymentManagedHelperBundle.verifiedCommand("'"
            + DeploymentManagedHelperBundle.PATH + "' probe\n" + "printf 'SHA256="
            + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\\n'\n");
    }

    private final class ApprovalPeer implements AutoCloseable {
        private final HttpServer http;

        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        private final ObjectMapper json = new ObjectMapper();

        ApprovalPeer() throws Exception {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(executor);
            http.createContext("/chat", exchange -> {
                try {
                    var request = json.readTree(exchange.getRequestBody());
                    assertEquals("Bearer fixture-model-key", exchange.getRequestHeaders().getFirst("Authorization"));
                    assertEquals(reviewModel, request.path("model").asText(), "Recovery must never call the LANGUAGE planner");
                    assertEquals("submit_result", request.path("tools").get(0).path("function").path("name").asText());
                    var facts = json.readTree(request.path("messages").get(request.path("messages").size() - 1).path("content").asText());
                    var keys = new HashSet<String>();
                    facts.fieldNames().forEachRemaining(keys::add);
                    var expected = new HashSet<>(Set.of("operation", "serverTargets", "resourceTargets", "businessRevision", "businessRevisionMeaning", "presentationLocale", "exactAction", "inputKind", "authorizationPolicy", "effectSummary"));
                    if (facts.has("installedHelperPath")) {
                        expected.addAll(Set.of("platformContractRevision", "installedHelperPath", "installedHelperSha256"));
                        assertEquals("managed-helper-review.v2", facts.path("platformContractRevision").asText());
                        assertFalse(facts.has("installedHelperScript"));
                        assertEquals(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.EXPECTED_SHA256,
                            facts.path("installedHelperSha256").asText());
                    }
                    assertEquals(expected, keys);
                    assertFalse(facts.has("policy"));
                    assertEquals("EMPTY", facts.path("inputKind").asText());
                    assertEquals(Files.readString(root.resolve("original-configuration")), facts.path("businessRevision").asText());
                    assertEquals(json.createArrayNode().add("server"), facts.path("serverTargets"));
                    String exact = facts.path("exactAction").asText();
                    int boundary = exact.indexOf("\nscript:\n");
                    assertTrue(boundary > 0);
                    String script = exact.substring(boundary + "\nscript:\n".length());
                    requireOriginalScript(script);
                    boolean probe = script.equals(probeScript());
                    boolean cleanupCommand = script.contains("'candidate-cleanup-task'");
                    assertEquals(probe ? "deployment.helper.probe.v1" : "deployment.agent.helper.v1", facts.path("operation").asText());
                    assertEquals("WTL-SSH-COMMAND-1\ntimeout=" + (probe ? "PT30S" : cleanupCommand ? "PT45S" : "PT20S")
                        + "\noutputLimitBytes=4096\nstdinBytes=0\nstdinSha256="
                        + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0])) + "\nscript:\n" + script, exact);
                    reviewedScripts.add(script);
                    approvedScripts.add(script);
                    var decision = json.createObjectNode().put("decision", "APPROVE").put("requiresUserConfirmation", false).put("reason", "Exact original fixed recovery facts verified");
                    var reply = json.createObjectNode();
                    var message = reply.putArray("choices").addObject().put("finish_reason", "tool_calls").putObject("message").put("role", "assistant");
                    message.putNull("content");
                    message.putArray("tool_calls").addObject().put("id", "review_" + reviewedScripts.size()).put("type", "function")
                        .putObject("function").put("name", "submit_result").put("arguments", decision.toString());
                    reply.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 20);
                    byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Throwable failure) {
                    peerFailure.compareAndSet(null, failure);
                } finally {
                    exchange.close();
                }
            });
            http.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + http.getAddress().getPort() + "/chat";
        }

        void healthy(int expectedCommands) {
            assertNull(peerFailure.get(), () -> "Original approval/SSH peer failed: " + peerFailure.get());
            assertEquals(expectedCommands, reviewedScripts.size(), "Every repeated observation requires another independent AI approval");
            assertEquals(reviewedScripts, sentScripts, "Actual SSH commands must match the ordered complete AI evidence");
            assertTrue(approvedScripts.isEmpty());
        }

        public void close() throws Exception {
            http.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static ModPageRegistry pages() {
        return new ModPageRegistry(new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, javax.swing.JComponent component) {
            }

            public void focus(ModPageRegistry.PageKey key) {
            }

            public void remove(ModPageRegistry.PageKey key) {
            }

            public void environment(UiEnvironment environment) {
            }
        }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
    }

    private final class FramedReply implements Command {
        private InputStream input;

        private OutputStream output;

        private org.apache.sshd.server.ExitCallback callback;

        private Thread worker;

        public void setInputStream(InputStream stream) {
            input = stream;
        }

        public void setOutputStream(OutputStream stream) {
            output = stream;
        }

        public void setErrorStream(OutputStream stream) {
        }

        public void setExitCallback(org.apache.sshd.server.ExitCallback exit) {
            callback = exit;
        }

        public void start(ChannelSession channel, org.apache.sshd.server.Environment environment) {
            worker = new Thread(() -> {
                try {
                    var length = new ByteArrayOutputStream();
                    for (int next; (next = input.read()) != '\n'; ) {
                        if (next < '0' || next > '9' || length.size() >= 8)
                            throw new IOException("Bad script frame");
                        length.write(next);
                    }
                    int count = Integer.parseInt(length.toString(StandardCharsets.US_ASCII));
                    if (count < 1 || count > 16_777_216)
                        throw new IOException("Large script frame");
                    byte[] bytes = input.readNBytes(count);
                    if (bytes.length != count)
                        throw new IOException("Truncated script frame");
                    assertArrayEquals(new byte[0], input.readAllBytes(), "Original fixed recovery commands have no hidden data payload");
                    String result = response(new String(bytes, StandardCharsets.UTF_8));
                    output.write(result.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    callback.onExit(0);
                } catch (Throwable failure) {
                    peerFailure.compareAndSet(null, failure);
                    failure.printStackTrace();
                    callback.onExit(1);
                }
            }, "agent-recovery-controlled-ssh");
            worker.start();
        }

        public void destroy(ChannelSession channel) throws Exception {
            if (input != null)
                input.close();
            if (worker != null && worker != Thread.currentThread()) {
                worker.join(5000);
                assertFalse(worker.isAlive());
            }
        }
    }
}
