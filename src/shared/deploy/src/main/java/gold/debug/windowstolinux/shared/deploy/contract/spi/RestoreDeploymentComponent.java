package gold.debug.windowstolinux.shared.deploy.contract.spi;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One dependency-ordered restored component ready for managed activation. / 准备进行受管激活的单个依赖有序恢复组件。
 *
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param managedApplicationId managed application id / 受管应用标识
 * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
 * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
 * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
 * @param releaseManifestPath release manifest path / 发布清单路径
 * @param configurationSnapshotPath configuration snapshot path / 配置快照路径
 * @param serviceDefinitionPath service definition path / 服务定义路径
 * @param dependsOn depends on / 依赖对应
 * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
 * @param inputManifest input manifest / 输入清单
 * @param persistentArchivePaths persistent archive paths / 持久化归档路径集合
 * @param ociArchivePath oci archive path / oci归档路径
 */
public record RestoreDeploymentComponent(
        String componentId,
        String managedApplicationId,
        String ownershipManifestSha256,
        String releaseSha256,
        List<SecretReference> secretReferences,
        String releaseManifestPath,
        String configurationSnapshotPath,
        String serviceDefinitionPath,
        List<String> dependsOn,
        DeploymentRuntimeSpecification runtime,
        Optional<DeploymentInputManifest> inputManifest,
        List<String> persistentArchivePaths,
        Optional<String> ociArchivePath
) {
    /**
     * SECRET ORDER.
     * <p>秘密顺序。
     */
    private static final Comparator<SecretReference> SECRET_ORDER = Comparator
            .comparing(SecretReference::identifier).thenComparingLong(SecretReference::revision);

    /**
     * Validates managed identities and fixed candidate-relative member paths. / 校验受管身份和固定候选相对成员路径。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param managedApplicationId managed application id / 受管应用标识
     * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
     * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param releaseManifestPath release manifest path / 发布清单路径
     * @param configurationSnapshotPath configuration snapshot path / 配置快照路径
     * @param serviceDefinitionPath service definition path / 服务定义路径
     * @param dependsOn depends on / 依赖对应
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param inputManifest input manifest / 输入清单
     * @param persistentArchivePaths persistent archive paths / 持久化归档路径集合
     * @param ociArchivePath oci archive path / oci归档路径
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RestoreDeploymentComponent {
        componentId = managedId(componentId, "componentId");
        managedApplicationId = managedId(managedApplicationId, "managedApplicationId");
        ownershipManifestSha256 = Objects.requireNonNull(ownershipManifestSha256, "ownershipManifestSha256")
                .trim().toLowerCase(Locale.ROOT);
        if (!ownershipManifestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ownershipManifestSha256 must be canonical SHA-256");
        }
        releaseSha256 = Objects.requireNonNull(releaseSha256, "releaseSha256")
                .trim().toLowerCase(Locale.ROOT);
        if (!releaseSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("releaseSha256 must be canonical SHA-256");
        }
        secretReferences = List.copyOf(Objects.requireNonNull(secretReferences, "secretReferences"));
        if (secretReferences.size() > 64 || secretReferences.stream().anyMatch(Objects::isNull)
                || !secretReferences.equals(secretReferences.stream().sorted(SECRET_ORDER).toList())
                || secretReferences.stream().distinct().count() != secretReferences.size()) {
            throw new IllegalArgumentException("secretReferences must be canonical and unique by exact revision");
        }
        releaseManifestPath = memberPath(releaseManifestPath, "releases/", "releaseManifestPath");
        configurationSnapshotPath = memberPath(configurationSnapshotPath, "config/", "configurationSnapshotPath");
        serviceDefinitionPath = memberPath(serviceDefinitionPath, "runtime/", "serviceDefinitionPath");
        dependsOn = Objects.requireNonNull(dependsOn, "dependsOn").stream()
                .map(value -> managedId(value, "dependsOn")).toList();
        if (dependsOn.size() > 255 || dependsOn.stream().distinct().count() != dependsOn.size()
                || dependsOn.contains(componentId)) {
            throw new IllegalArgumentException("component dependencies are invalid");
        }
        runtime = Objects.requireNonNull(runtime, "runtime");
        inputManifest = Objects.requireNonNull(inputManifest, "inputManifest");
        if (inputManifest.isPresent()) {
            DeploymentInputManifest inputs = inputManifest.orElseThrow();
            List<SecretReference> staged = inputs.secrets().stream().map(value -> value.reference())
                    .sorted(SECRET_ORDER).toList();
            if (!staged.equals(secretReferences)) {
                throw new IllegalArgumentException("staged deployment inputs differ from exact backup secret references");
            }
        }
        String persistentPrefix = "data/" + componentId + "/";
        persistentArchivePaths = List.copyOf(Objects.requireNonNull(persistentArchivePaths, "persistentArchivePaths"));
        if (persistentArchivePaths.size() > 256 || persistentArchivePaths.stream().anyMatch(path ->
                !memberPath(path, persistentPrefix, "persistentArchivePath").equals(path))
                || persistentArchivePaths.stream().distinct().count() != persistentArchivePaths.size()) {
            throw new IllegalArgumentException("persistentArchivePaths are invalid");
        }
        ociArchivePath = Objects.requireNonNull(ociArchivePath, "ociArchivePath");
        if (ociArchivePath.isPresent()) {
            String path = ociArchivePath.orElseThrow();
            if (!path.equals("runtime/" + componentId + ".oci")) {
                throw new IllegalArgumentException("ociArchivePath is invalid");
            }
        }
    }

    /**
     * Creates a schema-only component before short-lived deployment inputs are staged. / 在暂存短生命周期部署输入前创建仅含 schema 的组件。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param managedApplicationId managed application id / 受管应用标识
     * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
     * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param releaseManifestPath release manifest path / 发布清单路径
     * @param configurationSnapshotPath configuration snapshot path / 配置快照路径
     * @param serviceDefinitionPath service definition path / 服务定义路径
     * @param dependsOn depends on / 依赖对应
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     */
    public RestoreDeploymentComponent(
            String componentId,
            String managedApplicationId,
            String ownershipManifestSha256,
            String releaseSha256,
            List<SecretReference> secretReferences,
            String releaseManifestPath,
            String configurationSnapshotPath,
            String serviceDefinitionPath,
            List<String> dependsOn,
            DeploymentRuntimeSpecification runtime
    ) {
        this(componentId, managedApplicationId, ownershipManifestSha256, releaseSha256, secretReferences,
                releaseManifestPath, configurationSnapshotPath, serviceDefinitionPath, dependsOn, runtime,
                Optional.empty(), List.of(), Optional.empty());
    }

    /**
     * Validates a managed identifier before it reaches a remote resource boundary.
     * <p>在标识到达远端资源边界前验证受管标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return managed id text / 受管标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String managedId(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " must be a bounded managed identifier");
        }
        return value;
    }

    /**
     * Requires a normalized relative archive-member path beneath the specified prefix.
     * <p>要求归档成员路径为指定前缀下的规范相对路径。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param prefix prefix / 前缀
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return member path text / 成员路径文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String memberPath(String value, String prefix, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.startsWith(prefix) || value.startsWith("/") || value.indexOf('\\') >= 0
                || value.contains("//") || value.split("/").length < 2
                || java.util.Arrays.stream(value.split("/")).anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
            throw new IllegalArgumentException(field + " is not a fixed candidate member path");
        }
        return value;
    }
}
