package gold.debug.windowstolinux.mod.files.ui;

import java.util.Optional;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.extension.UiAction;
import gold.debug.windowstolinux.appapi.ui.extension.UiActionPresentation;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.appapi.ui.page.UiNavigationRequest;

/** Server shortcut using only the public owned navigation capability. / 仅使用公开所属导航能力的服务器快捷入口。 */
public final class FilesServerAction implements UiAction<ServerId> {
    /** Original owner-bound navigation. / 原所属者绑定导航。 */
    private final UiService ui;

    /** Captures the actual activation without creating a workspace. / 捕获实际启用，不创建工作区。
     * @param ui original navigation / 原导航 */
    public FilesServerAction(UiService ui) {
        this.ui = java.util.Objects.requireNonNull(ui);
    }

    /** Displays localized selection-dependent availability. / 展示本地化且依赖选择的可用性。
     * @param context current server / 当前服务器
     * @param environment current environment / 当前环境
     * @return menu presentation / 菜单展示 */
    @Override
    public UiActionPresentation presentation(Optional<ServerId> context, UiEnvironment environment) {
        return new UiActionPresentation(new FilesMessages(environment.locale()).text("serverShortcut"), true,
            context.isPresent() && !environment.locked());
    }

    /** Opens the existing workspace and explicitly requests the captured root. / 打开已有工作区并明确请求固定服务器根目录。
     * @param context original clicked server / 原点击服务器
     * @throws Exception if owned navigation is unavailable / 所属导航不可用时 */
    @Override
    public void invoke(Optional<ServerId> context) throws Exception {
        if (context.isEmpty()) return;
        ui.open("workspace", new UiNavigationRequest<>("remote-root", ServerId.class, context));
    }
}
