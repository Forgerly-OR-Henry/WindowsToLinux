package gold.debug.windowstolinux.shared.source.browse;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.shared.source.contract.validation.SourceBoundaryValidator;
import gold.debug.windowstolinux.shared.source.manifest.SourceEntry;
import gold.debug.windowstolinux.shared.source.snapshot.SourceSnapshotIdentity;

/** Read-only, paginated access to a frozen safe source manifest. / 对冻结安全源码清单提供只读分页访问。 */
public final class SourceBrowser implements SourceReadPort {
    /** Maximum bytes decoded from one text file. / 单个文本文件的最大解码字节数。 */
    private static final int MAX_FILE_BYTES = 262144;

    /** Validated snapshot directory. / 已验证快照目录。 */
    private final Path root;

    /** Stable admitted manifest. / 稳定准入清单。 */
    private final Map<String, SourceEntry> entries;

    /** Frozen snapshot identity. / 冻结快照身份。 */
    private final String revision;

    /** Source boundary checker. / 源码边界检查器。 */
    private final SourceBoundaryValidator validator = new SourceBoundaryValidator();

    /** Freezes a manifest without editing its source. / 冻结清单，不修改源码。
     * @param snapshot private snapshot root / 私有快照根目录
     * @throws Exception when the snapshot cannot be verified / 快照无法验证时
     */
    public SourceBrowser(Path snapshot) throws Exception {
        root = validator.validateSourceDirectory(snapshot);
        var manifest = validator.collect(root);
        if (manifest.entries().size() > 20000)
            throw new IOException("source manifest exceeds browsing budget");
        var indexed = new TreeMap<String, SourceEntry>();
        manifest.entries().forEach(entry -> indexed.put(entry.relativePath(), entry));
        entries = Collections.unmodifiableMap(indexed);
        revision = SourceSnapshotIdentity.digest(root);
    }

    /** Returns the content-bound revision. / 返回内容绑定修订。
     * @return snapshot digest / 快照摘要
     */
    public String revision() {
        return revision;
    }

    /** Checks that original frozen evidence still exists unchanged. / 检查原始冻结证据仍存在且未变化。
     * @throws Exception on mutation or boundary changes / 内容或边界变化时
     */
    public void verify() throws Exception {
        if (!revision.equals(SourceSnapshotIdentity.digest(root)))
            throw new SecurityException("frozen snapshot changed");
    }

    /** Lists immediate children with a stable numeric cursor. / 使用稳定数字游标列出直接子项。
     * @param directory relative directory, empty for root / 相对目录，根目录为空
     * @param offset zero-based page offset / 从零开始的分页偏移
     * @param limit bounded page size / 有界分页大小
     * @return names with directory suffixes / 带目录后缀的名称
     * @throws Exception on invalid paths or changed source / 路径无效或源码变化时
     */
    public List<String> list(String directory, int offset, int limit) throws Exception {
        page(offset, limit);
        verify();
        String prefix = directory.isEmpty() ? "" : relative(directory) + "/";
        var children = new TreeSet<String>();
        for (String name : entries.keySet())
            if (name.startsWith(prefix)) {
                String tail = name.substring(prefix.length());
                int slash = tail.indexOf('/');
                children.add(prefix + (slash < 0 ? tail : tail.substring(0, slash + 1)));
            }
        return children.stream().skip(offset).limit(limit).toList();
    }

    /** Reads a bounded page of numbered text lines, excluding secret content. / 读取有界编号文本行，排除秘密内容。
     * @param path admitted relative file / 准入相对文件
     * @param offset zero-based line offset / 从零开始的行偏移
     * @param limit requested line count / 请求行数
     * @return numbered redacted lines / 编号脱敏行
     * @throws Exception on invalid or non-text files / 文件无效或非文本时
     */
    public List<String> read(String path, int offset, int limit) throws Exception {
        page(offset, limit);
        verify();
        var lines = text(relative(path));
        var result = new ArrayList<String>();
        int chars = 0;
        for (int i = offset; i < lines.size() && result.size() < limit; i++) {
            String line = (i + 1) + ": " + safe(lines.get(i));
            if (chars + line.length() > 12000)
                break;
            result.add(line);
            chars += line.length();
        }
        return List.copyOf(result);
    }

    /** Searches literal text within a bounded manifest page. / 在有界清单分页中搜索字面文本。
     * @param query literal search text / 字面搜索文本
     * @param offset first manifest file index / 首个清单文件下标
     * @param limit files to inspect / 检查文件数
     * @return bounded matches with file and line identity / 含文件及行身份的有界匹配
     * @throws Exception when source boundaries change / 源码边界变化时
     */
    public List<String> search(String query, int offset, int limit) throws Exception {
        page(offset, limit);
        verify();
        if (query == null || query.isBlank() || query.length() > 256)
            throw new IllegalArgumentException("invalid query");
        var result = new ArrayList<String>();
        int chars = 0;
        for (var entry : entries.values().stream().skip(offset).limit(limit).toList()) {
            if (entry.byteCount() > MAX_FILE_BYTES)
                continue;
            List<String> lines;
            try {
                lines = text(entry.relativePath());
            } catch (java.nio.charset.CharacterCodingException binary) {
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = safe(lines.get(i));
                if (!line.contains(query))
                    continue;
                String match = entry.relativePath() + ":" + (i + 1) + ": " + line;
                if (chars + match.length() > 12000 || result.size() == 100)
                    return List.copyOf(result);
                result.add(match);
                chars += match.length();
            }
        }
        return List.copyOf(result);
    }

    /** Reads a regular manifest member with strict UTF-8 decoding. / 严格按 UTF-8 读取普通清单成员。
     * @param name normalized member name / 规范成员名称
     * @return decoded lines / 解码行
     * @throws IOException on invalid file content / 文件内容无效时
     */
    private List<String> text(String name) throws IOException {
        SourceEntry entry = entries.get(name);
        if (entry == null || entry.byteCount() > MAX_FILE_BYTES)
            throw new IOException("file not admitted or too large");
        validator.verifyUnchangedRegularFile(root, entry);
        byte[] bytes;
        try (var input = Files.newInputStream(entry.path(), LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (bytes.length != entry.byteCount())
            throw new IOException("source changed while reading");
        String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (value.indexOf('\0') >= 0)
            throw new java.nio.charset.CharacterCodingException();
        var lines = new ArrayList<String>();
        boolean privateBlock = false;
        for (String line : value.lines().toList()) {
            if (line.matches(".*-----BEGIN .*PRIVATE KEY-----.*"))
                privateBlock = true;
            lines.add(privateBlock ? "[credential content excluded]" : line);
            if (line.matches(".*-----END .*PRIVATE KEY-----.*"))
                privateBlock = false;
        }
        return List.copyOf(lines);
    }

    /** Rejects absolute paths, traversal and platform aliases. / 拒绝绝对路径、越界及平台别名。
     * @param value supplied path / 传入路径
     * @return unchanged safe path / 不变的安全路径
     */
    private static String relative(String value) {
        if (value == null || value.isEmpty() || value.length() > 500 || value.startsWith("/") || value.contains("\\")
                || value.contains(":") || value.chars().anyMatch(Character::isISOControl))
            throw new SecurityException("invalid source path");
        for (String part : value.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new SecurityException("source traversal rejected");
        return value;
    }

    /** Enforces bounded pagination. / 执行有界分页约束。
     * @param offset starting index / 起始下标
     * @param limit page size / 分页大小
     */
    private static void page(int offset, int limit) {
        if (offset < 0 || offset > 1000000 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("invalid source page");
    }

    /** Removes likely credential assignments before model access. / 模型访问前移除疑似凭据赋值。
     * @param line source line / 源码行
     * @return bounded nonsecret line / 有界非秘密行
     */
    private static String safe(String line) {
        if (line.matches("(?i).*(password|passwd|secret|token|api[_-]?key|private[_-]?key).*[:=].*")
                || line.contains("PRIVATE KEY") || line.matches(".*https?://[^ /]+:[^ /]+@.*"))
            return "[credential content excluded]";
        return line.length() <= 4000 ? line : line.substring(0, 4000) + " [line truncated]";
    }
}
