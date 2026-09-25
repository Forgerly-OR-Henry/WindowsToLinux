package gold.debug.windowstolinux.shared.source.browse;

import java.util.List;

/** Read-only access to one frozen source snapshot. / 对单个冻结源码快照提供只读访问。 */
public interface SourceReadPort {
    /** Returns the immutable source identity. / 返回不可变源码身份。
     * @return snapshot revision / 快照修订
     */
    String revision();

    /** Verifies that the snapshot remains unchanged. / 验证快照仍未变化。
     * @throws Exception when source identity changed / 源码身份变化时
     */
    void verify() throws Exception;

    /** Lists admitted directory members. / 列出准入目录成员。
     * @param directory relative directory / 相对目录
     * @param offset page offset / 分页偏移
     * @param limit page size / 分页大小
     * @return admitted member names / 准入成员名称
     * @throws Exception when the request violates the source boundary / 请求违反源码边界时
     */
    List<String> list(String directory, int offset, int limit) throws Exception;

    /** Reads numbered, sanitized text lines. / 读取带行号的脱敏文本行。
     * @param path relative file / 相对文件
     * @param offset line offset / 行偏移
     * @param limit line limit / 行数上限
     * @return observed text lines / 实际观察文本行
     * @throws Exception when the file cannot be read safely / 文件无法安全读取时
     */
    List<String> read(String path, int offset, int limit) throws Exception;

    /** Searches admitted text files. / 搜索准入文本文件。
     * @param query literal query / 字面查询
     * @param offset manifest offset / 清单偏移
     * @param limit file limit / 文件数上限
     * @return numbered source matches / 带行号的源码匹配
     * @throws Exception when the search violates the source boundary / 搜索违反源码边界时
     */
    List<String> search(String query, int offset, int limit) throws Exception;
}
