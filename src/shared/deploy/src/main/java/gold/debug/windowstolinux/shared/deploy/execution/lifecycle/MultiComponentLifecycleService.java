package gold.debug.windowstolinux.shared.deploy.execution.lifecycle;

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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Executes dependency-safe application lifecycle actions from authoritative remote observations. / 根据权威远端观测执行依赖安全的应用生命周期动作。 */
public final class MultiComponentLifecycleService {
    /** Observes every component, validates dependency impact, and executes only the reviewed component set. / 观测每个组件、验证依赖影响并仅执行经审阅的组件集合。 */
    public MultiComponentLifecycleResult execute(
            MultiComponentDeploymentPlan plan,
            List<ManagedComponentLifecycle> managedComponents,
            Set<String> targetComponentIds,
            LifecycleAction action,
            DeploymentLinuxGateway gateway,
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyEvaluator hostKeyVerifier
    ) {
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
            targets = MultiComponentLifecyclePolicy.normalizedTargets(
                    components.keySet(), targetComponentIds, requestedAction);
        } catch (RuntimeException failure) {
            credential.clear();
            throw failure;
        }
        LinkedHashMap<String, LifecycleObservation> observations = new LinkedHashMap<>();
        Set<String> attempted = new LinkedHashSet<>();
        Set<String> failed = new LinkedHashSet<>();
        try (DeploymentRemoteSession session = gateway.connect(endpoint, credential.duplicate(), hostKeyVerifier)) {
            observeAll(plan, components, session, observations);
            MultiComponentLifecycleResult rejection = MultiComponentLifecyclePolicy.validate(
                    plan, targets, requestedAction, observations);
            if (rejection != null) return rejection;
            if (requestedAction != LifecycleAction.REFRESH_STATUS) {
                boolean complete = executeAction(plan, components, targets, requestedAction, session, attempted, failed);
                observeAll(plan, components, session, observations);
                targets.stream().filter(id -> !MultiComponentLifecyclePolicy.matchesFinal(
                        requestedAction, observations.get(id))).forEach(failed::add);
                return MultiComponentLifecyclePolicy.result(complete && failed.isEmpty(), complete && failed.isEmpty()
                                ? LocalizedMessage.of("lifecycle.applicationVerified")
                                : LocalizedMessage.of("lifecycle.applicationPartialFailure"),
                        plan, observations, attempted, failed);
            }
            return MultiComponentLifecyclePolicy.result(true,
                    LocalizedMessage.of("lifecycle.applicationStatusFetched"),
                    plan, observations, attempted, failed);
        } catch (LinuxOperationException failure) {
            return MultiComponentLifecyclePolicy.failure(
                    plan, observations, attempted, failed, failure.failure());
        } finally {
            credential.clear();
        }
    }

    private static boolean executeAction(
            MultiComponentDeploymentPlan plan, Map<String, ManagedComponentLifecycle> components, Set<String> targets,
            LifecycleAction action, DeploymentRemoteSession session, Set<String> attempted, Set<String> failed)
            throws LinuxOperationException {
        if (action == LifecycleAction.RESTART) {
            if (!executeOrdered(plan.stopOrder(), components, targets, LifecycleAction.STOP, session, attempted, failed)) {
                return false;
            }
            return executeOrdered(plan.startOrder(), components, targets, LifecycleAction.START, session, attempted, failed);
        }
        List<String> order = action == LifecycleAction.STOP || action == LifecycleAction.DISABLE_AUTOSTART
                ? plan.stopOrder() : plan.startOrder();
        return executeOrdered(order, components, targets, action, session, attempted, failed);
    }

    private static boolean executeOrdered(
            List<String> order, Map<String, ManagedComponentLifecycle> components, Set<String> targets,
            LifecycleAction action, DeploymentRemoteSession session, Set<String> attempted, Set<String> failed)
            throws LinuxOperationException {
        for (String id : order) {
            if (!targets.contains(id)) continue;
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

    private static void observeAll(
            MultiComponentDeploymentPlan plan, Map<String, ManagedComponentLifecycle> components,
            DeploymentRemoteSession session, Map<String, LifecycleObservation> observations)
            throws LinuxOperationException {
        observations.clear();
        for (String id : plan.startOrder()) {
            ManagedComponentLifecycle component = components.get(id);
            observations.put(id, session.observe(component.application()));
        }
    }

}
