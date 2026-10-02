package gold.debug.windowstolinux.appapi.file;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Observed object and every link used to resolve it; observation grants no permission. / 实际对象及解析链，观测不授予权限。
 * @param requested original caller position / 原调用方位置
 * @param actual resolved actual object position / 已解析实际对象位置
 * @param kind actual observed object kind / 实际观测对象类型
 * @param bytes observed ordinary content extent / 已观测普通内容范围
 * @param modified observed modification instant / 已观测修改时刻
 * @param identity backend identity evidence for detecting object changes / 后端用于检测对象变化的身份依据
 * @param target original link target text, present only when the observed object is a link / 原始链接指向文本，仅观测对象为链接时存在
 * @param links immutable resolution link chain / 不可变解析链接链
 */
public record FileInfo(FilePath requested, FilePath actual, Kind kind, long bytes, Instant modified,
                       String identity, Optional<String> target, List<Link> links) {
    /** Copies an immutable bounded observation. / 复制不可变有界观测。
     * @param requested original caller position / 原调用方位置
     * @param actual resolved actual object position / 已解析实际对象位置
     * @param kind actual observed object kind / 实际观测对象类型
     * @param bytes observed ordinary content extent / 已观测普通内容范围
     * @param modified observed modification instant / 已观测修改时刻
     * @param identity backend identity evidence for detecting object changes / 后端用于检测对象变化的身份依据
     * @param target original link target text, present only when the observed object is a link / 原始链接指向文本，仅观测对象为链接时存在
     * @param links immutable resolution link chain / 不可变解析链接链
     */
    public FileInfo {
        java.util.Objects.requireNonNull(requested);
        java.util.Objects.requireNonNull(actual);
        java.util.Objects.requireNonNull(kind);
        java.util.Objects.requireNonNull(modified);
        if (bytes < 0 || identity == null || identity.isBlank())
            throw new IllegalArgumentException("Invalid file observation");
        java.util.Objects.requireNonNull(target);
        links = List.copyOf(links);
        if (links.size() > 64) throw new IllegalArgumentException("Link chain exceeds the limit");
    }

    /** Supported object kinds. / 支持的对象类型。 */
    public enum Kind {
        /** Ordinary bounded regular file content / 普通有界常规文件内容.
         */
        FILE,
        /** Ordinary directory whose children are listed without following links / 不跟随链接列举子项的普通目录.
         */
        DIRECTORY,
        /** Supported link object observed without reading its target / 不读取目标地观测的支持链接对象.
         */
        LINK,
        /** Unsupported filesystem object rejected by this capability / 此能力拒绝的不支持文件系统对象.
         */
        OTHER
    }

    /** Exact no-follow link observation. / 不跟随链接的精确观测。
     * @param path exact host-qualified position / 精确带主机位置
     * @param identity backend identity evidence for detecting object changes / 后端用于检测对象变化的身份依据
     * @param target exact original target text read from this link / 从此链接读取的精确原始指向文本
     */
    public record Link(FilePath path, String identity, String target) {
        /** Validates the observed link. / 校验链接观测。
         * @param path exact host-qualified position / 精确带主机位置
         * @param identity backend identity evidence for detecting object changes / 后端用于检测对象变化的身份依据
         * @param target exact original target text read from this link / 从此链接读取的精确原始指向文本
         */
        public Link {
            java.util.Objects.requireNonNull(path);
            java.util.Objects.requireNonNull(identity);
            java.util.Objects.requireNonNull(target);
        }
    }
}
