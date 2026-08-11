package gold.debug.windowstolinux.shared.deploy.result;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Terminal deployment outcome, including the distinct rollback result.
 *
 * <p>部署终态结果，包括独立的回滚结果。
 *
 * @param status the {@code status} value / {@code status} 值
 * @param events the {@code events} value / {@code events} 值
 * @param finalObservation the {@code finalObservation} value / {@code finalObservation} 值
 * @param publishedArtifactSha256 the {@code publishedArtifactSha256} value / {@code publishedArtifactSha256} 值
 */
public record DeploymentResult(
        DeploymentStatus status,
        List<DeploymentEvent> events,
        Optional<LifecycleObservation> finalObservation,
        Optional<String> publishedArtifactSha256
) {
    /**
     * Creates a {@code DeploymentResult} instance.
     *
     * <p>创建 {@code DeploymentResult} 实例。
     *
     * @param status the {@code status} value / {@code status} 值
     * @param events the {@code events} value / {@code events} 值
     * @param finalObservation the {@code finalObservation} value / {@code finalObservation} 值
     * @param publishedArtifactSha256 the {@code publishedArtifactSha256} value / {@code publishedArtifactSha256} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DeploymentResult {
        status = Objects.requireNonNull(status, "status");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        finalObservation = Objects.requireNonNull(finalObservation, "finalObservation");
        publishedArtifactSha256 = Objects.requireNonNull(publishedArtifactSha256, "publishedArtifactSha256");
        if (status == DeploymentStatus.SUCCEEDED != publishedArtifactSha256.isPresent()) {
            throw new IllegalArgumentException("only a successful deployment may report its published artifact digest");
        }
        publishedArtifactSha256.ifPresent(digest -> {
            if (!digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("publishedArtifactSha256 must be a lowercase SHA-256");
            }
        });
    }
}
