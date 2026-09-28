package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.main.startup.LoopbackSshServer;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Reopens an original native DB task through the registered Mod recovery and actual loopback SSH channel. / 经 Mod 正式登记恢复和实际回环 SSH 通道重开原生数据库原任务。 */
class DeploymentNativeDatabaseRecoveryRestartTest extends DeploymentRuntimeFixture {
    /** Original remote receipt directory shared only by the controlled child and peer. / 仅受控子进程及对等端共享的原远端回执目录。 */
    private Path remote;

    /** First read is missing, second conflicts, third is exact. / 首次缺失、第二次冲突、第三次精确。 */
    private final AtomicInteger statusStage = new AtomicInteger();

    /** Actual helper status queries. / 实际 helper 状态查询次数。 */
    private final AtomicInteger statusCalls = new AtomicInteger();

    /** Any forbidden recovery replay. / 任意禁止的恢复重发。 */
    private final AtomicInteger replayCalls = new AtomicInteger();

    /** One original STARTED response is lost; registered recovery waits on missing evidence then settles the exact receipt. / 一次原 STARTED 响应丢失；登记恢复在缺依据时等待，随后结清精确回执。
     * @throws Exception if child, loopback SSH or original recovery fails / 子进程、回环 SSH 或原恢复失败时 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registeredRecoveryQueriesOriginalReceiptWithoutReplayingNativeStart(boolean knownFailure) throws Exception {
        remote = Files.createDirectory(root.resolve("native-remote"));
        try (var ssh = new LoopbackSshServer(root, server -> {
            server.setPasswordAuthenticator(
                    (user, password, session) -> user.equals("root") && password.equals("fixture-password"));
            server.setCommandFactory((channel, command) -> new FramedReply());
        })) {
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-cp", System.getProperty("java.class.path"), getClass().getName(), "--original", root.toString(),
                    Integer.toString(ssh.server().getPort()), Boolean.toString(knownFailure)).redirectErrorStream(true)
                    .start();
            try {
                assertTrue(child.waitFor(60, TimeUnit.SECONDS), "Original native DB child timed out");
                String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, child.exitValue(), output);
                assertTrue(output.contains("ORIGINAL_NATIVE_DB_UNKNOWN"), output);
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    assertTrue(child.waitFor(5, TimeUnit.SECONDS));
                }
            }
            try (var files = Files.list(remote)) {
                assertEquals(1, files.filter(path -> path.getFileName().toString().startsWith("operation-")).count());
            }
            if (knownFailure)
                try (var files = Files.list(remote)) {
                    var receipt = files.filter(path -> path.getFileName().toString().startsWith("operation-"))
                            .findFirst().orElseThrow();
                    assertTrue(Files.readString(receipt).contains("MANUAL_RESTORE_REQUIRED"));
                }
            try (var runtime = open()) {
                var report = runtime.startDevelopmentMods(workspace(), pages());
                assertTrue(report.running().isEmpty());
                assertEquals(List.of("MOD_RECOVERY_READY"),
                        report.problems().stream().map(Problem::eventCode).toList());
                var unfinished = new TaskLedger(runtime.persistence().database()).unfinished();
                assertEquals(1, unfinished.size());
                var original = unfinished.getFirst();
                var held = runtime.tasks().reconcile(original).toCompletableFuture().get(25, TimeUnit.SECONDS);
                assertEquals(TaskState.UNKNOWN, held.state());
                assertEquals(1, checkpoint(runtime, original));
                statusStage.set(1);
                var conflicted = runtime.tasks().reconcile(original).toCompletableFuture().get(25, TimeUnit.SECONDS);
                assertEquals(TaskState.UNKNOWN, conflicted.state());
                assertEquals(1, checkpoint(runtime, original));
                statusStage.set(2);
                var recovered = runtime.tasks().reconcile(original).toCompletableFuture().get(25, TimeUnit.SECONDS);
                assertEquals(TaskState.ENDED, recovered.state(), recovered.toString());
                assertEquals(Optional.of(Outcome.FAILED), recovered.outcome());
                assertEquals(3, statusCalls.get());
                assertEquals(0, replayCalls.get());
                assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                runtime.releaseModRecovery();
            }
        }
    }

    /** Runs an isolated original UNKNOWN task; this process intentionally exits instead of falsely closing held resources. / 隔离运行原 UNKNOWN 任务；本进程刻意退出，不假称已释放占用资源。
     * @param args exact child marker, data root and loopback port / 精确子进程标记、数据根及回环端口 */
    public static void main(String[] args) {
        if (args.length != 4 || !"--original".equals(args[0]))
            throw new IllegalArgumentException("Original native DB fixture only");
        try {
            var fixture = new DeploymentNativeDatabaseRecoveryRestartTest();
            fixture.root = Path.of(args[1]);
            fixture.remote = fixture.root.resolve("native-remote");
            fixture.original(Integer.parseInt(args[2]), Boolean.parseBoolean(args[3]));
            System.out.println("ORIGINAL_NATIVE_DB_UNKNOWN");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private void original(int port, boolean knownFailure) throws Exception {
        enable();
        var runtime = open();
        start(runtime, true);
        var server = new ServerRef("native-database-server");
        runtime.servers().saveServerProfile(
                ServerProfile.fromStored(
                        new StoredServerProfile(server.id(), "127.0.0.1", port, "root", "fixture-password")),
                "fixture-password".toCharArray());
        var context = runtime.modContexts().get(OWNER).context();
        var session = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                new Class<?>[]{RemoteSession.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("close"))
                        return null;
                    assertEquals("execute", method.getName());
                    var request = (CommandRequest) arguments[0];
                    if (request.script().contains("native-db start")) {
                        peer(remote, knownFailure ? "start-failed" : "start", request.stdin());
                        return new OperationResult<CommandResult>(Outcome.UNKNOWN, Optional.empty(),
                                Optional.of(new Problem(ProblemCode.UNKNOWN, "TEST_ORIGINAL_REPLY_LOST",
                                        "Original helper reply was not observed", Map.of(), Optional.empty())));
                    }
                    return reply(
                            "HELPER=1\nPROTOCOL=12\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
                });
        var handle = context.tasks().submit(new TaskRequest(DeploymentReferenceFlow.TASK_TYPE,
                "Original native DB operation", List.of(server.id()), true), task -> {
                    DeploymentStaticPreparationJournal.begin(context.tasks(), task, server);
                    return new DeploymentNativeDatabaseChannel(session, context.interaction(), server, task)
                            .start(context.tasks(), "POSTGRESQL", "postgresql.service", "a".repeat(64));
                });
        for (int attempt = 0; attempt < 100 && handle.snapshot().state() != TaskState.UNKNOWN; attempt++)
            Thread.sleep(50);
        assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
        assertEquals(1, checkpoint(runtime, handle.id()));
    }

    @Override
    protected PlatformRuntime open() throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), PROTECTION);
        if (runtime.security().securityState().configured())
            runtime.security().unlock(PASSWORD.toCharArray());
        else
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
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
                return true;
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                    CancellationToken cancellation) {
                throw new AssertionError();
            }
        });
        runtime.setSourcePicker((title, task, cancel) -> {
            throw new AssertionError("Recovery cannot select a new source");
        });
        runtime.started();
        return runtime;
    }

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

    private static long checkpoint(PlatformRuntime runtime, UUID task) throws Exception {
        try (var connection = runtime.persistence().database().openConnection();
                var query = connection.prepareStatement("SELECT COUNT(*) FROM platform_task_resource"
                        + " WHERE task_id=? AND resource_kind='CUSTOM' AND resource_id=? AND released_at IS NULL")) {
            query.setObject(1, task);
            query.setString(2, OWNER + "/checkpoint/" + DeploymentNativeDatabaseJournal.KEY);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static OperationResult<CommandResult> reply(String stdout) {
        return new OperationResult<>(Outcome.SUCCEEDED,
                Optional.of(new CommandResult(Optional.of(0), stdout, "", false)), Optional.empty());
    }

    private String response(String script, InputStream input) throws Exception {
        if (script.contains("native-db status")) {
            statusCalls.incrementAndGet();
            byte[] frame = input.readNBytes(3 * 1024 * 1024 + 1);
            assertTrue(frame.length <= 3 * 1024 * 1024);
            if (statusStage.get() == 0)
                return "receipt.phase=TUlTU0lORw==\nstatus=T0s=\n";
            var original = peer(remote, "status", frame);
            if (statusStage.get() == 1)
                return original.replaceFirst("receipt.requestDigest=[^\\n]+", "receipt.requestDigest="
                        + Base64.getEncoder().encodeToString("f".repeat(64).getBytes(StandardCharsets.US_ASCII)));
            return original;
        }
        if (script.contains("native-db "))
            replayCalls.incrementAndGet();
        if (script.contains("managed-helper' probe"))
            return "HELPER=1\nPROTOCOL=12\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n";
        throw new AssertionError("Unexpected native DB recovery command: " + script);
    }

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
                    String script = readFrame(input);
                    output.write(response(script, input).getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    callback.onExit(0);
                } catch (Throwable failure) {
                    try {
                        error.write((failure.getClass().getSimpleName() + ": " + failure.getMessage())
                                .getBytes(StandardCharsets.UTF_8));
                        error.flush();
                    } catch (IOException ignored) {
                        // The original channel may already have closed. / 原通道可能已关闭。
                    }
                    callback.onExit(1);
                }
            });
        }

        public void destroy(ChannelSession channel) throws InterruptedException {
            if (input != null)
                try {
                    input.close();
                } catch (IOException ignored) {
                    // Channel teardown owns this stream. / 通道收尾持有此流。
                }
            if (worker != null && worker != Thread.currentThread()) {
                worker.join(5000);
                assertFalse(worker.isAlive());
            }
        }
    }

    private static String readFrame(InputStream input) throws IOException {
        var length = new ByteArrayOutputStream();
        for (int next; (next = input.read()) != '\n';) {
            if (next < '0' || next > '9' || length.size() >= 8)
                throw new IOException("Malformed SSH script frame");
            length.write(next);
        }
        int count = Integer.parseInt(length.toString(StandardCharsets.US_ASCII));
        if (count < 1 || count > 16_777_216)
            throw new IOException("SSH script frame too large");
        byte[] script = input.readNBytes(count);
        if (script.length != count)
            throw new IOException("Incomplete SSH script frame");
        return new String(script, StandardCharsets.UTF_8);
    }

    private String peer(Path state, String action, byte[] frame) throws Exception {
        var script = workspace().resolve("src/mod/deployment/src/test/python/test_native_database_operation.py");
        var process = new ProcessBuilder("python", script.toString(), "peer", state.toString(), action).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(frame);
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), error);
            return output.replace("\r\n", "\n");
        } finally {
            Arrays.fill(frame, (byte) 0);
            if (process.isAlive())
                process.destroyForcibly();
        }
    }
}
