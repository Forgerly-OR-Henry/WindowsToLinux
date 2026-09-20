package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.util.LinkedHashMap;
import java.util.Map;

@TableName("backups")
public final class WebBackupEntity extends WebResourceEntity {
    private String applicationId;
    private String digest;
    private Long byteCount;
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String value) { applicationId = value; }
    public String getDigest() { return digest; }
    public void setDigest(String value) { digest = value; }
    public Long getByteCount() { return byteCount; }
    public void setByteCount(Long value) { byteCount = value; }
    @Override public Map<String, Object> attributes() {
        var result = new LinkedHashMap<String, Object>();
        result.put("application_id", applicationId);
        result.put("digest", digest);
        result.put("byte_count", byteCount);
        return result;
    }
    @Override public void attributes(Map<String, Object> values) {
        applicationId = (String) values.get("application_id");
        digest = (String) values.get("digest");
        byteCount = values.get("byte_count") == null ? null : ((Number) values.get("byte_count")).longValue();
    }
}
