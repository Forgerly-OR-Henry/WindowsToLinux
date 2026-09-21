package gold.debug.windowstolinux.shared.model.failure;

import java.util.Objects;
import java.util.UUID;

/**
 * Secret-free identity shared by all stages of one user operation. / 单次用户操作各阶段共享的无秘密标识。
 *
 * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
 */
public record OperationIdentity(UUID value) {
    /**
     * Validates one operation identity. / 校验一个操作标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public OperationIdentity {
        value = Objects.requireNonNull(value, "value");
    }

    /**
     * Creates a new unpredictable operation identity. / 创建新的不可预测操作标识。
     *
     * @return a new unpredictable operation identity / 新的不可预测操作标识
     */
    public static OperationIdentity create() {
        return new OperationIdentity(UUID.randomUUID());
    }

    /**
     * Parses a persisted or transported operation identity. / 解析持久化或传输的操作标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return a persisted or transported operation identity / 持久化或传输的操作标识
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static OperationIdentity from(String value) {
        return new OperationIdentity(UUID.fromString(Objects.requireNonNull(value, "value")));
    }

    /**
     * Returns the canonical lower-case UUID. / 返回规范的小写 UUID。
     *
     * @return the canonical lower-case UUID / 规范的小写 UUID
     */
    @Override
    public String toString() {
        return value.toString();
    }
}
