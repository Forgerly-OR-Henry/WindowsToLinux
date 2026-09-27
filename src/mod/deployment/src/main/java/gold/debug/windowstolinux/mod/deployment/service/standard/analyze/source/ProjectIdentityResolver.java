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

    /**
     * Derives an identifier from matching metadata, with the root as a safe fallback. / 从匹配元数据推导标识，根目录为安全回退。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @param pattern pattern / 匹配模式
     * @return application id text / 应用标识文本
     */
    public static String applicationId(Path root, String text, Pattern pattern) {
        return applicationId(root, text, pattern, rootName(root));
    }

    /** Uses metadata first and a reviewed logical name only when metadata is absent. / 优先使用元数据，仅在缺失时回退经审阅的逻辑名称。
     * @param root physical source root / 物理源码根
     * @param text bounded metadata / 有界元数据
     * @param pattern metadata name pattern / 元数据名称模式
     * @param logicalRootName reviewed original source name / 经审阅的原源码名称
     * @return managed application identifier / 受管应用标识 */
    public static String applicationId(Path root, String text, Pattern pattern, String logicalRootName) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? normalize(matcher.group(1)) : rootApplicationId(logicalRootName);
    }

    /**
     * Derives an identifier from the selected root name. / 从选定根目录名称推导标识。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return root application id text / 根目录应用标识文本
     */
    public static String rootApplicationId(Path root) {
        return rootApplicationId(rootName(root));
    }

    /** Returns the original directory's last component for legacy Path analysis. / 返回旧Path分析所用目录末段。
     * @param root original source root / 原源码根
     * @return name before identifier normalization / 标识规范化前名称 */
    public static String rootName(Path root) {
        Path name = root.getFileName();
        return name == null ? "application" : name.toString();
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
