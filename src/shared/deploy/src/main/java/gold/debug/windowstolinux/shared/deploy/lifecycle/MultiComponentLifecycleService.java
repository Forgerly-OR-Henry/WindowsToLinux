package gold.debug.windowstolinux.shared.deploy.lifecycle;

import gold.debug.windowstolinux.shared.deploy.plan.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.result.ComponentLifecycleResult;
import gold.debug.windowstolinux.shared.deploy.result.MultiComponentLifecycleResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationAutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationRuntimeState;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.ArrayList;
import java.util.Arrays;
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
            HostKeyVerifier hostKeyVerifier
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
            components = components(plan, managedComponents);
            targets = normalizedTargets(components.keySet(), targetComponentIds, requestedAction);
        } catch (RuntimeException failure) {
            clearCredential(credential);
            throw failure;
        }
        LinkedHashMap<String, LifecycleObservation> observations = new LinkedHashMap<>();
        Set<String> attempted = new LinkedHashSet<>();
        Set<String> failed = new LinkedHashSet<>();
        try (DeploymentRemoteSession session = gateway.connect(endpoint, copyCredential(credential), hostKeyVerifier)) {
            observeAll(plan, components, session, observations);
            MultiComponentLifecycleResult rejection = validate(plan, targets, requestedAction, observations);
            if (rejection != null) return rejection;
            if (requestedAction != LifecycleAction.REFRESH_STATUS) {
                boolean complete = executeAction(plan, components, targets, requestedAction, session, attempted, failed);
                observeAll(plan, components, session, observations);
                targets.stream().filter(id -> !matchesFinal(requestedAction, observations.get(id))).forEach(failed::add);
                return result(complete && failed.isEmpty(), complete && failed.isEmpty()
                                ? LocalizedMessage.of("lifecycle.applicationVerified")
                                : LocalizedMessage.of("lifecycle.applicationPartialFailure"),
                        plan, observations, attempted, failed);
            }
            return result(true, LocalizedMessage.of("lifecycle.applicationStatusFetched"),
                    plan, observations, attempted, failed);
        } catch (LinuxOperationException failure) {
            return failure(plan, observations, attempted, failed, safeMessage(failure));
        } finally {
            clearCredential(credential);
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
            if (!matches(action, observation)) {
                failed.add(id);
                return false;
            }
        }
        return true;
    }

    private static boolean matches(LifecycleAction action, LifecycleObservation observation) {
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

    private static boolean matchesFinal(LifecycleAction action, LifecycleObservation observation) {
        return matches(action == LifecycleAction.RESTART ? LifecycleAction.START : action, observation);
    }

    private static MultiComponentLifecycleResult validate(
            MultiComponentDeploymentPlan plan, Set<String> targets, LifecycleAction action,
            Map<String, LifecycleObservation> observations) {
        if (observations.values().stream().anyMatch(observation -> !observation.ownershipVerified()
                || observation.runtimeState() == RuntimeState.UNKNOWN || observation.runtimeState() == RuntimeState.ERROR
                || observation.autostartState() == AutostartState.UNKNOWN
                || observation.autostartState() == AutostartState.ERROR)) {
            return result(false, LocalizedMessage.of("lifecycle.applicationStateUnverified"),
                    plan, observations, Set.of(), observations.keySet());
        }
        if (action == LifecycleAction.REFRESH_STATUS) return null;
        Set<String> unsafe = dependencyImpact(plan, targets, action, observations);
        if (!unsafe.isEmpty()) {
            return result(false, LocalizedMessage.of("lifecycle.applicationDependencyImpact",
                            Map.of("components", String.join(", ", unsafe))),
                    plan, observations, Set.of(), unsafe);
        }
        return null;
    }

    private static Set<String> dependencyImpact(
            MultiComponentDeploymentPlan plan, Set<String> targets, LifecycleAction action,
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
                            : observation.runtimeState() == RuntimeState.RUNNING;
                    if (!targets.contains(dependent) && affected) unsafe.add(dependent);
                }
            }
        }
        return unsafe;
    }

    private static Map<String, Set<String>> dependents(Map<String, List<String>> dependencies) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        dependencies.keySet().forEach(id -> result.put(id, new LinkedHashSet<>()));
        dependencies.forEach((id, values) -> values.forEach(dependency -> result.get(dependency).add(id)));
        return result;
    }

    private static Set<String> transitiveDependents(String id, Map<String, Set<String>> dependents) {
        Set<String> result = new LinkedHashSet<>();
        List<String> pending = new ArrayList<>(dependents.get(id));
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (result.add(current)) pending.addAll(dependents.get(current));
        }
        return result;
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

    private static MultiComponentLifecycleResult failure(
            MultiComponentDeploymentPlan plan, Map<String, LifecycleObservation> observations, Set<String> attempted,
            Set<String> failed, String detail) {
        Set<String> unresolved = new LinkedHashSet<>(failed);
        unresolved.addAll(attempted);
        unresolved.addAll(plan.startOrder().stream().filter(id -> !observations.containsKey(id)).toList());
        return result(false, LocalizedMessage.of("lifecycle.applicationConnectionFailed", Map.of("detail", detail)),
                plan, observations, attempted, unresolved);
    }

    private static MultiComponentLifecycleResult result(
            boolean accepted, LocalizedMessage message, MultiComponentDeploymentPlan plan,
            Map<String, LifecycleObservation> observations, Set<String> attempted, Set<String> failed) {
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

    private static boolean verified(LifecycleObservation observation) {
        return observation != null && observation.ownershipVerified()
                && observation.runtimeState() != RuntimeState.UNKNOWN && observation.runtimeState() != RuntimeState.ERROR
                && observation.autostartState() != AutostartState.UNKNOWN
                && observation.autostartState() != AutostartState.ERROR;
    }

    private static ApplicationRuntimeState runtime(java.util.Collection<LifecycleObservation> observations) {
        if (observations.isEmpty() || observations.stream().anyMatch(value -> value.runtimeState() == RuntimeState.UNKNOWN)) {
            return ApplicationRuntimeState.UNKNOWN;
        }
        if (observations.stream().anyMatch(value -> value.runtimeState() == RuntimeState.ERROR)) {
            return ApplicationRuntimeState.ERROR;
        }
        if (observations.stream().allMatch(value -> value.runtimeState() == RuntimeState.RUNNING)) {
            return ApplicationRuntimeState.RUNNING;
        }
        if (observations.stream().allMatch(value -> value.runtimeState() == RuntimeState.STOPPED)) {
            return ApplicationRuntimeState.STOPPED;
        }
        return ApplicationRuntimeState.PARTIALLY_RUNNING;
    }

    private static ApplicationAutostartState autostart(java.util.Collection<LifecycleObservation> observations) {
        if (observations.isEmpty() || observations.stream().anyMatch(value -> value.autostartState() == AutostartState.UNKNOWN)) {
            return ApplicationAutostartState.UNKNOWN;
        }
        if (observations.stream().anyMatch(value -> value.autostartState() == AutostartState.ERROR)) {
            return ApplicationAutostartState.ERROR;
        }
        if (observations.stream().allMatch(value -> value.autostartState() == AutostartState.ENABLED)) {
            return ApplicationAutostartState.ENABLED;
        }
        if (observations.stream().allMatch(value -> value.autostartState() == AutostartState.DISABLED)) {
            return ApplicationAutostartState.DISABLED;
        }
        return ApplicationAutostartState.PARTIALLY_ENABLED;
    }

    private static LinkedHashMap<String, ManagedComponentLifecycle> components(
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

    private static Set<String> normalizedTargets(Set<String> components, Set<String> requested, LifecycleAction action) {
        Objects.requireNonNull(requested, "targetComponentIds");
        Set<String> targets = new LinkedHashSet<>(requested.stream().sorted().toList());
        if (action == LifecycleAction.REFRESH_STATUS && targets.isEmpty()) targets.addAll(components);
        if (targets.isEmpty() || !components.containsAll(targets)) {
            throw new IllegalArgumentException("lifecycle targets must be a non-empty subset of the component plan");
        }
        return targets;
    }

    private static SshCredential copyCredential(SshCredential credential) {
        if (credential instanceof SshCredential.Password password) {
            char[] value = password.copy();
            try {
                return new SshCredential.Password(value);
            } finally {
                Arrays.fill(value, '\0');
            }
        }
        return credential;
    }

    private static void clearCredential(SshCredential credential) {
        if (credential instanceof SshCredential.Password password) password.clear();
    }

    private static String safeMessage(LinuxOperationException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Controlled application lifecycle operation failed" : failure.getMessage();
    }
}
