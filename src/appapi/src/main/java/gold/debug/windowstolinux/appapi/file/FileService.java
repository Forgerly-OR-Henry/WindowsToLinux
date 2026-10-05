package gold.debug.windowstolinux.appapi.file;

import gold.debug.windowstolinux.appapi.file.query.FileQueryService;
import gold.debug.windowstolinux.appapi.file.action.FileActionService;
import gold.debug.windowstolinux.appapi.file.source.FileSourceService;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshotService;
import gold.debug.windowstolinux.appapi.file.work.FileWorkspaceService;
import gold.debug.windowstolinux.appapi.file.material.FileMaterialService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Activation-bound file entry; concrete operations still require the original task authority. / 启用绑定文件入口；具体操作仍要求原任务授权。 */
public interface FileService {

    /** Returns the original activation-bound capability without performing I/O. / 返回原启用绑定能力，不执行读写。
     * @return original queries capability / 原queries能力 */
    FileQueryService queries();

    /** Returns the original activation-bound capability without performing I/O. / 返回原启用绑定能力，不执行读写。
     * @return original actions capability / 原actions能力 */
    FileActionService actions();

    /** Returns the original activation-bound capability without performing I/O. / 返回原启用绑定能力，不执行读写。
     * @return original sources capability / 原sources能力 */
    FileSourceService sources();

    /** Returns the original activation-bound capability without performing I/O. / 返回原启用绑定能力，不执行读写。
     * @return original snapshots capability / 原snapshots能力 */
    FileSnapshotService snapshots();

    /** Returns the original activation-bound capability without performing I/O. / 返回原启用绑定能力，不执行读写。
     * @return original workspaces capability / 原workspaces能力 */
    FileWorkspaceService workspaces();

    /** Returns original-task material custody without acquiring resources. / 返回原任务素材接管能力，不取得资源。
     * @return activation-bound material capability / 启用绑定素材能力 */
    FileMaterialService materials();

    /** Releases only physically verified work owned by this original task. / 仅释放此原任务物理已核实的工作素材。
     * @param task original task or recovery context / 原任务或恢复上下文
     * @throws ApiException if physical cleanup or ownership remains uncertain / 物理清理或归属仍不确定时 */
    void closeTaskResources(TaskContext task) throws ApiException;
}
