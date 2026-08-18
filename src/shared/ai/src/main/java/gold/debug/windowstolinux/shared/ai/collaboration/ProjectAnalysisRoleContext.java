package gold.debug.windowstolinux.shared.ai.collaboration;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;

import java.util.List;
import java.util.Objects;

/** Minimal project facts without source paths, contents, configuration values, or secrets. / 不含源码路径、内容、配置值或秘密的最小项目事实。 */
public record ProjectAnalysisRoleContext(
        String applicationId,
        String projectType,
        String buildTool,
        String supportLevel,
        List<String> missingInputCodes
) implements AiRoleContext {
    /** Validates the bounded project-analysis context. / 验证有界项目分析上下文。 */
    public ProjectAnalysisRoleContext {
        applicationId = identifier(applicationId, "applicationId");
        projectType = token(projectType, "projectType");
        buildTool = token(buildTool, "buildTool");
        supportLevel = token(supportLevel, "supportLevel");
        missingInputCodes = Objects.requireNonNull(missingInputCodes, "missingInputCodes");
        if (missingInputCodes.size() > 16) throw new IllegalArgumentException("too many missingInputCodes");
        missingInputCodes = missingInputCodes.stream()
                .map(value -> token(value, "missing input code")).sorted().distinct().toList();
    }

    /** Creates the role context from deterministic facts only. / 仅从确定性事实创建角色上下文。 */
    public static ProjectAnalysisRoleContext from(DeploymentProjectFacts facts) {
        Objects.requireNonNull(facts, "facts");
        return new ProjectAnalysisRoleContext(facts.applicationId(), facts.projectType().name(), facts.buildTool().name(),
                facts.support().level().name(), facts.missingInformation().stream().map(value -> value.key()).toList());
    }

    /** Performs the {@code role} operation. / 执行 {@code role} 操作。 */
    @Override public AiCollaborationRole role() { return AiCollaborationRole.PROJECT_ANALYSIS; }

    /** Performs the {@code redactedSummary} operation. / 执行 {@code redactedSummary} 操作。 */
    @Override public String redactedSummary() {
        return "applicationId=" + applicationId + ";projectType=" + projectType + ";buildTool=" + buildTool
                + ";supportLevel=" + supportLevel + ";missingInputCodes=" + String.join(",", missingInputCodes);
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
