package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import gold.debug.windowstolinux.shared.deploy.contract.result.compatibility.HostSupportStatus;
import gold.debug.windowstolinux.shared.deploy.contract.result.compatibility.HostSupportDecision;
import gold.debug.windowstolinux.shared.deploy.support.HostSupportEvaluator;
import gold.debug.windowstolinux.shared.deploy.error.DeploymentExecutionFailureType;
import gold.debug.windowstolinux.shared.deploy.error.DeploymentSwitchException;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedReleaseIdentityResolver;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.ComponentTransactionState;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentTraceEvent;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;
import gold.debug.windowstolinux.shared.linux.protocol.backup.ManagedContentPublication;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

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
            HostKeyEvaluator hostKeyVerifier
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
            applicationEvents.add(DeploymentEvent.result(DeploymentTraceEvent.APPLICATION_COMMIT, true,
                    "All reviewed components and the whole-application health gate were verified"));
            contexts.values().forEach(context -> context.state = ComponentTransactionState.SUCCEEDED);
            return MultiComponentTransactionContext.result(
                    DeploymentStatus.SUCCEEDED, applicationEvents, contexts, Optional.of(identity));
        } catch (DeploymentSwitchException failure) {
            applicationEvents.add(DeploymentEvent.failed(failure.step(), failure.failure()));
            return MultiComponentRecoveryCoordinator.recover(
                    plan, contexts, gateway, endpoint, credential, hostKeyVerifier, applicationEvents);
        } catch (LinuxOperationException failure) {
            applicationEvents.add(DeploymentEvent.failed(
                    DeploymentTraceEvent.MULTI_COMPONENT_LINUX_OPERATION, failure.failure()));
            return MultiComponentRecoveryCoordinator.recover(
                    plan, contexts, gateway, endpoint, credential, hostKeyVerifier, applicationEvents);
        } catch (ArithmeticException failure) {
            applicationEvents.add(DeploymentEvent.failed(DeploymentTraceEvent.APPLICATION_PREFLIGHT,
                    FailureDescriptor.create(DeploymentExecutionFailureType.ARITHMETIC_OVERFLOW,
                            OperationIdentity.create(), "Deployment size arithmetic exceeded its bounded range")));
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
            return Optional.of(rejected(contexts, applicationEvents, DeploymentTraceEvent.HELPER_PROTOCOL,
                    "Target helper protocol is stale; run product Environment Preparation first"));
        }
        LinuxCapabilityFacts capabilities = session.collectDeploymentCapabilities();
        long requiredBytes = 0;
        for (String id : plan.startOrder()) {
            MultiComponentTransactionContext context = contexts.get(id);
            HostSupportDecision compatibility = HostSupportEvaluator.evaluate(
                    capabilities, context.component.request().facts(), context.component.request().runtime());
            context.event(DeploymentTraceEvent.TYPED_HOST_COMPATIBILITY,
                    compatibility.support() == HostSupportStatus.READY_FOR_RUNTIME_VALIDATION,
                    String.join("; ", compatibility.evidence()));
            if (compatibility.support() != HostSupportStatus.READY_FOR_RUNTIME_VALIDATION) {
                return Optional.of(rejected(contexts, applicationEvents, DeploymentTraceEvent.TYPED_HOST_COMPATIBILITY,
                        "At least one component is outside the collected host runtime matrix"));
            }
            var request = context.component.request();
            long footprint = Math.addExact(request.archive().byteCount(), request.archive().uncompressedByteCount());
            if (footprint > request.limits().maxWorkspaceBytes()) {
                return Optional.of(rejected(contexts, applicationEvents, DeploymentTraceEvent.SOURCE_WORKSPACE,
                        "A component archive exceeds its confirmed workspace limit"));
            }
            requiredBytes = Math.addExact(requiredBytes, request.limits().maxWorkspaceBytes());
        }
        if (server.availableBytes() < requiredBytes) {
            return Optional.of(rejected(contexts, applicationEvents, DeploymentTraceEvent.TARGET_SPACE,
                    "Target free space is insufficient for independent component candidates"));
        }
        applicationEvents.add(DeploymentEvent.result(DeploymentTraceEvent.APPLICATION_PREFLIGHT, true,
                "Helper, target space, and every typed component runtime were verified before source upload"));
        return Optional.empty();
    }

    private static MultiComponentDeploymentResult buildAll(
            MultiComponentDeploymentPlan plan, Map<String, MultiComponentTransactionContext> contexts, DeploymentRemoteSession session,
            List<DeploymentEvent> applicationEvents) throws LinuxOperationException {
        for (List<String> wave : plan.buildWaves()) {
            applicationEvents.add(DeploymentEvent.result(DeploymentTraceEvent.COMPONENT_BUILD_WAVE, true, String.join(",", wave)));
            for (String id : wave) {
                MultiComponentTransactionContext context = contexts.get(id);
                var request = context.component.request();
                context.workspace = new RemoteWorkspace(request.facts().applicationId(), request.archive().contentSha256());
                var receipt = session.uploadSource(request.archive(), context.workspace);
                if (!receipt.contentSha256().equals(request.archive().contentSha256())
                        || receipt.byteCount() != request.archive().byteCount()) {
                    context.event(DeploymentTraceEvent.SOURCE_UPLOAD, false, "Target upload identity differs from the reviewed archive");
                    context.state = ComponentTransactionState.BUILD_FAILED;
                    boolean cleaned = MultiComponentRecoveryCoordinator.cleanupAll(contexts, session);
                    MultiComponentRecoveryCoordinator.markDiscarded(contexts, id);
                    if (!cleaned) MultiComponentRecoveryCoordinator.markCleanupFailure(contexts);
                    return MultiComponentTransactionContext.result(cleaned ? DeploymentStatus.FAILED_BUILD : DeploymentStatus.MANUAL_RECOVERY_REQUIRED,
                            applicationEvents, contexts, Optional.empty());
                }
                context.event(DeploymentTraceEvent.SOURCE_UPLOAD, true, receipt.evidence());
                context.build = session.buildDeployment(request.facts(), request.runtime(), context.workspace,
                        request.limits(), request.configuration());
                context.event(DeploymentTraceEvent.REMOTE_BUILD, context.build.succeeded(), context.build.evidence());
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
                context.releaseIdentity = ReviewedReleaseIdentityResolver.from(request);
                context.event(DeploymentTraceEvent.CANDIDATE_READY, true, "Candidate build and immutable deployment inputs are ready");
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
            context.event(DeploymentTraceEvent.SNAPSHOT, true, context.snapshot.evidence());
        }
        applicationEvents.add(DeploymentEvent.result(DeploymentTraceEvent.APPLICATION_SNAPSHOT, true,
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
            context.event(DeploymentTraceEvent.STOP_OLD, verified, stopped.evidence());
            if (!verified) throw DeploymentSwitchException.create(DeploymentTraceEvent.STOP_OLD,
                    DeploymentExecutionFailureType.SWITCH_UNVERIFIED,
                    "An old component could not be stopped and verified safely");
            context.stopped = true;
        }
        applicationEvents.add(DeploymentEvent.result(DeploymentTraceEvent.APPLICATION_STOP, true,
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
                    context.build, context.releaseIdentity, request.runtime(), context.inputs,
                    new ManagedContentPublication(plan.applicationId(), context.component.componentId(),
                            context.component.resourceBindings().fileBindings()), context.snapshot);
            context.event(DeploymentTraceEvent.PUBLISH, publish.succeeded(), publish.evidence());
            if (!publish.succeeded()) throw DeploymentSwitchException.create(DeploymentTraceEvent.PUBLISH,
                    DeploymentExecutionFailureType.PUBLISH_FAILED, "A component publication step failed");
            context.published = true;
            var health = session.checkDeploymentHealth(context.component.application(), request.runtime(),
                    request.runtime().healthCheck());
            context.event(DeploymentTraceEvent.CANDIDATE_HEALTH, health.healthy(), health.evidence());
            if (!health.healthy()) {
                throw DeploymentSwitchException.create(DeploymentTraceEvent.CANDIDATE_HEALTH,
                        DeploymentExecutionFailureType.HEALTH_FAILED,
                        "A published component did not pass its reviewed health check");
            }
        }
        MultiComponentTransactionContext gate = contexts.get(applicationHealth.componentId());
        var wholeHealth = session.checkDeploymentHealth(gate.component.application(), gate.component.request().runtime(),
                applicationHealth.healthCheck());
        applicationEvents.add(DeploymentEvent.result(DeploymentTraceEvent.APPLICATION_HEALTH, wholeHealth.healthy(), wholeHealth.evidence()));
        if (!wholeHealth.healthy()) throw DeploymentSwitchException.create(DeploymentTraceEvent.APPLICATION_HEALTH,
                DeploymentExecutionFailureType.HEALTH_FAILED,
                "The whole-application health gate did not pass");
    }

    private static void observeAndRetain(MultiComponentDeploymentPlan plan, Map<String, MultiComponentTransactionContext> contexts,
                                         DeploymentRemoteSession session, List<DeploymentEvent> applicationEvents)
            throws LinuxOperationException {
        for (String id : plan.healthOrder()) {
            MultiComponentTransactionContext context = contexts.get(id);
            context.observation = session.observeDeployment(context.component.application(),
                    context.component.request().runtime());
            context.event(DeploymentTraceEvent.FINAL_OBSERVATION, context.observation.ownershipVerified(), context.observation.evidence());
            if (!context.observation.ownershipVerified() || context.observation.runtimeState() != RuntimeState.RUNNING) {
                throw DeploymentSwitchException.create(DeploymentTraceEvent.FINAL_OBSERVATION,
                        DeploymentExecutionFailureType.OBSERVATION_UNVERIFIED,
                        "A final component runtime observation could not be verified");
            }
            var retention = session.retainRecentSuccessfulReleases(context.component.application());
            context.event(DeploymentTraceEvent.RELEASE_RETENTION, retention.succeeded(), retention.evidence());
        }
        applicationEvents.add(DeploymentEvent.result(DeploymentTraceEvent.APPLICATION_OBSERVATION, true,
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
                                                           DeploymentTraceEvent step, String evidence) {
        applicationEvents.add(DeploymentEvent.failed(step,
                FailureDescriptor.create(DeploymentExecutionFailureType.PRECONDITION_REJECTED,
                        OperationIdentity.create(), evidence)));
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

}
