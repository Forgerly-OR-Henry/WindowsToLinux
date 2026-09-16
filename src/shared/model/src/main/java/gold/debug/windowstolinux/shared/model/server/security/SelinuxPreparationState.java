package gold.debug.windowstolinux.shared.model.server.security;

/** Observed checkpoints of explicitly approved SELinux preparation. / 显式批准的 SELinux 准备观测检查点。 */
public enum SelinuxPreparationState {
    UNPREPARED, REBOOT_PENDING, READY_TO_ENFORCE, ENFORCEMENT_PENDING, COMPLETE
}
