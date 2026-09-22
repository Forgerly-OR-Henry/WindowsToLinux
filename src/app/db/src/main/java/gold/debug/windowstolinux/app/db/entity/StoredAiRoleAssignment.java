package gold.debug.windowstolinux.app.db.entity;

import java.util.Objects;
import java.util.Set;

/**
 * Non-secret assignment from one fixed collaboration role to one named provider. / 从一个固定协作角色到一个命名提供者的非秘密分配。
 *
 * @param role role / 角色
 * @param profileId profile id / 配置资料标识
 */
public record StoredAiRoleAssignment(String role, String profileId) {
    /**
     * ROLES.
     * <p>角色集合。
     */
    private static final Set<String> ROLES = Set.of("PROJECT_ANALYSIS", "DEPLOYMENT_RISK_REVIEW", "ERROR_EXPLANATION");

    /**
     * Validates the persisted role and provider identifiers. / 验证持久化角色与提供者标识。
     *
     * @param role role / 角色
     * @param profileId profile id / 配置资料标识
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public StoredAiRoleAssignment {
        role = Objects.requireNonNull(role, "role").trim();
        if (!ROLES.contains(role))
            throw new IllegalArgumentException("role is not a fixed AI collaboration role");
        profileId = Objects.requireNonNull(profileId, "profileId").trim();
        if (!profileId.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("profileId is invalid");
    }
}
