package gold.debug.windowstolinux.app.ai.provider.adapter;

import java.util.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.provider.*;
import gold.debug.windowstolinux.appapi.ai.model.*;

/** Shared mechanics for explicitly enumerated documented rules. / 显式枚举规则的公共机械处理。
 */
final class ModelReasoningRules {
    /** Captures inputs at the ModelReasoningRules ownership boundary. / 在 ModelReasoningRules 所有权边界捕获输入。
     */
    private ModelReasoningRules() {
    }

    /** Describes unknown support without claiming effort levels. / 描述未知支持状态，不声明强度档位。
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    static AiReasoningCapabilities unknown() {
        return new AiReasoningCapabilities(false, List.of(AiReasoningMode.DEFAULT), Optional.empty(), "", "");
    }

    /** Builds documented controls with a pinned source verification date. / 构造带固定来源核对日期的已记录控制。
     * @param source official documentation source / 官方文档来源
     * @param medium documented equivalent of medium, if any / 已记录的中等映射，可为空
     * @param controls documented native options in display order / 按展示顺序排列的官方原生选项
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    static AiReasoningCapabilities known(String source, AiReasoningMode medium, AiReasoningMode... controls) {
        var options = new ArrayList<AiReasoningMode>();
        options.add(AiReasoningMode.DEFAULT);
        options.addAll(List.of(controls));
        return new AiReasoningCapabilities(true, options, Optional.ofNullable(medium), source, "2026-10-30");
    }

    /** Translates the selected control before final wire-size validation. / 在最终线大小校验前转换所选控制。
     * @param body complete mutable wire object before final serialization / 最终序列化前的完整可变线对象
     * @param protocol explicit wire protocol / 显式线协议
     * @param setting requested reasoning control / 请求的推理控制
     */
    static void apply(ObjectNode body, ModelProtocol protocol, AiReasoningMode setting) {
        if (setting == AiReasoningMode.DEFAULT) return;
        String effort = setting == AiReasoningMode.STANDARD ? "none" : setting.name().toLowerCase(Locale.ROOT);
        switch (protocol) {
            case OPENAI_CHAT_COMPLETIONS -> body.put("reasoning_effort", effort);
            case OPENAI_RESPONSES -> body.putObject("reasoning").put("effort", effort);
            case ANTHROPIC_MESSAGES -> {
                if (setting == AiReasoningMode.STANDARD) body.putObject("thinking").put("type", "disabled");
                else body.putObject("output_config").put("effort", effort);
            }
        }
    }
}
