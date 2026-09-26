package gold.debug.windowstolinux.app.linux.recovery;

import java.util.*;

import gold.debug.windowstolinux.appapi.task.Problem;

/**
 * Typed SSH admission result retaining the original failure occurrence. / 保留原始失败实例的类型化 SSH 准入结果。
 *
 * @param status classification of the current operation result / 当前操作结果的分类
 * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
 */
public record RecoveryProbeResult(StatusType status, Optional<Problem> failure) {
    /**
     * Validates and binds the inputs required by recovery probe result.
     * <p>校验并绑定恢复探测结果所需输入。
     *
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RecoveryProbeResult {
        Objects.requireNonNull(status, "status");
        failure = Objects.requireNonNull(failure, "failure");
        if ((status == StatusType.RECOVERED) == failure.isPresent())
            throw new IllegalArgumentException("Only a recovered probe has no failure");
    }
    /**
     * Distinguishes verified SSH recovery, retryable connection loss and failures requiring attention.
     * <p>区分已验证 SSH 恢复、可重试连接丢失及需处理的失败。
     */
    public enum StatusType {
        /**
         * SSH verification succeeded and no failure descriptor is present.
         * <p>SSH 验证成功，不携带失败描述。
         */
        RECOVERED,
        /**
         * An explicit connection failure may be retried without discarding its original descriptor.
         * <p>明确的连接失败允许重试，并保留原始失败描述。
         */
        RETRYABLE_CONNECTION,
        /**
         * The SSH server rejected authentication; automatic connection retries must stop.
         * <p>SSH 服务器拒绝认证，必须停止自动连接重试。
         */
        AUTHENTICATION_REJECTED,
        /**
         * Host-key verification failed and requires an explicit trust decision.
         * <p>主机密钥验证失败，需要显式信任决策。
         */
        IDENTITY_CONFLICT,
        /**
         * The secret store could not provide usable credentials.
         * <p>秘密存储无法提供可用凭据。
         */
        CREDENTIAL_UNAVAILABLE,
        /**
         * Cancellation or interruption prevents further probing.
         * <p>取消或中断阻止继续探测。
         */
        INTERRUPTED,
        /**
         * Another failure requires attention and is not automatically retryable.
         * <p>其他失败需要处理，不允许自动重试。
         */
        FAILED
    }

    /**
     * Creates a successful SSH verification result without a failure descriptor.
     * <p>创建不含失败描述的 SSH 验证成功结果。
     *
     * @return a recovered result with an empty failure field / 失败字段为空的已恢复结果
     */
    public static RecoveryProbeResult recovered() {
        return new RecoveryProbeResult(StatusType.RECOVERED, Optional.empty());
    }

    /** Classifies exact platform evidence without replacing the original Problem. / 分类精确平台证据，不替换原 Problem。
     * @param problem original safe platform evidence / 原安全平台证据
     * @return explicit retry class and preserved diagnostic identity/code / 明确重试分类及保留的诊断身份和代码 */
    public static RecoveryProbeResult failed(gold.debug.windowstolinux.appapi.task.Problem problem) {
        var status = problem.code() == gold.debug.windowstolinux.appapi.task.ProblemCode.CANCELLED
                ? StatusType.INTERRUPTED
                : switch (problem.eventCode()) {
                    case "SSH_CONNECTION_FAILED", "SSH_CONNECT_TIMEOUT" -> StatusType.RETRYABLE_CONNECTION;
                    case "SSH_AUTHENTICATION_FAILED" -> StatusType.AUTHENTICATION_REJECTED;
                    case "SSH_HOST_KEY_REJECTED", "SSH_HOST_TRUST_FAILED" -> StatusType.IDENTITY_CONFLICT;
                    case "SERVER_CREDENTIAL_MISSING" -> StatusType.CREDENTIAL_UNAVAILABLE;
                    default -> StatusType.FAILED;
                };
        return new RecoveryProbeResult(status, Optional.of(problem));
    }

    /**
     * Maps the typed probe result to an existing safe UI state message; the failure descriptor remains separate.
     * <p>将类型化探测结果映射到既有安全 UI 状态消息；失败描述保持独立。
     *
     * @return message code text / 消息代码文本
     */
    public String messageCode() {
        return switch (status) {
            case RECOVERED -> "recovered";
            case RETRYABLE_CONNECTION -> "connectionFailed";
            case AUTHENTICATION_REJECTED -> "authentication";
            case IDENTITY_CONFLICT -> "identityConflict";
            case CREDENTIAL_UNAVAILABLE -> "credentialUnavailable";
            case INTERRUPTED -> "cancelled";
            case FAILED -> "probeFailed";
        };
    }
}
