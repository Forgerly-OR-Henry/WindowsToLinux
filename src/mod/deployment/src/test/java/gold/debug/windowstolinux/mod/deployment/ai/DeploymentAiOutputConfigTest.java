package gold.debug.windowstolinux.mod.deployment.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import org.junit.jupiter.api.Test;

class DeploymentAiOutputConfigTest {
    @Test
    void analysisAndApprovalUseTheirOwnConfiguredOutputRequests() throws Exception {
        var fixture = new NativeAiFixture();
        var ai = new DeploymentAiJson(fixture.context, new DeploymentConfig.Ai(4096, 12288, 2048));
        for (var purpose : List.of(AiPurpose.LANGUAGE, AiPurpose.APPROVAL)) {
            fixture.nativeReply(new AiToolCall(purpose.name(), "submit_result", "{}"));
            var response = fixture.replies.removeLast().value().orElseThrow();
            fixture.replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("{}",
                response.modelId(), response.finishReason(), response.usage(), response.toolCalls(), response.continuation())), Optional.empty()));
            assertTrue(ai.ask(purpose, "Return the complete structured result", Map.of(), "fixture-route", fixture.task).isObject());
        }
        assertEquals(List.of(Optional.of(4096), Optional.of(2048)), fixture.requests.stream().map(AiRequest::maxOutputTokens).toList());
        assertEquals(2, fixture.requests.size());
    }
}
