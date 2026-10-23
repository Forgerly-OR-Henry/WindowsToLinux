package gold.debug.windowstolinux.appapi.ui.extension;

import gold.debug.windowstolinux.appapi.server.model.ServerId;

/** Actually supported APP extension destinations. / APP 实际支持的界面扩展目的地。 */
public final class PlatformUiExtensions {
    /** Server detail menu, with the current optional server. / 服务器详情菜单，携带当前可选服务器。 */
    public static final UiExtensionPoint<ServerId> SERVER_MORE =
        new UiExtensionPoint<>("platform", "servers.more", ServerId.class, UiExtensionKind.ACTION);

    /** Prevents utility allocation. / 禁止工具实例化。 */
    private PlatformUiExtensions() {
    }
}
