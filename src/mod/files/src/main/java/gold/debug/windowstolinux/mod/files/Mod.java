package gold.debug.windowstolinux.mod.files;

import java.util.ArrayList;
import java.util.List;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.mod.ModEntrypoint;
import gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode;
import gold.debug.windowstolinux.appapi.mod.lifecycle.StopContext;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.mod.files.service.FilesRecovery;
import gold.debug.windowstolinux.mod.files.service.FilesService;
import gold.debug.windowstolinux.mod.files.ui.FilesWorkspace;

/** Optional file workspace and original file-operation recovery lifecycle. / 可选文件工作区及原文件操作核对生命周期。 */
public final class Mod implements ModEntrypoint {
    /** Registrations whose actual close has not been confirmed. / 尚未确认实际关闭的登记。 */
    private final List<AutoCloseable> registrations = new ArrayList<>();

    /** Original caller-bound service for this activation. / 本次启用的原调用方绑定服务。 */
    private FilesService service;

    /** Creates an inactive file Mod without file or database effects. / 创建无文件或数据库副作用的未启用文件 Mod。 */
    public Mod() {
    }

    /** Registers the actual workspace and original-task recovery without a business Schema. / 不使用业务 Schema 地登记实际工作区与原任务核对。
     * @param context authentic host-owned activation / 真实宿主所属启用上下文
     * @throws Exception if registration or actual cleanup fails / 登记或实际清理失败时 */
    @Override
    public void start(ModContext context) throws Exception {
        if (service != null || !registrations.isEmpty()) throw new IllegalStateException("Files is already bound");
        var candidate = new FilesService(context);
        service = candidate;
        try {
            for (String type : FilesRecovery.taskTypes())
                registrations.add(context.tasks().registerRecovery(type, candidate.recovery()));
            if (context.launchMode() == ModLaunchMode.NORMAL)
                registrations.add(context.ui().register(new PageSpec("workspace", "files.workspace.title", "文件 / Files", false),
                    () -> new FilesWorkspace(candidate)));
        } catch (Exception failure) {
            closeRegistrations(failure);
            if (registrations.isEmpty()) service = null;
            throw failure;
        }
    }

    /** Withdraws only confirmed registrations, retaining failed closes for retry. / 仅撤回确认关闭的登记，保留失败关闭供重试。
     * @param context original platform stop reason / 原平台停止原因
     * @throws Exception if any owned registration remains open / 任一所属登记仍未关闭时 */
    @Override
    public void stop(StopContext context) throws Exception {
        var failure = closeRegistrations(null);
        if (failure != null) throw failure;
        service = null;
    }

    /** Closes registrations in reverse order without forgetting uncertain occupancy. / 逆序关闭登记，不遗忘不确定的占用。
     * @param failure original failure, or null for normal stopping / 原失败，正常停止为空
     * @return original or first close failure, null after confirmed cleanup / 原失败或首次关闭失败，确认清理后为空 */
    private Exception closeRegistrations(Exception failure) {
        for (int index = registrations.size() - 1; index >= 0; index--)
            try {
                registrations.get(index).close();
                registrations.remove(index);
            } catch (Exception unclosed) {
                if (failure == null) failure = unclosed;
                else failure.addSuppressed(unclosed);
            }
        return failure;
    }
}
