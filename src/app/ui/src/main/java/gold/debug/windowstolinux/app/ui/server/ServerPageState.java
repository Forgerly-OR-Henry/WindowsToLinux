package gold.debug.windowstolinux.app.ui.server;

import java.util.Arrays;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

/**
 * Holds server selection and connection observations for the desktop server page.
 * <p>持有桌面服务器页面的服务器选择及连接观测。
 */
public final class ServerPageState implements AutoCloseable {
    /**
     * Stable identifier within the owning registry.
     * <p>所属登记表内的稳定标识。
     */
    private final String id;

    /**
     * Reviewed server hostname or IP address.
     * <p>已审阅服务器主机名或 IP 地址。
     */
    private final String host;

    /**
     * Network port number in the reviewed endpoint.
     * <p>已审阅端点中的网络端口号。
     */
    private final String port;

    /**
     * Account name used by the reviewed connection.
     * <p>已审阅连接使用的账户名。
     */
    private final String username;

    /**
     * Temporary plaintext authentication buffer.
     * <p>临时明文认证缓冲区。
     */
    private final char[] password;

    /**
     * Selected platform credential-storage mode.
     * <p>所选平台凭据存储模式。
     */
    private final CredentialStorageMode credentialMode;

    /**
     * Master-password buffer used to unlock protected credentials.
     * <p>用于解锁受保护凭据的主密码缓冲区。
     */
    private final char[] masterPassword;

    /**
     * Destination receiving the produced content.
     * <p>接收所生成内容的目标。
     */
    private final String output;

    /**
     * Display name.
     * <p>显示名称。
     * <p>search:
     * Search.
     * <p>搜索。
     * <p>credentialKey:
     * Opaque lookup key in the platform secret store.
     * <p>平台秘密存储中的不透明查找键。
     */
    private final String displayName, search, credentialKey;

    /**
     * Initializes server page state through its shared constructor contract.
     * <p>通过共享构造契约初始化服务器页面状态。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @param credentialMode selected platform credential-storage mode / 所选平台凭据存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param output destination receiving the produced content / 接收所生成内容的目标
     */
    public ServerPageState(String id, String host, String port, String username, char[] password,
            CredentialStorageMode credentialMode, char[] masterPassword, String output) {
        this(id, host, port, username, password, credentialMode, masterPassword, output, id, "");
    }

    /**
     * Captures selected display name and search query. / 捕获所选显示名称及搜索条件。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @param credentialMode selected platform credential-storage mode / 所选平台凭据存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param displayName display name / 显示名称
     * @param search search / 搜索
     */
    public ServerPageState(String id, String host, String port, String username, char[] password,
            CredentialStorageMode credentialMode, char[] masterPassword, String output, String displayName,
            String search) {
        this(id, host, port, username, password, credentialMode, masterPassword, output, displayName, search,
                "ssh/" + id + "/password");
    }

    /**
     * Preserves the exact credential reference across appearance changes. / 在外观变化时保留精确凭据引用。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @param credentialMode selected platform credential-storage mode / 所选平台凭据存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param displayName display name / 显示名称
     * @param search search / 搜索
     * @param credentialKey opaque lookup key in the platform secret store / 平台秘密存储中的不透明查找键
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ServerPageState(String id, String host, String port, String username, char[] password,
            CredentialStorageMode credentialMode, char[] masterPassword, String output, String displayName,
            String search, String credentialKey) {
        this.credentialKey = Objects.requireNonNull(credentialKey, "credentialKey");
        this.displayName = displayName;
        this.search = search;
        this.id = Objects.requireNonNull(id, "id");
        this.host = Objects.requireNonNull(host, "host");
        this.port = Objects.requireNonNull(port, "port");
        this.username = Objects.requireNonNull(username, "username");
        this.password = password.clone();
        this.credentialMode = Objects.requireNonNull(credentialMode, "credentialMode");
        this.masterPassword = masterPassword.clone();
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * Returns stable identifier within the owning registry.
     * <p>返回所属登记表内的稳定标识。
     *
     * @return the operation result / 操作结果
     */
    public String id() {
        return id;
    }

    /**
     * Returns reviewed server hostname or IP address.
     * <p>返回已审阅服务器主机名或 IP 地址。
     *
     * @return the operation result / 操作结果
     */
    public String host() {
        return host;
    }

    /**
     * Returns network port number in the reviewed endpoint.
     * <p>返回已审阅端点中的网络端口号。
     *
     * @return the operation result / 操作结果
     */
    public String port() {
        return port;
    }

    /**
     * Returns account name used by the reviewed connection.
     * <p>返回已审阅连接使用的账户名。
     *
     * @return the operation result / 操作结果
     */
    public String username() {
        return username;
    }

    /**
     * Returns temporary plaintext authentication buffer.
     * <p>返回临时明文认证缓冲区。
     *
     * @return the operation result / 操作结果
     */
    public char[] password() {
        return password.clone();
    }

    /**
     * Returns selected platform credential-storage mode.
     * <p>返回所选平台凭据存储模式。
     *
     * @return the operation result / 操作结果
     */
    public CredentialStorageMode credentialMode() {
        return credentialMode;
    }

    /**
     * Returns master-password buffer used to unlock protected credentials.
     * <p>返回用于解锁受保护凭据的主密码缓冲区。
     *
     * @return the operation result / 操作结果
     */
    public char[] masterPassword() {
        return masterPassword.clone();
    }

    /**
     * Returns destination receiving the produced content.
     * <p>返回接收所生成内容的目标。
     *
     * @return the operation result / 操作结果
     */
    public String output() {
        return output;
    }

    /**
     * Returns the selected display name. / 返回所选显示名称。
     *
     * @return the selected display name / 所选显示名称
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns the inventory query. / 返回列表查询。
     *
     * @return the inventory query / 列表查询
     */
    public String search() {
        return search;
    }

    /**
     * Returns the saved credential reference, never the secret. / 返回已保存的凭据引用，不包含秘密。
     *
     * @return the saved credential reference, never the secret / 已保存的凭据引用，不包含秘密
     */
    public String credentialKey() {
        return credentialKey;
    }

    /**
     * Closes this resource. / 关闭此资源。
     */
    @Override
    public void close() {
        Arrays.fill(password, '\0');
        Arrays.fill(masterPassword, '\0');
    }
}
