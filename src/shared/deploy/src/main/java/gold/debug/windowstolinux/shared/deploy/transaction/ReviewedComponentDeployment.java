package gold.debug.windowstolinux.shared.deploy.transaction;

import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.util.List;
import java.util.Objects;

/**
 * Reviewed inputs and stable managed identity for one application component.
 *
 * <p>一个应用组件的经审阅输入与稳定受管身份。
 */
public record ReviewedComponentDeployment(
        String componentId,
        ReviewedDeploymentRequest request,
        ManagedApplication application,
        List<ResolvedSecretRevision> resolvedSecrets
) {
    /** Validates exact identity and secret-revision binding. / 验证精确身份与秘密修订绑定。 */
    public ReviewedComponentDeployment {
        componentId = Objects.requireNonNull(componentId, "componentId").trim();
        if (!componentId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("componentId must be a bounded managed identifier");
        }
        request = Objects.requireNonNull(request, "request");
        application = Objects.requireNonNull(application, "application");
        resolvedSecrets = List.copyOf(Objects.requireNonNull(resolvedSecrets, "resolvedSecrets"));
        if (!application.id().equals(request.facts().applicationId()) || !application.server().equals(request.server())) {
            throw new IllegalArgumentException("component request and managed identity must match");
        }
        if (!resolvedSecrets.stream().map(ResolvedSecretRevision::reference).toList().equals(request.secretReferences())) {
            throw new IllegalArgumentException("resolved component secrets must exactly match reviewed references");
        }
    }
}
