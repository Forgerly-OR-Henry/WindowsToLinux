package gold.debug.windowstolinux.app.service.recovery;

import java.util.*;

import gold.debug.windowstolinux.app.linux.recovery.RecoveryProbeResult;
import gold.debug.windowstolinux.app.linux.recovery.RecoveryProbeResult.StatusType;
import gold.debug.windowstolinux.app.secret.SecretStoreFailureType;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.model.failure.*;

/** Converts the remaining old SSH boundary to Linux-owned rescue evidence. / 将剩余旧 SSH 边界转换为 Linux 所属救援证据。 */
final class RecoveryProbeAdapter {
    /** Prevents construction of the conversion boundary. / 禁止构造转换边界。 */
    private RecoveryProbeAdapter() {
    }

    /**
     * Maps the first structured failure in the cause chain and separates interruption, authentication, host identity and credential failures. Only an explicit connection failure without interruption is retryable.
     * <p>转换原因链中的第一个结构化失败，并区分中断、认证、主机身份及凭据失败。仅未伴随中断的明确连接失败可重试。
     *
     * @param exception original exception being classified or translated / 正在分类或转换的原始异常
     * @return a classified result carrying the original descriptor when available / 携带原始描述（存在时）的分类结果
     */
    static RecoveryProbeResult failed(Exception exception) {
        FailureDescriptor descriptor = null;
        boolean interrupted = Thread.currentThread().isInterrupted();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (descriptor == null && cause instanceof FailureCarrier carrier)
                descriptor = carrier.failure();
            interrupted |= cause instanceof InterruptedException || cause instanceof java.io.InterruptedIOException
                    || cause instanceof java.util.concurrent.CancellationException;
        }
        if (descriptor == null)
            descriptor = FailureDescriptor.create(
                    interrupted
                            ? ApplicationServiceFailureType.RECOVERY_INTERRUPTED
                            : ApplicationServiceFailureType.RECOVERY_PROBE_FAILED,
                    OperationIdentity.create(),
                    interrupted ? "SSH recovery probe was interrupted" : "SSH recovery verification failed");
        var definition = descriptor.definition();
        StatusType status = interrupted
                ? StatusType.INTERRUPTED
                : definition == LinuxOperationFailureType.CONNECTION_FAILED
                        ? StatusType.RETRYABLE_CONNECTION
                        : definition == LinuxOperationFailureType.AUTHENTICATION_FAILED
                                ? StatusType.AUTHENTICATION_REJECTED
                                : definition == LinuxOperationFailureType.HOST_KEY_REJECTED
                                        ? StatusType.IDENTITY_CONFLICT
                                        : definition instanceof SecretStoreFailureType
                                                ? StatusType.CREDENTIAL_UNAVAILABLE
                                                : StatusType.FAILED;
        var problem = new gold.debug.windowstolinux.appapi.task.Problem(
                interrupted
                        ? gold.debug.windowstolinux.appapi.task.ProblemCode.CANCELLED
                        : gold.debug.windowstolinux.appapi.task.ProblemCode.IO_FAILURE,
                descriptor.code(), "SSH recovery verification failed", Map.of(),
                Optional.of(UUID.fromString(descriptor.operationIdentity().toString())));
        return new RecoveryProbeResult(status, Optional.of(problem));
    }

}
