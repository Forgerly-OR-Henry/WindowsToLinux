package gold.debug.windowstolinux.shared.config;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Objects;

/** Structured unchecked rejection of invalid deterministic configuration. / 对无效确定性配置的结构化非受检拒绝。 */
public final class ConfigurationException extends IllegalArgumentException implements FailureCarrier {
    private final FailureDescriptor failure;

    /** Creates one configuration failure. / 创建一次配置失败。 */
    public ConfigurationException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /** Creates a typed configuration failure. / 创建类型化配置失败。 */
    public static ConfigurationException create(ConfigurationFailureType type, String diagnostic) {
        return new ConfigurationException(
                FailureDescriptor.create(type, OperationIdentity.create(), diagnostic), null);
    }

    /** Creates a typed configuration failure with its original cause. / 创建带原始原因的类型化配置失败。 */
    public static ConfigurationException create(
            ConfigurationFailureType type, String diagnostic, Throwable cause) {
        return new ConfigurationException(
                FailureDescriptor.create(type, OperationIdentity.create(), diagnostic), cause);
    }

    @Override public FailureDescriptor failure() { return failure; }
}
