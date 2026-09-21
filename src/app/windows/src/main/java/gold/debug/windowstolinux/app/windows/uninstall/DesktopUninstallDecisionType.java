package gold.debug.windowstolinux.app.windows.uninstall;

/**
 * Explicit user choice for local desktop data and credentials. / 桌面本地数据及凭据的显式用户选择。
 */
public enum DesktopUninstallDecisionType {
    /**
     * KEEP DATA AND CREDENTIALS classification within desktop uninstall decision type.
     * <p>Desktop卸载决定类型中的保留数据与凭据分类。
     */
    KEEP_DATA_AND_CREDENTIALS,
    /**
     * DELETE DATA AND CREDENTIALS classification within desktop uninstall decision type.
     * <p>Desktop卸载决定类型中的删除数据与凭据分类。
     */
    DELETE_DATA_AND_CREDENTIALS
}
