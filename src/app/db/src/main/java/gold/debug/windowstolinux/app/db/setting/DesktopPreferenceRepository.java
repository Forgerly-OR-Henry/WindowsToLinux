package gold.debug.windowstolinux.app.db.setting;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.app.db.connect.RepositoryConnections;

/**
 * Stores small non-secret desktop preferences. / 保存小型非秘密桌面偏好。
 */
public final class DesktopPreferenceRepository {
    /**
     * Factory for scoped database connections.
     * <p>限定作用域数据库连接的工厂。
     */
    private final RepositoryConnections connections;

    /** Binds owned platform connections and repository transactions. / 绑定所属平台连接和仓库事务。
     * @param connections platform transaction scope / 平台事务作用域
     */
    public DesktopPreferenceRepository(RepositoryConnections connections) {
        this.connections = java.util.Objects.requireNonNull(connections);
    }

    /**
     * Saves one preference. / 保存一项偏好。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void save(String name, String value) throws SQLException {
        requireText(name, "preference_key");
        if (!name.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("preference key must use lowercase underscores");
        requireText(value, "preference_value");
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                int changed;
                try (var update = connection
                    .prepareStatement("UPDATE desktop_preference SET preference_value=? WHERE preference_key=?")) {
                    update.setString(1, value);
                    update.setString(2, name);
                    changed = update.executeUpdate();
                }
                if (changed == 0)
                    try (var insert = connection.prepareStatement(
                        "INSERT INTO desktop_preference(preference_key,preference_value) VALUES(?,?)")) {
                        insert.setString(1, name);
                        insert.setString(2, value);
                        insert.executeUpdate();
                    }
            }
        });
    }

    /**
     * Finds one preference. / 查找一项偏好。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<String> find(String name) throws SQLException {
        requireText(name, "preference_key");
        if (!name.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("preference key must use lowercase underscores");
        try (Connection connection = connections.open();
             PreparedStatement statement = connection
                 .prepareStatement("SELECT preference_value FROM desktop_preference WHERE preference_key=?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString("preference_value")) : Optional.empty();
            }
        }
    }

    /**
     * Rejects missing or blank required text.
     * <p>拒绝缺失或空白的必填文本。
     *
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @param values values supplied to this boundary / 传入当前边界的values值
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void saveAll(java.util.Map<String, String> values) throws SQLException {
        connections.transaction(() -> {
            for (var entry : values.entrySet())
                save(entry.getKey(), entry.getValue());
        });
    }

    /** Requires nonempty persisted preference text. / 要求持久化偏好文本非空。
     * @param value value supplied to this boundary / 传入当前边界的value值
     * @param name name supplied to this boundary / 传入当前边界的name值
     */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
