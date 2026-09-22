package gold.debug.windowstolinux.app.service.contract;

import java.util.function.Predicate;

import gold.debug.windowstolinux.app.service.server.ServerProfile;

/**
 * APP-only browser rescue entry point. / 仅 APP 使用的浏览器救援入口。
 */
public interface SshRecoveryApplicationFacade {
    /**
     * Starts one session; the implementation owns and clears the supplied master password. / 开始会话，实现持有并清理传入的主密码。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @return constructed or resolved ssh recovery session / 构造或解析得到的SSH恢复会话
     */
    SshRecoverySession startSshRecovery(ServerProfile server, char[] master, Predicate<String> fingerprint);

    /**
     * Stops all task-owned rescue resources at application shutdown. / 应用退出时停止所有救援资源。
     */
    void closeSshRecovery();
}
