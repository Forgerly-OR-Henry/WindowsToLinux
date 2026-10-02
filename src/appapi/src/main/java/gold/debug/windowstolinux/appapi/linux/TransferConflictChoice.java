package gold.debug.windowstolinux.appapi.linux;

/** Explicit user choice for a same-name file conflict. / 同名文件冲突的明确用户选择。 */
public enum TransferConflictChoice {
    /** Publish verified content over the reviewed target. / 在已审核目标上发布验证后的内容。 */
    OVERWRITE,
    /** Publish under an automatically selected unused name. / 使用自动选择的未占用名称发布。 */
    KEEP_BOTH,
    /** Leave this item unchanged and report a known failure. / 保持此项不变并报告明确失败。 */
    SKIP
}
