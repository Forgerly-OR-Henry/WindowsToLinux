package gold.debug.windowstolinux.appapi.secret;

/**
 * Opaque secret reference bound to an authorized purpose. / 绑定授权用途的不透明秘密引用。
 * @param id stable identity within the owning capability / 所属能力内的稳定身份
 * @param purpose declared purpose used for routing or authorization / 路由或授权使用的声明用途
 */
public record SecretRef(String id, String purpose) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param id stable identity within the owning capability / 所属能力内的稳定身份
     * @param purpose declared purpose used for routing or authorization / 路由或授权使用的声明用途
     */
    public SecretRef {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(purpose, "purpose");
    }
}
