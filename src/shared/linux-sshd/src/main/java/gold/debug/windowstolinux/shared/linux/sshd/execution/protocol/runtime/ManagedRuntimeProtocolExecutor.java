package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime;

import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Controls only managed runtime observation, lifecycle, and bounded retention through fixed helper verbs. / 仅通过固定 helper 动词控制受管运行时观察、生命周期与有界保留。 */
public final class ManagedRuntimeProtocolExecutor {
    private final SshCommandExecutor commands;

    /** Creates a managed runtime controller. / 创建受管运行时控制器。 */
    public ManagedRuntimeProtocolExecutor(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /** Returns the helper-verified current runtime kind. / 返回 helper 验证的当前运行时类型。 */
    public Map<String, String> inspect(ManagedApplication application) throws LinuxOperationException {
        var result = commands.execProtocol(command("inspect-runtime", application), Duration.ofSeconds(20), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.localized("linux.error.runtimeObservationFailed",
                    "Controlled helper could not identify the managed runtime: " + result.failureEvidence());
        }
        return SshCommandExecutor.lines(result.output());
    }

    /** Executes one allowlisted ordinary-runtime lifecycle action. / 执行一个列入白名单的普通运行时生命周期动作。 */
    public RemoteStepResult lifecycle(ManagedApplication application, String action) throws LinuxOperationException {
        if (!java.util.Set.of("start", "stop", "restart", "enable", "disable").contains(action)) {
            throw new IllegalArgumentException("unsupported managed lifecycle action");
        }
        String command = "sudo -n " + SshCommandExecutor.quote(ManagedHelperBundle.PATH) + " 'lifecycle' "
                + SshCommandExecutor.quote(application.id()) + ' ' + SshCommandExecutor.quote(action) + ' '
                + SshCommandExecutor.quote(application.ownershipManifestSha256());
        var result = commands.exec(command,
                Duration.ofSeconds(60), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(), result.succeeded()
                ? "Controlled helper executed the ordinary managed lifecycle action" : result.failureEvidence());
    }

    /** Retains current plus at most two previous verified releases. / 保留当前发布及最多两个已验证的先前发布。 */
    public RemoteStepResult retain(ManagedApplication application) throws LinuxOperationException {
        var result = commands.exec(command("retain", application), Duration.ofSeconds(60), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(), result.succeeded()
                ? "Controlled helper retained the bounded recent verified releases"
                : "Controlled helper could not finish bounded release retention: " + result.failureEvidence());
    }

    private static String command(String verb, ManagedApplication application) {
        Objects.requireNonNull(application, "application");
        return "sudo -n " + SshCommandExecutor.quote(ManagedHelperBundle.PATH) + ' '
                + SshCommandExecutor.quote(verb) + ' ' + SshCommandExecutor.quote(application.id()) + ' '
                + SshCommandExecutor.quote(application.ownershipManifestSha256());
    }
}
