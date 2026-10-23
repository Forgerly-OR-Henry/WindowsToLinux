package gold.debug.windowstolinux.mod.deployment.service.standard.staticmode;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs.Answers;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs.Reviewer;
import org.junit.jupiter.api.Test;

class DeploymentStaticModeSupportTest {
    @Test
    void staticCaptureAndRecoveryDoNotAcquireModelCapabilities() throws Exception {
        var context = unused(ModContext.class);
        var task = unused(TaskContext.class);
        var support = DeploymentStaticWorkflow.support();
        var scope = support.freeze(context, task, "MANUAL_REVIEW", true);
        assertFalse(support.assisted());
        assertSame(context, scope.context());
        assertEquals("", scope.revision());
        assertSame(context, support.recover(context, task, "", "MANUAL_REVIEW", true));
    }

    @Test
    void staticReviewLeavesIncompleteAnswerClassificationToTheSharedWorkflow() throws Exception {
        var answer = new Answers(Map.of(), List.of(), gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().limits(), false, false);
        var original = new OperationResult<Map<String, Answers>>(TaskResult.SUCCEEDED,
            Optional.of(Map.of("unexpected-component", answer)), Optional.empty());
        var reviewer = (Reviewer) Proxy.newProxyInstance(Reviewer.class.getClassLoader(), new Class<?>[]{Reviewer.class},
            (receiver, method, args) -> {
                assertEquals("review", method.getName());
                return original;
            });
        var session = DeploymentStaticWorkflow.support().session(unused(ModContext.class), (prompt, value) -> {
            fail("The shared workflow owns deterministic answer classification");
        });
        assertSame(original, session.review(new ServerId("test-server"), "source-revision", List.of(), reviewer,
            unused(TaskContext.class)));
    }

    private static <T> T unused(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (receiver, method, args) -> {
            throw new AssertionError("Static mode must not acquire capability: " + method.getName());
        }));
    }
}
