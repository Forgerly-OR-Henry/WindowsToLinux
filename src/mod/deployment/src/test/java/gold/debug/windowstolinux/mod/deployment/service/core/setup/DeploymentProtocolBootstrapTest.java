package gold.debug.windowstolinux.mod.deployment.service.core.setup;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferProgress;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStall;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.source.DeploymentStandardSetupActionLog;
import org.junit.jupiter.api.Test;

/** Checks original-task helper installation evidence before default publication uses a new server. / 默认发布使用新服务器前核对原任务 helper 安装依据。 */
class DeploymentProtocolBootstrapTest extends DeploymentRuntimeFixture {
    /** Host permits the controlled fixed commands. / 宿主允许受控固定命令。 */
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

    /** Lost installer reply and absent receipt stay unknown until the exact original status appears. / 安装回包丢失及回执缺失持续未知，直至精确原状态出现。
     * @throws Exception if the real task or checkpoint cannot be observed / 真实任务或检查点无法观察时 */
    @Test
    void originalTaskDoesNotReplayUnknownInstallerAndSettlesExactReceipt() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = saved(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var original = new AtomicReference<TaskContext>();
            var installed = new AtomicInteger();
            var statusReady = new AtomicBoolean();
            var corruptReceipt = new AtomicBoolean();
            var probes = new AtomicInteger();
            var borrowed = session(request -> {
                if (!request.script().contains("--noprofile")) {
                    assertTrue(request.script().contains("if [ ! -f /usr/bin/sqlite3 ] || [ ! -x /usr/bin/sqlite3 ]; then exit 3; fi"));
                    return probes.getAndIncrement() == 0 ? exited(1, "") : ok(probeOutput());
                }
                String input = new String(request.stdin(), StandardCharsets.UTF_8);
                if (input.contains("prepare_stage=platform-packages")) {
                    assertTrue(input.contains("acl sudo iproute2 sqlite3\n"));
                    assertTrue(input.contains("acl sudo iproute sqlite\n"));
                    installed.incrementAndGet();
                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                        Optional.of(problem(ProblemCode.UNKNOWN, "TEST_INSTALL_REPLY_LOST")));
                }
                var facts = DeploymentProtocolBootstrapActionLog.find(context.tasks(), original.get()).orElseThrow()
                    .facts();
                if (!statusReady.get()) return exited(3, "");
                String receipt = DeploymentHelperInstallerScript.receipt(original.get().id(), facts.operationId(),
                    facts.serverDigest(), facts.bodyDigest(), "SUCCEEDED");
                return ok(corruptReceipt.get() ? receipt.replace("TASK=" + original.get().id(),
                    "TASK=00000000-0000-0000-0000-000000000001") : receipt);
            });
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE,
                "Review original helper bootstrap", List.of(server.id()), true), task -> {
                try {
                    original.set(task);
                    var marker = DeploymentStandardSetupActionLog.begin(context.tasks(), task, server);
                    var bootstrap = new DeploymentProtocolBootstrap(context.tasks(), task, server, borrowed,
                        context.interaction());
                    assertEquals(TaskResult.UNKNOWN, bootstrap.ensure().outcome());
                    var journal = DeploymentProtocolBootstrapActionLog.find(context.tasks(), task).orElseThrow();
                    assertEquals("DISPATCHED", journal.facts().phase());
                    assertEquals(TaskResult.UNKNOWN, bootstrap.reconcile(journal).outcome());
                    assertEquals("DISPATCHED", journal.facts().phase());
                    statusReady.set(true);
                    corruptReceipt.set(true);
                    assertEquals(TaskResult.UNKNOWN, bootstrap.reconcile(journal).outcome());
                    assertEquals("DISPATCHED", journal.facts().phase());
                    assertEquals(1, installed.get(), "A foreign task receipt never authorizes installation replay");
                    corruptReceipt.set(false);
                    assertEquals(TaskResult.SUCCEEDED, bootstrap.reconcile(journal).outcome());
                    assertEquals("SUCCEEDED", journal.facts().phase());
                    marker.close();
                    assertTrue(context.tasks().resourcesReleased(task));
                } catch (Throwable error) {
                    failure.set(error);
                }
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            assertEquals(TaskResult.SUCCEEDED,
                handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS).outcome());
            if (failure.get() != null)
                throw new AssertionError("Original helper bootstrap failed", failure.get());
            assertEquals(1, installed.get());
            assertEquals(2, probes.get());
        }
    }

    /** A host refusal before execute releases the unsent journal and never installs. / 宿主执行前拒绝会释放未发送日志且绝不安装。
     * @throws Exception if the real task cannot be admitted / 真实任务无法准入时 */
    @Test
    void preDispatchCancellationLeavesNoUnknownInstaller() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = saved(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var installed = new AtomicInteger();
            var borrowed = session(request -> {
                if (request.script().contains("--noprofile"))
                    installed.incrementAndGet();
                return exited(1, "");
            });
            var review = new InteractionService() {
                public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext task)
                    throws ApiException {
                    if (intent.semantics()
                        .filter(value -> value.operation().equals("deployment.helper.install.tracked.v1"))
                        .isPresent())
                        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(),
                            Optional.of(problem(ProblemCode.CANCELLED, "TEST_INSTALL_DECLINED")));
                    return context.interaction().approve(intent, task);
                }

                public OperationResult<String> requestText(String title, String prompt, boolean secret,
                                                           TaskContext task) throws ApiException {
                    return context.interaction().requestText(title, prompt, secret, task);
                }
            };
            var handle = context.tasks().submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE,
                "Decline helper installation", List.of(server.id()), true), task -> {
                var outcome = new DeploymentProtocolBootstrap(context.tasks(), task, server, borrowed, review)
                    .ensure();
                assertEquals("INTENT", DeploymentProtocolBootstrapActionLog.find(context.tasks(), task)
                    .orElseThrow().facts().phase());
                assertTrue(context.tasks().resourcesReleased(task));
                return new OperationResult<Void>(outcome.outcome(), Optional.empty(), outcome.problem());
            });
            assertEquals(TaskResult.CANCELLED,
                handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS).outcome());
            assertEquals(0, installed.get());
        }
    }

    /** A returned bounded process failure is known, whereas unknown or signal-like observations retain dispatch. / 已返回有界进程失败为已知，未知或类信号观察保留发送阶段。
     * @throws Exception if original task admission or controlled cleanup fails / 原任务准入或受控清理失败时 */
    @Test
    void processExitClassificationPreservesUncertainInstallations() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = saved(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            List<OperationResult<CommandResult>> cases = List.of(
                new OperationResult<>(TaskResult.SUCCEEDED,
                    Optional.of(new CommandResult(Optional.of(7), "", "", false)), Optional.<Problem>empty()),
                new OperationResult<>(TaskResult.UNKNOWN,
                    Optional.of(new CommandResult(Optional.of(7), "", "", false)),
                    Optional.of(problem(ProblemCode.UNKNOWN, "TEST_UNKNOWN_EXIT"))),
                new OperationResult<>(TaskResult.CANCELLED,
                    Optional.of(new CommandResult(Optional.of(7), "", "", false)),
                    Optional.of(problem(ProblemCode.CANCELLED, "TEST_CANCELLED_EXIT"))),
                new OperationResult<>(TaskResult.SUCCEEDED,
                    Optional.of(new CommandResult(Optional.of(143), "", "", false)),
                    Optional.<Problem>empty()));
            for (int i = 0; i < cases.size(); i++) {
                var observed = cases.get(i);
                boolean known = i == 0;
                var failure = new AtomicReference<Throwable>();
                var borrowed = session(request -> {
                    if (!request.script().contains("--noprofile"))
                        return exited(1, "");
                    if (new String(request.stdin(), StandardCharsets.UTF_8).contains("prepare_stage=platform-packages"))
                        return observed;
                    return exited(3, "");
                });
                var handle = context.tasks().submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE,
                    "Classify original helper installation", List.of(server.id()), true), task -> {
                    try {
                        var result = new DeploymentProtocolBootstrap(context.tasks(), task, server, borrowed,
                            context.interaction()).ensure();
                        assertEquals(known ? TaskResult.FAILED : TaskResult.UNKNOWN, result.outcome());
                        var journal = DeploymentProtocolBootstrapActionLog.find(context.tasks(), task)
                            .orElseThrow();
                        assertEquals(known ? "FAILED" : "DISPATCHED", journal.facts().phase());
                        if (!known) {
                            assertFalse(context.tasks().resourcesReleased(task));
                            // Only this isolated fixture settles an intentionally unresolved operation.
                            // 仅该隔离夹具收尾故意保留的未决操作。
                            journal.terminal(false);
                            journal.close();
                        }
                        assertTrue(context.tasks().resourcesReleased(task));
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                });
                assertEquals(TaskResult.SUCCEEDED,
                    handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS).outcome());
                if (failure.get() != null)
                    throw new AssertionError("Helper process classification failed", failure.get());
            }
        }
    }

    /** Saves one exact non-secret server profile for original admission. / 保存用于原准入的精确非秘密服务器资料。
     * @param runtime current platform / 当前平台
     * @return saved server reference / 已存服务器引用
     * @throws Exception if storage fails / 存储失败时 */
    private static ServerId saved(PlatformRuntime runtime) throws Exception {
        var server = new ServerId("helper-bootstrap-server");
        runtime.servers().saveServerProfile(
            ServerProfile
                .fromStored(new StoredServerProfile(server.id(), "127.0.0.1", 22, "root", "fixture-password")),
            "fixture-password".toCharArray());
        return server;
    }

    /** Borrowed command reply callback. / 借用命令回执回调。 */
    private interface Reply {
        /** Returns one controlled observation. / 返回一项受控观察。
         * @param request reviewed request / 已审核请求
         * @return command observation / 命令观察
         * @throws Exception if the fixture fails / 夹具失败时 */
        OperationResult<CommandResult> execute(CommandRequest request) throws Exception;
    }

    /** Creates a borrowed session without changing ownership. / 创建不改变所有权的借用会话。
     * @param reply controlled reply / 受控回执
     * @return borrowed session / 借用会话 */
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

    /** Returns a known successful command. / 返回已知成功命令。
     * @param stdout output / 输出
     * @return command result / 命令结果 */
    private static OperationResult<CommandResult> ok(String stdout) {
        return exited(0, stdout);
    }

    /** Returns a known process exit. / 返回已知进程退出。
     * @param code exit status / 退出码
     * @param stdout output / 输出
     * @return command result / 命令结果 */
    private static OperationResult<CommandResult> exited(int code, String stdout) {
        return new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new CommandResult(Optional.of(code), stdout, "", false)), Optional.empty());
    }

    /** Creates one bounded test problem. / 构造有界测试问题。
     * @param code category / 类别
     * @param event event / 事件
     * @return test problem / 测试问题 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Controlled original helper installation", Map.of(), Optional.empty());
    }

    /** Expected fixed probe response. / 预期固定探测回包。
     * @return probe output / 探测输出 */
    private static String probeOutput() {
        return "HELPER=1\nPROTOCOL=16\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n";
    }
}
