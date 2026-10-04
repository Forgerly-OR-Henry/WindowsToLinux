package gold.debug.windowstolinux.app.linux.ssh.recover;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.app.linux.ssh.connect.SshConnector;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** Verifies an authenticated SSH connection using only a fixed bounded echo. / 仅以固定有界回显复核已认证 SSH 连接。 */
public final class RecoveryProbe {
    /** Prevents allocation of the fixed protocol. / 禁止实例化固定协议。 */
    private RecoveryProbe() {
    }

    /** Checks the exact exit code and echo without accepting a model command. / 检查精确退出码及回显，不接受模型命令。
     * @param connection originally authenticated connection / 原已认证连接
     * @param cancellation original task cancellation / 原任务取消
     * @return verified observation or original uncertainty / 已核实观察或原不确定性
     * @throws ApiException if transport admission or cleanup fails / 传输准入或清理失败时 */
    public static OperationResult<Void> verify(SshConnector.Connection connection, CancelToken cancellation) throws ApiException {
        return verify(connection, cancellation, Duration.ofSeconds(15));
    }

    /** Uses the captured timeout with the same fixed verification command. / 使用捕获超时和同一固定验证命令。
     * @param connection admitted connection / 准入连接
     * @param cancellation original cancellation / 原取消
     * @param timeout positive deadline / 正期限
     * @return verified result / 复核结果
     * @throws ApiException if transport fails / 传输失败时 */
    public static OperationResult<Void> verify(SshConnector.Connection connection, CancelToken cancellation, Duration timeout) throws ApiException {
        var request = new CommandRequest("printf '%s\\n' WTL_SSH_RESCUE_OK", new byte[0], timeout, 1024);
        var result = connection.execute(request, cancellation);
        if (result.outcome() != TaskResult.SUCCEEDED)
            return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
        var output = result.value().orElseThrow();
        if (!output.exitCode().equals(Optional.of(0)) || output.truncated()
            || !output.stdout().equals("WTL_SSH_RESCUE_OK\n"))
            return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.IO_FAILURE, "RESCUE_VERIFICATION_OUTPUT",
                    "The fixed SSH verification did not produce its expected result", Map.of(), Optional.empty())));
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
    }
}
