package gold.debug.windowstolinux.api.server;

/**
 * Stable reference to a platform-managed server. / 平台管理服务器的稳定引用。
 * @param id stable identity within the owning capability / 所属能力内的稳定身份
 */
public record ServerRef(String id) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param id stable identity within the owning capability / 所属能力内的稳定身份
     */
    public ServerRef {
        java.util.Objects.requireNonNull(id, "id");
    }
}
