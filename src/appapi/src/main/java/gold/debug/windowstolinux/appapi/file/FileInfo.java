package gold.debug.windowstolinux.appapi.file;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Observed object and every link used to resolve it; observation grants no permission. / 实际对象及解析链，观测不授予权限。 */
public record FileInfo(FilePath requested, FilePath actual, Kind kind, long bytes, Instant modified,
                       String identity, Optional<String> target, List<Link> links) {
    /** Copies an immutable bounded observation. / 复制不可变有界观测。 */
    public FileInfo {
        java.util.Objects.requireNonNull(requested); java.util.Objects.requireNonNull(actual);
        java.util.Objects.requireNonNull(kind); java.util.Objects.requireNonNull(modified);
        if (bytes < 0 || identity == null || identity.isBlank()) throw new IllegalArgumentException("Invalid file observation");
        java.util.Objects.requireNonNull(target); links = List.copyOf(links);
        if (links.size() > 64) throw new IllegalArgumentException("Link chain exceeds the limit");
    }
    /** Supported object kinds. / 支持的对象类型。 */
    public enum Kind { FILE, DIRECTORY, LINK, OTHER }
    /** Exact no-follow link observation. / 不跟随链接的精确观测。 */
    public record Link(FilePath path, String identity, String target) {
        /** Validates the observed link. / 校验链接观测。 */
        public Link { java.util.Objects.requireNonNull(path); java.util.Objects.requireNonNull(identity); java.util.Objects.requireNonNull(target); }
    }
}
