package gold.debug.windowstolinux.shared.config.resource;

import java.util.Objects;

/**
 * Immutable database identity bound to one reviewed component. / 绑定到一个经审阅组件的不可变数据库身份。
 *
 * @param databaseId database id / 数据库标识
 * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
 */
public record ManagedDatabaseBinding(
        String databaseId,
        ManagedDatabaseConnection connection
) {
    /**
     * Validates the stable identity and non-secret connection. / 校验稳定身份及非秘密连接。
     *
     * @param databaseId database id / 数据库标识
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedDatabaseBinding {
        databaseId = managedIdentifier(databaseId, "databaseId");
        connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Validates a bounded identifier used for a managed resource.
     * <p>验证受管资源使用的有界标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return managed identifier text / 受管标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static String managedIdentifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a bounded managed identifier");
        }
        return value;
    }
}
