package gold.debug.windowstolinux.mod.deployment.db;

import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Checks actual retained orchestration and atomic histories under real CLASS/H2 tasks. / 在真实CLASS/H2任务下核对实际保留编排与原子历史。 */
class DeploymentRetainedSwitchTest extends DeploymentManagedApplicationFixture {
    enum ScenarioType {
        CURRENT_DRIFT, SNAPSHOT_WINDOW_DRIFT, HEALTH_ROLLBACK, STOPPED_SWITCH, COMPONENT_MAPPING_CONFLICT
    }

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken token) {
                return true;
            }

            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerRef server,
                                                           TaskSnapshot task, CancelToken token) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskSnapshot task,
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
    @EnumSource(value = ScenarioType.class, names = "COMPONENT_MAPPING_CONFLICT")
    void switchUsesExactCurrentOriginalMappingAndVerifiedRollback(ScenarioType scenario) throws Exception {
        if (scenario == ScenarioType.SNAPSHOT_WINDOW_DRIFT)
            runIsolated(scenario.name());
        else
            runScenario(scenario, false);
    }

    @org.junit.jupiter.api.Test
    void originalCurrentDriftPrecedesEveryRetainedSnapshot() throws Exception {
        runScenario(ScenarioType.CURRENT_DRIFT, false);
    }

    @org.junit.jupiter.api.Test
    void originalSnapshotWindowDriftKeepsUnknownEvidence() throws Exception {
        runIsolated(ScenarioType.SNAPSHOT_WINDOW_DRIFT.name());
    }

    @org.junit.jupiter.api.Test
    void originalHealthFailureRollsBackEveryRetainedComponent() throws Exception {
        runScenario(ScenarioType.HEALTH_ROLLBACK, false);
    }

    @org.junit.jupiter.api.Test
    void originalStoppedApplicationStaysStoppedAfterRetainedSwitch() throws Exception {
        runScenario(ScenarioType.STOPPED_SWITCH, false);
    }

    /** Runs a real retained transition while preserving permanently unknown evidence. / 执行真实保留转换并保留永久未知依据。
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
            var first = new AtomicReference<DeploymentReleaseRecord>();
            var second = new AtomicReference<DeploymentReleaseRecord>();
            run(runtime, task -> {
                first.set(publishManaged(runtime, task,
                    scenario == ScenarioType.COMPONENT_MAPPING_CONFLICT ? mappedGraph() : graph("application"), 1));
                return success();
            });
            run(runtime, task -> {
                second.set(publishManaged(runtime, task, graph("application"), 2));
                return success();
            });
            var actual = runtime.modContexts().get(OWNER).context();
            var peer = new Peer(scenario);
            var context = bind(actual, peer);
            var failures = new AtomicReference<Throwable>();
            var handle = actual.tasks()
                .submit(new TaskRequest("graph_acceptance", "Retained switch", List.of("server"), true), task -> {
                    try {
                        var service = new DeploymentRetainedReleases(context);
                        var app = new DeploymentApplicationRef("application", new ServerRef("server"));
                        var result = service.switchRelease(app, second.get().identity().sha256(),
                            first.get().identity().id(), task);
                        Outcome expected = scenario == ScenarioType.SNAPSHOT_WINDOW_DRIFT
                            ? Outcome.UNKNOWN
                            : scenario == ScenarioType.STOPPED_SWITCH ? Outcome.SUCCEEDED : Outcome.FAILED;
                        assertEquals(expected, result.outcome());
                        var repository = new DeploymentReleaseRepository(context.db());
                        UUID active = scenario == ScenarioType.STOPPED_SWITCH
                            ? first.get().identity().id()
                            : second.get().identity().id();
                        assertEquals(DeploymentReleaseRecord.DeploymentReleaseState.ACTIVE,
                            repository.find(task, "application", active).orElseThrow().state());
                        if (scenario == ScenarioType.HEALTH_ROLLBACK) {
                            assertEquals(List.of("web", "worker"), peer.rolledBack);
                            assertTrue(peer.current.values().stream().allMatch("b".repeat(64)::equals));
                            assertTrue(peer.snapshots.values().stream().noneMatch(Boolean::booleanValue));
                            assertEquals("FINISHED",
                                new DeploymentManagementJournal(context).find(task).orElseThrow().phase());
                        }
                        if (scenario == ScenarioType.CURRENT_DRIFT || scenario == ScenarioType.SNAPSHOT_WINDOW_DRIFT
                            || scenario == ScenarioType.COMPONENT_MAPPING_CONFLICT)
                            assertEquals(0, peer.snapshotsCreated.get(),
                                "Identity conflicts precede every snapshot effect");
                        int effects = peer.activations.get() + peer.snapshotsCreated.get() + peer.rolledBack.size();
                        var repeated = service.switchRelease(app, second.get().identity().sha256(),
                            first.get().identity().id(), task);
                        assertEquals(expected, repeated.outcome());
                        assertEquals(effects,
                            peer.activations.get() + peer.snapshotsCreated.get() + peer.rolledBack.size(),
                            "Original repeat never replays retained effects");
                        return new OperationResult<Void>(result.outcome(), Optional.empty(), result.problem());
                    } catch (Exception | AssertionError failure) {
                        failures.set(failure);
                        throw failure;
                    }
                });
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (handle.snapshot().state() != TaskState.ENDED && handle.snapshot().state() != TaskState.UNKNOWN
                && System.nanoTime() < end)
                Thread.sleep(20);
            if (failures.get() != null)
                throw new AssertionError("Retained fixture failed", failures.get());
            assertEquals(scenario == ScenarioType.SNAPSHOT_WINDOW_DRIFT ? TaskState.UNKNOWN : TaskState.ENDED,
                handle.snapshot().state());
            assertEquals(0, peer.sessions.get());
            if (interruptedHost) {
                var refusal = assertThrows(ApiException.class, runtime::close);
                assertEquals(ProblemCode.BUSY, refusal.problem().code());
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state(),
                    "Shutdown refusal cannot conclude the original transition");
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
            throw new IllegalArgumentException("Original retained child only");
        try {
            var scenario = ScenarioType.valueOf(args[2]);
            if (scenario != ScenarioType.SNAPSHOT_WINDOW_DRIFT)
                throw new IllegalArgumentException("Unknown retained scenario only");
            var probe = new DeploymentRetainedSwitchTest();
            probe.root = java.nio.file.Path.of(args[1]);
            probe.runScenario(scenario, true);
            System.out.println("MANAGED_APPLICATION_UNKNOWN_VERIFIED");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private DeploymentApplicationGraph mappedGraph() {
        var web = component("web", "server", List.of());
        var worker = component("worker", "server", List.of());
        return new DeploymentApplicationGraph("application", "web", new DeploymentHealthCheck.Process(20, 2),
            List.of(new DeploymentApplicationGraph.Component("worker", web.unit(), web.runtime(), List.of(),
                    web.reviewedRuntime(), web.dataPaths(), web.resources()),
                new DeploymentApplicationGraph.Component("web", worker.unit(), worker.runtime(), List.of(),
                    worker.reviewedRuntime(), worker.dataPaths(), worker.resources())));
    }

    private ModContext bind(ModContext actual, Peer peer) {
        LinuxService linux = new LinuxService() {
            public RemoteSession connect(ServerRef server, TaskContext task) throws ApiException {
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

        final AtomicInteger sessions = new AtomicInteger(), snapshotsCreated = new AtomicInteger(),
            activations = new AtomicInteger();

        final Map<String, String> current = new HashMap<>();

        final Map<String, Boolean> snapshots = new HashMap<>();

        final List<String> rolledBack = new ArrayList<>();

        Peer(ScenarioType scenario) {
            this.scenario = scenario;
            current.put("web", "b".repeat(64));
            current.put("worker", "b".repeat(64));
            if (scenario == ScenarioType.CURRENT_DRIFT)
                current.put("worker", "f".repeat(64));
        }

        RemoteSession session(TaskContext task) throws ApiException {
            var lease = task.retain("controlled_ssh", "retained-peer-" + UUID.randomUUID(),
                "Retained controlled session");
            sessions.incrementAndGet();
            return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                    if (method.getName().equals("facts"))
                        return new RemoteFacts("linux", "x86_64", "SHA256:retained-fixture");
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
            boolean stopped = scenario == ScenarioType.STOPPED_SWITCH;
            if (verb.equals("inspect-runtime")) {
                assertEquals(4, args.size());
                assertEquals("a".repeat(64), args.get(3));
                assertTrue(current.containsKey(unit));
                return reply(0, "KIND=deployment\nMODE=DAEMON\n");
            }
            if (verb.equals("managed-current-status"))
                return reply(0, "MANAGED_CURRENT=1\nKIND=deployment\nRELEASE_SHA256=" + current.get(unit) + "\n");
            if (verb.equals("observe-deployment"))
                return reply(0,
                    "OWNER=1\nRUNNING=" + (stopped ? "0" : "1") + "\nENABLED=enabled\nQUERY_OK=1\nActiveState="
                        + (stopped ? "inactive" : "active") + "\nSubState=" + (stopped ? "dead" : "running")
                        + "\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID="
                        + (stopped ? "0" : "123") + "\n");
            if (verb.equals("application-health"))
                return reply(
                    scenario == ScenarioType.HEALTH_ROLLBACK && current.get(unit).equals("a".repeat(64)) ? 1 : 0,
                    "HEALTHY=1\n");
            if (verb.equals("snapshot-retained")) {
                assertEquals(7, args.size());
                if (scenario == ScenarioType.SNAPSHOT_WINDOW_DRIFT)
                    current.put(unit, "f".repeat(64));
                if (!current.get(unit).equals(args.get(5)))
                    return reply(1, "");
                snapshots.put(unit, true);
                snapshotsCreated.incrementAndGet();
                return reply(0, "SNAPSHOT_TOKEN=" + args.get(4) + "\nPREVIOUS=1\nPREVIOUS_RUNNING="
                    + (stopped ? "0" : "1") + "\nPREVIOUS_DIGEST=" + args.get(5) + "\nPREVIOUS_KIND=deployment\n");
            }
            if (verb.equals("activate-retained")) {
                assertEquals(args.get(6), args.get(7));
                current.put(unit, args.get(4));
                activations.incrementAndGet();
                return reply(0, "SWITCHED=1\n");
            }
            if (verb.equals("rollback-retained")) {
                current.put(unit, args.get(3));
                snapshots.put(unit, false);
                rolledBack.add(unit);
                return reply(0, "ROLLED_BACK=1\n");
            }
            if (verb.equals("finish-retained")) {
                snapshots.put(unit, false);
                return reply(0, "FINISHED=1\n");
            }
            if (verb.equals("retained-status")) {
                String held = snapshots.getOrDefault(unit, false) ? "owned" : "absent";
                return reply(0,
                    "RETAINED_STATUS=1\nCURRENT=" + (current.get(unit).equals(args.get(3)) ? "source" : "target")
                        + "\nSNAPSHOT=" + held + "\nMARKER=" + held + "\n");
            }
            throw new AssertionError(request.script());
        }

        private OperationResult<CommandResult> reply(int exit, String text) {
            return new OperationResult<>(Outcome.SUCCEEDED,
                Optional.of(new CommandResult(Optional.of(exit), text, "", false)), Optional.empty());
        }
    }
}
