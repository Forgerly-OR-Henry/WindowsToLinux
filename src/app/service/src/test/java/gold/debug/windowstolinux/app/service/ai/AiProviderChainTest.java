package gold.debug.windowstolinux.app.service.ai;
import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.shared.ai.client.OpenAiCompatibleRoleClient;
import gold.debug.windowstolinux.shared.ai.transport.RoleChatResult;
import gold.debug.windowstolinux.shared.ai.collaboration.role.*;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
class AiProviderChainTest {
    @TempDir Path directory;
    private final ProjectAnalysisRoleContext context = new ProjectAnalysisRoleContext("local-project", "JAVA_MAVEN_SPRING_BOOT", "MAVEN_WRAPPER", "FORMALLY_SUPPORTED", List.of());
    private AiProviderProfile profile(String id) { return new AiProviderProfile(id, URI.create("https://"+id+".example.test/v1/chat/completions"), id, "ai/"+id, CredentialStorageMode.MASTER_PASSWORD); }
    private char[] master() { return "correct master password".toCharArray(); }
    private void seed(AiUseCaseFacade facade, String... ids) throws Exception { for (String id : ids) facade.saveNamed(profile(id), master(), (id+"-key").toCharArray()); }
    private RoleChatResult valid(String decision) { return new RoleChatResult(200, "{\"content\":\"{\\\"decision\\\":\\\""+decision+"\\\",\\\"summary\\\":\\\"checked\\\",\\\"findings\\\":[]}\"}"); }
    @Test void skipsDisabledAndFallsThroughHttpAndSchemaFailuresButStopsOnValidRefusal() throws Exception {
        List<String> calls = new ArrayList<>();
        var client = new OpenAiCompatibleRoleClient((endpoint, key, body) -> {
            String id = endpoint.getHost().split("\\.")[0]; calls.add(id);
            return id.equals("http") ? new RoleChatResult(503, "unavailable") : id.equals("schema") ? new RoleChatResult(200, "{\"content\":\"invalid\"}") : valid("SAFE_STOP");
        }, Clock.systemUTC());
        try (var db = DesktopPersistence.open(directory)) {
            var facade = new AiUseCaseFacade(db.aiProfiles(), new DesktopSecretStoreService(db.encryptedSecrets()), client); seed(facade,"disabled","http","schema","refusal","unused");
            facade.setEnabled("disabled", false); facade.assignRole(new AiRoleAssignment(AiCollaborationRoleKind.PROJECT_ANALYSIS,"unused"));
            var result = facade.invokeRole(context, master()).orElseThrow(); assertEquals(List.of("http","schema","refusal"), calls);
            assertEquals(AiInvocationStatus.VALIDATED,result.evidence().status()); assertEquals("SAFE_STOP",result.evidence().output().orElseThrow().decision().name());
            assertEquals(3,result.attempts().size()); assertEquals(List.of("http","schema","refusal"), result.attempts().stream().map(v -> v.providerId()).toList());
        }
    }
    @Test void usesSnapshotUntilNextCallAndReportsAllFailuresOncePerProvider() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            List<String> calls = new ArrayList<>();
            var client = new OpenAiCompatibleRoleClient((endpoint,key,body) -> {
                String id = endpoint.getHost().split("\\.")[0]; calls.add(id);
                try { db.aiProfiles().setEnabled("b", false); db.aiProfiles().reorder(List.of("b","a")); } catch (Exception e) { throw new IllegalStateException(e); }
                return new RoleChatResult(500,"failed");
            }, Clock.systemUTC());
            var facade = new AiUseCaseFacade(db.aiProfiles(), new DesktopSecretStoreService(db.encryptedSecrets()), client); seed(facade,"a","b");
            char[] unlock = master(); var first = facade.invokeRole(context,unlock).orElseThrow(); assertEquals(List.of("a","b"),calls); assertArrayEquals(new char[unlock.length],unlock);
            assertEquals(2,first.attempts().size()); assertEquals(AiInvocationStatus.UNAVAILABLE, first.evidence().status());
            assertEquals("all-enabled-providers-failed",first.evidence().validationDetail()); calls.clear(); facade.invokeRole(context,master()); assertEquals(List.of("a"),calls);
            facade.setEnabled("a", false); assertTrue(facade.invokeRole(context,master()).isEmpty());
        }
    }
    @Test void cancellationStopsEntireChainAndClearsSecrets() throws Exception {
        List<String> calls = new ArrayList<>();
        var client = new OpenAiCompatibleRoleClient((endpoint,key,body) -> { calls.add(endpoint.getHost()); throw new InterruptedException("cancelled"); }, Clock.systemUTC());
        try (var db = DesktopPersistence.open(directory)) {
            var facade = new AiUseCaseFacade(db.aiProfiles(),new DesktopSecretStoreService(db.encryptedSecrets()),client); seed(facade,"a","b"); char[] unlock = master();
            try { assertThrows(CancellationException.class, () -> facade.invokeRole(context,unlock)); assertTrue(Thread.currentThread().isInterrupted()); assertEquals(1,calls.size()); assertArrayEquals(new char[unlock.length],unlock); }
            finally { Thread.interrupted(); }
        }
    }
    @Test void testsOnlySelectedModelBeforeSavingAndRetainsImmutableCredentialVersions() throws Exception {
        List<String> requests = new ArrayList<>(); boolean[] success = {false};
        var client = new OpenAiCompatibleRoleClient((endpoint,key,body) -> { requests.add(body); return success[0] ? valid("CLEAR") : new RoleChatResult(401,"unauthorized"); },Clock.systemUTC());
        try (var db = DesktopPersistence.open(directory)) {
            var secrets = new DesktopSecretStoreService(db.encryptedSecrets()); var facade = new AiUseCaseFacade(db.aiProfiles(),secrets,client);
            char[] key = "first-key".toCharArray(); assertThrows(Exception.class, () -> facade.saveConfiguration(profile("a"),"First",master(),key)); assertArrayEquals(new char[key.length],key); assertTrue(facade.configurations().isEmpty());
            success[0] = true; facade.saveConfiguration(profile("a"),"First",master(),"first-key".toCharArray()); var first = facade.configurations().getFirst();
            assertTrue(first.verifiedAt().isPresent()); assertTrue(first.enabled()); assertTrue(requests.stream().allMatch(body -> body.contains("connection-test") && !body.contains("local-project")));
            facade.setEnabled("a",false); facade.saveConfiguration(first.profile(),"Renamed",master(),new char[0]); assertEquals(first.profile().credentialKey(),facade.configurations().getFirst().profile().credentialKey()); assertFalse(facade.configurations().getFirst().enabled());
            facade.saveConfiguration(first.profile(),"Renamed",master(),"second-key".toCharArray()); var second = facade.configurations().getFirst(); assertNotEquals(first.profile().credentialKey(),second.profile().credentialKey());
            try (var store = secrets.open(CredentialStorageMode.MASTER_PASSWORD,master())) { assertEquals("first-key",new String(store.read(first.profile().credentialKey()).orElseThrow())); assertEquals("second-key",new String(store.read(second.profile().credentialKey()).orElseThrow())); }
        }
    }
}
