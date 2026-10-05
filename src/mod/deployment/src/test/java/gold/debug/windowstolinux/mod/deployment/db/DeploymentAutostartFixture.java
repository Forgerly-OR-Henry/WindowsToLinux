package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentManagementActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Actual task leases, original H2 journal and controlled fixed helper protocol. / 实际任务租约、原H2日志与受控固定helper协议。 */
abstract class DeploymentAutostartFixture extends DeploymentUsageFixture {
    enum Backend {NATIVE, DOCKER, PODMAN}

    DeploymentApplicationGraph lifecycleGraph(Backend backend, boolean mixed) {
        var original = usageGraph("demo", false, true);
        var parts = new ArrayList<DeploymentApplicationGraph.Part>();
        for (var part : original.components()) {
            boolean job = part.componentId().equals("job");
            if (job && !mixed)
                continue;
            var old = part.runtime().workload();
            var workload = new DeploymentWorkload(old.mode(), true, old.command(), old.workingDirectory(),
                List.of(), old.verification(), old.expectedOutput(), old.client(), old.inputs(), old.companions(),
                old.buildDirectory(), List.of());
            DeploymentRuntimeDefinition definition = backend == Backend.NATIVE ? part.reviewedRuntime().withWorkload(workload)
                : new DeploymentRuntimeDefinition.Container(DeploymentRuntimeDefinition.ContainerEngineType.valueOf(backend.name()),
                Map.of(), List.of(), part.runtime().healthCheck(), DeploymentIdentityMode.CONTAINER_NON_ROOT, workload);
            var runtime = new DeploymentUnitRuntimeConfig(part.runtime().healthCheck(), Optional.empty(), definition.identityPolicy(), workload);
            parts.add(new DeploymentApplicationGraph.Part(part.componentId(), part.unit(), runtime, part.dependencies(),
                definition, part.dataPaths(), part.resources()));
        }
        return new DeploymentApplicationGraph("demo", "service", original.healthCheck(), parts);
    }

    DeploymentApplicationGraph withoutReview(DeploymentApplicationGraph original) {
        var parts = new ArrayList<DeploymentApplicationGraph.Part>();
        for (var part : original.components()) {
            var old = part.runtime().workload();
            var workload = new DeploymentWorkload(old.mode(), false, old.command(), old.workingDirectory(),
                old.endpoints(), old.verification(), old.expectedOutput(), old.client(), old.inputs(), old.companions(),
                old.buildDirectory(), old.workers());
            var definition = part.reviewedRuntime().withWorkload(workload);
            var runtime = new DeploymentUnitRuntimeConfig(part.runtime().healthCheck(), Optional.empty(), definition.identityPolicy(), workload);
            parts.add(new DeploymentApplicationGraph.Part(part.componentId(), part.unit(), runtime, part.dependencies(),
                definition, part.dataPaths(), part.resources()));
        }
        return new DeploymentApplicationGraph(original.applicationId(), original.healthComponentId(), original.healthCheck(), parts);
    }

    ModContext bind(ModContext actual, ManagedPeer controlled) {
        LinuxService linux = new LinuxService() {
            public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                assertEquals(new ServerId("server"), server);
                return controlled.session(task);
            }

            public RemoteSession connect(RemoteTarget expected, TaskContext task) throws ApiException {
                var current = actual.servers().get(expected.server(), task);
                if (!expected.taskId().equals(task.id()) || !expected.host().equals(current.host())
                    || expected.port() != current.port() || !expected.username().equals(current.username())
                    || expected.fingerprint().filter(value -> !value.equals(controlled.fingerprint)).isPresent())
                    throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "CONTROLLED_FIXED_TARGET_CHANGED",
                        "Original target refused before authentication", Map.of(), Optional.empty()));
                return controlled.session(task);
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

    static final class ManagedPeer {
        final ModContext actual;

        final Map<String, DeploymentRuntimeDefinition> definitions = new HashMap<>();

        final Map<String, Boolean> running = new HashMap<>(), enabled = new HashMap<>();

        final AtomicInteger effects = new AtomicInteger(), connects = new AtomicInteger(), sessions = new AtomicInteger();

        final AtomicInteger maintenance = new AtomicInteger();

        final AtomicReference<UUID> operation = new AtomicReference<>();

        int loseAck;

        volatile String fingerprint = "SHA256:" + "A".repeat(43);

        ManagedPeer(ModContext actual, DeploymentApplicationGraph graph, boolean active, boolean autostart) {
            this.actual = actual;
            for (var part : graph.components()) {
                definitions.put(part.unit().id(), part.reviewedRuntime());
                running.put(part.unit().id(), active);
                enabled.put(part.unit().id(), autostart);
            }
        }

        RemoteSession session(TaskContext task) throws ApiException {
            var lease = task.retain("controlled_ssh", "autostart-peer-" + UUID.randomUUID(), "Controlled managed helper session");
            connects.incrementAndGet();
            sessions.incrementAndGet();
            return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("facts"))
                        return new RemoteFacts("linux", "x86_64", fingerprint);
                    if (method.getName().equals("close")) {
                        lease.close();
                        sessions.decrementAndGet();
                        return null;
                    }
                    if (method.getName().equals("execute")) {
                        assertNotNull(args[1]);
                        return command((CommandRequest) args[0], task);
                    }
                    throw new AssertionError(method.getName());
                });
        }

        OperationResult<CommandResult> command(CommandRequest request, TaskContext task) throws Exception {
            if (request.script().contains("/usr/bin/sha256sum --check --status"))
                return reply("HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            if (request.script().contains("inspect --format")) {
                String unit = definitions.keySet().stream().filter(value -> request.script().contains("windowstolinux-" + value)).findFirst().orElseThrow();
                var container = (DeploymentRuntimeDefinition.Container) definitions.get(unit);
                String engine = container.engine().name().toLowerCase(Locale.ROOT);
                assertTrue(request.script().contains(engine + " inspect"));
                if (container.engine() == DeploymentRuntimeDefinition.ContainerEngineType.PODMAN)
                    assertTrue(request.script().contains("test ! -L"), "Podman observation must use the actual helper's regular-file contract");
                return reply("OWNER=1\nRUNNING=" + running.get(unit) + "\nENABLED=" + (enabled.get(unit) ? engine.equals("docker") ? "unless-stopped" : "enabled" : "no") + "\n");
            }
            var args = new ArrayList<String>();
            var matcher = Pattern.compile("'([^']*)'").matcher(request.script());
            while (matcher.find()) args.add(matcher.group(1));
            assertEquals(DeploymentManagedHelperBundle.PATH, args.getFirst());
            String verb = args.get(1), unit = args.get(2);
            if (verb.equals("application-maintenance")) {
                maintenance.incrementAndGet();
                throw new AssertionError("Rejected unreviewed collection cannot dispatch maintenance markers");
            }
            var runtime = definitions.get(unit);
            assertNotNull(runtime);
            boolean container = runtime instanceof DeploymentRuntimeDefinition.Container;
            String kind = container ? "container" : "deployment";
            if (verb.equals("managed-current-status")) {
                assertEquals(List.of(DeploymentManagedHelperBundle.PATH, verb, unit, "a".repeat(64), kind), args);
                return reply("MANAGED_CURRENT=1\nKIND=" + kind + "\nRELEASE_SHA256=" + "a".repeat(64) + "\n");
            }
            if (verb.equals("inspect-runtime")) {
                assertEquals(4, args.size());
                assertEquals("a".repeat(64), args.get(3));
                return reply("KIND=" + kind + "\n" + (container ? "ENGINE=" + ((DeploymentRuntimeDefinition.Container) runtime).engine().name().toLowerCase(Locale.ROOT) + "\n" : "")
                    + "MODE=" + runtime.workload().mode() + "\n");
            }
            if (verb.equals("application-health")) return reply("HEALTHY=1\n");
            if (verb.equals("observe-deployment")) {
                assertFalse(container);
                if (runtime.workload().mode() == DeploymentWorkload.ExecutionMode.ON_DEMAND)
                    return reply("OWNER=1\nINSTALLED=1\nRUNNING=0\nENABLED=not-applicable\nQUERY_OK=1\nActiveState=inactive\nMainPID=0\n");
                boolean active = running.get(unit);
                return reply("OWNER=1\nRUNNING=" + (active ? "1" : "0") + "\nENABLED=" + (enabled.get(unit) ? "enabled" : "disabled") + "\nQUERY_OK=1\nActiveState=" + (active ? "active" : "inactive")
                    + "\nSubState=" + (active ? "running" : "dead") + "\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID=" + (active ? "123" : "0") + "\n");
            }
            assertEquals("lifecycle-" + kind, verb);
            assertEquals(6, args.size());
            assertEquals("a".repeat(64), args.get(4));
            assertEquals("a".repeat(64), args.get(5));
            assertTrue(runtime.workload().supportsLifecycle(), "An installed job must never receive a daemon command");
            var entry = new DeploymentManagementActionLog(actual).find(task).orElseThrow();
            assertEquals(task.id(), entry.taskId());
            assertEquals("EFFECTING", entry.phase());
            assertEquals(UUID.nameUUIDFromBytes(("deployment-management-v1\n" + task.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)), entry.id());
            if (operation.get() == null) operation.set(entry.id());
            assertEquals(operation.get(), entry.id());
            var part = entry.facts().path("components").path(unit.substring("demo-".length()));
            assertTrue(part.path("attempted").asBoolean());
            assertFalse(part.path("verified").asBoolean());
            String action = args.get(3);
            assertEquals(action, part.path("verb").asText());
            if (action.equals("enable") || action.equals("disable")) enabled.put(unit, action.equals("enable"));
            else running.put(unit, !action.equals("stop"));
            if (effects.incrementAndGet() == loseAck)
                return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                    "CONTROLLED_AUTOSTART_ACK_LOST", "Original acknowledgement unavailable", Map.of(), Optional.empty())));
            return reply("LIFECYCLE=" + action + "\n");
        }

        private OperationResult<CommandResult> reply(String text) {
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), text, "", false)), Optional.empty());
        }
    }
}
