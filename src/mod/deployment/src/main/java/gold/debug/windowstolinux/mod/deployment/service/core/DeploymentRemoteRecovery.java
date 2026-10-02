package gold.debug.windowstolinux.mod.deployment.service.core;

import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentExecutionRecord;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentPublicationProposal;

/** Narrow original-task publication recovery boundary assembled by the Mod. / 由Mod装配的窄原任务发布恢复边界。 */
@FunctionalInterface
public interface DeploymentRemoteRecovery {
    /** Reconciles original remote evidence and cleanup without recreating source or replaying publication. / 不重建来源或重放发布，仅核对原远端依据与收尾。
     * @param task authentic original recovery context / 真实原恢复上下文
     * @param record original retained execution / 原留存执行
     * @return remote-owned result, or absence for a reference-only execution / 远端所属结果；纯引用执行为空
     * @throws Exception if original evidence or cleanup cannot be accessed / 原依据或收尾不可访问时 */
    Optional<OperationResult<DeploymentPublicationProposal>> reconcile(TaskContext task,
                                                                       DeploymentExecutionRecord record) throws Exception;
}
