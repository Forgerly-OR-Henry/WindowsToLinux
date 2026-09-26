package gold.debug.windowstolinux.appapi.task;

import java.util.UUID;

/**
 * Opaque approval identifier whose authority is checked by the host. / 授权效力由宿主核实的不透明审批标识。
 * @param id stable identity within the owning capability / 所属能力内的稳定身份
 */
public record ApprovalGrant(UUID id) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param id stable identity within the owning capability / 所属能力内的稳定身份
     */
    public ApprovalGrant {
        java.util.Objects.requireNonNull(id, "id");
    }
}
