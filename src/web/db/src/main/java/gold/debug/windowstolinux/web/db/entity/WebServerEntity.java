package gold.debug.windowstolinux.web.db.entity;

import java.util.LinkedHashMap;
import java.util.Map;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Carries persistent server columns for the scoped Web repository.
 * <p>携带限定作用域 Web 仓库使用的服务器持久化列。
 */
@TableName("servers")
public final class WebServerEntity extends WebResourceEntity {
    /**
     * Reviewed server hostname or IP address.
     * <p>已审阅服务器主机名或 IP 地址。
     */
    private String host;

    /**
     * Network port number in the reviewed endpoint.
     * <p>已审阅端点中的网络端口号。
     */
    private Integer port;

    /**
     * Account name used by the reviewed connection.
     * <p>已审阅连接使用的账户名。
     */
    private String username;

    /**
     * Pinned or freshly observed host-key fingerprint.
     * <p>固定或新近观测的主机密钥指纹。
     */
    private String fingerprint;

    /**
     * Secret id.
     * <p>秘密标识。
     */
    private String secretId;

    /**
     * Secret version.
     * <p>秘密版本。
     */
    private Long secretVersion;
    /**
     * Returns reviewed server hostname or IP address.
     * <p>返回已审阅服务器主机名或 IP 地址。
     *
     * @return reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     */
    public String getHost() {
        return host;
    }

    /**
     * Updates reviewed server hostname or IP address.
     * <p>更新已审阅服务器主机名或 IP 地址。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setHost(String value) {
        host = value;
    }

    /**
     * Returns network port number in the reviewed endpoint.
     * <p>返回已审阅端点中的网络端口号。
     *
     * @return network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     */
    public Integer getPort() {
        return port;
    }

    /**
     * Updates network port number in the reviewed endpoint.
     * <p>更新已审阅端点中的网络端口号。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setPort(Integer value) {
        port = value;
    }

    /**
     * Returns account name used by the reviewed connection.
     * <p>返回已审阅连接使用的账户名。
     *
     * @return account name used by the reviewed connection / 已审阅连接使用的账户名
     */
    public String getUsername() {
        return username;
    }

    /**
     * Updates account name used by the reviewed connection.
     * <p>更新已审阅连接使用的账户名。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setUsername(String value) {
        username = value;
    }

    /**
     * Returns pinned or freshly observed host-key fingerprint.
     * <p>返回固定或新近观测的主机密钥指纹。
     *
     * @return pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     */
    public String getFingerprint() {
        return fingerprint;
    }

    /**
     * Updates pinned or freshly observed host-key fingerprint.
     * <p>更新固定或新近观测的主机密钥指纹。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setFingerprint(String value) {
        fingerprint = value;
    }

    /**
     * Returns secret id.
     * <p>返回秘密标识。
     *
     * @return secret id / 秘密标识
     */
    public String getSecretId() {
        return secretId;
    }

    /**
     * Updates secret id.
     * <p>更新秘密标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setSecretId(String value) {
        secretId = value;
    }

    /**
     * Returns secret version.
     * <p>返回秘密版本。
     *
     * @return secret version / 秘密版本
     */
    public Long getSecretVersion() {
        return secretVersion;
    }

    /**
     * Updates secret version.
     * <p>更新秘密版本。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setSecretVersion(Long value) {
        secretVersion = value;
    }

    /**
     * Projects the entity columns into the scoped resource attribute map.
     * <p>将实体列投影为限定作用域资源属性映射。
     *
     * @return constructed or resolved map / 构造或解析得到的映射
     */
    @Override
    public Map<String, Object> attributes() {
        var result = new LinkedHashMap<String, Object>();
        result.put("host", host);
        result.put("port", port);
        result.put("username", username);
        result.put("fingerprint", fingerprint);
        result.put("secret_id", secretId);
        result.put("secret_version", secretVersion);
        return result;
    }

    /**
     * Copies scoped resource attributes into the corresponding persistence columns.
     * <p>将限定作用域资源属性复制到对应持久化列。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     */
    @Override
    public void attributes(Map<String, Object> values) {
        host = (String) values.get("host");
        port = values.get("port") == null ? null : ((Number) values.get("port")).intValue();
        username = (String) values.get("username");
        fingerprint = (String) values.get("fingerprint");
        secretId = (String) values.get("secret_id");
        secretVersion = values.get("secret_version") == null
                ? null
                : ((Number) values.get("secret_version")).longValue();
    }
}
