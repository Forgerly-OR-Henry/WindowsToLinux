package gold.debug.windowstolinux.app.ai.provider.adapter;

import java.util.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.provider.*;
import gold.debug.windowstolinux.appapi.ai.model.*;

/** Explicit OpenAi rules; exact aliases only, never endpoint inference. / 显式型号规则，不从端点推测。
 */
public final class OpenAiModelAdapter implements ModelProviderAdapter {
    /** Official rule provenance; reviewed when the static catalog changes. / 官方规则来源，静态目录变化时复核。
     */
    private static final String SOURCE = "https://developers.openai.com/api/docs/guides/reasoning";

    /** Identifies the explicitly selected manufacturer adapter. / 标识明确选择的厂商适配。
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    @Override
    public ModelProviderType type() {
        return ModelProviderType.OPENAI;
    }

    /** Returns controls documented for this exact model and protocol. / 返回确切型号与协议的已记录控制。
     * @param model configured model identity or captured model metadata / 已配置型号或捕获的模型信息
     * @param protocol explicit wire protocol / 显式线协议
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    @Override
    public AiReasoningCapabilities capabilities(String model, ModelProtocol protocol) {
        if (protocol == ModelProtocol.ANTHROPIC_MESSAGES) return ModelReasoningRules.unknown();
        if (Set.of("gpt-6-astra", "gpt-6.1-sol").contains(model))
            return ModelReasoningRules.known(SOURCE, null, AiReasoningMode.LOW, AiReasoningMode.MEDIUM, AiReasoningMode.HIGH, AiReasoningMode.XHIGH, AiReasoningMode.MAX);
        if (Set.of("gpt-6-sol", "gpt-6-luna").contains(model))
            return ModelReasoningRules.known(SOURCE, null, AiReasoningMode.STANDARD, AiReasoningMode.LOW, AiReasoningMode.MEDIUM, AiReasoningMode.HIGH, AiReasoningMode.XHIGH, AiReasoningMode.MAX);
        if (Set.of("o3", "o3-mini", "o4-mini").contains(model))
            return ModelReasoningRules.known(SOURCE, null, AiReasoningMode.LOW, AiReasoningMode.MEDIUM, AiReasoningMode.HIGH);
        if (Set.of("gpt-5", "gpt-5-mini", "gpt-5-nano").contains(model))
            return ModelReasoningRules.known(SOURCE, null, AiReasoningMode.MINIMAL, AiReasoningMode.LOW, AiReasoningMode.MEDIUM, AiReasoningMode.HIGH);
        if (model.equals("gpt-5.1"))
            return ModelReasoningRules.known(SOURCE, null, AiReasoningMode.STANDARD, AiReasoningMode.LOW, AiReasoningMode.MEDIUM, AiReasoningMode.HIGH);
        if (Set.of("gpt-5.2", "gpt-5.4").contains(model))
            return ModelReasoningRules.known(SOURCE, null, AiReasoningMode.STANDARD, AiReasoningMode.LOW, AiReasoningMode.MEDIUM, AiReasoningMode.HIGH, AiReasoningMode.XHIGH);
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
        if (protocol == ModelProtocol.OPENAI_CHAT_COMPLETIONS && capabilities(model, protocol).known()) {
            body.set("max_completion_tokens", body.remove("max_tokens"));
            if (!body.path("tools").isEmpty() && (Set.of("gpt-6-astra", "gpt-6.1-sol").contains(model)
                || Set.of("gpt-6-sol", "gpt-6-luna").contains(model) && setting != AiReasoningMode.STANDARD))
                throw new ModelProviderRequestException("This model requires Responses for tools at the selected reasoning setting");
        }
        ModelReasoningRules.apply(body, protocol, setting);
    }
}
