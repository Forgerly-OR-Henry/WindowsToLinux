package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.main.start.LoopbackSshServer;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Reopens an original interrupted publication against a controlled loopback SSH peer. / 在受控回环SSH对等端上重开原中断发布。 */
class DeploymentPublicationRecoveryRestartTest extends DeploymentRuntimeFixture {
    /** Original durable interruption to reconcile. / 待核对的原持久中断。 */
    private enum RecoveryScenarioType {
        PUBLISH_UNKNOWN, ROLLBACK_UNKNOWN, RETAIN_UNKNOWN, HEALTH_FAILED_ROLLBACK_CLOSE_UNKNOWN, PUBLISH_CONFLICT, ROLLBACK_UNIT_PRESENT, COMMIT_WINDOW
    }

    /** Durable remote state provided by the child fixture only after its original publish dispatch. / 子夹具仅在原发布发送后提供的远端持久状态。 */
    private final AtomicReference<String> release = new AtomicReference<>();

    /** Candidate bound to the child's original task. / 绑定子进程原任务的候选。 */
    private final AtomicReference<String> candidate = new AtomicReference<>();

    /** Whether the original remote publication was already rolled back. / 原远端发布是否已经回滚。 */
    private final AtomicBoolean rolledBack = new AtomicBoolean();

    /** Foreign current release for a deliberate first read-only conflict. / 首次只读冲突中的外来当前发布。 */
    private final AtomicReference<String> currentOverride = new AtomicReference<>();

    /** Stale first-deployment unit that prevents claiming a completed rollback. / 阻止声称首次部署已回滚的遗留单元。 */
    private final AtomicBoolean staleUnit = new AtomicBoolean();

    /** Mutable observed cleanup facts of this controlled remote peer. / 此受控远端对等端可观测的收尾事实。 */
    private final AtomicBoolean maintenance = new AtomicBoolean(true), candidatePresent = new AtomicBoolean(true);

    /** Fixed command calls sent by real PlatformLinuxService. / 真实PlatformLinuxService发送的固定命令。 */
    private final List<String> commands = new CopyOnWriteArrayList<>();

    /** Original child publishes once, then Mod.start explicitly reconciles it after reopening H2 without publishing again. / 原子进程发布一次；重开H2后Mod.start显式核对，不再次发布。
     * @throws Exception if the local host fixture fails / 本机宿主夹具失败时 */
    @ParameterizedTest
    @EnumSource(RecoveryScenarioType.class)
    void originalPublishedTaskReopensThroughProductionRecoveryWithoutReplay(RecoveryScenarioType scenario)
        throws Exception {
        try (var ssh = new LoopbackSshServer(root, server -> {
            server.setPasswordAuthenticator(
                (user, password, session) -> user.equals("root") && password.equals("fixture-password"));
            server.setCommandFactory((channel, command) -> new FramedReply());
        })) {
            String originalScenario = switch (scenario) {
                case PUBLISH_CONFLICT -> "PUBLISH_UNKNOWN";
                case ROLLBACK_UNIT_PRESENT -> "ROLLBACK_UNKNOWN";
                default -> scenario.name();
            };
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("java.class.path"),
                DeploymentStandardPublicationExecutorTest.class.getName(), "--unknown", root.toString(),
                originalScenario, Integer.toString(ssh.server().getPort())).redirectErrorStream(true).start();
            try {
                assertTrue(child.waitFor(60, TimeUnit.SECONDS), "Original publication child timed out");
                String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, child.exitValue(), output);
                assertTrue(output.contains("ORIGINAL_UNKNOWN_VERIFIED"), output);
            } finally {
                if (child.isAlive()) {
                    child.destroy();
                    if (!child.waitFor(2, TimeUnit.SECONDS)) {
                        child.destroyForcibly();
                        assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Original child did not exit");
                    }
                }
            }
            var remote = Files.readAllLines(root.resolve("controlled-remote-publication"));
            assertEquals(2, remote.size());
            release.set(remote.get(0));
            candidate.set(remote.get(1));
            boolean restored = scenario == RecoveryScenarioType.ROLLBACK_UNKNOWN
                || scenario == RecoveryScenarioType.HEALTH_FAILED_ROLLBACK_CLOSE_UNKNOWN
                || scenario == RecoveryScenarioType.ROLLBACK_UNIT_PRESENT;
            rolledBack.set(restored);
            maintenance.set(scenario == RecoveryScenarioType.PUBLISH_UNKNOWN
                || scenario == RecoveryScenarioType.ROLLBACK_UNKNOWN
                || scenario == RecoveryScenarioType.PUBLISH_CONFLICT
                || scenario == RecoveryScenarioType.ROLLBACK_UNIT_PRESENT);
            candidatePresent.set(scenario != RecoveryScenarioType.HEALTH_FAILED_ROLLBACK_CLOSE_UNKNOWN
                && scenario != RecoveryScenarioType.COMMIT_WINDOW);
            if (scenario == RecoveryScenarioType.PUBLISH_CONFLICT)
                currentOverride.set("c".repeat(64));
            if (scenario == RecoveryScenarioType.ROLLBACK_UNIT_PRESENT)
                staleUnit.set(true);
            try (var runtime = open()) {
                var report = runtime.startDevelopmentMods(workspace(), pages());
                assertTrue(report.running().isEmpty());
                assertEquals(List.of("MOD_RECOVERY_READY"),
                    report.problems().stream().map(Problem::eventCode).toList());
                var unfinished = new TaskLedger(runtime.persistence().database()).queries().unfinished();
                assertEquals(1, unfinished.size());
                assertEquals(1, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource"
                    + " WHERE resource_kind='CONNECTION' AND released_at IS NULL"));
                if (currentOverride.get() != null || staleUnit.get()) {
                    var unconfirmed = runtime.tasks().reconcile(unfinished.getFirst()).toCompletableFuture().get(25,
                        TimeUnit.SECONDS);
                    assertEquals(TaskState.UNKNOWN, unconfirmed.state());
                    assertTrue(checkpointUnreleased(runtime, unfinished.getFirst()));
                    try (var access = scope(runtime, true);
                         var connection = access.openConnection();
                         var query = connection.prepareStatement(
                             "SELECT business_committed FROM deployment_execution WHERE platform_task_id=?")) {
                        query.setObject(1, unfinished.getFirst());
                        try (var rows = query.executeQuery()) {
                            assertTrue(rows.next());
                            assertFalse(rows.getBoolean(1), "Conflicting remote state cannot commit original business");
                        }
                    }
                    try (var access = scope(runtime, true);
                         var connection = access.openConnection();
                         var statement = connection.createStatement();
                         var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_release")) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getLong(1));
                    }
                    assertFalse(
                        commands.stream()
                            .anyMatch(value -> value.contains("application-maintenance")
                                || value.contains("candidate-cleanup-task") || value.contains("'retain'")),
                        commands.toString());
                    currentOverride.set(null);
                    staleUnit.set(false);
                }
                TaskSnapshot recovered;
                try {
                    recovered = runtime.tasks().reconcile(unfinished.getFirst()).toCompletableFuture().get(25,
                        TimeUnit.SECONDS);
                } catch (TimeoutException unfinishedRecovery) {
                    throw new AssertionError("Original recovery remained unresolved: " + runtime.tasks().snapshots()
                        + ", commands=" + commands, unfinishedRecovery);
                }
                assertEquals(TaskState.ENDED, recovered.state(), () -> recovered + ", commands=" + commands
                    + ", checkpoint=" + checkpoint(runtime, unfinished.getFirst()));
                assertEquals(Optional.of(restored ? Outcome.FAILED : Outcome.SUCCEEDED), recovered.outcome());
                try (var access = scope(runtime, true);
                     var connection = access.openConnection();
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_release")) {
                    assertTrue(rows.next());
                    assertEquals(restored ? 0 : 1, rows.getLong(1));
                }
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                if (scenario == RecoveryScenarioType.HEALTH_FAILED_ROLLBACK_CLOSE_UNKNOWN
                    || scenario == RecoveryScenarioType.COMMIT_WINDOW)
                    assertTrue(commands.isEmpty(), "Already verified remote cleanup must not be repeated");
                else
                    assertTrue(commands.stream().anyMatch(value -> value.contains("publication-original-status")));
                if (scenario == RecoveryScenarioType.PUBLISH_UNKNOWN
                    || scenario == RecoveryScenarioType.PUBLISH_CONFLICT)
                    assertTrue(commands.stream().anyMatch(value -> value.contains("application-health")));
                assertFalse(commands.stream()
                        .anyMatch(value -> value.contains("publish-deployment") || value.contains("rollback-deployment")
                            || value.contains("prepare-toolchains") || value.contains("candidate-create-task")),
                    commands.toString());
                if (scenario == RecoveryScenarioType.RETAIN_UNKNOWN)
                    assertFalse(commands.stream().anyMatch(value -> value.contains("'retain'")), commands.toString());
                assertFalse(maintenance.get());
                assertFalse(candidatePresent.get());
                runtime.modRecovery().release();
            }
        }
    }

    /** Starts the reopened host with real approval and no source selection. / 以真实审核及无来源重选启动重开的宿主。
     * @return reopened platform / 重开平台
     * @throws Exception if startup fails / 启动失败时 */
    @Override
    protected PlatformRuntime open() throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), PROTECTION);
        runtime.security().unlock(PASSWORD.toCharArray());
        runtime.setInteractionPresenter(new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerRef server,
                                                           TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskSnapshot task,
                                                       CancelToken cancellation) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError();
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
                assertEquals(List.of("server-publication"), intent.serverIds());
                return true;
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken cancellation) {
                throw new AssertionError();
            }
        });
        runtime.setSourcePicker((title, task, cancellation) -> {
            throw new AssertionError("Recovery may not reselect source");
        });
        runtime.started();
        return runtime;
    }

    /** No page is mounted while the original Mod is recovery-ready. / 原Mod处于待恢复状态时不装载页面。
     * @return recovery registry / 恢复页面登记 */
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

    /** Supplies complete independent read-only facts and fixed cleanup receipts over real SSH. / 经真实SSH提供完整独立只读事实及固定收尾回执。
     * @param command exact request received by the loopback peer / 回环对等端收到的精确请求
     * @return complete controlled stdout / 完整受控标准输出 */
    private String reply(String command) {
        commands.add(command);
        if (command.contains("publication-original-status"))
            return "PUBLICATION_STATUS=1\nCURRENT="
                + (currentOverride.get() != null
                ? currentOverride.get()
                : rolledBack.get() ? "none" : release.get())
                + "\nCURRENT_KIND=" + (rolledBack.get() ? "none" : "deployment") + "\nCURRENT_MODE="
                + (rolledBack.get() ? "none" : "DAEMON") + "\nSNAPSHOT=none\nSNAPSHOT_OLD=none\n"
                + "SNAPSHOT_KIND=none\nSNAPSHOT_RUNNING=none\nMAINTENANCE="
                + (maintenance.get() ? "deployment-" + release.get() : "none") + "\nCANDIDATE="
                + (candidatePresent.get() ? "owned" : "absent") + "\nBUILD_ACTIVE=no\nUNIT_PRESENT="
                + (rolledBack.get() && !staleUnit.get() ? "no" : "yes") + "\n"
                + "CONTAINER_PRESENT=not-applicable\nRETAINED_COUNT=1\n";
        if (command.contains("inspect-runtime"))
            return "KIND=deployment\nMODE=DAEMON\n";
        if (command.contains("observe-deployment"))
            return "OWNER=1\nRUNNING=1\nENABLED=enabled\nQUERY_OK=1\nActiveState=active\n"
                + "SubState=running\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID=123\n";
        if (command.contains("application-health"))
            return "HEALTHY=1\n";
        if (command.contains("application-maintenance")) {
            maintenance.set(false);
            return "MAINTENANCE=end\n";
        }
        if (command.contains("candidate-cleanup-task")) {
            candidatePresent.set(false);
            return "CANDIDATE_CLEANED=1\n";
        }
        if (command.contains("'retain'"))
            return "RETAINED=1\n";
        throw new AssertionError("Unexpected recovery command: " + command);
    }

    /** Reads only the test original checkpoint when a recovery assertion needs its durable cause. / 恢复断言需要持久原因时仅读取本测试原检查点。
     * @param runtime reopened runtime / 重开运行时
     * @param task original task ID / 原任务ID
     * @return bounded retained recovery JSON / 有界留存恢复JSON */
    private static String checkpoint(PlatformRuntime runtime, UUID task) {
        try (var connection = runtime.persistence().database().openConnection();
             var statement = connection.prepareStatement("SELECT recovery_json FROM platform_task_resource"
                 + " WHERE task_id=? AND resource_kind='CUSTOM' AND resource_id=?")) {
            statement.setObject(1, task);
            statement.setString(2, "gold.debug.windowstolinux.mod.deployment/checkpoint/deployment-publication-remote");
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : "absent";
            }
        } catch (Exception unavailable) {
            return unavailable.toString();
        }
    }

    /** Checks that the exact original author occupancy remains unreleased. / 核对精确原作者占用仍未释放。
     * @param runtime reopened host / 重开宿主
     * @param task original task / 原任务
     * @return true only while the original checkpoint is held / 仅原检查点仍占用时为真 */
    private static boolean checkpointUnreleased(PlatformRuntime runtime, UUID task) throws Exception {
        try (var connection = runtime.persistence().database().openConnection();
             var query = connection.prepareStatement("SELECT released_at FROM platform_task_resource"
                 + " WHERE task_id=? AND resource_kind='CUSTOM' AND resource_id=?")) {
            query.setObject(1, task);
            query.setString(2, "gold.debug.windowstolinux.mod.deployment/checkpoint/deployment-publication-remote");
            try (var rows = query.executeQuery()) {
                return rows.next() && rows.getObject(1) == null;
            }
        }
    }

    /** Reads the actual public SSH command frame and answers only its fixed inner script. / 读取真实公开SSH命令帧，只应答其中的固定内层脚本。 */
    private final class FramedReply implements Command {
        /** SSH channel standard input. / SSH通道标准输入。 */
        private InputStream input;

        /** SSH channel standard output. / SSH通道标准输出。 */
        private OutputStream output;

        /** SSH channel standard error. / SSH通道标准错误。 */
        private OutputStream error;

        /** Channel exit callback. / 通道退出回调。 */
        private org.apache.sshd.server.ExitCallback callback;

        /** Test-owned bounded worker. / 测试拥有的有界工作线程。 */
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
                    String script = readFrame(input);
                    output.write(reply(script).getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    callback.onExit(0);
                } catch (Throwable failure) {
                    try {
                        error.write((failure.getClass().getSimpleName() + ": " + failure.getMessage())
                            .getBytes(StandardCharsets.UTF_8));
                        error.flush();
                    } catch (IOException ignored) {
                        // The SSH channel may already have closed. / SSH通道可能已关闭。
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
                    // Channel teardown already owns the stream. / 通道收尾已接管该流。
                }
            }
            if (worker != null && worker != Thread.currentThread()) {
                worker.join(5000);
                assertFalse(worker.isAlive(), "Controlled SSH command worker did not exit");
            }
        }
    }

    /** Reads exactly one bounded length-prefixed script, leaving original command stdin separate. / 精确读取一份有界长度前缀脚本，保持原命令标准输入分离。
     * @param input actual SSH stdin / 真实SSH标准输入
     * @return fixed inner script / 固定内层脚本
     * @throws IOException if the frame is incomplete or malformed / 帧不完整或畸形时 */
    private static String readFrame(InputStream input) throws IOException {
        var length = new ByteArrayOutputStream();
        for (int next; (next = input.read()) != '\n'; ) {
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
