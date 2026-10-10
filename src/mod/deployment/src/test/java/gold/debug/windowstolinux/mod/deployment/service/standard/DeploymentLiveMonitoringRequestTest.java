package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.app.config.AppConfig;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import org.junit.jupiter.api.Test;

class DeploymentLiveMonitoringRequestTest {
    @Test
    void realMonitoringWrapperPreservesNamedAndAutomaticToolSelection() throws Exception {
        var live = new DeploymentStaticLiveAcceptanceTest();
        for (String name : List.of("aiEnabled", "agentMode")) {
            var field = DeploymentStaticLiveAcceptanceTest.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(live, true);
        }
        var fixture = new NativeAiFixture();
        var monitored = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("monitored", ModContext.class, AppConfig.Ai.class);
        monitored.setAccessible(true);
        var context = (ModContext) monitored.invoke(live, fixture.context, AppConfig.defaultsConfig().ai());
        var tool = new AiToolDefinition("submit_result", "Submit verdict", "{\"type\":\"object\"}");
        var original = new AiRequest(AiPurpose.APPROVAL,
            List.of(new AiMessage(AiRole.SYSTEM, "Complete unchanged policy", List.of()),
                new AiMessage(AiRole.USER, "{\"operation\":\"current-review\"}", List.of())),
            Optional.of(8192), Duration.ofMinutes(10), false, List.of(tool), 1048576);
        for (var request : List.of(original.withRequiredTool("submit_result"), original)) {
            fixture.nativeReply(new AiToolCall("current-" + fixture.requests.size(), "submit_result", "{}"));
            context.ai().call(request, fixture.task);
            assertEquals(request, fixture.requests.getLast(), "Monitoring must preserve the complete request, including native tool choice");
        }
        assertEquals(2, fixture.requests.size());
        assertTrue(fixture.replies.isEmpty());
    }
}
