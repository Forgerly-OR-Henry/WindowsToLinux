package gold.debug.windowstolinux.appapi.file;

/** Deletion effects cannot be silently upgraded. / 删除效果不得静默升级。 */
public enum DeleteEffect {
    /** Recoverable deletion retaining restoration information / 保留恢复信息的可恢复删除.
     */
    RECYCLE,
    /** Explicit irreversible removal of the exact object / 精确对象的明确不可恢复删除.
     */
    PERMANENT
}
