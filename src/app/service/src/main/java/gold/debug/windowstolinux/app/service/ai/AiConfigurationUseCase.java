package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.app.db.persistence.repository.AiProfileRepository;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.shared.ai.client.OpenAiCompatibleRoleClient;
import gold.debug.windowstolinux.shared.ai.collaboration.role.ProjectAnalysisRoleContext;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiRoleBinding;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** Validates a single selected model before saving; runtime requests use the global chain. / 保存前验证单个所选模型，运行时请求使用全局调用链。 */
final class AiConfigurationUseCase {
    private final AiProfileRepository profiles;
    private final DesktopSecretStoreService secrets;
    private final OpenAiCompatibleRoleClient client;
    AiConfigurationUseCase(AiProfileRepository profiles, DesktopSecretStoreService secrets, OpenAiCompatibleRoleClient client) {
        this.profiles = profiles; this.secrets = secrets; this.client = client;
    }

    void save(AiProviderProfile profile, String name, char[] master, char[] suppliedKey) throws SQLException, SecretStoreException {
        char[] key = null;
        try {
            AiProviderChain.checkCancelled();
            if (name.isBlank() || name.length() > 120 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid model display name");
            var prior = profiles.findNamed(profile.id());
            try (var store = secrets.open(profile.credentialMode(), master)) {
                if (suppliedKey.length > 0) key = suppliedKey.clone();
                else {
                    var saved = prior.orElseThrow(() -> new IllegalArgumentException("API key is required for a new model"));
                    if (!saved.credentialMode().equals(profile.credentialMode().name()) || !saved.credentialKey().equals(profile.credentialKey()))
                        throw new IllegalArgumentException("changing credential storage requires an API key");
                    key = store.read(saved.credentialKey()).orElseThrow(() -> new IllegalArgumentException("saved API key is unavailable"));
                }
                AiProviderChain.checkCancelled();
                var context = new ProjectAnalysisRoleContext("connection-test", "JAVA_MAVEN_SPRING_BOOT", "MAVEN_WRAPPER", "FORMALLY_SUPPORTED", List.of());
                var probe = client.invoke(new AiRoleBinding(context.role(), profile.id(), profile.chatCompletionsEndpoint(), profile.model()), key, context);
                AiProviderChain.checkCancelled();
                if (probe.evidence().status() != AiInvocationStatus.VALIDATED)
                    throw ApplicationServiceException.create(ApplicationServiceFailureType.AI_CONFIGURATION_TEST_FAILED,
                            "Selected model probe failed: " + probe.evidence().validationDetail());
                String credential = suppliedKey.length > 0 ? "ai/provider/" + profile.id() + "/api-key/" + UUID.randomUUID() : profile.credentialKey();
                if (suppliedKey.length > 0) store.save(credential, key);
                var saved = new AiProviderProfile(profile.id(), profile.chatCompletionsEndpoint(), profile.model(), credential, profile.credentialMode());
                AiProviderChain.checkCancelled(); profiles.saveVerified(saved.stored(), name, Instant.now());
            }
        } finally { if (key != null) Arrays.fill(key, '\0'); Arrays.fill(suppliedKey, '\0'); Arrays.fill(master, '\0'); }
    }
}
