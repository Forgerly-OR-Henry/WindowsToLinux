package gold.debug.windowstolinux.shared.ai.prompt;

import gold.debug.windowstolinux.shared.ai.redaction.RedactedDeploymentProjectFacts;

import java.util.Objects;

/**
 * Builds the fixed structural-analysis request from already-redacted facts.
 *
 * <p>根据已经脱敏的事实构建固定结构分析请求。
 */
public final class StructuralAnalysisPrompt {
    private StructuralAnalysisPrompt() {
    }

    /**
     * Performs the {@code requestBody} operation.
     *
     * <p>执行 {@code requestBody} 操作。
     *
     * @param model the {@code model} value / {@code model} 值
     * @param facts the {@code facts} value / {@code facts} 值
     * @param responseLanguage the {@code responseLanguage} value / {@code responseLanguage} 值
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    /** Builds an AI request from type and fixed build-entrypoint facts only. / 仅从类型和固定构建入口事实构建 AI 请求。 */
    public static String requestBody(String model, RedactedDeploymentProjectFacts facts,
                                     AiResponseLanguageType responseLanguage) {
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(responseLanguage, "responseLanguage");
        return """
                {"model":"%s","temperature":0,"messages":[
                {"role":"system","content":"Use only the provided redacted static facts. Do not suggest executing commands, reading source code, sending secrets, or overriding deterministic checks. Respond in %s."},
                {"role":"user","content":"managed deployment static facts: applicationId=%s; projectType=%s; fixedBuildTool=%s. Explain what these facts mean and list at most three non-secret questions that require human confirmation."}
                ]}
                """.formatted(escapeJson(model), responseLanguage.promptName(), escapeJson(facts.applicationId()),
                escapeJson(facts.projectType()), escapeJson(facts.buildTool())).replaceAll("\\R", "");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
