package gold.debug.windowstolinux.shared.ai.generation.prompt;

import java.util.Objects;

import gold.debug.windowstolinux.shared.ai.redaction.RedactedDeploymentProjectFacts;

/**
 * Builds the fixed structural-analysis request from already-redacted facts.
 *
 *  <p>根据已经脱敏的事实构建固定结构分析请求。
 */
public final class StructuralAnalysisPrompt {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private StructuralAnalysisPrompt() {
    }

    /**
     * Builds an AI request from type and fixed build-entrypoint facts only. / 仅从类型和固定构建入口事实构建 AI 请求。
     *
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param responseLanguage response language / 响应语言
     * @return an AI request from type and fixed build-entrypoint facts only / 仅从类型和固定构建入口事实构建 AI 请求
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static String requestBody(String model, RedactedDeploymentProjectFacts facts,
            AiResponseLanguageType responseLanguage) {
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(responseLanguage, "responseLanguage");
        return """
                {"model":"%s","temperature":0,"messages":[
                {"role":"system","content":"Use only the provided redacted static facts. Do not suggest executing commands, reading source code, sending secrets, or overriding deterministic checks. Respond in %s."},
                {"role":"user","content":"managed deployment static facts: applicationId=%s; projectType=%s; fixedBuildTool=%s. Explain what these facts mean and list at most three non-secret questions that require human confirmation."}
                ]}
                """
                .formatted(escapeJson(model), responseLanguage.promptName(), escapeJson(facts.applicationId()),
                        escapeJson(facts.projectType()), escapeJson(facts.buildTool()))
                .replaceAll("\\R", "");
    }

    /**
     * Escapes backslashes, quotes and line separators in a JSON string value.
     * <p>转义 JSON 字符串值中的反斜杠、引号及换行符。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return escape json text / 转义JSON文本
     */
    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }
}
