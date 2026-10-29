package gold.debug.windowstolinux.appapi.ai.model;

import java.util.Map;
import java.util.Objects;

/** Non-secret model inventory visible to Mods. / Mod 可见的不含秘密模型清单。
 * @param id platform configuration identity / 平台配置标识
 * @param name display name / 展示名称
 * @param model provider model name / 提供方型号
 * @param provider explicit manufacturer adapter / 显式厂商适配
 * @param protocol explicit wire protocol / 显式线协议
 * @param revision model configuration revision / 模型配置修订
 * @param capabilities actual capability verification states / 实际能力验证状态
 * @param reasoning documented controls and provenance / 已记录控制与来源
 * @param reasoningDefaults independent purpose defaults / 独立用途默认设置
 */
public record AiModelMetadata(String id, String name, String model, String provider, String protocol, long revision,
                              Map<String, String> capabilities, AiReasoningCapabilities reasoning,
                              Map<AiPurpose, AiReasoningMode> reasoningDefaults) {
    /** Captures inputs at the AiModelMetadata ownership boundary. / 在 AiModelMetadata 所有权边界捕获输入。
     * @param id exact platform model configuration identity / 精确平台模型配置标识
     * @param name captured name at this validated model boundary / 此已校验模型边界捕获的 name
     * @param model configured model identity or captured model metadata / 已配置型号或捕获的模型信息
     * @param provider explicit provider adapter identity / 显式厂商适配标识
     * @param protocol explicit wire protocol / 显式线协议
     * @param revision captured configuration revision or revision source / 已捕获配置修订或修订来源
     * @param capabilities captured capabilities at this validated model boundary / 此已校验模型边界捕获的 capabilities
     * @param reasoning captured reasoning control or settings owner / 捕获的推理控制或设置所有者
     * @param reasoningDefaults independent defaults for language, approval and vision / 语言、审批和视觉的独立默认值
     */
    public AiModelMetadata {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(model);
        Objects.requireNonNull(provider);
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(reasoning);
        capabilities = Map.copyOf(capabilities);
        reasoningDefaults = Map.copyOf(reasoningDefaults);
    }
}
