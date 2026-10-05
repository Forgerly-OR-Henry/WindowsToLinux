package gold.debug.windowstolinux.mod.git;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.mod.ModEntrypoint;
import gold.debug.windowstolinux.appapi.mod.lifecycle.StopContext;
import gold.debug.windowstolinux.mod.git.api.GitSourceService;
import gold.debug.windowstolinux.mod.git.service.GitSourceProvider;

/** Stateless Git source service lifecycle without a page or business database. / 无页面或业务数据库的无状态 Git 来源服务生命周期。 */
public final class Mod implements ModEntrypoint {
    /** Original registered service. / 原已登记服务。 */
    private AutoCloseable registration;

    /** Original provider with actual pending work. / 含实际待完成工作的原提供方。 */
    private GitSourceProvider provider;

    /** Creates an inactive service entrypoint. / 创建未启用服务入口。 */
    public Mod() {
    }

    /** Publishes the one actual activation-bound source service. / 发布唯一实际启用绑定来源服务。
     * @param context original host context / 原宿主上下文
     * @throws Exception if service identity or publication fails / 服务身份或发布失败时 */
    @Override
    public void start(ModContext context) throws Exception {
        if (provider != null || registration != null) throw new IllegalStateException("Git is already bound");
        var candidate = new GitSourceProvider(context);
        registration = context.services().register(GitSourceService.class, candidate);
        provider = candidate;
    }

    /** Withdraws only after real original work has released. / 仅在真实原工作释放后撤回。
     * @param context original stop reason / 原停止原因
     * @throws Exception if cleanup or registration remains open / 清理或登记仍未关闭时 */
    @Override
    public void stop(StopContext context) throws Exception {
        if (provider != null) provider.close();
        if (registration != null) registration.close();
        registration = null;
        provider = null;
    }
}
