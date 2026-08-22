package gold.debug.windowstolinux.shared.linux.protocol.restore;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Complete application-wide activation request bound to one staged archive. / 绑定到一个已暂存归档的完整整应用激活请求。 */
public record RemoteRestoreActivationRequest(
        String applicationId,
        String candidateId,
        String candidateToken,
        String archiveSha256,
        String remoteCandidateRoot,
        RemoteRestoreActivationMode mode,
        List<RemoteRestoreActivationComponent> components,
        String applicationHealthComponentId,
        HealthCheck applicationHealthCheck
) {
    /** Validates candidate identity, dependency order and port-mode closure. / 校验候选身份、依赖顺序及端口模式闭合。 */
    public RemoteRestoreActivationRequest {
        applicationId = id(applicationId, "applicationId");
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256").trim().toLowerCase(Locale.ROOT);
        if (!archiveSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("archiveSha256 is invalid");
        candidateId = Objects.requireNonNull(candidateId, "candidateId").trim();
        if (!candidateId.equals(applicationId + "-" + archiveSha256.substring(0, 16))) {
            throw new IllegalArgumentException("candidateId differs from archive identity");
        }
        candidateToken = Objects.requireNonNull(candidateToken, "candidateToken").trim();
        if (!candidateToken.equals(archiveSha256.substring(0, 32))) {
            throw new IllegalArgumentException("candidateToken differs from archive identity");
        }
        remoteCandidateRoot = Objects.requireNonNull(remoteCandidateRoot, "remoteCandidateRoot").trim();
        if (!remoteCandidateRoot.equals("/var/lib/windowstolinux/work/" + candidateId + "/mutable/restore")) {
            throw new IllegalArgumentException("remoteCandidateRoot is invalid");
        }
        mode = Objects.requireNonNull(mode, "mode");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        if (components.isEmpty() || components.size() > 256) {
            throw new IllegalArgumentException("restore activation component count is invalid");
        }
        Set<String> seen = new HashSet<>();
        Set<Integer> candidatePorts = new HashSet<>();
        for (RemoteRestoreActivationComponent component : components) {
            if (!seen.containsAll(component.dependsOn()) || !seen.add(component.componentId())) {
                throw new IllegalArgumentException("restore activation components are not dependency-first");
            }
            if (mode == RemoteRestoreActivationMode.PARALLEL_LOOPBACK && component.ports().isEmpty()
                    || mode == RemoteRestoreActivationMode.SHORT_STOP && !component.ports().isEmpty()
                    || component.ports().stream().anyMatch(binding -> !candidatePorts.add(binding.candidatePort()))) {
                throw new IllegalArgumentException("restore activation ports differ from the selected mode");
            }
        }
        applicationHealthComponentId = id(applicationHealthComponentId, "applicationHealthComponentId");
        if (!seen.contains(applicationHealthComponentId)) {
            throw new IllegalArgumentException("application health owner is absent");
        }
        applicationHealthCheck = Objects.requireNonNull(applicationHealthCheck, "applicationHealthCheck");
    }

    private static String id(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
}
