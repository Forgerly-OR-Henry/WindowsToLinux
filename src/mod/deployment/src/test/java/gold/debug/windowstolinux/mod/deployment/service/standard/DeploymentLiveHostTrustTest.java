package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentLiveHostTrustTest extends DeploymentRuntimeFixture {
    private final DeploymentStaticLiveAcceptanceTest live = new DeploymentStaticLiveAcceptanceTest();

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return live.interactionPrompts();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHA256:different", "SHA256:expected-extra", "SHA256:expected"})
    void hostTrustDecisionCompletesWithoutDispatchingRemoteWork(String observed) throws Exception {
        String previous = System.getProperty("managed.ssh.fingerprint");
        System.setProperty("managed.ssh.fingerprint", "SHA256:expected");
        try {
            enable();
            try (var runtime = open()) {
                start(runtime, true);
                runtime.servers().saveServerProfile(new ServerProfile("static-live", "host.invalid", 22,
                    "fixture", "fixture-secret"), "fixture-secret".toCharArray());
                var context = runtime.modContexts().get(OWNER).context();
                var intent = new ActionIntent("platform.ssh.trust", List.of("static-live"), "static-live", "first-use",
                    "host=host.invalid\nport=22\nusername=fixture\nSSH wire-key fingerprint=" + observed,
                    "Local trust regression; no SSH connection is created");
                var authorizedActions = new AtomicInteger();
                var task = context.tasks().<Void>submit(new TaskRequest("host-trust-regression", "Local trust decision",
                    List.of("static-live"), false), active -> {
                    var decision = context.interaction().approve(intent, active);
                    if (decision.value().isPresent()) authorizedActions.incrementAndGet();
                    return new OperationResult<>(decision.outcome(), Optional.empty(), decision.problem());
                });
                try {
                    var result = task.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    boolean matches = observed.equals("SHA256:expected");
                    assertEquals(matches ? TaskResult.SUCCEEDED : TaskResult.CANCELLED, result.outcome());
                    assertEquals(matches ? 1 : 0, authorizedActions.get());
                    assertEquals(TaskState.ENDED, task.snapshot().state());
                    assertTrue(task.snapshot().blockers().isEmpty());
                    assertEquals(0, runtime.security().securityState().activeTasks());
                    assertTrue(runtime.persistence().servers().findServer("static-live").isEmpty());
                } finally {
                    if (!task.completion().toCompletableFuture().isDone()) task.requestCancel();
                }
            }
        } finally {
            if (previous == null) System.clearProperty("managed.ssh.fingerprint");
            else System.setProperty("managed.ssh.fingerprint", previous);
        }
    }
}
