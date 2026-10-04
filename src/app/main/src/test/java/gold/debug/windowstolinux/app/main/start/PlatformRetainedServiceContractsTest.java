package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformAiServiceTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformServerRefTest.completed;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Migrates inventory versus purpose routing without restoring a legacy deployment facade. / 迁移库存与用途路由的区别，不恢复旧部署门面。 */
class PlatformRetainedServiceContractsTest {
    @TempDir
    Path root;

    @Test
    void displayInventoryReorderingKeepsPurposeOrderButPurposeReplacementRejectsTheOriginalRevision() throws Exception {
        try (var http = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, http, "a", "/a", AiCapabilityType.TEXT, 128);
            model(runtime, http, "b", "/b", AiCapabilityType.TEXT, 128);
            route(runtime, AiPurposeType.DEPLOYMENT, "b", "a");
            runtime.persistence().aiProfiles().reorder(List.of("b", "a"));
            var ai = runtime.capabilities().ai(OWNER, VERSION);
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                String original = ai.configurationRevision(AiPurpose.LANGUAGE, task);
                runtime.persistence().aiProfiles().reorder(List.of("a", "b"));
                assertEquals(List.of("a", "b"), runtime.persistence().aiProfiles().listConfigured().stream()
                    .map(value -> value.profile().id()).toList());
                assertEquals(List.of(new AiPurposeAssignment("b", true), new AiPurposeAssignment("a", true)),
                    runtime.persistence().aiProfiles().purposes().list(AiPurposeType.DEPLOYMENT));
                assertEquals(original, ai.configurationRevision(AiPurpose.LANGUAGE, task));
                assertEquals("b", ai.call(message(AiPurpose.LANGUAGE), original, task).value().orElseThrow().modelId());
                assertEquals(1, http.calls.get());

                route(runtime, AiPurposeType.DEPLOYMENT, "a");
                assertEquals("AI_CONFIGURATION_CHANGED",
                    assertThrows(ApiException.class, () -> ai.call(message(AiPurpose.LANGUAGE), original, task))
                        .problem().eventCode());
                assertEquals(1, http.calls.get(), "The original frozen revision must not send after replacement");
                String replacement = ai.configurationRevision(AiPurpose.LANGUAGE, task);
                assertNotEquals(original, replacement);
                assertEquals("a",
                    ai.call(message(AiPurpose.LANGUAGE), replacement, task).value().orElseThrow().modelId());
                return success(null);
            }));
            assertEquals(List.of("/b", "/a"), List.of(http.requests.take().path(), http.requests.take().path()));
            assertEquals(2, http.calls.get());
            assertTrue(http.requests.isEmpty());
            assertEquals(0, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }
}
