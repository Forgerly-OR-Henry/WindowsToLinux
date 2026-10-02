package gold.debug.windowstolinux.app.file;

import java.io.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.file.*;

/** Host primitives only; the engine owns approval, linking and directory rules. / 主机原语，审批、链接及目录规则归统一引擎。 */
public interface FileBackend extends AutoCloseable {
    /** Observes the final object without following it. / 不跟随末段对象地观测。
     * @param path exact host-qualified position / 精确带主机位置
     * @return actual no-follow observation or proved absence / 实际不跟随观测或已证明缺失
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    Optional<FileInfo> stat(FilePath path) throws IOException;

    /** Observes an ancestor for traversal; ordinary directory identities are not consumed as effect evidence. / 观测遍历祖先，普通目录身份不作为副作用依据。
     * @param path ancestor position / 祖先位置
     * @return ancestor kind and exact link identity when present / 祖先类型及存在时的精确链接身份
     * @throws IOException when traversal attributes cannot be read / 无法读取遍历属性时
     */
    default Optional<FileInfo> ancestor(FilePath path) throws IOException {
        return stat(path);
    }

    /** Lists bounded immediate child names without following links. / 有界列举直接子项而不跟随链接。
     * @param path exact host-qualified position / 精确带主机位置
     * @param limit maximum accepted complete immediate entry count, from one through 100000 / 一到 100000 的完整直接子项数量上限
     * @return immutable sorted complete immediate names within the hard bound / 硬界限内不可变排序完整直接名称
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    List<String> list(FilePath path, int limit) throws IOException;

    /** Opens existing regular content. / 打开既有普通文件内容。
     * @param path exact host-qualified position / 精确带主机位置
     * @return actual no-follow content stream that the caller must close / 调用方必须关闭的实际不跟随内容流
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    InputStream read(FilePath path) throws IOException;

    /** Creates a new, exclusively owned staging file. / 排他创建新暂存文件。
     * @param path exact host-qualified position / 精确带主机位置
     * @return exclusive creation stream that the caller must close / 调用方必须关闭的排他创建流
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    OutputStream create(FilePath path) throws IOException;

    /** Creates one new directory. / 创建一个新目录。
     * @param path exact host-qualified position / 精确带主机位置
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    void mkdir(FilePath path) throws IOException;

    /** Creates a link with an explicit target string. / 以明确目标字符串创建链接。
     * @param path exact host-qualified position / 精确带主机位置
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    void link(FilePath path, String target) throws IOException;

    /** Stages an observed Link while preserving a locally supported native link kind. / 暂存已观测 Link，并保留本机支持的原生链接类型。
     * @param source verified no-follow original link / 已验证不跟随原链接
     * @param path exclusively owned new link position / 排他所属新链接位置
     * @param target approved target text at the new position / 新位置获批指向文本
     * @throws IOException if the link kind or target cannot be represented / 链接类型或指向无法表达时 */
    default void stageLink(FileInfo source, FilePath path, String target) throws IOException {
        link(path, target);
    }

    /** Atomically publishes a staged object when supported, without predeleting the old target. / 支持时原子发布暂存对象，不预删旧目标。
     * @param staging exclusively owned staged object / 排他所属暂存对象
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param replace whether exact replacement is approved / 是否批准精确替换
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    void publish(FilePath staging, FilePath target, boolean replace) throws IOException;

    /** Removes one object; directory must be empty and links are never followed. / 删除单个对象，目录须为空且不跟随链接。
     * @param path exact host-qualified position / 精确带主机位置
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    void remove(FilePath path) throws IOException;

    /** Reports whether this location supports recoverable trash. / 判断当前位置是否支持可恢复回收。
     * @param path exact host-qualified position / 精确带主机位置
     * @return whether backend-specific preflight admits a recoverable attempt; completion still requires a receipt / 后端特定预检是否允许可恢复尝试，完成仍须回执
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    boolean canRecycle(FilePath path) throws IOException;

    /** Moves the object into recoverable trash, preserving restoration information. / 移入可恢复回收区并保留恢复信息。
     * @param path exact host-qualified position / 精确带主机位置
     * @return actual recoverable object and required metadata identities / 实际可恢复对象及所需元数据身份
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    FileRecycleReceipt recycle(FilePath path) throws IOException;

    /** Checks exact original-path restoration metadata without moving the recycled object. / 不移动回收对象地核对精确原路径恢复元数据。
     * @param original original actual removed object position / 原实际被移除对象位置
     * @param receipt already observed recovery object and metadata / 已观测恢复对象及元数据
     * @return whether required metadata still describes the exact original object position / 所需元数据是否仍描述精确原对象位置
     * @throws IOException if metadata is unreadable or exceeds its bound / 元数据无法读取或超过界限时 */
    default boolean recoveryInformationMatches(FilePath original, FileRecycleReceipt receipt) throws IOException {
        if (receipt.information().isEmpty()) return false;
        String text = new String(new FileEngine(this).read(receipt.information().orElseThrow(), 65536, () -> false), java.nio.charset.StandardCharsets.UTF_8);
        var paths = text.lines().filter(line -> line.startsWith("Path=")).toList();
        return text.startsWith("[Trash Info]\n") && paths.size() == 1 && java.net.URLDecoder.decode(paths.getFirst().substring(5), java.nio.charset.StandardCharsets.UTF_8).equals(original.path());
    }

    /** Releases adapter-owned resources; the managed host retains SSH session lifecycle responsibility. / 释放适配器所属资源，受管宿主仍负责 SSH 会话生命周期。
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    @Override
    void close() throws IOException;
}
