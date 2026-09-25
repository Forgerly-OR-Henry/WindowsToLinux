package gold.debug.windowstolinux.app.windows.update;

import java.util.Objects;

/**
 * Strict three-part desktop release version. / 严格三段式桌面发布版本。
 *
 * @param major major / 主版本
 * @param minor minor / 次版本
 * @param patch patch / 补丁
 */
public record DesktopReleaseVersion(int major, int minor, int patch) implements Comparable<DesktopReleaseVersion> {
    /**
     * Rejects negative version parts. / 拒绝负版本段。
     *
     * @param major major / 主版本
     * @param minor minor / 次版本
     * @param patch patch / 补丁
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public DesktopReleaseVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("desktop release version parts must not be negative");
        }
    }

    /**
     * Parses canonical non-prefixed semantic version text. / 解析无前缀的规范语义版本文本。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return canonical non-prefixed semantic version text / 无前缀的规范语义版本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static DesktopReleaseVersion parse(String value) {
        value = Objects.requireNonNull(value, "value").trim();
        if (!value.matches("(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})")) {
            throw new IllegalArgumentException("desktop release version must be canonical major.minor.patch");
        }
        String[] parts = value.split("\\.");
        return new DesktopReleaseVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }

    /**
     * Compares to.
     * <p>比较目标。
     *
     * @param other other / 其他
     * @return compare to as a numeric result / 比较目标的数值结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    @Override
    public int compareTo(DesktopReleaseVersion other) {
        Objects.requireNonNull(other, "other");
        int result = Integer.compare(major, other.major);
        if (result == 0)
            result = Integer.compare(minor, other.minor);
        if (result == 0)
            result = Integer.compare(patch, other.patch);
        return result;
    }

    /**
     * Returns the diagnostic text representation of this object.
     * <p>返回当前对象的诊断文本表示。
     *
     * @return the diagnostic text representation of this object / 当前对象的诊断文本表示
     */
    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
