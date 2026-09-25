package gold.debug.windowstolinux.shared.standard.deploy.execution.transaction;

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

import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.ComponentTransactionState;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.deploy.error.DeploymentExecutionFailureType;
import gold.debug.windowstolinux.shared.deploy.error.DeploymentSwitchException;
import gold.debug.windowstolinux.shared.deploy.execution.transaction.DeploymentInputMapper;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;
import gold.debug.windowstolinux.shared.linux.protocol.backup.ManagedContentPublication;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentTraceEvent;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility.HostSupportDecision;
import gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility.HostSupportStatus;
import gold.debug.windowstolinux.shared.standard.deploy.plan.ReviewedReleaseIdentityResolver;
import gold.debug.windowstolinux.shared.standard.deploy.support.HostSupportEvaluator;

/**
 * Executes one whole-application transaction through a single verified typed session.
 *
 *  <p>通过单个已验证类型化会话执行一个整体应用事务。
 */
public final class ReviewedMultiComponentDeploymentService {
    /**
     * Builds all candidates, snapshots every affected component, switches in dependency order, and restores all on failure.
     *
     *  <p>构建全部候选、快照每个受影响组件、按依赖顺序切换，并在失败时恢复全部组件。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param reviewedComponents reviewed components / 已审阅组件集合
     * @param applicationHealth caller-supplied whole-application health contract / 调用方提供的整应用健康契约
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @return all candidates, snapshots every affected component, switches in dependency order, and restores all on failure / 全部候选、快照每个受影响组件、按依赖顺序切换，并在失败时恢复全部组件
     */
    public MultiComponentDeploymentResult deploy(MultiComponentDeploymentPlan plan,
            List<ReviewedComponentDeployment> reviewedComponents, ApplicationHealthGate applicationHealth,
            DeploymentLinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
            HostKeyEvaluator hostKeyVerifier) {
        return deploy(plan, reviewedComponents, applicationHealth, gateway, endpoint, credential, hostKeyVerifier,
                ignored -> {
                });
    }

    /**
     * Executes with per-operation component and application progress. / 以本次操作的组件和应用进度执行。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param reviewedComponents reviewed components / 已审阅组件集合
     * @param applicationHealth caller-supplied whole-application health contract / 调用方提供的整应用健康契约
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @param progress progress / 进度
     * @return constructed or resolved multi component deployment result / 构造或解析得到的多组件部署结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MultiComponentDeploymentResult deploy(MultiComponentDeploymentPlan plan,
            List<ReviewedComponentDeployment> reviewedComponents, ApplicationHealthGate applicationHealth,
            DeploymentLinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
            HostKeyEvaluator hostKeyVerifier,
            java.util.function.Consumer<gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent> progress) {
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
        contexts.values().forEach(context -> context.events = new DeploymentEventJournal(progress));
        List<DeploymentEvent> applicationEvents = new DeploymentEventJournal(progress);
        for (var context : contexts.values())
            for (var message : gold.debug.windowstolinux.shared.standard.deploy.input.ManagedStoragePreparation
                    .preview(context.component.request()))
                applicationEvents.add(new DeploymentEvent(DeploymentTraceEvent.APPLICATION_PREFLIGHT, true, message,
                        message.arguments().toString(), Optional.empty()));
        boolean committed = false;
        try (DeploymentRemoteSession session = gateway.connect(endpoint, credential.duplicate(), hostKeyVerifier)) {
            Optional<MultiComponentDeploymentResult> rejected = preflight(plan, contexts, session, applicationEvents);
            if (rejected.isPresent())
                return rejected.orElseThrow();
            MultiComponentDeploymentResult buildFailure = buildAll(plan, contexts, session, applicationEvents);
            if (buildFailure != null)
                return buildFailure;
            var publications = publications(plan, contexts);
            var publication = new gold.debug.windowstolinux.shared.deploy.publication.ManagedPublicationTransaction()
                    .publish(session, publications, Optional.of(applicationHealth), (id, event) -> {
                        if (id.isEmpty())
                            applicationEvents.add(event);
                        else
                            contexts.get(id).events.add(event);
                    });
            for (var entry : contexts.entrySet())
                entry.getValue().observation = publication.observations().get(entry.getKey());
            if (publication.status() != DeploymentStatus.SUCCEEDED) {
                boolean cleaned = publication.status() != DeploymentStatus.MANUAL_RECOVERY_REQUIRED
                        && MultiComponentRecoveryCoordinator.cleanupAll(contexts, session);
                for (var context : contexts.values())
                    context.state = context.observation != null
                            ? ComponentTransactionState.RESTORED
                            : !cleaned
                                    ? ComponentTransactionState.MANUAL_RECOVERY_REQUIRED
                                    : ComponentTransactionState.FIRST_DEPLOYMENT_REVERTED;
                return MultiComponentTransactionContext.result(
                        cleaned ? publication.status() : DeploymentStatus.MANUAL_RECOVERY_REQUIRED, applicationEvents,
                        contexts, Optional.empty());
            }
            committed = true;
            boolean retained = true;
            for (MultiComponentTransactionContext context : contexts.values()) {
                try {
                    var retention = session.retainRecentSuccessfulReleases(context.component.application());
                    context.event(DeploymentTraceEvent.RELEASE_RETENTION, retention.succeeded(), retention.evidence());
                    retained &= retention.succeeded();
                } catch (LinuxOperationException failure) {
                    context.failure(DeploymentTraceEvent.RELEASE_RETENTION, failure.failure());
                    retained = false;
                }
            }
            boolean cleaned = MultiComponentRecoveryCoordinator.cleanupAll(contexts, session);
            return committedResult(plan, contexts, applicationEvents, !retained || !cleaned);
        } catch (DeploymentSwitchException failure) {
            applicationEvents.add(DeploymentEvent.failed(failure.step(), failure.failure()));
            return MultiComponentRecoveryCoordinator.recover(plan, contexts, gateway, endpoint, credential,
                    hostKeyVerifier, applicationEvents);
        } catch (LinuxOperationException failure) {
            applicationEvents.add(
                    DeploymentEvent.failed(DeploymentTraceEvent.MULTI_COMPONENT_LINUX_OPERATION, failure.failure()));
            if (committed)
                return committedResult(plan, contexts, applicationEvents, true);
            return MultiComponentRecoveryCoordinator.recover(plan, contexts, gateway, endpoint, credential,
                    hostKeyVerifier, applicationEvents);
        } catch (ArithmeticException failure) {
            applicationEvents.add(DeploymentEvent.failed(DeploymentTraceEvent.APPLICATION_PREFLIGHT,
                    FailureDescriptor.create(DeploymentExecutionFailureType.ARITHMETIC_OVERFLOW,
                            OperationIdentity.create(), "Deployment size arithmetic exceeded its bounded range")));
            return MultiComponentRecoveryCoordinator.recover(plan, contexts, gateway, endpoint, credential,
                    hostKeyVerifier, applicationEvents);
        } catch (RuntimeException failure) {
            StackTraceElement[] frames = failure.getStackTrace();
            applicationEvents.add(DeploymentEvent.failed(DeploymentTraceEvent.MULTI_COMPONENT_LINUX_OPERATION,
                    FailureDescriptor.create(DeploymentExecutionFailureType.SWITCH_UNVERIFIED,
                            OperationIdentity.create(),
                            "Unexpected deployment failure: " + failure.getClass().getSimpleName()
                                    + (frames.length == 0 ? "" : " at " + frames[0]))));
            if (committed)
                return committedResult(plan, contexts, applicationEvents, true);
            return MultiComponentRecoveryCoordinator.recover(plan, contexts, gateway, endpoint, credential,
                    hostKeyVerifier, applicationEvents);
        } finally {
            credential.clear();
        }
    }

    /** Converts completed standard candidates to mode-neutral publication inputs. / 将已完成的标准候选转为模式无关发布输入。
     * @param plan dependency order / 依赖顺序
     * @param contexts prepared candidates / 已准备候选
     * @return publication inputs / 发布输入
     */
    private static List<gold.debug.windowstolinux.shared.deploy.publication.PreparedPublication> publications(
            MultiComponentDeploymentPlan plan, Map<String, MultiComponentTransactionContext> contexts) {
        var publications = new ArrayList<gold.debug.windowstolinux.shared.deploy.publication.PreparedPublication>();
        for (String id : plan.startOrder()) {
            var context = contexts.get(id);
            var request = context.component.request();
            publications.add(new gold.debug.windowstolinux.shared.deploy.publication.PreparedPublication(id,
                    plan.dependencies().get(id), context.component.application(), context.workspace, context.build,
                    context.releaseIdentity, request.runtime(), Optional.of(request.facts()),
                    DeploymentInputMapper.manifest(context.inputs),
                    new ManagedContentPublication(plan.applicationId(), id,
                            DeploymentInputMapper.storage(context.component.application().id(),
                                    context.component.resourceBindings(), request.runtime()))));
        }
        return List.copyOf(publications);
    }

    /**
     * Marks all component transactions successful and records whether committed-release cleanup remains pending.
     * <p>将所有组件事务标记为成功，并记录已提交发布的清理是否仍待完成。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param contexts contexts / 上下文集合
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param cleanupPending cleanup pending / 清理待处理
     * @return constructed or resolved multi component deployment result / 构造或解析得到的多组件部署结果
     */
    private static MultiComponentDeploymentResult committedResult(MultiComponentDeploymentPlan plan,
            Map<String, MultiComponentTransactionContext> contexts, List<DeploymentEvent> events,
            boolean cleanupPending) {
        contexts.values().forEach(context -> context.state = ComponentTransactionState.SUCCEEDED);
        events.add(DeploymentEvent.result(DeploymentTraceEvent.APPLICATION_COMMIT, true,
                "All reviewed components and the application health gate were verified"));
        var result = MultiComponentTransactionContext.result(DeploymentStatus.SUCCEEDED, events, contexts,
                Optional.of(applicationIdentity(plan, contexts)));
        result = result.withComponentReleaseIdentities(contexts.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().releaseIdentity)));
        return cleanupPending
                ? result.withNonFatalFailure(FailureDescriptor.create(
                        DeploymentExecutionFailureType.POST_PUBLICATION_CLEANUP_PENDING, OperationIdentity.create(),
                        "Verified component release identities remain active; temporary resource cleanup needs attention"))
                : result;
    }

    /**
     * Checks shared server and per-component prerequisites, returning a rejection result when deployment cannot safely proceed.
     * <p>检查共享服务器及各组件前提条件，无法安全继续部署时返回拒绝结果。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param contexts contexts / 上下文集合
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param applicationEvents application events / 应用事件集合
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private static Optional<MultiComponentDeploymentResult> preflight(MultiComponentDeploymentPlan plan,
            Map<String, MultiComponentTransactionContext> contexts, DeploymentRemoteSession session,
            List<DeploymentEvent> applicationEvents) throws LinuxOperationException {
        var server = session.collectCapabilities();
        if (server.managedHelperProtocolVersion() != ManagedHelperProtocol.VERSION) {
            return Optional.of(rejected(contexts, applicationEvents, DeploymentTraceEvent.HELPER_PROTOCOL,
                    "Target helper protocol is stale; run product Environment Preparation first"));
        }
        LinuxCapabilityFacts capabilities = session.collectDeploymentCapabilities();
        var platform = HostSupportEvaluator.evaluatePlatform(capabilities);
        if (platform.support() != HostSupportStatus.READY_FOR_RUNTIME_VALIDATION)
            return Optional.of(rejected(contexts, applicationEvents, DeploymentTraceEvent.TYPED_HOST_COMPATIBILITY,
                    String.join("; ", platform.evidence())));
        long requiredBytes = 0;
        for (String id : plan.startOrder()) {
            MultiComponentTransactionContext context = contexts.get(id);
            var prepared = session.prepareToolchains(context.component.request().facts(),
                    context.component.request().runtime(), context.component.request().limits());
            HostSupportDecision compatibility = HostSupportEvaluator.evaluate(prepared.capabilities(),
                    context.component.request().facts(), context.component.request().runtime(), prepared.toolchains());
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

    /**
     * Builds every reviewed component candidate before permitting whole-application publication.
     * <p>在允许整应用发布前构建所有已审阅组件候选。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param contexts contexts / 上下文集合
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param applicationEvents application events / 应用事件集合
     * @return every reviewed component candidate before permitting whole-application publication; null when no matching value is available / 在允许整应用发布前构建所有已审阅组件候选；没有匹配值时为 null
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private static MultiComponentDeploymentResult buildAll(MultiComponentDeploymentPlan plan,
            Map<String, MultiComponentTransactionContext> contexts, DeploymentRemoteSession session,
            List<DeploymentEvent> applicationEvents) throws LinuxOperationException {
        for (List<String> wave : plan.buildWaves()) {
            applicationEvents.add(
                    DeploymentEvent.result(DeploymentTraceEvent.COMPONENT_BUILD_WAVE, true, String.join(",", wave)));
            for (String id : wave) {
                MultiComponentTransactionContext context = contexts.get(id);
                var request = context.component.request();
                context.workspace = new RemoteWorkspace(request.facts().applicationId(),
                        request.archive().contentSha256());
                var receipt = session.uploadSource(request.archive(), context.workspace,
                        request.limits().maxWorkspaceBytes());
                if (!receipt.contentSha256().equals(request.archive().contentSha256())
                        || receipt.byteCount() != request.archive().byteCount()) {
                    context.event(DeploymentTraceEvent.SOURCE_UPLOAD, false,
                            "Target upload identity differs from the reviewed archive");
                    context.state = ComponentTransactionState.BUILD_FAILED;
                    boolean cleaned = MultiComponentRecoveryCoordinator.cleanupAll(contexts, session);
                    MultiComponentRecoveryCoordinator.markDiscarded(contexts, id);
                    if (!cleaned)
                        MultiComponentRecoveryCoordinator.markCleanupFailure(contexts);
                    return MultiComponentTransactionContext.result(
                            cleaned ? DeploymentStatus.FAILED_BUILD : DeploymentStatus.MANUAL_RECOVERY_REQUIRED,
                            applicationEvents, contexts, Optional.empty());
                }
                context.event(DeploymentTraceEvent.SOURCE_UPLOAD, true, receipt.evidence());
                context.build = session.buildDeployment(request.facts(), request.runtime(), context.workspace,
                        request.limits(), DeploymentInputMapper.build(request.configuration()));
                context.event(DeploymentTraceEvent.REMOTE_BUILD, context.build.succeeded(), context.build.evidence());
                if (!context.build.succeeded()
                        || !context.build.sourceSha256().equals(request.archive().contentSha256())) {
                    context.state = ComponentTransactionState.BUILD_FAILED;
                    boolean cleaned = MultiComponentRecoveryCoordinator.cleanupAll(contexts, session);
                    MultiComponentRecoveryCoordinator.markDiscarded(contexts, id);
                    if (!cleaned)
                        MultiComponentRecoveryCoordinator.markCleanupFailure(contexts);
                    return MultiComponentTransactionContext.result(
                            cleaned ? DeploymentStatus.FAILED_BUILD : DeploymentStatus.MANUAL_RECOVERY_REQUIRED,
                            applicationEvents, contexts, Optional.empty());
                }
                context.inputs = DeploymentInputMapper.stage(session, context.component.application(),
                        request.configuration(), context.component.resolvedSecrets());
                context.releaseIdentity = ReviewedReleaseIdentityResolver
                        .bind(ReviewedReleaseIdentityResolver.from(request), context.build.toolchains());
                context.event(DeploymentTraceEvent.CANDIDATE_READY, true,
                        "Candidate build and immutable deployment inputs are ready");
            }
        }
        return null;
    }

    /**
     * Validates reviewed component coverage and constructs transaction contexts in the plan's deterministic order.
     * <p>校验已审阅组件覆盖范围，并按计划的确定顺序构建事务上下文。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param reviewed reviewed / 已审阅
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param applicationHealth caller-supplied whole-application health contract / 调用方提供的整应用健康契约
     * @return constructed or resolved linked hash map / 构造或解析得到的Linked哈希映射
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static LinkedHashMap<String, MultiComponentTransactionContext> contexts(MultiComponentDeploymentPlan plan,
            List<ReviewedComponentDeployment> reviewed, SshEndpoint endpoint, ApplicationHealthGate applicationHealth) {
        Map<String, ReviewedComponentDeployment> indexed = new LinkedHashMap<>();
        for (ReviewedComponentDeployment component : Objects.requireNonNull(reviewed, "reviewedComponents")) {
            if (indexed.putIfAbsent(component.componentId(), component) != null) {
                throw new IllegalArgumentException("reviewed component identifiers must be unique");
            }
        }
        if (!indexed.keySet().equals(plan.candidateNamespaces().keySet())
                || !indexed.containsKey(applicationHealth.componentId())) {
            throw new IllegalArgumentException(
                    "reviewed components and application health must exactly match the plan");
        }
        LinkedHashMap<String, MultiComponentTransactionContext> contexts = new LinkedHashMap<>();
        for (String id : plan.startOrder()) {
            ReviewedComponentDeployment component = indexed.get(id);
            if (!plan.candidateNamespaces().get(id).equals(component.application().id())) {
                throw new IllegalArgumentException("component candidate namespace differs from the reviewed identity");
            }
            if (component.request().limits().runAsRoot() || !"root".equals(endpoint.username())) {
                throw new IllegalArgumentException(
                        "root build approval and SSH identity must agree for every component");
            }
            contexts.put(id, new MultiComponentTransactionContext(component));
        }
        return contexts;
    }

    /**
     * Builds a rejected result with its retained reason and evidence.
     * <p>构建被拒绝结果并保留其原因及证据。
     *
     * @param contexts contexts / 上下文集合
     * @param applicationEvents application events / 应用事件集合
     * @param step step / 步骤
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return a rejected result with its retained reason and evidence / 被拒绝结果并保留其原因及证据
     */
    private static MultiComponentDeploymentResult rejected(Map<String, MultiComponentTransactionContext> contexts,
            List<DeploymentEvent> applicationEvents, DeploymentTraceEvent step, String evidence) {
        applicationEvents.add(DeploymentEvent.failed(step, FailureDescriptor
                .create(DeploymentExecutionFailureType.PRECONDITION_REJECTED, OperationIdentity.create(), evidence)));
        return MultiComponentTransactionContext.result(DeploymentStatus.PRECONDITION_REJECTED, applicationEvents,
                contexts, Optional.empty());
    }

    /**
     * Hashes the application identifier and ordered component identities into a deterministic application release identity.
     * <p>对应用标识及有序组件身份计算哈希，得到确定的整应用发布身份。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param contexts contexts / 上下文集合
     * @return application identity text / 应用身份文本
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static String applicationIdentity(MultiComponentDeploymentPlan plan,
            Map<String, MultiComponentTransactionContext> contexts) {
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

    /**
     * Updates reviewed multi component deployment.
     * <p>更新已审阅多组件部署。
     *
     * @param digest content identity used for independent verification / 独立验证所用的内容身份
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

}
