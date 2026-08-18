package gold.debug.windowstolinux.shared.linux.sshd.protocol.workspace;

import gold.debug.windowstolinux.shared.linux.sshd.protocol.helper.ManagedHelperBundle;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;

import java.time.Duration;
import java.util.Objects;

/** Controls only candidate workspace creation and cleanup through fixed helper verbs. / 仅通过固定 helper 动词控制候选工作区创建与清理。 */
public final class CandidateWorkspaceController {
    private final SshCommandExecutor commands;

    /** Creates a candidate workspace controller. / 创建候选工作区控制器。 */
    public CandidateWorkspaceController(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /** Creates the exact reviewed candidate workspace. / 创建精确的经审阅候选工作区。 */
    public RemoteStepResult create(RemoteWorkspace workspace) throws LinuxOperationException {
        var result = commands.exec(command("candidate-create", workspace), Duration.ofSeconds(20), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(), result.succeeded()
                ? "Controlled helper created the managed candidate directory"
                : "Controlled helper could not create the managed candidate directory: " + result.failureEvidence());
    }

    /** Cleans the exact reviewed candidate workspace. / 清理精确的经审阅候选工作区。 */
    public RemoteStepResult cleanup(RemoteWorkspace workspace) throws LinuxOperationException {
        var result = commands.exec(command("candidate-cleanup", workspace), Duration.ofSeconds(30), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(), result.succeeded()
                ? "Controlled helper cleaned the current candidate directory"
                : "Controlled helper could not clean the current candidate directory: " + result.failureEvidence());
    }

    private static String command(String verb, RemoteWorkspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        return "sudo -n " + SshCommandExecutor.quote(ManagedHelperBundle.PATH) + ' '
                + SshCommandExecutor.quote(verb) + ' ' + SshCommandExecutor.quote(workspace.applicationId()) + ' '
                + SshCommandExecutor.quote(workspace.candidateId());
    }
}
