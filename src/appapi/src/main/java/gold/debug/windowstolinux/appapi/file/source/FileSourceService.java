package gold.debug.windowstolinux.appapi.file.source;

import gold.debug.windowstolinux.appapi.file.source.FileContent;
import gold.debug.windowstolinux.appapi.file.source.FileEntry;
import gold.debug.windowstolinux.appapi.file.source.FileReadRequest;
import gold.debug.windowstolinux.appapi.file.source.FileSelection;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Authorized source selection and bounded reads. / 经授权的来源选取与有界读取。 */
public interface FileSourceService {

    /** Registers an already known local directory under the same task and identity checks. / 按相同任务及身份检查登记已知本机目录。
     * @param path local directory to bind to the active task after physical identity validation / 经物理身份校验后绑定活动任务的本机目录
     * @param task authentic original executing task / 真实原执行任务
     * @return task-owned directory selection whose identity is rechecked when used / 使用时重新核对身份的任务所属目录选择
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileSelection selectDirectory(java.nio.file.Path path, TaskContext task) throws ApiException;

    /** Returns the original selected directory's canonical absolute file URI as metadata. / 将原选定目录的规范绝对文件URI作为元数据返回。
     * @param selection original user-approved directory, never a workspace selection / 原用户批准的目录，不得为工作目录选择
     * @param task original active task / 原活动任务
     * @return original location metadata, not a reusable authorization / 原位置元数据，不是可重复使用的授权
     * @throws ApiException if ownership or physical root identity cannot be confirmed / 无法确认归属或物理根身份时 */
    java.net.URI location(FileSelection selection, TaskContext task) throws ApiException;

    /** Lists one complete bounded directory without recursively reading its children. / 列出一个完整有界目录，不递归读取其子项。
     * @param selection original user-authorized selection / 原用户授权选择
     * @param relativeDirectory slash-separated relative directory, empty for the selected root / 斜线分隔的相对目录，空串表示所选根
     * @param maxEntries maximum immediate children from one to one hundred thousand / 一到十万个直接子项的上限
     * @param task original active task / 原活动任务
     * @return immutable sorted immediate entries; link entries retain their own metadata / 不可变且排序的直接子项，链接子项保留自身元数据
     * @throws ApiException if authorization, boundaries, capacity or actual reading fails / 授权、边界、容量或实际读取失败时 */
    java.util.List<FileEntry> list(FileSelection selection, String relativeDirectory, int maxEntries,
                                   TaskContext task) throws ApiException;

    /**
     * Obtains an explicit user selection bound to the current task. / 取得绑定当前任务的明确用户选择。
     * @param title human-readable task title / 可读的任务标题
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return task-owned directory selection whose identity is rechecked when used / 使用时重新核对身份的任务所属目录选择
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    FileSelection selectDirectory(String title, TaskContext task) throws ApiException;

    /**
     * Reads copied bytes within the selected root and requested limit. / 在选定根目录及请求限制内读取独立字节。
     * @param selection opaque user-authorized source selection / 不透明的用户授权来源选择
     * @param request immutable capability request / 不可变的能力请求
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return independent bounded bytes and the actual selected file metadata / 独立有界字节及实际选定文件元数据
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    FileContent read(FileSelection selection, FileReadRequest request, TaskContext task) throws ApiException;
}
