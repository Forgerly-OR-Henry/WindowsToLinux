package gold.debug.windowstolinux.shared.deploy.contract.spi;

import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

/**
 * Produces one deterministic plan for one supported typed deployment single-component project type.
 *
 * <p>为一个受支持的部署单组件项目类型生成一个确定性计划。
 */
public interface DeploymentAdapter {
    /**
     * Returns the supported project type.
     *
     * <p>返回受支持的项目类型。
     *
     * @return the supported project type / 受支持的项目类型
     */
    DeploymentProjectType projectType();

    /**
     * Produces the fixed transaction plan.
     *
     * <p>生成固定事务计划。
     *
     * @param request the reviewed input / 经审阅的输入
     * @return the deterministic plan / 确定性计划
     */
    ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request);
}
