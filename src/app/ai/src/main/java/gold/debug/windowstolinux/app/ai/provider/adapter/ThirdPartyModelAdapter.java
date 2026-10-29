package gold.debug.windowstolinux.app.ai.provider.adapter;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.provider.*;
import gold.debug.windowstolinux.appapi.ai.model.*;

/** Generic protocol mapping without model capability claims. / 通用协议映射，不声明型号能力。
 */
public final class ThirdPartyModelAdapter implements ModelProviderAdapter {
    /** Identifies the explicitly selected manufacturer adapter. / 标识明确选择的厂商适配。
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    @Override
    public ModelProviderType type() {
        return ModelProviderType.THIRD_PARTY;
    }

    /** Returns controls documented for this exact model and protocol. / 返回确切型号与协议的已记录控制。
     * @param model configured model identity or captured model metadata / 已配置型号或捕获的模型信息
     * @param protocol explicit wire protocol / 显式线协议
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    @Override
    public AiReasoningCapabilities capabilities(String model, ModelProtocol protocol) {
        return ModelReasoningRules.unknown();
    }

    /** Translates the selected control before final wire-size validation. / 在最终线大小校验前转换所选控制。
     * @param body complete mutable wire object before final serialization / 最终序列化前的完整可变线对象
     * @param model configured model identity or captured model metadata / 已配置型号或捕获的模型信息
     * @param protocol explicit wire protocol / 显式线协议
     * @param setting requested reasoning control / 请求的推理控制
     */
    @Override
    public void apply(ObjectNode body, String model, ModelProtocol protocol, AiReasoningMode setting) {
        ModelReasoningRules.apply(body, protocol, setting);
    }
}
