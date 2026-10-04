package gold.debug.windowstolinux.appapi.task.action;

/** Platform permission policy captured for one user-started workflow, independent of AI review. / 为一个用户启动流程捕获的平台权限策略，独立于 AI 审批。 */
public enum AuthorizationMode {
    /** Asks before each software operation. / 每次软件操作前询问。 */
    ONCE,
    /** Reuses an approved operation meaning and actual targets only within this workflow. / 仅在本流程内复用已批准操作含义及实际目标。 */
    GENERAL,
    /** Automatically authorizes declared operations within this user-started workflow. / 自动授权本次用户启动流程范围内已声明的操作。 */
    FULL
}
