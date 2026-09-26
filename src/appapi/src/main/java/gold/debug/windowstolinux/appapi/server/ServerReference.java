package gold.debug.windowstolinux.appapi.server;

import java.util.UUID;

/**
 * Business ownership reservation protecting a shared server. / 保护共享服务器的业务归属预留。
 * @param id stable identity within the owning capability / 所属能力内的稳定身份
 * @param server selected platform server reference / 选定的平台服务器引用
 * @param businessKey stable key of the referencing business record / 引用业务记录的稳定键
 * @param state observed lifecycle state / 已观察的生命周期状态
 */
public record ServerReference(UUID id, ServerRef server, String businessKey, ReferenceState state) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param id stable identity within the owning capability / 所属能力内的稳定身份
     * @param server selected platform server reference / 选定的平台服务器引用
     * @param businessKey stable key of the referencing business record / 引用业务记录的稳定键
     * @param state observed lifecycle state / 已观察的生命周期状态
     */
    public ServerReference {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(server, "server");
        java.util.Objects.requireNonNull(businessKey, "businessKey");
        java.util.Objects.requireNonNull(state, "state");
    }
}
