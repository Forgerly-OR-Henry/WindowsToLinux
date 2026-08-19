package gold.debug.windowstolinux.app.service.deployment.multi;

import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.util.Objects;

/** Secret-free reviewed deployment request and stable identity for one component. / 一个组件不含秘密的经审阅部署请求与稳定身份。 */
public record ReviewedComponentApplication(
        String componentId,
        ReviewedDeploymentRequest request,
        ManagedApplication application
) {
    /** Validates the exact managed identity. / 验证精确受管身份。 */
    public ReviewedComponentApplication {
        componentId = Objects.requireNonNull(componentId, "componentId");
        request = Objects.requireNonNull(request, "request");
        application = Objects.requireNonNull(application, "application");
        if (!application.id().equals(request.facts().applicationId()) || !application.server().equals(request.server())) {
            throw new IllegalArgumentException("reviewed component request must match its managed identity");
        }
    }
}
