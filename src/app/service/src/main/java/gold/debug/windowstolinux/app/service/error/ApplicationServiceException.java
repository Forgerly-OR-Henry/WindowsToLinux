package gold.debug.windowstolinux.app.service.error;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Problem;

/**
 * Unchecked structured failure at a desktop application-service boundary. / 桌面应用服务边界的非受检结构化失败。
 */
public final class ApplicationServiceException extends RuntimeException {
    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final Problem failure;

    /**
     * Creates a service-boundary failure. / 创建服务边界失败。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ApplicationServiceException(Problem failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").message(), cause);
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
        return new ApplicationServiceException(evidence(type, arguments, diagnostic, cause), cause);
    }

    /**
     * Returns the structured failure. / 返回结构化失败。
     *
     * @return the structured failure / 结构化失败
     */
    public Problem problem() {
        return failure;
    }

    /** Preserves an actual SDK failure before creating a local occurrence. / 创建本地发生事件前保留实际SDK失败。
     * @param type actual local classification / 实际本地分类
     * @param arguments literal message values / 消息字面值
     * @param diagnostic nonsecret local diagnostic / 非秘密本地诊断
     * @param cause actual nested failure or absence / 实际嵌套失败或不存在
     * @return original SDK evidence or complete local evidence / 原SDK依据或完整本地依据
     * @throws NullPointerException if a required local value is absent / 必需本地值缺失时 */
    private static Problem evidence(ApplicationServiceFailureType type, Map<String, ?> arguments, String diagnostic,
                                    Throwable cause) {
        Objects.requireNonNull(type, "type");
        if (cause instanceof ApiException api)
            return api.problem();
        if (cause != null && cause.getCause() instanceof ApiException api)
            return api.problem();
        return type.problem(UUID.randomUUID(), arguments, diagnostic);
    }
}
