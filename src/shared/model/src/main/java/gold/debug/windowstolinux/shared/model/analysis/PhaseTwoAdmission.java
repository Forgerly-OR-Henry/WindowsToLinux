package gold.debug.windowstolinux.shared.model.analysis;

/**
 * The deterministic Phase Two admission result before a project can enter a deployment plan.
 *
 * <p>项目进入部署计划前的确定性二期准入结果。
 */
public enum PhaseTwoAdmission {
    /** All deterministic requirements are present. / 所有确定性要求均已具备。 */
    READY_FOR_PLANNING,
    /** Explicit user input is needed, but no unsafe contradiction was observed. / 需要显式用户输入，但未观察到不安全矛盾。 */
    REQUIRES_INPUT,
    /** The selected project type is unsafe or outside the current phase. / 选定项目类型不安全或超出当前阶段。 */
    REJECTED
}
