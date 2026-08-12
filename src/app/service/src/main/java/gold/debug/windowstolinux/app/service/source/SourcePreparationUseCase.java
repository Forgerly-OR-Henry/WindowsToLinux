package gold.debug.windowstolinux.app.service.source;

import gold.debug.windowstolinux.app.windows.workspace.PreparedSourceArchive;
import gold.debug.windowstolinux.app.windows.workspace.WindowsSourceWorkspace;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.analyze.component.ComponentAnalysisRequest;
import gold.debug.windowstolinux.shared.analyze.component.MixedProjectAnalyzer;
import gold.debug.windowstolinux.shared.git.snapshot.GitSnapshot;
import gold.debug.windowstolinux.shared.git.snapshot.GitSnapshotException;
import gold.debug.windowstolinux.shared.git.snapshot.GitSnapshotPreparer;
import gold.debug.windowstolinux.shared.git.snapshot.GitSourceRequest;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmission;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.SourceRevision;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides the {@code SourcePreparationUseCase} implementation.
 *
 * <p>提供 {@code SourcePreparationUseCase} 实现。
 */
public final class SourcePreparationUseCase {
    private final DeploymentAnalysisCoordinator analyzer;
    private final WindowsSourceWorkspace workspace;
    private final GitSnapshotPreparer gitSnapshots;
    private final Path gitWorkspace;

    /**
     * Creates a {@code SourcePreparationUseCase} instance.
     *
     * <p>创建 {@code SourcePreparationUseCase} 实例。
     *
     * @param analyzer the {@code analyzer} value / {@code analyzer} 值
     * @param workspace the {@code workspace} value / {@code workspace} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SourcePreparationUseCase(DeploymentAnalysisCoordinator analyzer, WindowsSourceWorkspace workspace) {
        this(analyzer, workspace, new GitSnapshotPreparer(),
                Objects.requireNonNull(workspace, "workspace").workDirectory().resolve("git-snapshots"));
    }

    SourcePreparationUseCase(DeploymentAnalysisCoordinator analyzer, WindowsSourceWorkspace workspace,
                             GitSnapshotPreparer gitSnapshots, Path gitWorkspace) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.gitSnapshots = Objects.requireNonNull(gitSnapshots, "gitSnapshots");
        this.gitWorkspace = Objects.requireNonNull(gitWorkspace, "gitWorkspace").toAbsolutePath().normalize();
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
    public ReviewedSourcePreparation prepare(Path sourceDirectory, DeploymentProjectType projectType) throws IOException {
        DeploymentProjectAssessment assessment = analyzer.analyze(sourceDirectory, projectType);
        if (assessment.admission() != DeploymentAdmission.READY_FOR_PLANNING) {
            return new ReviewedSourcePreparation(assessment, Optional.empty(), Optional.empty(), List.of());
        }
        String applicationId = assessment.facts().orElseThrow().applicationId();
        PreparedSourceArchive archive = workspace.prepare(sourceDirectory, applicationId);
        return new ReviewedSourcePreparation(assessment, Optional.of(archive.descriptor()),
                Optional.of(new SourceRevision(archive.descriptor().contentSha256(), Optional.empty(), java.util.Map.of())),
                archive.excludedEntries());
    }

    /**
     * Analyzes an explicit component graph and creates one independent safe archive per admitted component.
     *
     * <p>分析显式组件图，并为每个准入组件创建一个独立安全归档。
     */
    public PreparedMultiComponentSource prepareMultiComponent(Path applicationRoot, String applicationId,
                                                               List<ComponentAnalysisRequest> requests)
            throws IOException {
        var assessment = new MixedProjectAnalyzer().analyze(applicationRoot, applicationId, requests);
        if (assessment.admission() != DeploymentAdmission.READY_FOR_PLANNING) {
            return new PreparedMultiComponentSource(assessment, java.util.Map.of());
        }
        LinkedHashMap<String, PreparedComponentSource> components = new LinkedHashMap<>();
        for (var component : assessment.components().stream()
                .filter(value -> value.runtime().isPresent())
                .sorted(java.util.Comparator.comparing(
                        gold.debug.windowstolinux.shared.model.project.component.DeploymentComponent::componentId))
                .toList()) {
            PreparedSourceArchive archive = workspace.prepare(component.sourceRoot(), component.facts().applicationId());
            SourceRevision revision = new SourceRevision(archive.descriptor().contentSha256(), Optional.empty(),
                    java.util.Map.of());
            components.put(component.componentId(), new PreparedComponentSource(component.componentId(),
                    component.facts(), archive.descriptor(), revision, archive.excludedEntries()));
        }
        return new PreparedMultiComponentSource(assessment, components);
    }

    /**
     * Resolves a user-selected Git reference to one detached commit, then analyzes that exact checkout without executing project code.
     *
     * <p>将用户选择的 Git 引用解析为一个分离 Commit，然后在不执行项目代码的情况下分析该精确检出。
     *
     * @param request the explicit credential-free Git source request / 显式且不含凭据的 Git 源码请求
     * @param projectType the user-selected type / 用户选择的类型
     * @return the typed analysis, pinned source identity, and safe archive / 类型化分析、固定源码身份和安全归档
     * @throws GitSnapshotException if the controlled Git snapshot cannot be prepared / 无法准备受控 Git 快照时
     */
    public ReviewedSourcePreparation prepareGit(GitSourceRequest request, DeploymentProjectType projectType)
            throws GitSnapshotException {
        GitSnapshot snapshot = gitSnapshots.prepare(request, gitWorkspace);
        DeploymentProjectAssessment assessment = analyzer.analyze(snapshot.checkoutDirectory(), projectType);
        if (assessment.admission() != DeploymentAdmission.READY_FOR_PLANNING) {
            return new ReviewedSourcePreparation(assessment, Optional.empty(), Optional.empty(), List.of());
        }
        var archive = snapshot.archive();
        var descriptor = new gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor(
                archive.archivePath(), archive.contentSha256(), archive.byteCount(), archive.uncompressedByteCount());
        SourceRevision revision = new SourceRevision(archive.contentSha256(), Optional.of(snapshot.commit()),
                Optional.of(snapshot.remote().location()), java.util.Map.of());
        return new ReviewedSourcePreparation(assessment, Optional.of(descriptor), Optional.of(revision),
                archive.excludedEntries());
    }

}
