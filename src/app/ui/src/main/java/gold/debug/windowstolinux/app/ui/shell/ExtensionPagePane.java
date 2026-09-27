package gold.debug.windowstolinux.app.ui.shell;

import java.awt.*;
import java.util.*;
import java.util.function.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.*;
import gold.debug.windowstolinux.appapi.ui.*;

/** Mounts opened Mod views and owner-grouped settings without adding navigation pins. / 挂载已打开的 Mod 视图及按所有者分组的设置，不添加导航固定项。 */
public final class ExtensionPagePane extends JPanel implements PageHost {
    /** Normal opened business pages. / 普通已打开业务页面。 */
    private final JTabbedPane pages = new JTabbedPane();

    /** Settings groups keyed by the owning Mod. / 按所属 Mod 分组的设置。 */
    private final JTabbedPane settingGroups = new JTabbedPane();

    /** Separate surface mounted by the host settings page. / 由宿主设置页面挂载的独立界面。 */
    private final JPanel settingSurface = new JPanel(new CardLayout());

    /** Business-page lock message supplied by the platform catalog. / 平台消息目录提供的业务页锁定消息。 */
    private final JLabel pageLock = new JLabel("", SwingConstants.CENTER);

    /** Settings lock message supplied by the platform catalog. / 平台消息目录提供的设置锁定消息。 */
    private final JLabel settingsLock = new JLabel("", SwingConstants.CENTER);

    /** Exact mounted components and their tab containers. / 精确挂载的组件及其标签容器。 */
    private final Map<PageKey, MountedView> mounted = new LinkedHashMap<>();

    /** Mod-local settings tab containers. / Mod 内部设置标签容器。 */
    private final Map<String, JTabbedPane> ownerSettings = new LinkedHashMap<>();

    /** Host action that performs tracked close and reports failures. / 执行跟踪关闭并报告失败的宿主操作。 */
    private final Consumer<PageKey> closeRequest;

    /** Display names supplied by the authoritative Mod registry. / 权威 Mod 登记提供的显示名称。 */
    private final Function<String, String> ownerTitle;

    /** Localized current lock message. / 当前本地化锁定消息。 */
    private final Supplier<String> lockMessage;

    /** Selects the actual outer business or settings surface after an explicit open. / 明确打开后选择实际外层业务或设置界面。 */
    private final Consumer<Boolean> activate;

    /**
     * Creates both Swing surfaces on the EDT. / 在 EDT 创建两个 Swing 界面。
     * @param closeRequest tracked host close action / 跟踪的宿主关闭操作
     * @param ownerTitle current Mod display-name lookup / 当前 Mod 显示名称查询
     * @param lockMessage platform-localized lock message / 平台本地化锁定消息
     * @param activate selects the outer settings or business surface / 选择外层设置或业务界面
     */
    public ExtensionPagePane(Consumer<PageKey> closeRequest, Function<String, String> ownerTitle,
            Supplier<String> lockMessage, Consumer<Boolean> activate) {
        super(new CardLayout());
        requireEdt();
        this.closeRequest = Objects.requireNonNull(closeRequest);
        this.ownerTitle = Objects.requireNonNull(ownerTitle);
        this.lockMessage = Objects.requireNonNull(lockMessage);
        this.activate = Objects.requireNonNull(activate);
        add(pages, "pages");
        add(pageLock, "locked");
        settingSurface.add(settingGroups, "pages");
        settingSurface.add(settingsLock, "locked");
    }

    /**
     * Requires all component ownership operations to run on the EDT. / 要求所有组件归属操作在 EDT 运行。
     */
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Page mounting requires the EDT");
    }

    /** Returns the separate owner-grouped settings component. / 返回独立的按所有者分组设置组件。
     * @return host settings contribution surface / 宿主设置扩展界面 */
    public JComponent settingsComponent() {
        requireEdt();
        return settingSurface;
    }

    /**
     * Adds a view to its business tabs or the owning Mod's settings section. / 将视图加入业务标签或所属 Mod 的设置分区。
     * @param key exact global identity / 精确全局身份
     * @param spec author presentation metadata / 作者展示元数据
     * @param component newly created unattached view / 新创建且未挂载的视图
     */
    @Override
    public void show(PageKey key, PageSpec spec, JComponent component) {
        requireEdt();
        if (mounted.containsKey(key))
            throw new IllegalStateException("Page is already mounted");
        JTabbedPane target = pages;
        if (spec.settingsPage())
            target = ownerSettings.computeIfAbsent(key.ownerId(), owner -> {
                var group = new JTabbedPane();
                settingGroups.addTab(ownerTitle.apply(owner), group);
                return group;
            });
        mounted.put(key, new MountedView(target, component));
        target.addTab(spec.defaultTitle(), component);
        int index = target.indexOfComponent(component);
        var title = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        title.setOpaque(false);
        var label = new JLabel(spec.defaultTitle());
        label.putClientProperty("html.disable", true);
        title.add(label);
        var close = new JButton("×");
        close.setFocusable(false);
        close.addActionListener(event -> closeRequest.accept(key));
        title.add(close);
        target.setTabComponentAt(index, title);
        focus(key);
    }

    /**
     * Selects the existing component and its settings owner group when necessary. / 选择已有组件，并在需要时选择其设置所有者分组。
     * @param key exact global identity / 精确全局身份
     */
    @Override
    public void focus(PageKey key) {
        requireEdt();
        var view = mounted.get(key);
        if (view == null)
            throw new IllegalStateException("Page is not mounted");
        if (view.tabs() != pages)
            settingGroups.setSelectedComponent(view.tabs());
        view.tabs().setSelectedComponent(view.component());
        activate.accept(view.tabs() != pages);
    }

    /**
     * Removes a closed view and any now-empty owner settings group. / 移除已关闭视图及因此变空的所有者设置分组。
     * @param key exact global identity / 精确全局身份
     */
    @Override
    public void remove(PageKey key) {
        requireEdt();
        var view = mounted.get(key);
        if (view == null)
            return;
        view.tabs().remove(view.component());
        if (view.tabs() != pages && view.tabs().getTabCount() == 0) {
            settingGroups.remove(view.tabs());
            ownerSettings.remove(key.ownerId(), view.tabs());
        }
        mounted.remove(key);
    }

    /**
     * Hides business and settings views behind the host lock surface. / 将业务及设置视图隐藏在宿主锁定界面之后。
     * @param environment current host language, theme and lock state / 当前宿主语言、主题及锁定状态
     */
    @Override
    public void environment(UiEnvironment environment) {
        requireEdt();
        String message = lockMessage.get();
        pageLock.setText(message);
        settingsLock.setText(message);
        String card = environment.locked() ? "locked" : "pages";
        ((CardLayout) getLayout()).show(this, card);
        ((CardLayout) settingSurface.getLayout()).show(settingSurface, card);
        if (environment.locked())
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
        SwingUtilities.updateComponentTreeUI(this);
        SwingUtilities.updateComponentTreeUI(settingSurface);
    }

    /**
     * Concrete mounting coordinates retained until successful removal. / 保留到成功移除的具体挂载坐标。
     * @param tabs owning tab container / 所属标签容器
     * @param component actual author view / 实际作者视图
     */
    private record MountedView(JTabbedPane tabs, JComponent component) {
    }
}
