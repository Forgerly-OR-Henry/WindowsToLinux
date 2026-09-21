package gold.debug.windowstolinux.shared.backup.manifest;

/**
 * Stable backup health-check discriminator. / 稳定的备份健康检查判别类型。
 */
public enum BackupHealthCheckType {
    /**
     * HTTP protocol classification within backup health check type.
     * <p>备份健康检查类型中的HTTP 协议分类。
     */
    HTTP,
    /**
     * TCP classification within backup health check type.
     * <p>备份健康检查类型中的TCP分类。
     */
    TCP,
    /**
     * PROCESS classification within backup health check type.
     * <p>备份健康检查类型中的进程分类。
     */
    PROCESS,
    /**
     * COMMAND classification within backup health check type.
     * <p>备份健康检查类型中的命令分类。
     */
    COMMAND,
    /**
     * UDP classification within backup health check type.
     * <p>备份健康检查类型中的UDP分类。
     */
    UDP
}
