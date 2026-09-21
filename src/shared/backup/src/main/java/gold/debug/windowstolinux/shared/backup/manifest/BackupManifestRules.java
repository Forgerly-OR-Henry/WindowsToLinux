package gold.debug.windowstolinux.shared.backup.manifest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Checks stable manifest identifiers, digests and member references.
 * <p>检查稳定的清单标识、摘要及成员引用。
 */
final class BackupManifestRules {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private BackupManifestRules() {
    }

    /**
     * Reads required text and rejects missing or invalid content.
     * <p>读取必填文本并拒绝缺失或无效内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param maximumLength maximum length / 最大长度
     * @return required text and rejects missing or invalid content / 必填文本并拒绝缺失或无效内容
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static String requiredText(String value, String name, int maximumLength) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isEmpty() || value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must contain bounded printable text");
        }
        return value;
    }

    /**
     * Validates an identifier against the bounded syntax of the owning contract.
     * <p>按所属契约的有界语法验证标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return identifier text / 标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    static String identifier(String value, String name) {
        value = requiredText(value, name, 128);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}")) {
            throw new IllegalArgumentException(name + " is not a stable identifier");
        }
        return value;
    }

    /**
     * Validates and produces distinct texts for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的去重文本集合。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param maximumCount maximum count / 最大数量
     * @param maximumLength maximum length / 最大长度
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static List<String> distinctTexts(List<String> values, String name, int maximumCount, int maximumLength) {
        Objects.requireNonNull(values, name);
        if (values.size() > maximumCount) throw new IllegalArgumentException(name + " exceeds its item bound");
        List<String> result = new ArrayList<>(values.size());
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            String normalized = requiredText(value, name, maximumLength);
            if (!unique.add(normalized.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(name + " must not contain duplicate values");
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    /**
     * Checks archive path syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查归档路径语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return archive path text / 归档路径文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    static String archivePath(String value) {
        value = requiredText(value, "path", 1024);
        if (value.indexOf('\\') >= 0 || value.startsWith("/") || value.endsWith("/")
                || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("archive member path must be relative canonical POSIX text");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(" ") || segment.endsWith(".")) {
                throw new IllegalArgumentException("archive member path contains an unsafe segment");
            }
        }
        return value;
    }
}
