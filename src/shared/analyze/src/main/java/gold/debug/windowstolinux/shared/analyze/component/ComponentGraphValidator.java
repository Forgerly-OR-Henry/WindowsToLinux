package gold.debug.windowstolinux.shared.analyze.component;

import gold.debug.windowstolinux.shared.model.assessment.ComponentIssue;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import gold.debug.windowstolinux.shared.model.project.component.DeploymentComponent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates cross-component ownership, dependency, port, artifact, and data-path invariants. / 验证跨组件所有权、依赖、端口、制品及数据路径约束。
 */
final class ComponentGraphValidator {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ComponentGraphValidator() {
    }

    /**
     * Checks component identities, dependencies, shared paths and transport conflicts before accepting the deployment graph.
     * <p>接受部署图前检查组件身份、依赖、共享路径及传输冲突。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param issues issues / 问题集合
     */
    static void validate(List<DeploymentComponent> components, List<ComponentIssue> issues) {
        Map<String, DeploymentComponent> indexed = new LinkedHashMap<>();
        components.forEach(component -> indexed.put(component.componentId(), component));
        for (DeploymentComponent component : components) {
            if (component.required() && component.runtime().isEmpty()) {
                issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "REQUIRED_COMPONENT_PREVIEW_ONLY",
                        List.of(component.componentId()), "analysis.component.requiredPreview"));
            }
            if (component.isolation().arbitraryShell()) unsafe(component, issues, "ARBITRARY_SHELL_REQUIRED",
                    "analysis.component.arbitraryShell");
            if (component.isolation().hostPrivileges()) unsafe(component, issues, "HOST_PRIVILEGE_REQUIRED",
                    "analysis.component.hostPrivilege");
            if (component.isolation().deviceAccess()) unsafe(component, issues, "DEVICE_ACCESS_REQUIRED",
                    "analysis.component.deviceAccess");
            if (component.isolation().uncontrolledNetwork()) unsafe(component, issues, "UNCONTROLLED_NETWORK_REQUIRED",
                    "analysis.component.uncontrolledNetwork");
            for (String dependency : component.dependencies()) {
                if (dependency.equals(component.componentId())) {
                    issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "SELF_DEPENDENCY",
                            List.of(component.componentId()), "analysis.component.selfDependency"));
                } else if (!indexed.containsKey(dependency)) {
                    issues.add(issue(ComponentIssue.SeverityLevel.REQUIRES_INPUT, "UNKNOWN_DEPENDENCY",
                            List.of(component.componentId()), "analysis.component.unknownDependency"));
                } else if (component.runtime().isPresent() && indexed.get(dependency).runtime().isEmpty()) {
                    issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "DEPENDS_ON_PREVIEW_COMPONENT",
                            List.of(component.componentId(), dependency), "analysis.component.previewDependency"));
                }
            }
        }
        validatePairs(components, issues);
        validateData(components, issues);
        Set<String> cycle = cycleMembers(indexed);
        if (!cycle.isEmpty()) {
            issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "COMPONENT_DEPENDENCY_CYCLE",
                    List.copyOf(cycle), "analysis.component.dependencyCycle"));
        }
    }

    /**
     * Checks each component pair for conflicting resource paths and exposed transport endpoints.
     * <p>检查每对组件是否存在资源路径及暴露传输端点冲突。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param issues issues / 问题集合
     */
    private static void validatePairs(List<DeploymentComponent> components, List<ComponentIssue> issues) {
        for (int left = 0; left < components.size(); left++) {
            for (int right = left + 1; right < components.size(); right++) {
                DeploymentComponent first = components.get(left);
                DeploymentComponent second = components.get(right);
                List<String> ids = List.of(first.componentId(), second.componentId());
                if (overlap(first.sourceRoot(), second.sourceRoot())) {
                    issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "COMPONENT_ROOT_OVERLAP", ids,
                            "analysis.component.rootOverlap"));
                }
                if (pathsOverlap(first.artifactPaths(), second.artifactPaths())) {
                    issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "COMPONENT_ARTIFACT_OVERLAP", ids,
                            "analysis.component.artifactOverlap"));
                }
                Set<String> ports = new TreeSet<>(transportPorts(first));
                ports.retainAll(transportPorts(second));
                if (!ports.isEmpty()) {
                    issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "COMPONENT_PORT_CONFLICT", ids,
                            "analysis.component.portConflict"));
                }
            }
        }
    }

    /**
     * Returns declared transport endpoint keys, falling back to the component's reviewed exposed ports when needed.
     * <p>返回声明的传输端点键，必要时使用组件已审阅暴露端口。
     *
     * @param component component / 组件
     * @return declared transport endpoint keys, falling back to the component's reviewed exposed ports when needed / 声明的传输端点键，必要时使用组件已审阅暴露端口
     */
    private static Set<String> transportPorts(DeploymentComponent component) {
        var endpoints = component.runtime().map(runtime -> runtime.workload().endpoints()).orElse(List.of());
        if (!endpoints.isEmpty()) return endpoints.stream().map(endpoint -> endpoint.portKey())
                .collect(java.util.stream.Collectors.toSet());
        String protocol = component.runtime().filter(runtime -> runtime.healthCheck()
                instanceof gold.debug.windowstolinux.shared.model.health.HealthCheck.Udp).isPresent() ? "udp" : "tcp";
        return component.ports().stream().map(port -> protocol + ":" + port)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Validates data.
     * <p>校验数据。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param issues issues / 问题集合
     */
    private static void validateData(List<DeploymentComponent> components, List<ComponentIssue> issues) {
        Map<String, List<Map.Entry<String, ComponentDataPath>>> byPath = new HashMap<>();
        for (DeploymentComponent component : components) {
            for (ComponentDataPath data : component.dataPaths()) {
                byPath.computeIfAbsent(data.path(), ignored -> new ArrayList<>())
                        .add(Map.entry(component.componentId(), data));
            }
        }
        byPath.values().stream().filter(values -> values.size() > 1).forEach(values -> {
            List<String> ids = values.stream().map(Map.Entry::getKey).distinct().sorted().toList();
            if (values.stream().map(entry -> entry.getValue().schemaId()).distinct().count() > 1) {
                issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "SHARED_DATA_SCHEMA_CONFLICT", ids,
                        "analysis.component.dataSchemaConflict"));
            }
            List<ComponentDataPath> writers = values.stream().map(Map.Entry::getValue)
                    .filter(value -> value.access() == ComponentDataPath.AccessMode.READ_WRITE).toList();
            if (writers.size() > 1) {
                issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "SHARED_DATA_MULTIPLE_WRITERS", ids,
                        "analysis.component.dataWriters"));
            }
            if (writers.stream().anyMatch(value -> !value.reversible())) {
                issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "SHARED_DATA_IRREVERSIBLE_WRITE", ids,
                        "analysis.component.dataIrreversible"));
            }
        });
    }

    /**
     * Traverses components deterministically to identify every member of a dependency cycle.
     * <p>以确定顺序遍历组件，识别依赖环中的所有成员。
     *
     * @param indexed indexed / 已索引
     * @return constructed or resolved set / 构造或解析得到的集合
     */
    private static Set<String> cycleMembers(Map<String, DeploymentComponent> indexed) {
        Map<String, Integer> state = new HashMap<>();
        List<String> stack = new ArrayList<>();
        Set<String> cycle = new TreeSet<>();
        for (String id : indexed.keySet().stream().sorted().toList()) visit(id, indexed, state, stack, cycle);
        return Set.copyOf(cycle);
    }

    /**
     * Visits component graph.
     * <p>遍历组件图。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param indexed indexed / 已索引
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @param stack stack / 栈
     * @param cycle cycle / 环
     */
    private static void visit(String id, Map<String, DeploymentComponent> indexed, Map<String, Integer> state,
                              List<String> stack, Set<String> cycle) {
        if (state.getOrDefault(id, 0) != 0) return;
        state.put(id, 1);
        stack.add(id);
        for (String dependency : indexed.get(id).dependencies().stream().sorted().toList()) {
            if (!indexed.containsKey(dependency) || dependency.equals(id)) continue;
            if (state.getOrDefault(dependency, 0) == 0) {
                visit(dependency, indexed, state, stack, cycle);
            } else if (state.get(dependency) == 1) {
                cycle.addAll(stack.subList(stack.indexOf(dependency), stack.size()));
            }
        }
        stack.removeLast();
        state.put(id, 2);
    }

    /**
     * Tests the overlap predicate against the supplied evidence.
     * <p>根据所提供证据检查重叠条件。
     *
     * @param first first / 首次
     * @param second second / 第二
     * @return true when overlap predicate against the supplied evidence, false otherwise / 根据所提供证据检查重叠条件时为 true，否则为 false
     */
    private static boolean overlap(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    /**
     * Tests the paths overlap predicate against the supplied evidence.
     * <p>根据所提供证据检查路径集合重叠条件。
     *
     * @param first first / 首次
     * @param second second / 第二
     * @return true when paths overlap predicate against the supplied evidence, false otherwise / 根据所提供证据检查路径集合重叠条件时为 true，否则为 false
     */
    private static boolean pathsOverlap(List<String> first, List<String> second) {
        for (String left : first) for (String right : second) {
            Path leftPath = Path.of(left).normalize();
            Path rightPath = Path.of(right).normalize();
            if (leftPath.startsWith(rightPath) || rightPath.startsWith(leftPath)) return true;
        }
        return false;
    }

    /**
     * Adds a safety-rejection issue attributed to the current component.
     * <p>添加归属于当前组件的安全拒绝问题。
     *
     * @param component component / 组件
     * @param issues issues / 问题集合
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param key lookup key within the current contract / 当前契约内的查找键
     */
    private static void unsafe(DeploymentComponent component, List<ComponentIssue> issues, String code, String key) {
        issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, code, List.of(component.componentId()), key));
    }

    /**
     * Builds component issue from the supplied issue inputs.
     * <p>根据所提供问题输入构建组件问题。
     *
     * @param severity severity level assigned to the failure definition / 分配给失败定义的严重级别
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return component issue from the supplied issue inputs / 根据所提供问题输入构建组件问题
     */
    private static ComponentIssue issue(ComponentIssue.SeverityLevel severity, String code, List<String> components,
                                        String key) {
        return new ComponentIssue(severity, code, components, LocalizedMessage.of(key,
                "components", String.join(", ", components.stream().sorted().toList())));
    }
}
