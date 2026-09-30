package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

class DeploymentAgentReviewTest extends DeploymentRuntimeFixture {
    @Test
    void validIndependentDenialStopsWithoutAnotherReviewOrHumanOverride() throws Exception {
        var approvals = new AtomicInteger();
        var effects = new AtomicInteger();
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(),
                    new Class<?>[]{AiService.class}, (proxy, method, arguments) -> {
                        assertEquals("call", method.getName());
                        var request = (AiRequest) arguments[0];
                        assertEquals(AiPurpose.APPROVAL, request.purpose());
                        assertEquals("a".repeat(64), arguments[1]);
                        assertEquals(1, approvals.incrementAndGet(), "A valid denial must never shop for approval");
                        return new OperationResult<>(Outcome.SUCCEEDED,
                                Optional.of(new AiResponse(
                                        "{\"decision\":\"DENY\",\"risk\":\"HIGH\",\"reason\":\"Denied exact action\"}",
                                        "independent", "stop", new AiUsage(Optional.empty(), Optional.empty()))),
                                Optional.empty());
                    });
            var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                    new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("ai"))
                            return ai;
                        if (method.getName().equals("interaction") || method.getName().equals("linux")) {
                            effects.incrementAndGet();
                            throw new AssertionError("Denied action cannot dispatch a tool or ask for human override");
                        }
                        return method.invoke(actual, arguments);
                    });
            var handle = actual.tasks().submit(
                    new TaskRequest("independent_denial", "Independent denial", java.util.List.of(), false), task -> {
                        var reviewer = new DeploymentAgentReview(context, new ServerRef("server"), task, "FULL_CONTROL",
                                "a".repeat(64), () -> "a".repeat(64), new DeploymentAgentJournal(actual.db()));
                        var denied = assertThrows(SecurityException.class,
                                () -> reviewer.command("service", "b".repeat(64), "printf approved"));
                        assertEquals("Independent deployment review denied action", denied.getMessage());
                        return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                    });
            assertEquals(Outcome.SUCCEEDED,
                    handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(1, approvals.get());
            assertEquals(0, effects.get());
        }
    }
}
