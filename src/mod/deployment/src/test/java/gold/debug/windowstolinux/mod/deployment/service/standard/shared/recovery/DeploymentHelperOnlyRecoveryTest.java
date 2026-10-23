package gold.debug.windowstolinux.mod.deployment.service.standard.shared.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.linux.database.DeploymentNativeDatabaseWire;
import gold.debug.windowstolinux.mod.deployment.linux.helper.*;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.setup.DeploymentProtocolBootstrapActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStandardAssembly;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy.DeploymentPublishPolicyFacts;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the real original recovery and ledger with controlled read-only SSH receipts. */
class DeploymentHelperOnlyRecoveryTest extends DeploymentRuntimeFixture {
    private final AtomicBoolean refuse = new AtomicBoolean();
    private final AtomicInteger approvals = new AtomicInteger();
    private static final String FINGERPRINT = "SHA256:" + "a".repeat(43);

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return (PlatformInteractionService.Prompts) Proxy.newProxyInstance(PlatformInteractionService.Prompts.class.getClassLoader(),
            new Class<?>[]{PlatformInteractionService.Prompts.class}, (proxy, method, args) -> {
                assertEquals("approve", method.getName());
                var intent = (ActionIntent) args[0];
                assertTrue(Set.of("deployment.helper.probe.v1", "deployment.helper.install.status.v1")
                    .contains(intent.semantics().orElseThrow().operation()), "Recovery cannot approve an installation");
                approvals.incrementAndGet();
                return !refuse.get();
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "extra", "refused", "receipt", "digest", "host", "close"})
    void genuineHelperOnlyRecoveryPreservesFailuresAndReleasesOnlySettledOriginal(String scenario) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = new ServerId("helper-only-server");
            runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile(server.id(), "127.0.0.1", 22,
                "root", "fixture-password")), "fixture-password".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            runtime.tasks().revokeOwnerServices(OWNER, VERSION);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var original = new AtomicReference<TaskContext>();
            var extra = new AtomicReference<ResourceLease>();
            var closed = new AtomicInteger();
            var commands = new AtomicInteger();
            var allowClose = new AtomicBoolean(!scenario.equals("close"));
            var receiptGood = new AtomicBoolean(!scenario.equals("receipt"));
            var callbackFailure = new AtomicReference<Throwable>();
            var linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(), new Class<?>[]{LinuxService.class},
                (proxy, method, args) -> {
                    assertEquals("connect", method.getName());
                    return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class},
                        (session, operation, values) -> switch (operation.getName()) {
                            case "facts" -> new RemoteFacts("Linux", "x86_64", scenario.equals("host") ? "SHA256:" + "b".repeat(43) : FINGERPRINT);
                            case "close" -> {
                                if (!allowClose.get()) throw new ApiException(problem());
                                closed.incrementAndGet(); yield null;
                            }
                            case "execute" -> {
                                var request = (CommandRequest) values[0];
                                var input = new String(request.stdin(), StandardCharsets.UTF_8);
                                assertFalse(input.contains("prepare_stage=platform-packages"), "Never replay installation");
                                commands.incrementAndGet();
                                String output;
                                if (request.script().contains("--noprofile")) {
                                    var facts = DeploymentProtocolBootstrapActionLog.find(tasks, original.get()).orElseThrow().facts();
                                    output = DeploymentHelperInstallerScript.receipt(original.get().id(), facts.operationId(),
                                        facts.serverDigest(), facts.bodyDigest(), "SUCCEEDED");
                                    if (!receiptGood.get()) output = output.replace("TASK=" + original.get().id(), "TASK=" + UUID.randomUUID());
                                } else output = "HELPER=1\nPROTOCOL=16\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n";
                                yield new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), output, "", false)), Optional.empty());
                            }
                            default -> throw new AssertionError("Unexpected SSH operation " + operation.getName());
                        });
                });
            var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("tasks")) return tasks;
                    if (method.getName().equals("linux")) return linux;
                    try { return method.invoke(actual, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            var recovery = new DeploymentStandardTaskRecovery(context, new DeploymentRefFlow(context), new DeploymentStandardAssembly());
            var calls = new AtomicInteger();
            try (var registered = tasks.registerRecovery(DeploymentRefFlow.TASK_TYPE, recovery)) {
                var handle = tasks.submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE, "Original helper-only interruption", List.of(server.id()), true), task -> {
                    try {
                        original.set(task); calls.incrementAndGet();
                        var policy = DeploymentPublishPolicyFacts.begin(context, task, server, "STATIC", "MANUAL_REVIEW", true, "");
                        policy.authenticated(FINGERPRINT);
                        var operation = UUID.randomUUID();
                        var serverDigest = digest(server.id());
                        var bodyDigest = digest(DeploymentHelperInstallerScript.render());
                        var requestDigest = digest(DeploymentHelperInstallerScript.renderTracked(task.id(), operation, serverDigest, bodyDigest));
                        var journal = DeploymentProtocolBootstrapActionLog.begin(tasks, task, server, operation, serverDigest, bodyDigest,
                            scenario.equals("digest") ? "0".repeat(64) : requestDigest);
                        journal.dispatched();
                        if (scenario.equals("extra")) extra.set(task.retain("fixture", "unexplained", "Extra original unresolved effect"));
                    } catch (Throwable failure) { callbackFailure.set(failure); }
                    return unknown();
                });
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < until) Thread.sleep(10);
                if (callbackFailure.get() != null) throw new AssertionError("Original helper-only fixture failed", callbackFailure.get());
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                refuse.set(scenario.equals("refused"));
                var recovered = reconcileReady(runtime, handle, 30);
                if (!scenario.equals("success")) {
                    assertEquals(TaskState.UNKNOWN, recovered.state(), recovered.toString());
                    assertEquals(1, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + handle.id()
                        + "' AND resource_id='" + OWNER + "/checkpoint/" + DeploymentPublishPolicyFacts.KEY + "' AND released_at IS NULL"));
                    assertEquals(1, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + handle.id()
                        + "' AND resource_kind='SERVER' AND released_at IS NULL"));
                    if (scenario.equals("extra")) {
                        assertEquals(1, closed.get());
                        assertEquals(1, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + handle.id()
                            + "' AND resource_id='" + OWNER + "/checkpoint/" + DeploymentProtocolBootstrapActionLog.KEY + "' AND released_at IS NOT NULL"));
                    }
                } else {
                    assertEquals(TaskState.ENDED, recovered.state(), recovered.toString());
                    assertEquals(Optional.of(TaskResult.FAILED), recovered.outcome());
                    assertEquals(1, closed.get());
                    assertEquals(2, commands.get());
                    assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + handle.id() + "' AND released_at IS NULL"));
                }
                assertEquals(1, calls.get(), "Recovery never replays the preparation callback");
                if (Set.of("refused", "digest", "host").contains(scenario)) assertEquals(0, commands.get());
                if (!Set.of("digest", "host").contains(scenario)) assertTrue(approvals.get() > 0, "Original recovery requires fresh command review");
                // Teardown retained local fixture leases only after the production UNKNOWN assertions above.
                if (!scenario.equals("success")) {
                    registered.close();
                    try (var teardown = tasks.registerRecovery(DeploymentRefFlow.TASK_TYPE, (p, t) -> {
                        if (extra.get() != null) extra.get().close();
                        tasks.findCheckpoint(t, DeploymentProtocolBootstrapActionLog.KEY).orElseThrow().close();
                        tasks.findCheckpoint(t, DeploymentPublishPolicyFacts.KEY).orElseThrow().close();
                        return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(problem()));
                    })) {
                        var ended = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(30, TimeUnit.SECONDS);
                        assertEquals(TaskState.ENDED, ended.state(), ended.toString());
                        assertEquals(Optional.of(TaskResult.FAILED), ended.outcome());
                    }
                }
            }
        }
    }

    private static String digest(String value) { return DeploymentNativeDatabaseWire.digest(value.getBytes(StandardCharsets.UTF_8)); }
    private static Problem problem() { return new Problem(ProblemCode.UNKNOWN, "TEST_HELPER_ONLY_INTERRUPTED", "Controlled original interruption", Map.of(), Optional.empty()); }
    private static OperationResult<Void> unknown() { return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(problem())); }
}
