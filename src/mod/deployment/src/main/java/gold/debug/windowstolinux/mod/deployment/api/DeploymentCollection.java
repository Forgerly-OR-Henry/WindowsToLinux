package gold.debug.windowstolinux.mod.deployment.api;

import java.util.List;

/** Collection progress and exact resulting artifacts. / 采集进度与精确结果产物。
 * @param handoff durable handoff / 持久交接
 * @param artifacts complete artifacts / 完整产物 */
public record DeploymentCollection(DeploymentHandoff handoff, List<DeploymentArtifact> artifacts) {
    /** Copies the artifact list. / 复制产物列表。
     * @param handoff durable handoff / 持久交接
     * @param artifacts complete artifacts / 完整产物 */
    public DeploymentCollection {
        artifacts = List.copyOf(artifacts);
    }
}
