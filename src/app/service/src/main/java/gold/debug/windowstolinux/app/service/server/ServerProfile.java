package gold.debug.windowstolinux.app.service.server;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;

import java.util.Objects;

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
 * @param credentialMode selected platform credential-storage mode / 所选平台凭据存储模式
 * @param displayName display name / 显示名称
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
     * Preserves profiles created before display names were added. / 保留显示名称引入前创建的资料。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @param sshPort ssh port / SSH端口
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param credentialKey opaque lookup key in the platform secret store / 平台秘密存储中的不透明查找键
     * @param credentialMode selected platform credential-storage mode / 所选平台凭据存储模式
     */
    public ServerProfile(String id, String host, int sshPort, String username, String credentialKey, CredentialStorageMode credentialMode) {
        this(id, host, sshPort, username, credentialKey, credentialMode, id);
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
     * @param credentialMode selected platform credential-storage mode / 所选平台凭据存储模式
     * @param displayName display name / 显示名称
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ServerProfile {
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        if (displayName.isEmpty() || displayName.length() > 120) throw new IllegalArgumentException("invalid server display name");
        Objects.requireNonNull(credentialKey, "credentialKey");
        Objects.requireNonNull(credentialMode, "credentialMode");
        new SshEndpoint(id, host, sshPort, username);
    }

    /**
     * Builds ssh endpoint from the supplied endpoint inputs.
     * <p>根据所提供端点输入构建SSH端点。
     *
     * @return the operation result / 操作结果
     */
    public SshEndpoint endpoint() {
        return new SshEndpoint(id, host, sshPort, username);
    }

    /**
     * Stores data through {@code stored}.
     *
     *  <p>通过 {@code stored} 保存数据。
     *
     * @return the operation result / 操作结果
     */
    public StoredServerProfile stored() {
        return new StoredServerProfile(id, host, sshPort, username, credentialKey, credentialMode.name(), displayName);
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
        return new ServerProfile(
                profile.id(), profile.host(), profile.sshPort(), profile.username(), profile.credentialKey(),
                CredentialStorageMode.valueOf(profile.credentialMode()), profile.displayName()
        );
    }
}
