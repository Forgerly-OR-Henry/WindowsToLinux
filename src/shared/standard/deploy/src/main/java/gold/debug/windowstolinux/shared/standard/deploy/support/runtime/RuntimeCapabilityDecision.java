package gold.debug.windowstolinux.shared.standard.deploy.support.runtime;

import java.util.Objects;
import java.util.Optional;

/**
 * Indicates whether one reviewed runtime matches collected host capabilities. / 表示一个已审阅运行时是否匹配采集到的主机能力。
 *
 * @param supported supported / 受支持
 * @param detail detail / 详情
 */
public record RuntimeCapabilityDecision(boolean supported, Optional<String> detail) {
    /**
     * Creates a consistent supported or unsupported runtime decision. / 创建一致的支持或不支持运行时决定。
     *
     * @param supported supported / 受支持
     * @param detail detail / 详情
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RuntimeCapabilityDecision {
        detail = Objects.requireNonNull(detail, "detail");
        if (supported == detail.isPresent()) {
            throw new IllegalArgumentException("supported runtime decisions must not contain a rejection detail");
        }
    }

    /**
     * Builds runtime capability decision from the supplied supported runtime inputs.
     * <p>根据所提供受支持运行时输入构建运行时能力决定。
     *
     * @return runtime capability decision from the supplied supported runtime inputs / 根据所提供受支持运行时输入构建运行时能力决定
     */
    static RuntimeCapabilityDecision supportedRuntime() {
        return new RuntimeCapabilityDecision(true, Optional.empty());
    }

    /**
     * Builds runtime capability decision from the supplied unsupported runtime inputs.
     * <p>根据所提供不支持运行时输入构建运行时能力决定。
     *
     * @param detail detail / 详情
     * @return runtime capability decision from the supplied unsupported runtime inputs / 根据所提供不支持运行时输入构建运行时能力决定
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static RuntimeCapabilityDecision unsupportedRuntime(String detail) {
        return new RuntimeCapabilityDecision(false, Optional.of(Objects.requireNonNull(detail, "detail")));
    }
}
