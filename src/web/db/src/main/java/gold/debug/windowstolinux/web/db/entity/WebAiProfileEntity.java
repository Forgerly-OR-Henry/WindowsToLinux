package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.util.LinkedHashMap;
import java.util.Map;

@TableName("ai_profiles")
public final class WebAiProfileEntity extends WebResourceEntity {
    private Integer priority;
    private Integer enabled;
    private String secretId;
    private Long secretVersion;
    public Integer getPriority() { return priority; }
    public void setPriority(Integer value) { priority = value; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer value) { enabled = value; }
    public String getSecretId() { return secretId; }
    public void setSecretId(String value) { secretId = value; }
    public Long getSecretVersion() { return secretVersion; }
    public void setSecretVersion(Long value) { secretVersion = value; }
    @Override public Map<String, Object> attributes() {
        var result = new LinkedHashMap<String, Object>();
        result.put("priority", priority);
        result.put("enabled", enabled);
        result.put("secret_id", secretId);
        result.put("secret_version", secretVersion);
        return result;
    }
    @Override public void attributes(Map<String, Object> values) {
        priority = values.get("priority") == null ? null : ((Number) values.get("priority")).intValue();
        enabled = values.get("enabled") == null ? null : ((Number) values.get("enabled")).intValue();
        secretId = (String) values.get("secret_id");
        secretVersion = values.get("secret_version") == null ? null : ((Number) values.get("secret_version")).longValue();
    }
}
