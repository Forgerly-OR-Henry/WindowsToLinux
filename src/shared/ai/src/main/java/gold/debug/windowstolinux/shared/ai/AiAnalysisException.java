package gold.debug.windowstolinux.shared.ai;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Map;
import java.util.Objects;

/**
 * A safe, non-secret failure from the optional AI explanation path.
 *
 *  <p>可选 AI 解释路径产生的安全、非秘密失败。
 */
public final class AiAnalysisException extends Exception implements FailureCarrier {
    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final FailureDescriptor failure;

    /**
     * Validates and binds the inputs required by ai analysis exception.
     * <p>校验并绑定AI分析异常所需输入。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public AiAnalysisException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /**
     * Creates ai analysis exception.
     * <p>创建AI分析异常。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return ai analysis exception / AI分析异常
     */
    public static AiAnalysisException create(AiAnalysisFailureType type, String diagnostic) {
        return create(type, Map.of(), diagnostic, null);
    }

    /**
     * Creates a typed AI failure with its original cause. / 创建带原始原因的类型化 AI 失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a typed AI failure with its original cause / 带原始原因的类型化 AI 失败
     */
    public static AiAnalysisException create(AiAnalysisFailureType type, String diagnostic, Throwable cause) {
        return create(type, Map.of(), diagnostic, cause);
    }

    /**
     * Creates a typed AI failure with safe message arguments. / 创建带安全消息参数的类型化 AI 失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a typed AI failure with safe message arguments / 带安全消息参数的类型化 AI 失败
     */
    public static AiAnalysisException create(
            AiAnalysisFailureType type, Map<String, ?> arguments, String diagnostic, Throwable cause) {
        return new AiAnalysisException(
                FailureDescriptor.create(type, OperationIdentity.create(), arguments, diagnostic), cause);
    }

    /**
     * Returns structured failure occurrence retained for safe reporting.
     * <p>返回保留用于安全报告的结构化失败实例。
     *
     * @return structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    @Override public FailureDescriptor failure() { return failure; }
}
