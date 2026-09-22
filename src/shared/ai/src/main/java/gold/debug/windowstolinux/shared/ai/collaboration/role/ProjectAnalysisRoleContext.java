package gold.debug.windowstolinux.shared.ai.collaboration.role;

import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;

/**
 * Minimal project facts without source paths, contents, configuration values, or secrets. / 不含源码路径、内容、配置值或秘密的最小项目事实。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param projectType supported project deployment category / 受支持的项目部署类别
 * @param buildTool the fixed build entrypoint / 固定构建入口
 * @param supportLevel support level / 支持级别
 * @param missingInputCodes missing input codes / 缺失输入代码集合
 */
public record ProjectAnalysisRoleContext(String applicationId, String projectType, String buildTool,
        String supportLevel, List<String> missingInputCodes) implements AiRoleContext {
    /**
     * Validates the bounded project-analysis context. / 验证有界项目分析上下文。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param buildTool the fixed build entrypoint / 固定构建入口
     * @param supportLevel support level / 支持级别
     * @param missingInputCodes missing input codes / 缺失输入代码集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ProjectAnalysisRoleContext {
        applicationId = identifier(applicationId, "applicationId");
        projectType = token(projectType, "projectType");
        buildTool = token(buildTool, "buildTool");
        supportLevel = token(supportLevel, "supportLevel");
        missingInputCodes = Objects.requireNonNull(missingInputCodes, "missingInputCodes");
        if (missingInputCodes.size() > 16)
            throw new IllegalArgumentException("too many missingInputCodes");
        missingInputCodes = missingInputCodes.stream().map(value -> token(value, "missing input code")).sorted()
                .distinct().toList();
    }

    /**
     * Creates the role context from deterministic facts only. / 仅从确定性事实创建角色上下文。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @return the role context from deterministic facts only / 仅从确定性事实创建角色上下文
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static ProjectAnalysisRoleContext from(DeploymentProjectFacts facts) {
        Objects.requireNonNull(facts, "facts");
        return new ProjectAnalysisRoleContext(facts.applicationId(), facts.projectType().name(),
                facts.buildTool().name(), facts.support().level().name(),
                facts.missingInformation().stream().map(value -> value.key()).toList());
    }

    /**
     * Returns role.
     * <p>返回角色。
     *
     * @return role / 角色
     */
    @Override
    public AiCollaborationRoleKind role() {
        return AiCollaborationRoleKind.PROJECT_ANALYSIS;
    }

    /**
     * Returns redacted summary.
     * <p>返回已脱敏摘要。
     *
     * @return redacted summary / 已脱敏摘要
     */
    @Override
    public String redactedSummary() {
        return "applicationId=" + applicationId + ";projectType=" + projectType + ";buildTool=" + buildTool
                + ";supportLevel=" + supportLevel + ";missingInputCodes=" + String.join(",", missingInputCodes);
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
    private static String identifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException(name + " is invalid");
        return value;
    }

    /**
     * Checks token syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查令牌语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return token text / 令牌文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String token(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[A-Za-z0-9._:-]{1,96}"))
            throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
