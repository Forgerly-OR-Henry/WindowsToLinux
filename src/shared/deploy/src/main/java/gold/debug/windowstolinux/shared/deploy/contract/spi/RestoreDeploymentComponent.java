package gold.debug.windowstolinux.shared.deploy.contract.spi;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** One dependency-ordered restored component ready for managed activation. / 准备进行受管激活的单个依赖有序恢复组件。 */
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
        DeploymentRuntimeSpecification runtime
) {
    private static final Comparator<SecretReference> SECRET_ORDER = Comparator
            .comparing(SecretReference::identifier).thenComparingLong(SecretReference::revision);

    /** Validates managed identities and fixed candidate-relative member paths. / 校验受管身份和固定候选相对成员路径。 */
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
    }

    private static String managedId(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " must be a bounded managed identifier");
        }
        return value;
    }

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
