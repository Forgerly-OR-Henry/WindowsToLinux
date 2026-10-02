package gold.debug.windowstolinux.mod.deployment.linux;

import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.LinuxDistroType;
import org.junit.jupiter.api.Test;

/** Exercises the public actual collection channel with a CLASS host task and strict read-only SSH boundary. / 以CLASS宿主原任务及严格只读SSH边界验证公开实际收集通道。 */
class DeploymentCapabilityCollectionTest extends DeploymentRuntimeFixture {
    private final AtomicInteger approvals = new AtomicInteger();

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerRef server,
                                                           TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError("No transfer during collection");
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskSnapshot task,
                                                       CancelToken cancellation) {
                throw new AssertionError("No transfer during collection");
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError("No transfer during collection");
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken cancellation) {
                throw new AssertionError("No additional input during collection");
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
                assertEquals("deployment.candidate.host-probe.v1", intent.semantics().orElseThrow().operation());
                assertEquals(List.of("server"), intent.serverIds());
                assertEquals("a".repeat(64), intent.revision());
                approvals.incrementAndGet();
                return true;
            }
        };
    }

    @Test
    void originalClassTaskCollectsExactCapabilitiesOnceAndClosesWithoutAnyMutation() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var connects = new AtomicInteger();
            var reads = new AtomicInteger();
            var closes = new AtomicInteger();
            var originalTask = new AtomicReference<UUID>();
            LinuxService remote = new LinuxService() {
                public RemoteSession connect(ServerRef server, TaskContext task) throws ApiException {
                    assertEquals(new ServerRef("server"), server);
                    assertEquals(originalTask.get(), task.id());
                    connects.incrementAndGet();
                    var lease = task.retain("controlled_ssh", "capability-read-" + task.id(),
                        "Original read-only capability collection");
                    return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                        new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "facts":
                                    return new RemoteFacts("linux", "x86_64", "SHA256:readonly-capability");
                                case "close":
                                    assertEquals(1, closes.incrementAndGet());
                                    lease.close();
                                    return null;
                                case "execute":
                                    assertEquals(originalTask.get(), task.id());
                                    assertNotNull(args[1]);
                                    assertEquals(1, reads.incrementAndGet());
                                    var request = (CommandRequest) args[0];
                                    assertEquals(DeploymentPlatformCapabilityProbe.render(), request.script());
                                    assertArrayEquals(new byte[0], request.stdin());
                                    assertEquals(Duration.ofSeconds(120), request.timeout());
                                    assertEquals(65536, request.outputLimitBytes());
                                    return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(new CommandResult(
                                        Optional.of(0),
                                        "DISTRO_ID=ubuntu\nVERSION=24.04\nARCH=x86_64\nPACKAGE_MANAGER=apt\nPACKAGE_ARCH=amd64\nSYSTEMD=1\nCPU_LEVEL=x86-64-v3\nSECURITY_MODULE=apparmor\nSECURITY_STATE=enabled\nFIREWALL=ufw\nFIREWALL_STATE=active\n",
                                        "", false)), Optional.empty());
                                default:
                                    throw new AssertionError(
                                        "Capability collection attempted a non-read-only SSH operation: "
                                            + method.getName());
                            }
                        });
                }

                public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                    throw new AssertionError("Collection requires no transfer workflow");
                }
            };
            var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("linux"))
                        return remote;
                    try {
                        return method.invoke(context, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var handle = bound.tasks().submit(
                new TaskRequest("capability_read", "Collect original capability evidence", List.of("server"), true),
                task -> {
                    assertTrue(originalTask.compareAndSet(null, task.id()));
                    try (var session = bound.linux().connect(new ServerRef("server"), task)) {
                        return new DeploymentCandidateChannel(session, bound.interaction(), new ServerRef("server"),
                            task, "a".repeat(64), new DeploymentBuildBudget(120)).hostFacts();
                    }
                });
            OperationResult<DeploymentLinuxCapabilityFacts> result = handle.completion().toCompletableFuture().get(20,
                TimeUnit.SECONDS);
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(handle.id(), originalTask.get());
            assertEquals(1, connects.get());
            assertEquals(1, reads.get());
            assertEquals(1, closes.get());
            assertEquals(1, approvals.get());
            var facts = result.value().orElseThrow();
            assertEquals(LinuxDistroType.UBUNTU, facts.distro());
            assertEquals("24.04", facts.version());
            assertEquals("x86_64", facts.architecture());
            assertEquals("apt", facts.packageManager());
            assertEquals("amd64", facts.packageArchitecture());
            assertTrue(facts.systemdAvailable());
            assertTrue(facts.evidence().contains("SSH host fingerprint verified: SHA256:readonly-capability"));
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            try (var access = scope(runtime, true);
                 var connection = access.openConnection();
                 var statement = connection.createStatement()) {
                for (String table : List.of("deployment_execution", "managed_application", "deployment_unit",
                    "deployment_release")) {
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1));
                    }
                }
            }
        }
    }
}
