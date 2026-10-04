package gold.debug.windowstolinux.appapi.secret.ref;

import gold.debug.windowstolinux.appapi.secret.model.SecretId;

import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.ref.ResourceRefState;

/**
 * Persistent business reference protecting a shared credential. / 保护共享凭据的持久业务引用。
 * @param id stable identity within the owning capability / 所属能力内的稳定身份
 * @param secret opaque secret identifier bound to its exact purpose / 绑定精确用途的不透明秘密标识
 * @param businessKey stable key of the referencing business record / 引用业务记录的稳定键
 * @param state observed lifecycle state / 已观察的生命周期状态
 */
public record SecretRef(UUID id, SecretId secret, String businessKey, ResourceRefState state) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param id stable identity within the owning capability / 所属能力内的稳定身份
     * @param secret opaque secret identifier bound to its exact purpose / 绑定精确用途的不透明秘密标识
     * @param businessKey stable key of the referencing business record / 引用业务记录的稳定键
     * @param state observed lifecycle state / 已观察的生命周期状态
     */
    public SecretRef {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(secret, "secret");
        java.util.Objects.requireNonNull(businessKey, "businessKey");
        java.util.Objects.requireNonNull(state, "state");
    }
}
