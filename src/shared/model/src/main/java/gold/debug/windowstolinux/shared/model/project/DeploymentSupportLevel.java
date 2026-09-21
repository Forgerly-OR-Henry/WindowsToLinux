package gold.debug.windowstolinux.shared.model.project;

/**
 * The evidence-backed support level exposed by analysis, planning, and results.
 *
 *  <p>由证据支撑并由分析、计划和结果公开的支持等级。
 */
public enum DeploymentSupportLevel {
    /**
     * No bounded language or workload identity was established. / 未建立有界语言或工作负载身份。
     */
    UNRECOGNIZED(false),
    /**
     * Static recognition only; target mutation is forbidden. / 仅静态识别；禁止修改目标机。
     */
    RECOGNITION_PREVIEW(false),
    /**
     * A bounded adapter may run only after explicit test-environment confirmation. / 有界适配器仅可在明确确认测试环境后运行。
     */
    EXPERIMENTAL_ADAPTER(true),
    /**
     * End-to-end evidence exists inside the declared validation matrix. / 声明的验证矩阵内存在端到端证据。
     */
    FORMALLY_SUPPORTED(true);

    /**
     * Deployable.
     * <p>可部署。
     */
    private final boolean deployable;

    /**
     * Binds the supplied dependencies and state for deployment support level.
     * <p>为部署支持级别绑定传入的依赖及状态。
     *
     * @param deployable deployable / 可部署
     */
    DeploymentSupportLevel(boolean deployable) {
        this.deployable = deployable;
    }

    /**
     * Returns whether this level can enter a reviewed deployment plan. / 返回此等级能否进入经审阅部署计划。
     *
     * @return true when returns whether this level can enter a reviewed deployment plan, false otherwise / 返回此等级能否进入经审阅部署计划时为 true，否则为 false
     */
    public boolean deployable() {
        return deployable;
    }
}
