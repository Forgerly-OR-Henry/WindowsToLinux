package gold.debug.windowstolinux.mod.deployment.api;

/** Verified lifecycle before/after status. / 已验证的生命周期前后状态。
 * @param application exact target / 精确目标
 * @param before preceding observation / 操作前观察
 * @param after following observation / 操作后观察
 * @param evidence bounded non-secret evidence / 有界无秘密依据 */
public record DeploymentLifecycleResult(DeploymentApplicationRef application, ApplicationRunState before,
        ApplicationRunState after, String evidence) {}
