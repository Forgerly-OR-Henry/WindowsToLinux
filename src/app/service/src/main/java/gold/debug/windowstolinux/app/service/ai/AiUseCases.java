package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.app.db.repository.AiProfileRepository;
import gold.debug.windowstolinux.app.secret.api.SecretStore;
import gold.debug.windowstolinux.app.secret.api.SecretStoreException;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStores;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.ai.client.AiAnalysisException;
import gold.debug.windowstolinux.shared.ai.client.OpenAiCompatibleStructuralAnalyzer;
import gold.debug.windowstolinux.shared.ai.prompt.AiResponseLanguage;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.message.LocalizedOperationException;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides the {@code AiUseCases} implementation.
 *
 * <p>提供 {@code AiUseCases} 实现。
 */
public final class AiUseCases {
    private final AiProfileRepository profiles;
    private final DesktopSecretStores secrets;

    /**
     * Creates a {@code AiUseCases} instance.
     *
     * <p>创建 {@code AiUseCases} 实例。
     *
     * @param database the {@code database} value / {@code database} 值
     * @param secrets the {@code secrets} value / {@code secrets} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public AiUseCases(AiProfileRepository profiles, DesktopSecretStores secrets) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /**
     * Stores data through {@code save}.
     *
     * <p>通过 {@code save} 保存数据。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param apiKey the {@code apiKey} value / {@code apiKey} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     */
    public void save(AiProfile profile, CredentialStorageMode mode, char[] masterPassword, char[] apiKey)
            throws SQLException, SecretStoreException {
        if (profile.credentialMode() != mode) {
            throw new LocalizedOperationException(LocalizedMessage.of("validation.storageModeMismatch"),
                    "AI credential storage mode does not match the selected save mode");
        }
        try (SecretStore store = secrets.open(mode, masterPassword)) {
            store.save(profile.credentialKey(), apiKey);
            profiles.saveDefault(profile.stored());
        } finally {
            clear(masterPassword);
            clear(apiKey);
        }
    }

    /**
     * Returns the value produced by {@code find}.
     *
     * <p>返回 {@code find} 生成的值。
     *
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<AiProfile> find() throws SQLException {
        return profiles.findDefault().map(AiProfile::fromStored);
    }

    /**
     * Stores one explicitly named AI provider without changing the legacy default provider.
     *
     * <p>保存一个显式命名的 AI 提供者，而不改变旧版默认提供者。
     */
    public void saveNamed(AiProviderProfile profile, char[] masterPassword, char[] apiKey)
            throws SQLException, SecretStoreException {
        Objects.requireNonNull(profile, "profile");
        try (SecretStore store = secrets.open(profile.credentialMode(), masterPassword)) {
            store.save(profile.credentialKey(), apiKey);
            profiles.saveNamed(profile.stored());
        } finally {
            clear(masterPassword);
            clear(apiKey);
        }
    }

    /**
     * Lists named AI providers without reading their API keys.
     *
     * <p>列出命名 AI 提供者，而不读取其 API Key。
     */
    public List<AiProviderProfile> listNamed() throws SQLException {
        return profiles.listNamed().stream().map(AiProviderProfile::fromStored).toList();
    }

    /**
     * Performs the {@code explain} operation.
     *
     * <p>执行 {@code explain} 操作。
     *
     * @param preparation the {@code preparation} value / {@code preparation} 值
     * @param profile the {@code profile} value / {@code profile} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param languageTag the {@code languageTag} value / {@code languageTag} 值
     * @return the operation result / 操作结果
     */
    /** Explains the result of a selected typed static source inspection. / 解释选定类型化静态源码检查的结果。 */
    public AiAnalysisOutcome explain(ReviewedSourcePreparation preparation, AiProfile profile,
                                     CredentialStorageMode mode, char[] masterPassword, String languageTag) {
        if (preparation.assessment().facts().isEmpty()) {
            return AiAnalysisOutcome.unavailable(LocalizedMessage.of("ai.status.analysisRequired"));
        }
        return explainFacts(preparation.assessment().facts().orElseThrow(), profile, mode, masterPassword, languageTag);
    }

    private AiAnalysisOutcome explainFacts(gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts facts,
                                            AiProfile profile, CredentialStorageMode mode, char[] masterPassword,
                                            String languageTag) {
        if (profile.credentialMode() != mode) {
            return AiAnalysisOutcome.unavailable(LocalizedMessage.of("ai.status.storageModeMismatch"));
        }
        try (SecretStore store = secrets.open(mode, masterPassword)) {
            char[] apiKey = store.read(profile.credentialKey()).orElse(null);
            if (apiKey == null) {
                return AiAnalysisOutcome.unavailable(LocalizedMessage.of("ai.status.apiKeyMissing"));
            }
            try {
                String explanation = new OpenAiCompatibleStructuralAnalyzer().analyze(
                        profile.chatCompletionsEndpoint(), profile.model(), apiKey, facts,
                        AiResponseLanguage.fromLanguageTag(languageTag)).explanation();
                return AiAnalysisOutcome.available(explanation);
            } finally {
                clear(apiKey);
            }
        } catch (AiAnalysisException | SecretStoreException exception) {
            return AiAnalysisOutcome.unavailable(exception.userMessage(), exception.diagnostic());
        } finally {
            clear(masterPassword);
        }
    }

    /**
     * Calls only the named provider selected by its stored identifier; no alternative provider is consulted on failure.
     *
     * <p>仅调用由已存储标识选定的命名提供者；失败时不会咨询其他提供者。
     */
    public AiAnalysisOutcome explainNamed(ReviewedSourcePreparation preparation, String providerId, char[] masterPassword,
                                          String languageTag) {
        try {
            AiProviderProfile profile = profiles.listNamed().stream()
                    .filter(candidate -> candidate.id().equals(providerId))
                    .map(AiProviderProfile::fromStored)
                    .findFirst()
                    .orElse(null);
            if (profile == null) {
                return AiAnalysisOutcome.unavailable(LocalizedMessage.of("ai.status.providerMissing"));
            }
            return explain(preparation, profile.selectedProfile(), profile.credentialMode(), masterPassword, languageTag);
        } catch (SQLException exception) {
            clear(masterPassword);
            return AiAnalysisOutcome.unavailable(LocalizedMessage.of("ai.status.providerMissing"),
                    "The selected AI provider metadata could not be read");
        }
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
