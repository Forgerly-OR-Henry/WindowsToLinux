package gold.debug.windowstolinux.app.ai.provider;

/** Explicit adapter choice, independent of the URL and protocol. / 独立于地址和协议的显式适配选择。
 */
public enum ModelProviderType {
    /** OpenAI model rules. / OpenAI 模型规则。
     */
    OPENAI,
    /** Anthropic Claude rules. / Anthropic Claude 规则。
     */
    ANTHROPIC,
    /** Google Gemini rules. / Google Gemini 规则。
     */
    GEMINI,
    /** xAI Grok rules. / xAI Grok 规则。
     */
    GROK,
    /** DeepSeek model rules. / DeepSeek 模型规则。
     */
    DEEPSEEK,
    /** Alibaba Qwen rules. / 阿里千问规则。
     */
    QWEN,
    /** Generic protocol without manufacturer inference. / 不推测厂商的通用协议。
     */
    THIRD_PARTY
}
