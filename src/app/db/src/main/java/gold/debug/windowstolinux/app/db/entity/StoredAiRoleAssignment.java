package gold.debug.windowstolinux.app.db.entity;

import java.util.Objects;
import java.util.Set;

/** Non-secret assignment from one fixed collaboration role to one named provider. / 从一个固定协作角色到一个命名提供者的非秘密分配。 */
public record StoredAiRoleAssignment(String role, String profileId) {
    private static final Set<String> ROLES = Set.of(
            "PROJECT_ANALYSIS", "DEPLOYMENT_RISK_REVIEW", "ERROR_EXPLANATION");

    /** Validates the persisted role and provider identifiers. / 验证持久化角色与提供者标识。 */
    public StoredAiRoleAssignment {
        role = Objects.requireNonNull(role, "role").trim();
        if (!ROLES.contains(role)) throw new IllegalArgumentException("role is not a fixed AI collaboration role");
        profileId = Objects.requireNonNull(profileId, "profileId").trim();
        if (!profileId.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("profileId is invalid");
    }
}
