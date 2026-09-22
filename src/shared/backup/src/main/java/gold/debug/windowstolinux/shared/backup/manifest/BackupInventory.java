package gold.debug.windowstolinux.shared.backup.manifest;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.deployment.ReleaseSetDigest;

/**
 * Complete structured inventory needed to recreate one managed application. / 重建一个受管应用所需的完整结构化清单。
 *
 * @param releaseManifests release manifests / 发布清单集合
 * @param configurationSnapshots configuration snapshots / 配置快照集合
 * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
 * @param persistentFiles persistent files / 持久化文件集合
 * @param persistentVolumes persistent volumes / 持久化卷集合
 * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
 * @param identity identity / 身份
 * @param serviceDefinitions service definitions / 服务定义集合
 * @param components reviewed components in the application graph / 应用图中的已审阅组件
 * @param applicationHealthComponentId application health component id / 应用健康组件标识
 * @param applicationHealthCheck independently reviewed whole-application probe / 独立审阅的整应用探测
 * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
 * @param recoveryRequirements recovery requirements / 恢复要求集合
 * @param legacySecretReferences legacy secret references / 历史秘密引用集合
 */
public record BackupInventory(List<String> releaseManifests, List<String> configurationSnapshots,
        List<SecretReference> secretReferences, List<String> persistentFiles, List<String> persistentVolumes,
        BackupDatabase database, BackupIdentity identity, List<String> serviceDefinitions,
        List<BackupComponent> components, String applicationHealthComponentId, BackupHealthCheck applicationHealthCheck,
        BackupRuntime runtime, List<String> recoveryRequirements, List<String> legacySecretReferences) {
    /**
     * SECRET ORDER.
     * <p>秘密顺序。
     */
    private static final Comparator<SecretReference> SECRET_ORDER = Comparator.comparing(SecretReference::identifier)
            .thenComparingLong(SecretReference::revision);

    /**
     * Creates one schema-v5 inventory with exact application secret references. / 创建带精确应用秘密引用的 schema v5 清单。
     *
     * @param releaseManifests release manifests / 发布清单集合
     * @param configurationSnapshots configuration snapshots / 配置快照集合
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param persistentFiles persistent files / 持久化文件集合
     * @param persistentVolumes persistent volumes / 持久化卷集合
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param identity identity / 身份
     * @param serviceDefinitions service definitions / 服务定义集合
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param applicationHealthComponentId application health component id / 应用健康组件标识
     * @param applicationHealthCheck independently reviewed whole-application probe / 独立审阅的整应用探测
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param recoveryRequirements recovery requirements / 恢复要求集合
     */
    public BackupInventory(List<String> releaseManifests, List<String> configurationSnapshots,
            List<SecretReference> secretReferences, List<String> persistentFiles, List<String> persistentVolumes,
            BackupDatabase database, BackupIdentity identity, List<String> serviceDefinitions,
            List<BackupComponent> components, String applicationHealthComponentId,
            BackupHealthCheck applicationHealthCheck, BackupRuntime runtime, List<String> recoveryRequirements) {
        this(releaseManifests, configurationSnapshots, secretReferences, persistentFiles, persistentVolumes, database,
                identity, serviceDefinitions, components, applicationHealthComponentId, applicationHealthCheck, runtime,
                recoveryRequirements, List.of());
    }

    /**
     * Freezes a complete, bounded and duplicate-free inventory. / 冻结完整、有界且无重复的清单。
     *
     * @param releaseManifests release manifests / 发布清单集合
     * @param configurationSnapshots configuration snapshots / 配置快照集合
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param persistentFiles persistent files / 持久化文件集合
     * @param persistentVolumes persistent volumes / 持久化卷集合
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param identity identity / 身份
     * @param serviceDefinitions service definitions / 服务定义集合
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param applicationHealthComponentId application health component id / 应用健康组件标识
     * @param applicationHealthCheck independently reviewed whole-application probe / 独立审阅的整应用探测
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param recoveryRequirements recovery requirements / 恢复要求集合
     * @param legacySecretReferences legacy secret references / 历史秘密引用集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupInventory {
        releaseManifests = BackupManifestRules.distinctTexts(releaseManifests, "releaseManifests", 256, 512);
        configurationSnapshots = BackupManifestRules.distinctTexts(configurationSnapshots, "configurationSnapshots",
                256, 512);
        secretReferences = canonicalSecrets(secretReferences);
        persistentFiles = BackupManifestRules.distinctTexts(persistentFiles, "persistentFiles", 4096, 1024);
        persistentVolumes = BackupManifestRules.distinctTexts(persistentVolumes, "persistentVolumes", 1024, 1024);
        database = Objects.requireNonNull(database, "database");
        identity = Objects.requireNonNull(identity, "identity");
        serviceDefinitions = BackupManifestRules.distinctTexts(serviceDefinitions, "serviceDefinitions", 256, 1024);
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        if (components.isEmpty() || components.size() > 256) {
            throw new IllegalArgumentException("components must contain one to 256 reviewed values");
        }
        applicationHealthComponentId = Objects
                .requireNonNull(applicationHealthComponentId, "applicationHealthComponentId").trim();
        if (!applicationHealthComponentId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("applicationHealthComponentId is invalid");
        }
        applicationHealthCheck = Objects.requireNonNull(applicationHealthCheck, "applicationHealthCheck");
        runtime = Objects.requireNonNull(runtime, "runtime");
        recoveryRequirements = BackupManifestRules.distinctTexts(recoveryRequirements, "recoveryRequirements", 128,
                512);
        legacySecretReferences = BackupManifestRules.distinctTexts(legacySecretReferences, "legacySecretReferences",
                256, 256);
        if (releaseManifests.isEmpty()) {
            throw new IllegalArgumentException("at least one release manifest is required");
        }
        if (configurationSnapshots.isEmpty()) {
            throw new IllegalArgumentException("at least one configuration snapshot is required");
        }
        if (serviceDefinitions.isEmpty()) {
            throw new IllegalArgumentException("at least one service or container definition is required");
        }
        validateComponents(releaseManifests, configurationSnapshots, serviceDefinitions, components,
                applicationHealthComponentId);
        validateVersionBindings(identity, components, secretReferences, legacySecretReferences);
    }

    /**
     * Computes the canonical schema-v5 release-set digest in dependency order. / 按依赖顺序计算规范 schema v5 发布集合摘要。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @return the canonical schema-v5 release-set digest in dependency order / 按依赖顺序计算规范 schema v5 发布集合摘要
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static String computeReleaseSetSha256(List<BackupComponent> components) {
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        return ReleaseSetDigest
                .sha256(components
                        .stream().map(
                                component -> new ReleaseSetDigest.ComponentRelease(component.componentId(),
                                        component.releaseSha256()
                                                .orElseThrow(() -> new IllegalArgumentException(
                                                        "release-set digest requires exact component releases"))))
                        .toList());
    }

    /**
     * Validates reviewed components in the application graph and rejects inputs outside the declared constraints.
     * <p>校验应用图中的已审阅组件并拒绝超出已声明约束的输入。
     *
     * @param releaseManifests release manifests / 发布清单集合
     * @param configurationSnapshots configuration snapshots / 配置快照集合
     * @param serviceDefinitions service definitions / 服务定义集合
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param applicationHealthComponentId application health component id / 应用健康组件标识
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void validateComponents(List<String> releaseManifests, List<String> configurationSnapshots,
            List<String> serviceDefinitions, List<BackupComponent> components, String applicationHealthComponentId) {
        Set<String> componentIds = new LinkedHashSet<>();
        Set<String> managedIds = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (BackupComponent component : components) {
            if (!componentIds.add(component.componentId()) || !managedIds.add(component.managedApplicationId())) {
                throw new IllegalArgumentException("component and managed application identities must be unique");
            }
            if (!seen.containsAll(component.dependsOn())) {
                throw new IllegalArgumentException("components must be stored in dependency-first order");
            }
            seen.add(component.componentId());
        }
        if (!componentIds.contains(applicationHealthComponentId)) {
            throw new IllegalArgumentException("application health must be owned by one declared component");
        }
        Set<String> componentReleases = components.stream().map(BackupComponent::releaseManifestPath)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> componentConfigurations = components.stream().map(BackupComponent::configurationSnapshotPath)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> componentDefinitions = components.stream().map(BackupComponent::serviceDefinitionPath)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!componentReleases.equals(new LinkedHashSet<>(releaseManifests))
                || !componentConfigurations.equals(new LinkedHashSet<>(configurationSnapshots))
                || !componentDefinitions.equals(new LinkedHashSet<>(serviceDefinitions))) {
            throw new IllegalArgumentException(
                    "component archive references must exactly cover the inventory definitions");
        }
    }

    /**
     * Validates version bindings and rejects inputs outside the declared constraints.
     * <p>校验版本绑定集合并拒绝超出已声明约束的输入。
     *
     * @param identity identity / 身份
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param legacySecretReferences legacy secret references / 历史秘密引用集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void validateVersionBindings(BackupIdentity identity, List<BackupComponent> components,
            List<SecretReference> secretReferences, List<String> legacySecretReferences) {
        boolean exact = components.stream().allMatch(BackupComponent::hasExactActivationBindings);
        boolean legacy = components.stream().noneMatch(BackupComponent::hasExactActivationBindings);
        if (!exact && !legacy) {
            throw new IllegalArgumentException("legacy and exact component bindings must not be mixed");
        }
        if (exact) {
            if (identity.releaseSetSha256().isEmpty() || identity.legacyReleaseIdentity().isPresent()
                    || !legacySecretReferences.isEmpty()) {
                throw new IllegalArgumentException(
                        "schema-v5 inventory requires only exact release and secret bindings");
            }
            String expectedReleaseSet = computeReleaseSetSha256(components);
            if (!identity.releaseSetSha256().orElseThrow().equals(expectedReleaseSet)) {
                throw new IllegalArgumentException("releaseSetSha256 differs from the dependency-ordered components");
            }
            Set<SecretReference> componentUnion = new LinkedHashSet<>();
            components.forEach(component -> componentUnion.addAll(component.secretReferences().orElseThrow()));
            if (!componentUnion.equals(new LinkedHashSet<>(secretReferences))) {
                throw new IllegalArgumentException(
                        "application secretReferences must equal the component reference union");
            }
        } else if (identity.legacyReleaseIdentity().isEmpty() || identity.releaseSetSha256().isPresent()
                || !secretReferences.isEmpty()) {
            throw new IllegalArgumentException(
                    "schema-v3 inventory must preserve only its legacy release and secret references");
        }
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
