package gold.debug.windowstolinux.appapi.ai.model;

import java.util.Objects;

/** Provider-independent function definition; its schema grants no execution authority. / 与提供方无关的函数定义，Schema 不授予执行权限。
 * @param name legal native function name / 合法原生函数名
 * @param description public capability description / 公开能力说明
 * @param inputSchema parameter JSON Schema / 参数 JSON Schema */
public record AiToolDefinition(String name, String description, String inputSchema) {
    public AiToolDefinition {
        Objects.requireNonNull(name);
        Objects.requireNonNull(description);
        Objects.requireNonNull(inputSchema);
        if (!name.matches("[A-Za-z0-9_-]{1,64}") || description.length() > 16000
            || inputSchema.isBlank() || inputSchema.length() > 65536)
            throw new IllegalArgumentException("Invalid native tool definition");
    }
}
