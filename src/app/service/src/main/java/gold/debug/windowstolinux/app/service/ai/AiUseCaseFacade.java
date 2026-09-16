package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.app.db.persistence.repository.AiProfileRepository;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.ai.AiAnalysisException;
import gold.debug.windowstolinux.shared.ai.client.OpenAiCompatibleStructuralAnalysisClient;
import gold.debug.windowstolinux.shared.ai.client.OpenAiCompatibleRoleClient;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiRoleBinding;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiRoleContext;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiRoleInvocationResult;
import gold.debug.windowstolinux.shared.ai.generation.prompt.AiResponseLanguageType;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides the {@code AiUseCaseFacade} implementation.
 *
 * <p>提供 {@code AiUseCaseFacade} 实现。
 */
public final class AiUseCaseFacade {
    private final AiProfileRepository profiles;
    private final DesktopSecretStoreService secrets;
    private final OpenAiCompatibleRoleClient roleClient;
    private final AiProviderChain chain;
    private final AiConfigurationUseCase configuration;

    /**
     * Creates a {@code AiUseCaseFacade} instance.
     *
     * <p>创建 {@code AiUseCaseFacade} 实例。
     *
     * @param profiles the {@code profiles} value / {@code profiles} 值
     * @param secrets the {@code secrets} value / {@code secrets} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public AiUseCaseFacade(AiProfileRepository profiles, DesktopSecretStoreService secrets) {
        this(profiles, secrets, new OpenAiCompatibleRoleClient());
    }

    AiUseCaseFacade(AiProfileRepository profiles, DesktopSecretStoreService secrets, OpenAiCompatibleRoleClient roleClient) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.roleClient = Objects.requireNonNull(roleClient, "roleClient");
        this.chain = new AiProviderChain(profiles, secrets);
        this.configuration = new AiConfigurationUseCase(profiles, secrets, roleClient);
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
            throw ApplicationServiceException.create(ApplicationServiceFailureType.STORAGE_MODE_MISMATCH,
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
    void saveNamed(AiProviderProfile profile, char[] masterPassword, char[] apiKey)
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
        return profiles.listConfigured().stream().map(value -> AiProviderProfile.fromStored(value.profile())).toList();
    }

    /** Assigns one fixed collaboration role to one existing named provider. / 将一个固定协作角色分配给一个已有命名提供者。 */
    public void assignRole(AiRoleAssignment assignment) throws SQLException {
        profiles.saveRoleAssignment(Objects.requireNonNull(assignment, "assignment").stored());
    }

    /** Lists explicit role bindings without loading any credential. / 列出显式角色绑定且不加载任何凭据。 */
    public List<AiRoleAssignment> listRoleAssignments() throws SQLException {
        return profiles.listRoleAssignments().stream().map(AiRoleAssignment::fromStored).toList();
    }

    /** Lists ordered enablement and verification metadata. / 列出有序启用及验证元数据。 */
    public List<AiProviderSummary> configurations() throws SQLException {
        return profiles.listConfigured().stream().map(value -> new AiProviderSummary(AiProviderProfile.fromStored(value.profile()),
                value.name(), value.enabled(), value.priority(), value.verifiedAt())).toList();
    }
    /** Tests the selected model with a fixed synthetic context before saving. / 保存前使用固定合成上下文测试所选模型。 */
    public void saveConfiguration(AiProviderProfile profile, String name, char[] master, char[] key) throws SQLException, SecretStoreException {
        configuration.save(profile, name, master, key);
    }
    /** Changes enablement while retaining position. / 改变启用状态并保留位置。 */
    public void setEnabled(String id, boolean enabled) throws SQLException { profiles.setEnabled(id, enabled); }
    /** Saves a complete priority permutation transactionally. / 通过事务保存完整优先级排列。 */
    public void reorder(List<String> ids) throws SQLException { profiles.reorder(ids); }

    /** Preserves role prompts and validation while trying enabled providers in global order. / 保留角色提示与校验，按全局顺序尝试启用提供者。 */
    public Optional<AiRoleInvocationResult> invokeRole(AiRoleContext context, char[] masterPassword)
            throws SQLException, SecretStoreException {
        Objects.requireNonNull(context, "context");
        var result = chain.invoke(masterPassword, (profile, key) -> {
            var value = roleClient.invoke(new AiRoleBinding(context.role(), profile.id(), profile.chatCompletionsEndpoint(), profile.model()), key, context);
            return new AiProviderChain.Attempt<>(value, value.evidence().status(), value.evidence().validationDetail());
        });
        if (result.snapshot().isEmpty()) return Optional.empty();
        var evidence = result.value().map(AiRoleInvocationResult::evidence).orElseGet(() -> {
            var last = result.snapshot().getLast();
            return roleClient.invoke(new AiRoleBinding(context.role(), last.id(), last.chatCompletionsEndpoint(), last.model()), new char[0], context).evidence();
        });
        if (!result.valid()) evidence = new gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationEvidence(evidence.role(), evidence.providerId(),
                evidence.model(), evidence.redactedInputSummary(), evidence.inputSha256(), gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus.UNAVAILABLE,
                Optional.empty(), "all-enabled-providers-failed", evidence.observedAt());
        return Optional.of(new AiRoleInvocationResult(evidence, result.attempts()));
    }

    /** Retains the older explanation signature while honoring current global model ordering. / 保留旧解释签名，同时遵循当前全局模型顺序。 */
    public AiAnalysisOutcome explain(ReviewedSourcePreparation preparation, AiProfile profile,
                                     CredentialStorageMode mode, char[] masterPassword, String languageTag) {
        return explainReviewed(preparation, masterPassword, languageTag);
    }

    private AiAnalysisOutcome explainReviewed(ReviewedSourcePreparation preparation, char[] master, String languageTag) {
        if (preparation.assessment().facts().isEmpty()) {
            clear(master); return AiAnalysisOutcome.unavailable(LocalizedMessage.of("ai.status.analysisRequired"));
        }
        try {
            var result = chain.invoke(master, (profile, key) -> {
                try {
                    var value = new OpenAiCompatibleStructuralAnalysisClient().analyze(profile.chatCompletionsEndpoint(), profile.model(), key,
                            preparation.assessment().facts().orElseThrow(), AiResponseLanguageType.fromLanguageTag(languageTag));
                    return new AiProviderChain.Attempt<>(AiAnalysisOutcome.available(value.explanation()),
                            gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus.VALIDATED, "structural-schema-validated");
                } catch (AiAnalysisException failure) {
                    return new AiProviderChain.Attempt<>(AiAnalysisOutcome.unavailable(failure.failure().userMessage(), failure.failure().diagnostic()),
                            gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus.INVALID_OUTPUT, failure.failure().code());
                }
            });
            var value = result.valid() ? result.value().orElseThrow() : AiAnalysisOutcome.unavailable(
                    LocalizedMessage.of(result.snapshot().isEmpty() ? "ai.status.noEnabledProviders" : "ai.status.allProvidersFailed"));
            return new AiAnalysisOutcome(value.available(), value.status(), value.content(), value.diagnostic(), result.attempts());
        } catch (SQLException failure) {
            return AiAnalysisOutcome.unavailable(LocalizedMessage.of("ai.status.providerMissing"), "AI configuration snapshot could not be read");
        } finally { clear(master); }
    }

    /** Named legacy requests also use the global chain; role bindings are retained only as migration metadata. / 旧命名请求同样使用全局调用链，角色绑定仅作为迁移元数据保留。 */
    public AiAnalysisOutcome explainNamed(ReviewedSourcePreparation preparation, String providerId, char[] masterPassword, String languageTag) {
        return explainReviewed(preparation, masterPassword, languageTag);
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
