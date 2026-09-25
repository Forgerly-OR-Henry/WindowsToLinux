package gold.debug.windowstolinux.web.db.entity;

import java.util.LinkedHashMap;
import java.util.Map;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Carries persistent backup columns for the scoped Web repository.
 * <p>携带限定作用域 Web 仓库使用的备份持久化列。
 */
@TableName("backups")
public final class WebBackupEntity extends WebResourceEntity {
    /**
     * Managed application identifier.
     * <p>受管应用标识。
     */
    private String applicationId;

    /**
     * Content identity used for independent verification.
     * <p>独立验证所用的内容身份。
     */
    private String digest;

    /**
     * Measured content length in bytes.
     * <p>实测内容长度，单位为字节。
     */
    private Long byteCount;
    /**
     * Returns managed application identifier.
     * <p>返回受管应用标识。
     *
     * @return managed application identifier / 受管应用标识
     */
    public String getApplicationId() {
        return applicationId;
    }

    /**
     * Updates managed application identifier.
     * <p>更新受管应用标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setApplicationId(String value) {
        applicationId = value;
    }

    /**
     * Returns content identity used for independent verification.
     * <p>返回独立验证所用的内容身份。
     *
     * @return content identity used for independent verification / 独立验证所用的内容身份
     */
    public String getDigest() {
        return digest;
    }

    /**
     * Updates content identity used for independent verification.
     * <p>更新独立验证所用的内容身份。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setDigest(String value) {
        digest = value;
    }

    /**
     * Returns measured content length in bytes.
     * <p>返回实测内容长度，单位为字节。
     *
     * @return measured content length in bytes / 实测内容长度，单位为字节
     */
    public Long getByteCount() {
        return byteCount;
    }

    /**
     * Updates measured content length in bytes.
     * <p>更新实测内容长度，单位为字节。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setByteCount(Long value) {
        byteCount = value;
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
        result.put("application_id", applicationId);
        result.put("digest", digest);
        result.put("byte_count", byteCount);
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
        applicationId = (String) values.get("application_id");
        digest = (String) values.get("digest");
        byteCount = values.get("byte_count") == null ? null : ((Number) values.get("byte_count")).longValue();
    }
}
