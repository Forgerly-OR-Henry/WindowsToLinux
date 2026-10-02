package gold.debug.windowstolinux.app.db.task;

import java.util.Objects;
import java.util.Set;

/**
 * Internal resource claim whose ownership is chosen by a platform adapter. / 由平台适配器确定归属的内部资源申请。
 * @param kind fixed platform resource kind / 固定的平台资源类型
 * @param id exact identity within that kind / 此类型中的精确身份
 * @param ownerId owning Mod identity / 所属 Mod 身份
 * @param exclusive whether the claim excludes every other holder / 申请是否排斥其他所有持有者
 */
public record TaskResource(String kind, String id, String ownerId, boolean exclusive) {
    /**
     * Checks bounded resource coordinates without altering identity. / 检查有界资源坐标，不修改身份。
     * @param kind fixed platform resource kind / 固定的平台资源类型
     * @param id exact identity within that kind / 此类型中的精确身份
     * @param ownerId owning Mod identity / 所属 Mod 身份
     * @param exclusive whether the claim excludes every other holder / 申请是否排斥其他所有持有者
     */
    public TaskResource {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(id);
        Objects.requireNonNull(ownerId);
        if (!Set.of("MOD", "SCHEMA", "SERVER", "CONNECTION", "PROCESS", "FILE", "PAGE", "CUSTOM").contains(kind)
            || id.isBlank() || id.length() > 4096 || ownerId.isBlank() || ownerId.length() > 512)
            throw new IllegalArgumentException("Invalid platform resource identity");
        if (kind.equals("SERVER") && !exclusive)
            throw new IllegalArgumentException("Server work requires exclusive admission");
    }
}
