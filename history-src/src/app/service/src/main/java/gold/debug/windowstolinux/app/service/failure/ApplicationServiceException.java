package gold.debug.windowstolinux.app.service.failure;

import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

/**
 * Unchecked structured failure at a desktop application-service boundary. / 桌面应用服务边界的非受检结构化失败。
 */
public final class ApplicationServiceException extends RuntimeException implements FailureCarrier {
    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final FailureDescriptor failure;

    /**
     * Creates a service-boundary failure. / 创建服务边界失败。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ApplicationServiceException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /**
     * Creates a service failure without message arguments. / 创建无消息参数的服务失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return a service failure without message arguments / 无消息参数的服务失败
     */
    public static ApplicationServiceException create(ApplicationServiceFailureType type, String diagnostic) {
        return create(type, Map.of(), diagnostic, null);
    }

    /**
     * Creates a service failure with its original cause. / 创建带原始原因的服务失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a service failure with its original cause / 带原始原因的服务失败
     */
    public static ApplicationServiceException create(ApplicationServiceFailureType type, String diagnostic,
            Throwable cause) {
        return create(type, Map.of(), diagnostic, cause);
    }

    /**
     * Creates a service failure with safe message arguments. / 创建带安全消息参数的服务失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return a service failure with safe message arguments / 带安全消息参数的服务失败
     */
    public static ApplicationServiceException create(ApplicationServiceFailureType type, Map<String, ?> arguments,
            String diagnostic) {
        return create(type, arguments, diagnostic, null);
    }

    /**
     * Creates a fully described service failure. / 创建完整描述的服务失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a fully described service failure / 完整描述的服务失败
     */
    public static ApplicationServiceException create(ApplicationServiceFailureType type, Map<String, ?> arguments,
            String diagnostic, Throwable cause) {
        return new ApplicationServiceException(
                FailureDescriptor.create(type, OperationIdentity.create(), arguments, diagnostic), cause);
    }

    /**
     * Translates native database failures at the service boundary. / 在服务边界转换原生数据库失败。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return constructed or resolved application service exception / 构造或解析得到的应用服务异常
     */
    public static ApplicationServiceException nativeDatabase(
            gold.debug.windowstolinux.shared.linux.error.NativeDatabaseException failure) {
        ApplicationServiceFailureType type = switch (failure.reason()) {
            case AUTH_REQUIRED -> ApplicationServiceFailureType.DATABASE_AUTH_REQUIRED;
            case STATE_CHANGED -> ApplicationServiceFailureType.DATABASE_STATE_CHANGED;
            case INITIALIZATION_FAILED -> ApplicationServiceFailureType.DATABASE_INITIALIZATION_FAILED;
            case MANUAL_RESTORE_REQUIRED -> ApplicationServiceFailureType.DATABASE_MANUAL_RESTORE_REQUIRED;
            case VERSION_UNSUPPORTED -> ApplicationServiceFailureType.DATABASE_VERSION_UNSUPPORTED;
            case ACTION_FAILED -> ApplicationServiceFailureType.DATABASE_ACTION_FAILED;
        };
        return create(type, "Native database operation requires attention: " + failure.reason().name(), failure);
    }

    /**
     * Returns the structured failure. / 返回结构化失败。
     *
     * @return the structured failure / 结构化失败
     */
    @Override
    public FailureDescriptor failure() {
        return failure;
    }
}
