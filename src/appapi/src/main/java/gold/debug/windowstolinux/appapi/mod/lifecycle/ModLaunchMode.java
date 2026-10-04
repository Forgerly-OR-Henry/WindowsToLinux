package gold.debug.windowstolinux.appapi.mod.lifecycle;

/**
 * Normal startup or isolated recovery of unfinished work. / 正常启动或对未完成工作的隔离核对模式。
 */
public enum ModLaunchMode {
    /**
     * Regular capability and page startup. / 正常启动能力与页面。
     */
    NORMAL,
    /**
     * Restricted startup for explicit unfinished-work reconciliation. / 为显式核对未完成工作执行受限启动。
     */
    RECOVERY
}
