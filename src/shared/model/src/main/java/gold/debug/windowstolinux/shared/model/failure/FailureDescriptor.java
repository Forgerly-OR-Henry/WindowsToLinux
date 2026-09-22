package gold.debug.windowstolinux.shared.model.failure;

import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

/**
 * Immutable occurrence of a structured, secret-free failure. / 单次结构化无秘密失败的不可变描述。
 *
 * @param definition definition / 定义
 * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
 * @param userMessage user message / 用户消息
 * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
 * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
 * @param recoveryDisposition verified or unknown result of the selected recovery action / 所选恢复动作的已验证或未知结果
 */
public record FailureDescriptor(FailureDefinition definition, OperationIdentity operationIdentity,
        LocalizedMessage userMessage, String diagnostic, FailureRecoveryAction recoveryAction,
        FailureRecoveryDisposition recoveryDisposition) {
    /**
     * Validates one structured failure occurrence. / 校验一次结构化失败。
     *
     * @param definition definition / 定义
     * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
     * @param userMessage user message / 用户消息
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     * @param recoveryDisposition verified or unknown result of the selected recovery action / 所选恢复动作的已验证或未知结果
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public FailureDescriptor {
        definition = Objects.requireNonNull(definition, "definition");
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        userMessage = Objects.requireNonNull(userMessage, "userMessage");
        diagnostic = requireText(diagnostic, "diagnostic");
        recoveryAction = Objects.requireNonNull(recoveryAction, "recoveryAction");
        recoveryDisposition = Objects.requireNonNull(recoveryDisposition, "recoveryDisposition");
        if (!definition.messageKey().equals(userMessage.key())) {
            throw new IllegalArgumentException("failure message key must match its definition");
        }
    }

    /**
     * Creates an unrecovered failure without message arguments. / 创建无消息参数且尚未恢复的失败。
     *
     * @param definition definition / 定义
     * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return an unrecovered failure without message arguments / 无消息参数且尚未恢复的失败
     */
    public static FailureDescriptor create(FailureDefinition definition, OperationIdentity operationIdentity,
            String diagnostic) {
        return create(definition, operationIdentity, Map.of(), diagnostic);
    }

    /**
     * Creates an unrecovered failure with named message arguments. / 创建带命名消息参数且尚未恢复的失败。
     *
     * @param definition definition / 定义
     * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return an unrecovered failure with named message arguments / 带命名消息参数且尚未恢复的失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static FailureDescriptor create(FailureDefinition definition, OperationIdentity operationIdentity,
            Map<String, ?> arguments, String diagnostic) {
        Objects.requireNonNull(definition, "definition");
        FailureRecoveryDisposition disposition = definition.recoveryAction() == FailureRecoveryAction.NONE
                ? FailureRecoveryDisposition.NOT_REQUIRED
                : FailureRecoveryDisposition.NOT_ATTEMPTED;
        return new FailureDescriptor(definition, operationIdentity,
                LocalizedMessage.of(definition.messageKey(), arguments), diagnostic, definition.recoveryAction(),
                disposition);
    }

    /**
     * Returns a copy with the verified recovery result. / 返回带有已验证恢复结果的副本。
     *
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param disposition disposition / 处置方式
     * @return a copy with the verified recovery result / 带有已验证恢复结果的副本
     */
    public FailureDescriptor withRecovery(FailureRecoveryAction action, FailureRecoveryDisposition disposition) {
        return new FailureDescriptor(definition, operationIdentity, userMessage, diagnostic, action, disposition);
    }

    /**
     * Returns a copy bound to the identity of its enclosing operation. / 返回绑定到外层操作标识的副本。
     *
     * @param identity identity / 身份
     * @return a copy bound to the identity of its enclosing operation / 绑定到外层操作标识的副本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public FailureDescriptor withOperationIdentity(OperationIdentity identity) {
        return new FailureDescriptor(definition, Objects.requireNonNull(identity, "identity"), userMessage, diagnostic,
                recoveryAction, recoveryDisposition);
    }

    /**
     * Returns the stable failure code. / 返回稳定错误码。
     *
     * @return the stable failure code / 稳定错误码
     */
    public String code() {
        return definition.code();
    }

    /**
     * Trims required text and rejects missing or invalid content.
     * <p>去除必填文本首尾空白，并拒绝缺失或无效内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require text text / 要求文本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
