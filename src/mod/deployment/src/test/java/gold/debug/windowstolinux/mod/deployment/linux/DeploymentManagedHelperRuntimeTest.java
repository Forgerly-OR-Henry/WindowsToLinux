package gold.debug.windowstolinux.mod.deployment.linux;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.CommandResult;
import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.TransferProgress;
import gold.debug.windowstolinux.appapi.linux.TransferStall;
import gold.debug.windowstolinux.appapi.linux.TransferStallChoice;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.CancellationToken;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

class DeploymentManagedHelperRuntimeTest extends DeploymentRuntimeFixture {
    private final List<ActionIntent> reviewed = new CopyOnWriteArrayList<>();

    @Override
    protected PlatformRuntime open() throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), PROTECTION);
        if (runtime.security().securityState().configured())
            runtime.security().unlock(PASSWORD.toCharArray());
        else
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.setInteractionPresenter(new PlatformInteractionService.Prompts() {
            @Override
            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerRef server,
                    TaskSnapshot task, CancellationToken cancellation) {
                throw new AssertionError("No transfer expected");
            }

            @Override
            public TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskSnapshot task,
                    CancellationToken cancellation) {
                throw new AssertionError("No transfer expected");
            }

            @Override
            public String transferProgress(TransferProgress progress) {
                throw new AssertionError("No transfer expected");
            }

            @Override
            public boolean approve(ActionIntent intent, TaskSnapshot task, CancellationToken cancellation) {
                reviewed.add(intent);
                return true;
            }

            @Override
            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                    CancellationToken cancellation) {
                throw new AssertionError("No input expected");
            }
        });
        runtime.setSourcePicker((title, task, cancel) -> selectedSourceDirectory());
        runtime.started();
        return runtime;
    }

    @Test
    void actualClassTaskAndHostInteractionBindTheOriginalServerBeforeBorrowedProbe() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = new ServerRef("server-exact");
            runtime.servers().saveServerProfile(
                    ServerProfile.fromStored(
                            new StoredServerProfile(server.id(), "127.0.0.1", 22, "root", "fixture-password")),
                    "fixture-password".toCharArray());
            var calls = new AtomicInteger();
            RemoteSession borrowed = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                    new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                        if (method.getName().equals("execute")) {
                            calls.incrementAndGet();
                            return new OperationResult<>(Outcome.SUCCEEDED,
                                    Optional.of(new CommandResult(Optional.of(0),
                                            "HELPER=1\nPROTOCOL=10\nSHA256="
                                                    + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n",
                                            "", false)),
                                    Optional.empty());
                        }
                        throw new AssertionError("Borrowed session must not be closed or inspected");
                    });
            var context = runtime.modContexts().get(OWNER).context();
            var observed = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(
                    new TaskRequest("helper-probe-contract", "Probe packaged helper", List.of(server.id()), true),
                    task -> {
                        try {
                            var helper = new DeploymentManagedHelper(borrowed, context.interaction(), server, task,
                                    "reviewed-plan-v1");
                            var result = helper.probe();
                            var foreign = new DeploymentManagedHelper(borrowed, context.interaction(),
                                    new ServerRef("unadmitted-server"), task, "reviewed-plan-v1");
                            ApiException rejected = assertThrows(ApiException.class, foreign::probe);
                            assertEquals(ProblemCode.ACCESS_DENIED, rejected.problem().code());
                            return result;
                        } catch (Throwable failure) {
                            observed.set(failure);
                            throw failure;
                        }
                    });
            var result = handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertNull(observed.get(), () -> String.valueOf(observed.get()));
            assertEquals(Outcome.SUCCEEDED, result.outcome());
            assertEquals(1, calls.get());
            assertEquals(1, reviewed.size());
            assertEquals("deployment.helper.probe.v1", reviewed.getFirst().semantics().orElseThrow().operation());
            assertEquals(server.id(), reviewed.getFirst().serverIds().getFirst());
        }
    }
}
