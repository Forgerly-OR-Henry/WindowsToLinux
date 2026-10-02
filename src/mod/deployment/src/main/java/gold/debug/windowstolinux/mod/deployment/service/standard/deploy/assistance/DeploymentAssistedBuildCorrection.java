package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.assistance;

import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.util.Map;


import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan;

/** Reopens only the four typed technical fields after a physically confirmed build failure. / 物理确认构建失败后仅重开四项类型化技术字段。 */
@FunctionalInterface
public interface DeploymentAssistedBuildCorrection {
    /** Produces real human-reviewed fixed-source values without sending remote work. / 产出真实人工审阅的固定来源值，不发送远端操作。
     * @param previous actually executed plan / 实际执行计划
     * @param failure complete known compiler evidence / 完整已知编译依据
     * @param task authentic original task / 真实原任务
     * @return accepted correction or a classified refusal / 已接受纠正或已分类拒绝
     * @throws Exception if review or model evidence remains unknown / 审阅或模型依据仍未知时 */
    OperationResult<Correction> review(ReviewedDeploymentPlan previous, Problem failure, TaskContext task) throws Exception;

    /** Complete typed transition, with actual old and new form values. / 含实际旧、新表单值的完整类型化转换。
     * @param plan actual newly planned correction / 实际重新规划纠正
     * @param previousInputs original four input values / 原四项输入值
     * @param nextInputs newly confirmed four values / 新确认四项值
     * @param catalogBranch reviewed fixed catalog branch / 已审阅固定目录分支 */
    record Correction(ReviewedDeploymentPlan plan, Map<String, String> previousInputs, Map<String, String> nextInputs,
                      int catalogBranch) {
        /** Copies exact typed answers and refuses unbounded tool candidates. / 复制精确类型化答案并拒绝无界工具候选。
         * @param plan actual new plan / 实际新计划
         * @param previousInputs original four values / 原四项值
         * @param nextInputs newly reviewed values / 新审阅值
         * @param catalogBranch fixed zero or one branch / 固定零或一分支 */
        public Correction {
            java.util.Objects.requireNonNull(plan);
            previousInputs = Map.copyOf(previousInputs);
            nextInputs = Map.copyOf(nextInputs);
            if (catalogBranch < 0 || catalogBranch > 1)
                throw new IllegalArgumentException("Only original fixed catalog branches are supported");
        }
    }
}
