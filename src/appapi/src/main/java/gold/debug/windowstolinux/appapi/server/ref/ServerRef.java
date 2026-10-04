package gold.debug.windowstolinux.appapi.server.ref;

import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.ref.ResourceRefState;

import gold.debug.windowstolinux.appapi.server.model.ServerId;

/**
 * Business ownership reservation protecting a shared server. / 保护共享服务器的业务归属预留。
 * @param id stable identity within the owning capability / 所属能力内的稳定身份
 * @param server selected platform server identifier / 选定的平台服务器标识
 * @param businessKey stable key of the referencing business record / 引用业务记录的稳定键
 * @param state observed lifecycle state / 已观察的生命周期状态
 */
public record ServerRef(UUID id, ServerId server, String businessKey, ResourceRefState state) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param id stable identity within the owning capability / 所属能力内的稳定身份
     * @param server selected platform server identifier / 选定的平台服务器标识
     * @param businessKey stable key of the referencing business record / 引用业务记录的稳定键
     * @param state observed lifecycle state / 已观察的生命周期状态
     */
    public ServerRef {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(server, "server");
        java.util.Objects.requireNonNull(businessKey, "businessKey");
        java.util.Objects.requireNonNull(state, "state");
    }
}
