package gold.debug.windowstolinux.shared.ai.collaboration.role;

import java.util.List;
import java.util.Objects;

/**
 * Minimal reviewed-plan facts without SSH data, source contents, configuration values, or secrets. / 不含 SSH 数据、源码内容、配置值或秘密的最小经审阅计划事实。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
 * @param distribution distribution / 发行版
 * @param distributionVersion distribution version / 发行版版本
 * @param architecture observed machine architecture / 观测到的机器架构
 * @param supportLevel support level / 支持级别
 * @param rootBuild root build / 根目录构建
 * @param componentIds affected component identifiers / 受影响的组件标识符
 */
public record DeploymentRiskRoleContext(String applicationId, String releaseIdentity, String distribution,
        String distributionVersion, String architecture, String supportLevel, boolean rootBuild,
        List<String> componentIds) implements AiRoleContext {
    /**
     * Validates the bounded deployment-risk context. / 验证有界部署风险上下文。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @param distribution distribution / 发行版
     * @param distributionVersion distribution version / 发行版版本
     * @param architecture observed machine architecture / 观测到的机器架构
     * @param supportLevel support level / 支持级别
     * @param rootBuild root build / 根目录构建
     * @param componentIds affected component identifiers / 受影响的组件标识符
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentRiskRoleContext {
        applicationId = identifier(applicationId, "applicationId");
        releaseIdentity = Objects.requireNonNull(releaseIdentity, "releaseIdentity");
        if (!releaseIdentity.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("releaseIdentity is invalid");
        distribution = token(distribution, "distribution");
        distributionVersion = token(distributionVersion, "distributionVersion");
        architecture = token(architecture, "architecture");
        supportLevel = token(supportLevel, "supportLevel");
        componentIds = Objects.requireNonNull(componentIds, "componentIds");
        if (componentIds.size() > 64)
            throw new IllegalArgumentException("too many componentIds");
        componentIds = componentIds.stream().map(value -> identifier(value, "component id")).sorted().distinct()
                .toList();
        if (componentIds.isEmpty())
            throw new IllegalArgumentException("componentIds cannot be empty");
    }

    /**
     * Returns role.
     * <p>返回角色。
     *
     * @return role / 角色
     */
    @Override
    public AiCollaborationRoleKind role() {
        return AiCollaborationRoleKind.DEPLOYMENT_RISK_REVIEW;
    }

    /**
     * Returns redacted summary.
     * <p>返回已脱敏摘要。
     *
     * @return redacted summary / 已脱敏摘要
     */
    @Override
    public String redactedSummary() {
        return "applicationId=" + applicationId + ";releaseIdentity=" + releaseIdentity + ";distribution="
                + distribution + ";distributionVersion=" + distributionVersion + ";architecture=" + architecture
                + ";supportLevel=" + supportLevel + ";rootBuild=" + rootBuild + ";componentIds="
                + String.join(",", componentIds);
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
