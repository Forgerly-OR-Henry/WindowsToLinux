package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import javax.imageio.ImageIO;

import gold.debug.windowstolinux.app.db.*;
import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.app.service.contract.profile.AiProviderProfile;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.PlatformTaskAdmission;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.app.ai.catalog.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformModelServiceTest {
    @TempDir
    Path root;

    static PlatformModelService setup(PlatformRuntime runtime) throws Exception {
        runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.started();
        return runtime.models();
    }

    static AiProviderProfile profile(PlatformAiServiceTest.Fixture fixture) {
        return new AiProviderProfile("sample", fixture.uri("/chat"), "fixture-model", "ai/provider/sample/key");
    }

    static void answer(PlatformAiServiceTest.Fixture fixture) {
        fixture.handler.set(exchange -> {
            var wire = fixture.requests.remove().body();
            var content = wire.path("messages").get(0).path("content");
            String answer;
            if (content.isTextual()) {
                String text = content.textValue();
                answer = text.substring(text.length() - 8);
            } else {
                var imageUrl = content.get(1).path("image_url").path("url").textValue();
                var bytes = Base64.getDecoder().decode(imageUrl.substring(imageUrl.indexOf(',') + 1));
                var image = ImageIO.read(new ByteArrayInputStream(bytes));
                var colors = Map.of(0xFF0000, "RED", 0x00FF00, "GREEN", 0x0000FF, "BLUE", 0xFFFF00, "YELLOW", 0x00FFFF,
                        "CYAN", 0xFF00FF, "MAGENTA", 0x000000, "BLACK", 0xFFFFFF, "WHITE");
                var names = new ArrayList<String>();
                for (int x : new int[]{60, 180, 300})
                    names.add(colors.get(image.getRGB(x, 60) & 0xFFFFFF));
                answer = String.join(" ", names);
                assertFalse(content.get(0).path("text").textValue().contains(answer));
            }
            reply(exchange, answer);
        });
    }

    static void reply(com.sun.net.httpserver.HttpExchange exchange, String answer) throws java.io.IOException {
        String body = "{\"choices\":[{\"message\":{\"content\":" + PlatformAiServiceTest.JSON.writeValueAsString(answer)
                + "},\"finish_reason\":\"stop\"}]}";
        PlatformAiServiceTest.Fixture.reply(exchange, 200, body);
    }

    static OperationResult<Void> done(TaskHandle<Void> handle) throws Exception {
        return handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
    }

    static void reconcile(PlatformModelService service, TaskHandle<?> handle) throws Exception {
        state(handle, TaskState.UNKNOWN);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            try {
                if (service.reconcile(handle.id()).toCompletableFuture().get(2, TimeUnit.SECONDS)
                        .state() == TaskState.ENDED)
                    return;
            } catch (ApiException running) {
                assertEquals(ProblemCode.BUSY, running.problem().code());
            }
            Thread.sleep(10);
        }
        fail("Probe did not reconcile");
    }

    @Test
    void realTextAndImageChallengesSaveIndependentEvidenceAndExplicitRoutesAcrossRestart() throws Exception {
        try (var fixture = new PlatformAiServiceTest.Fixture()) {
            answer(fixture);
            try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
                var service = setup(runtime);
                char[] key = "fixture-model-key".toCharArray();
                assertEquals(Outcome.SUCCEEDED,
                        done(service.verifyAndSave(profile(fixture), "测试模型", key, AiCapabilityType.TEXT, 128))
                                .outcome());
                assertArrayEquals(new char[key.length], key);
                var text = service.listAiConfigurations().getFirst();
                assertTrue(text.textVerifiedAt().isPresent());
                assertTrue(text.visionVerifiedAt().isEmpty());
                assertTrue(service.listAiPurpose(AiPurposeType.DEPLOYMENT).isEmpty());
                assertEquals(128, service.outputLimit("sample").orElseThrow());
                assertNotEquals(profile(fixture).credentialKey(), text.profile().credentialKey());
                assertEquals(Outcome.SUCCEEDED, done(
                        service.verifyAndSave(text.profile(), text.name(), new char[0], AiCapabilityType.VISION, 128))
                        .outcome());
                var both = service.listAiConfigurations().getFirst();
                assertEquals(text.revision(), both.revision());
                assertTrue(both.textVerifiedAt().isPresent());
                assertTrue(both.visionVerifiedAt().isPresent());
                service.saveAiPurpose(AiPurposeType.VISION, List.of(new AiPurposeAssignment("sample", true)));
                assertTrue(service.listAiPurpose(AiPurposeType.DEPLOYMENT).isEmpty());
                assertEquals(2, fixture.calls.get());
            }
            try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
                runtime.security().unlock(PASSWORD.toCharArray());
                runtime.started();
                var service = runtime.models();
                assertEquals(128, service.outputLimit("sample").orElseThrow());
                assertTrue(service.listAiConfigurations().getFirst().visionVerifiedAt().isPresent());
                assertEquals(List.of(new AiPurposeAssignment("sample", true)),
                        service.listAiPurpose(AiPurposeType.VISION));
                assertEquals(2, fixture.calls.get());
            }
        }
    }

    @Test
    void emptyKeyRenamePreservesRevisionAndDisabledRouteWhileNewKeyAdvancesRevision() throws Exception {
        try (var fixture = new PlatformAiServiceTest.Fixture();
                var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = setup(runtime);
            answer(fixture);
            char[] firstKey = "first-key".toCharArray();
            assertEquals(Outcome.SUCCEEDED, done(service.verifyAndSave(profile(fixture), "First", firstKey,
                    AiCapabilityType.TEXT, 128)).outcome());
            assertArrayEquals(new char[firstKey.length], firstKey);
            var first = service.listAiConfigurations().getFirst();
            var assignments = List.of(new AiPurposeAssignment("sample", false));
            service.saveAiPurpose(AiPurposeType.DEPLOYMENT, assignments);
            byte[] original;
            try (var stored = runtime.persistence().security().find(first.profile().credentialKey()).orElseThrow()) {
                original = stored.payload();
            }
            assertEquals(Outcome.SUCCEEDED, done(service.verifyAndSave(first.profile(), "Renamed", new char[0],
                    AiCapabilityType.TEXT, 128)).outcome());
            var renamed = service.listAiConfigurations().getFirst();
            assertEquals(first.revision(), renamed.revision());
            assertEquals(first.profile().credentialKey(), renamed.profile().credentialKey());
            assertEquals("Renamed", renamed.name());
            try (var stored = runtime.persistence().security().find(first.profile().credentialKey()).orElseThrow()) {
                assertArrayEquals(original, stored.payload());
            }
            assertEquals(assignments, service.listAiPurpose(AiPurposeType.DEPLOYMENT));
            char[] secondKey = "second-key".toCharArray();
            assertEquals(Outcome.SUCCEEDED, done(service.verifyAndSave(renamed.profile(), "Renamed", secondKey,
                    AiCapabilityType.TEXT, 128)).outcome());
            assertArrayEquals(new char[secondKey.length], secondKey);
            var changed = service.listAiConfigurations().getFirst();
            assertTrue(changed.revision() > renamed.revision());
            assertTrue(changed.textVerifiedAt().isPresent());
            assertEquals(assignments, service.listAiPurpose(AiPurposeType.DEPLOYMENT));
            try (var operation = runtime.security().enterOperation(); var store = runtime.security().openCredentials()) {
                char[] actual = store.read(changed.profile().credentialKey()).orElseThrow();
                try {
                    assertArrayEquals("second-key".toCharArray(), actual);
                } finally {
                    Arrays.fill(actual, '\0');
                }
            }
            assertEquals(3, fixture.calls.get(), "Each selected capability challenge is sent once");
            assertTrue(fixture.requests.isEmpty(), "The challenge handler consumed every original request");
        }
    }

    @Test
    void failedChallengeDoesNotReplaceSavedKeyOrBudgetAndRejectsNonlocalPlainHttp() throws Exception {
        try (var fixture = new PlatformAiServiceTest.Fixture();
                var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = setup(runtime);
            answer(fixture);
            assertEquals(Outcome.SUCCEEDED, done(service.verifyAndSave(profile(fixture), "Original",
                    "original-key".toCharArray(), AiCapabilityType.TEXT, 128)).outcome());
            var before = service.listAiConfigurations().getFirst();
            fixture.handler.set(exchange -> reply(exchange, "wrong-answer"));
            var result = done(service.verifyAndSave(before.profile(), "Changed", "replacement-key".toCharArray(),
                    AiCapabilityType.TEXT, 256));
            assertEquals(Outcome.FAILED, result.outcome());
            assertEquals("MODEL_PROBE_ANSWER", result.problem().orElseThrow().eventCode());
            assertEquals(before, service.listAiConfigurations().getFirst());
            assertEquals(128, service.outputLimit("sample").orElseThrow());
            try (var op = runtime.security().enterOperation(); var store = runtime.security().openCredentials()) {
                char[] key = store.read(before.profile().credentialKey()).orElseThrow();
                try {
                    assertArrayEquals("original-key".toCharArray(), key);
                } finally {
                    Arrays.fill(key, '\0');
                }
            }
            assertThrows(IllegalArgumentException.class, () -> new AiProviderProfile("invalid",
                    java.net.URI.create("http://example.com/chat"), "model", "ai/key"));
        }
    }

    @Test
    void concurrentCredentialChangeRejectsStaleEvidenceAndRetainsNewerConfiguration() throws Exception {
        try (var fixture = new PlatformAiServiceTest.Fixture();
                var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = setup(runtime);
            answer(fixture);
            done(service.verifyAndSave(profile(fixture), "Original", "old-key".toCharArray(), AiCapabilityType.TEXT,
                    128));
            var before = service.listAiConfigurations().getFirst();
            var respond = fixture.handler.get();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.handler.set(exchange -> {
                entered.countDown();
                try {
                    release.await(6, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                respond.serve(exchange);
            });
            var handle = service.verifyAndSave(before.profile(), "Stale edit", new char[0], AiCapabilityType.TEXT, 256);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                try (var op = runtime.security().enterOperation(); var store = runtime.security().openCredentials()) {
                    char[] key = "newer-key".toCharArray();
                    try {
                        store.save(before.profile().credentialKey(), key);
                    } finally {
                        Arrays.fill(key, '\0');
                    }
                }
            } finally {
                release.countDown();
            }
            assertEquals("MODEL_PROBE_STALE", done(handle).problem().orElseThrow().eventCode());
            assertTrue(service.listAiConfigurations().getFirst().textVerifiedAt().isEmpty());
            assertEquals(128, service.outputLimit("sample").orElseThrow());
        }
    }

    @Test
    void acceptedProbeCompletesAcrossLockButNewSettingsAreDenied() throws Exception {
        try (var fixture = new PlatformAiServiceTest.Fixture();
                var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = setup(runtime);
            answer(fixture);
            var respond = fixture.handler.get();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.handler.set(exchange -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                respond.serve(exchange);
            });
            var handle = service.verifyAndSave(profile(fixture), "Lock test", "original-key".toCharArray(),
                    AiCapabilityType.TEXT, 128);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                runtime.security().lock();
                assertThrows(RuntimeException.class, service::listAiConfigurations);
            } finally {
                release.countDown();
            }
            assertEquals(Outcome.SUCCEEDED, done(handle).outcome());
            runtime.security().unlock(PASSWORD.toCharArray());
            assertEquals(1, service.listAiConfigurations().size());
        }
    }

    @Test
    void cancelledSentProbeRetainsUnknownAndReconciliationDoesNotSaveOrReplay() throws Exception {
        try (var fixture = new PlatformAiServiceTest.Fixture();
                var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = setup(runtime);
            var release = new CountDownLatch(1);
            fixture.handler.set(exchange -> {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                reply(exchange, "discard");
            });
            var handle = service.verifyAndSave(profile(fixture), "Cancel", "original-key".toCharArray(),
                    AiCapabilityType.TEXT, 128);
            try {
                assertNotNull(fixture.requests.poll(5, TimeUnit.SECONDS));
                service.cancel(handle.id());
                state(handle, TaskState.UNKNOWN);
                assertFalse(handle.completion().toCompletableFuture().isDone());
                reconcile(service, handle);
                assertEquals(1, fixture.calls.get());
                assertTrue(service.listAiConfigurations().isEmpty());
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM credential"));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void failedVerifiedCommitRemainsUnknownAndCanBeExplicitlyClosedWithoutSavingAgain() throws Exception {
        try (var fixture = new PlatformAiServiceTest.Fixture();
                var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = setup(runtime);
            answer(fixture);
            sql(runtime.persistence().database(),
                    "ALTER TABLE ai_model ADD CONSTRAINT fixture_model_commit CHECK(model_id<>'sample')");
            var handle = service.verifyAndSave(profile(fixture), "Failure", "original-key".toCharArray(),
                    AiCapabilityType.TEXT, 128);
            state(handle, TaskState.UNKNOWN);
            sql(runtime.persistence().database(), "ALTER TABLE ai_model DROP CONSTRAINT fixture_model_commit");
            reconcile(service, handle);
            assertTrue(service.listAiConfigurations().isEmpty());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM credential"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void previousVersionProbeReleasesOnlyKnownVanishedProcessResources() throws Exception {
        UUID known;
        try (var db = PlatformDatabase.open(root, PlatformStartupTest.PROTECTION)) {
            var persistence = new PlatformPersistence(db);
            try (var security = new DesktopSecurityService(persistence.security())) {
                security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            }
            var ledger = new TaskLedger(db);
            known = PlatformServerServiceTest.old(ledger, PlatformTaskAdmission.PLATFORM_OWNER, "platform.ai.probe.v1");
            ledger.retain(known, new TaskResource("CONNECTION", "ai-http-" + UUID.randomUUID(),
                    PlatformTaskAdmission.PLATFORM_OWNER, false));
            ledger.retain(known, new TaskResource("CUSTOM", "ai-access-" + UUID.randomUUID(),
                    PlatformTaskAdmission.PLATFORM_OWNER, false));
        }
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            var result = runtime.models().reconcile(known).toCompletableFuture().get(8, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, result.state());
            assertEquals(Outcome.FAILED, result.outcome().orElseThrow());
            assertEquals("0.0.1-original", result.ownerVersion());
            assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }
}
