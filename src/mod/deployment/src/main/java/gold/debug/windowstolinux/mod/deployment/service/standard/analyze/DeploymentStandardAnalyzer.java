package gold.debug.windowstolinux.mod.deployment.service.standard.analyze;

import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;


import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.assessment.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseSchemaReview;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;

/** Borrows one still-owned immutable source material for deterministic standard analysis. / 借用一份仍受管的不可变素材进行确定性标准分析。 */
public final class DeploymentStandardAnalyzer {
    /** Complete single-project analyzer. / 完整单项目分析器。 */
    private final DeploymentAnalysisCoordinator single = new DeploymentAnalysisCoordinator();

    /** Complete mixed-project analyzer. / 完整混合项目分析器。 */
    private final MixedProjectInspector mixed = new MixedProjectInspector();

    /** Creates the standard analyzer without retaining a source handle. / 创建不持有来源句柄的标准分析器。 */
    public DeploymentStandardAnalyzer() {
    }

    /** Snapshot identity and the original typed assessment; source paths are observations only. / 快照身份及原类型化评估；源码路径仅为观测值。
     * @param snapshotId original platform snapshot / 原平台快照
     * @param snapshotSha256 original immutable content digest / 原不可变内容摘要
     * @param assessment original deterministic result / 原确定性结果
     * @param <T> single or mixed assessment type / 单项目或混合项目评估类型 */
    public record SnapshotAssessment<T>(UUID snapshotId, String snapshotSha256, T assessment) {
        /** Validates the borrowed material identity. / 校验借用素材身份。
         * @param snapshotId original snapshot UUID / 原快照 UUID
         * @param snapshotSha256 original content digest / 原内容摘要
         * @param assessment complete typed facts / 完整类型化事实 */
        public SnapshotAssessment {
            Objects.requireNonNull(snapshotId);
            Objects.requireNonNull(snapshotSha256);
            Objects.requireNonNull(assessment);
        }
    }

    /** Analyzes all original single-project language, build and safety rules on the frozen source. / 在冻结源码上运行全部原单项目语言、构建及安全规则。
     * @param material original still-open platform material / 原仍开放的平台素材
     * @param logicalRootName original reviewed project name, independent of workspace display / 与工作区显示名独立的原审阅项目名称
     * @param type reviewed project type / 已审阅项目类型
     * @param review optional exact database review / 可选精确数据库审阅
     * @param deferDatabase whether database review must remain pending / 是否保留数据库待审
     * @param task original admitted task / 原准入任务
     * @return typed assessment associated with the same snapshot / 关联同一快照的类型化评估 */
    public OperationResult<SnapshotAssessment<DeploymentProjectAssessment>> analyze(DeploymentSourceMaterial material,
                                                                                    String logicalRootName, DeploymentProjectKind type, Optional<DatabaseSchemaReview> review,
                                                                                    boolean deferDatabase, TaskContext task) {
        Objects.requireNonNull(material);
        requireLogicalName(logicalRootName);
        Objects.requireNonNull(type);
        Objects.requireNonNull(review);
        Objects.requireNonNull(task);
        try {
            active(task);
            Path root = sourceRoot(material);
            if (root == null) {
                active(task);
                return unavailable();
            }
            var assessment = single.analyze(root, type, review, deferDatabase, task.cancelToken(), logicalRootName);
            active(task);
            return success(material, assessment, task);
        } catch (CancellationException cancelled) {
            return cancelled();
        }
    }

    /** Analyzes a discovered component inside the original frozen snapshot. / 在原冻结快照内部分析已发现组件。
     * @param material original live material / 原开放素材
     * @param relativeRoot discovered relative directory / 已发现相对目录
     * @param logicalName reviewed component name / 经审阅组件名
     * @param type selected deployable kind / 选定可部署类型
     * @param task original task / 原任务
     * @return source-bound component assessment / 来源绑定组件评估 */
    public OperationResult<SnapshotAssessment<DeploymentProjectAssessment>> analyzeComponent(
        DeploymentSourceMaterial material, Path relativeRoot, String logicalName, DeploymentProjectKind type,
        TaskContext task) {
        return analyzeComponent(material, relativeRoot, logicalName, type, Optional.empty(), false, task);
    }

    /** Preserves a pending schema decision during discovery or verifies an exact prior review. / 发现时保留待审模式决定，或核验精确既有审阅。
     * @param material original live material / 原开放素材
     * @param relativeRoot discovered relative directory / 已发现相对目录
     * @param logicalName reviewed component name / 经审阅组件名
     * @param type selected deployable kind / 选定可部署类型
     * @param review verified schema evidence, if available / 已验证模式依据（若有）
     * @param deferDatabase whether schema review remains pending / 模式审阅是否待决
     * @param task original task / 原任务
     * @return source-bound component assessment / 来源绑定组件评估 */
    public OperationResult<SnapshotAssessment<DeploymentProjectAssessment>> analyzeComponent(
        DeploymentSourceMaterial material, Path relativeRoot, String logicalName, DeploymentProjectKind type,
        Optional<DatabaseSchemaReview> review, boolean deferDatabase, TaskContext task) {
        Objects.requireNonNull(material);
        Objects.requireNonNull(relativeRoot);
        requireLogicalName(logicalName);
        Objects.requireNonNull(type);
        Objects.requireNonNull(review);
        Objects.requireNonNull(task);
        try {
            active(task);
            Path root = sourceRoot(material);
            if (root == null) {
                active(task);
                return unavailable();
            }
            Path component = root.resolve(relativeRoot).normalize();
            if (relativeRoot.isAbsolute() || !component.startsWith(root))
                return unavailable();
            var assessment = single.analyze(component, type, review, deferDatabase, task.cancelToken(), logicalName);
            active(task);
            return success(material, assessment, task);
        } catch (CancellationException cancelled) {
            return cancelled();
        }
    }

    /** Analyzes one complete component graph on the same frozen source. / 在同一冻结源码上分析完整组件图。
     * @param material original still-open platform material / 原仍开放的平台素材
     * @param logicalRootName original reviewed project name / 原审阅项目名称
     * @param applicationId reviewed managed application / 已审阅受管应用
     * @param requests complete component requests / 完整组件请求
     * @param reviews exact per-component database reviews / 各组件精确数据库审阅
     * @param deferDatabase whether database review must remain pending / 是否保留数据库待审
     * @param task original admitted task / 原准入任务
     * @return typed graph assessment associated with the same snapshot / 关联同一快照的类型化图评估 */
    public OperationResult<SnapshotAssessment<MultiComponentProjectAssessment>> analyzeComponents(
        DeploymentSourceMaterial material, String logicalRootName, String applicationId,
        List<ComponentAnalysisRequest> requests, Map<String, DatabaseSchemaReview> reviews, boolean deferDatabase,
        TaskContext task) {
        Objects.requireNonNull(material);
        requireLogicalName(logicalRootName);
        Objects.requireNonNull(applicationId);
        Objects.requireNonNull(requests);
        Objects.requireNonNull(reviews);
        Objects.requireNonNull(task);
        try {
            active(task);
            Path root = sourceRoot(material);
            if (root == null) {
                active(task);
                return unavailable();
            }
            var assessment = mixed.analyze(root, applicationId, requests, reviews, deferDatabase, task.cancelToken(),
                logicalRootName);
            active(task);
            return success(material, assessment, task);
        } catch (CancellationException cancelled) {
            return cancelled();
        }
    }

    /** Checks cooperative cancellation of the original task. / 检查原任务的协作取消。
     * @param task original task / 原任务
     * @throws CancellationException if cancellation is requested / 请求取消时 */
    private static void active(TaskContext task) {
        if (task.cancelToken().requested() || Thread.currentThread().isInterrupted())
            throw new CancellationException("Original analysis was cancelled");
    }

    /** Reads the currently open snapshot directory only. / 仅读取当前开放的快照目录。
     * @param material borrowed material / 借用素材
     * @return directory or null when the handle is closed / 目录；句柄关闭时为空 */
    private static Path sourceRoot(DeploymentSourceMaterial material) {
        try {
            return material.snapshot().directory();
        } catch (IllegalStateException closed) {
            return null;
        }
    }

    /** Rejects an absent reviewed name before source access. / 在源码访问前拒绝缺失的审阅名称。
     * @param name original reviewed name / 原审阅名称 */
    private static void requireLogicalName(String name) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("logicalRootName is required");
    }

    /** Returns an assessment while its original material is still open. / 在原素材仍开放时返回评估。
     * @param material original material / 原素材
     * @param assessment complete typed assessment / 完整类型化评估
     * @param task original task / 原任务
     * @param <T> assessment type / 评估类型
     * @return successful observation / 成功观测 */
    private static <T> OperationResult<SnapshotAssessment<T>> success(DeploymentSourceMaterial material, T assessment,
                                                                      TaskContext task) {
        if (sourceRoot(material) == null) {
            active(task);
            return unavailable();
        }
        return new OperationResult<>(Outcome.SUCCEEDED,
            Optional.of(
                new SnapshotAssessment<>(material.snapshot().id(), material.snapshot().sha256(), assessment)),
            Optional.empty());
    }

    /** Reports confirmed cooperative cancellation. / 报告已确认协作取消。
     * @param <T> assessment type / 评估类型
     * @return cancelled outcome / 已取消结果 */
    private static <T> OperationResult<SnapshotAssessment<T>> cancelled() {
        return new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional.of(new Problem(ProblemCode.CANCELLED,
            "DEPLOYMENT_ANALYSIS_CANCELLED", "Source analysis was cancelled", Map.of(), Optional.empty())));
    }

    /** Reports that the original frozen material was not available. / 报告原冻结素材不可用。
     * @param <T> assessment type / 评估类型
     * @return known failure / 已知失败 */
    private static <T> OperationResult<SnapshotAssessment<T>> unavailable() {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
            Optional.of(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_ANALYSIS_SOURCE_UNAVAILABLE",
                "Frozen source material is unavailable", Map.of(), Optional.empty())));
    }
}
