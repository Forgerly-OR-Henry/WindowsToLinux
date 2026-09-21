package gold.debug.windowstolinux.shared.deploy.contract.spi;

/**
 * Application-wide restore activation mode selected from explicit runtime capabilities. / 依据明确运行时能力选择的整应用恢复激活模式。
 */
public enum CandidatePortMode {
    /**
     * Every component can run on loopback-only candidate ports before commit. / 每个组件均可在提交前使用仅回环候选端口运行。
     */
    PARALLEL_LOOPBACK,
    /**
     * At least one component has no typed port override and requires a stopped switch. / 至少一个组件没有类型化端口覆盖，必须停机切换。
     */
    ISOLATED_STOPPED,
    /**
     * SHORT STOP classification within candidate port mode.
     * <p>候选端口模式中的短停止分类。
     */
    SHORT_STOP
}
