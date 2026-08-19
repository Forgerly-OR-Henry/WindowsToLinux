package gold.debug.windowstolinux.shared.ai.collaboration.role;

import java.util.List;
import java.util.Objects;

/** Minimal reviewed-plan facts without SSH data, source contents, configuration values, or secrets. / 不含 SSH 数据、源码内容、配置值或秘密的最小经审阅计划事实。 */
public record DeploymentRiskRoleContext(
        String applicationId,
        String releaseIdentity,
        String distribution,
        String distributionVersion,
        String architecture,
        String supportLevel,
        boolean rootBuild,
        List<String> componentIds
) implements AiRoleContext {
    /** Validates the bounded deployment-risk context. / 验证有界部署风险上下文。 */
    public DeploymentRiskRoleContext {
        applicationId = identifier(applicationId, "applicationId");
        releaseIdentity = Objects.requireNonNull(releaseIdentity, "releaseIdentity");
        if (!releaseIdentity.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("releaseIdentity is invalid");
        distribution = token(distribution, "distribution");
        distributionVersion = token(distributionVersion, "distributionVersion");
        architecture = token(architecture, "architecture");
        supportLevel = token(supportLevel, "supportLevel");
        componentIds = Objects.requireNonNull(componentIds, "componentIds");
        if (componentIds.size() > 64) throw new IllegalArgumentException("too many componentIds");
        componentIds = componentIds.stream()
                .map(value -> identifier(value, "component id")).sorted().distinct().toList();
        if (componentIds.isEmpty()) throw new IllegalArgumentException("componentIds cannot be empty");
    }

    /** Performs the {@code role} operation. / 执行 {@code role} 操作。 */
    @Override public AiCollaborationRoleKind role() { return AiCollaborationRoleKind.DEPLOYMENT_RISK_REVIEW; }

    /** Performs the {@code redactedSummary} operation. / 执行 {@code redactedSummary} 操作。 */
    @Override public String redactedSummary() {
        return "applicationId=" + applicationId + ";releaseIdentity=" + releaseIdentity + ";distribution="
                + distribution + ";distributionVersion=" + distributionVersion + ";architecture=" + architecture
                + ";supportLevel=" + supportLevel + ";rootBuild=" + rootBuild + ";componentIds="
                + String.join(",", componentIds);
    }

    private static String identifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }

    private static String token(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[A-Za-z0-9._:-]{1,96}")) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
