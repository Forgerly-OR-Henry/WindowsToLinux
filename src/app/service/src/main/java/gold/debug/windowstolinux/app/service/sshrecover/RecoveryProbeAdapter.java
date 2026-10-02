package gold.debug.windowstolinux.app.service.sshrecover;

import java.util.*;

import gold.debug.windowstolinux.app.linux.ssh.recover.RecoveryProbeResult;
import gold.debug.windowstolinux.app.linux.ssh.recover.RecoveryProbeResult.StatusType;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.error.ApplicationServiceFailureType;
import gold.debug.windowstolinux.appapi.task.Problem;

/** Classifies original platform exceptions as Linux-owned rescue evidence. / 将原平台异常分类为Linux所属救援证据。 */
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
        Problem descriptor = null;
        boolean credential = false;
        boolean interrupted = Thread.currentThread().isInterrupted();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception; cause != null && visited.size() < 16
            && visited.add(cause); cause = cause.getCause()) {
            if (descriptor == null && cause instanceof SecretStoreException secret) {
                descriptor = secret.problem();
                credential = true;
            }
            if (descriptor == null && cause instanceof gold.debug.windowstolinux.appapi.task.ApiException api)
                descriptor = api.problem();
            if (descriptor == null
                && cause instanceof gold.debug.windowstolinux.app.service.error.ApplicationServiceException service)
                descriptor = service.problem();
            interrupted |= cause instanceof InterruptedException || cause instanceof java.io.InterruptedIOException
                || cause instanceof java.util.concurrent.CancellationException;
        }
        if (descriptor == null)
            descriptor = (interrupted
                ? ApplicationServiceFailureType.RECOVERY_INTERRUPTED
                : ApplicationServiceFailureType.RECOVERY_PROBE_FAILED).problem(UUID.randomUUID(), Map.of(),
                interrupted ? "SSH recovery probe was interrupted" : "SSH recovery verification failed");
        if (interrupted || credential)
            return new RecoveryProbeResult(interrupted ? StatusType.INTERRUPTED : StatusType.CREDENTIAL_UNAVAILABLE,
                Optional.of(descriptor));
        return RecoveryProbeResult.failed(descriptor);
    }

}
