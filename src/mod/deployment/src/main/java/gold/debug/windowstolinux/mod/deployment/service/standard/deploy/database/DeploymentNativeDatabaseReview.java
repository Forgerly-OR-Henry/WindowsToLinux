package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database;

import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseRequirement;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Candidate;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Instance;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.InstanceList;

/** Original-view decisions for exact native database effects and short-lived passwords. / 精确原生数据库效果与短期密码的原视图决定。 */
public interface DeploymentNativeDatabaseReview {
    /** Selects one actual instance or confirms exact candidate installation. / 选择实际实例或确认精确候选安装。
     * @param server admitted server / 已准入服务器
     * @param componentId frozen component / 冻结组件
     * @param database source-backed requirement / 源码绑定要求
     * @param inventory actual inventory / 实际库存
     * @param task original task / 原任务
     * @return selected ID; empty value means candidate install / 选定 ID；空值表示候选安装
     * @throws Exception if review fails / 审阅失败时 */
    default OperationResult<String> choose(ServerId server, String componentId, DatabaseRequirement database,
                                           InstanceList inventory, TaskContext task) throws Exception {
        return unavailable();
    }

    /** Confirms the exact package before first installation. / 首次安装前确认精确软件包。
     * @param server admitted server / 已准入服务器
     * @param database source-backed requirement / 源码绑定要求
     * @param candidate actual package candidate / 实际软件包候选
     * @param task original task / 原任务
     * @return affirmative decision / 肯定决定
     * @throws Exception if review fails / 审阅失败时 */
    default OperationResult<Boolean> install(ServerId server, DatabaseRequirement database, Candidate candidate,
                                             TaskContext task) throws Exception {
        return unavailable();
    }

    /** Requires user-completed backup and downtime before replacing one exact version. / 替换精确版本前要求用户已完成备份及停机。
     * @param server admitted server / 已准入服务器
     * @param previous exact selected instance / 精确选定实例
     * @param candidate exact replacement candidate / 精确替换候选
     * @param task original task / 原任务
     * @return affirmative backup and downtime confirmation / 已备份且停机的肯定确认
     * @throws Exception if review fails / 审阅失败时 */
    default OperationResult<Boolean> replacement(ServerId server, Instance previous, Candidate candidate,
                                                 TaskContext task) throws Exception {
        return unavailable();
    }

    /** Confirms user-completed manual restoration after package replacement. / 软件包替换后确认用户已完成人工恢复。
     * @param server admitted server / 已准入服务器
     * @param instance restored exact instance / 已恢复的精确实例
     * @param task original task / 原任务
     * @return affirmative decision / 肯定决定
     * @throws Exception if review fails / 审阅失败时 */
    default OperationResult<Boolean> restored(ServerId server, Instance instance, TaskContext task) throws Exception {
        return unavailable();
    }

    /** Obtains a newly owned administrator password only after socket authentication fails. / 仅 socket 认证失败后取得新所属管理员密码。
     * @param server admitted server / 已准入服务器
     * @param instance selected instance / 选定实例
     * @param task original task / 原任务
     * @return newly owned UTF-8 bytes for caller to clear / 由调用方清零的新所属 UTF-8 字节
     * @throws Exception if review fails / 审阅失败时 */
    default OperationResult<byte[]> administratorPassword(ServerId server, Instance instance, TaskContext task)
        throws Exception {
        return unavailable();
    }

    /** Obtains a newly owned application password for an existing target. / 为已有目标取得新所属应用密码。
     * @param server admitted server / 已准入服务器
     * @param database source-backed requirement / 源码绑定要求
     * @param task original task / 原任务
     * @return newly owned UTF-8 bytes for caller to clear / 由调用方清零的新所属 UTF-8 字节
     * @throws Exception if review fails / 审阅失败时 */
    default OperationResult<byte[]> applicationPassword(ServerId server, DatabaseRequirement database,
                                                        TaskContext task) throws Exception {
        return unavailable();
    }

    /** Independently approves SQL changes to a target that already contains a schema. / 独立批准已有结构目标的 SQL 变更。
     * @param server admitted server / 已准入服务器
     * @param componentId frozen component / 冻结组件
     * @param database source-backed requirement / 源码绑定要求
     * @param sourceDigest actual component inspection digest / 实际组件检查摘要
     * @param task original task / 原任务
     * @return affirmative schema change decision / 肯定结构变更决定
     * @throws Exception if review fails / 审阅失败时 */
    default OperationResult<Boolean> existingSchema(ServerId server, String componentId, DatabaseRequirement database,
                                                    String sourceDigest, TaskContext task) throws Exception {
        return unavailable();
    }

    /** Rejects native effects when a reviewer has no explicit implementation. / 审阅者没有显式实现时拒绝原生效果。
     * @param <T> expected value / 预期值
     * @return known refusal / 已知拒绝 */
    private static <T> OperationResult<T> unavailable() {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_NATIVE_DB_REVIEW_UNAVAILABLE",
                "Native database review is unavailable", Map.of(), Optional.empty())));
    }
}
