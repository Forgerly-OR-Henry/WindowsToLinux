package gold.debug.windowstolinux.app.service.source;

import gold.debug.windowstolinux.app.windows.workspace.PreparedSourceArchive;
import gold.debug.windowstolinux.app.windows.workspace.WindowsSourceWorkspace;
import gold.debug.windowstolinux.shared.analyze.core.StaticProjectAnalyzer;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentProjectAnalyzer;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmission;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
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
    private final DeploymentProjectAnalyzer deploymentAnalyzer;
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
        this(analyzer, new DeploymentProjectAnalyzer(), workspace);
    }

    SourcePreparationUseCase(StaticProjectAnalyzer analyzer, DeploymentProjectAnalyzer deploymentAnalyzer,
                             WindowsSourceWorkspace workspace) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.deploymentAnalyzer = Objects.requireNonNull(deploymentAnalyzer, "deploymentAnalyzer");
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

    /**
     * Performs the selected typed static analysis before creating an archive; it never executes project content.
     *
     * <p>在创建归档前执行选定的类型化静态分析；绝不执行项目内容。
     *
     * @param sourceDirectory the user-selected source directory / 用户选择的源码目录
     * @param projectType the explicitly selected single-component type / 显式选择的单组件类型
     * @return the typed analysis and safe archive result / 类型化分析和安全归档结果
     * @throws IOException if source reading or archiving cannot complete / 无法完成源码读取或归档时
     */
    public ReviewedSourcePreparation prepareReviewed(Path sourceDirectory, DeploymentProjectType projectType) throws IOException {
        DeploymentProjectAssessment assessment = deploymentAnalyzer.analyze(sourceDirectory, projectType);
        if (assessment.admission() != DeploymentAdmission.READY_FOR_PLANNING) {
            return new ReviewedSourcePreparation(assessment, Optional.empty(), List.of());
        }
        String applicationId = assessment.facts().orElseThrow().applicationId();
        PreparedSourceArchive archive = workspace.prepare(sourceDirectory, applicationId);
        return new ReviewedSourcePreparation(assessment, Optional.of(archive.descriptor()), archive.excludedEntries());
    }
}
