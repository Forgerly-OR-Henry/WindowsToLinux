package gold.debug.windowstolinux.mod.deployment.service.core.setup;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
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
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.source.DeploymentStandardSetupActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Checks original-task helper installation evidence before default publication uses a new server. / 默认发布使用新服务器前核对原任务 helper 安装依据。 */
class DeploymentProtocolBootstrapTest extends DeploymentRuntimeFixture {
    private enum ReceiptPath {
        VERIFIED, RETRY_UNCONFIRMED, MISSING_ABSENCE_FACT, ORIGINAL_RECEIPT_UNKNOWN, ORIGINAL_CLOSE_UNCONFIRMED
    }

    @ParameterizedTest
    @EnumSource(ReceiptPath.class)
    void publicationRetriesOnlyTheProvenUnsentReceiptWithAFreshGrant(ReceiptPath path) throws Exception {
        var server = new ServerId("synthetic-bootstrap-receipt");
        var taskId = UUID.randomUUID();
        TaskContext task = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
            new Class<?>[]{TaskContext.class}, (proxy, method, args) -> switch (method.getName()) {
                case "id" -> taskId;
                case "cancelToken" -> (CancelToken) () -> false;
                default -> throw new AssertionError(method.getName());
            });
        var checkpoint = new AtomicReference<TaskCheckpoint>();
        TaskService tasks = (TaskService) Proxy.newProxyInstance(TaskService.class.getClassLoader(),
            new Class<?>[]{TaskService.class}, (proxy, method, args) -> {
                assertSame(task, args[0]);
                if (method.getName().equals("findCheckpoint"))
                    return DeploymentProtocolBootstrapActionLog.KEY.equals(args[1])
                        ? Optional.ofNullable(checkpoint.get()) : Optional.empty();
                if (method.getName().equals("beginCheckpoint")) {
                    assertEquals(DeploymentProtocolBootstrapActionLog.KEY, args[1]);
                    var journal = new TaskCheckpoint() {
                        private Snapshot current = new Snapshot(0, (String) args[2], false);
                        public Snapshot snapshot() { return current; }
                        public Snapshot advance(Snapshot expected, String next) {
                            assertEquals(current, expected);
                            return current = new Snapshot(current.revision() + 1, next, false);
                        }
                        public void close() { current = new Snapshot(current.revision() + 1, current.payload(), true); }
                    };
                    assertTrue(checkpoint.compareAndSet(null, journal));
                    return journal;
                }
                throw new AssertionError(method.getName());
            });
        var reviewGrants = new HashMap<ApprovalGrant, ActionIntent>();
        var statusReviews = new ArrayList<ActionIntent>();
        InteractionService interaction = (InteractionService) Proxy.newProxyInstance(
            DeploymentCommandReview.class.getClassLoader(), new Class<?>[]{DeploymentCommandReview.class}, (proxy, method, args) -> {
                assertEquals("approveCommand", method.getName());
                assertInstanceOf(CommandRequest.class, args[1]);
                assertSame(task, args[2]);
                var intent = (ActionIntent) args[0];
                if ("deployment.helper.install.status.v1".equals(intent.semantics().orElseThrow().operation()))
                    statusReviews.add(intent);
                var grant = new ApprovalGrant(UUID.randomUUID());
                reviewGrants.put(grant, intent);
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(grant), Optional.empty());
            });
        var firstDiagnostic = UUID.randomUUID();
        var absence = new HashMap<>(Map.ofEntries(Map.entry("commandPhase", "CHANNEL_OPENING"),
            Map.entry("commandOpenRequested", "true"), Map.entry("channelOpenConfirmed", "false"),
            Map.entry("channelPeerAssigned", "false"), Map.entry("inputState", "NOT_STARTED"),
            Map.entry("inputSenderCreated", "false"), Map.entry("authorPayloadState", "NOT_SENT"),
            Map.entry("outputObserved", "false"), Map.entry("exitObserved", "false"),
            Map.entry("channelOpenWriteState", "WRITTEN"), Map.entry("channelOpenReply", "NOT_OBSERVED"),
            Map.entry("transportSocketReadPending", "true"), Map.entry("transportSocketReadCompletedDuringCommand", "0"),
            Map.entry("privateCommandOutput", "excluded")));
        if (path == ReceiptPath.MISSING_ABSENCE_FACT) absence.remove("inputSenderCreated");
        var receiptProblem = new Problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT",
            "Synthetic receipt read failure", absence, Optional.of(firstDiagnostic));
        var firstReceipt = new OperationResult<>(path == ReceiptPath.ORIGINAL_RECEIPT_UNKNOWN ? TaskResult.UNKNOWN : TaskResult.FAILED,
            Optional.of(new CommandResult(Optional.empty(), "", "", false)), Optional.of(receiptProblem));

        var connections = new AtomicInteger();
        var closes = new AtomicInteger();
        var installs = new AtomicInteger();
        var layout = DeploymentRemoteLayout.original();
        LinuxService linux = new LinuxService() {
            public RemoteSession connect(ServerId selected, TaskContext original) {
                assertSame(task, original);
                assertEquals(server, selected);
                int connection = connections.incrementAndGet();
                assertTrue(connection <= 2);
                assertEquals(connection - 1, closes.get());
                return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                    new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close")) {
                            if (connection == 1 && path == ReceiptPath.ORIGINAL_CLOSE_UNCONFIRMED)
                                throw new ApiException(problem(ProblemCode.IO_FAILURE, "TEST_CLOSE_UNCONFIRMED"));
                            closes.incrementAndGet();
                            return null;
                        }
                        assertEquals("execute", method.getName());
                        var intent = reviewGrants.remove((ApprovalGrant) args[1]);
                        assertNotNull(intent, "Each command needs a new grant");
                        return switch (intent.semantics().orElseThrow().operation()) {
                            case "deployment.helper.probe.v1" ->
                                connection == 1 ? exited(64, "") : ok(probeOutput());
                            case "deployment.helper.install.tracked.v1" -> {
                                assertEquals(1, connection);
                                assertEquals(1, installs.incrementAndGet());
                                assertEquals("DISPATCHED", DeploymentProtocolBootstrapActionLog.find(tasks, task)
                                    .orElseThrow().facts().phase());
                                yield ok("installer process exit observed");
                            }
                            case "deployment.helper.install.status.v1" -> {
                                var facts = DeploymentProtocolBootstrapActionLog.find(tasks, task).orElseThrow().facts();
                                assertEquals("DISPATCHED", facts.phase());
                                assertEquals(DeploymentHelperInstallerScript.renderStatus(facts.operationId(), layout),
                                    new String(((CommandRequest) args[0]).stdin(), StandardCharsets.UTF_8));
                                if (connection == 1 || path == ReceiptPath.RETRY_UNCONFIRMED) yield firstReceipt;
                                yield ok(DeploymentHelperInstallerScript.receipt(taskId, facts.operationId(), facts.serverDigest(),
                                    facts.bodyDigest(), "SUCCEEDED", layout));
                            }
                            default -> throw new AssertionError(intent.semantics());
                        };
                    });
            }

            public List<gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint> pendingTransfers(TaskContext original) {
                throw new AssertionError("Receipt verification cannot transfer files");
            }
        };

        var observed = DeploymentProtocolBootstrap.ensureForPublication(tasks, task, server, linux, interaction, layout);
        boolean retried = path == ReceiptPath.VERIFIED || path == ReceiptPath.RETRY_UNCONFIRMED;
        boolean verified = path == ReceiptPath.VERIFIED;
        assertEquals(retried ? 2 : 1, connections.get());
        assertEquals(path == ReceiptPath.ORIGINAL_CLOSE_UNCONFIRMED ? 0 : connections.get(), closes.get());
        assertEquals(connections.get(), statusReviews.size());
        if (retried) assertEquals(statusReviews.getFirst(), statusReviews.getLast());
        assertTrue(reviewGrants.isEmpty());
        assertEquals(1, installs.get());
        assertEquals(verified ? TaskResult.SUCCEEDED : TaskResult.UNKNOWN, observed.outcome());
        assertEquals(verified, checkpoint.get().snapshot().released());
        assertEquals(verified ? "SUCCEEDED" : "DISPATCHED",
            DeploymentProtocolBootstrapActionLog.find(tasks, task).orElseThrow().facts().phase());
        if (!verified) {
            var original = observed.problem().orElseThrow();
            assertEquals(Optional.of(firstDiagnostic), original.diagnosticId());
            assertEquals("0", original.context().get("installerObservedExitCode"));
            assertEquals("SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT", original.context().get("statusProblemEvent"));
            assertEquals("WRITTEN", original.context().get("channelOpenWriteState"));
            assertFalse(original.context().containsKey("statusExitCode"));
            assertFalse(original.context().containsKey("privateCommandOutput"));
            if (retried)
                assertEquals("DEPLOYMENT_HELPER_BOOTSTRAP_STATUS_UNCONFIRMED", original.context().get("receiptRetryEvent"));
            if (path == ReceiptPath.ORIGINAL_CLOSE_UNCONFIRMED)
                assertEquals("TEST_CLOSE_UNCONFIRMED", original.context().get("firstSessionCloseEvent"));
        }
    }

    @Test
    void probeEvidenceControlsInstallationAndPreservesOriginalFailure() throws Exception {
        var notSent = new Problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT", "Not sent",
            Map.of("commandPhase", "CHANNEL_OPENING", "authorPayloadState", "NOT_SENT"), Optional.of(UUID.randomUUID()));
        var cases = List.of(
            new OperationResult<CommandResult>(TaskResult.FAILED,
                Optional.of(new CommandResult(Optional.empty(), "", "", false)), Optional.of(notSent)),
            new OperationResult<CommandResult>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(notSent)),
            new OperationResult<CommandResult>(TaskResult.CANCELLED, Optional.empty(), Optional.of(notSent)),
            exited(7, ""), exited(64, ""), exited(3, ""));
        for (var probe : cases) {
            boolean install = probe.value().flatMap(CommandResult::exitCode).filter(code -> code == 64 || code == 3).isPresent();
            var checkpoints = new java.util.HashMap<String, TaskCheckpoint>();
            var begins = new AtomicInteger();
            TaskService tasks = (TaskService) Proxy.newProxyInstance(TaskService.class.getClassLoader(),
                new Class<?>[]{TaskService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findCheckpoint")) return Optional.ofNullable(checkpoints.get(args[1]));
                    if (method.getName().equals("beginCheckpoint")) {
                        begins.incrementAndGet();
                        TaskCheckpoint checkpoint = new TaskCheckpoint() {
                            Snapshot current = new Snapshot(0, (String) args[2], false);
                            public Snapshot snapshot() { return current; }
                            public Snapshot advance(Snapshot expected, String next) {
                                assertEquals(current, expected);
                                return current = new Snapshot(current.revision() + 1, next, false);
                            }
                            public void close() { current = new Snapshot(current.revision() + 1, current.payload(), true); }
                        };
                        checkpoints.put((String) args[1], checkpoint);
                        return checkpoint;
                    }
                    throw new AssertionError(method.getName());
                });
            TaskContext task = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
                new Class<?>[]{TaskContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> UUID.fromString("bd453aba-d5b0-4edf-8a50-b451a619ece1");
                    case "cancelToken" -> (CancelToken) () -> false;
                    default -> throw new AssertionError(method.getName());
                });
            var installs = new AtomicInteger();
            var installReviews = new AtomicInteger();
            var probes = new AtomicInteger();
            var session = session(request -> {
                if (request.stdin().length == 0) { probes.incrementAndGet(); return probe; }
                installs.incrementAndGet();
                return exited(7, "");
            });
            InteractionService unused = (InteractionService) Proxy.newProxyInstance(InteractionService.class.getClassLoader(),
                new Class<?>[]{InteractionService.class}, (proxy, method, args) -> { throw new AssertionError(method.getName()); });
            var observed = new DeploymentProtocolBootstrap(tasks, task, new ServerId("synthetic-bootstrap"), session,
                unused, (intent, request, original) -> {
                    if (intent.semantics().orElseThrow().operation().equals("deployment.helper.install.tracked.v1"))
                        installReviews.incrementAndGet();
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(UUID.randomUUID())), Optional.empty());
                }).ensure();
            assertEquals(1, probes.get());
            assertEquals(install ? 1 : 0, begins.get());
            assertEquals(install ? 1 : 0, installReviews.get());
            assertEquals(install ? 1 : 0, installs.get());
            if (!install) {
                assertEquals(probe.outcome(), observed.outcome());
                assertSame(probe.problem().orElseThrow(), observed.problem().orElseThrow());
                assertTrue(observed.value().isEmpty());
            } else {
                assertEquals(TaskResult.FAILED, observed.outcome());
                assertTrue(checkpoints.get(DeploymentProtocolBootstrapActionLog.KEY).snapshot().released());
            }
        }
    }

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
                    return probes.getAndIncrement() == 0 ? exited(64, "") : ok(probeOutput());
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
                return exited(64, "");
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
                        return exited(64, "");
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
        return new OperationResult<>(code == 0 ? TaskResult.SUCCEEDED : TaskResult.FAILED,
            Optional.of(new CommandResult(Optional.of(code), stdout, "", false)), code == 0 ? Optional.empty()
                : Optional.of(problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_NONZERO")));
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
