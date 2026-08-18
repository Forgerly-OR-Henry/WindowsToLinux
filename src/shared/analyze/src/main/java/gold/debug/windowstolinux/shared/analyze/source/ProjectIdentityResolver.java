package gold.debug.windowstolinux.shared.analyze.source;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives bounded managed project identifiers from reviewed metadata or the selected root.
 *
 * <p>从经审阅元数据或选定根目录推导有界受管项目标识。
 */
public final class ProjectIdentityResolver {
    private ProjectIdentityResolver() {
    }

    /** Derives an identifier from matching metadata, with the root as a safe fallback. / 从匹配元数据推导标识，根目录为安全回退。 */
    public static String applicationId(Path root, String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? normalize(matcher.group(1)) : rootApplicationId(root);
    }

    /** Derives an identifier from the selected root name. / 从选定根目录名称推导标识。 */
    public static String rootApplicationId(Path root) {
        Path name = root.getFileName();
        return normalize(name == null ? "application" : name.toString());
    }

    private static String normalize(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            return "application";
        }
        return normalized.length() > 63 ? normalized.substring(0, 63).replaceAll("-+$", "") : normalized;
    }
}
