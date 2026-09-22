package gold.debug.windowstolinux.shared.backup.execution.collection;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import gold.debug.windowstolinux.shared.backup.contract.definition.*;
import gold.debug.windowstolinux.shared.backup.contract.spi.*;
import gold.debug.windowstolinux.shared.backup.contract.validation.*;
import gold.debug.windowstolinux.shared.backup.extension.adapter.LinuxDatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.extension.registry.DatabaseAdapterRegistry;
import gold.debug.windowstolinux.shared.backup.manifest.*;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.backup.*;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.capability.*;
import gold.debug.windowstolinux.shared.model.failure.*;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.ManagedHelperProtocolVersion;

/**
 * Owns the shared stop, collect, recover and cleanup transaction. / 持有共享的停机、采集、恢复和清理事务。
 */
public final class BackupCollectionService {
    /**
     * Independently checks downloaded archive shape, sizes and member paths before accepting remote artifacts.
     * <p>接受远端制品前独立检查下载归档形态、大小及成员路径。
     */
    private final ManagedArtifactValidator validator;

    /**
     * Validates and binds the inputs required by backup collection service.
     * <p>校验并绑定备份采集服务所需输入。
     *
     * @param policy explicit validation and resource-bound policy / 显式校验及资源边界策略
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupCollectionService(BackupArchivePolicy policy) {
        validator = new ManagedArtifactValidator(Objects.requireNonNull(policy, "policy"));
    }

    /**
     * Collects verified backup members inside a maintenance window and restores the original running set before returning. Cancellation does not bypass recovery. Unverified recovery retains remote artifacts and maintenance markers and reports manual recovery; cleanup failures never replace the collection failure.
     * <p>在维护窗口内采集已验证备份成员，并在返回前恢复原始运行集合。取消不会跳过恢复。恢复未验证时保留远端制品及维护标记并报告人工恢复；清理失败不会覆盖采集失败。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param storage storage / 存储
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return validated members, database metadata, runtime facts and original observations after verified recovery and cleanup / 经验证恢复及清理后的已验证成员、数据库元数据、运行事实及原始观测
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     */
    public BackupCollectionResult collect(BackupCollectionRequest request, DeploymentRemoteSession session,
            BackupCollectionMaterialPort storage, BackupCollectionInteraction interaction)
            throws IOException, LinuxOperationException, InterruptedException {
        var components = new LinkedHashMap<String, BackupCollectionRequest.Component>();
        request.components().forEach(component -> components.put(component.id(), component));
        interaction.checkCancelled();
        var server = session.collectCapabilities();
        var linux = session.collectDeploymentCapabilities();
        if (server.managedHelperProtocolVersion() != ManagedHelperProtocolVersion.CURRENT || !server.tarAvailable()
                || !server.nonInteractiveSudoAvailable())
            throw failure(request, BackupFailureType.COLLECTION_PREFLIGHT_FAILED,
                    "The target lacks the current managed backup capability", null);
        var original = observeOriginal(request, session, interaction, components);
        var running = request.plan().startOrder().stream()
                .filter(id -> original.get(id).runtimeState() == RuntimeState.RUNNING).toList();
        var paused = new ArrayList<BackupCollectionRequest.Component>();
        var stopping = new HashSet<String>();
        var material = new LinkedHashMap<BackupMember, Path>();
        var databases = new LinuxDatabaseOperationPort(session.databaseOperations());
        DatabaseBackupArtifact databaseArtifact = null;
        Exception problem = null;
        try {
            for (var component : request.components()) {
                interaction.checkCancelled();
                session.backupArtifacts().beginMaintenance(component.application(), request.operationId());
                paused.add(component);
            }
            for (String id : request.plan().stopOrder())
                if (running.contains(id)) {
                    interaction.checkCancelled();
                    stopping.add(id);
                    var component = components.get(id);
                    var stopped = session.executeDeploymentLifecycle(component.application(), component.runtime(),
                            LifecycleAction.STOP);
                    if (!stopped.ownershipVerified() || stopped.runtimeState() != RuntimeState.STOPPED)
                        throw failure(request, BackupFailureType.COLLECTION_FAILED,
                                "A component did not enter a verified stopped state", null);
                }
            collectComponents(request, session, storage, interaction, components, material);
            if (request.database().isPresent()) {
                interaction.checkCancelled();
                var selected = request.database().orElseThrow();
                databaseArtifact = DatabaseAdapterRegistry.defaults(databases).require(selected.profile().type())
                        .backup(new DatabaseBackupRequest(components.get(selected.componentId()).application().id(),
                                selected.profile(), true, true));
                String name = "database/" + selected.databaseId() + ".dump";
                Path target = storage.member(name);
                try (var output = storage.open(target)) {
                    databases.copyArtifact(databaseArtifact, output);
                }
                BackupMember member = databaseEvidence(name, target);
                if (member.size() != databaseArtifact.byteCount() || !member.sha256().equals(databaseArtifact.sha256()))
                    throw failure(request, BackupFailureType.DATABASE_EVIDENCE_INVALID,
                            "Downloaded database material differs from remote evidence", null);
                material.put(member, target);
            }
            interaction.checkCancelled();
        } catch (Exception collection) {
            problem = collection;
        }

        // Cancellation must not prevent restoration of applications stopped by this attempt. / 取消不能阻止恢复本次尝试停止的应用。
        boolean interrupted = Thread.interrupted() || problem instanceof InterruptedException;
        try {
            Exception recovery = recover(request, session, components, running, stopping);
            if (recovery != null) {
                BackupException manual = failure(request, BackupFailureType.COLLECTION_RECOVERY_FAILED,
                        "Original runtime state could not be verified; remote material and maintenance markers were retained",
                        problem == null ? recovery : problem);
                if (problem != null)
                    manual.addSuppressed(recovery);
                throw new BackupException(manual.failure().withRecovery(FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY,
                        FailureRecoveryDisposition.UNVERIFIED), manual);
            }
            problem = cleanup(request, session, paused, databases, databaseArtifact, problem);
            if (problem instanceof InterruptedException cancellation)
                throw cancellation;
            if (problem instanceof LinuxOperationException remote)
                throw remote;
            if (problem instanceof IOException io)
                throw io;
            if (problem instanceof RuntimeException runtime)
                throw runtime;
            if (problem != null)
                throw failure(request, BackupFailureType.COLLECTION_FAILED, "Backup collection failed", problem);
            return new BackupCollectionResult(material,
                    databaseArtifact == null ? BackupDatabase.none() : databaseArtifact.database(),
                    runtime(request, server, linux), original);
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    /**
     * Requires authoritative ownership and records each component's state before any maintenance or stop action.
     * <p>在任何维护或停止动作前要求权威归属证据，并记录各组件状态。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @return component identifiers mapped to authoritative pre-maintenance runtime observations in start order / 按启动顺序排列的组件标识到维护前权威运行观测的映射
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     */
    private Map<String, LifecycleObservation> observeOriginal(BackupCollectionRequest request,
            DeploymentRemoteSession session, BackupCollectionInteraction interaction,
            Map<String, BackupCollectionRequest.Component> components)
            throws IOException, LinuxOperationException, InterruptedException {
        var original = new LinkedHashMap<String, LifecycleObservation>();
        for (String id : request.plan().startOrder()) {
            interaction.checkCancelled();
            var component = components.get(id);
            var observation = session.observeDeployment(component.application(), component.runtime());
            if (!observation.ownershipVerified()
                    || !Set.of(RuntimeState.RUNNING, RuntimeState.STOPPED, RuntimeState.INSTALLED)
                            .contains(observation.runtimeState()))
                throw failure(request, BackupFailureType.COLLECTION_PREFLIGHT_FAILED,
                        "Every component requires an authoritative runtime observation", null);
            original.put(id, observation);
        }
        return original;
    }

    /**
     * Attempts database-artifact cleanup, owned maintenance-marker release and operation cleanup independently after verified runtime recovery. Retains all additional failures as suppressed exceptions.
     * <p>在运行恢复已验证后独立尝试数据库制品清理、自有维护标记释放及操作清理。将所有附加失败保留为抑制异常。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param paused paused / 已暂停
     * @param databases databases / 数据库集合
     * @param databaseArtifact database artifact / 数据库制品
     * @param problem problem / 问题
     * @return the original or first cleanup failure, with additional failures suppressed; null when none occurred / 原始失败或第一个清理失败，并抑制附加失败；没有失败时为 null
     */
    private Exception cleanup(BackupCollectionRequest request, DeploymentRemoteSession session,
            List<BackupCollectionRequest.Component> paused, DatabaseOperationPort databases,
            DatabaseBackupArtifact databaseArtifact, Exception problem) {
        // Independently attempt cleanup steps so a failure does not hide later cleanup failures. / 独立尝试各项清理，避免先发生的故障掩盖后续清理故障。
        if (databaseArtifact != null) {
            try {
                databases.discardArtifact(databaseArtifact);
            } catch (Exception cleanup) {
                problem = accumulate(problem, cleanup);
            }
        }
        if (request.ownsMaintenance())
            for (var component : paused.reversed()) {
                try {
                    session.backupArtifacts().endMaintenance(component.application(), request.operationId());
                } catch (Exception cleanup) {
                    problem = accumulate(problem, cleanup);
                }
            }
        try {
            if (!session.backupArtifacts().discardBackupOperation(request.operationId()).succeeded())
                throw failure(request, BackupFailureType.CLEANUP_FAILED, "Remote backup operation cleanup failed",
                        null);
        } catch (Exception cleanup) {
            problem = accumulate(problem, cleanup);
        }
        return problem;
    }

    /**
     * Collects release trees, reviewed file bindings and container images in dependency order, checking cancellation between members.
     * <p>按依赖顺序采集发布树、已审阅文件绑定及容器镜像，并在成员之间检查取消。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param storage storage / 存储
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param material material / 素材
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private void collectComponents(BackupCollectionRequest request, DeploymentRemoteSession session,
            BackupCollectionMaterialPort storage, BackupCollectionInteraction interaction,
            Map<String, BackupCollectionRequest.Component> components, Map<BackupMember, Path> material)
            throws Exception {
        for (String id : request.plan().startOrder()) {
            interaction.checkCancelled();
            interaction.collecting(id);
            var component = components.get(id);
            collectArtifact(request, component, session, storage, material, RemoteBackupArtifactKind.RELEASE_TREE,
                    "release", "releases/" + id + ".pax", BackupMemberKind.RELEASE);
            for (var binding : component.resources().fileBindings()) {
                interaction.checkCancelled();
                collectArtifact(request, component, session, storage, material, RemoteBackupArtifactKind.FILE_TREE,
                        binding.bindingId(), "data/" + id + "/files/" + binding.bindingId() + ".pax",
                        BackupMemberKind.PERSISTENT_CONTENT);
            }
            if (component.runtime() instanceof DeploymentRuntimeSpecification.Container) {
                interaction.checkCancelled();
                collectArtifact(request, component, session, storage, material, RemoteBackupArtifactKind.OCI_IMAGE,
                        "image", "runtime/" + id + ".oci", BackupMemberKind.RUNTIME);
            }
        }
    }

    /**
     * Rechecks original running components in dependency order, restarting only those whose stop was attempted and whose current stopped state is verified. Continues attempting other recoveries after one failure and then applies the caller's whole-application health gate.
     * <p>按依赖顺序复核原先运行的组件，仅重启已尝试停止且当前停止状态已验证的组件。单项失败后继续尝试其他恢复，随后执行调用方提供的整应用健康门。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param running running / 运行中
     * @param stopping stopping / 停止中
     * @return the first recovery failure with later failures suppressed, or null when every required check succeeds / 第一个恢复失败及其抑制的后续失败；所有必要检查成功时为 null
     */
    private Exception recover(BackupCollectionRequest request, DeploymentRemoteSession session,
            Map<String, BackupCollectionRequest.Component> components, List<String> running, Set<String> stopping) {
        Exception problem = null;
        for (String id : running) {
            var component = components.get(id);
            try {
                var state = session.observeDeployment(component.application(), component.runtime());
                if (stopping.contains(id) && state.ownershipVerified() && (state.runtimeState() == RuntimeState.STOPPED
                        || state.runtimeState() == RuntimeState.INSTALLED))
                    state = session.executeDeploymentLifecycle(component.application(), component.runtime(),
                            LifecycleAction.START);
                if (!state.ownershipVerified() || state.runtimeState() != RuntimeState.RUNNING
                        || !session.checkDeploymentHealth(component.application(), component.runtime(),
                                component.runtime().healthCheck()).healthy())
                    throw failure(request, BackupFailureType.COLLECTION_RECOVERY_FAILED,
                            "A running component did not recover", null);
            } catch (Exception recovery) {
                problem = accumulate(problem, recovery);
            }
        }
        if (running.stream().anyMatch(request.healthTriggers()::contains)) {
            var owner = components.get(request.applicationHealth().componentId());
            try {
                if (!session.checkDeploymentHealth(owner.application(), owner.runtime(),
                        request.applicationHealth().healthCheck()).healthy())
                    throw failure(request, BackupFailureType.COLLECTION_RECOVERY_FAILED,
                            "Application health did not recover", null);
            } catch (Exception recovery) {
                problem = accumulate(problem, recovery);
            }
        }
        return problem;
    }

    /**
     * Downloads one helper artifact into platform-owned storage, validates its archive format independently and compares its measured size and SHA-256 with remote evidence.
     * <p>将一个 helper 制品下载到平台持有的存储，独立验证归档格式，并将实测大小及 SHA-256 与远端证据比较。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param component component / 组件
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param storage storage / 存储
     * @param material material / 素材
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param resource resource / 资源
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param memberKind member kind / 成员种类
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private void collectArtifact(BackupCollectionRequest request, BackupCollectionRequest.Component component,
            DeploymentRemoteSession session, BackupCollectionMaterialPort storage, Map<BackupMember, Path> material,
            RemoteBackupArtifactKind kind, String resource, String name, BackupMemberKind memberKind)
            throws IOException, LinuxOperationException {
        var remote = session.backupArtifacts()
                .createBackupArtifact(new RemoteBackupArtifactRequest(request.operationId(),
                        request.plan().applicationId(), component.id(), component.application(),
                        component.releaseSha256(), kind, resource, request.maximumArtifactBytes()));
        Path path = storage.member(name);
        try (var output = storage.open(path)) {
            session.backupArtifacts().copyBackupArtifact(remote, output);
        }
        var evidence = kind == RemoteBackupArtifactKind.OCI_IMAGE
                ? validator.validateOci(path)
                : validator.validatePax(path);
        if (evidence.byteCount() != remote.byteCount() || !evidence.sha256().equals(remote.sha256()))
            throw failure(request, BackupFailureType.INTEGRITY_FAILED,
                    "Downloaded material differs from helper evidence", null);
        material.put(new BackupMember(name, evidence.byteCount(), evidence.sha256(), memberKind), path);
    }

    /**
     * Measures the downloaded database file's byte count and SHA-256 without modifying it.
     * <p>在不修改已下载数据库文件的情况下测量其字节数及 SHA-256。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return constructed or resolved backup member / 构造或解析得到的备份成员
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static BackupMember databaseEvidence(String name, Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1)
                digest.update(buffer, 0, count);
        }
        return new BackupMember(name, Files.size(path), HexFormat.of().formatHex(digest.digest()),
                BackupMemberKind.DATABASE);
    }

    /**
     * Builds manifest runtime facts from the observed Linux capabilities and reviewed component runtimes.
     * <p>根据 Linux 能力观测及已审阅组件运行规格构建清单运行事实。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param linux linux / Linux 操作
     * @return manifest runtime facts from the observed Linux capabilities and reviewed component runtimes / 根据 Linux 能力观测及已审阅组件运行规格构建清单运行事实
     */
    private static BackupRuntime runtime(BackupCollectionRequest request, ServerCapabilityFacts server,
            LinuxCapabilityFacts linux) {
        var capabilities = new TreeSet<String>();
        capabilities.add("managed-helper-v" + server.managedHelperProtocolVersion());
        if (linux.systemdAvailable())
            capabilities.add("systemd");
        if (linux.dockerOperational())
            capabilities.add("docker");
        if (linux.podmanOperational())
            capabilities.add("podman");
        long containers = request.components().stream()
                .filter(component -> component.runtime() instanceof DeploymentRuntimeSpecification.Container).count();
        return new BackupRuntime(linux.distro().name().toLowerCase(Locale.ROOT), linux.version(),
                containers == 0 ? "systemd" : containers == request.components().size() ? "container" : "mixed",
                "managed-helper-" + server.managedHelperProtocolVersion(), linux.architecture(),
                List.copyOf(capabilities));
    }

    /**
     * Keeps the original exception as primary and attaches a distinct later exception as suppressed.
     * <p>保留原始异常为主异常，并将不同的后续异常附加为抑制异常。
     *
     * @param original original / 原始
     * @param additional additional / 额外
     * @return the original exception with the additional cause, or the additional exception when no original exists / 附带后续原因的原始异常；原始异常不存在时返回后续异常
     */
    private static Exception accumulate(Exception original, Exception additional) {
        if (original == null)
            return additional;
        if (original != additional)
            original.addSuppressed(additional);
        return original;
    }

    /**
     * Creates a backup-owned failure bound to the UUID encoded by the remote backup operation token.
     * <p>创建备份模块持有的失败，并绑定远端备份操作令牌编码的 UUID。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a backup-owned failure bound to the UUID encoded by the remote backup operation token / 备份模块持有的失败，并绑定远端备份操作令牌编码的 UUID
     */
    private static BackupException failure(BackupCollectionRequest request, BackupFailureType type, String diagnostic,
            Throwable cause) {
        String hex = request.operationId().substring("backup-".length());
        var identity = OperationIdentity.from(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
        return new BackupException(FailureDescriptor.create(type, identity, diagnostic), cause);
    }
}
