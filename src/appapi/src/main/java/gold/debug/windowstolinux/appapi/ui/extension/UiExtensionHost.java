package gold.debug.windowstolinux.appapi.ui.extension;

import java.util.List;
import javax.swing.JComponent;

/** Provider-owned mounting adapter; never exposes the surrounding control tree. / 提供方所属挂载适配器，不暴露周围控件树。 */
public interface UiExtensionHost {
    /** Identifies the owned mount anchor for page cleanup. / 标识供页面清理使用的所属挂载位置。
     * @return original provider anchor / 原提供方位置 */
    JComponent anchor();

    /** Creates an action control using provider styling. / 按提供方样式创建动作控件。
     * @param presentation actual state / 实际状态
     * @param invoke host-guarded click callback / 宿主核验的点击回调
     * @return unattached action control / 尚未挂载的动作控件
     * @throws Exception if construction fails / 创建失败时 */
    default JComponent createAction(UiActionPresentation presentation, Runnable invoke) throws Exception {
        throw new UnsupportedOperationException("This host accepts components only");
    }

    /** Updates an existing action control. / 更新已有动作控件。
     * @param control original action control / 原动作控件
     * @param presentation current state / 当前状态
     * @throws Exception if update fails / 更新失败时 */
    default void updateAction(JComponent control, UiActionPresentation presentation) throws Exception {
        throw new UnsupportedOperationException("This host accepts components only");
    }

    /** Installs the ordered managed children; other provider content stays owned by the provider. / 安装有序受管子项，其他内容仍由提供方持有。
     * @param components ordered managed children / 有序受管子项
     * @throws Exception if mounting fails / 挂载失败时 */
    void setContents(List<JComponent> components) throws Exception;
}
