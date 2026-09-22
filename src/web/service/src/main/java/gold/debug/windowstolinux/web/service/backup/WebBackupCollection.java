package gold.debug.windowstolinux.web.service.backup;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.shared.backup.contract.spi.*;
import gold.debug.windowstolinux.shared.backup.contract.validation.*;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretCryptoService;
import gold.debug.windowstolinux.shared.backup.extension.adapter.LinuxDatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.extension.registry.DatabaseAdapterRegistry;
import gold.debug.windowstolinux.shared.backup.format.*;
import gold.debug.windowstolinux.shared.backup.manifest.*;
import gold.debug.windowstolinux.shared.config.resource.*;
import gold.debug.windowstolinux.shared.config.secretref.*;
import gold.debug.windowstolinux.shared.linux.protocol.backup.*;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.ManagedHelperProtocolVersion;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;

/**
 * Adapts shared backup collection to Web quotas, credentials, task interaction and archive persistence.
 * <p>将共享备份采集适配到 Web 配额、凭据、任务交互及归档持久化。
 */
public final class WebBackupCollection {
    /**
     * Quota.
     * <p>配额。
     */
    private final gold.debug.windowstolinux.web.file.quota.UploadQuota quota;

    /**
     * Minimum free disk space in bytes.
     * <p>最小磁盘剩余空间，单位为字节。
     */
    private final long minimumFreeBytes;
    /**
     * Binds the supplied dependencies and state for web backup collection.
     * <p>为Web备份采集绑定传入的依赖及状态。
     *
     * @param quota quota / 配额
     * @param minimumFreeBytes minimum free disk space in bytes / 最小磁盘剩余空间，单位为字节
     */
    public WebBackupCollection(gold.debug.windowstolinux.web.file.quota.UploadQuota quota, long minimumFreeBytes) {
        this.quota = quota;
        this.minimumFreeBytes = minimumFreeBytes;
    }
    /**
     * Bound backup archive policy collaborator for explicit validation and resource-bound policy.
     * <p>处理显式校验及资源边界策略的备份归档策略协作对象。
     */
    private final BackupArchivePolicy policy = BackupArchivePolicy.defaults();

    /**
     * Bytes already written against the current backup quota.
     * <p>已计入当前备份配额的写入字节数。
     */
    private long written;
    /**
     * Adapts the persisted Web graph, quota-limited material output and task interaction to shared collection, then packages the verified members in the existing backup format.
     * <p>将持久化 Web 应用图、配额受限素材输出及任务交互适配到共享采集，随后按既有备份格式打包已验证成员。
     *
     * @param graph graph / 图
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved backup archive validation / 构造或解析得到的备份归档校验
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public BackupArchiveValidation create(ManagedWebGraph graph, DeploymentRemoteSession session, Path directory,
            Path output, char[] password, List<ResolvedSecretRevision> secrets, TaskInteraction interaction)
            throws Exception {
        return create(graph, session, directory, output, password, secrets, interaction, null);
    }

    /**
     * Adapts the persisted Web graph, quota-limited material output and task interaction to shared collection, then packages the verified members in the existing backup format.
     * <p>将持久化 Web 应用图、配额受限素材输出及任务交互适配到共享采集，随后按既有备份格式打包已验证成员。
     *
     * @param graph graph / 图
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param heldMaintenance held maintenance / 已持有维护
     * @return constructed or resolved backup archive validation / 构造或解析得到的备份归档校验
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public BackupArchiveValidation create(ManagedWebGraph graph, DeploymentRemoteSession session, Path directory,
            Path output, char[] password, List<ResolvedSecretRevision> secrets, TaskInteraction interaction,
            String heldMaintenance) throws Exception {
        var bindings = databaseBindings(graph);
        var components = new ArrayList<gold.debug.windowstolinux.shared.backup.contract.definition.BackupCollectionRequest.Component>();
        for (var component : graph.components())
            components.add(
                    new gold.debug.windowstolinux.shared.backup.contract.definition.BackupCollectionRequest.Component(
                            component.id(), component.application(), component.runtime(), component.releaseIdentity(),
                            component.configuration().resourceBindings()));
        var request = new gold.debug.windowstolinux.shared.backup.contract.definition.BackupCollectionRequest(
                graph.plan(), components,
                heldMaintenance == null ? "backup-" + UUID.randomUUID().toString().replace("-", "") : heldMaintenance,
                heldMaintenance == null,
                new gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate(graph.healthOwner(),
                        graph.components().stream().filter(component -> component.id().equals(graph.healthOwner()))
                                .findFirst().orElseThrow().runtime().healthCheck()),
                Set.of(graph.healthOwner()),
                bindings.entrySet().stream().findFirst().map(
                        entry -> new gold.debug.windowstolinux.shared.backup.contract.definition.BackupCollectionRequest.Database(
                                entry.getKey().id(), entry.getValue().databaseId(), profile(entry.getValue()))),
                quota.fileBytes());
        gold.debug.windowstolinux.shared.backup.contract.definition.BackupCollectionResult collected;
        try {
            collected = new gold.debug.windowstolinux.shared.backup.execution.collection.BackupCollectionService(policy)
                    .collect(request, session, new BackupCollectionMaterialPort() {
                        /**
                         * Resolves a canonical archive-member path within the owning storage boundary.
                         * <p>在所属存储边界内解析规范归档成员路径。
                         *
                         * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
                         * @return a canonical archive-member path within the owning storage boundary / 在所属存储边界内解析规范归档成员路径
                         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
                         */
                        @Override
                        public Path member(String name) throws IOException {
                            return WebBackupCollection.member(directory, name);
                        }

                        /**
                         * Opens output stream.
                         * <p>打开输出流。
                         *
                         * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
                         * @return constructed or resolved output stream / 构造或解析得到的输出流
                         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
                         */
                        @Override
                        public OutputStream open(Path path) throws IOException {
                            return bounded(path);
                        }
                    }, new BackupCollectionInteraction() {
                        /**
                         * Checks cancelled.
                         * <p>检查已取消。
                         *
                         * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
                         */
                        @Override
                        public void checkCancelled() throws InterruptedException {
                            interaction.checkCancelled();
                        }

                        /**
                         * Notifies the caller that collection has reached the selected component.
                         * <p>通知调用方采集已到达所选组件。
                         *
                         * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
                         * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
                         */
                        @Override
                        public void collecting(String id) throws Exception {
                            interaction.progress("BACKUP_COLLECTING", WebJsonCodec.object().put("component", id));
                        }
                    });
        } catch (BackupException failure) {
            if (failure.failure().definition() == BackupFailureType.COLLECTION_RECOVERY_FAILED)
                interaction.completion(OperationCompletionState.REVALIDATION_REQUIRED);
            throw failure;
        }
        return packageArchive(graph, directory, output, password, secrets, new LinkedHashMap<>(collected.materials()),
                collected);
    }

    /**
     * Checks database bindings syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查数据库绑定集合语法及边界。
     *
     * @param graph graph / 图
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private Map<ManagedWebComponent, ManagedDatabaseBinding> databaseBindings(ManagedWebGraph graph) throws Exception {
        var bindings = new LinkedHashMap<ManagedWebComponent, ManagedDatabaseBinding>();
        for (var component : graph.components()) {
            if (!component.releaseIdentity().matches("[a-f0-9]{64}"))
                throw new IllegalStateException("Exact successful release is required");
            for (var binding : component.configuration().resourceBindings().databaseBindings().orElseThrow()) {
                if (!bindings.isEmpty())
                    throw new IllegalArgumentException("Complete backup supports at most one database");
                profile(binding);
                bindings.put(component, binding);
            }
        }
        return bindings;
    }

    /**
     * Adds persisted configuration, runtime definitions and authenticated encrypted secrets to verified remote members, writes the bounded archive and independently validates it.
     * <p>向已验证远端成员加入持久化配置、运行定义及认证加密秘密，写入有界归档并独立验证。
     *
     * @param graph graph / 图
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param material material / 素材
     * @param collected collected / 已采集
     * @return constructed or resolved backup archive validation / 构造或解析得到的备份归档校验
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private BackupArchiveValidation packageArchive(ManagedWebGraph graph, Path directory, Path output, char[] password,
            List<ResolvedSecretRevision> secrets, Map<BackupMember, Path> material,
            gold.debug.windowstolinux.shared.backup.contract.definition.BackupCollectionResult collected)
            throws Exception {
        var components = new LinkedHashMap<String, ManagedWebComponent>();
        graph.components().forEach(component -> components.put(component.id(), component));
        for (var component : graph.components()) {
            write(directory, material, "config/" + component.id() + ".bin", BackupMemberKind.CONFIGURATION,
                    Base64.getDecoder().decode(component.activation()));
            write(directory, material, "runtime/" + component.id() + ".bin", BackupMemberKind.RUNTIME,
                    Base64.getDecoder().decode(component.runtimeDefinition()));
        }
        if (!secrets.isEmpty())
            write(directory, material, "secrets.enc", BackupMemberKind.ENCRYPTED_SECRETS,
                    new BackupSecretCryptoService().encryptRevisions(password, secrets));
        var entries = graph.components().stream()
                .map(component -> new BackupComponent(component.id(), component.application().id(),
                        component.application().ownershipManifestSha256(), "releases/" + component.id() + ".pax",
                        "config/" + component.id() + ".bin", "runtime/" + component.id() + ".bin",
                        component.dependencies(), BackupComponentRuntime.from(component.runtime()),
                        component.releaseIdentity(), component.secrets()))
                .toList();
        var references = entries.stream().flatMap(component -> component.secretReferences().orElseThrow().stream())
                .distinct()
                .sorted(Comparator.comparing(SecretReference::identifier).thenComparingLong(SecretReference::revision))
                .toList();
        var runtime = collected.runtime();
        var inventory = new BackupInventory(entries.stream().map(BackupComponent::releaseManifestPath).toList(),
                entries.stream().map(BackupComponent::configurationSnapshotPath).toList(), references,
                material.keySet().stream().filter(item -> item.path().contains("/files/")).map(BackupMember::path)
                        .toList(),
                material.keySet().stream().filter(item -> item.path().contains("/volumes/")).map(BackupMember::path)
                        .toList(),
                collected.database(),
                new BackupIdentity(graph.applicationId(), graph.components().getFirst().application().server().id(),
                        "/opt/windowstolinux/apps/" + graph.applicationId(),
                        BackupInventory.computeReleaseSetSha256(entries)),
                entries.stream().map(BackupComponent::serviceDefinitionPath).toList(), entries, graph.healthOwner(),
                BackupHealthCheck.from(components.get(graph.healthOwner()).runtime().healthCheck()), runtime,
                List.of("restore requires managed helper protocol " + ManagedHelperProtocolVersion.CURRENT));
        if (material.keySet().stream().mapToLong(BackupMember::size).sum() > quota.fileBytes())
            throw new IOException("Restore material quota exceeded");
        var manifest = BackupManifest.create(Instant.now(), graph.applicationId(), inventory,
                List.copyOf(material.keySet()));
        try (var stream = bounded(output)) {
            new BackupArchiveWriter(policy).write(manifest, material.entrySet().stream()
                    .map(item -> new BackupArchiveContent(item.getKey(), () -> Files.newInputStream(item.getValue())))
                    .toList(), stream);
        }
        return new BackupArchiveValidator(policy).validate(output);
    }

    /**
     * Builds database connection profile from the supplied profile inputs.
     * <p>根据所提供配置资料输入构建数据库连接配置资料。
     *
     * @param binding binding / 绑定
     * @return database connection profile from the supplied profile inputs / 根据所提供配置资料输入构建数据库连接配置资料
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static DatabaseConnectionProfile profile(ManagedDatabaseBinding binding) {
        var connection = binding.connection();
        if (connection instanceof ManagedDatabaseConnection.Sqlite sqlite)
            return new DatabaseConnectionProfile.Sqlite(binding.databaseId(), sqlite.location(), sqlite.fileName());
        if (!(connection instanceof ManagedDatabaseConnection.Server server)
                || server.engine() == ManagedDatabaseEngineType.REDIS)
            throw new IllegalArgumentException("Database backup requires PostgreSQL, MySQL or MariaDB");
        return new DatabaseConnectionProfile.Server(BackupDatabaseType.valueOf(server.engine().name()), server.host(),
                server.port(), server.database(), server.username(), server.passwordReference(), server.tlsRequired());
    }

    /**
     * Resolves a canonical archive-member path within the owning storage boundary.
     * <p>在所属存储边界内解析规范归档成员路径。
     *
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return a canonical archive-member path within the owning storage boundary / 在所属存储边界内解析规范归档成员路径
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static Path member(Path directory, String name) throws IOException {
        Path target = directory.resolve(name).normalize();
        if (!target.startsWith(directory))
            throw new IOException("Backup path escaped");
        Files.createDirectories(target.getParent());
        return target;
    }

    /**
     * Writes web backup collection.
     * <p>写入Web备份采集。
     *
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @param material material / 素材
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private void write(Path directory, Map<BackupMember, Path> material, String name, BackupMemberKind kind,
            byte[] bytes) throws Exception {
        try {
            Path target = member(directory, name);
            try (var output = bounded(target)) {
                output.write(bytes);
            }
            material.put(evidence(name, target, kind), target);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * Reads the complete local member to measure its byte count and SHA-256 digest.
     * <p>读取完整本地成员以测量字节数及 SHA-256 摘要。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @return the complete local member to measure its byte count and SHA-256 digest / 完整本地成员以测量字节数及 SHA-256 摘要
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private static BackupMember evidence(String name, Path path, BackupMemberKind kind) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1)
                digest.update(buffer, 0, count);
        }
        return new BackupMember(name, Files.size(path), HexFormat.of().formatHex(digest.digest()), kind);
    }

    /**
     * Rejects content exceeding the explicit size or count bound.
     * <p>拒绝超出显式大小或数量限制的内容。
     *
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @return constructed or resolved output stream / 构造或解析得到的输出流
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private OutputStream bounded(Path target) throws IOException {
        return new FilterOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            /**
             * Count.
             * <p>数量。
             */
            private long count;
            /**
             * Writes anonymous.
             * <p>写入匿名。
             *
             * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
             * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
             */
            @Override
            public void write(int value) throws IOException {
                check(1);
                out.write(value);
            }

            /**
             * Writes anonymous.
             * <p>写入匿名。
             *
             * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
             * @param offset offset / 偏移量
             * @param length length / 长度
             * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
             */
            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                check(length);
                out.write(bytes, offset, length);
            }

            /**
             * Checks anonymous.
             * <p>检查匿名。
             *
             * @param length length / 长度
             * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
             */
            private void check(int length) throws IOException {
                count = Math.addExact(count, length);
                written = Math.addExact(written, length);
                if (count > quota.fileBytes() || written > quota.projectBytes())
                    throw new IOException("Backup quota exceeded");
                if (count == length || count / (8L << 20) != (count - length) / (8L << 20))
                    if (Files.getFileStore(target).getUsableSpace() < minimumFreeBytes)
                        throw new IOException("Insufficient backup space");
            }
        };
    }
}
