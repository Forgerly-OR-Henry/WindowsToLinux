package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import java.time.Duration;

/** Runs only the verification entry sealed into the owned release. */
public final class ApplicationHealthProbe {
    private ApplicationHealthProbe() { }

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
