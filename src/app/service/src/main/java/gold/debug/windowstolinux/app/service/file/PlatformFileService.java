package gold.debug.windowstolinux.app.service.file;

import java.util.Objects;

import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.file.query.FileQueryService;
import gold.debug.windowstolinux.appapi.file.action.FileActionService;
import gold.debug.windowstolinux.appapi.file.source.FileSourceService;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshotService;
import gold.debug.windowstolinux.appapi.file.work.FileWorkspaceService;
import gold.debug.windowstolinux.appapi.file.material.FileMaterialService;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** Publishes the original file owner through responsibility interfaces. / 通过职责接口发布原文件所有者。 */
public final class PlatformFileService implements FileService {
    /** Original activation owner retained by the host. / 宿主持有的原启用所有者。 */
    private final PlatformFileAccess access;

    /** Borrows the activation owner without acquiring resources. / 借用启用所有者，不获取资源。
     * @param access original activation owner / 原启用所有者 */
    public PlatformFileService(PlatformFileAccess access) {
        this.access = Objects.requireNonNull(access);
    }

    /** Returns the original capability view. / 返回原能力视图。
     * @return original capability / 原能力 */
    @Override
    public FileQueryService queries() {
        return access;
    }

    /** Returns the original capability view. / 返回原能力视图。
     * @return original capability / 原能力 */
    @Override
    public FileActionService actions() {
        return access;
    }

    /** Returns the original capability view. / 返回原能力视图。
     * @return original capability / 原能力 */
    @Override
    public FileSourceService sources() {
        return access;
    }

    /** Returns the original capability view. / 返回原能力视图。
     * @return original capability / 原能力 */
    @Override
    public FileSnapshotService snapshots() {
        return access;
    }

    /** Returns the original capability view. / 返回原能力视图。
     * @return original capability / 原能力 */
    @Override
    public FileWorkspaceService workspaces() {
        return access;
    }

    /** Returns the original activation's material custody capability. / 返回原启用的素材接管能力。
     * @return exact activation-bound material capability / 精确启用绑定素材能力 */
    @Override
    public FileMaterialService materials() {
        return access.materials();
    }

    /** Reconciles only the original task resources. / 仅核对原任务资源。
     * @param task original task / 原任务
     * @throws ApiException if ownership or cleanup fails / 归属或清理失败时 */
    @Override
    public void closeTaskResources(TaskContext task) throws ApiException {
        access.closeTaskResources(task);
    }
}
