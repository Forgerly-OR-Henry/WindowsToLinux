package gold.debug.windowstolinux.shared.deploy.adapter;

import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectType;

/**
 * Produces one deterministic plan for one supported Phase Two single-component project type.
 *
 * <p>为一个受支持的二期单组件项目类型生成一个确定性计划。
 */
public interface PhaseTwoDeploymentAdapter {
    /**
     * Returns the supported project type.
     *
     * <p>返回受支持的项目类型。
     *
     * @return the supported project type / 受支持的项目类型
     */
    PhaseTwoProjectType projectType();

    /**
     * Produces the fixed transaction plan.
     *
     * <p>生成固定事务计划。
     *
     * @param request the reviewed input / 经审阅的输入
     * @return the deterministic plan / 确定性计划
     */
    PhaseTwoDeploymentPlan plan(PhaseTwoDeploymentRequest request);
}
