package gold.debug.windowstolinux.shared.ai;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Map;
import java.util.Objects;

/**
 * A safe, non-secret failure from the optional AI explanation path.
 *
 * <p>可选 AI 解释路径产生的安全、非秘密失败。
 */
public final class AiAnalysisException extends Exception implements FailureCarrier {
    private final FailureDescriptor failure;

    /**
     * Creates a {@code AiAnalysisException} instance.
     *
     * <p>创建 {@code AiAnalysisException} 实例。
     *
     * @param userMessage the {@code userMessage} value / {@code userMessage} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     */
    public AiAnalysisException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /**
     * Creates a {@code AiAnalysisException} instance.
     *
     * <p>创建 {@code AiAnalysisException} 实例。
     *
     * @param userMessage the {@code userMessage} value / {@code userMessage} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     * @param cause the {@code cause} value / {@code cause} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public static AiAnalysisException create(AiAnalysisFailureType type, String diagnostic) {
        return create(type, Map.of(), diagnostic, null);
    }

    /** Creates a typed AI failure with its original cause. / 创建带原始原因的类型化 AI 失败。 */
    public static AiAnalysisException create(AiAnalysisFailureType type, String diagnostic, Throwable cause) {
        return create(type, Map.of(), diagnostic, cause);
    }

    /** Creates a typed AI failure with safe message arguments. / 创建带安全消息参数的类型化 AI 失败。 */
    public static AiAnalysisException create(
            AiAnalysisFailureType type, Map<String, ?> arguments, String diagnostic, Throwable cause) {
        return new AiAnalysisException(
                FailureDescriptor.create(type, OperationIdentity.create(), arguments, diagnostic), cause);
    }

    @Override public FailureDescriptor failure() { return failure; }
}
