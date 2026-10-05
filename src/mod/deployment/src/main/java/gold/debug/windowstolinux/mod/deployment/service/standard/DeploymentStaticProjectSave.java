package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.sql.SQLException;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitSourceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentProjectSaveActionLog;
import gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics;

/** Saves the already reviewed publication source once on its original task. / 在原任务中仅保存一次已审阅发布来源。 */
final class DeploymentStaticProjectSave {
    /** No instances. / 不创建实例。 */
    private DeploymentStaticProjectSave() {
    }

    /** Commits the selected complete project revision after final source consent. / 最终来源同意后提交所选完整项目修订。
     * @param context original capabilities / 原能力
     * @param task original publication task / 原发布任务
     * @param draft captured complete input / 已抓取完整输入
     * @param git original pinned Git or absence / 原固定 Git 或空
     * @param local complete observed local row / 完整已观察本地行
     * @return actual saved project or absence / 实际已存项目或不存在
     * @throws ApiException if original durability is unconfirmed / 原持久性未确认时
     * @throws SQLException if the complete expected row conflicts / 完整预期行冲突时 */
    static Optional<DeploymentProjectRecord> save(ModContext context, TaskContext task,
                                                  DeploymentStaticWorkflow.Draft draft, DeploymentGitSourceFlow.PublishSource git,
                                                  Optional<DeploymentProjectRecord> local) throws ApiException, SQLException {
        if (git != null) {
            var saved = git.save();
            DeploymentDiagnostics.progress(context, task, "Source-project revision saved / 来源项目修订已保存");
            return Optional.of(saved);
        }
        if (local.isEmpty()) return Optional.empty();
        var edit = draft.sourceOptions().project().orElseThrow();
        var saved = DeploymentProjectSaveActionLog.save(context, task, edit.expected(), local.orElseThrow());
        DeploymentDiagnostics.progress(context, task, "Source-project revision saved / 来源项目修订已保存");
        return Optional.of(saved);
    }
}
