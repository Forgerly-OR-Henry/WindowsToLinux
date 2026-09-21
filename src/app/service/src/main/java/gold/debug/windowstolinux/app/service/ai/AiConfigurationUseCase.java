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
    AiConfigurationUseCase(AiProfileRepository profiles, DesktopSecretStoreService secrets, OpenAiCompatibleRoleClient client) {
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
    AiConfigurationUseCase(AiProfileRepository profiles, DesktopSecretStoreService secrets, OpenAiCompatibleRoleClient client,
            gold.debug.windowstolinux.shared.ai.recovery.RecoveryModelClient vision) {
        this.profiles = profiles; this.secrets = secrets; this.client = client; this.vision = vision;
    }

    /**
     * Validates the model configuration and saves its provider metadata and protected credential through separate stores.
     * <p>校验模型配置，并通过各自存储保存提供者元数据及受保护凭据。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param suppliedKey API credential characters supplied to the selected operation / 提供给所选操作的 API 凭据字符
     * @param capability independent model capability whose order and enablement apply / 独立模型分组，其顺序及启用设置分别生效
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    void save(AiProviderProfile profile, String name, char[] master, char[] suppliedKey, gold.debug.windowstolinux.shared.model.ai.AiCapabilityType capability) throws SQLException, SecretStoreException {
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
                var probe = capability == gold.debug.windowstolinux.shared.model.ai.AiCapabilityType.VISION ? null : client.invoke(new AiRoleBinding(context.role(), profile.id(), profile.chatCompletionsEndpoint(), profile.model()), key, context);
                AiProviderChain.checkCancelled();
                if (capability == gold.debug.windowstolinux.shared.model.ai.AiCapabilityType.VISION) {
                    try { vision.probeVision(profile.chatCompletionsEndpoint(), profile.model(), key); }
                    catch (IllegalArgumentException failure) { throw ApplicationServiceException.create(ApplicationServiceFailureType.AI_CONFIGURATION_TEST_FAILED, "Selected model image test failed"); }
                }
                if (probe != null && probe.evidence().status() != AiInvocationStatus.VALIDATED)
                    throw ApplicationServiceException.create(ApplicationServiceFailureType.AI_CONFIGURATION_TEST_FAILED,
                            "Selected model probe failed: " + probe.evidence().validationDetail());
                String credential = suppliedKey.length > 0 ? "ai/provider/" + profile.id() + "/api-key/" + UUID.randomUUID() : profile.credentialKey();
                if (suppliedKey.length > 0) store.save(credential, key);
                var saved = new AiProviderProfile(profile.id(), profile.chatCompletionsEndpoint(), profile.model(), credential, profile.credentialMode());
                AiProviderChain.checkCancelled(); profiles.saveVerified(saved.stored(), name, Instant.now(), capability);
            }
        } finally { if (key != null) Arrays.fill(key, '\0'); Arrays.fill(suppliedKey, '\0'); Arrays.fill(master, '\0'); }
    }
    /**
     * Loads the selected model's protected key, performs an image challenge and clears both the key and supplied unlock password.
     * <p>加载所选模型受保护密钥、执行图像挑战，并清空密钥及传入解锁密码。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @param capability separately tested text or vision capability / 分别测试的文本或视觉能力
     */
    void testCapability(String id, gold.debug.windowstolinux.shared.model.ai.AiCapabilityType capability, char[] master) throws Exception {
        char[] key = null;
        try {
            var profile = AiProviderProfile.fromStored(profiles.findNamed(id).orElseThrow());
            try (var store = secrets.open(profile.credentialMode(), master)) {
                key = store.read(profile.credentialKey()).orElseThrow();
                if (capability == gold.debug.windowstolinux.shared.model.ai.AiCapabilityType.VISION)
                    vision.probeVision(profile.chatCompletionsEndpoint(), profile.model(), key);
                else {
                    var context = new ProjectAnalysisRoleContext("connection-test", "JAVA_MAVEN_SPRING_BOOT", "MAVEN_WRAPPER", "FORMALLY_SUPPORTED", List.of());
                    var result = client.invoke(new AiRoleBinding(context.role(), profile.id(), profile.chatCompletionsEndpoint(), profile.model()), key, context);
                    if (result.evidence().status() != AiInvocationStatus.VALIDATED) throw new IllegalArgumentException("text capability probe failed");
                }
                AiProviderChain.checkCancelled();
                profiles.recordVerification(profile.stored(), capability, Instant.now());
            }
        } finally { if (key != null) Arrays.fill(key, '\0'); Arrays.fill(master, '\0'); }
    }
}
