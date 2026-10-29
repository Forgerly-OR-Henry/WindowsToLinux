package gold.debug.windowstolinux.appapi.ai.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Static documented controls with provenance. / 带来源的静态官方控制能力。
 * @param known whether the exact model and protocol are documented / 是否收录确切型号与协议
 * @param options distinct options including DEFAULT / 包含 DEFAULT 的不同选项
 * @param mediumEquivalent documented equivalent for medium / 官方中等强度映射
 * @param source official document URL, empty for unknown / 官方文档地址，未知时为空
 * @param checkedOn source verification date / 来源核对日期
 */
public record AiReasoningCapabilities(boolean known, List<AiReasoningMode> options,
                                      Optional<AiReasoningMode> mediumEquivalent, String source, String checkedOn) {
    /** Captures inputs at the AiReasoningCapabilities ownership boundary. / 在 AiReasoningCapabilities 所有权边界捕获输入。
     * @param known captured known at this validated model boundary / 此已校验模型边界捕获的 known
     * @param options captured options at this validated model boundary / 此已校验模型边界捕获的 options
     * @param mediumEquivalent captured mediumEquivalent at this validated model boundary / 此已校验模型边界捕获的 mediumEquivalent
     * @param source official documentation source / 官方文档来源
     * @param checkedOn captured checkedOn at this validated model boundary / 此已校验模型边界捕获的 checkedOn
     */
    public AiReasoningCapabilities {
        options = List.copyOf(options);
        Objects.requireNonNull(mediumEquivalent);
        Objects.requireNonNull(source);
        Objects.requireNonNull(checkedOn);
        if (!options.contains(AiReasoningMode.DEFAULT) || options.stream().distinct().count() != options.size()
            || mediumEquivalent.isPresent() && !options.contains(mediumEquivalent.orElseThrow()))
            throw new IllegalArgumentException("Invalid reasoning capabilities");
    }
}
