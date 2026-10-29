package gold.debug.windowstolinux.app.ai.provider;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.appapi.ai.model.AiReasoningCapabilities;
import gold.debug.windowstolinux.appapi.ai.model.AiReasoningMode;

/** Chooses injected implementations without importing implementation packages. / 选择注入的实现，不引用实现包。
 */
public final class ModelProviderRegistry {
    /** Immutable implementations supplied by startup assembly. / 启动装配提供的不可变实现。
     */
    private final Map<ModelProviderType, ModelProviderAdapter> adapters;

    /** Captures inputs at the ModelProviderRegistry ownership boundary. / 在 ModelProviderRegistry 所有权边界捕获输入。
     * @param values validated controls or implementations supplied by the owner / 所有者提供的已校验控制或实现
     */
    public ModelProviderRegistry(Collection<ModelProviderAdapter> values) {
        var registered = new EnumMap<ModelProviderType, ModelProviderAdapter>(ModelProviderType.class);
        for (var value : values)
            if (registered.put(value.type(), value) != null) throw new IllegalArgumentException("Duplicate provider");
        if (registered.size() != ModelProviderType.values().length)
            throw new IllegalArgumentException("Missing provider");
        adapters = Map.copyOf(registered);
    }

    /** Resolves the implementation from the injected complete registry. / 从注入的完整注册表解析实现。
     * @param type explicit provider adapter type / 显式厂商适配类型
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    public ModelProviderAdapter adapter(ModelProviderType type) {
        return adapters.get(type);
    }

    /** Returns controls documented for this exact model and protocol. / 返回确切型号与协议的已记录控制。
     * @param type explicit provider adapter type / 显式厂商适配类型
     * @param model configured model identity or captured model metadata / 已配置型号或捕获的模型信息
     * @param protocol explicit wire protocol / 显式线协议
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    public AiReasoningCapabilities capabilities(ModelProviderType type, String model, ModelProtocol protocol) {
        return adapter(type).capabilities(model, protocol);
    }

    /** Resolves documented initialization without overwriting stored choices. / 根据文档解析初值，不覆盖已存选择。
     * @param type explicit provider adapter type / 显式厂商适配类型
     * @param model configured model identity or captured model metadata / 已配置型号或捕获的模型信息
     * @param protocol explicit wire protocol / 显式线协议
     * @param level configured initial effort target / 配置的初始强度目标
     * @param mode configured initial binary-mode choice / 配置的初始双模式选项
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    public AiReasoningMode initial(ModelProviderType type, String model, ModelProtocol protocol, String level, String mode) {
        var capability = capabilities(type, model, protocol);
        if (!capability.known()) return AiReasoningMode.DEFAULT;
        var selected = AiReasoningMode.valueOf(level);
        if (capability.options().contains(selected)) return selected;
        if (selected == AiReasoningMode.MEDIUM && capability.mediumEquivalent().isPresent())
            return capability.mediumEquivalent().orElseThrow();
        if (capability.options().contains(AiReasoningMode.STANDARD)
            && capability.options().contains(AiReasoningMode.THINKING)) return AiReasoningMode.valueOf(mode);
        return AiReasoningMode.DEFAULT;
    }
}
