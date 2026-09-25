package gold.debug.windowstolinux.app.service.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.app.windows.workspace.PreparedSourceArchive;
import gold.debug.windowstolinux.app.windows.workspace.WindowsSourcePreparer;
import gold.debug.windowstolinux.shared.git.GitSnapshot;
import gold.debug.windowstolinux.shared.git.GitSnapshotException;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import gold.debug.windowstolinux.shared.git.snapshot.GitSnapshotPreparer;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.shared.model.assessment.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.SourceRevision;
import gold.debug.windowstolinux.shared.standard.analyze.component.ComponentAnalysisRequest;
import gold.debug.windowstolinux.shared.standard.analyze.component.MixedProjectInspector;
import gold.debug.windowstolinux.shared.standard.analyze.core.DeploymentAnalysisCoordinator;

/**
 * Freezes reviewed local or pinned Git sources into verified deployment archives.
 * <p>将经审阅的本地或固定 Git 源码冻结为已验证部署归档。
 */
public final class SourcePreparationUseCase {
    /** Archives a frozen source without recognition or build policy. / 不经过识别或构建策略归档冻结源码。
     * @param directory frozen source directory / 冻结源码目录
     * @param applicationId managed identifier / 受管标识
     * @return verified archive / 已验证归档
     * @throws IOException when the source boundary is invalid / 源码边界无效时
     */
    public gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor archiveNeutral(Path directory,
            String applicationId) throws IOException {
        return workspace.prepare(directory, applicationId).descriptor();
    }

    /**
     * Freezes local input before automatic discovery or any remote change. / 在自动发现或任何远端变更之前冻结本地输入。
     *
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @return constructed or resolved source directory snapshot / 构造或解析得到的源码目录快照
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public gold.debug.windowstolinux.shared.source.snapshot.SourceDirectorySnapshot snapshot(Path directory)
            throws IOException {
        return gold.debug.windowstolinux.shared.source.snapshot.SourceDirectorySnapshot.create(directory,
                workspace.workDirectory().resolve("source-snapshots"));
    }

    /**
     * Preserves the repository name for analyzers that derive the application identity from its directory. / 保留仓库名称，供根据目录推导应用身份的分析器使用。
     *
     * @param git Git source / Git 源码
     * @return constructed or resolved source directory snapshot / 构造或解析得到的源码目录快照
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public gold.debug.windowstolinux.shared.source.snapshot.SourceDirectorySnapshot snapshot(GitSnapshot git)
            throws IOException {
        String path = git.remote().location().getPath().replaceFirst("/+$", "");
        String name = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.git$", "");
        return gold.debug.windowstolinux.shared.source.snapshot.SourceDirectorySnapshot.create(git.checkoutDirectory(),
                workspace.workDirectory().resolve("source-snapshots"), name);
    }

    /**
     * Resolves the whole Git source once so every component uses the same commit. / 仅解析一次完整 Git 源码，使每个组件使用相同提交。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return the whole Git source once so every component uses the same commit / 仅解析一次完整 Git 源码，使每个组件使用相同提交
     * @throws GitSnapshotException if the git snapshot boundary rejects the operation / Git快照边界拒绝当前操作时
     */
    public GitSnapshot snapshotGit(GitSourceRequest request) throws GitSnapshotException {
        return gitSnapshots.prepare(request, gitWorkspace);
    }
    /**
     * Bound deployment analysis coordinator collaborator for analyzer.
     * <p>处理分析器的部署分析协调器协作对象。
     */
    private final DeploymentAnalysisCoordinator analyzer;

    /**
     * Bound windows source preparer collaborator for platform-owned work area with enforced path boundaries.
     * <p>处理具有路径边界约束的平台工作区的Windows源码准备器协作对象。
     */
    private final WindowsSourcePreparer workspace;

    /**
     * Bound git snapshot preparer collaborator for git snapshots.
     * <p>处理Git快照集合的Git快照准备器协作对象。
     */
    private final GitSnapshotPreparer gitSnapshots;

    /**
     * Git workspace.
     * <p>Git工作区。
     */
    private final Path gitWorkspace;

    /**
     * Validates and binds the inputs required by source preparation use case.
     * <p>校验并绑定源码准备用例所需输入。
     *
     * @param analyzer analyzer / 分析器
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SourcePreparationUseCase(DeploymentAnalysisCoordinator analyzer, WindowsSourcePreparer workspace) {
        this(analyzer, workspace, new GitSnapshotPreparer(),
                Objects.requireNonNull(workspace, "workspace").workDirectory().resolve("git-snapshots"));
    }

    /**
     * Validates and binds the inputs required by source preparation use case.
     * <p>校验并绑定源码准备用例所需输入。
     *
     * @param analyzer analyzer / 分析器
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param gitSnapshots git snapshots / Git快照集合
     * @param gitWorkspace git workspace / Git工作区
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    SourcePreparationUseCase(DeploymentAnalysisCoordinator analyzer, WindowsSourcePreparer workspace,
            GitSnapshotPreparer gitSnapshots, Path gitWorkspace) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.gitSnapshots = Objects.requireNonNull(gitSnapshots, "gitSnapshots");
        this.gitWorkspace = Objects.requireNonNull(gitWorkspace, "gitWorkspace").toAbsolutePath().normalize();
    }

    /**
     * Performs the selected typed static analysis before creating an archive; it never executes project content.
     *
     *  <p>在创建归档前执行选定的类型化静态分析；绝不执行项目内容。
     *
     * @param sourceDirectory the user-selected source directory / 用户选择的源码目录
     * @param projectType the explicitly selected single-component type / 显式选择的单组件类型
     * @return the typed analysis and safe archive result / 类型化分析和安全归档结果
     * @throws IOException if source reading or archiving cannot complete / 无法完成源码读取或归档时
     */
    public ReviewedSourcePreparation prepare(Path sourceDirectory, DeploymentProjectType projectType)
            throws IOException {
        DeploymentProjectAssessment assessment = analyzer.analyze(sourceDirectory, projectType);
        return archive(sourceDirectory, assessment);
    }

    /**
     * Preserves database review as a missing field while discovering runtime inputs. / 发现运行输入时，将数据库审阅保留为待补充字段。
     *
     * @param sourceDirectory the user-selected source directory / 用户选择的源码目录
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @return constructed or resolved reviewed source preparation / 构造或解析得到的已审阅源码准备
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public ReviewedSourcePreparation prepareAutomatic(Path sourceDirectory, DeploymentProjectType projectType)
            throws IOException {
        return archive(sourceDirectory, analyzer.analyzeForDatabaseReview(sourceDirectory, projectType));
    }

    /**
     * Creates the final archive only after database evidence admits the same frozen source. / 仅在数据库证据允许同一冻结源码后创建最终归档。
     *
     * @param sourceDirectory the user-selected source directory / 用户选择的源码目录
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param review review / 审阅
     * @return the final archive only after database evidence admits the same frozen source / 仅在数据库证据允许同一冻结源码后创建最终归档
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public ReviewedSourcePreparation prepareWithDatabaseReview(Path sourceDirectory, DeploymentProjectType projectType,
            gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseSchemaReview review) throws IOException {
        return archive(sourceDirectory, analyzer.analyze(sourceDirectory, projectType, review));
    }

    /**
     * Builds reviewed source preparation from the supplied archive inputs.
     * <p>根据所提供归档输入构建已审阅源码准备。
     *
     * @param sourceDirectory the user-selected source directory / 用户选择的源码目录
     * @param assessment the typed static assessment / 类型化静态评估
     * @return reviewed source preparation from the supplied archive inputs / 根据所提供归档输入构建已审阅源码准备
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private ReviewedSourcePreparation archive(Path sourceDirectory, DeploymentProjectAssessment assessment)
            throws IOException {
        if (assessment.admission() != DeploymentAdmissionStatus.READY_FOR_PLANNING) {
            return new ReviewedSourcePreparation(assessment, Optional.empty(), Optional.empty(), List.of());
        }
        String applicationId = assessment.facts().orElseThrow().applicationId();
        PreparedSourceArchive archive = workspace.prepare(sourceDirectory, applicationId);
        return new ReviewedSourcePreparation(assessment, Optional.of(archive.descriptor()),
                Optional.of(
                        new SourceRevision(archive.descriptor().contentSha256(), Optional.empty(), java.util.Map.of())),
                archive.excludedEntries());
    }

    /**
     * Analyzes an explicit component graph and creates one independent safe archive per admitted component.
     *
     *  <p>分析显式组件图，并为每个准入组件创建一个独立安全归档。
     *
     * @param applicationRoot application root / 应用根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param requests requests / 请求集合
     * @return constructed or resolved prepared multi component source / 构造或解析得到的已准备多组件源码
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public PreparedMultiComponentSource prepareMultiComponent(Path applicationRoot, String applicationId,
            List<ComponentAnalysisRequest> requests) throws IOException {
        return prepareMultiComponent(applicationRoot, applicationId, requests, java.util.Map.of());
    }

    /**
     * Archives a validated graph after each schema-bearing component has been reviewed. / 各含模式声明的组件完成审阅后，归档已验证的组件图。
     *
     * @param applicationRoot application root / 应用根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param requests requests / 请求集合
     * @param reviews reviews / 审阅集合
     * @return constructed or resolved prepared multi component source / 构造或解析得到的已准备多组件源码
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public PreparedMultiComponentSource prepareMultiComponent(Path applicationRoot, String applicationId,
            List<ComponentAnalysisRequest> requests,
            java.util.Map<String, gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseSchemaReview> reviews)
            throws IOException {
        var assessment = new MixedProjectInspector().analyze(applicationRoot, applicationId, requests, reviews);
        if (assessment.admission() != DeploymentAdmissionStatus.READY_FOR_PLANNING) {
            return new PreparedMultiComponentSource(assessment, java.util.Map.of());
        }
        LinkedHashMap<String, PreparedComponentSource> components = new LinkedHashMap<>();
        for (var component : assessment.components().stream().filter(value -> value.runtime().isPresent())
                .sorted(java.util.Comparator.comparing(
                        gold.debug.windowstolinux.shared.model.project.component.DeploymentComponent::componentId))
                .toList()) {
            PreparedSourceArchive archive = workspace.prepare(component.sourceRoot(),
                    component.facts().applicationId());
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
     *  <p>将用户选择的 Git 引用解析为一个分离 Commit，然后在不执行项目代码的情况下分析该精确检出。
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
        if (assessment.admission() != DeploymentAdmissionStatus.READY_FOR_PLANNING) {
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
