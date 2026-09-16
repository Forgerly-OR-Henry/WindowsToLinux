package gold.debug.windowstolinux.app.db.entity;

import java.util.Objects;

/**
 * Non-secret desktop connection metadata; the password/key stays in app/secret.
 *
 * <p>非秘密桌面连接元数据；密码或密钥保留在 app/secret 中。
 *
 * @param id the {@code id} value / {@code id} 值
 * @param host the {@code host} value / {@code host} 值
 * @param sshPort the {@code sshPort} value / {@code sshPort} 值
 * @param username the {@code username} value / {@code username} 值
 * @param credentialKey the {@code credentialKey} value / {@code credentialKey} 值
 * @param credentialMode the {@code credentialMode} value / {@code credentialMode} 值
 */
public record StoredServerProfile(
        String id,
        String host,
        int sshPort,
        String username,
        String credentialKey,
        String credentialMode,
        String displayName
) {
    /**
     * Creates a {@code StoredServerProfile} instance.
     *
     * <p>创建 {@code StoredServerProfile} 实例。
     *
     * @param id the {@code id} value / {@code id} 值
     * @param host the {@code host} value / {@code host} 值
     * @param sshPort the {@code sshPort} value / {@code sshPort} 值
     * @param username the {@code username} value / {@code username} 值
     * @param credentialKey the {@code credentialKey} value / {@code credentialKey} 值
     * @param credentialMode the {@code credentialMode} value / {@code credentialMode} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     */
    /** Preserves profiles created before display names were added. / 保留显示名称引入前创建的资料。 */
    public StoredServerProfile(String id, String host, int sshPort, String username, String credentialKey, String credentialMode) {
        this(id, host, sshPort, username, credentialKey, credentialMode, id);
    }

    public StoredServerProfile {
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        if (displayName.isEmpty() || displayName.length() > 120) throw new IllegalArgumentException("invalid server display name");
        id = requireId(id, "id");
        host = requireText(host, "host");
        username = requireText(username, "username");
        credentialKey = requireText(credentialKey, "credentialKey");
        credentialMode = requireText(credentialMode, "credentialMode");
        if (sshPort < 1 || sshPort > 65535) {
            throw new IllegalArgumentException("sshPort must be between 1 and 65535");
        }
    }

    private static String requireId(String value, String name) {
        value = requireText(value, name);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
