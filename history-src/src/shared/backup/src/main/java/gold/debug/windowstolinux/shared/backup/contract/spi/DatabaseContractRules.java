package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Checks database identifiers and connection fields before fixed database operations.
 * <p>在固定数据库操作前检查数据库标识及连接字段。
 */
final class DatabaseContractRules {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DatabaseContractRules() {
    }

    /**
     * Validates an identifier against the bounded syntax of the owning contract.
     * <p>按所属契约的有界语法验证标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return identifier text / 标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static String identifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException(name + " is invalid");
        return value;
    }

    /**
     * Checks candidate syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查候选语法及边界。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return candidate text / 候选文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static String candidate(String applicationId, String value) {
        value = Objects.requireNonNull(value, "candidateId").trim();
        if (!value.matches(java.util.regex.Pattern.quote(applicationId) + "-[0-9a-f]{16}")) {
            throw new IllegalArgumentException("candidateId is not bound to the managed application");
        }
        return value;
    }

    /**
     * Checks name syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查名称语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return name text / 名称文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static String name(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_$.-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    /**
     * Checks host syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查主机语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return host text / 主机文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static String host(String value) {
        value = Objects.requireNonNull(value, "host").trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) {
            throw new IllegalArgumentException("database host is invalid");
        }
        return value;
    }

    /**
     * Validates a relative path before it is joined to the controlled root.
     * <p>在与受控根目录拼接前验证相对路径。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return relative path text / 相对路径文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static String relativePath(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[A-Za-z0-9._/-]{1,255}") || value.startsWith("/") || value.matches("^[A-Za-z]:.*")
                || value.contains("\\") || value.contains("//")) {
            throw new IllegalArgumentException(name + " is not a controlled relative path");
        }
        for (String segment : value.split("/")) {
            if (segment.equals(".") || segment.equals("..") || segment.isEmpty()) {
                throw new IllegalArgumentException(name + " contains traversal");
            }
        }
        return value;
    }

    /**
     * Validates textual content against the declared length and character constraints.
     * <p>按声明的长度及字符约束校验文本内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param maximumLength maximum length / 最大长度
     * @return text text / 文本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static String text(String value, String name, int maximumLength) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isEmpty() || value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must contain bounded printable text");
        }
        return value;
    }

    /**
     * Requires a nonempty bounded collection of distinct database evidence strings.
     * <p>要求非空、有界且内容不重复的数据库证据字符串集合。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static List<String> evidence(List<String> values) {
        Objects.requireNonNull(values, "evidence");
        if (values.isEmpty() || values.size() > 64)
            throw new IllegalArgumentException("database evidence is incomplete");
        List<String> result = new ArrayList<>(values.size());
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            String text = text(value, "evidence", 512);
            if (!unique.add(text))
                throw new IllegalArgumentException("database evidence contains duplicates");
            result.add(text);
        }
        return List.copyOf(result);
    }
}
