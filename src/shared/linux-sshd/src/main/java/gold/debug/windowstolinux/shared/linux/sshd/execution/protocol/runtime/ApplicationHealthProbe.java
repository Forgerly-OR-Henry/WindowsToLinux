package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import java.time.Duration;

/**
 * Runs only the verification entry sealed into the owned release.
 * <p>仅运行封存在自有发布中的验证入口。
 */
public final class ApplicationHealthProbe {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ApplicationHealthProbe() { }

    /**
     * Checks health check result.
     * <p>检查健康检查结果。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @param app app / 应用
     * @param health health / 健康
     * @return constructed or resolved health check result / 构造或解析得到的健康检查结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public static HealthCheckResult check(SshCommandExecutor commands, ManagedApplication app, HealthCheck health)
            throws LinuxOperationException {
        var result = commands.exec("sudo -n " + ManagedHelperBundle.PATH + " application-health "
                + SshCommandExecutor.quote(app.id()) + " " + SshCommandExecutor.quote(app.ownershipManifestSha256()) + " "
                + SshCommandExecutor.quote(ApplicationWorkloadArguments.healthPayload(health)),
                Duration.ofSeconds(health.timeoutSeconds() + 45L), true);
        return new HealthCheckResult(result.succeeded() && "1".equals(SshCommandExecutor.lines(result.output()).get("HEALTHY")),
                result.succeeded() ? "Reviewed application validation completed" : result.failureEvidence());
    }
}
