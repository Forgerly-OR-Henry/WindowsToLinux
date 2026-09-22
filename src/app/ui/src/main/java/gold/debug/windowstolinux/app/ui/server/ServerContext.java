package gold.debug.windowstolinux.app.ui.server;

import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

/**
 * Narrow server context consumed by deployment and lifecycle pages. / 部署与生命周期页面使用的窄服务器上下文。
 */
public interface ServerContext {
    /**
     * Selects a saved profile without reading its password. / 选择已保存配置，不读取其密码。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     */
    void selectProfile(ServerProfile profile);

    /**
     * Returns the entered profile. / 返回已输入资料。
     *
     * @return the entered profile / 已输入资料
     */
    ServerProfile profile();

    /**
     * Returns the selected credential mode. / 返回已选凭据模式。
     *
     * @return the selected credential mode / 已选凭据模式
     */
    CredentialStorageMode credentialMode();

    /**
     * Returns a caller-owned master-password copy. / 返回由调用方持有的主密码副本。
     *
     * @return a caller-owned master-password copy / 由调用方持有的主密码副本
     */
    char[] masterPassword();

    /**
     * Requests first-use fingerprint confirmation. / 请求首次使用指纹确认。
     *
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @return true when requests first-use fingerprint confirmation, false otherwise / 请求首次使用指纹确认时为 true，否则为 false
     */
    boolean confirmFingerprint(String fingerprint);
}
