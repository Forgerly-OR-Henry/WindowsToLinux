package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.util.LinkedHashMap;
import java.util.Map;

@TableName("applications")
public final class WebApplicationEntity extends WebResourceEntity {
    private String serverId;
    private String kind;
    private String remoteIdentity;
    public String getServerId() { return serverId; }
    public void setServerId(String value) { serverId = value; }
    public String getKind() { return kind; }
    public void setKind(String value) { kind = value; }
    public String getRemoteIdentity() { return remoteIdentity; }
    public void setRemoteIdentity(String value) { remoteIdentity = value; }
    @Override public Map<String, Object> attributes() {
        var result = new LinkedHashMap<String, Object>();
        result.put("server_id", serverId);
        result.put("kind", kind);
        result.put("remote_identity", remoteIdentity);
        return result;
    }
    @Override public void attributes(Map<String, Object> values) {
        serverId = (String) values.get("server_id");
        kind = (String) values.get("kind");
        remoteIdentity = (String) values.get("remote_identity");
    }
}
