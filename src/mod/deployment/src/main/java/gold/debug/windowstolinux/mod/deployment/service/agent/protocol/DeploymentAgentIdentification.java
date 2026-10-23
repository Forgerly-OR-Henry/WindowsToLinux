package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import java.util.*;

/** Independent source hypothesis before optional deterministic analysis. / 可选确定性分析之前的独立源码判断。
 * @param component component identity / 组件身份
 * @param root snapshot-relative source root / 快照内源码根
 * @param technology independent technology finding / 独立技术发现
 * @param runtime independent runtime finding / 独立运行发现
 * @param resources resource needs / 资源需求
 * @param uncertainties remaining unknowns / 待确认事项
 * @param evidence actual evidence IDs / 实际证据标识
 */
public record DeploymentAgentIdentification(String component, String root, String technology, String runtime,
                                            String resources, String uncertainties, List<String> evidence) {
    /** Rejects traversal and unbounded hypotheses. / 拒绝路径穿越和无界判断。
     * @param component Selected component identity / 选定组件身份
     * @param root Component source root / 组件源码根
     * @param technology Source-backed technology conclusion / 来源支持技术结论
     * @param runtime Reviewed runtime contract / 已审阅运行合同
     * @param resources Resource requirements / 资源需求
     * @param uncertainties Unresolved business or source facts / 未解决业务或来源事实
     * @param evidence actual evidence IDs / 实际证据标识
     */
    public DeploymentAgentIdentification {
        component = componentId(component);
        root = sourceRoot(root);
        for (String value : List.of(technology, runtime, resources, uncertainties))
            if (value.isBlank() || value.length() > 1000)
                throw new IllegalArgumentException("Bounded independent findings required");
        evidence = List.copyOf(evidence);
        if (evidence.isEmpty() || evidence.size() > 32)
            throw new IllegalArgumentException("Independent source evidence required");
    }

    /** Uses the same component identity in browsing, findings and plans. / 浏览、发现与计划使用相同组件身份。
     * @param value exact supplied component ID / 精确给定组件标识
     * @return unchanged valid identity / 未改变的有效身份 */
    public static String componentId(String value) {
        if (!Objects.requireNonNull(value).matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("Expected component ID matching [a-z0-9][a-z0-9-]{0,62}; a source path is not a component ID");
        return value;
    }

    /** Normalizes only a safe relative root. / 只规范化安全相对根。
     * @param root supplied root / 给定根
     * @return normalized root / 规范根 */
    public static String sourceRoot(String root) {
        Objects.requireNonNull(root);
        if (root.equals(".")) return root;
        if (root.length() > 500 || root.startsWith("/") || root.contains("\\") || root.contains(":"))
            throw new IllegalArgumentException("Invalid source root");
        for (String part : root.split("/", -1))
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.chars().anyMatch(c -> c < 32))
                throw new IllegalArgumentException("Invalid source root segment");
        return root;
    }
}
