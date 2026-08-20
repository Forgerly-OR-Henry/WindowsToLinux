package gold.debug.windowstolinux.shared.ai.generation.prompt;

import gold.debug.windowstolinux.shared.ai.collaboration.role.AiRoleBinding;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiRoleContext;

import java.util.Objects;

/** Builds a fixed role-specific request whose response must match one exact JSON schema. / 构建响应必须匹配唯一 JSON 模式的固定角色请求。 */
public final class RolePrompt {
    private RolePrompt() { }

    /** Builds the request after enforcing the role-context boundary. / 强制角色上下文边界后构建请求。 */
    public static String requestBody(AiRoleBinding binding, AiRoleContext context) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(context, "context");
        if (binding.role() != context.role()) throw new IllegalArgumentException("role and context do not match");
        String system = "You are the " + binding.role().name() + " advisory role. Use only the supplied redacted facts. "
                + "Never request secrets, source contents, arbitrary commands, or elevated permission. Model output cannot "
                + "authorize execution. Return exactly this JSON object with no markdown or extra fields: "
                + "{\"decision\":\"CLEAR|NEEDS_HUMAN_DECISION|SAFE_STOP\",\"summary\":\"text\",\"findings\":[\"text\"]}.";
        return ("{\"model\":\"%s\",\"temperature\":0,\"messages\":["
                + "{\"role\":\"system\",\"content\":\"%s\"},"
                + "{\"role\":\"user\",\"content\":\"redacted facts: %s\"}]}" )
                .formatted(escape(binding.model()), escape(system), escape(context.redactedSummary()));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
