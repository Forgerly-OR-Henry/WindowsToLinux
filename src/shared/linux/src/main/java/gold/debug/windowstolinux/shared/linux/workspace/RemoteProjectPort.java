package gold.debug.windowstolinux.shared.linux.workspace;

import java.time.Duration;

import gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;

/** Task-bound remote source and isolated output operations, without build policy. / 不含构建策略的任务绑定远端源码及隔离输出操作。 */
public interface RemoteProjectPort {
    /** Protects uploaded source and opens a separate writable output directory. / 保护上传源码并打开独立可写输出目录。
     * @param task owning task / 所属任务
     * @param workspace uploaded candidate / 上传候选项
     * @return actual remote source digest / 实际远端源码摘要
     * @throws Exception when ownership or preparation fails / 归属或准备失败时
     */
    String open(String task, RemoteWorkspace workspace) throws Exception;

    /** Opens the backend explicitly selected by the delivery contract. / 打开交付契约显式选择的后端。
     * @param task task identity / 任务身份
     * @param workspace uploaded candidate / 上传候选
     * @param engine execution backend / 执行后端
     * @return remote source revision / 远端源码修订
     * @throws Exception when preparation fails / 准备失败时
     */
    default String open(String task, RemoteWorkspace workspace, String engine) throws Exception {
        if (!"ordinary".equals(engine))
            throw new UnsupportedOperationException("container sandbox unavailable");
        return open(task, workspace);
    }

    /** Reads bounded evidence from the protected Linux source copy. / 从受保护 Linux 源码副本读取有界证据。
     * @param task task identity / 任务身份
     * @param workspace candidate / 候选项
     * @param revision exact source revision / 精确源码修订
     * @param path relative file / 相对文件
     * @param offset zero-based line offset / 从零开始的行偏移
     * @param limit line budget / 行预算
     * @return safe text and file digest / 安全文本及文件摘要
     * @throws Exception on invalid source evidence / 源码证据无效时
     */
    String read(String task, RemoteWorkspace workspace, String revision, String path, int offset, int limit)
            throws Exception;

    /** Runs an exact script under an unprivileged sandbox identity. / 在无特权沙箱身份下运行精确脚本。
     * @param task task identity / 任务身份
     * @param workspace candidate / 候选项
     * @param revision exact source revision / 精确源码修订
     * @param script model-generated script / 模型生成脚本
     * @param timeout bounded timeout / 有界超时
     * @param outputLimit output byte limit / 输出字节上限
     * @return actual exit or unknown outcome / 实际退出或未知结果
     * @throws Exception on transport failure / 传输失败时
     */
    RemoteCommandResult command(String task, RemoteWorkspace workspace, String revision, String script,
            Duration timeout, long outputLimit) throws Exception;

    /** Applies only a separately reviewed revision-bound patch. / 仅应用经过单独审核且绑定修订的补丁。
     * @param task task identity / 任务身份
     * @param workspace candidate / 候选项
     * @param patch exact source diff / 精确源码差异
     * @param approval exact review digest / 精确审核摘要
     * @return new remote source revision / 新远端源码修订
     * @throws Exception on conflicts or unknown results / 冲突或结果未知时
     */
    String patch(String task, RemoteWorkspace workspace, RemoteSourcePatch patch, String approval) throws Exception;

    /** Seals the current output only against the current source revision. / 仅针对当前源码修订封存当前输出。
     * @param task task identity / 任务身份
     * @param workspace candidate / 候选项
     * @param revision required source revision / 所需源码修订
     * @return verified output digest / 已验证输出摘要
     * @throws Exception when provenance is unavailable / 来源无法验证时
     */
    String seal(String task, RemoteWorkspace workspace, String revision) throws Exception;
}
