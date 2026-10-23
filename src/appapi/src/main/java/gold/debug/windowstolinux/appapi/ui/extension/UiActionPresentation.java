package gold.debug.windowstolinux.appapi.ui.extension;

import java.util.Objects;

/** Host-rendered action state. / 提供方呈现的动作状态。
 * @param text localized label / 本地化名称
 * @param visible whether displayed / 是否展示
 * @param enabled whether author permits invocation / 作者是否允许执行 */
public record UiActionPresentation(String text, boolean visible, boolean enabled) {
    /** Validates the actual display label. / 校验实际展示名称。
     * @param text localized label / 本地化名称
     * @param visible display flag / 展示标记
     * @param enabled author permission / 作者许可 */
    public UiActionPresentation {
        Objects.requireNonNull(text);
        if (text.isBlank()) throw new IllegalArgumentException("Empty action label");
    }
}
