package gold.debug.windowstolinux.shared.linux.protocol.restore;

/**
 * Closed remote activation mode. / 封闭的远程激活模式。
 */
public enum RemoteRestoreActivationMode {
    /**
     * PARALLEL LOOPBACK classification within remote restore activation mode.
     * <p>远端恢复激活模式中的并行回环分类。
     */
    PARALLEL_LOOPBACK,
    /**
     * ISOLATED STOPPED classification within remote restore activation mode.
     * <p>远端恢复激活模式中的隔离已停止分类。
     */
    ISOLATED_STOPPED,
    /**
     * SHORT STOP classification within remote restore activation mode.
     * <p>远端恢复激活模式中的短停止分类。
     */
    SHORT_STOP
}
