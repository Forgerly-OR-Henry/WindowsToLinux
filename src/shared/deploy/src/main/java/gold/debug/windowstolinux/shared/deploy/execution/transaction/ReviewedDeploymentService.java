package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedReleaseIdentityResolver;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentTraceEvent;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.compatibility.HostSupportStatus;
import gold.debug.windowstolinux.shared.deploy.contract.result.compatibility.HostSupportDecision;
import gold.debug.windowstolinux.shared.deploy.support.HostSupportEvaluator;
import gold.debug.windowstolinux.shared.deploy.error.DeploymentExecutionFailureType;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.protocol.backup.ManagedContentPublication;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Executes one reviewed, type-specific deployment through the same bounded transaction semantics as every managed application.
 *
 *  <p>通过与所有受管应用相同的有界事务语义执行一个经审阅、类型专属的部署。
 */
public final class ReviewedDeploymentService {
    /**
     * MINIMUM FREE SPACE MULTIPLIER.
     * <p>最小剩余SPACEMULTIPLIER。
     */
    private static final long MINIMUM_FREE_SPACE_MULTIPLIER = 3;

    /**
     * Deploys one fully reviewed request without accepting a raw command or an inferred runtime.
     *
     *  <p>部署一个完整审阅的请求，不接受原始命令或推断的运行时。
     *
     * @param request the reviewed deployment input / 经审阅的部署输入
     * @param application the stable managed identity / 稳定的受管身份
     * @param gateway the typed SSH gateway / 类型化 SSH 网关
     * @param endpoint the verified endpoint / 已验证的端点
     * @param credential the selected credential / 选定的凭据
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @return the terminal deployment result / 部署终态结果
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public DeploymentResult deploy(ReviewedDeploymentRequest request, ManagedApplication application,
                                   DeploymentLinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
                                   HostKeyEvaluator hostKeyVerifier) {
        if (!request.secretReferences().isEmpty()) {
            throw new IllegalArgumentException("resolved secret revisions are required for this reviewed request");
        }
        return deploy(request, application, gateway, endpoint, credential, hostKeyVerifier, List.of());
    }

    /**
     * Executes a reviewed transaction with short-lived exact secret revisions. / 使用短生命周期精确秘密修订执行经审阅事务。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @param resolvedSecrets resolved secrets / 已解析秘密集合
     * @return constructed or resolved deployment result / 构造或解析得到的部署结果
     */
    public DeploymentResult deploy(ReviewedDeploymentRequest request, ManagedApplication application,
                                   DeploymentLinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
                                   HostKeyEvaluator hostKeyVerifier, List<ResolvedSecretRevision> resolvedSecrets) {
        return deploy(request, application, gateway, endpoint, credential, hostKeyVerifier, resolvedSecrets, ignored -> { });
    }

    /**
     * Executes with a per-operation observer of real transaction events. / 使用本次操作的观察器接收真实事务事件。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @param resolvedSecrets resolved secrets / 已解析秘密集合
     * @param progress progress / 进度
     * @return constructed or resolved deployment result / 构造或解析得到的部署结果
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentResult deploy(ReviewedDeploymentRequest request, ManagedApplication application,
            DeploymentLinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
            HostKeyEvaluator hostKeyVerifier, List<ResolvedSecretRevision> resolvedSecrets,
            java.util.function.Consumer<gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent> progress) {
        request = java.util.Objects.requireNonNull(request, "request");
        application = java.util.Objects.requireNonNull(application, "application");
        gateway = java.util.Objects.requireNonNull(gateway, "gateway");
        endpoint = java.util.Objects.requireNonNull(endpoint, "endpoint");
        credential = java.util.Objects.requireNonNull(credential, "credential");
        hostKeyVerifier = java.util.Objects.requireNonNull(hostKeyVerifier, "hostKeyVerifier");
        resolvedSecrets = List.copyOf(java.util.Objects.requireNonNull(resolvedSecrets, "resolvedSecrets"));
        if (!resolvedSecrets.stream().map(ResolvedSecretRevision::reference).toList().equals(request.secretReferences())) {
            throw new IllegalArgumentException("resolved secret revisions must exactly match the reviewed references");
        }
        List<DeploymentEvent> events = new DeploymentEventJournal(progress);
        DeploymentResult initialRejection = validateRequestBinding(request, application, endpoint, events);
        if (initialRejection != null) return initialRejection;
        for (var message : gold.debug.windowstolinux.shared.deploy.input.ManagedStoragePreparation.preview(request))
            events.add(new DeploymentEvent(DeploymentTraceEvent.APPLICATION_PREFLIGHT,true,message,message.arguments().toString(),Optional.empty()));
        RemoteWorkspace workspace = new RemoteWorkspace(application.id(), request.archive().contentSha256());
        String releaseIdentity = ReviewedReleaseIdentityResolver.from(request);
        ReleaseSnapshot snapshot = null;
        DeploymentBuildResult build = null;
        DeploymentInputManifest inputs = null;
        boolean candidateMayExist = false;
        LifecycleObservation committedObservation = null;
        try (DeploymentRemoteSession session = gateway.connect(endpoint, credential.duplicate(), hostKeyVerifier)) {
            DeploymentResult preflightRejection = preflight(request, session, events);
            if (preflightRejection != null) return preflightRejection;

            candidateMayExist = true;
            var receipt = session.uploadSource(request.archive(), workspace, request.limits().maxWorkspaceBytes());
            if (!receipt.contentSha256().equals(request.archive().contentSha256())
                    || receipt.byteCount() != request.archive().byteCount()) {
                if (!cleanup(session, workspace, events)) return new DeploymentResult(
                        DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.empty(), Optional.empty());
                return rejected(events, DeploymentTraceEvent.SOURCE_UPLOAD,
                        "Target archive digest or size differs from the reviewed archive");
            }
            events.add(DeploymentEvent.result(DeploymentTraceEvent.SOURCE_UPLOAD, true, receipt.evidence()));

            build = session.buildDeployment(request.facts(), request.runtime(), workspace, request.limits(),
                    DeploymentInputMapper.build(request.configuration()));
            events.add(DeploymentEvent.result(DeploymentTraceEvent.REMOTE_BUILD, build.succeeded(), build.evidence()));
            if (!build.succeeded()) {
                boolean cleaned = cleanup(session, workspace, events);
                return new DeploymentResult(cleaned ? DeploymentStatus.FAILED_BUILD : DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.empty(), Optional.empty());
            }
            if (!request.archive().contentSha256().equals(build.sourceSha256())) {
                if (!cleanup(session, workspace, events)) return new DeploymentResult(
                        DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.empty(), Optional.empty());
                return rejected(events, DeploymentTraceEvent.BUILD_PROVENANCE,
                        "The build result is not bound to the reviewed source archive");
            }

            inputs = DeploymentInputMapper.stage(session, application, request.configuration(), resolvedSecrets);
            releaseIdentity = ReviewedReleaseIdentityResolver.bind(releaseIdentity, build.toolchains());
            events.add(DeploymentEvent.result(DeploymentTraceEvent.DEPLOYMENT_INPUTS, true,
                    "Immutable configuration and exact secret revisions were sealed outside the release tree"));

            snapshot = session.snapshotDeployment(application, request.runtime());
            events.add(DeploymentEvent.result(DeploymentTraceEvent.SNAPSHOT, true, snapshot.evidence()));
            RemoteStepResult publish = session.publishDeployment(application, request.facts(), workspace, build, releaseIdentity,
                    request.runtime(), DeploymentInputMapper.manifest(inputs),
                    new ManagedContentPublication(application.id(), application.id(), DeploymentInputMapper.storage(application.id(),
                            new gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings(request.fileBindings(), request.databaseBindings()), request.runtime())), snapshot);
            events.add(DeploymentEvent.result(DeploymentTraceEvent.PUBLISH, publish.succeeded(), publish.evidence()));
            if (!publish.succeeded()) {
                return recover(session, request, application, workspace, snapshot, build, releaseIdentity, inputs, events);
            }
            HealthCheckResult health = session.checkDeploymentHealth(application, request.runtime(), request.runtime().healthCheck());
            events.add(DeploymentEvent.result(DeploymentTraceEvent.CANDIDATE_HEALTH, health.healthy(), health.evidence()));
            if (!health.healthy()) {
                return recover(session, request, application, workspace, snapshot, build, releaseIdentity, inputs, events);
            }
            LifecycleObservation observation = session.observeDeployment(application, request.runtime());
            events.add(DeploymentEvent.result(DeploymentTraceEvent.FINAL_OBSERVATION, observation.ownershipVerified() && observation.runtimeState() == (request.runtime().workload().mode() == gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.ExecutionMode.ON_DEMAND ? RuntimeState.INSTALLED : RuntimeState.RUNNING), observation.evidence()));
            if (!observation.ownershipVerified() || observation.runtimeState() != (request.runtime().workload().mode() == gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.ExecutionMode.ON_DEMAND ? RuntimeState.INSTALLED : RuntimeState.RUNNING)) {
                return recover(session, request, application, workspace, snapshot, build, releaseIdentity, inputs, events);
            }
            session.backupArtifacts().endMaintenance(application, "deployment-" + releaseIdentity);
            committedObservation = observation;
            return finishPublication(session, application, workspace, observation, releaseIdentity, events);
        } catch (LinuxOperationException exception) {
            events.add(DeploymentEvent.failed(DeploymentTraceEvent.LINUX_OPERATION, exception.failure()));
            if (committedObservation != null) {
                return new DeploymentResult(DeploymentStatus.SUCCEEDED, events, Optional.of(committedObservation),
                        Optional.of(releaseIdentity)).withNonFatalFailure(failure(
                        DeploymentExecutionFailureType.POST_PUBLICATION_CLEANUP_PENDING,
                        "Release identity was verified before the session cleanup failed"));
            }
            if (snapshot != null && build != null && build.succeeded() && inputs != null) {
                return recoverAfterInterruptedSession(request, application, gateway, endpoint, credential,
                        hostKeyVerifier, workspace, snapshot, build, releaseIdentity, inputs, events);
            }
            if (candidateMayExist) {
                return cleanupAfterInterruptedSession(gateway, endpoint, credential, hostKeyVerifier, workspace, events);
            }
            return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
        } catch (ArithmeticException exception) {
            events.add(DeploymentEvent.failed(DeploymentTraceEvent.APPLICATION_PREFLIGHT,
                    failure(DeploymentExecutionFailureType.ARITHMETIC_OVERFLOW,
                            "Deployment size arithmetic exceeded its bounded range")));
            if (candidateMayExist) {
                return cleanupAfterInterruptedSession(gateway, endpoint, credential, hostKeyVerifier, workspace, events);
            }
            return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
        } finally {
            credential.clear();
        }
    }

    /**
     * Finishes publication.
     * <p>完成发布。
     *
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param observation observation / 观测
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @return constructed or resolved deployment result / 构造或解析得到的部署结果
     */
    private static DeploymentResult finishPublication(DeploymentRemoteSession session, ManagedApplication application,
            RemoteWorkspace workspace, LifecycleObservation observation, String releaseIdentity,
            List<DeploymentEvent> events) {
    boolean retained = false;
    try {
        RemoteStepResult retention = session.retainRecentSuccessfulReleases(application);
        retained = retention.succeeded();
        events.add(DeploymentEvent.result(DeploymentTraceEvent.RELEASE_RETENTION, retained, retention.evidence()));
    } catch (LinuxOperationException exception) {
        events.add(DeploymentEvent.failed(DeploymentTraceEvent.RELEASE_RETENTION, exception.failure()));
    }
    boolean cleaned = cleanup(session, workspace, events);
    DeploymentResult result = new DeploymentResult(DeploymentStatus.SUCCEEDED, events, Optional.of(observation),
            Optional.of(releaseIdentity));
    return retained && cleaned ? result : result.withNonFatalFailure(failure(
            DeploymentExecutionFailureType.POST_PUBLICATION_CLEANUP_PENDING,
            "The verified release remains active; candidate or retained release cleanup needs attention"));
    }

    /**
     * Validates request binding.
     * <p>校验请求绑定。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @return constructed or resolved deployment result; null when no matching value is available / 构造或解析得到的部署结果；没有匹配值时为 null
     */
    private static DeploymentResult validateRequestBinding(
            ReviewedDeploymentRequest request, ManagedApplication application, SshEndpoint endpoint,
            List<DeploymentEvent> events) {
        if (!application.server().equals(request.server())
                || !application.id().equals(request.facts().applicationId())) {
            return rejected(events, DeploymentTraceEvent.MANAGED_IDENTITY,
                    "Reviewed request and managed application identity do not match");
        }
        if (request.limits().runAsRoot() || !"root".equals(endpoint.username())) {
            return rejected(events, DeploymentTraceEvent.ROOT_BUILD_SESSION,
                    "Root management and restricted build execution are required before a candidate is created");
        }
        return null;
    }

    /**
     * Checks source footprint and remote deployment prerequisites before allowing release changes.
     * <p>在允许发布变更前检查源码占用及远端部署前提条件。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @return constructed or resolved deployment result / 构造或解析得到的部署结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private static DeploymentResult preflight(
            ReviewedDeploymentRequest request, DeploymentRemoteSession session, List<DeploymentEvent> events)
            throws LinuxOperationException {
        long sourceFootprint = Math.addExact(request.archive().byteCount(), request.archive().uncompressedByteCount());
        if (sourceFootprint > request.limits().maxWorkspaceBytes()) {
            return rejected(events, DeploymentTraceEvent.SOURCE_WORKSPACE,
                    "The reviewed archive exceeds the confirmed workspace limit");
        }
        long requiredBytes = Math.max(Math.multiplyExact(request.archive().byteCount(), MINIMUM_FREE_SPACE_MULTIPLIER),
                request.limits().maxWorkspaceBytes());
        var serverCapabilities = session.collectCapabilities();
        if (serverCapabilities.managedHelperProtocolVersion() != ManagedHelperProtocol.VERSION) {
            return rejected(events, DeploymentTraceEvent.HELPER_PROTOCOL,
                    "The target helper protocol is stale; run product Environment Preparation before uploading source");
        }
        events.add(DeploymentEvent.result(DeploymentTraceEvent.HELPER_PROTOCOL, true,
                "Target helper protocol " + ManagedHelperProtocol.VERSION + " was verified before source upload"));
        if (serverCapabilities.availableBytes() < requiredBytes) {
            return rejected(events, DeploymentTraceEvent.TARGET_SPACE,
                    "Target free space is insufficient for the reviewed candidate");
        }
        events.add(DeploymentEvent.result(DeploymentTraceEvent.TARGET_CAPABILITIES, true,
                "Target capabilities were collected before the reviewed deployment"));
        var platform = HostSupportEvaluator.evaluatePlatform(session.collectDeploymentCapabilities());
        if (platform.support() != HostSupportStatus.READY_FOR_RUNTIME_VALIDATION)
            return rejected(events, DeploymentTraceEvent.TYPED_HOST_COMPATIBILITY, String.join("; ", platform.evidence()));
        events.add(DeploymentEvent.result(DeploymentTraceEvent.TYPED_HOST_COMPATIBILITY, true,
                "Preparing the reviewed project toolchains before uploading source"));
        var prepared = session.prepareToolchains(request.facts(), request.runtime(), request.limits());
        HostSupportDecision typedCompatibility = HostSupportEvaluator.evaluate(
                prepared.capabilities(), request.facts(), request.runtime(), prepared.toolchains());
        events.add(DeploymentEvent.result(DeploymentTraceEvent.TYPED_HOST_COMPATIBILITY,
                typedCompatibility.support() == HostSupportStatus.READY_FOR_RUNTIME_VALIDATION,
                String.join("; ", typedCompatibility.evidence())));
        return typedCompatibility.support() == HostSupportStatus.READY_FOR_RUNTIME_VALIDATION ? null
                : new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events,
                Optional.empty(), Optional.empty());
    }

    /**
     * Attempts verified rollback through a recovery session and retains both the original deployment failure and recovery evidence.
     * <p>通过恢复会话尝试已验证回滚，并保留原始部署失败及恢复证据。
     *
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @param build build / 构建
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @return constructed or resolved deployment result / 构造或解析得到的部署结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private static DeploymentResult recover(DeploymentRemoteSession session, ReviewedDeploymentRequest request,
                                             ManagedApplication application, RemoteWorkspace workspace,
                                             ReleaseSnapshot snapshot, DeploymentBuildResult build,
                                             String releaseIdentity, DeploymentInputManifest inputs,
                                             List<DeploymentEvent> events)
            throws LinuxOperationException {
        RemoteStepResult rollback = session.rollbackDeployment(application, snapshot, build, releaseIdentity,
                request.runtime(), DeploymentInputMapper.manifest(inputs));
        events.add(DeploymentEvent.result(DeploymentTraceEvent.ROLLBACK, rollback.succeeded(), rollback.evidence()));
        boolean cleaned = cleanup(session, workspace, events);
        if (!rollback.succeeded() || !cleaned) {
            return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.empty(), Optional.empty());
        }
        if (!snapshot.hasPreviousRelease()) {
            session.backupArtifacts().endMaintenance(application, "deployment-" + releaseIdentity);
            return new DeploymentResult(DeploymentStatus.FAILED_FIRST_DEPLOYMENT, events, Optional.empty(), Optional.empty());
        }
        LifecycleObservation restored;
        try {
            restored = session.observe(application);
        } catch (LinuxOperationException exception) {
            events.add(DeploymentEvent.failed(DeploymentTraceEvent.ROLLBACK_OBSERVATION, exception.failure()));
            return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events,
                    Optional.empty(), Optional.empty());
        }
        events.add(DeploymentEvent.result(DeploymentTraceEvent.ROLLBACK_OBSERVATION, restored.ownershipVerified(), restored.evidence()));
        boolean expectedState = snapshot.previousWasRunning()
                ? restored.runtimeState() == RuntimeState.RUNNING : restored.runtimeState() == RuntimeState.STOPPED || restored.runtimeState() == RuntimeState.INSTALLED;
        if (!restored.ownershipVerified() || !expectedState) {
            return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.of(restored), Optional.empty());
        }
        session.backupArtifacts().endMaintenance(application, "deployment-" + releaseIdentity);
        return new DeploymentResult(DeploymentStatus.FAILED_ROLLED_BACK, events, Optional.of(restored), Optional.empty());
    }

    /**
     * Recovers after interrupted session.
     * <p>恢复之后已中断会话。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @param build build / 构建
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @return constructed or resolved deployment result / 构造或解析得到的部署结果
     */
    private static DeploymentResult recoverAfterInterruptedSession(
            ReviewedDeploymentRequest request, ManagedApplication application, DeploymentLinuxGateway gateway,
            SshEndpoint endpoint, SshCredential credential, HostKeyEvaluator hostKeyVerifier, RemoteWorkspace workspace,
            ReleaseSnapshot snapshot, DeploymentBuildResult build, String releaseIdentity, DeploymentInputManifest inputs,
            List<DeploymentEvent> events
    ) {
        try (DeploymentRemoteSession recoverySession = gateway.connect(
                endpoint, credential.duplicate(), hostKeyVerifier)) {
            events.add(DeploymentEvent.result(DeploymentTraceEvent.RECOVERY_RECONNECT, true,
                    "SSH host was reverified after the typed publication session was interrupted"));
            return recover(recoverySession, request, application, workspace, snapshot, build, releaseIdentity, inputs,
                    events);
        } catch (LinuxOperationException exception) {
            events.add(DeploymentEvent.failed(DeploymentTraceEvent.ROLLBACK, exception.failure()));
            return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events,
                    Optional.empty(), Optional.empty());
        }
    }

    /**
     * Cleans up after interrupted session.
     * <p>清理之后已中断会话。
     *
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @return constructed or resolved deployment result / 构造或解析得到的部署结果
     */
    private static DeploymentResult cleanupAfterInterruptedSession(
            DeploymentLinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
            HostKeyEvaluator hostKeyVerifier, RemoteWorkspace workspace, List<DeploymentEvent> events
    ) {
        try (DeploymentRemoteSession cleanupSession = gateway.connect(
                endpoint, credential.duplicate(), hostKeyVerifier)) {
            events.add(DeploymentEvent.result(DeploymentTraceEvent.CANDIDATE_CLEANUP_RECONNECT, true,
                    "SSH host was reverified after the typed candidate session was interrupted"));
            if (cleanup(cleanupSession, workspace, events)) {
                return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events,
                        Optional.empty(), Optional.empty());
            }
        } catch (LinuxOperationException exception) {
            events.add(DeploymentEvent.failed(
                    DeploymentTraceEvent.CANDIDATE_CLEANUP_RECONNECT, exception.failure()));
        }
        return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events,
                Optional.empty(), Optional.empty());
    }

    /**
     * Builds a rejected result with its retained reason and evidence.
     * <p>构建被拒绝结果并保留其原因及证据。
     *
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param step step / 步骤
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return a rejected result with its retained reason and evidence / 被拒绝结果并保留其原因及证据
     */
    private static DeploymentResult rejected(
            List<DeploymentEvent> events, DeploymentTraceEvent step, String evidence) {
        events.add(DeploymentEvent.failed(step,
                failure(DeploymentExecutionFailureType.PRECONDITION_REJECTED, evidence)));
        return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
    }

    /**
     * Cleans up reviewed deployment.
     * <p>清理已审阅部署。
     *
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @return true when cleans up reviewed deployment, false otherwise / 清理已审阅部署时为 true，否则为 false
     */
    private static boolean cleanup(DeploymentRemoteSession session, RemoteWorkspace workspace, List<DeploymentEvent> events) {
        try {
            RemoteStepResult cleanup = session.cleanupCandidate(workspace);
            events.add(DeploymentEvent.result(DeploymentTraceEvent.CANDIDATE_CLEANUP, cleanup.succeeded(), cleanup.evidence()));
            return cleanup.succeeded();
        } catch (LinuxOperationException exception) {
            events.add(DeploymentEvent.failed(DeploymentTraceEvent.CANDIDATE_CLEANUP, exception.failure()));
            return false;
        }
    }

    /**
     * Creates or preserves the module-owned failure for the supplied cause and diagnostic evidence.
     * <p>为所提供原因及诊断证据创建或保留模块自有失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return or preserves the module-owned failure for the supplied cause and diagnostic evidence / 为所提供原因及诊断证据创建或保留模块自有失败
     */
    private static FailureDescriptor failure(DeploymentExecutionFailureType type, String diagnostic) {
        return FailureDescriptor.create(type, OperationIdentity.create(), diagnostic);
    }
}
