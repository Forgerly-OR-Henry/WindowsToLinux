package gold.debug.windowstolinux.app.service.server;

import java.util.Objects;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;

/**
 * Saved non-secret desktop connection profile.
 *
 *  <p>已保存的非秘密桌面连接资料。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
 * @param sshPort ssh port / SSH端口
 * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
 * @param credentialKey opaque lookup key in the platform secret store / 平台秘密存储中的不透明查找键
 * @param displayName display name / 显示名称
 */
public record ServerProfile(String id, String host, int sshPort, String username, String credentialKey,
                            String displayName) {
    /**
     * Preserves profiles created before display names were added. / 保留显示名称引入前创建的资料。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @param sshPort ssh port / SSH端口
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param credentialKey opaque lookup key in the platform secret store / 平台秘密存储中的不透明查找键
     */
    public ServerProfile(String id, String host, int sshPort, String username, String credentialKey) {
        this(id, host, sshPort, username, credentialKey, id);
    }

    /**
     * Validates and binds the inputs required by server profile.
     * <p>校验并绑定服务器配置资料所需输入。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @param sshPort ssh port / SSH端口
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param credentialKey opaque lookup key in the platform secret store / 平台秘密存储中的不透明查找键
     * @param displayName display name / 显示名称
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ServerProfile {
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        if (displayName.isEmpty() || displayName.length() > 120)
            throw new IllegalArgumentException("invalid server display name");
        Objects.requireNonNull(credentialKey, "credentialKey");

        String checkedId = Objects.requireNonNull(id, "id").toLowerCase(java.util.Locale.ROOT);
        String checkedHost = Objects.requireNonNull(host, "host").trim();
        String checkedUser = Objects.requireNonNull(username, "username").trim();
        if (!checkedId.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("id must use lowercase letters, digits and hyphens");
        if (checkedHost.isBlank() || checkedHost.contains(" ") || checkedUser.isBlank() || checkedUser.contains("\n"))
            throw new IllegalArgumentException("host and username must be non-blank plain values");
        if (sshPort < 1 || sshPort > 65535)
            throw new IllegalArgumentException("port must be between 1 and 65535");
    }

    /**
     * Stores data through {@code stored}.
     *
     *  <p>通过 {@code stored} 保存数据。
     *
     * @return the operation result / 操作结果
     */
    public StoredServerProfile stored() {
        return new StoredServerProfile(id, host, sshPort, username, credentialKey, displayName);
    }

    /**
     * Creates a value through {@code fromStored}.
     *
     *  <p>通过 {@code fromStored} 创建值。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @return the operation result / 操作结果
     */
    public static ServerProfile fromStored(StoredServerProfile profile) {
        return new ServerProfile(profile.id(), profile.host(), profile.sshPort(), profile.username(),
            profile.credentialKey(), profile.displayName());
    }
}
