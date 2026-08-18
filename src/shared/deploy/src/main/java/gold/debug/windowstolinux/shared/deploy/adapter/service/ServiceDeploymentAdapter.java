package gold.debug.windowstolinux.shared.deploy.adapter.service;

import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentPlanFactory;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.spi.DeploymentAdapter;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.util.Objects;

/**
 * Plans one profile-selected ordinary systemd service without accepting a command string.
 *
 * <p>计划一个由 Profile 选定的普通 systemd 服务，不接受命令字符串。
 */
public final class ServiceDeploymentAdapter implements DeploymentAdapter {
    private final ServiceDeploymentProfile profile;

    /** Creates an adapter for exactly one service profile. / 为恰好一个服务 Profile 创建适配器。 */
    public ServiceDeploymentAdapter(ServiceDeploymentProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
    @Override
    public DeploymentProjectType projectType() {
        return profile.projectType();
    }

    /** Builds the reviewed deployment plan. / 构建经审阅的部署计划。 */
    @Override
    public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        return DeploymentPlanFactory.plan(request, projectType(), false, false);
    }
}
