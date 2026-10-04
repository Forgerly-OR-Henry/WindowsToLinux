package gold.debug.windowstolinux.appapi.server.model;

/**
 * Stable identifier for a platform-managed server. / 平台管理服务器的稳定标识。
 * @param id stable identity within the owning capability / 所属能力内的稳定身份
 */
public record ServerId(String id) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param id stable identity within the owning capability / 所属能力内的稳定身份
     */
    public ServerId {
        java.util.Objects.requireNonNull(id, "id");
    }
}
