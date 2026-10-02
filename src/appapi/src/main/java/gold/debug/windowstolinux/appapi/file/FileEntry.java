package gold.debug.windowstolinux.appapi.file;

import java.util.Objects;

/** One immediate child observed without following links or applying business exclusions. / 不跟随链接、不套用业务排除规则观察到的一个直接子项。
 * @param relativePath original slash-separated path from the selected root / 自所选根目录起的原斜线路径
 * @param type observed filesystem category / 已观察文件系统类别
 * @param byteCount observed regular-file size, zero for other categories / 已观察普通文件大小，其他类别为零 */
public record FileEntry(String relativePath, EntryType type, long byteCount) {
    /** Retains the observed name without granting path access. / 保留已观察名称，不授予路径访问权限。
     * @param relativePath original relative name / 原相对名称
     * @param type observed category / 已观察类别
     * @param byteCount observed size / 已观察大小 */
    public FileEntry {
        Objects.requireNonNull(relativePath);
        Objects.requireNonNull(type);
        if (relativePath.isEmpty() || byteCount < 0 || (type != EntryType.FILE && byteCount != 0))
            throw new IllegalArgumentException("Invalid source directory entry");
    }

    /** Directory observation categories; unsupported entries are not traversed. / 目录观测类别，不遍历不支持的子项。 */
    public enum EntryType {
        /** Ordinary non-link file. / 普通非链接文件。 */
        FILE,
        /** Ordinary non-link directory. / 普通非链接目录。 */
        DIRECTORY,
        /** Link, junction or other non-ordinary entry. / 链接、联接或其他非常规子项。 */
        OTHER
    }
}
