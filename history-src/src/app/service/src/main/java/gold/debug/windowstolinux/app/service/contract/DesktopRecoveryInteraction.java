package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.app.service.contract.SshRecoverySession;
import gold.debug.windowstolinux.app.service.server.ServerProfile;

/**
 * Optional desktop handoff during a safely resumable operation. / 可安全继续操作期间的桌面交接。
 */
public interface DesktopRecoveryInteraction {
    /**
     * Asks whether to open an independent console for this failed server. / 询问是否为失败的服务器打开独立控制台。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @return true when asks whether to open an independent console for this failed server, false otherwise / 询问是否为失败的服务器打开独立控制台时为 true，否则为 false
     */
    boolean offerSshRecovery(ServerProfile server);

    /**
     * Presents session controls without blocking the service worker. / 展示会话控件，不阻塞服务工作线程。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     */
    void showSshRecovery(ServerProfile server, SshRecoverySession session);
}
