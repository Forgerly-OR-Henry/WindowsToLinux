package gold.debug.windowstolinux.shared.linux.sshd.capability;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import java.time.Duration;

/**
 * Retries only read-only capability probes with their original failure classification. / 只重试只读能力探测，保留原始失败分类。
 */
final class CapabilityReadExecutor {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private CapabilityReadExecutor() { }

    /**
     * Collects command result.
     * <p>采集命令结果。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @param failureType failure type / 失败类型
     * @param script build script path / 构建脚本路径
     * @return constructed or resolved command result / 构造或解析得到的命令结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
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
