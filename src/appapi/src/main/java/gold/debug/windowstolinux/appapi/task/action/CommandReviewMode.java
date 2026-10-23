package gold.debug.windowstolinux.appapi.task.action;

/** Human decision policy after independent action review. / 独立动作审核后的人工确认策略。 */
public enum CommandReviewMode {
    /** Requires a human decision for each reviewed action. / 每项经审核动作均要求人工决定。 */
    MANUAL_REVIEW,
    /** Asks only when independent review identifies a user tradeoff. / 独立审核发现用户取舍时才询问。 */
    AUTOMATIC,
    /** Confirms the workflow at startup and executes reviewed actions within its scope. / 启动时确认流程，在其作用域内执行经审核动作。 */
    FULL_CONTROL
}
