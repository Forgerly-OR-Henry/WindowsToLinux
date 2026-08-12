package gold.debug.windowstolinux.app.db.repository;

import gold.debug.windowstolinux.app.db.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.OpaqueSecret;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** Stores only encrypted opaque secret payloads. / 仅保存加密的不透明秘密载荷。 */
public final class EncryptedSecretRepository {
    private final DesktopConnectionFactory connections;

    /** Creates the repository. / 创建仓库。 */
    public EncryptedSecretRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** Saves an encrypted payload. / 保存加密载荷。 */
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

    /** Finds an encrypted payload. / 查找加密载荷。 */
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
}
