package gold.debug.windowstolinux.shared.linux.protocol;

import gold.debug.windowstolinux.shared.model.server.ManagedHelperProtocolVersion;

/**
 * Stable managed-helper protocol identity shared by preflight and the SSH implementation. / 由预检与 SSH 实现共享的稳定受管 helper 协议身份。
 */
public final class ManagedHelperProtocol {
    /**
     * Current reviewed deployment protocol version. / 当前经审阅部署协议版本。
     */
    public static final int VERSION = ManagedHelperProtocolVersion.CURRENT;

    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ManagedHelperProtocol() {
    }
}
