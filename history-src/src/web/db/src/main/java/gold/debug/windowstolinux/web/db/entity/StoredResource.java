package gold.debug.windowstolinux.web.db.entity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents an immutable revision of an owned resource; its JSON contains no secret values.
 * <p>表示自有资源的不可变修订；其 JSON 不包含秘密值。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
 * @param attributes attributes / 属性
 * @param document document / 文档
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 * @param createdAt instant at which this record was created / 当前记录创建时刻
 * @param updatedAt updated at / 已更新时刻
 */
public record StoredResource(String id, String name, Map<String, Object> attributes, String document, long version,
        String createdAt, String updatedAt) {
    /**
     * Binds the supplied dependencies and state for stored resource.
     * <p>为已存储资源绑定传入的依赖及状态。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param attributes attributes / 属性
     * @param document document / 文档
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param createdAt instant at which this record was created / 当前记录创建时刻
     * @param updatedAt updated at / 已更新时刻
     */
    public StoredResource {
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
