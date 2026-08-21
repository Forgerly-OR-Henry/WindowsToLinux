package gold.debug.windowstolinux.app.service.failure;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Map;
import java.util.Objects;

/** Unchecked structured failure at a desktop application-service boundary. / 桌面应用服务边界的非受检结构化失败。 */
public final class ApplicationServiceException extends RuntimeException implements FailureCarrier {
    private final FailureDescriptor failure;

    /** Creates a service-boundary failure. / 创建服务边界失败。 */
    public ApplicationServiceException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /** Creates a service failure without message arguments. / 创建无消息参数的服务失败。 */
    public static ApplicationServiceException create(ApplicationServiceFailureType type, String diagnostic) {
        return create(type, Map.of(), diagnostic, null);
    }

    /** Creates a service failure with its original cause. / 创建带原始原因的服务失败。 */
    public static ApplicationServiceException create(
            ApplicationServiceFailureType type, String diagnostic, Throwable cause) {
        return create(type, Map.of(), diagnostic, cause);
    }

    /** Creates a service failure with safe message arguments. / 创建带安全消息参数的服务失败。 */
    public static ApplicationServiceException create(
            ApplicationServiceFailureType type, Map<String, ?> arguments, String diagnostic) {
        return create(type, arguments, diagnostic, null);
    }

    /** Creates a fully described service failure. / 创建完整描述的服务失败。 */
    public static ApplicationServiceException create(
            ApplicationServiceFailureType type, Map<String, ?> arguments, String diagnostic, Throwable cause) {
        return new ApplicationServiceException(
                FailureDescriptor.create(type, OperationIdentity.create(), arguments, diagnostic), cause);
    }

    /** Returns the structured failure. / 返回结构化失败。 */
    @Override public FailureDescriptor failure() { return failure; }
}
