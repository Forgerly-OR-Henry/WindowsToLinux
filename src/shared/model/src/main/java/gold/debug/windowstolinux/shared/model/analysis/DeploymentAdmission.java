package gold.debug.windowstolinux.shared.model.analysis;

/**
 * The deterministic typed deployment admission result before a project can enter a deployment plan.
 *
 * <p>项目进入部署计划前的确定性部署准入结果。
 */
public enum DeploymentAdmission {
    /** All deterministic requirements are present. / 所有确定性要求均已具备。 */
    READY_FOR_PLANNING,
    /** Static recognition completed, but no archive, build, or target mutation is allowed. / 静态识别已完成，但不允许归档、构建或修改目标机。 */
    RECOGNITION_PREVIEW,
    /** Explicit user input is needed, but no unsafe contradiction was observed. / 需要显式用户输入，但未观察到不安全矛盾。 */
    REQUIRES_INPUT,
    /** The selected project type is unsafe or outside the current supported scope. / 选定项目类型不安全或超出当前阶段。 */
    REJECTED
}
