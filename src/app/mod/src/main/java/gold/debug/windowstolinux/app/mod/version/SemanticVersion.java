package gold.debug.windowstolinux.app.mod.version;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Compares canonical SemVer releases without integer overflow or build metadata. / 比较规范 SemVer 发布版本，避免整数溢出且不接收构建元数据。 */
public final class SemanticVersion implements Comparable<SemanticVersion> {
    /** Accepted canonical three-part release syntax. / 接受的规范三段发布语法。 */
    private static final Pattern SYNTAX = Pattern
        .compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");

    /** Original canonical spelling used as the installation identity. / 安装身份使用的原始规范拼写。 */
    private final String text;

    /** Arbitrary-precision major, minor and patch values. / 任意精度的主、次及修订数值。 */
    private final List<BigInteger> core;

    /** Ordered prerelease identifiers, empty for a final release. / 有序预发布标识，正式发布时为空。 */
    private final List<String> prerelease;

    /**
     * Retains only validated immutable version components. / 仅保留已验证的不可变版本组成。
     * @param text canonical source spelling / 规范来源拼写
     * @param core three numeric components / 三段数字组成
     * @param prerelease ordered prerelease identifiers / 有序预发布标识
     */
    private SemanticVersion(String text, List<BigInteger> core, List<String> prerelease) {
        this.text = text;
        this.core = List.copyOf(core);
        this.prerelease = List.copyOf(prerelease);
    }

    /**
     * Parses one exact release with a 512-character identity limit. / 解析最长 512 字符的精确发布版本。
     * @param text canonical release identity / 规范发布身份
     * @return validated immutable version / 已校验的不可变版本
     * @throws IllegalArgumentException if syntax or numeric canonicality fails / 语法或数字规范性不满足时
     */
    public static SemanticVersion parse(String text) {
        if (text == null || text.length() > 512)
            throw new IllegalArgumentException("Invalid release version");
        var matcher = SYNTAX.matcher(text);
        if (!matcher.matches())
            throw new IllegalArgumentException("Invalid release version: " + text);
        var pre = matcher.group(4) == null ? List.<String>of() : List.of(matcher.group(4).split("\\."));
        for (var part : pre)
            if (numeric(part) && part.length() > 1 && part.startsWith("0"))
                throw new IllegalArgumentException("Prerelease numeric identifier has a leading zero");
        return new SemanticVersion(text, List.of(new BigInteger(matcher.group(1)), new BigInteger(matcher.group(2)),
            new BigInteger(matcher.group(3))), pre);
    }

    /**
     * Reports whether an identifier consists exclusively of ASCII decimal digits. / 判断标识是否完全由 ASCII 十进制数字组成。
     * @param identifier prerelease identifier / 预发布标识
     * @return whether the identifier is numeric / 标识是否为数字
     */
    private static boolean numeric(String identifier) {
        return identifier.matches("[0-9]+");
    }

    /**
     * Applies SemVer precedence using arbitrary-precision numeric comparison. / 使用任意精度数字比较应用 SemVer 优先顺序。
     * @param right version to compare against / 对照版本
     * @return negative, zero or positive precedence difference / 负、零或正的优先顺序差
     */
    @Override
    public int compareTo(SemanticVersion right) {
        Objects.requireNonNull(right);
        for (int i = 0; i < 3; i++) {
            int compared = core.get(i).compareTo(right.core.get(i));
            if (compared != 0)
                return compared;
        }
        if (prerelease.isEmpty() != right.prerelease.isEmpty())
            return prerelease.isEmpty() ? 1 : -1;
        for (int i = 0; i < Math.min(prerelease.size(), right.prerelease.size()); i++) {
            String a = prerelease.get(i), b = right.prerelease.get(i);
            boolean an = numeric(a), bn = numeric(b);
            int compared = an && bn
                ? new BigInteger(a).compareTo(new BigInteger(b))
                : an != bn ? (an ? -1 : 1) : a.compareTo(b);
            if (compared != 0)
                return compared;
        }
        return Integer.compare(prerelease.size(), right.prerelease.size());
    }

    /** Reports whether this is a prerelease. / 报告此版本是否为预发布。
     * @return whether prerelease identifiers exist / 是否存在预发布标识 */
    public boolean isPrerelease() {
        return !prerelease.isEmpty();
    }

    /** Compares only the three numeric components. / 仅比较三段数字组成。
     * @param other other validated version / 另一已校验版本
     * @return whether the numeric core matches / 数字核心是否相同 */
    public boolean sameCore(SemanticVersion other) {
        return core.equals(other.core);
    }

    /** Returns the canonical installation spelling. / 返回规范安装拼写。
     * @return canonical version text / 规范版本文本 */
    @Override
    public String toString() {
        return text;
    }

    /** Compares canonical version identities. / 比较规范版本身份。
     * @param other candidate identity / 候选身份
     * @return whether both canonical versions match / 两个规范版本是否相同 */
    @Override
    public boolean equals(Object other) {
        return other instanceof SemanticVersion version && text.equals(version.text);
    }

    /** Hashes the canonical installation identity. / 对规范安装身份计算散列。
     * @return identity hash / 身份散列 */
    @Override
    public int hashCode() {
        return text.hashCode();
    }
}
