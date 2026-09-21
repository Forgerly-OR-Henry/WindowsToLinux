package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores small non-secret desktop preferences. / 保存小型非秘密桌面偏好。
 */
public final class DesktopPreferenceRepository {
    /**
     * Factory for scoped database connections.
     * <p>限定作用域数据库连接的工厂。
     */
    private final DesktopConnectionFactory connections;

    /**
     * Creates the repository. / 创建仓库。
     *
     * @param connections factory for scoped database connections / 限定作用域数据库连接的工厂
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopPreferenceRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Saves one preference. / 保存一项偏好。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void save(String name, String value) throws SQLException {
        requireText(name, "name");
        requireText(value, "value");
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO desktop_preference (name, value) VALUES (?, ?)
                     ON CONFLICT(name) DO UPDATE SET value=excluded.value
                     """)) {
            statement.setString(1, name);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    /**
     * Finds one preference. / 查找一项偏好。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<String> find(String name) throws SQLException {
        requireText(name, "name");
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("SELECT value FROM desktop_preference WHERE name=?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString("value")) : Optional.empty();
            }
        }
    }

    /**
     * Rejects missing or blank required text.
     * <p>拒绝缺失或空白的必填文本。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
