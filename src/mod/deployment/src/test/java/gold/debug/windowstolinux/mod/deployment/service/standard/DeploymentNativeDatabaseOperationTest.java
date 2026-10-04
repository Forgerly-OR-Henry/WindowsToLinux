package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferProgress;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStall;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow;
import org.junit.jupiter.api.Test;

/** Checks actual helper receipt processing against the original CLASS/H2 task and recovery. / 以原 CLASS/H2 任务和恢复核对实际 helper 回执处理。 */
class DeploymentNativeDatabaseOperationTest extends DeploymentRuntimeFixture {
    private final AtomicInteger approved = new AtomicInteger();

    @Override
    protected PlatformRuntime open() throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), PROTECTION);
        if (runtime.security().securityState().configured())
            runtime.security().unlock(PASSWORD.toCharArray());
        else
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.setInteractionPresenter(new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerId server,
                                                           TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError("No transfer expected");
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerId server, TaskSnapshot task,
                                                       CancelToken cancellation) {
                throw new AssertionError("No transfer expected");
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError("No transfer expected");
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
                approved.incrementAndGet();
                return true;
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken cancellation) {
                throw new AssertionError("No input expected");
            }
        });
        runtime.setSourcePicker((title, task, cancel) -> selectedSourceDirectory());
        runtime.started();
        return runtime;
    }

    /** A previously installed protocol cannot start a database without exact receipt support. / 先前协议若无精确回执支持，不能启动数据库。
     * @throws Exception if original task admission fails / 原任务准入失败时 */
    @Test
    void mismatchedHelperNeverExecutesNativeAction() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = saved(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var starts = new AtomicInteger();
            var session = session((request) -> {
                if (request.script().contains("native-db start"))
                    starts.incrementAndGet();
                return reply("HELPER=1\nPROTOCOL=11\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            });
            var handle = context.tasks()
                .submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE, "Reject old native DB helper",
                        List.of(server.id()), true),
                    task -> new DeploymentNativeDatabaseChannel(session, context.interaction(), server, task)
                        .start(context.tasks(), "POSTGRESQL", "postgresql.service", "a".repeat(64)));
            var result = handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TaskResult.FAILED, result.outcome());
            assertEquals(0, starts.get());
        }
    }

    /** An approval exception before dispatch is a known unsent cancellation, with no retained operation. / 发送前的审批异常属于已知未发送取消，不保留操作。
     * @throws Exception if actual task admission fails / 真实任务准入失败时 */
    @Test
    void approvalCancellationBeforeDispatchDoesNotBecomeUnknown() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = saved(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var starts = new AtomicInteger();
            var borrowed = session(request -> {
                if (request.script().contains("native-db start"))
                    starts.incrementAndGet();
                return reply("HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                    + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            });
            var review = new InteractionService() {
                public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext task)
                    throws ApiException {
                    if (intent.semantics().isPresent()
                        && "deployment.native-db.start.v1".equals(intent.semantics().orElseThrow().operation()))
                        throw new ApiException(new Problem(ProblemCode.CANCELLED, "TEST_REVIEW_CANCELLED",
                            "Review ended before native DB dispatch", Map.of(), Optional.empty()));
                    return context.interaction().approve(intent, task);
                }

                public OperationResult<String> requestText(String title, String prompt, boolean secret,
                                                           TaskContext task) throws ApiException {
                    return context.interaction().requestText(title, prompt, secret, task);
                }
            };
            var handle = context.tasks()
                .submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE, "Cancel native DB before dispatch",
                        List.of(server.id()), true),
                    task -> new DeploymentNativeDatabaseChannel(borrowed, review, server, task)
                        .start(context.tasks(), "POSTGRESQL", "postgresql.service", "a".repeat(64)));
            assertEquals(TaskResult.CANCELLED,
                handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(0, starts.get());
        }
    }

    /** Serial advance retains earlier affected business targets and never overwrites an unresolved dispatch. / 串行推进保留先前受影响业务目标，绝不覆盖未决发送。
     * @throws Exception if original checkpoint persistence fails / 原检查点持久化失败时 */
    @Test
    void serialCheckpointRetainsPriorEffectAndRejectsUnknownOverwrite() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = saved(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE,
                "Serial native DB evidence", List.of(server.id()), true), task -> {
                try {
                    var journal = DeploymentNativeDatabaseActionLog.begin(context.tasks(), task, server,
                        java.util.UUID.randomUUID(), "start", "a".repeat(64), "b".repeat(64),
                        "POSTGRESQL:original");
                    journal.dispatched();
                    assertThrows(IllegalArgumentException.class, () -> journal.next(java.util.UUID.randomUUID(),
                        "prepare", "c".repeat(64), "d".repeat(64), "POSTGRESQL:next"));
                    journal.terminal("SUCCEEDED", "CONFIRMED");
                    journal.next(java.util.UUID.randomUUID(), "prepare", "c".repeat(64), "d".repeat(64),
                        "POSTGRESQL:next");
                    assertEquals("INTENT", journal.facts().phase());
                    assertEquals(List.of("POSTGRESQL:original"), journal.facts().effectTargets());
                    assertTrue(journal.facts().effectsObserved());
                    for (int i = 0; i < 16; i++) {
                        journal.dispatched();
                        journal.terminal("SUCCEEDED", "CONFIRMED");
                        if (i < 15) {
                            String target = ("db-" + i + "-" + "x".repeat(256)).substring(0, 256);
                            journal.next(java.util.UUID.randomUUID(), "prepare", "c".repeat(64), "d".repeat(64),
                                target);
                        }
                    }
                    assertThrows(IllegalArgumentException.class,
                        () -> journal.next(java.util.UUID.randomUUID(), "prepare", "c".repeat(64),
                            "d".repeat(64), ("db-16-" + "x".repeat(256)).substring(0, 256)));
                    assertEquals("SUCCEEDED", journal.facts().phase());
                    journal.close();
                    assertTrue(context.tasks().findCheckpoint(task, DeploymentNativeDatabaseActionLog.KEY)
                        .orElseThrow().snapshot().released());
                } catch (Throwable caught) {
                    failure.set(caught);
                }
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            assertEquals(TaskResult.SUCCEEDED,
                handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            if (failure.get() != null)
                throw new AssertionError("Serial original checkpoint failed", failure.get());
        }
    }

    /** A lost start response is reconciled only from the actual helper receipt on the same original task. / 丢失的启动响应仅由同一原任务的实际 helper 回执核对。
     * @throws Exception if the controlled helper peer or original task fails / 受控 helper peer 或原任务失败时 */
    @Test
    void originalRecoveryReadsActualHelperReceiptAndNeverReplaysStart() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = saved(runtime);
            var original = runtime.modContexts().get(OWNER).context();
            var state = Files.createDirectory(root.resolve("native-state"));
            var starts = new AtomicInteger();
            var statuses = new AtomicInteger();
            var observedStatus = new AtomicReference<String>();
            var session = session(request -> {
                if (request.script().contains("native-db start")) {
                    starts.incrementAndGet();
                    peer(state, "start", request.stdin());
                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                        Optional.of(new Problem(ProblemCode.UNKNOWN, "TEST_REPLY_LOST", "Controlled reply lost",
                            Map.of(), Optional.empty())));
                }
                if (request.script().contains("native-db status")) {
                    statuses.incrementAndGet();
                    var status = peer(state, "status", request.stdin());
                    observedStatus.set(status);
                    return reply(status);
                }
                return reply("HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                    + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            });
            var linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(),
                new Class<?>[]{LinuxService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("connect"))
                        return session;
                    throw new AssertionError("Only original connection is needed");
                });
            var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("linux"))
                        return linux;
                    try {
                        return method.invoke(original, args);
                    } catch (InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                });
            var callbackFailure = new AtomicReference<Throwable>();
            var handle = original.tasks().submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE,
                "Reconcile original native DB receipt", List.of(server.id()), true), task -> {
                try {
                    DeploymentStaticSetupActionLog.begin(original.tasks(), task, server);
                    var channel = new DeploymentNativeDatabaseChannel(session, original.interaction(), server,
                        task);
                    assertEquals(TaskResult.UNKNOWN,
                        channel.start(original.tasks(), "POSTGRESQL", "postgresql.service", "a".repeat(64))
                            .outcome());
                    var journal = DeploymentNativeDatabaseActionLog.find(original.tasks(), task).orElseThrow();
                    assertEquals("DISPATCHED", journal.facts().phase());
                    var prior = new TaskSnapshot(task.id(), OWNER, VERSION, DeploymentRefFlow.TASK_TYPE,
                        TaskState.RUNNING, Optional.empty(), "", Instant.now(), Optional.empty(),
                        List.of());
                    var reconciled = new DeploymentStaticTaskRecovery(bound, new DeploymentRefFlow(bound))
                        .reconcile(prior, task);
                    assertEquals(TaskResult.FAILED, reconciled.outcome(),
                        reconciled + " status=" + observedStatus.get());
                    assertTrue(journal.facts().effectsObserved());
                    assertTrue(original.tasks().resourcesReleased(task));
                } catch (Throwable failure) {
                    callbackFailure.set(failure);
                }
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            for (int attempt = 0; attempt < 100 && callbackFailure.get() == null
                && handle.snapshot().state() != TaskState.ENDED; attempt++)
                Thread.sleep(50);
            if (callbackFailure.get() != null)
                throw new AssertionError("Original native DB recovery failed", callbackFailure.get());
            assertEquals(TaskResult.SUCCEEDED,
                handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(1, starts.get());
            assertEquals(1, statuses.get());
            try (var files = Files.list(state)) {
                assertEquals(1, files.filter(path -> path.getFileName().toString().startsWith("operation-")).count());
            }
            assertTrue(approved.get() >= 3);
        }
    }

    private ServerId saved(PlatformRuntime runtime) throws Exception {
        var server = new ServerId("database-server");
        runtime.servers().saveServerProfile(
            ServerProfile
                .fromStored(new StoredServerProfile(server.id(), "127.0.0.1", 22, "root", "fixture-password")),
            "fixture-password".toCharArray());
        return server;
    }

    private interface Reply {
        OperationResult<CommandResult> execute(CommandRequest request) throws Exception;
    }

    private static RemoteSession session(Reply reply) {
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (method.getName().equals("execute"))
                    return reply.execute((CommandRequest) args[0]);
                if (method.getName().equals("close"))
                    return null;
                throw new AssertionError("Unexpected borrowed session call: " + method.getName());
            });
    }

    private static OperationResult<CommandResult> reply(String stdout) {
        return new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new CommandResult(Optional.of(0), stdout, "", false)), Optional.empty());
    }

    private String peer(Path state, String action, byte[] frame) throws Exception {
        var script = workspace().resolve("src/mod/deployment/src/test/python/test_native_database_operation.py");
        var process = new ProcessBuilder(System.getProperty("managed.test.python", "python"), script.toString(), "peer",
            state.toString(), action).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(frame);
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Controlled helper peer timed out");
            String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            String error = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), error);
            return output.replace("\r\n", "\n");
        } finally {
            java.util.Arrays.fill(frame, (byte) 0);
            if (process.isAlive())
                process.destroyForcibly();
        }
    }
}
