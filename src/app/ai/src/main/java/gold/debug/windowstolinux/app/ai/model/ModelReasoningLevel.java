package gold.debug.windowstolinux.app.ai.model;

import java.util.Locale;

/** Optional Chat Completions reasoning effort; support must be verified for the selected model. / 可选的文字协议推理强度，须验证所选模型支持。 */
public enum ModelReasoningLevel {
    /** Leaves provider behavior unchanged. / 保留提供者默认行为。 */
    DEFAULT,
    /** Requests a lower reasoning budget. / 请求较低推理预算。 */
    LOW,
    /** Requests an intermediate reasoning budget. / 请求中等推理预算。 */
    MEDIUM,
    /** Requests a higher reasoning budget. / 请求较高推理预算。 */
    HIGH;

    /** Returns the exact protocol value for an explicitly configured effort. / 返回明确配置强度的精确协议值。
     * @return lowercase protocol value / 小写协议值 */
    public String protocolValue() {
        if (this == DEFAULT) throw new IllegalStateException("Default effort must be omitted");
        return name().toLowerCase(Locale.ROOT);
    }
}
