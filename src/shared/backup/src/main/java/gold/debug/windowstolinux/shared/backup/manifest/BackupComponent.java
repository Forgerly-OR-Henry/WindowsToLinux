package gold.debug.windowstolinux.shared.backup.manifest;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** One ordered managed component with exact archived definitions and reviewed runtime. / 带精确归档定义和经审阅运行时的单个有序受管组件。 */
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
    private static final Comparator<SecretReference> SECRET_ORDER = Comparator
            .comparing(SecretReference::identifier).thenComparingLong(SecretReference::revision);

    /** Creates one schema-v5 component with exact release and secret bindings. / 创建带精确发布及秘密绑定的 schema v5 组件。 */
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

    /** Validates component identity, archive references, release and secret ownership. / 校验组件身份、归档引用、发布及秘密归属。 */
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

    /** Returns whether this component carries schema-v5 activation bindings. / 返回组件是否携带 schema v5 激活绑定。 */
    public boolean hasExactActivationBindings() {
        return releaseSha256.isPresent();
    }

    private static String managedId(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " must be a bounded managed identifier");
        }
        return value;
    }

    private static String memberPath(String value, String prefix, String field) {
        value = BackupManifestRules.archivePath(Objects.requireNonNull(value, field));
        if (!value.startsWith(prefix)) {
            throw new IllegalArgumentException(field + " must stay in " + prefix);
        }
        return value;
    }

    private static String canonicalSha256(String value, String field) {
        value = Objects.requireNonNull(value, field).trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be canonical SHA-256");
        }
        return value;
    }

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
