package gold.debug.windowstolinux.shared.deploy.contract.spi;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.deployment.ReleaseSetDigest;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Complete typed activation request for one already staged restore candidate. / 单个已暂存恢复候选的完整类型化激活请求。 */
public record RestoreDeploymentRequest(
        String applicationId,
        String targetServerId,
        String candidateId,
        String archiveSha256,
        String releaseSetSha256,
        List<SecretReference> secretReferences,
        String remoteCandidateRoot,
        String candidateToken,
        List<RestoreDeploymentComponent> components,
        String applicationHealthComponentId,
        HealthCheck applicationHealthCheck,
        boolean isolatedDatabase
) {
    private static final Comparator<SecretReference> SECRET_ORDER = Comparator
            .comparing(SecretReference::identifier).thenComparingLong(SecretReference::revision);

    /** Validates digest binding, dependency order and whole-application health ownership. / 校验摘要绑定、依赖顺序和整应用健康归属。 */
    public RestoreDeploymentRequest {
        applicationId = managedId(applicationId, "applicationId");
        targetServerId = managedId(targetServerId, "targetServerId");
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256").trim().toLowerCase(Locale.ROOT);
        if (!archiveSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("archiveSha256 must be canonical SHA-256");
        }
        releaseSetSha256 = Objects.requireNonNull(releaseSetSha256, "releaseSetSha256")
                .trim().toLowerCase(Locale.ROOT);
        if (!releaseSetSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("releaseSetSha256 must be canonical SHA-256");
        }
        secretReferences = List.copyOf(Objects.requireNonNull(secretReferences, "secretReferences"));
        if (secretReferences.size() > 64 || secretReferences.stream().anyMatch(Objects::isNull)
                || !secretReferences.equals(secretReferences.stream().sorted(SECRET_ORDER).toList())
                || secretReferences.stream().distinct().count() != secretReferences.size()) {
            throw new IllegalArgumentException("secretReferences must be canonical and unique by exact revision");
        }
        candidateId = Objects.requireNonNull(candidateId, "candidateId").trim();
        if (!candidateId.equals(applicationId + "-" + archiveSha256.substring(0, 16))) {
            throw new IllegalArgumentException("candidateId is not bound to the archive digest");
        }
        remoteCandidateRoot = Objects.requireNonNull(remoteCandidateRoot, "remoteCandidateRoot").trim();
        if (!remoteCandidateRoot.equals(
                "/var/lib/windowstolinux/work/" + candidateId + "/mutable/restore")) {
            throw new IllegalArgumentException("remoteCandidateRoot is outside the staged candidate");
        }
        candidateToken = Objects.requireNonNull(candidateToken, "candidateToken").trim();
        if (!candidateToken.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("candidateToken is invalid");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        if (components.isEmpty() || components.size() > 256) {
            throw new IllegalArgumentException("components must contain one to 256 reviewed values");
        }
        Set<String> seen = new LinkedHashSet<>();
        Set<String> managed = new HashSet<>();
        for (RestoreDeploymentComponent component : components) {
            if (!managed.add(component.managedApplicationId()) || !seen.containsAll(component.dependsOn())
                    || !seen.add(component.componentId())) {
                throw new IllegalArgumentException("restore components are duplicated or not dependency-first");
            }
        }
        String expectedReleaseSet = ReleaseSetDigest.sha256(components.stream().map(component ->
                new ReleaseSetDigest.ComponentRelease(component.componentId(), component.releaseSha256())).toList());
        if (!releaseSetSha256.equals(expectedReleaseSet)) {
            throw new IllegalArgumentException("releaseSetSha256 differs from the dependency-ordered components");
        }
        Set<SecretReference> componentSecrets = new LinkedHashSet<>();
        components.forEach(component -> componentSecrets.addAll(component.secretReferences()));
        if (!componentSecrets.equals(new LinkedHashSet<>(secretReferences))) {
            throw new IllegalArgumentException("application secretReferences must equal the component reference union");
        }
        applicationHealthComponentId = managedId(applicationHealthComponentId, "applicationHealthComponentId");
        if (!seen.contains(applicationHealthComponentId)) {
            throw new IllegalArgumentException("application health is not owned by a restored component");
        }
        applicationHealthCheck = Objects.requireNonNull(applicationHealthCheck, "applicationHealthCheck");
    }

    public RestoreDeploymentRequest(String applicationId,String targetServerId,String candidateId,String archiveSha256,
            String releaseSetSha256,List<SecretReference> secretReferences,String remoteCandidateRoot,String candidateToken,
            List<RestoreDeploymentComponent> components,String applicationHealthComponentId,HealthCheck applicationHealthCheck) {
        this(applicationId,targetServerId,candidateId,archiveSha256,releaseSetSha256,secretReferences,remoteCandidateRoot,candidateToken,
                components,applicationHealthComponentId,applicationHealthCheck,false);
    }

    private static String managedId(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " must be a bounded managed identifier");
        }
        return value;
    }
}
