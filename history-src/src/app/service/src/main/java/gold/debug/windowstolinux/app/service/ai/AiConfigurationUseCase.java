package gold.debug.windowstolinux.app.service.ai;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.persistence.repository.AiProfileRepository;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.shared.ai.client.OpenAiCompatibleRoleClient;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiRoleBinding;
import gold.debug.windowstolinux.shared.ai.collaboration.role.ProjectAnalysisRoleContext;

/**
 * Validates a single selected model before saving; runtime requests use the global chain. / 保存前验证单个所选模型，运行时请求使用全局调用链。
 */
final class AiConfigurationUseCase {
    /**
     * Bound ai profile repository collaborator for profiles.
     * <p>处理配置资料集合的AI配置资料仓库协作对象。
     */
    private final AiProfileRepository profiles;

    /**
     * Bound desktop secret store service collaborator for credential references or scoped secret-access service.
     * <p>处理凭据引用或限定作用域的秘密访问服务的Desktop秘密存储服务协作对象。
     */
    private final DesktopSecretStoreService secrets;

    /**
     * Client.
     * <p>客户端。
     */
    private final OpenAiCompatibleRoleClient client;

    /**
     * Vision.
     * <p>视觉。
     */
    private final gold.debug.windowstolinux.shared.ai.recovery.RecoveryModelClient vision;
    /**
     * Initializes ai configuration use case through its shared constructor contract.
     * <p>通过共享构造契约初始化AI配置用例。
     *
     * @param profiles profiles / 配置资料集合
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param client client / 客户端
     */
    AiConfigurationUseCase(AiProfileRepository profiles, DesktopSecretStoreService secrets,
            OpenAiCompatibleRoleClient client) {
        this(profiles, secrets, client, new gold.debug.windowstolinux.shared.ai.recovery.RecoveryModelClient());
    }

    /**
     * Binds the supplied dependencies and state for ai configuration use case.
     * <p>为AI配置用例绑定传入的依赖及状态。
     *
     * @param profiles profiles / 配置资料集合
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param client client / 客户端
     * @param vision vision / 视觉
     */
    AiConfigurationUseCase(AiProfileRepository profiles, DesktopSecretStoreService secrets,
            OpenAiCompatibleRoleClient client,
            gold.debug.windowstolinux.shared.ai.recovery.RecoveryModelClient vision) {
        this.profiles = profiles;
        this.secrets = secrets;
        this.client = client;
        this.vision = vision;
    }

    /**
     * Validates the model configuration and saves its provider metadata and protected credential through separate stores.
     * <p>校验模型配置，并通过各自存储保存提供者元数据及受保护凭据。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param suppliedKey API credential characters supplied to the selected operation / 提供给所选操作的 API 凭据字符
     * @param capability independent model capability whose order and enablement apply / 独立模型分组，其顺序及启用设置分别生效
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    void save(AiProviderProfile profile, String name, char[] suppliedKey,
            gold.debug.windowstolinux.shared.model.ai.AiCapabilityType capability)
            throws SQLException, SecretStoreException {
        char[] key = null;
        try {
            AiProviderChain.checkCancelled();
            if (name.isBlank() || name.length() > 120 || name.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("invalid model display name");
            var priorConfiguration = profiles.listConfigured().stream()
                    .filter(value -> value.profile().id().equals(profile.id())).findFirst();
            var prior = priorConfiguration.map(value -> value.profile());
            try (var store = secrets.open()) {
                if (suppliedKey.length > 0)
                    key = suppliedKey.clone();
                else {
                    var saved = prior
                            .orElseThrow(() -> new IllegalArgumentException("API key is required for a new model"));
                    if (!saved.credentialKey().equals(profile.credentialKey()))
                        throw new IllegalArgumentException("changing credential storage requires an API key");
                    key = existingKey(store, priorConfiguration.orElseThrow());
                }
                AiProviderChain.checkCancelled();
                var context = new ProjectAnalysisRoleContext("connection-test", "JAVA_MAVEN_SPRING_BOOT",
                        "MAVEN_WRAPPER", "FORMALLY_SUPPORTED", List.of());
                var probe = capability == gold.debug.windowstolinux.shared.model.ai.AiCapabilityType.VISION
                        ? null
                        : client.invoke(new AiRoleBinding(context.role(), profile.id(),
                                profile.chatCompletionsEndpoint(), profile.model()), key, context);
                AiProviderChain.checkCancelled();
                if (capability == gold.debug.windowstolinux.shared.model.ai.AiCapabilityType.VISION) {
                    try {
                        vision.probeVision(profile.chatCompletionsEndpoint(), profile.model(), key);
                    } catch (IllegalArgumentException failure) {
                        throw ApplicationServiceException.create(
                                ApplicationServiceFailureType.AI_CONFIGURATION_TEST_FAILED,
                                "Selected model image test failed");
                    }
                }
                if (probe != null && probe.evidence().status() != AiInvocationStatus.VALIDATED)
                    throw ApplicationServiceException.create(ApplicationServiceFailureType.AI_CONFIGURATION_TEST_FAILED,
                            "Selected model probe failed: " + probe.evidence().validationDetail());
                String credential = prior.map(value -> value.credentialKey())
                        .orElseGet(() -> "ai/provider/" + profile.id() + "/api-key/" + UUID.randomUUID());
                var saved = new AiProviderProfile(profile.id(), profile.chatCompletionsEndpoint(), profile.model(),
                        credential);
                char[] verifiedKey = key;
                AiProviderChain.checkCancelled();
                store.transaction(() -> {
                    var current = profiles.listConfigured().stream()
                            .filter(value -> value.profile().id().equals(profile.id())).findFirst();
                    if (current.isPresent() != priorConfiguration.isPresent() || current.isPresent()
                            && (!current.get().profile().equals(priorConfiguration.get().profile())
                                    || current.get().revision() != priorConfiguration.get().revision()))
                        throw new SQLException("model changed during configuration probe");
                    store.save(credential, verifiedKey);
                    profiles.saveVerified(saved.stored(), name, Instant.now(), capability);
                });
            }
        } finally {
            if (key != null)
                Arrays.fill(key, '\0');
            Arrays.fill(suppliedKey, '\0');

        }
    }

    /**
     * Loads the selected model's protected key, performs an image challenge and clears the key.
     * <p>加载所选模型受保护密钥、执行图像挑战，并清空密钥。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @param capability separately tested text or vision capability / 分别测试的文本或视觉能力
     */
    void testCapability(String id, gold.debug.windowstolinux.shared.model.ai.AiCapabilityType capability)
            throws Exception {
        char[] key = null;
        try {
            var configuration = profiles.listConfigured().stream().filter(value -> value.profile().id().equals(id))
                    .findFirst().orElseThrow();
            var profile = AiProviderProfile.fromStored(configuration.profile());
            try (var store = secrets.open()) {
                key = store.read(profile.credentialKey()).orElseThrow();
                if (capability == gold.debug.windowstolinux.shared.model.ai.AiCapabilityType.VISION)
                    vision.probeVision(profile.chatCompletionsEndpoint(), profile.model(), key);
                else {
                    var context = new ProjectAnalysisRoleContext("connection-test", "JAVA_MAVEN_SPRING_BOOT",
                            "MAVEN_WRAPPER", "FORMALLY_SUPPORTED", List.of());
                    var result = client.invoke(new AiRoleBinding(context.role(), profile.id(),
                            profile.chatCompletionsEndpoint(), profile.model()), key, context);
                    if (result.evidence().status() != AiInvocationStatus.VALIDATED)
                        throw new IllegalArgumentException("text capability probe failed");
                }
                AiProviderChain.checkCancelled();
                profiles.recordVerification(profile.stored(), configuration.revision(), capability, Instant.now());
            }
        } finally {
            if (key != null)
                Arrays.fill(key, '\0');

        }
    }

    /** Captures the previous key only while its model revision still matches. / 仅在模型修订仍匹配时取得原密钥。
     * @param store scoped credential access / 限定凭据访问
     * @param expected configuration observed before the edit / 编辑前观测的配置
     * @return caller-owned API key / 调用方持有的 API Key
     * @throws SQLException when the model changed / 模型变化时
     * @throws gold.debug.windowstolinux.app.secret.SecretStoreException when credentials cannot be read / 无法读取凭据时
     */
    private char[] existingKey(gold.debug.windowstolinux.app.secret.SecretStore store,
            gold.debug.windowstolinux.app.db.entity.StoredAiProviderConfiguration expected)
            throws SQLException, gold.debug.windowstolinux.app.secret.SecretStoreException {
        char[][] captured = new char[1][];
        boolean success = false;
        try {
            store.transaction(() -> {
                var current = profiles.listConfigured().stream().filter(
                        value -> value.profile().equals(expected.profile()) && value.revision() == expected.revision())
                        .findFirst();
                if (current.isEmpty())
                    throw new SQLException("model changed before configuration probe");
                captured[0] = store.read(expected.profile().credentialKey())
                        .orElseThrow(() -> new IllegalArgumentException("saved API key is unavailable"));
            });
            success = true;
            return captured[0];
        } finally {
            if (!success && captured[0] != null)
                Arrays.fill(captured[0], '\0');
        }
    }
}
