package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives bounded managed project identifiers from reviewed metadata or the selected root.
 *
 *  <p>从经审阅元数据或选定根目录推导有界受管项目标识。
 */
public final class ProjectIdentityResolver {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ProjectIdentityResolver() {
    }

    /** Uses metadata first and a reviewed logical name only when metadata is absent. / 优先使用元数据，仅在缺失时回退经审阅的逻辑名称。
     * @param root physical source root / 物理源码根
     * @param text bounded metadata / 有界元数据
     * @param pattern metadata name pattern / 元数据名称模式
     * @param logicalRootName reviewed original source name / 经审阅的原源码名称
     * @return managed application identifier / 受管应用标识 */
    public static String applicationId(Path root, String text, Pattern pattern, String logicalRootName) {
        if (logicalRootName == null || logicalRootName.isBlank())
            throw new IllegalArgumentException("logicalRootName is required");
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? normalize(matcher.group(1)) : rootApplicationId(logicalRootName);
    }

    /** Normalizes one explicit reviewed fallback name. / 规范化一个明确审阅的回退名称。
     * @param logicalRootName original reviewed name / 原经审阅名称
     * @return managed application identifier / 受管应用标识 */
    public static String rootApplicationId(String logicalRootName) {
        if (logicalRootName == null || logicalRootName.isBlank())
            throw new IllegalArgumentException("logicalRootName is required");
        return normalize(logicalRootName);
    }

    /**
     * Normalizes project identity resolver.
     * <p>规范化项目身份解析器。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return normalize text / 规范化文本
     */
    private static String normalize(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            return "application";
        }
        return normalized.length() > 63 ? normalized.substring(0, 63).replaceAll("-+$", "") : normalized;
    }
}
