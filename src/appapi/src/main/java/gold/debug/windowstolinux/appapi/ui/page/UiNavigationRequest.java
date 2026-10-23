package gold.debug.windowstolinux.appapi.ui.page;

import java.util.Objects;
import java.util.Optional;

/** Transient explicit navigation, never startup persistence. / 临时明确导航，不参与启动持久化。
 * @param <C> detached payload / 分离载荷
 * @param destination stable internal feature identity / 稳定内部功能身份
 * @param contextType exact payload type / 精确载荷类型
 * @param context optional original target / 可选原目标 */
public record UiNavigationRequest<C>(String destination, Class<C> contextType, Optional<C> context) {
    /** Validates an explicit transient request. / 校验明确临时请求。
     * @param destination feature identity / 功能身份
     * @param contextType payload type / 载荷类型
     * @param context original target / 原目标 */
    public UiNavigationRequest {
        if (destination == null || !destination.matches("[a-z][a-z0-9_.-]{0,127}"))
            throw new IllegalArgumentException("Invalid navigation destination");
        Objects.requireNonNull(contextType);
        Objects.requireNonNull(context);
        if (context.isPresent() && !contextType.isInstance(context.orElseThrow()))
            throw new IllegalArgumentException("Navigation context differs from its type");
    }
}
