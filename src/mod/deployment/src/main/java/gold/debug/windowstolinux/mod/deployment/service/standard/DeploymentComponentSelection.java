package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectPart;

/** Validates the reviewed graph and derives roots from explicit inputs rather than discovery membership. / 校验审阅图，并从明确输入派生根，不要求属于发现全集。 */
final class DeploymentComponentSelection {
    /** No instances. / 不创建实例。 */
    private DeploymentComponentSelection() {
    }

    /** Resolves explicit manual roots while keeping automatic choices bound to actual discovery. / 解析明确手工根，同时使自动选择仍绑定实际发现。
     * @param discovered actual frozen-source suggestions / 实际冻结来源建议
     * @param selection complete reviewed graph / 完整审阅图
     * @return validated deterministic component roots / 已校验确定性组件根 */
    static List<DiscoveredProjectPart> resolve(List<DiscoveredProjectPart> discovered, DeploymentStaticWorkflow.Selection selection) {
        if (!selection.applicationId().matches("[a-z0-9][a-z0-9-]{0,62}")
            || selection.choices().isEmpty() || selection.choices().size() > 16
            || selection.choices().size() == 1 != selection.healthComponent().isEmpty()
            || selection.healthComponent().filter(id -> !selection.choices().containsKey(id)).isPresent())
            throw new IllegalArgumentException("Reviewed application identity or health owner is incomplete");
        if (selection.choices().values().stream().allMatch(choice -> choice.inputs().isEmpty())
            && !selection.choices().keySet().equals(discovered.stream().map(DiscoveredProjectPart::id)
            .collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("Automatic selection differs from complete frozen discovery");
        var result = new ArrayList<DiscoveredProjectPart>();
        for (String id : selection.choices().keySet().stream().sorted().toList()) {
            var choice = selection.choices().get(id);
            if (!id.matches("[a-z0-9][a-z0-9-]{0,62}") || !choice.type().deployable()
                || choice.dependencies().contains(id) || !selection.choices().keySet().containsAll(choice.dependencies()))
                throw new IllegalArgumentException("Invalid reviewed component identity or dependency");
            if (choice.inputs().isPresent()) {
                var inputs = choice.inputs().orElseThrow();
                result.add(new DiscoveredProjectPart(id, Path.of(inputs.relativeRoot()), List.of(choice.type())));
            } else {
                var original = discovered.stream().filter(part -> part.id().equals(id)).findFirst().orElseThrow(
                    () -> new IllegalArgumentException("Automatic component is not in frozen discovery"));
                if (!original.types().contains(choice.type()))
                    throw new IllegalArgumentException("Automatic type differs from discovery");
                result.add(original);
            }
        }
        var done = new HashSet<String>();
        for (String id : selection.choices().keySet()) visit(id, selection, new HashSet<>(), done);
        return List.copyOf(result);
    }

    /** Rejects a real reviewed dependency cycle before component work begins. / 组件工作开始前拒绝真实审阅依赖环。
     * @param id current component / 当前组件
     * @param selection complete graph / 完整图
     * @param path active traversal / 活动遍历
     * @param done proved acyclic nodes / 已证明无环节点 */
    private static void visit(String id, DeploymentStaticWorkflow.Selection selection, Set<String> path, Set<String> done) {
        if (done.contains(id)) return;
        if (!path.add(id)) throw new IllegalArgumentException("Reviewed component dependencies form a cycle");
        for (String predecessor : selection.choices().get(id).dependencies()) visit(predecessor, selection, path, done);
        path.remove(id);
        done.add(id);
    }
}
