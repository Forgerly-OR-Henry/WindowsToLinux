package gold.debug.windowstolinux.app.service.security;

/** Non-secret desktop authorization state. / 不含秘密的桌面授权状态。
 * @param configured configured supplied to this boundary / 传入当前边界的configured值
 * @param unlocked unlocked supplied to this boundary / 传入当前边界的unlocked值
 * @param encryptionEnabled global credential encryption policy / 全局凭据加密策略
 * @param activeTasks active tasks supplied to this boundary / 传入当前边界的activeTasks值
 * @param changing changing supplied to this boundary / 传入当前边界的changing值
 */
public record DesktopSecurityState(boolean configured, boolean unlocked, boolean encryptionEnabled, int activeTasks,
                                   boolean changing) {
}
