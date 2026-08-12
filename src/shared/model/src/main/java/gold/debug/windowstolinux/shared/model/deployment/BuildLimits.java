package gold.debug.windowstolinux.shared.model.deployment;

/**
 * Explicit, reviewed limits for the fixed managed-deployment remote Maven build entrypoint.
 *
 * <p>固定受管部署远程 Maven 构建入口经过审阅的明确限制。
 *
 * @param timeoutSeconds the {@code timeoutSeconds} value / {@code timeoutSeconds} 值
 * @param maxProcesses the {@code maxProcesses} value / {@code maxProcesses} 值
 * @param maxMemoryMiB the {@code maxMemoryMiB} value / {@code maxMemoryMiB} 值
 * @param maxOutputBytes the {@code maxOutputBytes} value / {@code maxOutputBytes} 值
 * @param maxWorkspaceBytes the {@code maxWorkspaceBytes} value / {@code maxWorkspaceBytes} 值
 * @param runAsRoot the {@code runAsRoot} value / {@code runAsRoot} 值
 */
public record BuildLimits(
        int timeoutSeconds,
        int maxProcesses,
        int maxMemoryMiB,
        long maxOutputBytes,
        long maxWorkspaceBytes,
        boolean runAsRoot
) {
    /**
     * Creates a {@code BuildLimits} instance.
     *
     * <p>创建 {@code BuildLimits} 实例。
     *
     * @param timeoutSeconds the {@code timeoutSeconds} value / {@code timeoutSeconds} 值
     * @param maxProcesses the {@code maxProcesses} value / {@code maxProcesses} 值
     * @param maxMemoryMiB the {@code maxMemoryMiB} value / {@code maxMemoryMiB} 值
     * @param maxOutputBytes the {@code maxOutputBytes} value / {@code maxOutputBytes} 值
     * @param maxWorkspaceBytes the {@code maxWorkspaceBytes} value / {@code maxWorkspaceBytes} 值
     * @param runAsRoot the {@code runAsRoot} value / {@code runAsRoot} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     */
    public BuildLimits {
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
     * Performs the {@code defaultNonRoot} operation.
     *
     * <p>执行 {@code defaultNonRoot} 操作。
     *
     * @return the operation result / 操作结果
     */
    public static BuildLimits defaultNonRoot() {
        return new BuildLimits(1800, 1024, 4096, 4L * 1024 * 1024, 4L * 1024 * 1024 * 1024, false);
    }
}
