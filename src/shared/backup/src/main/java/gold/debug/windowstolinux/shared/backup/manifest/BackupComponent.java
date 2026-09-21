package gold.debug.windowstolinux.shared.backup.manifest;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One ordered managed component with exact archived definitions and reviewed runtime. / 带精确归档定义和经审阅运行时的单个有序受管组件。
 *
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param managedApplicationId managed application id / 受管应用标识
 * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
 * @param releaseManifestPath release manifest path / 发布清单路径
 * @param configurationSnapshotPath configuration snapshot path / 配置快照路径
 * @param serviceDefinitionPath service definition path / 服务定义路径
 * @param dependsOn depends on / 依赖对应
 * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
 * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
 * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
 */
public record BackupComponent(
        String componentId,
        String managedApplicationId,
        String ownershipManifestSha256,
        String releaseManifestPath,
        String configurationSnapshotPath,
        String serviceDefinitionPath,
        List<String> dependsOn,
        BackupComponentRuntime runtime,
        Optional<String> releaseSha256,
        Optional<List<SecretReference>> secretReferences
) {
    /**
     * SECRET ORDER.
     * <p>秘密顺序。
     */
    private static final Comparator<SecretReference> SECRET_ORDER = Comparator
            .comparing(SecretReference::identifier).thenComparingLong(SecretReference::revision);

    /**
     * Creates one schema-v5 component with exact release and secret bindings. / 创建带精确发布及秘密绑定的 schema v5 组件。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param managedApplicationId managed application id / 受管应用标识
     * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
     * @param releaseManifestPath release manifest path / 发布清单路径
     * @param configurationSnapshotPath configuration snapshot path / 配置快照路径
     * @param serviceDefinitionPath service definition path / 服务定义路径
     * @param dependsOn depends on / 依赖对应
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     */
    public BackupComponent(
            String componentId,
            String managedApplicationId,
            String ownershipManifestSha256,
            String releaseManifestPath,
            String configurationSnapshotPath,
            String serviceDefinitionPath,
            List<String> dependsOn,
            BackupComponentRuntime runtime,
            String releaseSha256,
            List<SecretReference> secretReferences
    ) {
        this(componentId, managedApplicationId, ownershipManifestSha256, releaseManifestPath,
                configurationSnapshotPath, serviceDefinitionPath, dependsOn, runtime,
                Optional.of(releaseSha256), Optional.of(secretReferences));
    }

    /**
     * Validates component identity, archive references, release and secret ownership. / 校验组件身份、归档引用、发布及秘密归属。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param managedApplicationId managed application id / 受管应用标识
     * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
     * @param releaseManifestPath release manifest path / 发布清单路径
     * @param configurationSnapshotPath configuration snapshot path / 配置快照路径
     * @param serviceDefinitionPath service definition path / 服务定义路径
     * @param dependsOn depends on / 依赖对应
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupComponent {
        componentId = managedId(componentId, "componentId");
        managedApplicationId = managedId(managedApplicationId, "managedApplicationId");
        ownershipManifestSha256 = Objects.requireNonNull(ownershipManifestSha256, "ownershipManifestSha256")
                .trim().toLowerCase(Locale.ROOT);
        if (!ownershipManifestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ownershipManifestSha256 must be canonical SHA-256");
        }
        releaseManifestPath = memberPath(releaseManifestPath, "releases/", "releaseManifestPath");
        configurationSnapshotPath = memberPath(configurationSnapshotPath, "config/", "configurationSnapshotPath");
        serviceDefinitionPath = memberPath(serviceDefinitionPath, "runtime/", "serviceDefinitionPath");
        dependsOn = BackupManifestRules.distinctTexts(dependsOn, "dependsOn", 255, 63).stream()
                .map(value -> managedId(value, "dependsOn")).toList();
        if (dependsOn.contains(componentId)) {
            throw new IllegalArgumentException("a backup component cannot depend on itself");
        }
        runtime = Objects.requireNonNull(runtime, "runtime");
        releaseSha256 = Objects.requireNonNull(releaseSha256, "releaseSha256")
                .map(value -> canonicalSha256(value, "releaseSha256"));
        secretReferences = Objects.requireNonNull(secretReferences, "secretReferences")
                .map(BackupComponent::canonicalSecrets);
        if (releaseSha256.isPresent() != secretReferences.isPresent()) {
            throw new IllegalArgumentException("component release and secret bindings must be present together");
        }
    }

    /**
     * Builds backup component from the supplied legacy inputs.
     * <p>根据所提供历史输入构建备份组件。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param managedApplicationId managed application id / 受管应用标识
     * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
     * @param releaseManifestPath release manifest path / 发布清单路径
     * @param configurationSnapshotPath configuration snapshot path / 配置快照路径
     * @param serviceDefinitionPath service definition path / 服务定义路径
     * @param dependsOn depends on / 依赖对应
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return backup component from the supplied legacy inputs / 根据所提供历史输入构建备份组件
     */
    static BackupComponent legacy(
            String componentId,
            String managedApplicationId,
            String ownershipManifestSha256,
            String releaseManifestPath,
            String configurationSnapshotPath,
            String serviceDefinitionPath,
            List<String> dependsOn,
            BackupComponentRuntime runtime
    ) {
        return new BackupComponent(componentId, managedApplicationId, ownershipManifestSha256,
                releaseManifestPath, configurationSnapshotPath, serviceDefinitionPath, dependsOn, runtime,
                Optional.empty(), Optional.empty());
    }

    /**
     * Returns whether this component carries schema-v5 activation bindings. / 返回组件是否携带 schema v5 激活绑定。
     *
     * @return true when returns whether this component carries schema-v5 activation bindings, false otherwise / 返回组件是否携带 schema v5 激活绑定时为 true，否则为 false
     */
    public boolean hasExactActivationBindings() {
        return releaseSha256.isPresent();
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
     * Validates a canonical archive path and requires the component-specific member prefix.
     * <p>校验规范归档路径，并要求组件专用成员前缀。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param prefix prefix / 前缀
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return member path text / 成员路径文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String memberPath(String value, String prefix, String field) {
        value = BackupManifestRules.archivePath(Objects.requireNonNull(value, field));
        if (!value.startsWith(prefix)) {
            throw new IllegalArgumentException(field + " must stay in " + prefix);
        }
        return value;
    }

    /**
     * Validates and canonicalizes a SHA-256 identity digest.
     * <p>校验并规范化 SHA-256 身份摘要。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return canonical sha 256 text / 规范SHA256文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String canonicalSha256(String value, String field) {
        value = Objects.requireNonNull(value, field).trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be canonical SHA-256");
        }
        return value;
    }

    /**
     * Orders and validates immutable secret references for stable serialization.
     * <p>为稳定序列化排序并校验不可变秘密引用。
     *
     * @param references references / 引用集合
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static List<SecretReference> canonicalSecrets(List<SecretReference> references) {
        references = List.copyOf(Objects.requireNonNull(references, "secretReferences"));
        if (references.size() > 64 || references.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("secretReferences exceed their bounded exact set");
        }
        List<SecretReference> sorted = references.stream().sorted(SECRET_ORDER).toList();
        if (!references.equals(sorted) || references.stream().distinct().count() != references.size()) {
            throw new IllegalArgumentException("secretReferences must be canonical and unique by exact revision");
        }
        return references;
    }
}
