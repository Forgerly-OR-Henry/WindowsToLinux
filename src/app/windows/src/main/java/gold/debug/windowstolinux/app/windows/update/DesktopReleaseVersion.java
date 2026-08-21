package gold.debug.windowstolinux.app.windows.update;

import java.util.Objects;

/** Strict three-part desktop release version. / 严格三段式桌面发布版本。 */
public record DesktopReleaseVersion(int major, int minor, int patch)
        implements Comparable<DesktopReleaseVersion> {
    /** Rejects negative version parts. / 拒绝负版本段。 */
    public DesktopReleaseVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("desktop release version parts must not be negative");
        }
    }

    /** Parses canonical non-prefixed semantic version text. / 解析无前缀的规范语义版本文本。 */
    public static DesktopReleaseVersion parse(String value) {
        value = Objects.requireNonNull(value, "value").trim();
        if (!value.matches("(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})")) {
            throw new IllegalArgumentException("desktop release version must be canonical major.minor.patch");
        }
        String[] parts = value.split("\\.");
        return new DesktopReleaseVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }

    @Override
    public int compareTo(DesktopReleaseVersion other) {
        Objects.requireNonNull(other, "other");
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        if (result == 0) result = Integer.compare(patch, other.patch);
        return result;
    }

    @Override public String toString() { return major + "." + minor + "." + patch; }
}
