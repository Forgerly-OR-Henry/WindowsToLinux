package gold.debug.windowstolinux.app.file;

import java.io.*;
import java.util.*;
import gold.debug.windowstolinux.appapi.file.*;

/** Host primitives only; the engine owns approval, linking and directory rules. / 主机原语，审批、链接及目录规则归统一引擎。 */
public interface FileBackend extends AutoCloseable {
    /** Observes the final object without following it. / 不跟随末段对象地观测。 */
    Optional<FileInfo> stat(FilePath path) throws IOException;
    /** Observes an ancestor for traversal; ordinary directory identities are not consumed as effect evidence. / 观测遍历祖先，普通目录身份不作为副作用依据。
     * @param path ancestor position / 祖先位置
     * @return ancestor kind and exact link identity when present / 祖先类型及存在时的精确链接身份
     * @throws IOException when traversal attributes cannot be read / 无法读取遍历属性时
     */
    default Optional<FileInfo> ancestor(FilePath path) throws IOException { return stat(path); }
    /** Lists bounded immediate child names without following links. / 有界列举直接子项而不跟随链接。 */
    List<String> list(FilePath path, int limit) throws IOException;
    /** Opens existing regular content. / 打开既有普通文件内容。 */
    InputStream read(FilePath path) throws IOException;
    /** Creates a new, exclusively owned staging file. / 排他创建新暂存文件。 */
    OutputStream create(FilePath path) throws IOException;
    /** Creates one new directory. / 创建一个新目录。 */
    void mkdir(FilePath path) throws IOException;
    /** Creates a link with an explicit target string. / 以明确目标字符串创建链接。 */
    void link(FilePath path, String target) throws IOException;
    /** Atomically publishes a staged object when supported, without predeleting the old target. / 支持时原子发布暂存对象，不预删旧目标。 */
    void publish(FilePath staging, FilePath target, boolean replace) throws IOException;
    /** Removes one object; directory must be empty and links are never followed. / 删除单个对象，目录须为空且不跟随链接。 */
    void remove(FilePath path) throws IOException;
    /** Reports whether this location supports recoverable trash. / 判断当前位置是否支持可恢复回收。 */
    boolean canRecycle(FilePath path) throws IOException;
    /** Moves the object into recoverable trash, preserving restoration information. / 移入可恢复回收区并保留恢复信息。 */
    void recycle(FilePath path) throws IOException;
    /** Closes only this adapter's owned channel, never an unrelated SSH session. / 仅关闭适配器所属通道，不关闭无关 SSH 会话。 */
    @Override void close() throws IOException;
}
