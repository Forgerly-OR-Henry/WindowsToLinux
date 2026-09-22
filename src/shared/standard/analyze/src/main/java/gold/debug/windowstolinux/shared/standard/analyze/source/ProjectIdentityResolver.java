package gold.debug.windowstolinux.shared.standard.analyze.source;

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
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? normalize(matcher.group(1)) : rootApplicationId(root);
    }

    /**
     * Derives an identifier from the selected root name. / 从选定根目录名称推导标识。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return root application id text / 根目录应用标识文本
     */
    public static String rootApplicationId(Path root) {
        Path name = root.getFileName();
        return normalize(name == null ? "application" : name.toString());
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
