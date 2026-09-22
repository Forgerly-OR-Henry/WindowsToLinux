package gold.debug.windowstolinux.web.db.entity;

import java.util.Map;

/**
 * Defines common revision columns; database operations also require workspace identity.
 * <p>定义共享修订列；数据库操作还须提供工作区身份。
 */
public abstract class WebResourceEntity {
    /**
     * Server-assigned workspace identifier.
     * <p>服务端分配的工作区标识。
     */
    private String workspaceId;

    /**
     * Stable identifier within the owning registry.
     * <p>所属登记表内的稳定标识。
     */
    private String id;

    /**
     * Identifier of the user who created the resource.
     * <p>创建资源的用户标识。
     */
    private String createdBy;

    /**
     * Human-readable name or diagnostic field label.
     * <p>可读名称或诊断字段标签。
     */
    private String name;

    /**
     * Document.
     * <p>文档。
     */
    private String document;

    /**
     * Version of the relevant protocol, configuration or runtime.
     * <p>相应协议、配置或运行时的版本。
     */
    private Long version;

    /**
     * Instant at which this record was created.
     * <p>当前记录创建时刻。
     */
    private String createdAt;

    /**
     * Updated at.
     * <p>已更新时刻。
     */
    private String updatedAt;
    /**
     * Returns server-assigned workspace identifier.
     * <p>返回服务端分配的工作区标识。
     *
     * @return server-assigned workspace identifier / 服务端分配的工作区标识
     */
    public String getWorkspaceId() {
        return workspaceId;
    }

    /**
     * Updates server-assigned workspace identifier.
     * <p>更新服务端分配的工作区标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setWorkspaceId(String value) {
        workspaceId = value;
    }

    /**
     * Returns stable identifier within the owning registry.
     * <p>返回所属登记表内的稳定标识。
     *
     * @return stable identifier within the owning registry / 所属登记表内的稳定标识
     */
    public String getId() {
        return id;
    }

    /**
     * Updates stable identifier within the owning registry.
     * <p>更新所属登记表内的稳定标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setId(String value) {
        id = value;
    }

    /**
     * Returns identifier of the user who created the resource.
     * <p>返回创建资源的用户标识。
     *
     * @return identifier of the user who created the resource / 创建资源的用户标识
     */
    public String getCreatedBy() {
        return createdBy;
    }

    /**
     * Updates identifier of the user who created the resource.
     * <p>更新创建资源的用户标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setCreatedBy(String value) {
        createdBy = value;
    }

    /**
     * Returns human-readable name or diagnostic field label.
     * <p>返回可读名称或诊断字段标签。
     *
     * @return human-readable name or diagnostic field label / 可读名称或诊断字段标签
     */
    public String getName() {
        return name;
    }

    /**
     * Updates human-readable name or diagnostic field label.
     * <p>更新可读名称或诊断字段标签。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setName(String value) {
        name = value;
    }

    /**
     * Returns document.
     * <p>返回文档。
     *
     * @return document / 文档
     */
    public String getDocument() {
        return document;
    }

    /**
     * Updates document.
     * <p>更新文档。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setDocument(String value) {
        document = value;
    }

    /**
     * Returns version of the relevant protocol, configuration or runtime.
     * <p>返回相应协议、配置或运行时的版本。
     *
     * @return version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Updates version of the relevant protocol, configuration or runtime.
     * <p>更新相应协议、配置或运行时的版本。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setVersion(Long value) {
        version = value;
    }

    /**
     * Returns instant at which this record was created.
     * <p>返回当前记录创建时刻。
     *
     * @return instant at which this record was created / 当前记录创建时刻
     */
    public String getCreatedAt() {
        return createdAt;
    }

    /**
     * Updates instant at which this record was created.
     * <p>更新当前记录创建时刻。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setCreatedAt(String value) {
        createdAt = value;
    }

    /**
     * Returns updated at.
     * <p>返回已更新时刻。
     *
     * @return updated at / 已更新时刻
     */
    public String getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Updates updated at.
     * <p>更新已更新时刻。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setUpdatedAt(String value) {
        updatedAt = value;
    }

    /**
     * Projects the entity columns into the scoped resource attribute map.
     * <p>将实体列投影为限定作用域资源属性映射。
     *
     * @return constructed or resolved map / 构造或解析得到的映射
     */
    public abstract Map<String, Object> attributes();

    /**
     * Copies scoped resource attributes into the corresponding persistence columns.
     * <p>将限定作用域资源属性复制到对应持久化列。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     */
    public abstract void attributes(Map<String, Object> values);

    /**
     * Builds stored resource from the supplied stored inputs.
     * <p>根据所提供已存储输入构建已存储资源。
     *
     * @return stored resource from the supplied stored inputs / 根据所提供已存储输入构建已存储资源
     */
    public StoredResource stored() {
        return new StoredResource(id, name, attributes(), document, version, createdAt, updatedAt);
    }
}
