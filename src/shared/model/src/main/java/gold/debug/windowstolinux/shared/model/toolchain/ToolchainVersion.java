package gold.debug.windowstolinux.shared.model.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Numeric release identity; parsing never consults the build support catalog. / 数字发布身份，解析不查询构建支持目录。 */
public record ToolchainVersion(ToolchainEcosystemType ecosystem, String text, List<Integer> numbers, boolean preview)
        implements Comparable<ToolchainVersion> {
    private static final Pattern NUMERIC = Pattern.compile("^(\\d{1,9}(?:[.]\\d{1,9}){0,4})([A-Za-z+_.-][A-Za-z0-9+_.-]*)?$");

    public ToolchainVersion {
        ecosystem = Objects.requireNonNull(ecosystem, "ecosystem");
        text = Objects.requireNonNull(text, "text");
        numbers = List.copyOf(numbers);
        if (numbers.isEmpty() || numbers.size() > 5 || numbers.stream().anyMatch(n -> n < 0))
            throw new IllegalArgumentException("invalid numeric version");
        if (!text.matches("[A-Za-z0-9+_.-]{1,96}")) throw new IllegalArgumentException("invalid version token");
    }

    public static Optional<ToolchainVersion> parse(ToolchainEcosystemType ecosystem, String raw) {
        Objects.requireNonNull(ecosystem, "ecosystem");
        if (raw == null || raw.length() > 96) return Optional.empty();
        String value = raw.trim();
        if (ecosystem == ToolchainEcosystemType.GO && value.startsWith("go")) value = value.substring(2);
        if (value.startsWith("v")) value = value.substring(1);
        if (ecosystem == ToolchainEcosystemType.JAVA) {
            value = value.replaceFirst("^(?:jdk-?|java-?)", "");
            value = value.replace('_', '.');
            if (value.matches("1[.][0-8](?:[.].*)?")) value = value.substring(2);
            value = value.replaceFirst("^(\\d+)u(\\d+)", "$1.0.$2").replace('_', '.');
        }
        var matcher = NUMERIC.matcher(value);
        if (!matcher.matches()) return Optional.empty();
        List<Integer> parts = new ArrayList<>();
        for (String part : matcher.group(1).split("[.]")) parts.add(Integer.parseInt(part));
        String suffix = matcher.group(2);
        boolean preview = suffix != null && !suffix.matches("[+]\\d+(?:[.][0-9]+)*")
                && !(ecosystem == ToolchainEcosystemType.JAVA && suffix.matches("-b\\d+"));
        return Optional.of(new ToolchainVersion(ecosystem, value, parts, preview));
    }

    public String branch() {
        int count = Math.min(ecosystem.branchSegments(), numbers.size());
        return numbers.subList(0, count).stream().map(Object::toString).collect(java.util.stream.Collectors.joining("."));
    }

    /** Compares complete releases independently of compatibility ordering. / 独立于兼容排序比较完整发布。 */
    public boolean sameRelease(ToolchainVersion other) {
        return compareTo(other) == 0 && releaseSuffix().equals(other.releaseSuffix());
    }

    private String releaseSuffix() {
        var matcher = NUMERIC.matcher(text);
        if (!matcher.matches() || matcher.group(2) == null) return "";
        String suffix = matcher.group(2);
        return ecosystem == ToolchainEcosystemType.JAVA ? suffix.replaceFirst("^-b", "+") : suffix;
    }

    @Override public int compareTo(ToolchainVersion other) {
        if (ecosystem != other.ecosystem) throw new IllegalArgumentException("cannot compare different ecosystems");
        for (int i = 0; i < Math.max(numbers.size(), other.numbers.size()); i++) {
            int comparison = Integer.compare(i < numbers.size() ? numbers.get(i) : 0,
                    i < other.numbers.size() ? other.numbers.get(i) : 0);
            if (comparison != 0) return comparison;
        }
        return Boolean.compare(other.preview, preview);
    }
}
