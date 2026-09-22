package gold.debug.windowstolinux.shared.deploy.execution.lifecycle;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.MultiComponentLifecycleResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

/**
 * Executes dependency-safe application lifecycle actions from authoritative remote observations. / 根据权威远端观测执行依赖安全的应用生命周期动作。
 */
public final class MultiComponentLifecycleService {
    /**
     * Observes every component, validates dependency impact, and executes only the reviewed component set. / 观测每个组件、验证依赖影响并仅执行经审阅的组件集合。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param managedComponents managed components / 受管组件集合
     * @param targetComponentIds target component ids / 目标组件标识集合
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @return constructed or resolved multi component lifecycle result / 构造或解析得到的多组件生命周期结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MultiComponentLifecycleResult execute(MultiComponentDeploymentPlan plan,
            List<ManagedComponentLifecycle> managedComponents, Set<String> targetComponentIds, LifecycleAction action,
            DeploymentLinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
            HostKeyEvaluator hostKeyVerifier) {
        plan = Objects.requireNonNull(plan, "plan");
        LifecycleAction requestedAction = Objects.requireNonNull(action, "action");
        gateway = Objects.requireNonNull(gateway, "gateway");
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        credential = Objects.requireNonNull(credential, "credential");
        hostKeyVerifier = Objects.requireNonNull(hostKeyVerifier, "hostKeyVerifier");
        LinkedHashMap<String, ManagedComponentLifecycle> components;
        Set<String> targets;
        try {
            components = MultiComponentLifecyclePolicy.components(plan, managedComponents);
            targets = MultiComponentLifecyclePolicy.normalizedTargets(components.keySet(), targetComponentIds,
                    requestedAction);
        } catch (RuntimeException failure) {
            credential.clear();
            throw failure;
        }
        LinkedHashMap<String, LifecycleObservation> observations = new LinkedHashMap<>();
        Set<String> attempted = new LinkedHashSet<>();
        Set<String> failed = new LinkedHashSet<>();
        try (DeploymentRemoteSession session = gateway.connect(endpoint, credential.duplicate(), hostKeyVerifier)) {
            observeAll(plan, components, session, observations);
            MultiComponentLifecycleResult rejection = MultiComponentLifecyclePolicy.validate(plan, targets,
                    requestedAction, observations);
            if (rejection != null)
                return rejection;
            if (requestedAction != LifecycleAction.REFRESH_STATUS) {
                boolean complete = executeAction(plan, components, targets, requestedAction, session, attempted,
                        failed);
                observeAll(plan, components, session, observations);
                targets.stream().filter(
                        id -> !MultiComponentLifecyclePolicy.matchesFinal(requestedAction, observations.get(id)))
                        .forEach(failed::add);
                return MultiComponentLifecyclePolicy.result(complete && failed.isEmpty(),
                        complete && failed.isEmpty()
                                ? LocalizedMessage.of("lifecycle.applicationVerified")
                                : LocalizedMessage.of("lifecycle.applicationPartialFailure"),
                        plan, observations, attempted, failed);
            }
            return MultiComponentLifecyclePolicy.result(true, LocalizedMessage.of("lifecycle.applicationStatusFetched"),
                    plan, observations, attempted, failed);
        } catch (LinuxOperationException failure) {
            return MultiComponentLifecyclePolicy.failure(plan, observations, attempted, failed, failure.failure());
        } finally {
            credential.clear();
        }
    }

    /**
     * Executes explicit action selected for the current target.
     * <p>执行为当前目标显式选择的动作。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param targets targets / 目标集合
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param attempted attempted / 已尝试
     * @param failed failed / 失败
     * @return true when executes explicit action selected for the current target, false otherwise / 执行为当前目标显式选择的动作时为 true，否则为 false
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private static boolean executeAction(MultiComponentDeploymentPlan plan,
            Map<String, ManagedComponentLifecycle> components, Set<String> targets, LifecycleAction action,
            DeploymentRemoteSession session, Set<String> attempted, Set<String> failed) throws LinuxOperationException {
        if (action == LifecycleAction.RESTART) {
            if (!executeOrdered(plan.stopOrder(), components, targets, LifecycleAction.STOP, session, attempted,
                    failed)) {
                return false;
            }
            return executeOrdered(plan.startOrder(), components, targets, LifecycleAction.START, session, attempted,
                    failed);
        }
        List<String> order = action == LifecycleAction.STOP || action == LifecycleAction.DISABLE_AUTOSTART
                ? plan.stopOrder()
                : plan.startOrder();
        return executeOrdered(order, components, targets, action, session, attempted, failed);
    }

    /**
     * Executes ordered.
     * <p>执行有序。
     *
     * @param order order / 顺序
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param targets targets / 目标集合
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param attempted attempted / 已尝试
     * @param failed failed / 失败
     * @return true when executes ordered, false otherwise / 执行有序时为 true，否则为 false
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private static boolean executeOrdered(List<String> order, Map<String, ManagedComponentLifecycle> components,
            Set<String> targets, LifecycleAction action, DeploymentRemoteSession session, Set<String> attempted,
            Set<String> failed) throws LinuxOperationException {
        for (String id : order) {
            if (!targets.contains(id))
                continue;
            ManagedComponentLifecycle component = components.get(id);
            attempted.add(id);
            LifecycleObservation observation = session.executeLifecycle(component.application(), action,
                    component.healthCheck());
            if (!MultiComponentLifecyclePolicy.matches(action, observation)) {
                failed.add(id);
                return false;
            }
        }
        return true;
    }

    /**
     * Observes all.
     * <p>观测全部。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param observations observations / 观测集合
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private static void observeAll(MultiComponentDeploymentPlan plan, Map<String, ManagedComponentLifecycle> components,
            DeploymentRemoteSession session, Map<String, LifecycleObservation> observations)
            throws LinuxOperationException {
        observations.clear();
        for (String id : plan.startOrder()) {
            ManagedComponentLifecycle component = components.get(id);
            observations.put(id, session.observe(component.application()));
        }
    }

}
