package gold.debug.windowstolinux.app.service.recovery;

import gold.debug.windowstolinux.app.secret.SecretStoreFailureType;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.model.failure.*;
import java.util.*;

/**
 * Typed SSH admission result retaining the original failure occurrence. / 保留原始失败实例的类型化 SSH 准入结果。
 *
 * @param status classification of the current operation result / 当前操作结果的分类
 * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
 */
record RecoveryProbeResult(StatusType status, Optional<FailureDescriptor> failure) {
    /**
     * Validates and binds the inputs required by recovery probe result.
     * <p>校验并绑定恢复探测结果所需输入。
     *
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    RecoveryProbeResult {
        Objects.requireNonNull(status, "status"); failure = Objects.requireNonNull(failure, "failure");
        if ((status == StatusType.RECOVERED) == failure.isPresent())
            throw new IllegalArgumentException("Only a recovered probe has no failure");
    }
    /**
     * Distinguishes verified SSH recovery, retryable connection loss and failures requiring attention.
     * <p>区分已验证 SSH 恢复、可重试连接丢失及需处理的失败。
     */
    enum StatusType {
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
    static RecoveryProbeResult recovered() { return new RecoveryProbeResult(StatusType.RECOVERED, Optional.empty()); }
    /**
     * Preserves the first structured failure in the cause chain and separates interruption, authentication, host identity and credential failures. Only an explicit connection failure without interruption is retryable.
     * <p>保留原因链中的第一个结构化失败，并区分中断、认证、主机身份及凭据失败。仅未伴随中断的明确连接失败可重试。
     *
     * @param exception original exception being classified or translated / 正在分类或转换的原始异常
     * @return a classified result carrying the original descriptor when available / 携带原始描述（存在时）的分类结果
     */
    static RecoveryProbeResult failed(Exception exception) {
        FailureDescriptor descriptor = null;
        boolean interrupted = Thread.currentThread().isInterrupted();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (descriptor == null && cause instanceof FailureCarrier carrier) descriptor = carrier.failure();
            interrupted |= cause instanceof InterruptedException || cause instanceof java.io.InterruptedIOException
                    || cause instanceof java.util.concurrent.CancellationException;
        }
        if (descriptor == null) descriptor = FailureDescriptor.create(interrupted
                ? ApplicationServiceFailureType.RECOVERY_INTERRUPTED : ApplicationServiceFailureType.RECOVERY_PROBE_FAILED,
                OperationIdentity.create(), interrupted ? "SSH recovery probe was interrupted" : "SSH recovery verification failed");
        var definition = descriptor.definition();
        StatusType status = interrupted ? StatusType.INTERRUPTED
                : definition == LinuxOperationFailureType.CONNECTION_FAILED ? StatusType.RETRYABLE_CONNECTION
                : definition == LinuxOperationFailureType.AUTHENTICATION_FAILED ? StatusType.AUTHENTICATION_REJECTED
                : definition == LinuxOperationFailureType.HOST_KEY_REJECTED ? StatusType.IDENTITY_CONFLICT
                : definition instanceof SecretStoreFailureType ? StatusType.CREDENTIAL_UNAVAILABLE : StatusType.FAILED;
        return new RecoveryProbeResult(status, Optional.of(descriptor));
    }
    /**
     * Maps the typed probe result to an existing safe UI state message; the failure descriptor remains separate.
     * <p>将类型化探测结果映射到既有安全 UI 状态消息；失败描述保持独立。
     *
     * @return message code text / 消息代码文本
     */
    String messageCode() {
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
