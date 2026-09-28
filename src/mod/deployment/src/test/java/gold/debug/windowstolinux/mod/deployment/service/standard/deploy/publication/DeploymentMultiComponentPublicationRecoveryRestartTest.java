package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.main.startup.LoopbackSshServer;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Reopens one original whole-graph publication through production Mod.start and framed loopback SSH. / 经正式Mod.start及封帧回环SSH重开同一原整图发布。 */
class DeploymentMultiComponentPublicationRecoveryRestartTest extends DeploymentRuntimeFixture {
    /** Deterministic read-only conflict or one proven pre-dispatch review refusal. / 确定性只读冲突或一次已证未发送的审核拒绝。 */
    private enum RecoveryModeType {
        NORMAL, CONFLICT, REFUSED_CLEANUP, ORIGINAL_REFUSAL, PREPUBLISH_REFUSED, SENT_CLEANUP_UNKNOWN, LOCAL_ONLY, ROLLED_BACK
    }

    /** Persisted controlled releases for both exact units. / 两个精确单元的持久受控发布。 */
    private final Map<String, String> releases = new ConcurrentHashMap<>();

    /** Deliberate first read-only conflict. / 首次只读核对的刻意冲突。 */
    private final AtomicBoolean conflictingWeb = new AtomicBoolean();

    /** One exact host refusal before candidate-cleanup dispatch. / 一次候选清理发送前精确宿主拒绝。 */
    private final AtomicBoolean refuseCleanup = new AtomicBoolean();

    /** Refuses the second prepublication candidate after the first was already cleaned. / 首个发布前候选已清后拒绝第二个。 */
    private final AtomicBoolean refuseWebCleanup = new AtomicBoolean();

    /** One cleanup request has been dispatched but its remote receipt is lost. / 一次清理已发送却丢失远端回执。 */
    private final AtomicBoolean loseCleanupReceipt = new AtomicBoolean();

    /** Controlled candidate cleanup evidence. / 受控候选清理依据。 */
    private final Map<String, Boolean> candidates = new ConcurrentHashMap<>();

    /** Actual SSH inner scripts observed after H2 reopening. / H2重开后观察到的真实SSH内层脚本。 */
    private final List<String> commands = new CopyOnWriteArrayList<>();

    /** A mixed foreign current cannot commit; once corrected, the same task completes without republishing. / 外来混合现行状态不能提交；纠正后同原任务不重发发布即可完成。
     * @param mode actual evidence or refused review / 实际依据或审核拒绝
     * @throws Exception if the controlled host fails / 受控宿主失败时 */
    @ParameterizedTest
    @EnumSource(value = RecoveryModeType.class, names = {"NORMAL", "CONFLICT", "REFUSED_CLEANUP", "ORIGINAL_REFUSAL",
            "PREPUBLISH_REFUSED", "SENT_CLEANUP_UNKNOWN"})
    void originalGraphReopensAndReconcilesWithoutBusinessReplay(RecoveryModeType mode) throws Exception {
        runMode(mode);
    }

    /** Closes only original local resources after prior remote cleanup was durably verified. / 远端原收尾已持久核实后仅关闭本机原资源。
     * @throws Exception if the original restarted task fails / 原任务重开失败时 */
    @Test
    void verifiedRemoteCleanupReopensWithoutAnyRemoteCommand() throws Exception {
        runMode(RecoveryModeType.LOCAL_ONLY);
    }

    /** Converges a fully restored failed graph without replaying its rollback. / 整图旧状态已恢复时收敛原失败且不重发回滚。
     * @throws Exception if the original task cannot be verified / 原任务无法核实时 */
    @Test
    void originalWholeGraphRollbackReopensAsKnownFailure() throws Exception {
        runMode(RecoveryModeType.ROLLED_BACK);
    }

    /** Executes one exact restart boundary. / 执行一项精确重开边界。
     * @param mode controlled boundary / 受控边界
     * @throws Exception if original task or host fails / 原任务或宿主失败时 */
    private void runMode(RecoveryModeType mode) throws Exception {
        try (var ssh = new LoopbackSshServer(root, server -> {
            server.setPasswordAuthenticator(
                    (user, password, session) -> user.equals("root") && password.equals("fixture-password"));
            server.setCommandFactory((channel, command) -> new FramedReply());
        })) {
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-cp", System.getProperty("java.class.path"),
                    DeploymentMultiComponentPublicationExecutorTest.class.getName(), "--unknown", root.toString(),
                    mode == RecoveryModeType.ORIGINAL_REFUSAL
                            ? "ORIGINAL_CLEANUP_REFUSED"
                            : mode == RecoveryModeType.PREPUBLISH_REFUSED
                                    ? "STAGE_UNKNOWN"
                                    : mode == RecoveryModeType.ROLLED_BACK
                                            ? "ROLLBACK_CLEANUP_REFUSED"
                                            : mode == RecoveryModeType.LOCAL_ONLY ? "CLOSE_UNKNOWN" : "PUBLISH_UNKNOWN",
                    Integer.toString(ssh.server().getPort())).redirectErrorStream(true).start();
            try {
                assertTrue(child.waitFor(70, TimeUnit.SECONDS), "Original graph child timed out");
                String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, child.exitValue(), output);
                assertTrue(output.contains("GRAPH_UNKNOWN_VERIFIED"), output);
            } finally {
                if (child.isAlive()) {
                    child.destroy();
                    if (!child.waitFor(2, TimeUnit.SECONDS)) {
                        child.destroyForcibly();
                        assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Original graph child did not exit");
                    }
                }
            }
            if (mode == RecoveryModeType.PREPUBLISH_REFUSED || mode == RecoveryModeType.ROLLED_BACK) {
                candidates.put("api", true);
                candidates.put("web", true);
            } else {
                for (String line : Files.readAllLines(root.resolve("controlled-graph-releases"))) {
                    var parts = line.split("=", -1);
                    assertEquals(2, parts.length);
                    assertTrue(releases.putIfAbsent(parts[0], parts[1]) == null);
                    candidates.put(parts[0], true);
                }
                assertEquals(Set.of("api", "web"), releases.keySet());
            }
            conflictingWeb.set(mode == RecoveryModeType.CONFLICT);
            refuseCleanup.set(mode == RecoveryModeType.REFUSED_CLEANUP);
            refuseWebCleanup.set(mode == RecoveryModeType.PREPUBLISH_REFUSED);
            loseCleanupReceipt.set(mode == RecoveryModeType.SENT_CLEANUP_UNKNOWN);
            if (mode == RecoveryModeType.SENT_CLEANUP_UNKNOWN) {
                probeSentCleanupUnknown(ssh.server().getPort());
                assertEquals(1, commands.stream().filter(value -> value.contains("candidate-cleanup-task")).count(),
                        commands.toString());
                assertFalse(
                        commands.stream().anyMatch(
                                value -> value.contains("publish-deployment") || value.contains("rollback-deployment")),
                        commands.toString());
                return;
            }
            try (var runtime = open()) {
                var report = runtime.startDevelopmentMods(workspace(), pages());
                assertTrue(report.running().isEmpty());
                assertEquals(List.of("MOD_RECOVERY_READY"),
                        report.problems().stream().map(Problem::eventCode).toList());
                var unfinished = new TaskLedger(runtime.persistence().database()).unfinished();
                assertEquals(1, unfinished.size());
                var original = unfinished.getFirst();
                assertEquals(1, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource"
                        + " WHERE resource_kind='CONNECTION' AND released_at IS NULL"));
                if (mode == RecoveryModeType.CONFLICT || mode == RecoveryModeType.REFUSED_CLEANUP
                        || mode == RecoveryModeType.PREPUBLISH_REFUSED) {
                    var withheld = runtime.tasks().reconcile(original).toCompletableFuture().get(25, TimeUnit.SECONDS);
                    assertEquals(TaskState.UNKNOWN, withheld.state());
                    assertTrue(checkpointUnreleased(runtime, original));
                    if (mode == RecoveryModeType.CONFLICT)
                        assertEquals(0, countMod(runtime, "deployment_release"));
                    if (mode == RecoveryModeType.PREPUBLISH_REFUSED) {
                        assertEquals(0, countMod(runtime, "deployment_release"));
                        assertEquals(Map.of("api", false, "web", true), candidates);
                        assertEquals(1,
                                commands.stream().filter(value -> value.contains("candidate-cleanup-task")).count());
                        assertFalse(commands.stream().anyMatch(
                                value -> value.contains("publish-deployment") || value.contains("rollback-deployment")),
                                commands.toString());
                    } else
                        assertFalse(
                                commands.stream()
                                        .anyMatch(DeploymentMultiComponentPublicationRecoveryRestartTest::writes),
                                commands.toString());
                    conflictingWeb.set(false);
                    assertFalse(refuseCleanup.get(), "The intended host approval must actually have been refused");
                    assertFalse(refuseWebCleanup.get(), "The second candidate review must have been refused");
                }
                var recovered = runtime.tasks().reconcile(original).toCompletableFuture().get(25, TimeUnit.SECONDS);
                assertEquals(TaskState.ENDED, recovered.state(), () -> recovered + ", commands=" + commands);
                boolean failed = mode == RecoveryModeType.PREPUBLISH_REFUSED || mode == RecoveryModeType.ROLLED_BACK;
                assertEquals(Optional.of(failed ? Outcome.FAILED : Outcome.SUCCEEDED), recovered.outcome());
                assertEquals(failed ? 0 : 1, countMod(runtime, "deployment_release"));
                assertEquals(failed ? 0 : 2, countMod(runtime, "deployment_unit_release_binding"));
                assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                assertEquals(mode != RecoveryModeType.LOCAL_ONLY,
                        commands.stream().anyMatch(value -> value.contains("publication-original-status")),
                        commands.toString());
                if (mode == RecoveryModeType.LOCAL_ONLY)
                    assertTrue(commands.isEmpty(), commands.toString());
                assertFalse(
                        commands.stream().anyMatch(value -> value.contains("publish-deployment")
                                || value.contains("rollback-deployment") || value.contains("candidate-create-task")
                                || value.contains("prepare-toolchains") || value.contains("'retain'")),
                        commands.toString());
                if (mode != RecoveryModeType.LOCAL_ONLY)
                    assertEquals(Map.of("api", false, "web", false), candidates);
                runtime.releaseModRecovery();
            }
        }
    }

    /** Runs the deliberately occupied recovered task in a separate process. / 在独立进程检查刻意保持占用的恢复任务。
     * @param port controlled loopback SSH port / 受控回环SSH端口
     * @throws Exception if the child fails or remains running / 子进程失败或未退出时 */
    private void probeSentCleanupUnknown(int port) throws Exception {
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-cp",
                System.getProperty("java.class.path"), getClass().getName(), "--sent-cleanup-unknown", root.toString(),
                Integer.toString(port)).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS), "Recovered unknown task child timed out");
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains("SENT_CLEANUP_UNKNOWN_VERIFIED"), output);
        } finally {
            if (child.isAlive()) {
                child.destroy();
                if (!child.waitFor(2, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    assertTrue(child.waitFor(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    /** Exercises two explicit reconciliations of the same occupied original task. / 对同一占用中的原任务执行两次显式核对。
     * @param args exact child marker, database root and SSH port / 精确子进程标记、数据库根和SSH端口 */
    public static void main(String[] args) {
        if (args.length != 3 || !args[0].equals("--sent-cleanup-unknown"))
            throw new IllegalArgumentException("Isolated recovery probe only");
        try {
            var probe = new DeploymentMultiComponentPublicationRecoveryRestartTest();
            probe.root = Path.of(args[1]);
            try (var runtime = probe.open()) {
                var report = runtime.startDevelopmentMods(probe.workspace(), pages());
                assertEquals(List.of("MOD_RECOVERY_READY"),
                        report.problems().stream().map(Problem::eventCode).toList());
                var original = new TaskLedger(runtime.persistence().database()).unfinished().getFirst();
                for (int attempt = 0; attempt < 2; attempt++) {
                    var result = runtime.tasks().reconcile(original).toCompletableFuture().get(25, TimeUnit.SECONDS);
                    assertEquals(TaskState.UNKNOWN, result.state());
                    assertTrue(checkpointUnreleased(runtime, original));
                }
                assertEquals(1, probe.countMod(runtime, "deployment_release"));
                System.out.println("SENT_CLEANUP_UNKNOWN_VERIFIED");
                System.exit(0);
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    /** Starts the actual reopened runtime with real interaction review and no source selection. / 以真实交互审核且不重选来源启动重开宿主。 */
    @Override
    protected PlatformRuntime open() throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), PROTECTION);
        runtime.security().unlock(PASSWORD.toCharArray());
        runtime.setInteractionPresenter(new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerRef server,
                    TaskSnapshot task, CancellationToken cancellation) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskSnapshot task,
                    CancellationToken cancellation) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError();
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancellationToken cancellation) {
                assertEquals(List.of("server-publication"), intent.serverIds());
                if (intent.semantics().map(ActionSemantics::operation)
                        .filter("deployment.publication.candidate-cleanup.v1"::equals).isPresent()
                        && refuseCleanup.compareAndSet(true, false))
                    return false;
                if (intent.semantics().map(ActionSemantics::operation)
                        .filter("deployment.publication.candidate-cleanup.v1"::equals).isPresent()
                        && intent.exactAction().contains("'web'") && refuseWebCleanup.compareAndSet(true, false))
                    return false;
                return true;
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                    CancellationToken cancellation) {
                throw new AssertionError();
            }
        });
        runtime.setSourcePicker((title, task, cancellation) -> {
            throw new AssertionError("Recovery cannot select or recreate source");
        });
        runtime.started();
        return runtime;
    }

    /** Constructs an empty UI registry for the actual recovery-ready Mod startup. / 为真实待恢复Mod启动构造空页面登记。 */
    private static ModPageRegistry pages() {
        return new ModPageRegistry(new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, javax.swing.JComponent component) {
                throw new AssertionError();
            }

            public void focus(ModPageRegistry.PageKey key) {
            }

            public void remove(ModPageRegistry.PageKey key) {
            }

            public void environment(UiEnvironment environment) {
            }
        }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
    }

    /** Reads one exact Mod-owned table. / 读取一张精确Mod所有表。 */
    private long countMod(PlatformRuntime runtime, String table) throws Exception {
        try (var access = scope(runtime, true);
                var connection = access.openConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    /** Confirms the original graph checkpoint remains occupied after a conflicting read. / 冲突只读后确认原图检查点仍占用。 */
    private static boolean checkpointUnreleased(PlatformRuntime runtime, UUID task) throws Exception {
        try (var connection = runtime.persistence().database().openConnection();
                var query = connection.prepareStatement("SELECT released_at FROM platform_task_resource"
                        + " WHERE task_id=? AND resource_kind='CUSTOM' AND resource_id=?")) {
            query.setObject(1, task);
            query.setString(2, "gold.debug.windowstolinux.mod.deployment/checkpoint/deployment-publication-graph");
            try (var rows = query.executeQuery()) {
                return rows.next() && rows.getObject(1) == null;
            }
        }
    }

    /** Detects commands that would mutate remote publication state. / 识别会写远端发布状态的命令。 */
    private static boolean writes(String script) {
        return script.contains("application-maintenance") || script.contains("candidate-cleanup-task")
                || script.contains("'retain'") || script.contains("publish-deployment")
                || script.contains("rollback-deployment");
    }

    /** Returns complete read-only facts and fixed cleanup receipts for one exact component. / 对一个精确组件返回完整只读事实与固定收尾回执。 */
    private String reply(String script) {
        commands.add(script);
        var id = script.contains("'api'") ? "api" : "web";
        if (script.contains("publication-original-status"))
            return "PUBLICATION_STATUS=1\nCURRENT="
                    + (releases.isEmpty()
                            ? "none"
                            : conflictingWeb.get() && id.equals("web") ? "f".repeat(64) : releases.get(id))
                    + "\nCURRENT_KIND=" + (releases.isEmpty() ? "none" : "deployment") + "\nCURRENT_MODE="
                    + (releases.isEmpty() ? "none" : "DAEMON") + "\nSNAPSHOT=none\nSNAPSHOT_OLD=none\n"
                    + "SNAPSHOT_KIND=none\nSNAPSHOT_RUNNING=none\nMAINTENANCE=none\nCANDIDATE="
                    + (candidates.get(id) ? "owned" : "absent") + "\nBUILD_ACTIVE=no\nUNIT_PRESENT="
                    + (releases.isEmpty() ? "no" : "yes") + "\nCONTAINER_PRESENT=not-applicable\nRETAINED_COUNT=1\n";
        if (script.contains("inspect-runtime"))
            return "KIND=deployment\nMODE=DAEMON\n";
        if (script.contains("observe-deployment"))
            return "OWNER=1\nRUNNING=1\nENABLED=enabled\nQUERY_OK=1\nActiveState=active\nSubState=running\n"
                    + "Result=success\nExecMainCode=0\nExecMainStatus=0\nMainPID=123\n";
        if (script.contains("application-health"))
            return "HEALTHY=1\n";
        if (script.contains("candidate-cleanup-task")) {
            if (loseCleanupReceipt.compareAndSet(true, false))
                return "__UNKNOWN__";
            candidates.put(id, false);
            return "CANDIDATE_CLEANED=1\n";
        }
        throw new AssertionError("Unexpected original graph recovery command: " + script);
    }

    /** Reads actual platform SSH frames before answering an inner fixed helper script. / 应答内部固定helper脚本前读取真实平台SSH封帧。 */
    private final class FramedReply implements Command {
        private InputStream input;

        private OutputStream output;

        private OutputStream error;

        private org.apache.sshd.server.ExitCallback callback;

        private Thread worker;

        public void setInputStream(InputStream stream) {
            input = stream;
        }

        public void setOutputStream(OutputStream stream) {
            output = stream;
        }

        public void setErrorStream(OutputStream stream) {
            error = stream;
        }

        public void setExitCallback(org.apache.sshd.server.ExitCallback exit) {
            callback = exit;
        }

        public void start(ChannelSession channel, org.apache.sshd.server.Environment environment) {
            worker = Thread.ofVirtual().start(() -> {
                try {
                    output.write(reply(readFrame(input)).getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    callback.onExit(0);
                } catch (Throwable failure) {
                    try {
                        error.write((failure.getClass().getSimpleName() + ": " + failure.getMessage())
                                .getBytes(StandardCharsets.UTF_8));
                        error.flush();
                    } catch (IOException ignored) {
                        // The original SSH channel may have closed. / 原SSH通道可能已关闭。
                    }
                    callback.onExit(1);
                }
            });
        }

        public void destroy(ChannelSession channel) throws InterruptedException {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // Platform teardown already owns this stream. / 平台收尾已接管此流。
                }
            }
            if (worker != null && worker != Thread.currentThread()) {
                worker.join(5000);
                assertFalse(worker.isAlive(), "Controlled SSH worker did not exit");
            }
        }
    }

    /** Reads exactly one bounded platform command frame. / 精确读取一份有界平台命令帧。 */
    private static String readFrame(InputStream input) throws IOException {
        var length = new ByteArrayOutputStream();
        for (int next; (next = input.read()) != '\n';) {
            if (next < '0' || next > '9' || length.size() >= 8)
                throw new IOException("Malformed command frame length");
            length.write(next);
        }
        int count = Integer.parseInt(length.toString(StandardCharsets.US_ASCII));
        if (count < 1 || count > 16_777_216)
            throw new IOException("Command frame exceeds platform bound");
        byte[] script = input.readNBytes(count);
        if (script.length != count)
            throw new IOException("Command frame is incomplete");
        return new String(script, StandardCharsets.UTF_8);
    }
}
