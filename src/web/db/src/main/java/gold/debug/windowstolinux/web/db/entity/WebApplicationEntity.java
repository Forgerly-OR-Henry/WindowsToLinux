package gold.debug.windowstolinux.web.db.entity;

import java.util.LinkedHashMap;
import java.util.Map;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Carries persistent application columns for the scoped Web repository.
 * <p>携带限定作用域 Web 仓库使用的应用持久化列。
 */
@TableName("applications")
public final class WebApplicationEntity extends WebResourceEntity {
    /**
     * Persisted server identifier.
     * <p>持久化服务器标识。
     */
    private String serverId;

    /**
     * Selected member of the supported kind set.
     * <p>受支持种类集合中的所选项。
     */
    private String kind;

    /**
     * Remote identity.
     * <p>远端身份。
     */
    private String remoteIdentity;
    /**
     * Returns persisted server identifier.
     * <p>返回持久化服务器标识。
     *
     * @return persisted server identifier / 持久化服务器标识
     */
    public String getServerId() {
        return serverId;
    }

    /**
     * Updates persisted server identifier.
     * <p>更新持久化服务器标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setServerId(String value) {
        serverId = value;
    }

    /**
     * Returns selected member of the supported kind set.
     * <p>返回受支持种类集合中的所选项。
     *
     * @return selected member of the supported kind set / 受支持种类集合中的所选项
     */
    public String getKind() {
        return kind;
    }

    /**
     * Updates selected member of the supported kind set.
     * <p>更新受支持种类集合中的所选项。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setKind(String value) {
        kind = value;
    }

    /**
     * Returns remote identity.
     * <p>返回远端身份。
     *
     * @return remote identity / 远端身份
     */
    public String getRemoteIdentity() {
        return remoteIdentity;
    }

    /**
     * Updates remote identity.
     * <p>更新远端身份。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setRemoteIdentity(String value) {
        remoteIdentity = value;
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
        result.put("server_id", serverId);
        result.put("kind", kind);
        result.put("remote_identity", remoteIdentity);
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
        serverId = (String) values.get("server_id");
        kind = (String) values.get("kind");
        remoteIdentity = (String) values.get("remote_identity");
    }
}
