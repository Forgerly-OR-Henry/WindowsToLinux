package gold.debug.windowstolinux.shared.model.failure;

import java.util.Objects;
import java.util.UUID;

/** Secret-free identity shared by all stages of one user operation. / 单次用户操作各阶段共享的无秘密标识。 */
public record OperationIdentity(UUID value) {
    /** Validates one operation identity. / 校验一个操作标识。 */
    public OperationIdentity {
        value = Objects.requireNonNull(value, "value");
    }

    /** Creates a new unpredictable operation identity. / 创建新的不可预测操作标识。 */
    public static OperationIdentity create() {
        return new OperationIdentity(UUID.randomUUID());
    }

    /** Parses a persisted or transported operation identity. / 解析持久化或传输的操作标识。 */
    public static OperationIdentity from(String value) {
        return new OperationIdentity(UUID.fromString(Objects.requireNonNull(value, "value")));
    }

    /** Returns the canonical lower-case UUID. / 返回规范的小写 UUID。 */
    @Override
    public String toString() {
        return value.toString();
    }
}
