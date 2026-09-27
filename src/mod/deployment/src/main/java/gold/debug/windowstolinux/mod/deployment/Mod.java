package gold.debug.windowstolinux.mod.deployment;

import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.task.LogCategory;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSchema;

/** Owns official deployment initialization and its platform-bound lifecycle. / 持有官方部署初始化及绑定平台的生命周期。 */
public final class Mod implements ModEntrypoint {
    /** Original published context, absent before startup or after stop. / 原已发布上下文，启动前及停止后不存在。 */
    private ModContext context;

    /** Binds owned diagnostics after the platform has prepared the exact business generation. / 平台准备精确业务代次后绑定所属诊断。
     * @param context actual host-created context / 实际宿主创建的上下文
     * @throws Exception if identity or diagnostic registration is unavailable / 身份或诊断登记不可用时 */
    @Override
    public void start(ModContext context) throws Exception {
        if (this.context != null)
            throw new IllegalStateException("Deployment context is already bound");
        DeploymentSchema.requireIdentity(context.db().identity());
        context.logs().register(new LogCategory("deployment", "Deployment", Optional.of("deployment_log")));
        this.context = context;
    }

    /** Releases the author reference; platform capabilities own their actual cleanup. / 释放作者引用，平台能力负责其实际清理。
     * @param context original stop reason / 原停止原因 */
    @Override
    public void stop(StopContext context) {
        this.context = null;
    }
}
