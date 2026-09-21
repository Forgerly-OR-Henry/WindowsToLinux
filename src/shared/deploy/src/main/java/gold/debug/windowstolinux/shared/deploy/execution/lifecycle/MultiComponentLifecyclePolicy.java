package gold.debug.windowstolinux.shared.deploy.execution.lifecycle;

import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.ComponentLifecycleResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.MultiComponentLifecycleResult;
import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationAutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationRuntimeState;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies pure target, dependency-impact, final-state, and aggregation rules. / 应用纯目标、依赖影响、最终状态与汇总规则。
 */
final class MultiComponentLifecyclePolicy {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private MultiComponentLifecyclePolicy() {
    }

    /**
     * Validates multi component lifecycle result.
     * <p>校验多组件生命周期结果。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param targets targets / 目标集合
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param observations observations / 观测集合
     * @return constructed or resolved multi component lifecycle result; null when no matching value is available / 构造或解析得到的多组件生命周期结果；没有匹配值时为 null
     */
    static MultiComponentLifecycleResult validate(MultiComponentDeploymentPlan plan, Set<String> targets,
                                                  LifecycleAction action,
                                                  Map<String, LifecycleObservation> observations) {
        if (observations.values().stream().anyMatch(observation -> !verified(observation))) {
            return result(false, LocalizedMessage.of("lifecycle.applicationStateUnverified"),
                    plan, observations, Set.of(), observations.keySet());
        }
        if (action == LifecycleAction.REFRESH_STATUS) return null;
        if (targets.stream().anyMatch(id -> observations.get(id).runtimeState() == RuntimeState.INSTALLED))
            return result(false, LocalizedMessage.of("lifecycle.onDemandCommandRequired"), plan, observations, Set.of(), targets);
        if (action != LifecycleAction.STOP && action != LifecycleAction.DISABLE_AUTOSTART
                && targets.stream().anyMatch(id -> observations.get(id).runtimeState() == RuntimeState.ERROR)) {
            return result(false, LocalizedMessage.of("lifecycle.errorRequiresStop"),
                    plan, observations, Set.of(), targets);
        }
        Set<String> unsafe = dependencyImpact(plan, targets, action, observations);
        if (!unsafe.isEmpty()) {
            return result(false, LocalizedMessage.of("lifecycle.applicationDependencyImpact",
                            Map.of("components", String.join(", ", unsafe))),
                    plan, observations, Set.of(), unsafe);
        }
        return null;
    }

    /**
     * Reports whether the multi component lifecycle policy condition holds for this contract.
     * <p>判断当前契约是否满足多组件生命周期策略条件。
     *
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param observation observation / 观测
     * @return true when multi component lifecycle policy condition holds for this contract, false otherwise / 当前契约是否满足多组件生命周期策略条件时为 true，否则为 false
     */
    static boolean matches(LifecycleAction action, LifecycleObservation observation) {
        if (!observation.ownershipVerified()) return false;
        return switch (action) {
            case START -> observation.runtimeState() == RuntimeState.RUNNING;
            case STOP -> observation.runtimeState() == RuntimeState.STOPPED;
            case ENABLE_AUTOSTART -> observation.autostartState() == AutostartState.ENABLED;
            case DISABLE_AUTOSTART -> observation.autostartState() == AutostartState.DISABLED;
            case RESTART -> observation.runtimeState() == RuntimeState.RUNNING;
            case REFRESH_STATUS -> true;
        };
    }

    /**
     * Reports whether the final condition holds for this contract.
     * <p>判断当前契约是否满足最终条件。
     *
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param observation observation / 观测
     * @return true when final condition holds for this contract, false otherwise / 当前契约是否满足最终条件时为 true，否则为 false
     */
    static boolean matchesFinal(LifecycleAction action, LifecycleObservation observation) {
        return matches(action == LifecycleAction.RESTART ? LifecycleAction.START : action, observation);
    }

    /**
     * Creates or preserves the module-owned failure for the supplied cause and diagnostic evidence.
     * <p>为所提供原因及诊断证据创建或保留模块自有失败。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param observations observations / 观测集合
     * @param attempted attempted / 已尝试
     * @param failed failed / 失败
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return or preserves the module-owned failure for the supplied cause and diagnostic evidence / 为所提供原因及诊断证据创建或保留模块自有失败
     */
    static MultiComponentLifecycleResult failure(MultiComponentDeploymentPlan plan,
                                                 Map<String, LifecycleObservation> observations,
                                                 Set<String> attempted, Set<String> failed,
                                                 FailureDescriptor failure) {
        Set<String> unresolved = new LinkedHashSet<>(failed);
        unresolved.addAll(attempted);
        unresolved.addAll(plan.startOrder().stream().filter(id -> !observations.containsKey(id)).toList());
        MultiComponentLifecycleResult result = result(false, failure.userMessage(),
                plan, observations, attempted, unresolved);
        return new MultiComponentLifecycleResult(false, failure.userMessage(), result.runtimeState(),
                result.autostartState(), result.componentResults(), failure.operationIdentity(),
                java.util.Optional.of(failure), List.of());
    }

    /**
     * Builds multi component lifecycle result from the supplied result inputs.
     * <p>根据所提供结果输入构建多组件生命周期结果。
     *
     * @param accepted accepted / 已接受
     * @param message localized explanation / 本地化说明
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param observations observations / 观测集合
     * @param attempted attempted / 已尝试
     * @param failed failed / 失败
     * @return multi component lifecycle result from the supplied result inputs / 根据所提供结果输入构建多组件生命周期结果
     */
    static MultiComponentLifecycleResult result(boolean accepted, LocalizedMessage message,
                                                MultiComponentDeploymentPlan plan,
                                                Map<String, LifecycleObservation> observations,
                                                Set<String> attempted, Set<String> failed) {
        List<ComponentLifecycleResult> componentResults = plan.startOrder().stream().map(id -> {
            LifecycleObservation observation = observations.get(id);
            boolean componentAccepted = verified(observation) && !failed.contains(id);
            LocalizedMessage componentMessage = failed.contains(id)
                    ? LocalizedMessage.of("lifecycle.componentFailed")
                    : observation == null ? LocalizedMessage.of("lifecycle.componentUnobserved")
                    : LocalizedMessage.of("lifecycle.componentObserved");
            return new ComponentLifecycleResult(id, attempted.contains(id), componentAccepted, componentMessage,
                    java.util.Optional.ofNullable(observation));
        }).toList();
        boolean applicationAccepted = accepted && componentResults.stream().allMatch(ComponentLifecycleResult::accepted);
        return new MultiComponentLifecycleResult(applicationAccepted, message,
                observations.size() == plan.startOrder().size() ? runtime(observations.values())
                        : ApplicationRuntimeState.UNKNOWN,
                observations.size() == plan.startOrder().size() ? autostart(observations.values())
                        : ApplicationAutostartState.UNKNOWN,
                componentResults);
    }

    /**
     * Indexes managed components in plan order and validates that they match the deployment plan.
     * <p>按计划顺序索引受管组件，并校验其与部署计划匹配。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param managed managed / 受管
     * @return constructed or resolved linked hash map / 构造或解析得到的Linked哈希映射
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static LinkedHashMap<String, ManagedComponentLifecycle> components(
            MultiComponentDeploymentPlan plan, List<ManagedComponentLifecycle> managed) {
        Map<String, ManagedComponentLifecycle> indexed = new LinkedHashMap<>();
        for (ManagedComponentLifecycle component : Objects.requireNonNull(managed, "managedComponents")) {
            if (indexed.putIfAbsent(component.componentId(), component) != null) {
                throw new IllegalArgumentException("reviewed component identifiers must be unique");
            }
        }
        if (!indexed.keySet().equals(plan.candidateNamespaces().keySet())) {
            throw new IllegalArgumentException("reviewed lifecycle components must exactly match the plan");
        }
        LinkedHashMap<String, ManagedComponentLifecycle> ordered = new LinkedHashMap<>();
        for (String id : plan.startOrder()) {
            ManagedComponentLifecycle component = indexed.get(id);
            if (!component.application().id().equals(plan.candidateNamespaces().get(id))) {
                throw new IllegalArgumentException("reviewed lifecycle identity differs from the component plan");
            }
            ordered.put(id, component);
        }
        return ordered;
    }

    /**
     * Validates and produces normalized targets for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的规范化目标集合。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param requested requested / 已请求
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return constructed or resolved set / 构造或解析得到的集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static Set<String> normalizedTargets(Set<String> components, Set<String> requested, LifecycleAction action) {
        Objects.requireNonNull(requested, "targetComponentIds");
        Set<String> targets = new LinkedHashSet<>(requested.stream().sorted().toList());
        if (action == LifecycleAction.REFRESH_STATUS && targets.isEmpty()) targets.addAll(components);
        if (targets.isEmpty() || !components.containsAll(targets)) {
            throw new IllegalArgumentException("lifecycle targets must be a non-empty subset of the component plan");
        }
        return targets;
    }

    /**
     * Finds dependencies or dependents whose observed state makes the selected lifecycle action unsafe.
     * <p>查找观测状态使所选生命周期动作不安全的依赖项或被依赖项。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param targets targets / 目标集合
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param observations observations / 观测集合
     * @return dependencies or dependents whose observed state makes the selected lifecycle action unsafe / 观测状态使所选生命周期动作不安全的依赖项或被依赖项
     */
    private static Set<String> dependencyImpact(MultiComponentDeploymentPlan plan, Set<String> targets,
                                                LifecycleAction action,
                                                Map<String, LifecycleObservation> observations) {
        Set<String> unsafe = new LinkedHashSet<>();
        Map<String, Set<String>> dependents = dependents(plan.dependencies());
        for (String id : targets) {
            if (action == LifecycleAction.START || action == LifecycleAction.ENABLE_AUTOSTART) {
                for (String dependency : plan.dependencies().get(id)) {
                    LifecycleObservation observation = observations.get(dependency);
                    boolean ready = action == LifecycleAction.START
                            ? observation.runtimeState() == RuntimeState.RUNNING
                            : observation.autostartState() == AutostartState.ENABLED;
                    if (!targets.contains(dependency) && !ready) unsafe.add(dependency);
                }
            } else {
                for (String dependent : transitiveDependents(id, dependents)) {
                    LifecycleObservation observation = observations.get(dependent);
                    boolean affected = action == LifecycleAction.DISABLE_AUTOSTART
                            ? observation.autostartState() == AutostartState.ENABLED
                            : observation.runtimeState() == RuntimeState.RUNNING || observation.runtimeState() == RuntimeState.ERROR;
                    if (!targets.contains(dependent) && affected) unsafe.add(dependent);
                }
            }
        }
        return unsafe;
    }

    /**
     * Reverses the dependency graph into direct dependent sets.
     * <p>将依赖图反转为直接被依赖项集合。
     *
     * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
     * @return constructed or resolved map / 构造或解析得到的映射
     */
    private static Map<String, Set<String>> dependents(Map<String, List<String>> dependencies) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        dependencies.keySet().forEach(id -> result.put(id, new LinkedHashSet<>()));
        dependencies.forEach((id, values) -> values.forEach(dependency -> result.get(dependency).add(id)));
        return result;
    }

    /**
     * Traverses the reverse graph to collect all transitive dependents without revisiting nodes.
     * <p>遍历反向图，收集所有传递被依赖项且不重复访问节点。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param dependents dependents / 被依赖项集合
     * @return constructed or resolved set / 构造或解析得到的集合
     */
    private static Set<String> transitiveDependents(String id, Map<String, Set<String>> dependents) {
        Set<String> result = new LinkedHashSet<>();
        List<String> pending = new ArrayList<>(dependents.get(id));
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (result.add(current)) pending.addAll(dependents.get(current));
        }
        return result;
    }

    /**
     * Tests the verified predicate against the supplied evidence.
     * <p>根据所提供证据检查已验证条件。
     *
     * @param observation observation / 观测
     * @return true when verified predicate against the supplied evidence, false otherwise / 根据所提供证据检查已验证条件时为 true，否则为 false
     */
    private static boolean verified(LifecycleObservation observation) {
        return observation != null && observation.ownershipVerified()
                && observation.runtimeState() != RuntimeState.UNKNOWN
                && observation.autostartState() != AutostartState.UNKNOWN
                && observation.autostartState() != AutostartState.ERROR;
    }

    /**
     * Aggregates component observations into the application's runtime state, preserving unknown or mixed states.
     * <p>将组件观测聚合为整应用运行状态，并保留未知或混合状态。
     *
     * @param observations observations / 观测集合
     * @return constructed or resolved application runtime state / 构造或解析得到的应用运行时状态
     */
    private static ApplicationRuntimeState runtime(java.util.Collection<LifecycleObservation> observations) {
        if (observations.isEmpty() || observations.stream().anyMatch(value -> value.runtimeState() == RuntimeState.UNKNOWN)) {
            return ApplicationRuntimeState.UNKNOWN;
        }
        if (observations.stream().anyMatch(value -> value.runtimeState() == RuntimeState.ERROR)) return ApplicationRuntimeState.ERROR;
        if (observations.stream().allMatch(value -> value.runtimeState() == RuntimeState.INSTALLED)) return ApplicationRuntimeState.INSTALLED;
        var daemons = observations.stream().filter(value -> value.runtimeState() != RuntimeState.INSTALLED).toList();
        if (daemons.stream().allMatch(value -> value.runtimeState() == RuntimeState.RUNNING)) return ApplicationRuntimeState.RUNNING;
        if (daemons.stream().allMatch(value -> value.runtimeState() == RuntimeState.STOPPED)) return ApplicationRuntimeState.STOPPED;
        return ApplicationRuntimeState.PARTIALLY_RUNNING;
    }

    /**
     * Aggregates component autostart observations into the application's autostart state.
     * <p>将组件自动启动观测聚合为整应用自动启动状态。
     *
     * @param observations observations / 观测集合
     * @return constructed or resolved application autostart state / 构造或解析得到的应用自动启动状态
     */
    private static ApplicationAutostartState autostart(java.util.Collection<LifecycleObservation> observations) {
        if (observations.isEmpty() || observations.stream().anyMatch(value -> value.autostartState() == AutostartState.UNKNOWN)) {
            return ApplicationAutostartState.UNKNOWN;
        }
        if (observations.stream().anyMatch(value -> value.autostartState() == AutostartState.ERROR)) return ApplicationAutostartState.ERROR;
        if (observations.stream().allMatch(value -> value.autostartState() == AutostartState.ENABLED)) return ApplicationAutostartState.ENABLED;
        if (observations.stream().allMatch(value -> value.autostartState() == AutostartState.DISABLED)) return ApplicationAutostartState.DISABLED;
        return ApplicationAutostartState.PARTIALLY_ENABLED;
    }
}
