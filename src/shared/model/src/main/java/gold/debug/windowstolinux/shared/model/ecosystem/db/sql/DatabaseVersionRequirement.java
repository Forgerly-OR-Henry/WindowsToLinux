package gold.debug.windowstolinux.shared.model.ecosystem.db.sql;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Explicit database release constraints; a newer version is compatible with a minimum requirement. / 显式数据库版本约束，较新版本兼容最低版本要求。
 */
public final class DatabaseVersionRequirement {
    /**
     * Pattern recognizing TERM.
     * <p>用于识别条件的匹配模式。
     */
    private static final Pattern TERM = Pattern.compile("(>=|<=|>|<|=)?([0-9]{1,3}(?:\\.[0-9]{1,3}){0,2})(?:\\.x)?");
    /**
     * Declaration.
     * <p>声明。
     */
    private final String declaration;
    /**
     * Validates and binds the inputs required by database version requirement.
     * <p>校验并绑定数据库版本要求所需输入。
     *
     * @param declaration declaration / 声明
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DatabaseVersionRequirement(String declaration) {
        this.declaration = Objects.requireNonNull(declaration).trim();
        if (this.declaration.length() > 100) throw new IllegalArgumentException("database version requirement is too long");
        for (String term : terms()) if (!TERM.matcher(term).matches()) throw new IllegalArgumentException("unsupported database version constraint");
    }
    /**
     * Returns declaration.
     * <p>返回声明。
     *
     * @return declaration / 声明
     */
    public String declaration() { return declaration; }
    /**
     * Tests a parsed database version against every declared numeric comparison term.
     * <p>根据所有声明的数字比较条件检查已解析数据库版本。
     *
     * @param observed observed / 已观测
     * @return true when a parsed database version against every declared numeric comparison term, false otherwise / 根据所有声明的数字比较条件检查已解析数据库版本时为 true，否则为 false
     */
    public boolean accepts(String observed) {
        if (observed == null || !observed.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){0,3}")) return false;
        int[] actual = numbers(observed);
        for (String term : terms()) {
            var matcher = TERM.matcher(term); matcher.matches();
            int[] required = numbers(matcher.group(2));
            String operator = matcher.group(1);
            int compared = compare(actual, required);
            if (operator == null || operator.equals("=")) {
                for (int index = 0; index < required.length; index++) if ((index < actual.length ? actual[index] : 0) != required[index]) return false;
            } else if (!(switch (operator) { case ">=" -> compared >= 0; case "<=" -> compared <= 0; case ">" -> compared > 0; case "<" -> compared < 0; default -> false; })) return false;
        }
        return true;
    }
    /**
     * Returns terms.
     * <p>返回条件集合。
     *
     * @return terms / 条件集合
     */
    private List<String> terms() { return declaration.isEmpty() ? List.of() : List.of(declaration.replaceAll("([<>=]+)\\s+", "$1").split("[,\\s]+")); }
    /**
     * Parses a dot-separated numeric version into integer components.
     * <p>将点分隔数字版本解析为整数分量。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return a dot-separated numeric version into integer components / 将点分隔数字版本解析为整数分量
     */
    private static int[] numbers(String value) { return Arrays.stream(value.split("\\.")).mapToInt(Integer::parseInt).toArray(); }
    /**
     * Compares database version requirement.
     * <p>比较数据库版本要求。
     *
     * @param left left / 左侧
     * @param right right / 右侧
     * @return compare as a numeric result / 比较的数值结果
     */
    private static int compare(int[] left, int[] right) {
        for (int index = 0; index < Math.max(left.length, right.length); index++) {
            int compared = Integer.compare(index < left.length ? left[index] : 0, index < right.length ? right[index] : 0);
            if (compared != 0) return compared;
        }
        return 0;
    }
}
