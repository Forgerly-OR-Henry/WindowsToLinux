package gold.debug.windowstolinux.app.service.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.service.SecurityTestFixture;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.shared.ai.client.OpenAiCompatibleRoleClient;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import gold.debug.windowstolinux.shared.ai.collaboration.role.*;
import gold.debug.windowstolinux.shared.ai.transport.RoleChatResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiProviderChainTest extends SecurityTestFixture {
    @TempDir
    Path directory;

    private final ProjectAnalysisRoleContext context = new ProjectAnalysisRoleContext("local-project",
            "JAVA_MAVEN_SPRING_BOOT", "MAVEN_WRAPPER", "FORMALLY_SUPPORTED", List.of());
    private AiProviderProfile profile(String id) {
        return new AiProviderProfile(id, URI.create("https://" + id + ".example.test/v1/chat/completions"), id,
                "ai/" + id);
    }

    private char[] master() {
        return "correct master password".toCharArray();
    }

    private void seed(DesktopPersistence db, String... ids) throws Exception {
        for (String id : ids) {
            var profile = profile(id);
            try (var store = SecurityTestFixture.secrets(db).open()) {
                store.save(profile.credentialKey(), (id + "-key").toCharArray());
            }
            db.aiProfiles().saveVerified(profile.stored(), id, java.time.Instant.now());
            var members = new java.util.ArrayList<>(db.aiProfiles().purposes()
                    .list(gold.debug.windowstolinux.shared.model.ai.AiPurposeType.DEPLOYMENT));
            members.add(new gold.debug.windowstolinux.shared.model.ai.AiPurposeAssignment(id, true));
            db.aiProfiles().purposes().save(gold.debug.windowstolinux.shared.model.ai.AiPurposeType.DEPLOYMENT,
                    members);
        }
    }

    private void enabled(DesktopPersistence db, String id, boolean enabled) throws Exception {
        var purpose = gold.debug.windowstolinux.shared.model.ai.AiPurposeType.DEPLOYMENT;
        db.aiProfiles().purposes().save(purpose,
                db.aiProfiles().purposes().list(purpose).stream()
                        .map(v -> v.profileId().equals(id)
                                ? new gold.debug.windowstolinux.shared.model.ai.AiPurposeAssignment(id, enabled)
                                : v)
                        .toList());
    }

    private RoleChatResult valid(String decision) {
        return new RoleChatResult(200, "{\"content\":\"{\\\"decision\\\":\\\"" + decision
                + "\\\",\\\"summary\\\":\\\"checked\\\",\\\"findings\\\":[]}\"}");
    }

    @Test
    void skipsDisabledAndFallsThroughHttpAndSchemaFailuresButStopsOnValidRefusal() throws Exception {
        List<String> calls = new ArrayList<>();
        var client = new OpenAiCompatibleRoleClient((endpoint, key, body) -> {
            String id = endpoint.getHost().split("\\.")[0];
            calls.add(id);
            return id.equals("http")
                    ? new RoleChatResult(503, "unavailable")
                    : id.equals("schema") ? new RoleChatResult(200, "{\"content\":\"invalid\"}") : valid("SAFE_STOP");
        }, Clock.systemUTC());
        try (var db = DesktopPersistence.open(directory)) {
            var facade = new AiUseCaseFacade(db.aiProfiles(), SecurityTestFixture.secrets(db), client);
            seed(db, "disabled", "http", "schema", "refusal", "unused");
            enabled(db, "disabled", false);
            var result = facade.invokeRole(context).orElseThrow();
            assertEquals(List.of("http", "schema", "refusal"), calls);
            assertEquals(AiInvocationStatus.VALIDATED, result.evidence().status());
            assertEquals("SAFE_STOP", result.evidence().output().orElseThrow().decision().name());
            assertEquals(3, result.attempts().size());
            assertEquals(List.of("http", "schema", "refusal"),
                    result.attempts().stream().map(v -> v.providerId()).toList());
        }
    }

    @Test
    void usesSnapshotUntilNextCallAndReportsAllFailuresOncePerProvider() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            List<String> calls = new ArrayList<>();
            var client = new OpenAiCompatibleRoleClient((endpoint, key, body) -> {
                String id = endpoint.getHost().split("\\.")[0];
                calls.add(id);
                try {
                    enabled(db, "b", false);
                    db.aiProfiles().reorder(List.of("b", "a"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return new RoleChatResult(500, "failed");
            }, Clock.systemUTC());
            var facade = new AiUseCaseFacade(db.aiProfiles(), SecurityTestFixture.secrets(db), client);
            seed(db, "a", "b");
            char[] unlock = master();
            var first = facade.invokeRole(context).orElseThrow();
            assertEquals(List.of("a", "b"), calls);
            assertEquals(2, first.attempts().size());
            assertEquals(AiInvocationStatus.UNAVAILABLE, first.evidence().status());
            assertEquals("all-enabled-providers-failed", first.evidence().validationDetail());
            calls.clear();
            facade.invokeRole(context);
            assertEquals(List.of("a"), calls);
            enabled(db, "a", false);
            assertTrue(facade.invokeRole(context).isEmpty());
        }
    }

    @Test
    void cancellationStopsEntireChainAndClearsSecrets() throws Exception {
        List<String> calls = new ArrayList<>();
        var client = new OpenAiCompatibleRoleClient((endpoint, key, body) -> {
            calls.add(endpoint.getHost());
            throw new InterruptedException("cancelled");
        }, Clock.systemUTC());
        try (var db = DesktopPersistence.open(directory)) {
            var facade = new AiUseCaseFacade(db.aiProfiles(), SecurityTestFixture.secrets(db), client);
            seed(db, "a", "b");
            char[] unlock = master();
            try {
                assertThrows(CancellationException.class, () -> facade.invokeRole(context));
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(1, calls.size());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void testsOnlySelectedModelBeforeSavingAndRevisesChangedApiKeyContent() throws Exception {
        List<String> requests = new ArrayList<>();
        boolean[] success = {false};
        var client = new OpenAiCompatibleRoleClient((endpoint, key, body) -> {
            requests.add(body);
            return success[0] ? valid("CLEAR") : new RoleChatResult(401, "unauthorized");
        }, Clock.systemUTC());
        try (var db = DesktopPersistence.open(directory)) {
            var secrets = SecurityTestFixture.secrets(db);
            var facade = new AiUseCaseFacade(db.aiProfiles(), secrets, client);
            char[] key = "first-key".toCharArray();
            assertThrows(Exception.class, () -> facade.saveConfiguration(profile("a"), "First", key,
                    gold.debug.windowstolinux.shared.model.ai.AiCapabilityType.TEXT));
            assertArrayEquals(new char[key.length], key);
            assertTrue(facade.configurations().isEmpty());
            success[0] = true;
            facade.saveConfiguration(profile("a"), "First", "first-key".toCharArray(),
                    gold.debug.windowstolinux.shared.model.ai.AiCapabilityType.TEXT);
            var first = facade.configurations().getFirst();
            assertTrue(first.textVerifiedAt().isPresent());
            assertTrue(facade.purpose(gold.debug.windowstolinux.shared.model.ai.AiPurposeType.DEPLOYMENT).isEmpty());
            assertTrue(requests.stream()
                    .allMatch(body -> body.contains("connection-test") && !body.contains("local-project")));
            enabled(db, "a", false);
            facade.saveConfiguration(first.profile(), "Renamed", new char[0],
                    gold.debug.windowstolinux.shared.model.ai.AiCapabilityType.TEXT);
            assertEquals(first.profile().credentialKey(), facade.configurations().getFirst().profile().credentialKey());
            assertTrue(facade.purpose(gold.debug.windowstolinux.shared.model.ai.AiPurposeType.DEPLOYMENT).isEmpty());
            facade.saveConfiguration(first.profile(), "Renamed", "second-key".toCharArray(),
                    gold.debug.windowstolinux.shared.model.ai.AiCapabilityType.TEXT);
            var second = facade.configurations().getFirst();
            assertEquals(first.profile().credentialKey(), second.profile().credentialKey());
            assertTrue(db.aiProfiles().listConfigured().getFirst().revision() > 1);
            try (var store = secrets.open()) {
                assertEquals("second-key", new String(store.read(second.profile().credentialKey()).orElseThrow()));
            }
        }
    }
}
