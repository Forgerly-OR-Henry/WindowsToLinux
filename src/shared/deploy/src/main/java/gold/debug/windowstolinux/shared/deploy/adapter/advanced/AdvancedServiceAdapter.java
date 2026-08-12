package gold.debug.windowstolinux.shared.deploy.adapter.advanced;

import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentPlanSupport;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.AdvancedRuntimeKind;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.util.Objects;

/**
 * Plans one bounded advanced experimental language service.
 *
 * <p>计划一个有界的高级试验语言服务。
 */
public final class AdvancedServiceAdapter implements DeploymentAdapter {
    private final AdvancedRuntimeKind kind;

    /** Creates an adapter for exactly one fixed runtime kind. / 为恰好一个固定运行时种类创建适配器。 */
    public AdvancedServiceAdapter(AdvancedRuntimeKind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    @Override public DeploymentProjectType projectType() {
        return kind.projectType();
    }

    @Override public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        return DeploymentPlanSupport.plan(request, projectType(), false, false);
    }
}
