package gold.debug.windowstolinux.app.secret;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Map;
import java.util.Objects;

/**
 * Sensitive-store failures are intentionally not expanded with plaintext input.
 *
 *  <p>敏感存储失败被刻意设计为不附加明文输入。
 */
public final class SecretStoreException extends Exception implements FailureCarrier {
    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final FailureDescriptor failure;

    /**
     * Validates and binds the inputs required by secret store exception.
     * <p>校验并绑定秘密存储异常所需输入。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SecretStoreException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /**
     * Creates secret store exception.
     * <p>创建秘密存储异常。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return secret store exception / 秘密存储异常
     */
    public static SecretStoreException create(SecretStoreFailureType type, String diagnostic) {
        return create(type, Map.of(), diagnostic, null);
    }

    /**
     * Creates a typed failure with its original cause. / 创建带原始原因的类型化失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a typed failure with its original cause / 带原始原因的类型化失败
     */
    public static SecretStoreException create(SecretStoreFailureType type, String diagnostic, Throwable cause) {
        return create(type, Map.of(), diagnostic, cause);
    }

    /**
     * Creates a typed failure with safe message arguments. / 创建带安全消息参数的类型化失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a typed failure with safe message arguments / 带安全消息参数的类型化失败
     */
    public static SecretStoreException create(
            SecretStoreFailureType type, Map<String, ?> arguments, String diagnostic, Throwable cause) {
        return new SecretStoreException(
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
