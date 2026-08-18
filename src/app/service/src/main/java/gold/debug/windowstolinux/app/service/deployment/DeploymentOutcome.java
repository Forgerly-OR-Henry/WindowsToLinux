package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentEvent;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Secret-free deployment summary rendered by the desktop UI.
 *
 * <p>由桌面界面呈现的无秘密部署摘要。
 *
 * @param result the complete typed deployment result / 完整的类型化部署结果
 * @param handoff the {@code handoff} value / {@code handoff} 值
 */
public record DeploymentOutcome(DeploymentResult result, Optional<DeploymentHandoff> handoff) {
    /**
     * Creates a {@code DeploymentOutcome} instance.
     *
     * <p>创建 {@code DeploymentOutcome} 实例。
     *
     * @param result the {@code result} value / {@code result} 值
     * @param handoff the {@code handoff} value / {@code handoff} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DeploymentOutcome {
        result = Objects.requireNonNull(result, "result");
        handoff = Objects.requireNonNull(handoff, "handoff");
        if ((result.status() == DeploymentStatus.SUCCEEDED) != handoff.isPresent()) {
            throw new IllegalArgumentException("only a successful deployment may provide a user handoff");
        }
    }

    /** Returns the terminal deployment status. / 返回部署终态。 */
    public DeploymentStatus status() {
        return result.status();
    }

    /** Returns the ordered deployment evidence events. / 返回有序部署证据事件。 */
    public List<DeploymentEvent> events() {
        return result.events();
    }

    /**
     * Creates a value through {@code from}.
     *
     * <p>通过 {@code from} 创建值。
     *
     * @param result the {@code result} value / {@code result} 值
     * @param request the {@code request} value / {@code request} 值
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public static DeploymentOutcome from(
            DeploymentResult result,
            ReviewedDeploymentRequest request,
            ManagedApplication application
    ) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(application, "application");
        Optional<DeploymentHandoff> handoff = result.status() == DeploymentStatus.SUCCEEDED
                ? Optional.of(successHandoff(request, application))
                : Optional.empty();
        return new DeploymentOutcome(result, handoff);
    }

    private static DeploymentHandoff successHandoff(ReviewedDeploymentRequest request, ManagedApplication application) {
        if (request.runtime().healthCheck() instanceof HealthCheck.Http) {
            return new DeploymentHandoff.HttpAccessUrl(request.userAccessUrl()
                    .orElseThrow(() -> new IllegalStateException("HTTP deployment is missing the reviewed user access URL"))
                    .url());
        }
        return new DeploymentHandoff.SystemdStartCommand(request.facts().applicationId(), application.systemdUnit(),
                application.ownershipManifestSha256());
    }

}
