package gold.debug.windowstolinux.appapi.ui.extension;

import java.util.Objects;

/** Typed exact extension destination. / 类型明确的精确扩展目的地。
 * @param <C> detached context type / 分离的上下文类型
 * @param providerId actual provider identity, platform for APP points / 实际提供方身份，APP 位置为 platform
 * @param id stable provider-local position / 提供方内稳定位置
 * @param contextType exact shared context Class / 精确共享的上下文类型
 * @param kind accepted contribution form / 接受的内容形式 */
public record UiExtensionPoint<C>(String providerId, String id, Class<C> contextType, UiExtensionKind kind) {
    /** Validates the explicit destination. / 校验明确的目的地。
     * @param providerId original provider / 原提供方
     * @param id local position / 本地位置
     * @param contextType shared type / 共享类型
     * @param kind accepted form / 接受形式 */
    public UiExtensionPoint {
        if (providerId == null || providerId.isBlank() || id == null || !id.matches("[a-z][a-z0-9_.-]{0,127}"))
            throw new IllegalArgumentException("Invalid extension destination");
        Objects.requireNonNull(contextType);
        Objects.requireNonNull(kind);
    }
}
