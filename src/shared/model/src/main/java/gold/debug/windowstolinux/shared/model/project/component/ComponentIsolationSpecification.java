package gold.debug.windowstolinux.shared.model.project.component;

/**
 * Explicit unsafe execution capabilities requested by one component.
 *
 *  <p>一个组件显式请求的不安全执行能力。
 *
 * @param arbitraryShell whether an arbitrary command or script is required / 是否需要任意命令或脚本
 * @param hostPrivileges whether host or elevated privileges are required / 是否需要宿主机或提升权限
 * @param deviceAccess whether uncontrolled device access is required / 是否需要不受控设备访问
 * @param uncontrolledNetwork whether unrestricted host networking is required / 是否需要不受限宿主网络
 */
public record ComponentIsolationSpecification(
        boolean arbitraryShell,
        boolean hostPrivileges,
        boolean deviceAccess,
        boolean uncontrolledNetwork
) {
    /**
     * Returns the safe default for a typed managed component. / 返回类型化受管组件的安全默认值。
     *
     * @return the safe default for a typed managed component / 类型化受管组件的安全默认值
     */
    public static ComponentIsolationSpecification managed() {
        return new ComponentIsolationSpecification(false, false, false, false);
    }

    /**
     * Returns whether the component stays within the managed execution boundary. / 返回组件是否保持在受管执行边界内。
     *
     * @return true when returns whether the component stays within the managed execution boundary, false otherwise / 返回组件是否保持在受管执行边界内时为 true，否则为 false
     */
    public boolean managedOnly() {
        return !arbitraryShell && !hostPrivileges && !deviceAccess && !uncontrolledNetwork;
    }
}
