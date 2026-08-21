package gold.debug.windowstolinux.shared.model.failure;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Map;
import java.util.Objects;

/** Immutable occurrence of a structured, secret-free failure. / 单次结构化无秘密失败的不可变描述。 */
public record FailureDescriptor(
        FailureDefinition definition,
        OperationIdentity operationIdentity,
        LocalizedMessage userMessage,
        String diagnostic,
        FailureRecoveryAction recoveryAction,
        FailureRecoveryDisposition recoveryDisposition
) {
    /** Validates one structured failure occurrence. / 校验一次结构化失败。 */
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

    /** Creates an unrecovered failure without message arguments. / 创建无消息参数且尚未恢复的失败。 */
    public static FailureDescriptor create(
            FailureDefinition definition,
            OperationIdentity operationIdentity,
            String diagnostic
    ) {
        return create(definition, operationIdentity, Map.of(), diagnostic);
    }

    /** Creates an unrecovered failure with named message arguments. / 创建带命名消息参数且尚未恢复的失败。 */
    public static FailureDescriptor create(
            FailureDefinition definition,
            OperationIdentity operationIdentity,
            Map<String, ?> arguments,
            String diagnostic
    ) {
        Objects.requireNonNull(definition, "definition");
        FailureRecoveryDisposition disposition = definition.recoveryAction() == FailureRecoveryAction.NONE
                ? FailureRecoveryDisposition.NOT_REQUIRED : FailureRecoveryDisposition.NOT_ATTEMPTED;
        return new FailureDescriptor(definition, operationIdentity,
                LocalizedMessage.of(definition.messageKey(), arguments), diagnostic,
                definition.recoveryAction(), disposition);
    }

    /** Returns a copy with the verified recovery result. / 返回带有已验证恢复结果的副本。 */
    public FailureDescriptor withRecovery(
            FailureRecoveryAction action,
            FailureRecoveryDisposition disposition
    ) {
        return new FailureDescriptor(definition, operationIdentity, userMessage, diagnostic, action, disposition);
    }

    /** Returns a copy bound to the identity of its enclosing operation. / 返回绑定到外层操作标识的副本。 */
    public FailureDescriptor withOperationIdentity(OperationIdentity identity) {
        return new FailureDescriptor(definition, Objects.requireNonNull(identity, "identity"), userMessage,
                diagnostic, recoveryAction, recoveryDisposition);
    }

    /** Returns the stable failure code. / 返回稳定错误码。 */
    public String code() {
        return definition.code();
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
