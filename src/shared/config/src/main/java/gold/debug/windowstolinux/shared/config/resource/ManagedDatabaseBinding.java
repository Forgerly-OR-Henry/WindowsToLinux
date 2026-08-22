package gold.debug.windowstolinux.shared.config.resource;

import java.util.Objects;

/** Immutable database identity bound to one reviewed component. / 绑定到一个经审阅组件的不可变数据库身份。 */
public record ManagedDatabaseBinding(
        String databaseId,
        ManagedDatabaseConnection connection
) {
    /** Validates the stable identity and non-secret connection. / 校验稳定身份及非秘密连接。 */
    public ManagedDatabaseBinding {
        databaseId = managedIdentifier(databaseId, "databaseId");
        connection = Objects.requireNonNull(connection, "connection");
    }

    static String managedIdentifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a bounded managed identifier");
        }
        return value;
    }
}
