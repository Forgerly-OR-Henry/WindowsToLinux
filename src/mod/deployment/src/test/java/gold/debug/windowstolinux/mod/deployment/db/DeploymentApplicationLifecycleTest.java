package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteFacts;
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
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real CLASS/H2 tasks and production management channels against a controlled peer. / 真实CLASS/H2任务及生产管理频道连接受控对等端。 */
class DeploymentApplicationLifecycleTest extends DeploymentManagedApplicationFixture {
    enum ScenarioType {
        CURRENT_DRIFT, DISPATCH_DRIFT, DEGRADED_STOP, RESTART_ACK_LOST, FINAL_HEALTH_FAILURE, FINAL_CURRENT_DRIFT
    }

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken token) {
                return true;
            }

            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerId server,
                                                           TaskSnapshot task, CancelToken token) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerId server, TaskSnapshot task,
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
        };
    }

    @ParameterizedTest
    @EnumSource(value = ScenarioType.class, names = "CURRENT_DRIFT", mode = EnumSource.Mode.EXCLUDE)
    void originalCurrentAndAcknowledgementsGateEveryLifecycle(ScenarioType scenario) throws Exception {
        if (scenario == ScenarioType.DISPATCH_DRIFT || scenario == ScenarioType.RESTART_ACK_LOST
            || scenario == ScenarioType.FINAL_CURRENT_DRIFT)
            runIsolated(scenario.name());
        else
            runScenario(scenario, false);
    }

    @org.junit.jupiter.api.Test
    void originalCurrentDriftPrecedesEveryLifecycleEffect() throws Exception {
        runScenario(ScenarioType.CURRENT_DRIFT, false);
    }

    /** Runs actual management evidence without inventing an unknown final acknowledgement. / 执行实际管理依据，不捏造未知最终确认。
     * @param scenario exact peer behavior / 精确对等端行为
     * @param interruptedHost whether the child preserves a pending host / 子进程是否保留待核对宿主
     * @throws Exception if actual original evidence differs / 实际原依据不符时 */
    private void runScenario(ScenarioType scenario, boolean interruptedHost) throws Exception {
        enable();
        var runtime = open();
        Throwable originalFailure = null;
        try {
            start(runtime, true);
            server(runtime);
            var graph = reviewedGraph();
            run(runtime, task -> {
                publishManaged(runtime, task, graph, 1);
                return success();
            });
            var actual = runtime.modContexts().get(OWNER).context();
            var peer = new Peer(scenario);
            ModContext context = bind(actual, peer);
            var failures = new AtomicReference<Throwable>();
            var handle = actual.tasks().submit(
                new TaskRequest("graph_acceptance", "Lifecycle contract", List.of("server"), true), task -> {
                    try {
                        var app = new DeploymentApplicationId("application", new ServerId("server"));
                        var snapshot = new DeploymentApplicationReader(context.db()).find(app, task).snapshot();
                        var manager = new DeploymentApplicationRunner(context);
                        var action = scenario == ScenarioType.DEGRADED_STOP
                            || scenario == ScenarioType.CURRENT_DRIFT || scenario == ScenarioType.DISPATCH_DRIFT
                            || scenario == ScenarioType.FINAL_CURRENT_DRIFT
                            ? DeploymentLifecycleAction.STOP
                            : DeploymentLifecycleAction.RESTART;
                        var first = manager.lifecycle(app, snapshot.revisionSha256(), action, task);
                        if (scenario == ScenarioType.CURRENT_DRIFT) {
                            assertEquals(TaskResult.FAILED, first.outcome());
                            assertEquals(0, peer.effects.get());
                            assertTrue(new DeploymentManagementActionLog(context).find(task).isEmpty());
                        } else if (scenario == ScenarioType.DEGRADED_STOP) {
                            assertEquals(TaskResult.SUCCEEDED, first.outcome());
                            assertEquals(2, peer.effects.get());
                        } else {
                            assertEquals(TaskResult.UNKNOWN, first.outcome());
                            assertEquals("UNKNOWN",
                                new DeploymentManagementActionLog(context).find(task).orElseThrow().phase());
                        }
                        if (scenario == ScenarioType.CURRENT_DRIFT)
                            return new OperationResult<Void>(first.outcome(), Optional.empty(), first.problem());
                        assertCompleteObservation(context, task);
                        int before = peer.effects.get();
                        peer.healthy = true;
                        var repeated = manager.lifecycle(app, snapshot.revisionSha256(), action, task);
                        assertEquals(before, peer.effects.get(),
                            "Repeated original-task query cannot resend lifecycle");
                        TaskResult expected = scenario == ScenarioType.DISPATCH_DRIFT
                            || scenario == ScenarioType.RESTART_ACK_LOST
                            || scenario == ScenarioType.FINAL_CURRENT_DRIFT
                            ? TaskResult.UNKNOWN
                            : TaskResult.SUCCEEDED;
                        assertEquals(expected, repeated.outcome());
                        if (scenario == ScenarioType.DISPATCH_DRIFT)
                            assertEquals(0, peer.effects.get(),
                                "Lock-time same-owner CURRENT drift prevents the effect");
                        if (scenario == ScenarioType.FINAL_CURRENT_DRIFT)
                            assertEquals(2, peer.effects.get(),
                                "Both stops completed before the final CURRENT drift");
                        return new OperationResult<Void>(repeated.outcome(), Optional.empty(), repeated.problem());
                    } catch (Exception | AssertionError failure) {
                        failures.set(failure);
                        throw failure;
                    }
                });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (handle.snapshot().state() != TaskState.ENDED && handle.snapshot().state() != TaskState.UNKNOWN
                && System.nanoTime() < deadline)
                Thread.sleep(20);
            if (failures.get() != null)
                throw new AssertionError("Original management fixture failed", failures.get());
            assertTrue(handle.snapshot().state() == TaskState.ENDED || handle.snapshot().state() == TaskState.UNKNOWN,
                handle.snapshot().toString());
            assertEquals(0, peer.sessions.get(), "All provider sessions close before the task reports its state");
            assertEquals(
                scenario == ScenarioType.DISPATCH_DRIFT || scenario == ScenarioType.RESTART_ACK_LOST
                    || scenario == ScenarioType.FINAL_CURRENT_DRIFT ? TaskState.UNKNOWN : TaskState.ENDED,
                handle.snapshot().state());
            if (interruptedHost) {
                var refusal = assertThrows(ApiException.class, runtime::close);
                assertEquals(ProblemCode.BUSY, refusal.problem().code());
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state(),
                    "Shutdown refusal cannot conclude the original task");
                assertEquals(0, peer.sessions.get());
            }
        } catch (Exception | AssertionError failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            if (!interruptedHost) {
                if (originalFailure == null)
                    runtime.close();
                else
                    try {
                        runtime.close();
                    } catch (Exception | AssertionError cleanup) {
                        originalFailure.addSuppressed(cleanup);
                    }
            }
        }
    }

    /** Enters only the test-owned unknown child and exits without claiming host cleanup. / 仅进入测试所属未知子进程并不冒称宿主清理地退出。
     * @param args fixed marker, original directory and scenario / 固定标志、原目录及场景 */
    public static void main(String[] args) {
        if (args.length != 3 || !args[0].equals("--unknown"))
            throw new IllegalArgumentException("Original management child only");
        try {
            var scenario = ScenarioType.valueOf(args[2]);
            if (scenario != ScenarioType.DISPATCH_DRIFT && scenario != ScenarioType.RESTART_ACK_LOST
                && scenario != ScenarioType.FINAL_CURRENT_DRIFT)
                throw new IllegalArgumentException("Unknown management scenario only");
            var probe = new DeploymentApplicationLifecycleTest();
            probe.root = java.nio.file.Path.of(args[1]);
            probe.runScenario(scenario, true);
            System.out.println("MANAGED_APPLICATION_UNKNOWN_VERIFIED");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    /** Verifies the real owned H2 observation tuple without manufacturing enablement evidence. / 核对真实所属H2观测元组，不捏造启用依据。
     * @param context original deployment context / 原部署上下文
     * @param task authenticated original task / 已认证原任务
     * @throws Exception if persisted complete evidence differs / 持久完整依据不符时 */
    private void assertCompleteObservation(ModContext context, TaskContext task) throws Exception {
        try (var connection = context.db().openConnection(task);
             var query = connection.prepareStatement(
                 "SELECT runtime_state,autostart_state,is_ownership_verified,observation_evidence,observed_at FROM deployment_unit ORDER BY deployment_unit_id");
             var rows = query.executeQuery()) {
            int count = 0;
            while (rows.next()) {
                count++;
                assertNotNull(rows.getString(1));
                assertEquals(rows.getString(1).equals("UNKNOWN") ? "UNKNOWN" : "ENABLED", rows.getString(2));
                assertNotNull(rows.getObject(3));
                assertEquals("deployment-helper-observation-v1", rows.getString(4));
                assertNotNull(rows.getObject(5));
            }
            assertEquals(2, count);
        }
    }

    private DeploymentApplicationGraph reviewedGraph() {
        var original = graph("application");
        var parts = original.components().stream().map(value -> {
            var old = value.runtime().workload();
            var workload = new DeploymentWorkload(old.mode(), true, old.command(), old.workingDirectory(), old.endpoints(),
                old.verification(), old.expectedOutput(), old.client(), old.inputs(), old.companions(), old.buildDirectory(), old.workers());
            var definition = value.reviewedRuntime().withWorkload(workload);
            var runtime = new DeploymentUnitRuntimeConfig(value.runtime().healthCheck(), value.runtime().userAccessUrl(),
                value.runtime().identityPolicy(), workload);
            return new DeploymentApplicationGraph.Part(value.componentId(), value.unit(), runtime, value.dependencies(),
                definition, value.dataPaths(), value.resources());
        }).toList();
        return new DeploymentApplicationGraph(original.applicationId(), original.healthComponentId(), original.healthCheck(), parts);
    }

    private ModContext bind(ModContext actual, Peer peer) {
        LinuxService linux = new LinuxService() {
            public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                return peer.session(task);
            }

            public RemoteSession connect(gold.debug.windowstolinux.appapi.linux.session.RemoteTarget expected, TaskContext task) throws ApiException {
                var current = actual.servers().get(expected.server(), task);
                assertEquals(task.id(), expected.taskId());
                assertEquals(current.host(), expected.host());
                assertEquals(current.port(), expected.port());
                assertEquals(current.username(), expected.username());
                expected.fingerprint().ifPresent(value -> assertEquals("SHA256:" + "A".repeat(43), value));
                return peer.session(task);
            }

            public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                return List.of();
            }
        };
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("linux"))
                    return linux;
                try {
                    return method.invoke(actual, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }

    private static final class Peer {
        final ScenarioType scenario;

        final AtomicInteger effects = new AtomicInteger();

        final AtomicInteger sessions = new AtomicInteger();

        final Map<String, String> current = new HashMap<>();

        final Map<String, Boolean> running = new HashMap<>();

        volatile boolean healthy;

        Peer(ScenarioType scenario) {
            this.scenario = scenario;
            for (String unit : List.of("web", "worker")) {
                current.put(unit, "a".repeat(64));
                running.put(unit, true);
            }
            if (scenario == ScenarioType.CURRENT_DRIFT)
                current.put("worker", "f".repeat(64));
        }

        RemoteSession session(TaskContext task) throws ApiException {
            var lease = task.retain("controlled_ssh", "management-peer-" + UUID.randomUUID(),
                "Controlled management session");
            sessions.incrementAndGet();
            return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                    if (method.getName().equals("facts"))
                        return new RemoteFacts("linux", "x86_64", "SHA256:" + "A".repeat(43));
                    if (method.getName().equals("close")) {
                        lease.close();
                        sessions.decrementAndGet();
                        return null;
                    }
                    if (method.getName().equals("execute")) {
                        assertNotNull(args[1]);
                        return command((CommandRequest) args[0]);
                    }
                    throw new AssertionError(method.getName());
                });
        }

        private OperationResult<CommandResult> command(CommandRequest request) {
            if (request.script().contains("/usr/bin/sha256sum --check --status"))
                return reply(0, "HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                    + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            var args = new ArrayList<String>();
            var matcher = Pattern.compile("'([^']*)'").matcher(request.script());
            while (matcher.find())
                args.add(matcher.group(1));
            String verb = args.get(1), unit = args.get(2);
            if (verb.equals("inspect-runtime")) {
                assertEquals(4, args.size());
                assertEquals("a".repeat(64), args.get(3));
                assertTrue(current.containsKey(unit));
                return reply(0, "KIND=deployment\nMODE=DAEMON\n");
            }
            if (verb.equals("managed-current-status")) {
                if (scenario == ScenarioType.FINAL_CURRENT_DRIFT && effects.get() == 2)
                    current.put("worker", "f".repeat(64));
                return reply(0, "MANAGED_CURRENT=1\nKIND=deployment\nRELEASE_SHA256=" + current.get(unit) + "\n");
            }
            if (verb.equals("application-health"))
                return reply(scenario == ScenarioType.DEGRADED_STOP
                        || scenario == ScenarioType.FINAL_HEALTH_FAILURE && effects.get() >= 4 && !healthy ? 1 : 0,
                    "HEALTHY=1\n");
            if (verb.equals("observe-deployment"))
                return observe(unit);
            if (verb.equals("lifecycle-deployment")) {
                assertEquals(6, args.size());
                assertEquals("a".repeat(64), args.get(4));
                if (scenario == ScenarioType.DISPATCH_DRIFT)
                    current.put(unit, "f".repeat(64));
                if (!current.get(unit).equals(args.get(5)))
                    return reply(1, "");
                effects.incrementAndGet();
                String action = args.get(3);
                running.put(unit, !action.equals("stop"));
                if (scenario == ScenarioType.RESTART_ACK_LOST && action.equals("start")) {
                    running.replaceAll((key, value) -> true);
                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                        Optional.of(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_START_ACK_LOST",
                            "Start acknowledgement unavailable", Map.of(), Optional.empty())));
                }
                return reply(0, "LIFECYCLE=" + action + "\n");
            }
            throw new AssertionError(request.script());
        }

        private OperationResult<CommandResult> observe(String unit) {
            boolean active = running.get(unit);
            return reply(0, "OWNER=1\nRUNNING=" + (active ? "1" : "0") + "\nENABLED=enabled\nQUERY_OK=1\nActiveState="
                + (active ? "active" : "inactive") + "\nSubState=" + (active ? "running" : "dead")
                + "\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID=" + (active ? "123" : "0") + "\n");
        }

        private OperationResult<CommandResult> reply(int exit, String text) {
            return new OperationResult<>(TaskResult.SUCCEEDED,
                Optional.of(new CommandResult(Optional.of(exit), text, "", false)), Optional.empty());
        }
    }
}
