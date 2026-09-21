package gold.debug.windowstolinux.shared.model.deployment;

/**
 * Explicit, reviewed limits for the fixed managed-deployment remote Maven build entrypoint.
 *
 *  <p>固定受管部署远程 Maven 构建入口经过审阅的明确限制。
 *
 * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
 * @param maxProcesses max processes / 最大进程
 * @param maxMemoryMiB max memory mi B / 最大内存MiB
 * @param maxOutputBytes max output bytes / 最大输出字节
 * @param maxWorkspaceBytes max workspace bytes / 最大工作区字节
 * @param runAsRoot run as root / 运行As根目录
 */
public record BuildLimitConfiguration(
        int timeoutSeconds,
        int maxProcesses,
        int maxMemoryMiB,
        long maxOutputBytes,
        long maxWorkspaceBytes,
        boolean runAsRoot
) {
    /**
     * Validates and binds the inputs required by build limit configuration.
     * <p>校验并绑定构建限制配置所需输入。
     *
     * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
     * @param maxProcesses max processes / 最大进程
     * @param maxMemoryMiB max memory mi B / 最大内存MiB
     * @param maxOutputBytes max output bytes / 最大输出字节
     * @param maxWorkspaceBytes max workspace bytes / 最大工作区字节
     * @param runAsRoot run as root / 运行As根目录
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     */
    public BuildLimitConfiguration {
        if (timeoutSeconds < 60 || timeoutSeconds > 7200) {
            throw new IllegalArgumentException("timeoutSeconds must be between 60 and 7200");
        }
        if (maxProcesses < 8 || maxProcesses > 4096) {
            throw new IllegalArgumentException("maxProcesses must be between 8 and 4096");
        }
        if (maxMemoryMiB < 256 || maxMemoryMiB > 262144) {
            throw new IllegalArgumentException("maxMemoryMiB must be between 256 and 262144");
        }
        if (maxOutputBytes < 4096 || maxOutputBytes > 128L * 1024 * 1024) {
            throw new IllegalArgumentException("maxOutputBytes must be between 4 KiB and 128 MiB");
        }
        if (maxWorkspaceBytes < 64L * 1024 * 1024 || maxWorkspaceBytes > 128L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("maxWorkspaceBytes must be between 64 MiB and 128 GiB");
        }
    }

    /**
     * Builds build limit configuration from the supplied default non root inputs.
     * <p>根据所提供默认非根目录输入构建构建限制配置。
     *
     * @return the operation result / 操作结果
     */
    public static BuildLimitConfiguration defaultNonRoot() {
        return new BuildLimitConfiguration(1800, 1024, 4096, 4L * 1024 * 1024, 4L * 1024 * 1024 * 1024, false);
    }
}
