package gold.debug.windowstolinux.shared.backup.manifest;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** One ordered managed component with exact archived definitions and reviewed runtime. / 带精确归档定义和经审阅运行时的单个有序受管组件。 */
public record BackupComponent(
        String componentId,
        String managedApplicationId,
        String ownershipManifestSha256,
        String releaseManifestPath,
        String configurationSnapshotPath,
        String serviceDefinitionPath,
        List<String> dependsOn,
        BackupComponentRuntime runtime
) {
    /** Validates component identity, archive references and dependency identifiers. / 校验组件身份、归档引用和依赖标识。 */
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
}
