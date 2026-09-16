package gold.debug.windowstolinux.shared.linux.sshd.capability;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import java.time.Duration;

/** Retries only read-only capability probes with their original failure classification. / 只重试只读能力探测，保留原始失败分类。 */
final class CapabilityReadExecutor {
    private CapabilityReadExecutor() { }

    static SshCommandExecutor.CommandResult collect(SshCommandExecutor commands, LinuxOperationFailureType failureType,
                                                    String script) throws LinuxOperationException {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return commands.exec(script, Duration.ofSeconds(20), true);
            } catch (LinuxOperationException failure) {
                if (!SshCommandExecutor.isTransientTransportFailure(failure) || attempt == 3) throw failure;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw LinuxOperationException.create(failureType, "Read-only capability retry interrupted", interrupted);
                }
            }
        }
        throw new IllegalStateException("Capability retry completed without a result");
    }
}
