package gold.debug.windowstolinux.app.db.persistence.connection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Opens desktop SQLite connections with foreign-key enforcement and a bounded busy timeout.
 * <p>创建启用外键约束并设置有界忙等待时间的桌面 SQLite 连接。
 */
public final class DesktopConnectionFactory {
    /**
     * The SQLite JDBC connection URL.
     * <p>SQLite JDBC 连接 URL。
     */
    private final String jdbcUrl;

    /**
     * Validates and binds the inputs required by desktop connection factory.
     * <p>校验并绑定Desktop连接工厂所需输入。
     *
     * @param jdbcUrl the SQLite JDBC connection URL / SQLite JDBC 连接 URL
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopConnectionFactory(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    }

    /**
     * Opens connection.
     * <p>打开连接。
     *
     * @return the operation result / 操作结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
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
