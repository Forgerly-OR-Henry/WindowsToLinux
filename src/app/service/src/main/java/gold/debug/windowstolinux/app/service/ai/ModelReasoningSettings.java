package gold.debug.windowstolinux.app.service.ai;

import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.ai.provider.*;
import gold.debug.windowstolinux.app.config.AppConfig;
import gold.debug.windowstolinux.app.db.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.*;

/** One source for initialization, metadata and actual request controls. / 初始化、元数据和实际请求控制的同一来源。
 */
public final class ModelReasoningSettings {
    /** Authoritative inventory used inside the owning task or transaction. / 所属任务或事务内使用的权威清单。
     */
    private final AiModelStore profiles;

    /** Injected exact-model rule registry, independent of the endpoint. / 独立于端点的注入确切型号规则表。
     */
    private final ModelProviderRegistry providers;

    /** Seeds applicable purposes from the same legacy selection. / 从同一旧选择初始化适用用途。
     */
    private final AppConfig.Reasoning defaults;

    /** Captures inputs at the ModelReasoningSettings ownership boundary. / 在 ModelReasoningSettings 所有权边界捕获输入。
     * @param profiles authoritative model inventory repository / 权威模型清单仓库
     * @param providers complete injected provider registry / 注入的完整厂商注册表
     * @param defaults initialization values used only for absent preferences / 仅用于缺失偏好的初始化值
     */
    public ModelReasoningSettings(AiModelStore profiles, ModelProviderRegistry providers, AppConfig.Reasoning defaults) {
        this.profiles = profiles;
        this.providers = providers;
        this.defaults = defaults;
    }

    /** Resolves documented initialization without overwriting stored choices. / 根据文档解析初值，不覆盖已存选择。
     * @param profile captured non-secret connection configuration / 已捕获非秘密连接配置
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    public Map<AiPurpose, AiReasoningMode> initial(AiModelProfile profile) {
        var setting = providers.initial(profile.provider(), profile.model(), profile.protocol(), defaults.defaultLevel(), defaults.defaultMode());
        return Map.of(AiPurpose.LANGUAGE, setting, AiPurpose.APPROVAL, setting, AiPurpose.VISION, setting);
    }

    /** Fills absent purpose preferences using the legacy seed or configured defaults. / 以旧值或配置默认填充缺失用途偏好。
     * @throws SQLException if original admission, storage or configuration validation fails / 原准入、存储或配置校验失败时
     */
    public void initialize() throws SQLException {
        for (var model : profiles.listConfigured())
            profiles.reasoning().initialize(model.profile().id(), initial(AiModelProfile.fromStored(model.profile())));
    }

    /** Returns controls documented for this exact model and protocol. / 返回确切型号与协议的已记录控制。
     * @param profile captured non-secret connection configuration / 已捕获非秘密连接配置
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    public AiReasoningCapabilities capabilities(AiModelProfile profile) {
        return providers.capabilities(profile.provider(), profile.model(), profile.protocol());
    }

    /** Rejects an unsupported explicit control without reducing effort. / 拒绝不支持的明确控制，不降低强度。
     * @param profile captured non-secret connection configuration / 已捕获非秘密连接配置
     * @param setting requested reasoning control / 请求的推理控制
     * @param explicit whether the caller explicitly chose this control / 调用方是否明确选择此控制
     */
    public void validate(AiModelProfile profile, AiReasoningMode setting, boolean explicit) {
        var capability = capabilities(profile);
        if (!capability.options().contains(setting) && (capability.known() || explicit))
            throw new IllegalArgumentException("Reasoning setting is not supported by this model and protocol");
    }

    /** Translates the selected control before final wire-size validation. / 在最终线大小校验前转换所选控制。
     * @param body complete mutable wire object before final serialization / 最终序列化前的完整可变线对象
     * @param profile captured non-secret connection configuration / 已捕获非秘密连接配置
     * @param setting requested reasoning control / 请求的推理控制
     */
    public void apply(com.fasterxml.jackson.databind.node.ObjectNode body, StoredAiModelProfile profile, AiReasoningMode setting) {
        providers.adapter(profile.provider()).apply(body, profile.model(), profile.protocol(), setting);
    }

    /** Projects captured model evidence without credentials or endpoint secrets. / 投影捕获的模型证据，不包含凭据或端点秘密。
     * @param model configured model identity or captured model metadata / 已配置型号或捕获的模型信息
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    public AiModelMetadata metadata(StoredAiModelInfo model) {
        var states = new LinkedHashMap<String, String>();
        model.checks().forEach((capability, check) -> states.put(capability.name(), check.state().name()));
        var profile = model.profile();
        return new AiModelMetadata(profile.id(), model.name(), profile.model(), profile.provider().name(), profile.protocol().name(),
            model.revision(), states, capabilities(AiModelProfile.fromStored(profile)), profile.reasoningDefaults());
    }
}
