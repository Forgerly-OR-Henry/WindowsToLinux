package gold.debug.windowstolinux.shared.linux.protocol.restore;

import java.util.Locale;
import java.util.Objects;

/** One exact regular candidate member transferred for restore. / 为恢复传输的单个精确常规候选成员。 */
public record RemoteRestoreMember(String path, long size, String sha256) {
    /** Validates a canonical relative path, size and digest. / 校验规范相对路径、大小和摘要。 */
    public RemoteRestoreMember {
        path = Objects.requireNonNull(path, "path").trim();
        if (path.isEmpty() || path.length() > 1024 || path.indexOf('\\') >= 0
                || path.startsWith("/") || path.endsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("restore member path must be canonical relative POSIX text");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(" ") || segment.endsWith(".")) {
                throw new IllegalArgumentException("restore member path contains an unsafe segment");
            }
        }
        if (size < 0) throw new IllegalArgumentException("restore member size must not be negative");
        sha256 = Objects.requireNonNull(sha256, "sha256").trim().toLowerCase(Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("restore member digest must be canonical SHA-256");
        }
    }
}
