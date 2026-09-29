package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.main.startup.*;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.source.*;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.api.Test;

/** Reopens constructed interrupted original state with real CLASS/H2 and actual loopback SSH, without a fresh model. / 以真实 CLASS/H2 和实际回环 SSH 重开构造的中断原状态，不调用新模型。 */
class DeploymentAgentRecoveryRestartTest extends DeploymentRuntimeFixture {
    private final AtomicInteger stage = new AtomicInteger();

    private final AtomicInteger cleanup = new AtomicInteger();

    private final AtomicInteger replay = new AtomicInteger();

    private volatile boolean present = true;

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict c, ServerRef s, TaskSnapshot t,
                    CancellationToken token) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall c, ServerRef s, TaskSnapshot t,
                    CancellationToken token) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError();
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                    CancellationToken token) {
                throw new AssertionError();
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancellationToken token) {
                return true;
            }
        };
    }

    /** Registered recovery retains missing and active original candidate evidence, then cleans only the same task. / 正式登记恢复保留缺失及活动的原候选依据，随后仅清理同一原任务。
     * @throws Exception if isolated child or real recovery fails / 隔离子进程或真实恢复失败时 */
    @Test
    void restartKeepsUnknownCandidateUntilExactInactiveEvidenceArrives() throws Exception {
        try (var ssh = new LoopbackSshServer(root, server -> {
            server.setPasswordAuthenticator(
                    (user, password, session) -> user.equals("root") && password.equals("fixture-password"));
            server.setCommandFactory((channel, command) -> new FramedReply());
        })) {
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-cp", System.getProperty("java.class.path"), getClass().getName(), "--original", root.toString(),
                    Integer.toString(ssh.server().getPort())).redirectErrorStream(true).start();
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
                assertEquals(TaskState.UNKNOWN,
                        runtime.tasks().reconcile(id).toCompletableFuture().get(25, TimeUnit.SECONDS).state());
                assertEquals(0, cleanup.get());
                assertTrue(scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL") > 0);
                stage.set(1);
                assertEquals(TaskState.UNKNOWN,
                        runtime.tasks().reconcile(id).toCompletableFuture().get(25, TimeUnit.SECONDS).state());
                assertEquals(0, cleanup.get());
                stage.set(2);
                var settled = runtime.tasks().reconcile(id).toCompletableFuture().get(25, TimeUnit.SECONDS);
                assertEquals(TaskState.ENDED, settled.state(), settled.toString());
                assertEquals(Optional.of(Outcome.FAILED), settled.outcome());
                assertEquals(1, cleanup.get());
                assertEquals(0, replay.get());
                assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                runtime.releaseModRecovery();
            }
        }
    }

    /** Constructs durable interrupted preparation in an isolated JVM; this is not a simulated power loss. / 在隔离 JVM 构造持久中断准备；不冒充断电。
     * @param args marker, exact data directory and loopback port / 标记、精确数据目录及回环端口 */
    public static void main(String[] args) {
        if (args.length != 3 || !args[0].equals("--original"))
            throw new IllegalArgumentException();
        try {
            var fixture = new DeploymentAgentRecoveryRestartTest();
            fixture.root = Path.of(args[1]);
            fixture.original(Integer.parseInt(args[2]));
            System.out.println("ORIGINAL_AGENT_STATE_RETAINED");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private void original(int port) throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("README.md"), "Original candidate source\n");
        enable();
        var runtime = open();
        start(runtime, true);
        var server = new ServerRef("server");
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
                    var selected = context.source().selectDirectory("Original agent source", task);
                    var material = new DeploymentSourcePreparer(context.source()).prepare(selected, 65536, 100, task);
                    var archive = new DeploymentSourceArchiver(context.source()).prepare(material, 65536, task);
                    var journal = new DeploymentAgentJournal(context.db());
                    journal.create(task,
                            new DeploymentAgentJournal.Identity(server.id(), target, "a".repeat(64), "MANUAL_REVIEW"));
                    journal.event(task, "SOURCE", archive.sha256(), material.snapshot().sha256());
                    journal.event(task, "PLAN_APPLICATION", "", "demo");
                    journal.event(task, "PREPARE_EXECUTING", "b".repeat(64), "api");
                    String candidate = new DeploymentRemoteWorkspace("demo-api", archive.sha256()).candidateId();
                    journal.event(task, "CANDIDATE", "api", candidate);
                    Files.writeString(root.resolve("original-candidate"), candidate + "\n" + task.id());
                    journal.state(task, "UNKNOWN");
                    return new OperationResult<Void>(Outcome.UNKNOWN, Optional.empty(),
                            Optional.of(new Problem(ProblemCode.UNKNOWN, "CONSTRUCTED_AGENT_INTERRUPTED",
                                    "Constructed original preparation", Map.of(), Optional.empty())));
                });
        for (int i = 0; i < 100 && handle.snapshot().state() != TaskState.UNKNOWN; i++)
            Thread.sleep(50);
        assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
    }

    private String response(String script) throws Exception {
        if (script.contains("sha256sum --check --status"))
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
                    for (int next; (next = input.read()) != '\n';) {
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
                    String result = response(new String(bytes, StandardCharsets.UTF_8));
                    output.write(result.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    callback.onExit(0);
                } catch (Throwable failure) {
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
