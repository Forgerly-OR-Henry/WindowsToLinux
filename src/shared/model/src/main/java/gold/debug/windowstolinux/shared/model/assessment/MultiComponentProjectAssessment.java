package gold.debug.windowstolinux.shared.model.assessment;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.shared.model.project.component.DeploymentComponent;

/**
 * Deterministic mixed-project analysis with component-scoped reasons.
 *
 *  <p>具有组件范围原因的确定性混合项目分析。
 *
 * @param admission the deterministic admission status / 确定性准入状态
 * @param applicationId managed application identifier / 受管应用标识
 * @param applicationRoot application root / 应用根目录
 * @param components reviewed components in the application graph / 应用图中的已审阅组件
 * @param issues issues / 问题集合
 */
public record MultiComponentProjectAssessment(DeploymentAdmissionStatus admission, String applicationId,
        Path applicationRoot, List<DeploymentComponent> components, List<ComponentIssue> issues) {
    /**
     * Validates admission consistency without hiding component issues. / 验证准入一致性且不隐藏组件问题。
     *
     * @param admission the deterministic admission status / 确定性准入状态
     * @param applicationId managed application identifier / 受管应用标识
     * @param applicationRoot application root / 应用根目录
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param issues issues / 问题集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MultiComponentProjectAssessment {
        admission = Objects.requireNonNull(admission, "admission");
        applicationId = Objects.requireNonNull(applicationId, "applicationId").trim();
        if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("applicationId must be a managed identifier");
        }
        applicationRoot = Objects.requireNonNull(applicationRoot, "applicationRoot").toAbsolutePath().normalize();
        components = List.copyOf(Objects.requireNonNull(components, "components").stream()
                .sorted(java.util.Comparator.comparing(DeploymentComponent::componentId)).toList());
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        boolean rejected = issues.stream()
                .anyMatch(issue -> issue.severity() == ComponentIssue.SeverityLevel.SAFETY_REJECTION);
        boolean needsInput = issues.stream()
                .anyMatch(issue -> issue.severity() == ComponentIssue.SeverityLevel.REQUIRES_INPUT);
        if (admission == DeploymentAdmissionStatus.REJECTED != rejected) {
            throw new IllegalArgumentException("rejected mixed assessments must match hard component issues");
        }
        if (!rejected && (admission == DeploymentAdmissionStatus.REQUIRES_INPUT) != needsInput) {
            throw new IllegalArgumentException("input-required mixed assessments must match component issues");
        }
        if (admission == DeploymentAdmissionStatus.READY_FOR_PLANNING && (components.isEmpty()
                || components.stream().noneMatch(component -> component.runtime().isPresent()))) {
            throw new IllegalArgumentException("ready mixed assessments require deployable components");
        }
    }
}
