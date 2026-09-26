package gold.debug.windowstolinux.app.service.mod;

import java.util.*;

import gold.debug.windowstolinux.app.mod.lifecycle.ModRuntimeSession.ContextLease;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;
import gold.debug.windowstolinux.appapi.mod.ModContext;

/** Binds actual task gates and staged host contributions to one Mod activation. / 将实际任务准入门及暂存宿主扩展绑定到一次 Mod 启用。 */
public final class ModHostLease implements ContextLease {
    /** Exact public capabilities supplied by host assembly. / 宿主装配提供的精确公开能力。 */
    private final ModContext context;

    /** Shared task coordinator enforcing owner and dependency admission. / 执行所有者及依赖准入的共享任务协调器。 */
    private final PlatformTaskRuntime tasks;

    /** Actual staged host registrations, released in reverse publication order. / 按发布逆序释放的实际暂存宿主登记。 */
    private final List<Contribution> contributions;

    /** Number of trailing contributions already closed successfully. / 已成功关闭的末尾扩展数量。 */
    private int closedContributions;

    /** Whether new publication has been permanently sealed. / 是否已永久封闭新发布。 */
    private boolean stopping;

    /** Whether all contributions and recovery registrations are released. / 全部扩展及恢复登记是否已释放。 */
    private boolean closed;

    /** Whether publication already succeeded. / 发布是否已成功。 */
    private boolean published;

    /**
     * Holds admission closed while the author initializes its registrations. / 作者初始化登记期间保持准入关闭。
     * @param context exact caller-bound capabilities / 精确绑定调用方的能力
     * @param tasks actual platform task runtime / 实际平台任务运行时
     * @param contributions prepared publication and cleanup actions / 已准备的发布及清理操作
     */
    public ModHostLease(ModContext context, PlatformTaskRuntime tasks, List<Contribution> contributions) {
        this.context = Objects.requireNonNull(context);
        this.tasks = Objects.requireNonNull(tasks);
        this.contributions = List.copyOf(contributions);
        tasks.blockOwner(context.identity().id(), "Mod startup has not completed");
    }

    /** Returns the actual bound public capability context. / 返回实际绑定的公开能力上下文。
     * @return exact caller context / 精确调用方上下文 */
    @Override
    public ModContext context() {
        return context;
    }

    /**
     * Publishes real contributions before allowing new business tasks. / 允许新业务任务前发布实际扩展。
     * @throws Exception if retained work, publication or activation prevents success / 保留工作、发布或激活阻止成功时
     */
    @Override
    public synchronized void commit() throws Exception {
        if (stopping || closed)
            throw new IllegalStateException("Mod publication is sealed");
        if (published)
            return;
        boolean normal = context.launchMode() == gold.debug.windowstolinux.appapi.mod.ModLaunchMode.NORMAL;
        if (normal && !tasks.ownerBlockers(context.identity().id()).isEmpty())
            throw new IllegalStateException("Previous Mod work still requires reconciliation");
        for (var contribution : contributions)
            contribution.publish();
        if (normal)
            tasks.allowOwner(context.identity().id());
        published = true;
    }

    /** Seals owner admission before checking the retained resource set. / 检查保留资源集合前封闭所有者准入。 */
    @Override
    public synchronized void seal() {
        stopping = true;
        tasks.blockOwner(context.identity().id(), "Mod is stopping or waiting for restart");
    }

    /**
     * Reports actual task and cross-Mod resource occupancy. / 报告实际任务及跨 Mod 资源占用。
     * @return retained resource blockers / 保留资源的阻断原因
     * @throws Exception if the ledger cannot be checked / 无法检查账本时
     */
    @Override
    public List<String> blockers() throws Exception {
        return tasks.ownerBlockers(context.identity().id());
    }

    /**
     * Closes real registrations and removes old recovery closures after the owner drains. / 所有者排空后关闭实际登记并移除旧恢复闭包。
     * @throws Exception if resources remain or cleanup fails / 仍有资源或清理失败时
     */
    @Override
    public synchronized void close() throws Exception {
        if (closed)
            return;
        seal();
        if (!blockers().isEmpty())
            throw new IllegalStateException("Mod resources still require cleanup");
        while (closedContributions < contributions.size()) {
            contributions.get(contributions.size() - closedContributions - 1).close();
            closedContributions++;
        }
        tasks.revokeOwnerServices(context.identity().id(), context.identity().version());
        closed = true;
    }

    /** A concrete host registration group with publication and actual cleanup. / 具有发布及实际清理操作的具体宿主登记组。 */
    public interface Contribution extends AutoCloseable {
        /**
         * Publishes the already prepared contribution group. / 发布已准备的扩展组。
         * @throws Exception if publication fails / 发布失败时
         */
        void publish() throws Exception;

        /**
         * Closes actual registered resources; failed close remains retryable. / 关闭实际登记资源；关闭失败保持可重试。
         * @throws Exception if any resource remains / 仍有任一资源时
         */
        @Override
        void close() throws Exception;
    }
}
