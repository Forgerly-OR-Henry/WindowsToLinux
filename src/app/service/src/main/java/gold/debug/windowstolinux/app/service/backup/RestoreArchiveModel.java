package gold.debug.windowstolinux.app.service.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupArtifact;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.format.BackupConfigurationCodec;
import gold.debug.windowstolinux.shared.backup.format.BackupConfigurationDocument;
import gold.debug.windowstolinux.shared.backup.manifest.BackupComponent;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMemberKind;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;

/**
 * Locally decoded exact schema-v5 state used before any target mutation. / 任何目标修改前在本地解码的精确 schema-v5 状态。
 *
 * @param activation activation / 激活
 * @param configurations configurations / 配置集合
 * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
 */
record RestoreArchiveModel(PreparedBackupActivation activation, Map<String, BackupConfigurationDocument> configurations,
        Optional<DatabaseMaterial> database) {
    /**
     * Reconstructs exact component runtime, resource and database material from a validated backup archive.
     * <p>从已验证备份归档重建精确组件运行规格、资源及数据库素材。
     *
     * @param activation activation / 激活
     * @return constructed or resolved restore archive model / 构造或解析得到的恢复归档模型
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    static RestoreArchiveModel load(PreparedBackupActivation activation) throws IOException {
        BackupConfigurationCodec codec = new BackupConfigurationCodec();
        LinkedHashMap<String, BackupConfigurationDocument> configurations = new LinkedHashMap<>();
        List<DatabaseOwner> databases = new ArrayList<>();
        for (BackupComponent component : activation.validation().manifest().inventory().components()) {
            BackupMember member = exactMember(activation, component.configurationSnapshotPath(),
                    BackupMemberKind.CONFIGURATION);
            BackupConfigurationDocument document = codec.readActivation(readMember(activation, member));
            var policy = document.runtimeConfiguration().identityPolicy();
            if (policy == gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode.LEGACY_UNSPECIFIED) {
                throw new IOException(
                        "Historical backup has no runtime identity policy; redeploy with controlled migration and create a new backup before automatic activation");
            }
            boolean container = component.runtime()
                    .projectType() == gold.debug.windowstolinux.shared.model.project.DeploymentProjectType.DOCKERFILE_CONTAINER;
            if (container != (policy == gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode.CONTAINER_NON_ROOT)) {
                throw new IOException("Backup runtime identity policy differs from the execution backend");
            }
            if (container)
                exactMember(activation, "runtime/" + component.componentId() + ".oci", BackupMemberKind.RUNTIME);
            if (!document.configuration().applicationId().equals(component.managedApplicationId()) || !document
                    .runtimeConfiguration().healthCheck().equals(component.runtime().healthCheck().toHealthCheck())) {
                throw new IOException("backup component configuration identity or runtime contract differs");
            }
            for (var file : document.resourceBindings().fileBindings()) {
                exactMember(activation, "data/" + component.componentId() + "/files/" + file.bindingId() + ".pax",
                        BackupMemberKind.PERSISTENT_CONTENT);
            }
            document.resourceBindings().databaseBindings().orElseThrow()
                    .forEach(binding -> databases.add(new DatabaseOwner(component, binding)));
            configurations.put(component.componentId(), document);
        }
        Optional<DatabaseMaterial> database = database(activation, databases);
        return new RestoreArchiveModel(activation, Map.copyOf(configurations), database);
    }

    /**
     * Matches the manifest database type to its reviewed owner and exact archive material.
     * <p>将清单数据库类型与已审阅所有者及精确归档素材匹配。
     *
     * @param activation activation / 激活
     * @param databases databases / 数据库集合
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static Optional<DatabaseMaterial> database(PreparedBackupActivation activation,
            List<DatabaseOwner> databases) throws IOException {
        BackupDatabaseType type = activation.validation().manifest().inventory().database().type();
        List<BackupMember> members = activation.validation().manifest().members().stream()
                .filter(member -> member.kind() == BackupMemberKind.DATABASE).toList();
        if (type == BackupDatabaseType.NONE) {
            if (!databases.isEmpty() || !members.isEmpty()) {
                throw new IOException("database-free manifest contains database bindings or material");
            }
            return Optional.empty();
        }
        if (databases.size() != 1 || members.size() != 1) {
            throw new IOException("automatic restore requires exactly one reviewed database binding and artifact");
        }
        DatabaseOwner owner = databases.getFirst();
        BackupMember member = members.getFirst();
        if (!member.path().equals("database/" + owner.binding().databaseId() + ".dump")
                || BackupDatabaseProfileMapper.type(owner.binding().connection().engine()) != type
                || owner.binding().connection() instanceof ManagedDatabaseConnection.Server server
                        && !owner.component().secretReferences().orElseThrow().contains(server.passwordReference())) {
            throw new IOException("database artifact, binding, type, or secret identity differs from the manifest");
        }
        DatabaseBackupArtifact artifact = new DatabaseBackupArtifact("db-" + member.sha256().substring(0, 32),
                member.size(), member.sha256(), activation.validation().manifest().inventory().database(),
                List.of("database artifact identity reconstructed from the validated archive member"));
        DatabaseRestoreRequest request = new DatabaseRestoreRequest(activation.validation().manifest().applicationId(),
                owner.component().managedApplicationId(),
                activation.localCandidate().inspection().applicationId() + "-"
                        + activation.validation().archiveSha256().substring(0, 16),
                BackupDatabaseProfileMapper.profile(owner.binding()), artifact);
        return Optional.of(new DatabaseMaterial(request,
                activation.restoreCandidate().root().resolve(member.path()).normalize(), artifact));
    }

    /**
     * Requires exactly one archive member with the requested path and kind.
     * <p>要求恰好存在一个路径及类型均匹配的归档成员。
     *
     * @param activation activation / 激活
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @return constructed or resolved backup member / 构造或解析得到的备份成员
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static BackupMember exactMember(PreparedBackupActivation activation, String path, BackupMemberKind kind)
            throws IOException {
        List<BackupMember> matches = activation.validation().manifest().members().stream()
                .filter(member -> member.path().equals(path) && member.kind() == kind).toList();
        if (matches.size() != 1)
            throw new IOException("backup lacks one exact required member: " + path);
        return matches.getFirst();
    }

    /**
     * Reads member.
     * <p>读取成员。
     *
     * @param activation activation / 激活
     * @param member member / 成员
     * @return member / 成员
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static byte[] readMember(PreparedBackupActivation activation, BackupMember member) throws IOException {
        Path path = activation.restoreCandidate().root().resolve(member.path()).normalize();
        if (!path.startsWith(activation.restoreCandidate().root())
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != member.size()
                || member.size() > 4L * 1024 * 1024) {
            throw new IOException("extracted backup member changed before activation decoding");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != member.size())
            throw new IOException("extracted backup member read was incomplete");
        if (!sha256(bytes).equals(member.sha256())) {
            throw new IOException("extracted backup member changed after archive validation");
        }
        return bytes;
    }

    /**
     * Computes the SHA-256 content identity used for independent integrity checks.
     * <p>计算独立完整性检查使用的 SHA-256 内容身份。
     *
     * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
     * @return computed SHA-256 content digest / 已计算的 SHA-256 内容摘要
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static String sha256(byte[] bytes) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Pairs the selected database backup metadata with its verified local content.
     * <p>将所选数据库备份元数据与其已验证本地内容配对。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param localArtifact local artifact / 本地制品
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     */
    record DatabaseMaterial(DatabaseRestoreRequest request, Path localArtifact, DatabaseBackupArtifact artifact) {
    }
    /**
     * Identifies the component and resource binding that own the database backup.
     * <p>标识数据库备份所属的组件及资源绑定。
     *
     * @param component component / 组件
     * @param binding binding / 绑定
     */
    private record DatabaseOwner(BackupComponent component, ManagedDatabaseBinding binding) {
    }
}
