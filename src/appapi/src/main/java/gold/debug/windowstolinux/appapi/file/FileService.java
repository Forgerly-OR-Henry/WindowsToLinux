package gold.debug.windowstolinux.appapi.file;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskContext;

/**
 * User-selected bounded reads and owned immutable snapshots. / 用户选定来源的有界读取及受管不可变快照。
 */
public interface FileService {

    /** Registers an already known local directory under the same task and identity checks. / 按相同任务及身份检查登记已知本机目录。 */
    FileSelection selectDirectory(java.nio.file.Path path, TaskContext task) throws ApiException;
    /** Observes a File's actual object and resolution chain. / 观测 File 实际对象及解析链。 */
    FileInfo getFile(FilePath path, TaskContext task) throws ApiException;
    /** Observes a Link itself without reading target content. / 观测 Link 自身，不读取目标内容。 */
    FileInfo readLink(FilePath path, TaskContext task) throws ApiException;
    /** Reads bounded actual regular content. / 读取有界实际普通内容。 */
    byte[] readFile(FilePath path, int maxBytes, TaskContext task) throws ApiException;
    /** Lists sorted immediate entries without following their links. / 按序列举直接子项，不跟随其链接。 */
    java.util.List<FileInfo> list(FilePath directory, int limit, TaskContext task) throws ApiException;
    /** Lists bounded entries with explicit type and name filters. / 按明确类型及名称条件列举有界子项。 */
    java.util.List<FileInfo> list(FilePath directory, int limit, java.util.Optional<FileInfo.Kind> kind, String nameContains, TaskContext task) throws ApiException;
    /** Creates a new empty file after operation authorization. / 操作授权后创建新空文件。 */
    FileReport createFile(FilePath path, TaskContext task) throws ApiException;
    /** Creates a new directory after operation authorization. / 操作授权后创建新目录。 */
    FileReport createDirectory(FilePath path, TaskContext task) throws ApiException;
    /** Writes bounded generated content, asking on replacement. / 写入有界生成内容，替换时询问。 */
    FileReport writeFile(FilePath path, byte[] content, TaskContext task) throws ApiException;
    /** Creates and registers a link with an explicit target. / 以明确指向创建并登记链接。 */
    FileReport createLink(FilePath path, String target, TaskContext task) throws ApiException;
    /** Registers an observed link without granting modification authority. / 登记观测链接，不授予修改权限。 */
    void registerLink(FilePath path, TaskContext task) throws ApiException;
    /** Removes this caller's registration, preserving the actual link. / 解除此调用方登记，保留实际链接。 */
    void unregisterLink(FilePath path, TaskContext task) throws ApiException;
    /** Copies actual File content with interactive collisions. / 交互处理冲突并复制实际 File 内容。 */
    FileReport copyFile(FilePath from, FilePath to, TaskContext task) throws ApiException;
    /** Copies actual File content with separate explicit collision layers. / 按分开的明确冲突层复制实际 File 内容。 */
    FileReport copyFile(FilePath from, FilePath to, TaskContext task, FileChoice choice) throws ApiException;
    /** Copies the Link itself. / 复制 Link 自身。 */
    FileReport copyLink(FilePath from, FilePath to, TaskContext task) throws ApiException;
    /** Copies the Link itself with an explicit collision choice. / 按明确冲突选择复制 Link 自身。 */
    FileReport copyLink(FilePath from, FilePath to, TaskContext task, FileChoice choice) throws ApiException;
    /** Moves the actual File, removing only verified completed sources. / 移动实际 File，仅清理已验证完成的源。 */
    FileReport moveFile(FilePath from, FilePath to, TaskContext task) throws ApiException;
    /** Moves the actual File using separate collision layers. / 按分开的冲突层移动实际 File。 */
    FileReport moveFile(FilePath from, FilePath to, TaskContext task, FileChoice choice) throws ApiException;
    /** Moves the Link itself while preserving its original target. / 移动 Link 自身并保持原目标。 */
    FileReport moveLink(FilePath from, FilePath to, TaskContext task) throws ApiException;
    /** Moves the Link itself with an explicit collision choice. / 按明确冲突选择移动 Link 自身。 */
    FileReport moveLink(FilePath from, FilePath to, TaskContext task, FileChoice choice) throws ApiException;
    /** Renames the actual File via the same move contract. / 以相同移动合同重命名实际 File。 */
    FileReport renameFile(FilePath from, String name, TaskContext task) throws ApiException;
    /** Renames the Link itself via the same move contract. / 以相同移动合同重命名 Link 自身。 */
    FileReport renameLink(FilePath from, String name, TaskContext task) throws ApiException;
    /** Always confirms ordinary deletion; refusal waits for explicit continuation. / 常规删除始终确认，拒绝后等待明确继续。 */
    FileReport deleteFile(FilePath path, TaskContext task) throws ApiException;
    /** Consumes an exact independently approved platform deletion permit without another confirmation. / 消费精确独立批准的平台删除许可，不重复确认。 */
    FileReport deleteFile(FilePath path, TaskContext task, gold.debug.windowstolinux.appapi.task.ApprovalGrant permit) throws ApiException;
    /** Confirms deletion of the Link itself. / 确认删除 Link 自身。 */
    FileReport deleteLink(FilePath path, TaskContext task) throws ApiException;
    /** Deletes only the Link covered by the genuine platform permit. / 仅删除真实平台许可覆盖的 Link。 */
    FileReport deleteLink(FilePath path, TaskContext task, gold.debug.windowstolinux.appapi.task.ApprovalGrant permit) throws ApiException;
    /** Uploads local File content to one task-authorized server. / 向单台任务授权服务器上传本机 File 内容。 */
    FileReport uploadFile(FilePath local, FilePath remote, TaskContext task) throws ApiException;
    /** Uploads using explicit directory/file choices. / 按明确目录/文件策略上传。 */
    FileReport uploadFile(FilePath local, FilePath remote, TaskContext task, FileChoice choice) throws ApiException;
    /** Downloads File content from one task-authorized server. / 从单台任务授权服务器下载 File 内容。 */
    FileReport downloadFile(FilePath remote, FilePath local, TaskContext task) throws ApiException;
    /** Downloads using explicit directory/file choices. / 按明确目录/文件策略下载。 */
    FileReport downloadFile(FilePath remote, FilePath local, TaskContext task, FileChoice choice) throws ApiException;
    /** Reads original task-scoped operation evidence without replay. / 读取原任务限定操作依据，不重放。 */
    java.util.List<FileOperation> operations(TaskContext task) throws ApiException;
    /** Reconciles observable facts only, preserving unknown stages and old authorization expiry. / 仅核对可观测事实，保留未知阶段及旧授权失效。 */
    FileReport checkOperation(java.util.UUID operation, TaskContext task) throws ApiException;

    /** Returns the original selected directory's canonical absolute file URI as metadata. / 将原选定目录的规范绝对文件URI作为元数据返回。
     * @param selection original user-approved directory, never a workspace selection / 原用户批准的目录，不得为工作目录选择
     * @param task original active task / 原活动任务
     * @return original location metadata, not a reusable authorization / 原位置元数据，不是可重复使用的授权
     * @throws ApiException if ownership or physical root identity cannot be confirmed / 无法确认归属或物理根身份时 */
    java.net.URI location(FileSelection selection, TaskContext task) throws ApiException;

    /** Creates an empty task-owned workspace for source staging and generated material. / 为来源暂存及生成素材创建空的任务所属工作目录。
     * @param task authentic executing task; recovery cannot create new work / 真实执行任务，恢复不得创建新工作
     * @return owned writable handle requiring explicit close / 需要明确关闭的所属可写句柄
     * @throws ApiException if admission, creation or cleanup fails / 准入、创建或清理失败时 */
    FileWorkspace workspace(TaskContext task) throws ApiException;

    /** Creates one workspace using its previously persisted task-local identity. / 以预先持久化的任务内身份首次创建一个工作目录。
     * @param task original executing task / 原执行任务
     * @param workspaceId original preselected identity / 原预选身份
     * @return original writable workspace / 原可写工作目录
     * @throws ApiException if identity has been used or creation cannot be confirmed / 身份已使用或创建无法确认时 */
    FileWorkspace workspace(TaskContext task, java.util.UUID workspaceId) throws ApiException;

    /** Copies bounded evidence from the original workspace without granting writable access. / 从原工作目录复制有界依据，不授予写权限。
     * @param task original task or recovery context / 原任务或恢复上下文
     * @param workspaceId original workspace identity / 原工作目录身份
     * @param name one simple file name / 单个简单文件名
     * @param maxBytes maximum copied bytes / 最大复制字节数
     * @return copied bytes or absence / 独立字节或不存在
     * @throws ApiException if the original identity or physical file cannot be verified / 原身份或物理文件无法核实时 */
    java.util.Optional<byte[]> readWorkspaceEvidence(TaskContext task, java.util.UUID workspaceId, String name,
                                                     int maxBytes) throws ApiException;

    /** Releases only physically verified work owned by this original task. / 仅释放此原任务物理已核实的工作素材。
     * @param task original task or recovery context / 原任务或恢复上下文
     * @throws ApiException if physical cleanup or ownership remains uncertain / 物理清理或归属仍不确定时 */
    void closeTaskResources(TaskContext task) throws ApiException;

    /** Lists one complete bounded directory without recursively reading its children. / 列出一个完整有界目录，不递归读取其子项。
     * @param selection original user-authorized selection / 原用户授权选择
     * @param relativeDirectory slash-separated relative directory, empty for the selected root / 斜线分隔的相对目录，空串表示所选根
     * @param maxEntries maximum immediate children from one to one hundred thousand / 一到十万个直接子项的上限
     * @param task original active task / 原活动任务
     * @return immutable entries sorted by original relative path / 按原相对路径排序的不可变子项
     * @throws ApiException if authorization, boundaries, capacity or actual reading fails / 授权、边界、容量或实际读取失败时 */
    java.util.List<FileEntry> list(FileSelection selection, String relativeDirectory, int maxEntries,
                                              TaskContext task) throws ApiException;

    /**
     * Obtains an explicit user selection bound to the current task. / 取得绑定当前任务的明确用户选择。
     * @param title human-readable task title / 可读的任务标题
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    FileSelection selectDirectory(String title, TaskContext task) throws ApiException;

    /**
     * Reads copied bytes within the selected root and requested limit. / 在选定根目录及请求限制内读取独立字节。
     * @param selection opaque user-authorized source selection / 不透明的用户授权来源选择
     * @param request immutable capability request / 不可变的能力请求
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    FileContent read(FileSelection selection, FileReadRequest request, TaskContext task) throws ApiException;

    /**
     * Captures the current owned state or immutable selected source. / 取得当前所属状态或选定来源的不可变快照。
     * @param selection opaque user-authorized source selection / 不透明的用户授权来源选择
     * @param request immutable capability request / 不可变的能力请求
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    FileSnapshot snapshot(FileSelection selection, SnapshotRequest request, TaskContext task) throws ApiException;
}
