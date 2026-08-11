package gold.debug.windowstolinux.app.service.source;

import gold.debug.windowstolinux.shared.model.analysis.ProjectAssessment;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;

import java.util.Objects;
import java.util.Optional;
import java.util.List;

/**
 * Result shown to the desktop UI before the user can confirm a target deployment.
 *
 * <p>用户确认目标部署之前向桌面界面展示的结果。
 *
 * @param assessment the {@code assessment} value / {@code assessment} 值
 * @param archive the {@code archive} value / {@code archive} 值
 * @param excludedEntries the {@code excludedEntries} value / {@code excludedEntries} 值
 */
public record SourcePreparation(ProjectAssessment assessment, Optional<SourceArchiveDescriptor> archive, List<String> excludedEntries) {
    /**
     * Creates a {@code SourcePreparation} instance.
     *
     * <p>创建 {@code SourcePreparation} 实例。
     *
     * @param assessment the {@code assessment} value / {@code assessment} 值
     * @param archive the {@code archive} value / {@code archive} 值
     * @param excludedEntries the {@code excludedEntries} value / {@code excludedEntries} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SourcePreparation {
        assessment = Objects.requireNonNull(assessment, "assessment");
        archive = Objects.requireNonNull(archive, "archive");
        excludedEntries = List.copyOf(Objects.requireNonNull(excludedEntries, "excludedEntries"));
        if (assessment.decision() == gold.debug.windowstolinux.shared.model.analysis.SupportDecision.SUPPORTED != archive.isPresent()) {
            throw new IllegalArgumentException("only supported projects may have an archive");
        }
    }
}
