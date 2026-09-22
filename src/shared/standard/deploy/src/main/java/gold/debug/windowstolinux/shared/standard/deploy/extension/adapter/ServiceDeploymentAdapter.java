package gold.debug.windowstolinux.shared.standard.deploy.extension.adapter;

import java.util.Objects;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.standard.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.standard.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.standard.deploy.contract.spi.DeploymentAdapter;
import gold.debug.windowstolinux.shared.standard.deploy.extension.adapter.DeploymentPlanFactory;

/**
 * Plans one profile-selected ordinary systemd service without accepting a command string.
 *
 *  <p>计划一个由 Profile 选定的普通 systemd 服务，不接受命令字符串。
 */
public final class ServiceDeploymentAdapter implements DeploymentAdapter {
    /**
     * Connection or provider settings supplied to the operation.
     * <p>提供给操作的连接或提供者设置。
     */
    private final ServiceDeploymentProfile profile;

    /**
     * Creates an adapter for exactly one service profile. / 为恰好一个服务 Profile 创建适配器。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ServiceDeploymentAdapter(ServiceDeploymentProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    /**
     * Returns the supported deployment project type. / 返回支持的部署项目类型。
     *
     * @return the supported deployment project type / 支持的部署项目类型
     */
    @Override
    public DeploymentProjectType projectType() {
        return profile.projectType();
    }

    /**
     * Builds the reviewed deployment plan. / 构建经审阅的部署计划。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return the reviewed deployment plan / 经审阅的部署计划
     */
    @Override
    public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        return DeploymentPlanFactory.plan(request, projectType(), false, false);
    }
}
