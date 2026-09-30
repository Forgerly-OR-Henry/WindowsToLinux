package gold.debug.windowstolinux.app.service.contract.spi;

import java.nio.file.Path;

import gold.debug.windowstolinux.appapi.task.CancellationToken;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;

/** Host-owned callback for original-task directory selection. / 原任务目录选择的宿主回调契约。 */
@FunctionalInterface
public interface SourceDirectoryPicker {
    /** Returns a user-confirmed directory after its owned dialog actually closes. / 所属对话框实际关闭后返回用户确认目录。
     * @param title supplied title / 提供标题
     * @param task original task snapshot / 原任务快照
     * @param cancellation cooperative cancellation / 协作取消
     * @return chosen directory or null on cancellation / 选定目录或取消时 null */
    Path select(String title, TaskSnapshot task, CancellationToken cancellation);
}
