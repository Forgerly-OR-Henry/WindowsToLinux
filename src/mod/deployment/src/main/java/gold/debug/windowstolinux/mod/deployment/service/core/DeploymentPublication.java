package gold.debug.windowstolinux.mod.deployment.service.core;

import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.mod.deployment.db.DeploymentUnitCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentApplicationGraph;

/** Verified author result ready for one atomic local publication. / 可供一次本机原子发布的作者已核实结果。
 * @param graph actual complete application / 实际完整应用图
 * @param units actual verified unit publications / 实际已核实单元发布 */
public record DeploymentPublication(DeploymentApplicationGraph graph, List<DeploymentUnitCommit> units) {
    /** Copies immutable publication evidence. / 复制不可变发布证据。
     * @param graph actual application / 实际应用
     * @param units actual units / 实际单元 */
    public DeploymentPublication {
        Objects.requireNonNull(graph);
        units = List.copyOf(units);
    }
}
