package gold.debug.windowstolinux.shared.ai.collaboration.role;

import java.net.URI;
import java.util.Objects;

/** Non-secret binding from one fixed role to exactly one named provider and model. / 从一个固定角色到唯一命名提供者和模型的非秘密绑定。 */
public record AiRoleBinding(AiCollaborationRoleKind role, String providerId, URI endpoint, String model) {
    /** Validates the non-secret role binding. / 验证非秘密角色绑定。 */
    public AiRoleBinding {
        role = Objects.requireNonNull(role, "role");
        providerId = Objects.requireNonNull(providerId, "providerId").trim();
        if (!providerId.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("providerId is invalid");
        }
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        model = Objects.requireNonNull(model, "model").trim();
        if (model.isBlank()) throw new IllegalArgumentException("model cannot be blank");
    }
}
