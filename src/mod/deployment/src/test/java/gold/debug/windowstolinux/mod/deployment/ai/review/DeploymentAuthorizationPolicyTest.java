package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeploymentAuthorizationPolicyTest {
    @ParameterizedTest
    @CsvSource({"MANUAL_REVIEW,false", "MANUAL_REVIEW,true", "AUTOMATIC,false", "AUTOMATIC,true", "FULL_CONTROL,false", "FULL_CONTROL,true"})
    void hostResultAndRequestRemainExactUnderEveryCapturedPolicy(String policy, boolean remember) throws Exception {
        var fixture = new NativeAiFixture();
        var intent = new ActionIntent("platform.ssh.execute", List.of("original"), "original", "revision", "exact", "effect");
        try (var request = new CommandRequest("printf original", new byte[]{1, 2}, Duration.ofSeconds(30), 8192)) {
            var command = Optional.of(request);
            for (var outcome : TaskResult.values()) {
                var result = new OperationResult<ApprovalGrant>(outcome,
                    outcome == TaskResult.SUCCEEDED ? Optional.of(new ApprovalGrant(UUID.randomUUID())) : Optional.empty(),
                    outcome == TaskResult.FAILED || outcome == TaskResult.UNKNOWN
                        ? Optional.of(new Problem(ProblemCode.UNKNOWN, "ORIGINAL_HOST_RESULT", "original", Map.of(), Optional.empty()))
                        : Optional.empty());
                var calls = new AtomicInteger();
                var host = (InteractionService) Proxy.newProxyInstance(InteractionService.class.getClassLoader(),
                    new Class<?>[]{InteractionService.class}, (p, method, args) -> {
                        assertEquals("approveReviewedAction", method.getName());
                        assertSame(intent, args[0]);
                        assertSame(command, args[1]);
                        assertEquals(true, args[2]);
                        assertEquals("original review", args[3]);
                        assertSame(fixture.task, args[4]);
                        calls.incrementAndGet();
                        return result;
                    });
                assertSame(result, DeploymentAuthorizationPolicy.authorize(host, intent, command, fixture.task,
                    policy, remember, new DeploymentReviewDecision("APPROVE", true, "original review")));
                assertEquals(1, calls.get());
            }
        }
    }
}
