package gold.debug.windowstolinux.shared.linux.protocol.restore;

import gold.debug.windowstolinux.shared.linux.protocol.RemoteDeploymentInputs;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** One exact staged component for bounded restore activation. / 用于有界恢复激活的一个精确暂存组件。 */
public record RemoteRestoreActivationComponent(
        String componentId,
        String managedApplicationId,
        String ownershipManifestSha256,
        String releaseSha256,
        String releaseArchivePath,
        List<String> persistentArchivePaths,
        Optional<String> ociArchivePath,
        List<String> dependsOn,
        DeploymentRuntimeSpecification runtime,
        RemoteDeploymentInputs inputs,
        List<RemoteRestorePortBinding> ports
) {
    /** Validates exact managed identities and candidate-relative members. / 校验精确受管身份及候选相对成员。 */
    public RemoteRestoreActivationComponent {
        componentId = id(componentId, "componentId");
        managedApplicationId = id(managedApplicationId, "managedApplicationId");
        ownershipManifestSha256 = digest(ownershipManifestSha256, "ownershipManifestSha256");
        releaseSha256 = digest(releaseSha256, "releaseSha256");
        releaseArchivePath = member(releaseArchivePath, "releases/");
        String persistentPrefix = "data/" + componentId + "/";
        persistentArchivePaths = List.copyOf(Objects.requireNonNull(persistentArchivePaths, "persistentArchivePaths"));
        if (persistentArchivePaths.size() > 256 || persistentArchivePaths.stream().anyMatch(value ->
                !member(value, persistentPrefix).equals(value))
                || persistentArchivePaths.stream().distinct().count() != persistentArchivePaths.size()) {
            throw new IllegalArgumentException("persistent restore archives are invalid");
        }
        ociArchivePath = Objects.requireNonNull(ociArchivePath, "ociArchivePath")
                .map(value -> member(value, "runtime/"));
        dependsOn = Objects.requireNonNull(dependsOn, "dependsOn").stream()
                .map(value -> id(value, "dependsOn")).toList();
        runtime = Objects.requireNonNull(runtime, "runtime");
        inputs = Objects.requireNonNull(inputs, "inputs");
        ports = List.copyOf(Objects.requireNonNull(ports, "ports"));
    }

    private static String id(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static String digest(String value, String field) {
        value = Objects.requireNonNull(value, field).trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static String member(String value, String prefix) {
        value = Objects.requireNonNull(value, "member").trim();
        if (!value.startsWith(prefix) || value.startsWith("/") || value.contains("\\") || value.contains("//")
                || java.util.Arrays.stream(value.split("/")).anyMatch(part -> part.isEmpty()
                || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("restore activation member path is invalid");
        }
        return value;
    }
}
