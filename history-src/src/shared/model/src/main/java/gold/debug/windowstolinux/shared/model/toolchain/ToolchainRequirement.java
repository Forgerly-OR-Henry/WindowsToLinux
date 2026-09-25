package gold.debug.windowstolinux.shared.model.toolchain;

import java.util.Objects;
import java.util.Optional;

/**
 * Preserves a source declaration even when it has no supported build candidate. / 即使没有构建候选也保留源码声明。
 *
 * @param ecosystem ecosystem / 生态
 * @param declaration declaration / 声明
 * @param source source identity or content read by the operation / 操作读取的源身份或内容
 * @param purpose purpose / 用途
 * @param constraint constraint / 约束
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 */
public record ToolchainRequirement(ToolchainEcosystemType ecosystem, String declaration, String source,
        PurposeType purpose, ConstraintType constraint, Optional<ToolchainVersion> version) {
    /**
     * Distinguishes build-time and runtime toolchain requirements.
     * <p>区分构建期及运行期工具链需求。
     */
    public enum PurposeType {
        /**
         * BUILD classification within purpose type.
         * <p>用途类型中的构建分类。
         */
        BUILD,
        /**
         * RUNTIME classification within purpose type.
         * <p>用途类型中的运行时分类。
         */
        RUNTIME,
        /**
         * LANGUAGE TARGET classification within purpose type.
         * <p>用途类型中的语言目标分类。
         */
        LANGUAGE_TARGET
    }

    /**
     * Identifies the form of a reviewed toolchain version constraint.
     * <p>标识已审阅工具链版本约束的形式。
     */
    public enum ConstraintType {
        /**
         * EXACT classification within constraint type.
         * <p>约束类型中的精确分类。
         */
        EXACT,
        /**
         * SERIES classification within constraint type.
         * <p>约束类型中的系列分类。
         */
        SERIES,
        /**
         * MINIMUM classification within constraint type.
         * <p>约束类型中的最小分类。
         */
        MINIMUM,
        /**
         * UNRESOLVED classification within constraint type.
         * <p>约束类型中的未解析分类。
         */
        UNRESOLVED
    }

    /**
     * Validates and binds the inputs required by toolchain requirement.
     * <p>校验并绑定工具链要求所需输入。
     *
     * @param ecosystem ecosystem / 生态
     * @param declaration declaration / 声明
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param purpose purpose / 用途
     * @param constraint constraint / 约束
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ToolchainRequirement {
        ecosystem = Objects.requireNonNull(ecosystem, "ecosystem");
        declaration = bounded(declaration, "declaration");
        source = bounded(source, "source");
        purpose = Objects.requireNonNull(purpose, "purpose");
        constraint = Objects.requireNonNull(constraint, "constraint");
        version = Objects.requireNonNull(version, "version");
        if (version.isPresent() && version.orElseThrow().ecosystem() != ecosystem)
            throw new IllegalArgumentException("version ecosystem mismatch");
        if ((constraint == ConstraintType.UNRESOLVED) != version.isEmpty())
            throw new IllegalArgumentException("unresolved declarations must not manufacture a version");
    }

    /**
     * Builds toolchain requirement from the supplied declared inputs.
     * <p>根据所提供已声明输入构建工具链要求。
     *
     * @param ecosystem ecosystem / 生态
     * @param declaration declaration / 声明
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param purpose purpose / 用途
     * @return toolchain requirement from the supplied declared inputs / 根据所提供已声明输入构建工具链要求
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static ToolchainRequirement declared(ToolchainEcosystemType ecosystem, String declaration, String source,
            PurposeType purpose) {
        String value = Objects.requireNonNull(declaration, "declaration").trim();
        boolean minimum = value.startsWith(">=");
        String token = minimum ? value.substring(2).trim() : value.replaceFirst("^(?:==|=)", "").trim();
        if (!minimum && token.matches("[0-9]+(?:[.][0-9]+)*[.][xX*]"))
            token = token.substring(0, token.length() - 2);
        Optional<ToolchainVersion> parsed = ToolchainVersion.parse(ecosystem, token);
        ConstraintType kind = parsed.isEmpty()
                ? ConstraintType.UNRESOLVED
                : minimum
                        ? ConstraintType.MINIMUM
                        : parsed.orElseThrow().numbers().size() <= ecosystem.branchSegments()
                                || ecosystem == ToolchainEcosystemType.DOTNET
                                        && parsed.orElseThrow().numbers().size() == 2
                                        && parsed.orElseThrow().numbers().get(1) == 0
                                                ? ConstraintType.SERIES
                                                : ConstraintType.EXACT;
        return new ToolchainRequirement(ecosystem, declaration, source, purpose, kind, parsed);
    }

    /**
     * Tests the accepts predicate against the supplied evidence.
     * <p>根据所提供证据检查接受条件。
     *
     * @param candidate candidate / 候选
     * @return true when accepts predicate against the supplied evidence, false otherwise / 根据所提供证据检查接受条件时为 true，否则为 false
     */
    public boolean accepts(ToolchainVersion candidate) {
        if (candidate.ecosystem() != ecosystem || candidate.preview() || version.isEmpty())
            return false;
        ToolchainVersion requested = version.orElseThrow();
        return switch (constraint) {
            case EXACT -> requested.sameRelease(candidate);
            case SERIES -> requested.branch().equals(candidate.branch());
            case MINIMUM -> candidate.compareTo(requested) >= 0;
            case UNRESOLVED -> false;
        };
    }

    /**
     * Rejects content exceeding the explicit size or count bound.
     * <p>拒绝超出显式大小或数量限制的内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return bounded text / 有界文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String bounded(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > 1024 || value.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("invalid " + field);
        return value;
    }
}
