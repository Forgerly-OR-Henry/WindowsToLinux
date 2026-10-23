package gold.debug.windowstolinux.app.ui.server;

import java.util.List;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import gold.debug.windowstolinux.appapi.ui.extension.UiActionPresentation;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionHost;

/** Server-menu adapter retaining the original confirmed delete action. / 保留原确认删除动作的服务器菜单适配器。 */
final class ServerExtensionMenu implements UiExtensionHost {
    /** Original visible anchor. / 原可见位置。 */
    private final JButton more;

    /** Original delete control and action owner. / 原删除控件及动作所有者。 */
    private final JButton delete;

    /** Actual keyboard-accessible popup. / 实际可键盘访问菜单。 */
    private final JPopupMenu menu = new JPopupMenu();

    /** Original delete delegation. / 原删除委托。 */
    private final JMenuItem deleteItem = new JMenuItem();

    /** Current managed identities. / 当前受管身份。 */
    private List<JComponent> displayed = List.of();

    /** Actual popup appearance, including updates while it is hidden. / 实际菜单外观，包含隐藏期间更新。 */
    private LookAndFeel appearance;

    /** Last application zoom applied to the original popup. / 原菜单最近应用的缩放。 */
    private float scale;

    /** Binds original controls without changing their listeners. / 绑定原控件，不改其监听器。
     * @param more original more action / 原更多动作
     * @param delete original confirmed deletion / 原确认删除 */
    ServerExtensionMenu(JButton more, JButton delete) {
        this.more = more;
        this.delete = delete;
        menu.setName("servers.more.menu");
        deleteItem.setName("servers.more.delete");
        deleteItem.addActionListener(event -> delete.doClick());
        more.addActionListener(event -> menu.show(more, 0, more.getHeight()));
        setContents(List.of());
    }

    /** Returns the real page anchor rather than a detached popup. / 返回实际页面位置，而非分离菜单。
     * @return original more button / 原更多按钮 */
    @Override
    public JComponent anchor() {
        return more;
    }

    /** Creates a menu action under the provider theme. / 在提供方主题下创建菜单动作。
     * @param presentation current state / 当前状态
     * @param invoke guarded callback / 核验回调
     * @return original menu item / 原菜单项 */
    @Override
    public JComponent createAction(UiActionPresentation presentation, Runnable invoke) {
        var item = new JMenuItem();
        item.addActionListener(event -> invoke.run());
        updateAction(item, presentation);
        return item;
    }

    /** Updates the actual menu item in place. / 原地更新实际菜单项。
     * @param control original item / 原菜单项
     * @param presentation current state / 当前状态 */
    @Override
    public void updateAction(JComponent control, UiActionPresentation presentation) {
        var item = (JMenuItem) control;
        item.setText(presentation.text());
        item.setEnabled(presentation.enabled());
        item.getAccessibleContext().setAccessibleName(presentation.text());
    }

    /** Reorders managed items only when their identities change. / 仅受管身份变化时重新排列。
     * @param components ordered managed controls / 有序受管控件 */
    @Override
    public void setContents(List<JComponent> components) {
        if (!displayed.equals(components) || menu.getComponentCount() == 0) {
            displayed = List.copyOf(components);
            menu.removeAll();
            for (var component : components) menu.add(component);
            if (!components.isEmpty()) menu.addSeparator();
            menu.add(deleteItem);
        }
        refresh();
    }

    /** Keeps original localized deletion availability. / 保持原本地化删除可用性。 */
    void refresh() {
        var current = UIManager.getLookAndFeel();
        float zoom = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        if (appearance != current || scale != zoom) {
            SwingUtilities.updateComponentTreeUI(menu);
            appearance = current;
            scale = zoom;
        }
        deleteItem.setText(delete.getText());
        deleteItem.setEnabled(delete.isEnabled());
        menu.revalidate();
        menu.repaint();
    }

    /** Hides the original transient popup on release. / 释放时隐藏原临时菜单。 */
    void hide() {
        menu.setVisible(false);
    }
}
