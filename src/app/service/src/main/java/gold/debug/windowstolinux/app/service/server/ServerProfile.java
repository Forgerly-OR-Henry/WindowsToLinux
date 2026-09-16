package gold.debug.windowstolinux.app.service.server;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;

import java.util.Objects;

/**
 * Saved non-secret desktop connection profile.
 *
 * <p>已保存的非秘密桌面连接资料。
 *
 * @param id the {@code id} value / {@code id} 值
 * @param host the {@code host} value / {@code host} 值
 * @param sshPort the {@code sshPort} value / {@code sshPort} 值
 * @param username the {@code username} value / {@code username} 值
 * @param credentialKey the {@code credentialKey} value / {@code credentialKey} 值
 * @param credentialMode the {@code credentialMode} value / {@code credentialMode} 值
 */
public record ServerProfile(
        String id,
        String host,
        int sshPort,
        String username,
        String credentialKey,
        CredentialStorageMode credentialMode,
        String displayName
) {
    /**
     * Creates a {@code ServerProfile} instance.
     *
     * <p>创建 {@code ServerProfile} 实例。
     *
     * @param id the {@code id} value / {@code id} 值
     * @param host the {@code host} value / {@code host} 值
     * @param sshPort the {@code sshPort} value / {@code sshPort} 值
     * @param username the {@code username} value / {@code username} 值
     * @param credentialKey the {@code credentialKey} value / {@code credentialKey} 值
     * @param credentialMode the {@code credentialMode} value / {@code credentialMode} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    /** Preserves profiles created before display names were added. / 保留显示名称引入前创建的资料。 */
    public ServerProfile(String id, String host, int sshPort, String username, String credentialKey, CredentialStorageMode credentialMode) {
        this(id, host, sshPort, username, credentialKey, credentialMode, id);
    }

    public ServerProfile {
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        if (displayName.isEmpty() || displayName.length() > 120) throw new IllegalArgumentException("invalid server display name");
        Objects.requireNonNull(credentialKey, "credentialKey");
        Objects.requireNonNull(credentialMode, "credentialMode");
        new SshEndpoint(id, host, sshPort, username);
    }

    /**
     * Performs the {@code endpoint} operation.
     *
     * <p>执行 {@code endpoint} 操作。
     *
     * @return the operation result / 操作结果
     */
    public SshEndpoint endpoint() {
        return new SshEndpoint(id, host, sshPort, username);
    }

    /**
     * Stores data through {@code stored}.
     *
     * <p>通过 {@code stored} 保存数据。
     *
     * @return the operation result / 操作结果
     */
    public StoredServerProfile stored() {
        return new StoredServerProfile(id, host, sshPort, username, credentialKey, credentialMode.name(), displayName);
    }

    /**
     * Creates a value through {@code fromStored}.
     *
     * <p>通过 {@code fromStored} 创建值。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @return the operation result / 操作结果
     */
    public static ServerProfile fromStored(StoredServerProfile profile) {
        return new ServerProfile(
                profile.id(), profile.host(), profile.sshPort(), profile.username(), profile.credentialKey(),
                CredentialStorageMode.valueOf(profile.credentialMode()), profile.displayName()
        );
    }
}
