package gold.debug.windowstolinux.app.ai.provider.adapter;

import java.util.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.provider.*;
import gold.debug.windowstolinux.appapi.ai.model.*;

/** Explicit DeepSeek rules; exact aliases only, never endpoint inference. / 显式型号规则，不从端点推测。
 */
public final class DeepSeekModelAdapter implements ModelProviderAdapter {
    /** Official rule provenance; reviewed when the static catalog changes. / 官方规则来源，静态目录变化时复核。
     */
    private static final String SOURCE = "https://api-docs.deepseek.com/guides/thinking_mode/";

    /** Identifies the explicitly selected manufacturer adapter. / 标识明确选择的厂商适配。
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    @Override
    public ModelProviderType type() {
        return ModelProviderType.DEEPSEEK;
    }

    /** Returns controls documented for this exact model and protocol. / 返回确切型号与协议的已记录控制。
     * @param model configured model identity or captured model metadata / 已配置型号或捕获的模型信息
     * @param protocol explicit wire protocol / 显式线协议
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    @Override
    public AiReasoningCapabilities capabilities(String model, ModelProtocol protocol) {
        if (Set.of("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro").contains(model))
            return ModelReasoningRules.known(SOURCE, AiReasoningMode.HIGH, AiReasoningMode.STANDARD, AiReasoningMode.LOW, AiReasoningMode.HIGH, AiReasoningMode.MAX);
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
        if (!capabilities(model, protocol).known()) {
            ModelReasoningRules.apply(body, protocol, setting);
            return;
        }
        if (capabilities(model, protocol).known() && protocol == ModelProtocol.OPENAI_CHAT_COMPLETIONS && setting != AiReasoningMode.STANDARD
            && (body.path("tool_choice").isObject() || body.path("tool_choice").asText().equals("required")))
            throw new ModelProviderRequestException("DeepSeek thinking mode does not support forced tool choice");
        if (setting == AiReasoningMode.DEFAULT) return;
        if (protocol == ModelProtocol.OPENAI_RESPONSES) {
            ModelReasoningRules.apply(body, protocol, setting);
        } else {
            body.putObject("thinking").put("type", setting == AiReasoningMode.STANDARD ? "disabled" : "enabled");
            if (setting.level()) ModelReasoningRules.apply(body, protocol, setting);
        }
    }
}
