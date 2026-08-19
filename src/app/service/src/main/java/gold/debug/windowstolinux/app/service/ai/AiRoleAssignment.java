package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.app.db.entity.StoredAiRoleAssignment;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiCollaborationRoleKind;

import java.util.Objects;

/** Typed non-secret mapping from one fixed AI role to one named provider. / 从一个固定 AI 角色到一个命名提供者的类型化非秘密映射。 */
public record AiRoleAssignment(AiCollaborationRoleKind role, String providerId) {
    /** Validates the typed assignment. / 验证类型化分配。 */
    public AiRoleAssignment {
        role = Objects.requireNonNull(role, "role");
        providerId = Objects.requireNonNull(providerId, "providerId").trim();
        if (!providerId.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("providerId is invalid");
    }

    /** Converts to credential-free persisted metadata. / 转换为不含凭据的持久化元数据。 */
    public StoredAiRoleAssignment stored() { return new StoredAiRoleAssignment(role.name(), providerId); }

    /** Creates a typed assignment from persisted metadata. / 从持久化元数据创建类型化分配。 */
    public static AiRoleAssignment fromStored(StoredAiRoleAssignment stored) {
        return new AiRoleAssignment(AiCollaborationRoleKind.valueOf(stored.role()), stored.profileId());
    }
}
