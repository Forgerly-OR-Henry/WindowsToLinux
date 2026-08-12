package gold.debug.windowstolinux.app.service.source;

import gold.debug.windowstolinux.shared.model.analysis.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmission;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.project.SourceRevision;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed source analysis and safe archive result shown before a reviewed deployment is planned.
 *
 * <p>在计划经审阅部署前展示的类型化源码分析和安全归档结果。
 *
 * @param assessment the typed static assessment / 类型化静态评估
 * @param archive the safe archive when planning is permitted / 允许计划时的安全归档
 * @param sourceRevision the immutable local or pinned-Git source identity / 不可变本地或固定 Git 源码身份
 * @param excludedEntries entries excluded by the safe archive policy / 安全归档策略排除的条目
 */
public record ReviewedSourcePreparation(
        DeploymentProjectAssessment assessment,
        Optional<SourceArchiveDescriptor> archive,
        Optional<SourceRevision> sourceRevision,
        List<String> excludedEntries
) {
    /** Creates the reviewed source result. / 创建经审阅源码结果。 */
    public ReviewedSourcePreparation {
        assessment = Objects.requireNonNull(assessment, "assessment");
        archive = Objects.requireNonNull(archive, "archive");
        sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
        excludedEntries = List.copyOf(Objects.requireNonNull(excludedEntries, "excludedEntries"));
        if ((assessment.admission() == DeploymentAdmission.READY_FOR_PLANNING) != archive.isPresent()) {
            throw new IllegalArgumentException("only a planning-ready typed source may have an archive");
        }
        if (archive.isPresent() != sourceRevision.isPresent()) {
            throw new IllegalArgumentException("a reviewed archive must have exactly one immutable source identity");
        }
        if (archive.isPresent() && !archive.orElseThrow().contentSha256()
                .equals(sourceRevision.orElseThrow().sourceSha256())) {
            throw new IllegalArgumentException("source identity must bind the reviewed archive digest");
        }
    }
}
