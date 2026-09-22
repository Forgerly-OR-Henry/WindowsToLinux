package gold.debug.windowstolinux.web.db.entity;

import java.util.LinkedHashMap;
import java.util.Map;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Carries persistent source columns for the scoped Web repository.
 * <p>携带限定作用域 Web 仓库使用的源码持久化列。
 */
@TableName("sources")
public final class WebSourceEntity extends WebResourceEntity {
    /**
     * Current lifecycle or workflow state.
     * <p>当前生命周期或工作流状态。
     */
    private String state;

    /**
     * Selected member of the supported kind set.
     * <p>受支持种类集合中的所选项。
     */
    private String kind;

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
     * Returns current lifecycle or workflow state.
     * <p>返回当前生命周期或工作流状态。
     *
     * @return current lifecycle or workflow state / 当前生命周期或工作流状态
     */
    public String getState() {
        return state;
    }

    /**
     * Updates current lifecycle or workflow state.
     * <p>更新当前生命周期或工作流状态。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setState(String value) {
        state = value;
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
        result.put("state", state);
        result.put("kind", kind);
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
        state = (String) values.get("state");
        kind = (String) values.get("kind");
        digest = (String) values.get("digest");
        byteCount = values.get("byte_count") == null ? null : ((Number) values.get("byte_count")).longValue();
    }
}
