package gold.debug.windowstolinux.shared.model.assessment;

import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmission;
import gold.debug.windowstolinux.shared.model.project.component.DeploymentComponent;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic mixed-project analysis with component-scoped reasons.
 *
 * <p>具有组件范围原因的确定性混合项目分析。
 */
public record MultiComponentProjectAssessment(
        DeploymentAdmission admission,
        String applicationId,
        Path applicationRoot,
        List<DeploymentComponent> components,
        List<ComponentIssue> issues
) {
    /** Validates admission consistency without hiding component issues. / 验证准入一致性且不隐藏组件问题。 */
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
        boolean rejected = issues.stream().anyMatch(issue -> issue.severity() == ComponentIssue.Severity.SAFETY_REJECTION);
        boolean needsInput = issues.stream().anyMatch(issue -> issue.severity() == ComponentIssue.Severity.REQUIRES_INPUT);
        if (admission == DeploymentAdmission.REJECTED != rejected) {
            throw new IllegalArgumentException("rejected mixed assessments must match hard component issues");
        }
        if (!rejected && (admission == DeploymentAdmission.REQUIRES_INPUT) != needsInput) {
            throw new IllegalArgumentException("input-required mixed assessments must match component issues");
        }
        if (admission == DeploymentAdmission.READY_FOR_PLANNING
                && (components.isEmpty() || components.stream().noneMatch(component -> component.runtime().isPresent()))) {
            throw new IllegalArgumentException("ready mixed assessments require deployable components");
        }
    }
}
