package gold.debug.windowstolinux.app.service.contract;

import java.util.function.Predicate;

import gold.debug.windowstolinux.app.service.server.ServerProfile;

/**
 * APP-only browser rescue entry point. / 仅 APP 使用的浏览器救援入口。
 */
public interface SshRecoveryApplicationFacade {
    /**
     * Starts recovery under an accepted task credential capability. / 在已接纳任务的凭据能力内启动救援。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @return constructed or resolved ssh recovery session / 构造或解析得到的SSH恢复会话
     */
    SshRecoverySession startSshRecovery(ServerProfile server, Predicate<String> fingerprint);

    /**
     * Stops all task-owned rescue resources at application shutdown. / 应用退出时停止所有救援资源。
     */
    void closeSshRecovery();
}
