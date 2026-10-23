package gold.debug.windowstolinux.mod.deployment.service.core.config.error;

import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/** Rejects deterministic deployment input with a public, non-secret failure description. / 以公开且不含秘密的失败说明拒绝确定性部署输入。 */
public final class DeploymentConfigException extends IllegalArgumentException {
    /** Stable public configuration problem. / 稳定公开配置问题。 */
    private final Problem problem;

    /** Binds a constant diagnostic to the observed failure. / 将固定诊断绑定到已观察失败。
     * @param problem public safe diagnostic / 公开安全诊断
     * @param cause original local cause / 原本地原因 */
    private DeploymentConfigException(Problem problem, Throwable cause) {
        super(problem.message(), cause);
        this.problem = problem;
    }

    /** Preserves the original configuration event code for rejected values. / 为被拒绝内容保留原配置事件码。
     * @param eventCode stable configuration event / 稳定配置事件
     * @param message constant non-secret explanation / 不含秘密的固定说明
     * @return structured invalid-input failure / 结构化输入无效失败 */
    public static DeploymentConfigException create(String eventCode, String message) {
        return new DeploymentConfigException(
            new Problem(ProblemCode.INVALID_INPUT, eventCode, message, Map.of(), Optional.empty()), null);
    }

    /** Reports an unavailable deterministic implementation with its original cause. / 携带原原因报告确定性实现不可用。
     * @param eventCode stable configuration event / 稳定配置事件
     * @param message constant non-secret explanation / 不含秘密的固定说明
     * @param cause original local cause / 原本地原因
     * @return structured capability failure / 结构化能力失败 */
    public static DeploymentConfigException create(String eventCode, String message, Throwable cause) {
        return new DeploymentConfigException(
            new Problem(ProblemCode.UNAVAILABLE, eventCode, message, Map.of(), Optional.empty()), cause);
    }

    /** Returns the stable public failure, never a submitted value. / 返回稳定公开失败，绝不返回提交内容。
     * @return safe public failure / 安全公开失败 */
    public Problem problem() {
        return problem;
    }
}
