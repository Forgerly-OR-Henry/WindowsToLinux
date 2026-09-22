package gold.debug.windowstolinux.app.service.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.service.failure.*;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.shared.ai.client.OpenAiCompatibleRoleClient;
import gold.debug.windowstolinux.shared.ai.collaboration.role.*;
import gold.debug.windowstolinux.shared.ai.transport.RoleChatResult;
import gold.debug.windowstolinux.shared.model.ai.*;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentAiScopeTest {
    @TempDir
    Path directory;
    private AiProviderProfile profile(String id) {
        return new AiProviderProfile(id, URI.create("https://example.test/v1/chat/completions"), id, "ai/" + id,
                CredentialStorageMode.MASTER_PASSWORD);
    }

    @Test
    void staticScopeNeverInvokesTransportEvenWhenModelsExist() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            var calls = new AtomicInteger();
            var facade = new AiUseCaseFacade(db.aiProfiles(), new DesktopSecretStoreService(db.encryptedSecrets()),
                    new OpenAiCompatibleRoleClient((url, key, body) -> {
                        calls.incrementAndGet();
                        throw new AssertionError("static task must not invoke AI");
                    }, Clock.systemUTC()));
            db.aiProfiles().saveVerified(profile("model").stored(), "model", Instant.now());
            db.aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT, List.of(new AiPurposeAssignment("model", true)));
            try (var scope = facade.openDeployment(DeploymentAutomationMode.STATIC)) {
                assertFalse(scope.allowsAi());
                assertTrue(
                        facade.invokeRole(new ErrorExplanationRoleContext("deploy", "failure"), "master".toCharArray())
                                .isEmpty());
            }
            assertEquals(0, calls.get());
            assertTrue(DeploymentAiScope.current().isEmpty());
        }
    }

    @Test
    void agentCannotOpenWithoutExplicitVerifiedApprovalMembership() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            var facade = new AiUseCaseFacade(db.aiProfiles(), new DesktopSecretStoreService(db.encryptedSecrets()));
            db.aiProfiles().saveVerified(profile("model").stored(), "model", Instant.now());
            db.aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT, List.of(new AiPurposeAssignment("model", true)));
            for (var ignored : AgentApprovalMode.values()) {
                var failure = assertThrows(ApplicationServiceException.class,
                        () -> facade.openDeployment(DeploymentAutomationMode.AGENT));
                assertEquals(ApplicationServiceFailureType.APPROVAL_MODEL_REQUIRED, failure.failure().definition());
            }
            db.aiProfiles().purposes().save(AiPurposeType.APPROVAL, List.of(new AiPurposeAssignment("model", false)));
            assertThrows(ApplicationServiceException.class,
                    () -> facade.openDeployment(DeploymentAutomationMode.AGENT));
            db.aiProfiles().purposes().save(AiPurposeType.APPROVAL, List.of(new AiPurposeAssignment("model", true)));
            try (var scope = facade.openDeployment(DeploymentAutomationMode.AGENT)) {
                assertEquals(List.of(profile("model")), scope.providers(AiPurposeType.APPROVAL));
                db.aiProfiles().purposes().save(AiPurposeType.APPROVAL, List.of());
                assertEquals(1, scope.providers(AiPurposeType.APPROVAL).size());
            }
            assertTrue(DeploymentAiScope.current().isEmpty());
        }
    }

    @Test
    void deploymentPurposeRequiredAndInventoryOrderCannotChangeCapturedRouting() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            var facade = new AiUseCaseFacade(db.aiProfiles(), new DesktopSecretStoreService(db.encryptedSecrets()));
            assertThrows(ApplicationServiceException.class,
                    () -> facade.openDeployment(DeploymentAutomationMode.ASSISTED));
            for (var id : List.of("a", "b"))
                db.aiProfiles().saveVerified(profile(id).stored(), id, Instant.now());
            db.aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT,
                    List.of(new AiPurposeAssignment("b", true), new AiPurposeAssignment("a", true)));
            assertThrows(ApplicationServiceException.class,
                    () -> facade.openDeployment(DeploymentAutomationMode.ASSISTED));
            db.aiProfiles().purposes().save(AiPurposeType.APPROVAL, List.of(new AiPurposeAssignment("a", true)));
            try (var scope = facade.openDeployment(DeploymentAutomationMode.ASSISTED)) {
                db.aiProfiles().reorder(List.of("a", "b"));
                db.aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT, List.of(new AiPurposeAssignment("a", true)));
                assertEquals(List.of("b", "a"),
                        scope.providers(AiPurposeType.DEPLOYMENT).stream().map(AiProviderProfile::id).toList());
            }
        }
    }
}
