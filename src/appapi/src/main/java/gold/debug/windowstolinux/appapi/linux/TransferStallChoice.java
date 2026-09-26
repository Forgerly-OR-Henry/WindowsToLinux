package gold.debug.windowstolinux.appapi.linux;

/** Explicit choice after the old physical transfer has stopped. / 原物理传输停止后的明确选择。 */
public enum TransferStallChoice {
    /** Reconnect and verify the original source and staging before continuation. / 重连并核验原源及暂存后继续。 */
    RETRY,
    /** Stop and retain any unconfirmed file effects for original-task recovery. / 停止并为原任务恢复保留未确认文件副作用。 */
    TERMINATE
}
