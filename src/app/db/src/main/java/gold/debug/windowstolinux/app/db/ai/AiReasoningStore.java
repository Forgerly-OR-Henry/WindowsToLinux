package gold.debug.windowstolinux.app.db.ai;

import java.sql.SQLException;
import java.util.Map;

import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.app.db.connect.StoreConnections;
import gold.debug.windowstolinux.app.db.setting.DesktopPreferenceStore;
import gold.debug.windowstolinux.appapi.ai.model.*;

/** Atomic model-purpose preference ownership and one-time initialization. / 原子模型用途偏好及首次初始化。
 */
public final class AiReasoningStore {
    /** Captured connections for this model capability boundary. / 此模型能力边界捕获的 connections。
     */
    private final StoreConnections connections;

    /** Captures inputs at the AiReasoningStore ownership boundary. / 在 AiReasoningStore 所有权边界捕获输入。
     * @param connections shared scoped database transaction owner / 共用限定数据库事务所有者
     */
    public AiReasoningStore(StoreConnections connections) {
        this.connections = connections;
    }

    /** Maintains the validated model preference namespace. / 维护已校验模型偏好命名空间。
     * @param id exact platform model configuration identity / 精确平台模型配置标识
     */
    private static void validateId(String id) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid model identity");
    }

    /** Maintains the validated model preference namespace. / 维护已校验模型偏好命名空间。
     * @param id exact platform model configuration identity / 精确平台模型配置标识
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    static String legacyKey(String id) {
        validateId(id);
        return "ai_model_reasoning_effort_" + id.replace('-', '_');
    }

    /** Commits purpose defaults against the exact captured model row. / 按精确捕获的模型行提交用途设置。
     * @param id exact platform model configuration identity / 精确平台模型配置标识
     * @param revision captured configuration revision or revision source / 已捕获配置修订或修订来源
     * @param values validated controls or implementations supplied by the owner / 所有者提供的已校验控制或实现
     * @throws SQLException if original admission, storage or configuration validation fails / 原准入、存储或配置校验失败时
     */
    public void save(String id, long revision, Map<AiPurpose, AiReasoningMode> values) throws SQLException {
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                StoreConnections.lockPlatformState(connection);
                var current = new AiModelStore(connections).listConfigured().stream().filter(value -> value.profile().id().equals(id)).findFirst()
                    .orElseThrow(() -> new SQLException("Model is absent", "02000"));
                if (current.revision() != revision)
                    throw new SQLException("Model configuration changed; reload", "40001");
                var profile = current.profile();
                new AiModelStore(connections).saveConfiguration(new StoredAiModelProfile(profile.id(), profile.endpoint(), profile.model(), profile.credentialKey(),
                    profile.reasoningEffort(), profile.protocol(), profile.provider(), values), current.name());
            }
        });
    }

    /** Converts the old single-value vocabulary without changing its intent. / 转换旧单值词表，保持原有意图。
     * @param effort explicit legacy protocol effort / 显式旧协议强度
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    public static AiReasoningMode legacyReasoning(ModelReasoningLevel effort) {
        return effort == ModelReasoningLevel.NONE ? AiReasoningMode.STANDARD : AiReasoningMode.valueOf(effort.name());
    }

    /** Maintains the validated model preference namespace. / 维护已校验模型偏好命名空间。
     * @param id exact platform model configuration identity / 精确平台模型配置标识
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    static String providerKey(String id) {
        validateId(id);
        return "ai_model_provider_" + id.replace('-', '_');
    }

    /** Maintains the validated model preference namespace. / 维护已校验模型偏好命名空间。
     * @param id exact platform model configuration identity / 精确平台模型配置标识
     * @param purpose declared invocation purpose / 声明的调用用途
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    static String purposeReasoningKey(String id, AiPurpose purpose) {
        validateId(id);
        return "ai_model_reasoning_" + purpose.name().toLowerCase(java.util.Locale.ROOT) + "_" + id.replace('-', '_');
    }

    /** Initializes absent purpose rows atomically; a legacy row wins only once. / 原子初始化缺失用途，旧值只用于首次迁移。
     * @param id exact platform model configuration identity / 精确平台模型配置标识
     * @param defaults initialization values used only for absent preferences / 仅用于缺失偏好的初始化值
     * @throws SQLException if original admission, storage or configuration validation fails / 原准入、存储或配置校验失败时
     */
    public void initialize(String id, Map<AiPurpose, AiReasoningMode> defaults) throws SQLException {
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                StoreConnections.lockPlatformState(connection);
                var preferences = new DesktopPreferenceStore(connections);
                var legacy = preferences.find(legacyKey(id));
                var store = new AiModelStore(connections);
                var current = store.listConfigured().stream().filter(value -> value.profile().id().equals(id)).findFirst()
                    .orElseThrow(() -> new SQLException("Model is absent", "02000"));
                var values = new java.util.EnumMap<AiPurpose, AiReasoningMode>(AiPurpose.class);
                values.putAll(current.profile().reasoningDefaults());
                boolean missing = false;
                for (var entry : defaults.entrySet()) {
                    String key = purposeReasoningKey(id, entry.getKey());
                    if (preferences.find(key).isEmpty()) {
                        var value = legacy.isPresent() ? legacyReasoning(ModelReasoningLevel.valueOf(legacy.orElseThrow())) : entry.getValue();
                        values.put(entry.getKey(), value);
                        missing = true;
                    }
                }
                if (missing) {
                    var profile = current.profile();
                    store.saveConfiguration(new StoredAiModelProfile(profile.id(), profile.endpoint(), profile.model(), profile.credentialKey(),
                        profile.reasoningEffort(), profile.protocol(), profile.provider(), values), current.name());
                }
            }
        });
    }

}
