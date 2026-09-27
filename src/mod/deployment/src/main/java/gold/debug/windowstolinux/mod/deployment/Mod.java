package gold.debug.windowstolinux.mod.deployment;

import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.task.LogCategory;
import gold.debug.windowstolinux.appapi.task.RecoveryRegistration;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSchema;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;

/** Owns official deployment initialization and its platform-bound lifecycle. / 持有官方部署初始化及绑定平台的生命周期。 */
public final class Mod implements ModEntrypoint {
    /** Original published context, absent before startup or after stop. / 原已发布上下文，启动前及停止后不存在。 */
    private ModContext context;

    /** Actual publication task and recovery implementation. / 实际发布任务及恢复实现。 */
    private DeploymentReferenceFlow publicationFlow;

    /** Active original-task recovery registration. / 活动原任务恢复登记。 */
    private RecoveryRegistration recoveryRegistration;

    /** Binds owned diagnostics after the platform has prepared the exact business generation. / 平台准备精确业务代次后绑定所属诊断。
     * @param context actual host-created context / 实际宿主创建的上下文
     * @throws Exception if identity or diagnostic registration is unavailable / 身份或诊断登记不可用时 */
    @Override
    public void start(ModContext context) throws Exception {
        if (this.context != null)
            throw new IllegalStateException("Deployment context is already bound");
        DeploymentSchema.requireIdentity(context.db().identity());
        context.logs().register(new LogCategory("deployment", "Deployment", Optional.of("deployment_log")));
        var flow = new DeploymentReferenceFlow(context);
        var registration = context.tasks().registerRecovery(DeploymentReferenceFlow.TASK_TYPE, flow);
        this.publicationFlow = flow;
        this.recoveryRegistration = registration;
        this.context = context;
    }

    /** Returns the active production publication entrypoint. / 返回活动生产发布入口。
     * @return bound publication flow / 已绑定发布流程 */
    public DeploymentReferenceFlow publicationFlow() {
        if (publicationFlow == null)
            throw new IllegalStateException("Deployment Mod is not started");
        return publicationFlow;
    }

    /** Releases the author reference; platform capabilities own their actual cleanup. / 释放作者引用，平台能力负责其实际清理。
     * @param context original stop reason / 原停止原因
     * @throws Exception if recovery registration cannot close / 恢复登记无法关闭时 */
    @Override
    public void stop(StopContext context) throws Exception {
        if (recoveryRegistration != null)
            recoveryRegistration.close();
        recoveryRegistration = null;
        publicationFlow = null;
        this.context = null;
    }
}
