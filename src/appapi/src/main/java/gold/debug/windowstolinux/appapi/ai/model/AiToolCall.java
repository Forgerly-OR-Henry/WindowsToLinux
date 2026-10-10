package gold.debug.windowstolinux.appapi.ai.model;

import java.util.Objects;

/** Actual provider call with untrusted arguments. / 提供方的实际调用，参数仍须所属工具校验。
 * @param id original provider call identity / 提供方原调用标识
 * @param name selected function / 选定函数
 * @param arguments untrusted JSON arguments / 不可信 JSON 参数 */
public record AiToolCall(String id, String name, String arguments) {
    public AiToolCall {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(arguments);
        if (!id.matches("[A-Za-z0-9_-]{1,128}") || !name.matches("[A-Za-z0-9_-]{1,64}")
            || arguments.length() > 1024 * 1024)
            throw new IllegalArgumentException("Invalid native tool call");
    }
}
