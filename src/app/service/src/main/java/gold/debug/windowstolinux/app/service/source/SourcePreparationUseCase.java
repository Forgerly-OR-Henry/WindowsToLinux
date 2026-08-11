package gold.debug.windowstolinux.app.service.source;

import gold.debug.windowstolinux.app.windows.workspace.PreparedSourceArchive;
import gold.debug.windowstolinux.app.windows.workspace.WindowsSourceWorkspace;
import gold.debug.windowstolinux.shared.analyze.core.StaticProjectAnalyzer;
import gold.debug.windowstolinux.shared.model.analysis.ProjectAssessment;
import gold.debug.windowstolinux.shared.model.analysis.SupportDecision;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides the {@code SourcePreparationUseCase} implementation.
 *
 * <p>提供 {@code SourcePreparationUseCase} 实现。
 */
public final class SourcePreparationUseCase {
    private final StaticProjectAnalyzer analyzer;
    private final WindowsSourceWorkspace workspace;

    /**
     * Creates a {@code SourcePreparationUseCase} instance.
     *
     * <p>创建 {@code SourcePreparationUseCase} 实例。
     *
     * @param analyzer the {@code analyzer} value / {@code analyzer} 值
     * @param workspace the {@code workspace} value / {@code workspace} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SourcePreparationUseCase(StaticProjectAnalyzer analyzer, WindowsSourceWorkspace workspace) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    /**
     * Performs the {@code prepare} operation.
     *
     * <p>执行 {@code prepare} 操作。
     *
     * @param sourceDirectory the {@code sourceDirectory} value / {@code sourceDirectory} 值
     * @return the operation result / 操作结果
     * @throws IOException if the operation cannot be completed / 无法完成操作时
     */
    public SourcePreparation prepare(Path sourceDirectory) throws IOException {
        ProjectAssessment assessment = analyzer.analyze(sourceDirectory);
        if (assessment.decision() != SupportDecision.SUPPORTED) {
            return new SourcePreparation(assessment, Optional.empty(), List.of());
        }
        String applicationId = assessment.facts().orElseThrow().applicationName();
        PreparedSourceArchive archive = workspace.prepare(sourceDirectory, applicationId);
        return new SourcePreparation(assessment, Optional.of(archive.descriptor()), archive.excludedEntries());
    }
}
