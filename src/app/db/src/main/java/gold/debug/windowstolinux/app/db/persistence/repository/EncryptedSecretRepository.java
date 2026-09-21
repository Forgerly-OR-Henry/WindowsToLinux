package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.OpaqueSecret;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores only encrypted opaque secret payloads. / 仅保存加密的不透明秘密载荷。
 */
public final class EncryptedSecretRepository {
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
    public EncryptedSecretRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Saves an encrypted payload. / 保存加密载荷。
     *
     * @param secret secret / 秘密
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void save(OpaqueSecret secret) throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO encrypted_secret (secret_key, algorithm, salt, nonce, ciphertext) VALUES (?, ?, ?, ?, ?)
                     ON CONFLICT(secret_key) DO UPDATE SET algorithm=excluded.algorithm, salt=excluded.salt,
                         nonce=excluded.nonce, ciphertext=excluded.ciphertext
                     """)) {
            statement.setString(1, secret.key());
            statement.setString(2, secret.algorithm());
            statement.setBytes(3, secret.salt());
            statement.setBytes(4, secret.nonce());
            statement.setBytes(5, secret.ciphertext());
            statement.executeUpdate();
        }
    }

    /**
     * Finds an encrypted payload. / 查找加密载荷。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<OpaqueSecret> find(String key) throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT secret_key, algorithm, salt, nonce, ciphertext FROM encrypted_secret WHERE secret_key=?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new OpaqueSecret(result.getString("secret_key"),
                        result.getString("algorithm"), result.getBytes("salt"), result.getBytes("nonce"),
                        result.getBytes("ciphertext"))) : Optional.empty();
            }
        }
    }

    /**
     * Deletes one exact encrypted payload and reports whether a row existed. / 删除一个精确加密载荷并报告是否存在记录。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return true when deletes one exact encrypted payload and reports whether a row existed, false otherwise / 删除一个精确加密载荷并报告是否存在记录时为 true，否则为 false
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public boolean delete(String key) throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM encrypted_secret WHERE secret_key=?")) {
            statement.setString(1, Objects.requireNonNull(key, "key"));
            return statement.executeUpdate() == 1;
        }
    }
}
