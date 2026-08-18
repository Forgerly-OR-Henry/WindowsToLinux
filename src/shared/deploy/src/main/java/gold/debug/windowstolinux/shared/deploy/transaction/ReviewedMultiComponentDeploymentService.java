package gold.debug.windowstolinux.shared.deploy.transaction;

import gold.debug.windowstolinux.shared.deploy.support.HostSupportChecker;
import gold.debug.windowstolinux.shared.deploy.support.HostSupport;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedReleaseIdentity;
import gold.debug.windowstolinux.shared.deploy.result.ComponentTransactionState;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentEvent;
import gold.debug.windowstolinux.shared.deploy.result.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilities;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Executes one whole-application transaction through a single verified typed session.
 *
 * <p>通过单个已验证类型化会话执行一个整体应用事务。
 */
public final class ReviewedMultiComponentDeploymentService {
    /**
     * Builds all candidates, snapshots every affected component, switches in dependency order, and restores all on failure.
     *
     * <p>构建全部候选、快照每个受影响组件、按依赖顺序切换，并在失败时恢复全部组件。
     */
    public MultiComponentDeploymentResult deploy(
            MultiComponentDeploymentPlan plan,
            List<ReviewedComponentDeployment> reviewedComponents,
            ApplicationHealthGate applicationHealth,
            DeploymentLinuxGateway gateway,
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyVerifier hostKeyVerifier
    ) {
        plan = Objects.requireNonNull(plan, "plan");
        applicationHealth = Objects.requireNonNull(applicationHealth, "applicationHealth");
        gateway = Objects.requireNonNull(gateway, "gateway");
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        credential = Objects.requireNonNull(credential, "credential");
        hostKeyVerifier = Objects.requireNonNull(hostKeyVerifier, "hostKeyVerifier");
        LinkedHashMap<String, MultiComponentTransactionContext> contexts;
        try {
            contexts = contexts(plan, reviewedComponents, endpoint, applicationHealth);
        } catch (RuntimeException failure) {
            credential.clear();
            throw failure;
        }
        List<DeploymentEvent> applicationEvents = new ArrayList<>();
        try (DeploymentRemoteSession session = gateway.connect(endpoint, credential.duplicate(), hostKeyVerifier)) {
            Optional<MultiComponentDeploymentResult> rejected = preflight(plan, contexts, session, applicationEvents);
            if (rejected.isPresent()) return rejected.orElseThrow();
            MultiComponentDeploymentResult buildFailure = buildAll(plan, contexts, session, applicationEvents);
            if (buildFailure != null) return buildFailure;
            snapshotAll(plan, contexts, session, applicationEvents);
            stopOldComponents(plan, contexts, session, applicationEvents);
            publishAndCheck(plan, contexts, applicationHealth, session, applicationEvents);
            observeAndRetain(plan, contexts, session, applicationEvents);
            MultiComponentRecoveryCoordinator.cleanupAll(contexts, session);
            String identity = applicationIdentity(plan, contexts);
            applicationEvents.add(new DeploymentEvent("application-commit", true,
                    "All reviewed components and the whole-application health gate were verified"));
            contexts.values().forEach(context -> context.state = ComponentTransactionState.SUCCEEDED);
            return MultiComponentTransactionContext.result(
                    DeploymentStatus.SUCCEEDED, applicationEvents, contexts, Optional.of(identity));
        } catch (SwitchFailure failure) {
            applicationEvents.add(new DeploymentEvent(failure.step, false, failure.getMessage()));
            return MultiComponentRecoveryCoordinator.recover(
                    plan, contexts, gateway, endpoint, credential, hostKeyVerifier, applicationEvents);
        } catch (LinuxOperationException | ArithmeticException failure) {
            applicationEvents.add(new DeploymentEvent("multi-component-linux-operation", false, safeMessage(failure)));
            return MultiComponentRecoveryCoordinator.recover(
                    plan, contexts, gateway, endpoint, credential, hostKeyVerifier, applicationEvents);
        } finally {
            credential.clear();
        }
    }

    private static Optional<MultiComponentDeploymentResult> preflight(
            MultiComponentDeploymentPlan plan, Map<String, MultiComponentTransactionContext> contexts, DeploymentRemoteSession session,
            List<DeploymentEvent> applicationEvents) throws LinuxOperationException {
        var server = session.collectCapabilities();
        if (server.managedHelperProtocolVersion() != ManagedHelperProtocol.VERSION) {
            return Optional.of(rejected(contexts, applicationEvents, "helper-protocol",
                    "Target helper protocol is stale; run product Environment Preparation first"));
        }
        LinuxCapabilities capabilities = session.collectDeploymentCapabilities();
        long requiredBytes = 0;
        for (String id : plan.startOrder()) {
            MultiComponentTransactionContext context = contexts.get(id);
            HostSupportChecker.Result compatibility = HostSupportChecker.evaluate(
                    capabilities, context.component.request().facts(), context.component.request().runtime());
            context.event("typed-host-compatibility",
                    compatibility.support() == HostSupport.READY_FOR_RUNTIME_VALIDATION,
                    String.join("; ", compatibility.evidence()));
            if (compatibility.support() != HostSupport.READY_FOR_RUNTIME_VALIDATION) {
                return Optional.of(rejected(contexts, applicationEvents, "typed-host-compatibility",
                        "At least one component is outside the collected host runtime matrix"));
            }
            var request = context.component.request();
            long footprint = Math.addExact(request.archive().byteCount(), request.archive().uncompressedByteCount());
            if (footprint > request.limits().maxWorkspaceBytes()) {
                return Optional.of(rejected(contexts, applicationEvents, "source-workspace",
                        "A component archive exceeds its confirmed workspace limit"));
            }
            requiredBytes = Math.addExact(requiredBytes, request.limits().maxWorkspaceBytes());
        }
        if (server.availableBytes() < requiredBytes) {
            return Optional.of(rejected(contexts, applicationEvents, "target-space",
                    "Target free space is insufficient for independent component candidates"));
        }
        applicationEvents.add(new DeploymentEvent("application-preflight", true,
                "Helper, target space, and every typed component runtime were verified before source upload"));
        return Optional.empty();
    }

    private static MultiComponentDeploymentResult buildAll(
            MultiComponentDeploymentPlan plan, Map<String, MultiComponentTransactionContext> contexts, DeploymentRemoteSession session,
            List<DeploymentEvent> applicationEvents) throws LinuxOperationException {
        for (List<String> wave : plan.buildWaves()) {
            applicationEvents.add(new DeploymentEvent("component-build-wave", true, String.join(",", wave)));
            for (String id : wave) {
                MultiComponentTransactionContext context = contexts.get(id);
                var request = context.component.request();
                context.workspace = new RemoteWorkspace(request.facts().applicationId(), request.archive().contentSha256());
                var receipt = session.uploadSource(request.archive(), context.workspace);
                if (!receipt.contentSha256().equals(request.archive().contentSha256())
                        || receipt.byteCount() != request.archive().byteCount()) {
                    context.event("source-upload", false, "Target upload identity differs from the reviewed archive");
                    context.state = ComponentTransactionState.BUILD_FAILED;
                    boolean cleaned = MultiComponentRecoveryCoordinator.cleanupAll(contexts, session);
                    MultiComponentRecoveryCoordinator.markDiscarded(contexts, id);
                    if (!cleaned) MultiComponentRecoveryCoordinator.markCleanupFailure(contexts);
                    return MultiComponentTransactionContext.result(cleaned ? DeploymentStatus.FAILED_BUILD : DeploymentStatus.MANUAL_RECOVERY_REQUIRED,
                            applicationEvents, contexts, Optional.empty());
                }
                context.event("source-upload", true, receipt.evidence());
                context.build = session.buildDeployment(request.facts(), request.runtime(), context.workspace,
                        request.limits(), request.configuration());
                context.event("remote-build", context.build.succeeded(), context.build.evidence());
                if (!context.build.succeeded()
                        || !context.build.sourceSha256().equals(request.archive().contentSha256())) {
                    context.state = ComponentTransactionState.BUILD_FAILED;
                    boolean cleaned = MultiComponentRecoveryCoordinator.cleanupAll(contexts, session);
                    MultiComponentRecoveryCoordinator.markDiscarded(contexts, id);
                    if (!cleaned) MultiComponentRecoveryCoordinator.markCleanupFailure(contexts);
                    return MultiComponentTransactionContext.result(cleaned ? DeploymentStatus.FAILED_BUILD : DeploymentStatus.MANUAL_RECOVERY_REQUIRED,
                            applicationEvents, contexts, Optional.empty());
                }
                context.inputs = session.stageDeploymentInputs(context.component.application(), request.configuration(),
                        context.component.resolvedSecrets());
                context.releaseIdentity = ReviewedReleaseIdentity.from(request);
                context.event("candidate-ready", true, "Candidate build and immutable deployment inputs are ready");
            }
        }
        return null;
    }

    private static void snapshotAll(MultiComponentDeploymentPlan plan, Map<String, MultiComponentTransactionContext> contexts,
                                    DeploymentRemoteSession session, List<DeploymentEvent> applicationEvents)
            throws LinuxOperationException {
        for (String id : plan.stopOrder()) {
            MultiComponentTransactionContext context = contexts.get(id);
            context.snapshot = session.snapshotDeployment(context.component.application(), context.component.request().runtime());
            context.event("snapshot", true, context.snapshot.evidence());
        }
        applicationEvents.add(new DeploymentEvent("application-snapshot", true,
                "Every affected release, runtime, autostart, configuration, and secret revision was captured"));
    }

    private static void stopOldComponents(MultiComponentDeploymentPlan plan, Map<String, MultiComponentTransactionContext> contexts,
                                          DeploymentRemoteSession session, List<DeploymentEvent> applicationEvents)
            throws LinuxOperationException {
        for (String id : plan.stopOrder()) {
            MultiComponentTransactionContext context = contexts.get(id);
            if (!context.snapshot.hasPreviousRelease() || !context.snapshot.previousWasRunning()) continue;
            context.stopAttempted = true;
            LifecycleObservation stopped = session.executeDeploymentLifecycle(context.component.application(),
                    context.component.request().runtime(), LifecycleAction.STOP);
            boolean verified = stopped.ownershipVerified() && stopped.runtimeState() == RuntimeState.STOPPED;
            context.event("stop-old", verified, stopped.evidence());
            if (!verified) throw new SwitchFailure("stop-old", "Old component could not be stopped safely: " + id);
            context.stopped = true;
        }
        applicationEvents.add(new DeploymentEvent("application-stop", true,
                "Previously running affected components were stopped in reverse dependency order"));
    }

    private static void publishAndCheck(
            MultiComponentDeploymentPlan plan, Map<String, MultiComponentTransactionContext> contexts, ApplicationHealthGate applicationHealth,
            DeploymentRemoteSession session, List<DeploymentEvent> applicationEvents) throws LinuxOperationException {
        for (String id : plan.startOrder()) {
            MultiComponentTransactionContext context = contexts.get(id);
            var request = context.component.request();
            context.publishAttempted = true;
            var publish = session.publishDeployment(context.component.application(), request.facts(), context.workspace,
                    context.build, context.releaseIdentity, request.runtime(), context.inputs, context.snapshot);
            context.event("publish", publish.succeeded(), publish.evidence());
            if (!publish.succeeded()) throw new SwitchFailure("publish", "Component publication failed: " + id);
            context.published = true;
            var health = session.checkDeploymentHealth(context.component.application(), request.runtime(),
                    request.runtime().healthCheck());
            context.event("candidate-health", health.healthy(), health.evidence());
            if (!health.healthy()) {
                throw new SwitchFailure("candidate-health", "Component health failed: " + id
                        + "; controlled diagnostic: " + health.evidence());
            }
        }
        MultiComponentTransactionContext gate = contexts.get(applicationHealth.componentId());
        var wholeHealth = session.checkDeploymentHealth(gate.component.application(), gate.component.request().runtime(),
                applicationHealth.healthCheck());
        applicationEvents.add(new DeploymentEvent("application-health", wholeHealth.healthy(), wholeHealth.evidence()));
        if (!wholeHealth.healthy()) throw new SwitchFailure("application-health", "Whole-application health failed");
    }

    private static void observeAndRetain(MultiComponentDeploymentPlan plan, Map<String, MultiComponentTransactionContext> contexts,
                                         DeploymentRemoteSession session, List<DeploymentEvent> applicationEvents)
            throws LinuxOperationException {
        for (String id : plan.healthOrder()) {
            MultiComponentTransactionContext context = contexts.get(id);
            context.observation = session.observeDeployment(context.component.application(),
                    context.component.request().runtime());
            context.event("final-observation", context.observation.ownershipVerified(), context.observation.evidence());
            if (!context.observation.ownershipVerified() || context.observation.runtimeState() != RuntimeState.RUNNING) {
                throw new SwitchFailure("final-observation", "Final component runtime is not verified: " + id);
            }
            var retention = session.retainRecentSuccessfulReleases(context.component.application());
            context.event("release-retention", retention.succeeded(), retention.evidence());
        }
        applicationEvents.add(new DeploymentEvent("application-observation", true,
                "Every component ownership and running state was observed from the target"));
    }

    private static LinkedHashMap<String, MultiComponentTransactionContext> contexts(
            MultiComponentDeploymentPlan plan, List<ReviewedComponentDeployment> reviewed, SshEndpoint endpoint,
            ApplicationHealthGate applicationHealth) {
        Map<String, ReviewedComponentDeployment> indexed = new LinkedHashMap<>();
        for (ReviewedComponentDeployment component : Objects.requireNonNull(reviewed, "reviewedComponents")) {
            if (indexed.putIfAbsent(component.componentId(), component) != null) {
                throw new IllegalArgumentException("reviewed component identifiers must be unique");
            }
        }
        if (!indexed.keySet().equals(plan.candidateNamespaces().keySet())
                || !indexed.containsKey(applicationHealth.componentId())) {
            throw new IllegalArgumentException("reviewed components and application health must exactly match the plan");
        }
        LinkedHashMap<String, MultiComponentTransactionContext> contexts = new LinkedHashMap<>();
        for (String id : plan.startOrder()) {
            ReviewedComponentDeployment component = indexed.get(id);
            if (!plan.candidateNamespaces().get(id).equals(component.application().id())) {
                throw new IllegalArgumentException("component candidate namespace differs from the reviewed identity");
            }
            if (component.request().limits().runAsRoot() != "root".equals(endpoint.username())) {
                throw new IllegalArgumentException("root build approval and SSH identity must agree for every component");
            }
            contexts.put(id, new MultiComponentTransactionContext(component));
        }
        return contexts;
    }

    private static MultiComponentDeploymentResult rejected(Map<String, MultiComponentTransactionContext> contexts,
                                                           List<DeploymentEvent> applicationEvents,
                                                           String step, String evidence) {
        applicationEvents.add(new DeploymentEvent(step, false, evidence));
        return MultiComponentTransactionContext.result(
                DeploymentStatus.PRECONDITION_REJECTED, applicationEvents, contexts, Optional.empty());
    }

    private static String applicationIdentity(MultiComponentDeploymentPlan plan, Map<String, MultiComponentTransactionContext> contexts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, plan.applicationId());
            contexts.values().stream().sorted(Comparator.comparing(context -> context.component.componentId()))
                    .forEach(context -> {
                        update(digest, context.component.componentId());
                        update(digest, context.releaseIdentity);
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", failure);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String safeMessage(Exception failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Controlled multi-component operation failed" : failure.getMessage();
    }

    private static final class SwitchFailure extends RuntimeException {
        private final String step;

        private SwitchFailure(String step, String message) {
            super(message);
            this.step = step;
        }
    }
}
