package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformServerRefTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.VoiceServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceOptions;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceModelSelectionTest {
    @TempDir
    Path root;

    @Test
    void directFileAndRealtimeModelsWorkWithoutVoiceRecommendations() throws Exception {
        try (var endpoint = new Endpoint(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, "file", endpoint.http(), "", AiCapabilityType.VOICE_FILE);
            model(runtime, "live", "", endpoint.ws(), AiCapabilityType.VOICE_REALTIME);
            var ai = runtime.capabilities().ai(OWNER, VERSION);
            assertEquals("file", completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> ai.call(audio().withModel("file"), task))).modelId());
            assertEquals(1, endpoint.httpCalls.get());
            var result = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                try (var session = ai.openVoice(new VoiceOptions(Duration.ofSeconds(5), 65536).withModel("live"), ignored -> {
                }, task)) {
                    session.send(audio().pcm()).toCompletableFuture().get(2, TimeUnit.SECONDS);
                    return session.finish().toCompletableFuture().get(6, TimeUnit.SECONDS);
                }
            }).completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
            assertEquals("model-live", endpoint.socketModel.get());
            assertEquals(1, endpoint.commits.get());
        }
    }

    @Test
    void directVoiceRejectionDoesNotUseTheSecondModel() throws Exception {
        try (var endpoint = new Endpoint(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, "first", endpoint.http(), "", AiCapabilityType.VOICE_FILE);
            model(runtime, "second", endpoint.http(), "", AiCapabilityType.VOICE_FILE);
            route(runtime, "first", "second");
            endpoint.httpStatus.set(503);
            var result = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> runtime.capabilities().ai(OWNER, VERSION).call(audio().withModel("first"), task))
                .completion().toCompletableFuture().get(6, TimeUnit.SECONDS);
            assertEquals(TaskResult.FAILED, result.outcome());
            assertEquals(1, endpoint.httpCalls.get());
        }
    }

    @Test
    void recommendedFileModelsUseTheNextModelAfterKnownServiceRejection() throws Exception {
        try (var first = new Endpoint(); var second = new Endpoint(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, "first", first.http(), "", AiCapabilityType.VOICE_FILE);
            model(runtime, "second", second.http(), "", AiCapabilityType.VOICE_FILE);
            route(runtime, "first", "second");
            first.httpStatus.set(503);
            var result = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> runtime.capabilities().ai(OWNER, VERSION).call(audio(), task)));
            assertEquals("second", result.modelId());
            assertEquals(1, first.httpCalls.get());
            assertEquals(1, second.httpCalls.get());
        }
    }

    @Test
    void realtimeOnlyFallsBackBeforeASessionHasOpened() throws Exception {
        try (var endpoint = new Endpoint(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            int unusedPort;
            try (var socket = new java.net.ServerSocket(0)) {
                unusedPort = socket.getLocalPort();
            }
            model(runtime, "unreachable", "", "ws://127.0.0.1:" + unusedPort + "/realtime", AiCapabilityType.VOICE_REALTIME);
            model(runtime, "live", "", endpoint.ws(), AiCapabilityType.VOICE_REALTIME);
            route(runtime, "unreachable", "live");
            var ai = runtime.capabilities().ai(OWNER, VERSION);
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                try (var session = ai.openVoice(new VoiceOptions(Duration.ofSeconds(5), 65536), ignored -> {
                }, task)) {
                    session.send(audio().pcm()).toCompletableFuture().get(2, TimeUnit.SECONDS);
                    return session.finish().toCompletableFuture().get(6, TimeUnit.SECONDS);
                }
            }));
            assertEquals("model-live", endpoint.socketModel.get());
            assertEquals(1, endpoint.commits.get());
        }
    }
}
