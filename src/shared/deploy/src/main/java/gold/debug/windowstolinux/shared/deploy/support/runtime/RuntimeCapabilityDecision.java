package gold.debug.windowstolinux.shared.deploy.support.runtime;

import java.util.Objects;
import java.util.Optional;

/** Indicates whether one reviewed runtime matches collected host capabilities. / 表示一个已审阅运行时是否匹配采集到的主机能力。 */
public record RuntimeCapabilityDecision(boolean supported, Optional<String> detail) {
    /** Creates a consistent supported or unsupported runtime decision. / 创建一致的支持或不支持运行时决定。 */
    public RuntimeCapabilityDecision {
        detail = Objects.requireNonNull(detail, "detail");
        if (supported == detail.isPresent()) {
            throw new IllegalArgumentException("supported runtime decisions must not contain a rejection detail");
        }
    }

    static RuntimeCapabilityDecision supportedRuntime() {
        return new RuntimeCapabilityDecision(true, Optional.empty());
    }

    static RuntimeCapabilityDecision unsupportedRuntime(String detail) {
        return new RuntimeCapabilityDecision(false, Optional.of(Objects.requireNonNull(detail, "detail")));
    }
}
