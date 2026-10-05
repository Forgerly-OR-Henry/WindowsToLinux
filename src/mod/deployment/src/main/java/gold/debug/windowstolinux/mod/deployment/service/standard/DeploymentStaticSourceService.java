package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.recover.TaskRecovery;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentProjectStore;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceSetup;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitSourceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentProjectSaveActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentProjectSourceRecorder;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentSourceSelectionService;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentSourceSelectionService.LocalLocation;

/** Runs source preselection and complete project saves without sharing an active grant between tasks. / 执行来源预选及完整项目保存，不跨任务分享活动授权。 */
public final class DeploymentStaticSourceService implements TaskRecovery {
    /** Original project metadata query. / 原项目元数据查询。 */
    public static final String PROJECT_LIST_TASK = "deployment-project-list";

    /** Original standalone local project write. / 原独立本地项目写入。 */
    public static final String PROJECT_SAVE_TASK = "deployment-local-project-save";

    /** Original bound capabilities. / 原绑定能力。 */
    private final ModContext context;

    /** Complete reviewed project identity retained through a source-kind change. / 来源种类变更时保留的完整已审阅项目身份。
     * @param id stable project ID / 稳定项目 ID
     * @param displayName edited name / 已编辑名称
     * @param expected complete old row or creation / 完整旧行或创建
     * @param observedAt actual millisecond observation / 实际毫秒观测 */
    public record ProjectEdit(UUID id, String displayName, Optional<DeploymentProjectRecord> expected,
                              Instant observedAt) {
        /** Validates the old identity and actual observation before source work. / 来源工作前校验旧身份及实际观测。
         * @param id stable identity / 稳定身份
         * @param displayName user name / 用户名称
         * @param expected complete old row / 完整旧行
         * @param observedAt observation / 观测 */
        public ProjectEdit {
            Objects.requireNonNull(id);
            Objects.requireNonNull(observedAt);
            Objects.requireNonNull(expected);
            if (displayName == null || displayName.isBlank() || displayName.length() > 256
                || displayName.chars().anyMatch(c -> c < 32 || c == 127) || observedAt.getNano() % 1_000_000 != 0
                || expected.isPresent() && (!expected.orElseThrow().id().equals(id)
                || !observedAt.isAfter(expected.orElseThrow().updatedAt())))
                throw new IllegalArgumentException("Project edit requires complete original identity and later observation");
        }
    }

    /** Actual owner decision before a standalone source write. / 独立来源写入前的真实所属者决定。 */
    @FunctionalInterface
    public interface SaveReview {
        /** Reviews the actual frozen digest and complete proposed row. / 审阅实际冻结摘要及完整拟写行。
         * @param row actual proposed row / 实际拟写行
         * @param task original task / 原任务
         * @return explicit approval / 明确批准
         * @throws Exception if the owner view closes / 所属视图关闭时 */
        boolean confirm(DeploymentProjectRecord row, TaskContext task) throws Exception;
    }

    /** Binds one page service activation. / 绑定一次页面服务启用。
     * @param context actual context / 实际上下文 */
    public DeploymentStaticSourceService(ModContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /** Returns only metadata after original picker resources are confirmed closed. / 原选择器资源确认关闭后仅返回元数据。
     * @param title actual host picker title / 实际宿主选择器标题
     * @param explicit user-submitted location, absent for host chooser / 用户提交位置，宿主选择器时为空
     * @return original short task / 原短任务
     * @throws ApiException if original admission fails / 原准入失败时 */
    public TaskHandle<LocalLocation> select(String title, Optional<Path> explicit) throws ApiException {
        return selection().select(title, explicit);
    }

    /** Supplies the common picker without exposing standard analysis or project writes. / 提供共用选择器，不暴露标准分析或项目写入。
     * @return original owner picker / 原所属选择器 */
    public DeploymentSourceSelectionService selection() {
        return new DeploymentSourceSelectionService(context);
    }

    /** Lists only the owned schema's complete bounded project rows. / 仅列出所属结构的完整有界项目行。
     * @return original read-only task / 原只读任务
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<List<DeploymentProjectRecord>> projects() throws ApiException {
        return context.tasks().submit(new TaskRequest(PROJECT_LIST_TASK, "Deployment source projects", List.of(), true), task -> {
            try {
                return success(new DeploymentProjectStore(context.db()).list(task, 1000));
            } catch (SQLException unavailable) {
                return failure(TaskResult.FAILED, "DEPLOYMENT_PROJECT_LIST_FAILED");
            } catch (ApiException unavailable) {
                return classified(unavailable);
            }
        });
    }

    /** Reauthorizes and freezes the selected local location in this new save task. / 在本次新保存任务重新授权并冻结所选本地位置。
     * @param location selected metadata / 所选元数据
     * @param edit complete reviewed project edit / 完整已审阅项目编辑
     * @param maximumBytes source limit / 来源限额
     * @param maximumEntries entry limit / 条目限额
     * @param review actual owner review / 实际所属审阅
     * @return original observable save task / 原可观察保存任务
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<DeploymentProjectRecord> saveLocal(LocalLocation location, ProjectEdit edit,
                                                         long maximumBytes, int maximumEntries, SaveReview review) throws ApiException {
        Objects.requireNonNull(location);
        Objects.requireNonNull(edit);
        Objects.requireNonNull(review);
        return context.tasks().submit(new TaskRequest(PROJECT_SAVE_TASK, "Save local source project", List.of(), true), task -> {
            DeploymentSourceMaterial material = null;
            try {
                var selected = context.file().sources().selectDirectory(Path.of(location.location()), task);
                material = new DeploymentSourceSetup(context.file()).prepare(selected, maximumBytes, maximumEntries, task);
                var recorder = new DeploymentProjectSourceRecorder(context);
                var row = recorder.localRecord(task, selected, material, edit.id(), edit.displayName(), edit.expected(), edit.observedAt());
                if (task.cancelToken().requested() || !review.confirm(row, task))
                    return failure(TaskResult.CANCELLED, "DEPLOYMENT_PROJECT_SAVE_CANCELLED");
                return success(DeploymentProjectSaveActionLog.save(context, task, edit.expected(), row));
            } catch (ApiException unavailable) {
                return classified(unavailable);
            } catch (SQLException | IllegalArgumentException conflict) {
                return failure(TaskResult.FAILED, "DEPLOYMENT_PROJECT_SAVE_CONFLICT");
            } finally {
                closeLocal(material, task);
            }
        });
    }

    /** Saves a Git revision using the existing original provider and project journal. / 使用既有原提供方及项目日志保存 Git 修订。
     * @param source captured Git input / 已抓取 Git 输入
     * @param edit complete reviewed project edit / 完整已审阅项目编辑
     * @param maximumBytes source limit / 来源限额
     * @param maximumEntries entry limit / 条目限额
     * @return original Git save task / 原 Git 保存任务
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<DeploymentProjectRecord> saveGit(DeploymentStaticWorkflow.GitSource source, ProjectEdit edit,
                                                       long maximumBytes, int maximumEntries) throws ApiException {
        return new DeploymentGitSourceFlow(context).submit(source.request(), source.executable(), edit.id(),
            edit.displayName(), edit.expected(), edit.observedAt(), maximumBytes, maximumEntries);
    }

    /** Attempts all original local cleanup while preserving uncertainty over a later known rejection. / 尝试全部原本地清理，并保留未知优先于后续已知拒绝。
     * @param material original source if capture succeeded / 捕获成功时的原来源
     * @param task authentic original task / 真实原任务
     * @throws ApiException if either original cleanup is unconfirmed / 任一原清理未确认时 */
    private void closeLocal(DeploymentSourceMaterial material, TaskContext task) throws ApiException {
        ApiException failure = null;
        try {
            if (material != null) material.close();
        } catch (ApiException retained) {
            failure = retained;
        }
        try {
            context.file().closeTaskResources(task);
        } catch (ApiException retained) {
            if (failure == null) failure = retained;
            else if (retained.problem().code() == ProblemCode.UNKNOWN && failure.problem().code() != ProblemCode.UNKNOWN) {
                retained.addSuppressed(failure);
                failure = retained;
            } else failure.addSuppressed(retained);
        }
        if (failure != null) throw failure;
    }

    /** Closes original resources and observes only retained project facts, without another picker or write. / 关闭原资源且仅观察保留项目事实，不再选择或写入。
     * @param previous original task / 原任务
     * @param task original recovery / 原恢复
     * @return confirmed interruption or retained uncertainty / 已确认中断或保留未知
     * @throws Exception if original cleanup or evidence cannot be read / 原清理或依据无法读取时 */
    @Override
    public OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext task) throws Exception {
        if (!previous.id().equals(task.id()) || !List.of(PROJECT_LIST_TASK, PROJECT_SAVE_TASK).contains(previous.type()))
            return failure(TaskResult.UNKNOWN, "DEPLOYMENT_SOURCE_RECOVERY_UNKNOWN");
        var saved = DeploymentProjectSaveActionLog.reconcile(context, task);
        if (saved.filter(TaskResult.UNKNOWN::equals).isPresent())
            return failure(TaskResult.UNKNOWN, "DEPLOYMENT_PROJECT_SAVE_UNKNOWN");
        context.file().closeTaskResources(task);
        if (!context.tasks().resourcesReleased(task))
            return failure(TaskResult.UNKNOWN, "DEPLOYMENT_SOURCE_CLEANUP_UNKNOWN");
        return saved.filter(TaskResult.SUCCEEDED::equals).isPresent() ? success(null)
            : failure(saved.orElse(TaskResult.CANCELLED), "DEPLOYMENT_SOURCE_TASK_INTERRUPTED");
    }

    /** Produces a known value without minting a grant. / 产生已知值，不生成授权。
     * @param <T> value type / 值类型
     * @param value returned small value / 返回小值
     * @return successful outcome / 成功结果 */
    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.ofNullable(value), Optional.empty());
    }

    /** Preserves authentic API failure classification. / 保留真实 API 失败分类。
     * @param <T> value type / 值类型
     * @param failure actual failure / 实际失败
     * @return classified failure / 已分类失败 */
    private static <T> OperationResult<T> classified(ApiException failure) {
        var code = failure.problem().code();
        return new OperationResult<>(code == ProblemCode.UNKNOWN ? TaskResult.UNKNOWN
            : code == ProblemCode.CANCELLED ? TaskResult.CANCELLED : TaskResult.FAILED, Optional.empty(), Optional.of(failure.problem()));
    }

    /** Reports a bounded source or project failure. / 报告有界来源或项目失败。
     * @param <T> value type / 值类型
     * @param outcome exact classification / 精确分类
     * @param event stable event / 稳定事件
     * @return failure result / 失败结果 */
    private static <T> OperationResult<T> failure(TaskResult outcome, String event) {
        return new OperationResult<>(outcome, Optional.empty(), Optional.of(new Problem(outcome == TaskResult.UNKNOWN
            ? ProblemCode.UNKNOWN : outcome == TaskResult.CANCELLED ? ProblemCode.CANCELLED : ProblemCode.IO_FAILURE,
            event, "Original source task could not complete", Map.of(), Optional.empty())));
    }
}
