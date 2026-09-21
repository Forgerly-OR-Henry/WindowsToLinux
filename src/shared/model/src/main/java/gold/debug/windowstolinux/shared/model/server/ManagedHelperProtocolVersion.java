package gold.debug.windowstolinux.shared.model.server;

/**
 * Canonical managed-helper protocol version shared across capability facts and remote execution. / 能力事实与远端执行共享的受管 helper 规范协议版本。
 */
public final class ManagedHelperProtocolVersion {
    /**
     * Current reviewed helper protocol version. / 当前经审阅的 helper 协议版本。
     */
    public static final int CURRENT = 9;

    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ManagedHelperProtocolVersion() {
    }
}
