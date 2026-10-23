package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStandardAssembly;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy.DeploymentPublishPolicyContext;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy.DeploymentPublishPolicyFacts;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Uses genuine original-task recovery to verify a separately retained helper installer without creating a checkpoint or replaying business. / 经真实原任务恢复验证独立留痕helper安装，不新建检查点或重放业务。 */
class DeploymentHelperRepairPolicyTest extends DeploymentRuntimeFixture {
    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return (PlatformInteractionService.Prompts) Proxy.newProxyInstance(PlatformInteractionService.Prompts.class.getClassLoader(),
            new Class<?>[]{PlatformInteractionService.Prompts.class}, (proxy, method, args) -> {
                assertEquals("approve", method.getName(), "Only reviewed helper commands are expected");
                var intent = (ActionIntent) args[0];
                assertEquals(List.of("helper-repair-server"), intent.serverIds());
                assertTrue(Set.of("deployment.helper.probe.v1", "deployment.helper.install.tracked.v1",
                    "deployment.helper.install.status.v1").contains(intent.semantics().orElseThrow().operation()), intent::toString);
                return true;
            });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void originalRecoveryRepairsOnceAndRetainsLostInstallerReceipt(boolean loseReply) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = new ServerId("helper-repair-server");
            runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile(server.id(), "127.0.0.1", 22,
                "root", "fixture-password")), "fixture-password".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var installs = new AtomicInteger();
            var current = new AtomicBoolean();
            var receiptReady = new AtomicBoolean(!loseReply);
            var failure = new AtomicReference<Throwable>();
            var type = "helper-repair-policy-test";
            try (var registration = context.tasks().registerRecovery(type, (previous, task) -> {
                var policy = DeploymentPublishPolicyFacts.find(context, task).orElseThrow();
                var original = policy.facts();
                var borrowed = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close")) return null;
                        assertEquals("execute", method.getName());
                        var request = (CommandRequest) args[0];
                        if (!request.script().contains("--noprofile"))
                            return exit(current.get() ? 0 : 1, current.get()
                                ? "HELPER=1\nPROTOCOL=16\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n" : "");
                        var input = new String(request.stdin(), StandardCharsets.UTF_8);
                        if (input.contains("prepare_stage=platform-packages")) {
                            assertEquals("DISPATCHED", policy.repair().orElseThrow().facts().phase());
                            installs.incrementAndGet();
                            current.set(true);
                            return loseReply ? new OperationResult<CommandResult>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(problem()))
                                : exit(0, "");
                        }
                        var repair = policy.repair().orElseThrow().facts();
                        return receiptReady.get() ? exit(0, DeploymentHelperInstallerScript.receipt(task.id(), repair.operationId(),
                            repair.serverDigest(), repair.bodyDigest(), "SUCCEEDED")) : exit(3, "");
                    });
                var guarded = DeploymentPublishPolicyContext.recover(context, task, new DeploymentStandardAssembly());
                try {
                    guarded.ensureRecoveryHelper(borrowed);
                    assertEquals(original, policy.facts(), "Repair must preserve every original target, model and approval field");
                    assertEquals("SUCCEEDED", policy.repair().orElseThrow().facts().phase());
                    guarded.finish(TaskResult.SUCCEEDED);
                    assertTrue(context.tasks().resourcesReleased(task));
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (ApiException unknown) {
                    assertTrue(loseReply);
                    assertEquals("DISPATCHED", policy.repair().orElseThrow().facts().phase());
                    assertFalse(context.tasks().resourcesReleased(task));
                    assertThrows(IllegalArgumentException.class, policy::close);
                    return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(unknown.problem()));
                } catch (Throwable unexpected) {
                    failure.set(unexpected);
                    return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(problem()));
                }
            })) {
                var handle = context.tasks().submit(new TaskRequest(type, "Original helper repair", List.of(server.id()), true), task -> {
                    var policy = DeploymentPublishPolicyFacts.begin(context, task, server, "STATIC", "MANUAL_REVIEW", true, "");
                    policy.authenticated("SHA256:" + "a".repeat(43));
                    return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(problem()));
                });
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < until) Thread.sleep(10);
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                var recovered = reconcileReady(runtime, handle, 30);
                if (loseReply) {
                    assertEquals(TaskState.UNKNOWN, recovered.state());
                    recovered = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(30, TimeUnit.SECONDS);
                    assertEquals(TaskState.UNKNOWN, recovered.state());
                    assertEquals(1, installs.get(), "Unconfirmed installation must only be observed on recovery");
                    receiptReady.set(true);
                    recovered = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(30, TimeUnit.SECONDS);
                }
                if (failure.get() != null) throw new AssertionError("Controlled helper repair failed", failure.get());
                assertEquals(TaskState.ENDED, recovered.state(), recovered::toString);
                assertEquals(TaskResult.SUCCEEDED, recovered.outcome().orElseThrow());
                assertEquals(1, installs.get());
            }
        }
    }

    private static OperationResult<CommandResult> exit(int code, String output) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(code), output, "", false)), Optional.empty());
    }

    private static Problem problem() {
        return new Problem(ProblemCode.UNKNOWN, "TEST_HELPER_REPAIR_UNKNOWN", "Controlled original repair", Map.of(), Optional.empty());
    }
}
