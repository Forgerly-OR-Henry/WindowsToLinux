package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.util.LinkedHashMap;
import java.util.Map;

@TableName("sources")
public final class WebSourceEntity extends WebResourceEntity {
    private String state;
    private String kind;
    private String digest;
    private Long byteCount;
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public String getKind() { return kind; }
    public void setKind(String value) { kind = value; }
    public String getDigest() { return digest; }
    public void setDigest(String value) { digest = value; }
    public Long getByteCount() { return byteCount; }
    public void setByteCount(Long value) { byteCount = value; }
    @Override public Map<String, Object> attributes() {
        var result = new LinkedHashMap<String, Object>();
        result.put("state", state);
        result.put("kind", kind);
        result.put("digest", digest);
        result.put("byte_count", byteCount);
        return result;
    }
    @Override public void attributes(Map<String, Object> values) {
        state = (String) values.get("state");
        kind = (String) values.get("kind");
        digest = (String) values.get("digest");
        byteCount = values.get("byte_count") == null ? null : ((Number) values.get("byte_count")).longValue();
    }
}
