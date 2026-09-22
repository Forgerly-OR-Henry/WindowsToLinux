package gold.debug.windowstolinux.app.db.entity;

import java.util.Objects;

/**
 * Non-secret desktop connection metadata; the password/key stays in app/secret.
 *
 *  <p>非秘密桌面连接元数据；密码或密钥保留在 app/secret 中。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
 * @param sshPort ssh port / SSH端口
 * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
 * @param credentialKey opaque lookup key in the platform secret store / 平台秘密存储中的不透明查找键
 * @param credentialMode selected platform credential-storage mode / 所选平台凭据存储模式
 * @param displayName display name / 显示名称
 */
public record StoredServerProfile(String id, String host, int sshPort, String username, String credentialKey,
        String credentialMode, String displayName) {
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
    public StoredServerProfile(String id, String host, int sshPort, String username, String credentialKey,
            String credentialMode) {
        this(id, host, sshPort, username, credentialKey, credentialMode, id);
    }

    /**
     * Validates and binds the inputs required by stored server profile.
     * <p>校验并绑定已存储服务器配置资料所需输入。
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
    public StoredServerProfile {
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        if (displayName.isEmpty() || displayName.length() > 120)
            throw new IllegalArgumentException("invalid server display name");
        id = requireId(id, "id");
        host = requireText(host, "host");
        username = requireText(username, "username");
        credentialKey = requireText(credentialKey, "credentialKey");
        credentialMode = requireText(credentialMode, "credentialMode");
        if (sshPort < 1 || sshPort > 65535) {
            throw new IllegalArgumentException("sshPort must be between 1 and 65535");
        }
    }

    /**
     * Validates and returns stable identifier within the owning registry and rejects inputs outside the declared constraints.
     * <p>校验并返回所属登记表内的稳定标识并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require id text / 要求标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static String requireId(String value, String name) {
        value = requireText(value, name);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    /**
     * Trims required text and rejects missing or invalid content.
     * <p>去除必填文本首尾空白，并拒绝缺失或无效内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require text text / 要求文本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
