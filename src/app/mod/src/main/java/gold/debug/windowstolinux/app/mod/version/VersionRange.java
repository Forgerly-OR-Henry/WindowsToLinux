package gold.debug.windowstolinux.app.mod.version;

import java.util.Objects;

/** Evaluates an exact version or one interval with explicit prerelease admission. / 判断精确版本或单一区间，并显式限制预发布准入。 */
public final class VersionRange {
    /** Canonical author declaration. / 规范作者声明。 */
    private final String text;

    /** Lower boundary, absent for an unbounded lower end. / 下界，无界下端时为空。 */
    private final SemanticVersion lower;

    /** Upper boundary, absent for an unbounded upper end. / 上界，无界上端时为空。 */
    private final SemanticVersion upper;

    /** Whether equality at the lower boundary is accepted. / 是否接受等于下界的版本。 */
    private final boolean includeLower;

    /** Whether equality at the upper boundary is accepted. / 是否接受等于上界的版本。 */
    private final boolean includeUpper;

    /**
     * Retains an already validated exact version or interval. / 保留已验证的精确版本或区间。
     * @param text original canonical declaration / 原始规范声明
     * @param lower optional lower end / 可选下端
     * @param upper optional upper end / 可选上端
     * @param includeLower whether lower equality is accepted / 是否接受等于下界
     * @param includeUpper whether upper equality is accepted / 是否接受等于上界
     */
    private VersionRange(String text, SemanticVersion lower, SemanticVersion upper, boolean includeLower,
            boolean includeUpper) {
        this.text = text;
        this.lower = lower;
        this.upper = upper;
        this.includeLower = includeLower;
        this.includeUpper = includeUpper;
    }

    /**
     * Parses only a supported exact version or nonempty single interval. / 仅解析受支持的精确版本或非空单一区间。
     * @param text author range declaration / 作者范围声明
     * @return validated range / 已校验范围
     * @throws IllegalArgumentException if the range is malformed or empty / 范围格式错误或为空时
     */
    public static VersionRange parse(String text) {
        if (text == null || text.length() > 512 || text.isEmpty())
            throw new IllegalArgumentException("Invalid version range");
        if (!text.startsWith("[") && !text.startsWith("(")) {
            var exact = SemanticVersion.parse(text);
            return new VersionRange(text, exact, exact, true, true);
        }
        if (!text.endsWith("]") && !text.endsWith(")"))
            throw new IllegalArgumentException("Invalid version range");
        var halves = text.substring(1, text.length() - 1).split(",", -1);
        if (halves.length != 2 || halves[0].isEmpty() && halves[1].isEmpty())
            throw new IllegalArgumentException("Invalid version range");
        var lower = halves[0].isEmpty() ? null : SemanticVersion.parse(halves[0]);
        var upper = halves[1].isEmpty() ? null : SemanticVersion.parse(halves[1]);
        boolean includeLower = text.startsWith("["), includeUpper = text.endsWith("]");
        if (lower != null && upper != null
                && (lower.compareTo(upper) > 0 || lower.compareTo(upper) == 0 && (!includeLower || !includeUpper)))
            throw new IllegalArgumentException("Empty version range");
        return new VersionRange(text, lower, upper, includeLower, includeUpper);
    }

    /**
     * Checks prerelease eligibility before applying numeric and identifier bounds. / 先检查预发布资格，再应用数字和标识上下界。
     * @param candidate validated candidate version / 已校验候选版本
     * @return whether the explicit range admits this candidate / 显式范围是否接纳候选版本
     */
    public boolean contains(SemanticVersion candidate) {
        Objects.requireNonNull(candidate);
        if (candidate.isPrerelease() && !explicitPrerelease(lower, candidate) && !explicitPrerelease(upper, candidate))
            return false;
        return (lower == null || candidate.compareTo(lower) > 0 || includeLower && candidate.compareTo(lower) == 0)
                && (upper == null || candidate.compareTo(upper) < 0 || includeUpper && candidate.compareTo(upper) == 0);
    }

    /**
     * Determines whether a boundary explicitly admits this prerelease core. / 判断边界是否显式接纳此预发布数字核心。
     * @param boundary optional interval end / 可选区间端点
     * @param candidate candidate prerelease / 候选预发布
     * @return whether a prerelease boundary has the same core / 预发布边界是否具有相同核心
     */
    private static boolean explicitPrerelease(SemanticVersion boundary, SemanticVersion candidate) {
        return boundary != null && boundary.isPrerelease() && boundary.sameCore(candidate);
    }

    /** Returns the exact author range declaration. / 返回精确的作者范围声明。
     * @return declared range text / 声明的范围文本 */
    @Override
    public String toString() {
        return text;
    }
}
