package gold.debug.windowstolinux.app.db.persistence.connection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Provides the {@code DesktopConnectionFactory} implementation.
 *
 * <p>提供 {@code DesktopConnectionFactory} 实现。
 */
public final class DesktopConnectionFactory {
    private final String jdbcUrl;

    /**
     * Creates a {@code DesktopConnectionFactory} instance.
     *
     * <p>创建 {@code DesktopConnectionFactory} 实例。
     *
     * @param jdbcUrl the {@code jdbcUrl} value / {@code jdbcUrl} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DesktopConnectionFactory(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    }

    /**
     * Performs the {@code open} operation.
     *
     * <p>执行 {@code open} 操作。
     *
     * @return the operation result / 操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }
}
