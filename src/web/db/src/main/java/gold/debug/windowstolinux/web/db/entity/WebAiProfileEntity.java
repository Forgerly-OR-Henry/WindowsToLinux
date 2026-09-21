package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Carries persistent ai profile columns for the scoped Web repository.
 * <p>携带限定作用域 Web 仓库使用的AI配置资料持久化列。
 */
@TableName("ai_profiles")
public final class WebAiProfileEntity extends WebResourceEntity {
    /**
     * Priority.
     * <p>优先级。
     */
    private Integer priority;
    /**
     * Whether this configured capability participates in execution.
     * <p>当前配置能力是否参与执行。
     */
    private Integer enabled;
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
     * Returns priority.
     * <p>返回优先级。
     *
     * @return priority / 优先级
     */
    public Integer getPriority() { return priority; }
    /**
     * Updates priority.
     * <p>更新优先级。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setPriority(Integer value) { priority = value; }
    /**
     * Returns whether this configured capability participates in execution.
     * <p>返回当前配置能力是否参与执行。
     *
     * @return whether this configured capability participates in execution / 当前配置能力是否参与执行
     */
    public Integer getEnabled() { return enabled; }
    /**
     * Updates whether this configured capability participates in execution.
     * <p>更新当前配置能力是否参与执行。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setEnabled(Integer value) { enabled = value; }
    /**
     * Returns secret id.
     * <p>返回秘密标识。
     *
     * @return secret id / 秘密标识
     */
    public String getSecretId() { return secretId; }
    /**
     * Updates secret id.
     * <p>更新秘密标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setSecretId(String value) { secretId = value; }
    /**
     * Returns secret version.
     * <p>返回秘密版本。
     *
     * @return secret version / 秘密版本
     */
    public Long getSecretVersion() { return secretVersion; }
    /**
     * Updates secret version.
     * <p>更新秘密版本。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setSecretVersion(Long value) { secretVersion = value; }
    /**
     * Projects the entity columns into the scoped resource attribute map.
     * <p>将实体列投影为限定作用域资源属性映射。
     *
     * @return constructed or resolved map / 构造或解析得到的映射
     */
    @Override public Map<String, Object> attributes() {
        var result = new LinkedHashMap<String, Object>();
        result.put("priority", priority);
        result.put("enabled", enabled);
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
    @Override public void attributes(Map<String, Object> values) {
        priority = values.get("priority") == null ? null : ((Number) values.get("priority")).intValue();
        enabled = values.get("enabled") == null ? null : ((Number) values.get("enabled")).intValue();
        secretId = (String) values.get("secret_id");
        secretVersion = values.get("secret_version") == null ? null : ((Number) values.get("secret_version")).longValue();
    }
}
