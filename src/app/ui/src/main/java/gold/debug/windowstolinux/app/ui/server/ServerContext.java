package gold.debug.windowstolinux.app.ui.server;

import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

/** Narrow server context consumed by deployment and lifecycle pages. / 部署与生命周期页面使用的窄服务器上下文。 */
public interface ServerContext {
    /** Returns the entered profile. / 返回已输入资料。 */
    ServerProfile profile();
    /** Returns the selected credential mode. / 返回已选凭据模式。 */
    CredentialStorageMode credentialMode();
    /** Returns a caller-owned master-password copy. / 返回由调用方持有的主密码副本。 */
    char[] masterPassword();
    /** Requests first-use fingerprint confirmation. / 请求首次使用指纹确认。 */
    boolean confirmFingerprint(String fingerprint);
}
