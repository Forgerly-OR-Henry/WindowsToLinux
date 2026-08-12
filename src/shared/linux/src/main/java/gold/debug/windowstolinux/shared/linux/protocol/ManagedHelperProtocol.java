package gold.debug.windowstolinux.shared.linux.protocol;

/** Stable managed-helper protocol identity shared by preflight and the SSH implementation. / 由预检与 SSH 实现共享的稳定受管 helper 协议身份。 */
public final class ManagedHelperProtocol {
    /** Current reviewed deployment protocol version. / 当前经审阅部署协议版本。 */
    public static final int VERSION = 2;

    private ManagedHelperProtocol() {
    }
}
