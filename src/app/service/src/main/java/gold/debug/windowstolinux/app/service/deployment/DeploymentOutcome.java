package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.shared.deploy.plan.DeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentEvent;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Secret-free deployment summary rendered by the desktop UI.
 *
 * <p>由桌面界面呈现的无秘密部署摘要。
 *
 * @param status the {@code status} value / {@code status} 值
 * @param events the {@code events} value / {@code events} 值
 * @param handoff the {@code handoff} value / {@code handoff} 值
 */
public record DeploymentOutcome(DeploymentStatus status, List<DeploymentEvent> events, Optional<DeploymentHandoff> handoff) {
    /**
     * Creates a {@code DeploymentOutcome} instance.
     *
     * <p>创建 {@code DeploymentOutcome} 实例。
     *
     * @param status the {@code status} value / {@code status} 值
     * @param events the {@code events} value / {@code events} 值
     * @param handoff the {@code handoff} value / {@code handoff} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DeploymentOutcome {
        status = Objects.requireNonNull(status, "status");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        handoff = Objects.requireNonNull(handoff, "handoff");
        if ((status == DeploymentStatus.SUCCEEDED) != handoff.isPresent()) {
            throw new IllegalArgumentException("only a successful deployment may provide a user handoff");
        }
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
            DeploymentRequest request
    ) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(request, "request");
        Optional<DeploymentHandoff> handoff = result.status() == DeploymentStatus.SUCCEEDED
                ? Optional.of(successHandoff(request))
                : Optional.empty();
        return new DeploymentOutcome(result.status(), result.events(), handoff);
    }

    private static DeploymentHandoff successHandoff(DeploymentRequest request) {
        if (request.healthCheck() instanceof HealthCheck.Http) {
            return new DeploymentHandoff.HttpAccessUrl(request.userAccessUrl()
                    .orElseThrow(() -> new IllegalStateException("HTTP deployment is missing the reviewed user access URL"))
                    .url());
        }
        return systemdStartCommand(request);
    }

    private static DeploymentHandoff.SystemdStartCommand systemdStartCommand(DeploymentRequest request) {
        return new DeploymentHandoff.SystemdStartCommand(request.application().id(), request.application().systemdUnit(),
                request.application().ownershipManifestSha256());
    }

}
