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
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
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
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.api.Test;

/** Reopens constructed original state with real CLASS/H2, fixed-command AI approval and loopback SSH, without a new plan. / 以真实CLASS/H2、固定命令AI审批及回环SSH重开构造的原状态，不生成新计划。 */
class DeploymentAgentRecoveryRestartTest extends DeploymentRuntimeFixture {
    private final AtomicInteger stage = new AtomicInteger();

    private final AtomicInteger cleanup = new AtomicInteger();

    private final AtomicInteger replay = new AtomicInteger();

    private volatile boolean present = true;

    private final Queue<String> approvedScripts = new ConcurrentLinkedQueue<>();

    private final List<String> reviewedScripts = new CopyOnWriteArrayList<>();

    private final List<String> sentScripts = new CopyOnWriteArrayList<>();

    private final AtomicReference<Throwable> peerFailure = new AtomicReference<>();

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
                return true;
            }
        };
    }

    /** Registered recovery retains missing and active original candidate evidence, then cleans only the same task. / 正式登记恢复保留缺失及活动的原候选依据，随后仅清理同一原任务。
     * @throws Exception if isolated child or real recovery fails / 隔离子进程或真实恢复失败时 */
    @Test
    void restartKeepsUnknownCandidateUntilExactInactiveEvidenceArrives() throws Exception {
        try (var approval = new ApprovalPeer(); var ssh = new LoopbackSshServer(root, server -> {
            server.setPasswordAuthenticator(
                (user, password, session) -> user.equals("root") && password.equals("fixture-password"));
            server.setCommandFactory((channel, command) -> new FramedReply());
        })) {
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("java.class.path"), getClass().getName(), "--original", root.toString(),
                Integer.toString(ssh.server().getPort()), approval.endpoint()).redirectErrorStream(true).start();
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
            try (var runtime = open()) {
                var report = runtime.startDevelopmentMods(workspace(), pages());
                assertTrue(report.running().isEmpty());
                assertEquals(List.of("MOD_RECOVERY_READY"),
                    report.problems().stream().map(Problem::eventCode).toList());
                UUID id = new TaskLedger(runtime.persistence().database()).queries().unfinished().getFirst();
                assertEquals(UUID.fromString(Files.readAllLines(root.resolve("original-candidate")).get(1)), id,
                    "Reopening must retain the exact original task UUID");
                assertEquals(TaskState.UNKNOWN,
                    runtime.tasks().reconcile(id).toCompletableFuture().get(25, TimeUnit.SECONDS).state());
                approval.healthy(2);
                assertEquals(0, cleanup.get());
                assertTrue(scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL") > 0);
                stage.set(1);
                assertEquals(TaskState.UNKNOWN,
                    runtime.tasks().reconcile(id).toCompletableFuture().get(25, TimeUnit.SECONDS).state());
                approval.healthy(4);
                assertEquals(0, cleanup.get());
                stage.set(2);
                var settled = runtime.tasks().reconcile(id).toCompletableFuture().get(25, TimeUnit.SECONDS);
                approval.healthy(9);
                assertEquals(TaskState.ENDED, settled.state(), settled.toString());
                assertEquals(Optional.of(TaskResult.FAILED), settled.outcome());
                assertEquals(1, cleanup.get());
                assertEquals(0, replay.get());
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                runtime.modRecovery().release();
            }
        }
    }

    /** Constructs durable interrupted preparation in an isolated JVM; this is not a simulated power loss. / 在隔离 JVM 构造持久中断准备；不冒充断电。
     * @param args marker, exact data directory, SSH port and independent approval endpoint / 标记、精确数据目录、SSH端口及独立审批端点 */
    public static void main(String[] args) {
        if (args.length != 4 || !args[0].equals("--original"))
            throw new IllegalArgumentException();
        try {
            var fixture = new DeploymentAgentRecoveryRestartTest();
            fixture.root = Path.of(args[1]);
            fixture.original(Integer.parseInt(args[2]), args[3]);
            System.out.println("ORIGINAL_AGENT_STATE_RETAINED");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private void original(int port, String approvalEndpoint) throws Exception {
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
            runtime.persistence().aiProfiles().saveOutputLimit(role, 1600);
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
            "Constructed interrupted agent preparation", List.of(server.id()), true), task -> {
            String target;
            try (var session = context.linux().connect(server, task)) {
                target = DeploymentAgentWorkflow.targetDigest(server, session);
            }
            var selected = context.file().sources().selectDirectory("Original agent source", task);
            var material = new DeploymentSourceSetup(context.file()).prepare(selected, 65536, 100, task);
            var archive = new DeploymentSourceArchiver(context.file()).prepare(material, 65536, task);
            var journal = new DeploymentAgentActionLog(context.db());
            String configuration = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
            assertEquals(configuration, context.ai().configurationRevision(AiPurpose.APPROVAL, task));
            journal.create(task,
                new DeploymentAgentActionLog.Identity(server.id(), target, configuration, "MANUAL_REVIEW", "GENERAL"));
            Files.writeString(root.resolve("original-configuration"), configuration);
            journal.event(task, "SOURCE", archive.sha256(), material.snapshot().sha256());
            journal.event(task, "TARGET_PROFILE", "", DeploymentAgentWorkflow.targetProfile(context.servers().get(server, task)));
            journal.event(task, "PLAN_APPLICATION", "", "demo");
            journal.event(task, "PREPARE_EXECUTING", "b".repeat(64), "api");
            String candidate = new DeploymentRemoteWorkspace("demo-api", archive.sha256()).candidateId();
            journal.event(task, "CANDIDATE", "api", candidate);
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
        var original = Files.readAllLines(root.resolve("original-candidate"));
        String prefix = "'" + DeploymentManagedHelperBundle.PATH + "' '";
        String suffix = "' 'demo-api' '" + original.get(0) + "' '" + original.get(1) + "'";
        assertTrue(script.equals(prefix + "candidate-query-task" + suffix) || script.equals(prefix + "candidate-cleanup-task" + suffix),
            "Recovery may review only the complete original candidate query or cleanup command");
        if (script.equals(prefix + "candidate-cleanup-task" + suffix))
            assertEquals(2, stage.get(), "Cleanup cannot be approved while original evidence is missing or active");
    }

    private static String probeScript() {
        return "set -euo pipefail\n" + "printf '%s  %s\\n' '" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "' '"
            + DeploymentManagedHelperBundle.PATH + "' | /usr/bin/sha256sum --check --status\n" + "'"
            + DeploymentManagedHelperBundle.PATH + "' probe\n" + "printf 'SHA256="
            + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\\n'\n";
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
                    assertEquals("fixture/approval", request.path("model").asText(), "Recovery must never call the LANGUAGE planner");
                    var facts = json.readTree(request.path("messages").get(1).path("content").asText());
                    var keys = new HashSet<String>();
                    facts.fieldNames().forEachRemaining(keys::add);
                    assertEquals(Set.of("policy", "operation", "targets", "businessRevision", "exactAction", "inputKind"), keys);
                    assertEquals("MANUAL_REVIEW", facts.path("policy").asText());
                    assertEquals("EMPTY", facts.path("inputKind").asText());
                    assertEquals(Files.readString(root.resolve("original-configuration")), facts.path("businessRevision").asText());
                    assertEquals(json.createArrayNode().add("server"), facts.path("targets"));
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
                    var decision = json.createObjectNode().put("decision", "APPROVE").put("risk", "NORMAL").put("reason", "Exact original fixed recovery facts verified");
                    var reply = json.createObjectNode();
                    reply.putArray("choices").addObject().put("finish_reason", "stop").putObject("message").put("role", "assistant").put("content", decision.toString());
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
