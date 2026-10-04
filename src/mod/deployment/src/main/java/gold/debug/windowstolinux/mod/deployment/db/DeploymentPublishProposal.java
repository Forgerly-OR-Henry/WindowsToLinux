package gold.debug.windowstolinux.mod.deployment.db;

import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentApplicationGraph;

/** Persisted non-secret business input, independent of the service result wrapper. / 与服务结果封套独立的已持久非秘密业务输入。
 * @param graph exact original complete application graph / 精确原完整应用图
 * @param units exact original component commits / 精确原组件提交 */
public record DeploymentPublishProposal(DeploymentApplicationGraph graph, List<DeploymentUnitCommit> units) {
    /** Requires a complete immutable original proposal. / 要求完整不可变原提案。
     * @param graph original graph / 原图
     * @param units exact component inputs / 精确组件输入 */
    public DeploymentPublishProposal {
        Objects.requireNonNull(graph);
        units = List.copyOf(units);
        DeploymentReleaseSnapshot.capture(graph, units);
    }
}
