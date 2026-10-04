package gold.debug.windowstolinux.appapi.file.action;

import gold.debug.windowstolinux.appapi.file.action.FileChoice;
import gold.debug.windowstolinux.appapi.file.action.FileOperation;
import gold.debug.windowstolinux.appapi.file.action.FileReport;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Task-authorized file effects and original-operation recovery. / 任务授权文件副作用与原操作恢复。 */
public interface FileActionService {

    /** Creates a new empty file after operation authorization. / 操作授权后创建新空文件。
     * @param path exact host-qualified position / 精确带主机位置
     * @param task authentic original executing task / 真实原执行任务
     * @return actual creation outcome and the destination used, including any failure or uncertainty / 真实创建结果及使用的目标位置，包含失败或不确定性
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport createFile(FilePath path, TaskContext task) throws ApiException;

    /** Creates a new directory after operation authorization. / 操作授权后创建新目录。
     * @param path exact host-qualified position / 精确带主机位置
     * @param task authentic original executing task / 真实原执行任务
     * @return actual directory creation outcome and its destination / 真实目录创建结果及其目标位置
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport createDirectory(FilePath path, TaskContext task) throws ApiException;

    /** Writes bounded generated content, asking on replacement. / 写入有界生成内容，替换时询问。
     * @param path exact host-qualified position / 精确带主机位置
     * @param content generated content copied before publication, at most sixty-four MiB / 发布前独立复制的生成内容，至多六十四 MiB
     * @param task authentic original executing task / 真实原执行任务
     * @return actual publication outcome and the destination chosen after collision handling / 真实发布结果及处理冲突后选择的目标位置
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport writeFile(FilePath path, byte[] content, TaskContext task) throws ApiException;

    /** Creates and registers a link with an explicit target. / 以明确指向创建并登记链接。
     * @param path exact host-qualified position / 精确带主机位置
     * @param target original absolute or relative link target text; absence of its target is allowed / 原始绝对或相对链接指向文本，允许目标不存在
     * @param task authentic original executing task / 真实原执行任务
     * @return actual link creation outcome and the newly registered link position / 真实链接创建结果及新登记的链接位置
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport createLink(FilePath path, String target, TaskContext task) throws ApiException;

    /** Registers an observed link without granting modification authority. / 登记观测链接，不授予修改权限。
     * @param path exact host-qualified position / 精确带主机位置
     * @param task authentic original executing task / 真实原执行任务
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    void registerLink(FilePath path, TaskContext task) throws ApiException;

    /** Removes this caller's registration, preserving the actual link. / 解除此调用方登记，保留实际链接。
     * @param path exact host-qualified position / 精确带主机位置
     * @param task authentic original executing task / 真实原执行任务
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    void unregisterLink(FilePath path, TaskContext task) throws ApiException;

    /** Copies actual File content with interactive collisions. / 交互处理冲突并复制实际 File 内容。
     * @param from original source position / 原来源位置
     * @param to requested destination position / 请求目标位置
     * @param task authentic original executing task / 真实原执行任务
     * @return per-object results and actual destination mapping; skipped required items make the aggregate fail / 逐对象结果及实际目标映射，跳过必需项会使汇总失败
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport copyFile(FilePath from, FilePath to, TaskContext task) throws ApiException;

    /** Copies actual File content with separate explicit collision layers. / 按分开的明确冲突层复制实际 File 内容。
     * @param from original source position / 原来源位置
     * @param to requested destination position / 请求目标位置
     * @param task authentic original executing task / 真实原执行任务
     * @param choice separate outer-directory and inner file/link collision choices; empty choices remain interactive / 分开的外层目录及内层文件或链接冲突策略，未指定的层级仍交互
     * @return per-object results and actual destination mapping; skipped required items make the aggregate fail / 逐对象结果及实际目标映射，跳过必需项会使汇总失败
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport copyFile(FilePath from, FilePath to, TaskContext task, FileChoice choice) throws ApiException;

    /** Copies the Link itself. / 复制 Link 自身。
     * @param from original source position / 原来源位置
     * @param to requested destination position / 请求目标位置
     * @param task authentic original executing task / 真实原执行任务
     * @return result for the copied link itself and its actual chosen destination / 链接自身的复制结果及实际选择的目标位置
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport copyLink(FilePath from, FilePath to, TaskContext task) throws ApiException;

    /** Copies the Link itself with an explicit collision choice. / 按明确冲突选择复制 Link 自身。
     * @param from original source position / 原来源位置
     * @param to requested destination position / 请求目标位置
     * @param task authentic original executing task / 真实原执行任务
     * @param choice separate outer-directory and inner file/link collision choices; empty choices remain interactive / 分开的外层目录及内层文件或链接冲突策略，未指定的层级仍交互
     * @return result for the copied link itself and its actual chosen destination / 链接自身的复制结果及实际选择的目标位置
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport copyLink(FilePath from, FilePath to, TaskContext task, FileChoice choice) throws ApiException;

    /** Moves the actual File, removing only verified completed sources. / 移动实际 File，仅清理已验证完成的源。
     * @param from original source position / 原来源位置
     * @param to requested destination position / 请求目标位置
     * @param task authentic original executing task / 真实原执行任务
     * @return per-object results identifying verified publications, source removals and unresolved effects / 标识已核实发布、源清理及未确定副作用的逐对象结果
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport moveFile(FilePath from, FilePath to, TaskContext task) throws ApiException;

    /** Moves the actual File using separate collision layers. / 按分开的冲突层移动实际 File。
     * @param from original source position / 原来源位置
     * @param to requested destination position / 请求目标位置
     * @param task authentic original executing task / 真实原执行任务
     * @param choice separate outer-directory and inner file/link collision choices; empty choices remain interactive / 分开的外层目录及内层文件或链接冲突策略，未指定的层级仍交互
     * @return per-object results identifying verified publications, source removals and unresolved effects / 标识已核实发布、源清理及未确定副作用的逐对象结果
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport moveFile(FilePath from, FilePath to, TaskContext task, FileChoice choice) throws ApiException;

    /** Moves the Link itself while preserving its original target. / 移动 Link 自身并保持原目标。
     * @param from original source position / 原来源位置
     * @param to requested destination position / 请求目标位置
     * @param task authentic original executing task / 真实原执行任务
     * @return result for moving the link itself while retaining its original target / 保持原始指向地移动链接自身的结果
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport moveLink(FilePath from, FilePath to, TaskContext task) throws ApiException;

    /** Moves the Link itself with an explicit collision choice. / 按明确冲突选择移动 Link 自身。
     * @param from original source position / 原来源位置
     * @param to requested destination position / 请求目标位置
     * @param task authentic original executing task / 真实原执行任务
     * @param choice separate outer-directory and inner file/link collision choices; empty choices remain interactive / 分开的外层目录及内层文件或链接冲突策略，未指定的层级仍交互
     * @return result for moving the link itself while retaining its original target / 保持原始指向地移动链接自身的结果
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport moveLink(FilePath from, FilePath to, TaskContext task, FileChoice choice) throws ApiException;

    /** Renames the actual File via the same move contract. / 以相同移动合同重命名实际 File。
     * @param from original source position / 原来源位置
     * @param name single sibling name without separators, . or ..; local names must have no root or drive qualifier / 不含分隔符且不为 . 或 .. 的同级名称；本机名称不得带根或盘符
     * @param task authentic original executing task / 真实原执行任务
     * @return the same actual publication and cleanup results as moving the File to the sibling name / 将 File 移至同级新名称时相同的真实发布及清理结果
     * @throws ApiException if input is invalid (INVALID_INPUT), or task or host admission, exact authorization, identity, I/O or durable recording fails / 输入非法（INVALID_INPUT），或任务及主机准入、精确授权、身份、I/O、持久记录失败时
     */
    FileReport renameFile(FilePath from, String name, TaskContext task) throws ApiException;

    /** Renames the Link itself via the same move contract. / 以相同移动合同重命名 Link 自身。
     * @param from original source position / 原来源位置
     * @param name single sibling name without separators, . or ..; local names must have no root or drive qualifier / 不含分隔符且不为 . 或 .. 的同级名称；本机名称不得带根或盘符
     * @param task authentic original executing task / 真实原执行任务
     * @return the same link-preserving results as moving the Link to the sibling name / 将 Link 移至同级新名称时相同的链接保留结果
     * @throws ApiException if input is invalid (INVALID_INPUT), or task or host admission, exact authorization, identity, I/O or durable recording fails / 输入非法（INVALID_INPUT），或任务及主机准入、精确授权、身份、I/O、持久记录失败时
     */
    FileReport renameLink(FilePath from, String name, TaskContext task) throws ApiException;

    /** Always confirms ordinary deletion; refusal waits for explicit continuation. / 常规删除始终确认，拒绝后等待明确继续。
     * @param path exact host-qualified position / 精确带主机位置
     * @param task authentic original executing task / 真实原执行任务
     * @return actual recycle or permanent-deletion results for the resolved object / 已解析对象的真实回收或永久删除结果
     * @throws ApiException if authorization is invalid, cancellation stops waiting, the object changes or deletion remains unproved / 授权无效、取消结束等待、对象变化或删除无法证实时
     */
    FileReport deleteFile(FilePath path, TaskContext task) throws ApiException;

    /** Consumes an exact independently approved platform deletion permit without another confirmation. / 消费精确独立批准的平台删除许可，不重复确认。
     * @param path exact host-qualified position / 精确带主机位置
     * @param task authentic original executing task / 真实原执行任务
     * @param permit genuine single-use deletion grant bound to this caller, task, observed object, link semantics and recovery effect / 绑定此调用方、任务、观测对象、链接语义及恢复效果的真实单次删除许可
     * @return actual recycle or permanent-deletion results for the resolved object / 已解析对象的真实回收或永久删除结果
     * @throws ApiException if authorization is invalid, cancellation stops waiting, the object changes or deletion remains unproved / 授权无效、取消结束等待、对象变化或删除无法证实时
     */
    FileReport deleteFile(FilePath path, TaskContext task, gold.debug.windowstolinux.appapi.task.action.ApprovalGrant permit) throws ApiException;

    /** Confirms deletion of the Link itself. / 确认删除 Link 自身。
     * @param path exact host-qualified position / 精确带主机位置
     * @param task authentic original executing task / 真实原执行任务
     * @return actual recycle or permanent-deletion results for the link itself / 链接自身的真实回收或永久删除结果
     * @throws ApiException if authorization is invalid, cancellation stops waiting, the object changes or deletion remains unproved / 授权无效、取消结束等待、对象变化或删除无法证实时
     */
    FileReport deleteLink(FilePath path, TaskContext task) throws ApiException;

    /** Deletes only the Link covered by the genuine platform permit. / 仅删除真实平台许可覆盖的 Link。
     * @param path exact host-qualified position / 精确带主机位置
     * @param task authentic original executing task / 真实原执行任务
     * @param permit genuine single-use deletion grant bound to this caller, task, observed object, link semantics and recovery effect / 绑定此调用方、任务、观测对象、链接语义及恢复效果的真实单次删除许可
     * @return actual recycle or permanent-deletion results for the link itself / 链接自身的真实回收或永久删除结果
     * @throws ApiException if authorization is invalid, cancellation stops waiting, the object changes or deletion remains unproved / 授权无效、取消结束等待、对象变化或删除无法证实时
     */
    FileReport deleteLink(FilePath path, TaskContext task, gold.debug.windowstolinux.appapi.task.action.ApprovalGrant permit) throws ApiException;

    /** Uploads local File content to one task-authorized server. / 向单台任务授权服务器上传本机 File 内容。
     * @param local absolute current-account local source or destination, as indicated by transfer direction / 按传输方向指定的当前账户绝对本机来源或目标
     * @param remote absolute source or destination on the one server admitted to the task / 任务准入的单台服务器上的绝对来源或目标
     * @param task authentic original executing task / 真实原执行任务
     * @return per-object upload results preserving actual conflict destinations and transfer uncertainty / 保留实际冲突目标及传输不确定性的逐对象上传结果
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport uploadFile(FilePath local, FilePath remote, TaskContext task) throws ApiException;

    /** Uploads using explicit directory/file choices. / 按明确目录/文件策略上传。
     * @param local absolute current-account local source or destination, as indicated by transfer direction / 按传输方向指定的当前账户绝对本机来源或目标
     * @param remote absolute source or destination on the one server admitted to the task / 任务准入的单台服务器上的绝对来源或目标
     * @param task authentic original executing task / 真实原执行任务
     * @param choice separate outer-directory and inner file/link collision choices; empty choices remain interactive / 分开的外层目录及内层文件或链接冲突策略，未指定的层级仍交互
     * @return per-object upload results preserving actual conflict destinations and transfer uncertainty / 保留实际冲突目标及传输不确定性的逐对象上传结果
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport uploadFile(FilePath local, FilePath remote, TaskContext task, FileChoice choice) throws ApiException;

    /** Downloads File content from one task-authorized server. / 从单台任务授权服务器下载 File 内容。
     * @param remote absolute source or destination on the one server admitted to the task / 任务准入的单台服务器上的绝对来源或目标
     * @param local absolute current-account local source or destination, as indicated by transfer direction / 按传输方向指定的当前账户绝对本机来源或目标
     * @param task authentic original executing task / 真实原执行任务
     * @return per-object download results preserving actual conflict destinations and transfer uncertainty / 保留实际冲突目标及传输不确定性的逐对象下载结果
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport downloadFile(FilePath remote, FilePath local, TaskContext task) throws ApiException;

    /** Downloads using explicit directory/file choices. / 按明确目录/文件策略下载。
     * @param remote absolute source or destination on the one server admitted to the task / 任务准入的单台服务器上的绝对来源或目标
     * @param local absolute current-account local source or destination, as indicated by transfer direction / 按传输方向指定的当前账户绝对本机来源或目标
     * @param task authentic original executing task / 真实原执行任务
     * @param choice separate outer-directory and inner file/link collision choices; empty choices remain interactive / 分开的外层目录及内层文件或链接冲突策略，未指定的层级仍交互
     * @return per-object download results preserving actual conflict destinations and transfer uncertainty / 保留实际冲突目标及传输不确定性的逐对象下载结果
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport downloadFile(FilePath remote, FilePath local, TaskContext task, FileChoice choice) throws ApiException;

    /** Reads original task-scoped operation evidence without replay. / 读取原任务限定操作依据，不重放。
     * @param task authentic original executing task / 真实原执行任务
     * @return original operation records belonging to this caller and task, without effect replay / 属于此调用方及任务的原操作记录，不重放副作用
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    java.util.List<FileOperation> operations(TaskContext task) throws ApiException;

    /** Reconciles observable facts only, preserving unknown stages and old authorization expiry. / 仅核对可观测事实，保留未知阶段及旧授权失效。
     * @param operation original durable operation identity already belonging to this caller and task / 已属于此调用方及任务的原持久操作身份
     * @param task authentic original executing task / 真实原执行任务
     * @return current verification results; unproved effects remain UNKNOWN and no file operation is replayed / 当前核对结果，未证实的副作用保留 UNKNOWN 且不重放文件操作
     * @throws ApiException if task or host admission, exact authorization, identity checks, I/O or durable recording fails / 任务或主机准入、精确授权、身份检查、I/O 或持久记录失败时
     */
    FileReport checkOperation(java.util.UUID operation, TaskContext task) throws ApiException;

    /** Newly authorizes only unfinished original link maintenance after a proved move. / 已证明移动后仅对未完成原链接维护重新授权。
     * @param operation original move identity owned by the same caller and release / 同调用方及发布所属的原移动身份
     * @param task new ordinary authorized task or the current original ordinary callback; never a recovery callback / 新普通授权任务或当前原普通回调，不得为恢复回调
     * @return new link-only report whose durable record refers to the original operation and task / 持久记录关联原操作及任务的新仅链接报告
     * @throws ApiException if ownership, old link identity, moved target evidence, ordinary admission or fresh approval fails / 归属、旧链接身份、移动目标依据、普通准入或新审批失败时 */
    FileReport continueOperation(java.util.UUID operation, TaskContext task) throws ApiException;
}
