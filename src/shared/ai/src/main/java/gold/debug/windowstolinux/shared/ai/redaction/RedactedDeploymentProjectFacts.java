package gold.debug.windowstolinux.shared.ai.redaction;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;

import java.util.Objects;

/**
 * Minimal typed deployment facts permitted to leave the deterministic analysis boundary.
 *
 * <p>允许离开确定性分析边界的最小类型化部署事实。
 *
 * @param applicationId the managed application identifier / 受管应用标识
 * @param projectType the selected type, not a source path / 选定类型而非源码路径
 * @param buildTool the fixed build entrypoint / 固定构建入口
 */
public record RedactedDeploymentProjectFacts(String applicationId, String projectType, String buildTool) {
    /** Creates a redacted typed fact value. / 创建脱敏类型化事实值。 */
    public RedactedDeploymentProjectFacts {
        applicationId = Objects.requireNonNull(applicationId, "applicationId");
        projectType = Objects.requireNonNull(projectType, "projectType");
        buildTool = Objects.requireNonNull(buildTool, "buildTool");
    }

    /** Creates redacted facts without retaining a source path or source contents. / 创建不保留源码路径或内容的脱敏事实。 */
    public static RedactedDeploymentProjectFacts from(DeploymentProjectFacts facts) {
        Objects.requireNonNull(facts, "facts");
        return new RedactedDeploymentProjectFacts(facts.applicationId(), facts.projectType().name(), facts.buildTool().name());
    }
}
