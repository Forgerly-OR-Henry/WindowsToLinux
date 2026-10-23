package gold.debug.windowstolinux.mod.deployment.db.release;

import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentApplicationGraph;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentReleaseBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitCommit;

/** Complete immutable runtime graph and identities of retained configuration and secret bindings. / 完整不可变运行图及保留配置和秘密绑定身份。
 * @param graph original full graph / 原完整图
 * @param bindings exact component release bindings / 精确组件发布绑定 */
public record DeploymentReleaseSnapshot(DeploymentApplicationGraph graph, List<DeploymentReleaseBinding> bindings) {
    /** Requires exact graph coverage and immutable original binding order. / 要求精确图覆盖及不可变原绑定顺序。
     * @param graph original full graph / 原完整图
     * @param bindings exact component release bindings / 精确组件发布绑定 */
    public DeploymentReleaseSnapshot {
        Objects.requireNonNull(graph);
        bindings = List.copyOf(bindings).stream().sorted(Comparator.comparing(DeploymentReleaseBinding::unitId)).toList();
        var units = new HashSet<String>();
        for (var binding : bindings)
            if (!units.add(binding.unitId()))
                throw new IllegalArgumentException("Duplicate release unit");
        if (!units.equals(new HashSet<>(graph.components().stream().map(c -> c.unit().id()).toList())))
            throw new IllegalArgumentException("Release bindings must cover the whole graph");
    }

    /** Captures complete verified records without folding publication timestamps into content identity. / 捕获完整已验证记录，不将发布时间混入内容身份。
     * @param graph full original graph / 原完整图
     * @param publications verified component records / 已验证组件记录
     * @return immutable release manifest / 不可变发布清单 */
    public static DeploymentReleaseSnapshot capture(DeploymentApplicationGraph graph,
                                                    List<DeploymentUnitCommit> publications) {
        var records = List.copyOf(publications);
        for (var component : graph.components()) {
            var record = records.stream().filter(r -> r.unit().id().equals(component.unit().id())).findFirst()
                .orElseThrow();
            if (!record.unit().equals(component.unit()) || !record.runtime().equals(component.runtime()))
                throw new IllegalArgumentException("Release differs from the reviewed graph");
        }
        return new DeploymentReleaseSnapshot(graph,
            records.stream()
                .map(r -> new DeploymentReleaseBinding(r.unit().id(), r.release().sha256(),
                    Math.toIntExact(r.configuration().revision()), r.configuration().sha256(), r.secrets()))
                .toList());
    }

    /** Original immutable content references for one unit. / 一个单元的原不可变内容引用。
     * @param unitId exact unit identity / 精确单元身份
     * @param releaseSha256 original component release digest / 原组件发布摘要
     * @param configurationRevision original immutable configuration revision / 原不可变配置修订
     * @param configurationSha256 original configuration digest / 原配置摘要
     * @param secrets original complete secret revision set / 原完整秘密修订集合 */

}
