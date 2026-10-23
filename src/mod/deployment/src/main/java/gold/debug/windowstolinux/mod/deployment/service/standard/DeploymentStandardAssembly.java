package gold.debug.windowstolinux.mod.deployment.service.standard;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRemoteRecovery;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardModeSupport;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardRecoverySupport;
import gold.debug.windowstolinux.mod.deployment.service.standard.staticmode.DeploymentStaticWorkflow;

/** Upper assembly of standard modes and captured recovery routing. / 标准模式与捕获恢复路由的上层装配。 */
public final class DeploymentStandardAssembly implements DeploymentStandardRecoverySupport {
    /** Selects the adapter for the persisted original execution mode. / 为持久化原执行模式选择适配。
     * @param executionMode persisted original mode value / 持久化原模式值
     * @return adapter for the stored original mode / 存储原模式的适配 */
    public DeploymentStandardModeSupport mode(String executionMode) {
        return switch (executionMode) {
            case "STATIC" -> DeploymentStaticWorkflow.support();
            case "ASSISTED" -> DeploymentAssistedWorkflow.support();
            default -> throw new IllegalArgumentException("Unsupported standard recovery mode");
        };
    }

    /** Checks whether original publication recovery is still admitted. / 核对原发布恢复是否仍获准入。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param task original admitted task / 原已准入任务
     * @return whether original recovery is admitted / 原恢复是否获准入 */
    public boolean permits(ModContext context, TaskContext task) {
        return DeploymentStandardPublishRecovery.permitsOriginalRecovery(context, task);
    }

    /** Confirms captured authority before original candidate cleanup. / 在原候选清理前确认已捕获授权。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param task original admitted task / 原已准入任务
     * @return whether original cleanup authority is confirmed / 原清理授权是否获确认
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public boolean cleanupReady(ModContext context, TaskContext task) throws ApiException {
        return DeploymentStandardPublishRecovery.originalCleanupReady(context, task);
    }

    /** Routes the original publication through its captured mode. / 按已捕获模式路由原发布。
     * @param context original Mod capabilities / 原 Mod 能力
     * @return original classified publication preparation or recovery route / 原分类发布准备或恢复路由 */
    public DeploymentRemoteRecovery publication(ModContext context) {
        return new DeploymentStandardPublishRecovery(context);
    }
}
