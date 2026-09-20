package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.util.LinkedHashMap;
import java.util.Map;

@TableName("servers")
public final class WebServerEntity extends WebResourceEntity {
    private String host;
    private Integer port;
    private String username;
    private String fingerprint;
    private String secretId;
    private Long secretVersion;
    public String getHost() { return host; }
    public void setHost(String value) { host = value; }
    public Integer getPort() { return port; }
    public void setPort(Integer value) { port = value; }
    public String getUsername() { return username; }
    public void setUsername(String value) { username = value; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String value) { fingerprint = value; }
    public String getSecretId() { return secretId; }
    public void setSecretId(String value) { secretId = value; }
    public Long getSecretVersion() { return secretVersion; }
    public void setSecretVersion(Long value) { secretVersion = value; }
    @Override public Map<String, Object> attributes() {
        var result = new LinkedHashMap<String, Object>();
        result.put("host", host);
        result.put("port", port);
        result.put("username", username);
        result.put("fingerprint", fingerprint);
        result.put("secret_id", secretId);
        result.put("secret_version", secretVersion);
        return result;
    }
    @Override public void attributes(Map<String, Object> values) {
        host = (String) values.get("host");
        port = values.get("port") == null ? null : ((Number) values.get("port")).intValue();
        username = (String) values.get("username");
        fingerprint = (String) values.get("fingerprint");
        secretId = (String) values.get("secret_id");
        secretVersion = values.get("secret_version") == null ? null : ((Number) values.get("secret_version")).longValue();
    }
}
