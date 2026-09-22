package gold.debug.windowstolinux.shared.model.server.security;

/**
 * Observed checkpoints of explicitly approved SELinux preparation. / 显式批准的 SELinux 准备观测检查点。
 */
public enum SelinuxPreparationState {
    /**
     * UNPREPARED classification within selinux preparation state.
     * <p>Selinux准备状态中的未准备分类。
     */
    UNPREPARED,
    /**
     * REBOOT PENDING classification within selinux preparation state.
     * <p>Selinux准备状态中的重启待处理分类。
     */
    REBOOT_PENDING,
    /**
     * READY TO ENFORCE classification within selinux preparation state.
     * <p>Selinux准备状态中的就绪目标强制分类。
     */
    READY_TO_ENFORCE,
    /**
     * ENFORCEMENT PENDING classification within selinux preparation state.
     * <p>Selinux准备状态中的强制执行待处理分类。
     */
    ENFORCEMENT_PENDING,
    /**
     * COMPLETE classification within selinux preparation state.
     * <p>Selinux准备状态中的完整分类。
     */
    COMPLETE
}
