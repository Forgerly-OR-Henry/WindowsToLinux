package gold.debug.windowstolinux.shared.model.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Numeric release identity; parsing never consults the build support catalog. / 数字发布身份，解析不查询构建支持目录。
 *
 * @param ecosystem ecosystem / 生态
 * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
 * @param numbers numbers / 数字集合
 * @param preview preview / 预览
 */
public record ToolchainVersion(ToolchainEcosystemType ecosystem, String text, List<Integer> numbers,
        boolean preview) implements Comparable<ToolchainVersion> {
    /**
     * Pattern recognizing NUMERIC.
     * <p>用于识别数值的匹配模式。
     */
    private static final Pattern NUMERIC = Pattern
            .compile("^(\\d{1,9}(?:[.]\\d{1,9}){0,4})([A-Za-z+_.-][A-Za-z0-9+_.-]*)?$");

    /**
     * Validates and binds the inputs required by toolchain version.
     * <p>校验并绑定工具链版本所需输入。
     *
     * @param ecosystem ecosystem / 生态
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @param numbers numbers / 数字集合
     * @param preview preview / 预览
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ToolchainVersion {
        ecosystem = Objects.requireNonNull(ecosystem, "ecosystem");
        text = Objects.requireNonNull(text, "text");
        numbers = List.copyOf(numbers);
        if (numbers.isEmpty() || numbers.size() > 5 || numbers.stream().anyMatch(n -> n < 0))
            throw new IllegalArgumentException("invalid numeric version");
        if (!text.matches("[A-Za-z0-9+_.-]{1,96}"))
            throw new IllegalArgumentException("invalid version token");
    }

    /**
     * Parses supported ecosystem version syntax into numeric release identity and preview status, returning empty for invalid input.
     * <p>将受支持生态版本语法解析为数字发布身份及预览状态，对无效输入返回空值。
     *
     * @param ecosystem ecosystem / 生态
     * @param raw raw / 原始
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static Optional<ToolchainVersion> parse(ToolchainEcosystemType ecosystem, String raw) {
        Objects.requireNonNull(ecosystem, "ecosystem");
        if (raw == null || raw.length() > 96)
            return Optional.empty();
        String value = raw.trim();
        if (ecosystem == ToolchainEcosystemType.GO && value.startsWith("go"))
            value = value.substring(2);
        if (value.startsWith("v"))
            value = value.substring(1);
        if (ecosystem == ToolchainEcosystemType.JAVA) {
            value = value.replaceFirst("^(?:jdk-?|java-?)", "");
            value = value.replace('_', '.');
            if (value.matches("1[.][0-8](?:[.].*)?"))
                value = value.substring(2);
            value = value.replaceFirst("^(\\d+)u(\\d+)", "$1.0.$2").replace('_', '.');
        }
        var matcher = NUMERIC.matcher(value);
        if (!matcher.matches())
            return Optional.empty();
        List<Integer> parts = new ArrayList<>();
        for (String part : matcher.group(1).split("[.]"))
            parts.add(Integer.parseInt(part));
        String suffix = matcher.group(2);
        boolean preview = suffix != null && !suffix.matches("[+]\\d+(?:[.][0-9]+)*")
                && !(ecosystem == ToolchainEcosystemType.JAVA && suffix.matches("-b\\d+"));
        return Optional.of(new ToolchainVersion(ecosystem, value, parts, preview));
    }

    /**
     * Returns branch.
     * <p>返回分支。
     *
     * @return branch / 分支
     */
    public String branch() {
        int count = Math.min(ecosystem.branchSegments(), numbers.size());
        return numbers.subList(0, count).stream().map(Object::toString)
                .collect(java.util.stream.Collectors.joining("."));
    }

    /**
     * Compares complete releases independently of compatibility ordering. / 独立于兼容排序比较完整发布。
     *
     * @param other other / 其他
     * @return true when compares complete releases independently of compatibility ordering, false otherwise / 独立于兼容排序比较完整发布时为 true，否则为 false
     */
    public boolean sameRelease(ToolchainVersion other) {
        return compareTo(other) == 0 && releaseSuffix().equals(other.releaseSuffix());
    }

    /**
     * Releases suffix.
     * <p>释放后缀。
     *
     * @return release suffix text / 发布后缀文本
     */
    private String releaseSuffix() {
        var matcher = NUMERIC.matcher(text);
        if (!matcher.matches() || matcher.group(2) == null)
            return "";
        String suffix = matcher.group(2);
        return ecosystem == ToolchainEcosystemType.JAVA ? suffix.replaceFirst("^-b", "+") : suffix;
    }

    /**
     * Compares to.
     * <p>比较目标。
     *
     * @param other other / 其他
     * @return compare to as a numeric result / 比较目标的数值结果
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public int compareTo(ToolchainVersion other) {
        if (ecosystem != other.ecosystem)
            throw new IllegalArgumentException("cannot compare different ecosystems");
        for (int i = 0; i < Math.max(numbers.size(), other.numbers.size()); i++) {
            int comparison = Integer.compare(i < numbers.size() ? numbers.get(i) : 0,
                    i < other.numbers.size() ? other.numbers.get(i) : 0);
            if (comparison != 0)
                return comparison;
        }
        return Boolean.compare(other.preview, preview);
    }
}
