package gold.debug.windowstolinux.mod.deployment.ai;

import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Known unsent refusal requiring a different independently reviewed lower-risk action. / 要求不同且独立审核的低风险动作的已知未发送拒绝。 */
public final class DeploymentLowRiskRequiredException extends ApiException {
    /** Whether the first helper approval was refused before any preparation effect. / 首个helper审批是否在任何准备副作用前被拒绝。 */
    private final boolean preparationUnsent;

    /** Binds a known refusal to the authentic original task without claiming uncertain effects. / 将已知拒绝绑定真实原任务，不宣称效果未知。
     * @param task authentic original task / 真实原任务
     * @param reason nonsecret retry constraint / 不含秘密的重试约束 */
    public DeploymentLowRiskRequiredException(TaskContext task, String reason) {
        this(task, reason, false);
    }

    /** Captures only the original classified refusal and its actual preparation boundary. / 仅记录原分类拒绝及其真实准备边界。
     * @param task authentic original task / 真实原任务
     * @param reason nonsecret constraint / 不含秘密的约束
     * @param preparationUnsent confirmed refusal before the first preparation effect / 首个准备副作用前已确认拒绝 */
    private DeploymentLowRiskRequiredException(TaskContext task, String reason, boolean preparationUnsent) {
        super(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_LOWER_RISK_REQUIRED", reason,
            Map.of("task", task.id().toString()), Optional.of(task.id())));
        this.preparationUnsent = preparationUnsent;
    }

    /** Records a first-helper refusal without treating later candidate refusals as effect-free. / 记录首个helper拒绝，不把后续候选拒绝当作无副作用。
     * @param task authentic original task / 真实原任务
     * @param refusal actual first-helper approval refusal / 实际首个helper审批拒绝
     * @return the original refusal classified before preparation effects / 分类为准备副作用前的原拒绝 */
    public static DeploymentLowRiskRequiredException beforePreparation(TaskContext task, DeploymentLowRiskRequiredException refusal) {
        var known = new DeploymentLowRiskRequiredException(task, refusal.problem().message(), true);
        known.initCause(refusal);
        return known;
    }

    /** Reports only the captured first-helper boundary, not authority to execute a replacement. / 仅报告已记录首个helper边界，不授予替代执行权限。
     * @return whether no preparation effect preceded this refusal / 此拒绝前是否没有准备副作用 */
    public boolean preparationUnsent() {
        return preparationUnsent;
    }
}
