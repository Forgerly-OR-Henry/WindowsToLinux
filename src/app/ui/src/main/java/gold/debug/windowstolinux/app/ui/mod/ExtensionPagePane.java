package gold.debug.windowstolinux.app.ui.mod;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.function.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.*;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;

/** Mounts opened Mod views and owner-grouped settings without adding navigation pins. / 挂载保留的 Mod 根卡片及按所有者分组的设置。 */
public final class ExtensionPagePane extends JPanel implements PageHost {
    /** Normal opened business pages. / 普通已打开业务页面。 */
    private final JPanel pages = new JPanel(new CardLayout());

    /** Host-owned compact root heading and explicit close action. / 宿主所属紧凑根标题及明确关闭动作。 */
    private final JLabel title = new JLabel();

    /** Explicit root close, outside allowed lock navigation. / 明确根关闭，不属于锁定时允许的导航。 */
    private final JButton close = new JButton("×");

    /** Compact APP-owned heading. / 紧凑 APP 所属标题。 */
    private final JPanel heading = new JPanel(new BorderLayout());

    /** Visible root, independent of hidden retained views. / 可见根，独立于隐藏的保留视图。 */
    private PageKey selected;

    /** Host selection observer. / 宿主选择观察器。 */
    private Consumer<PageKey> selection = ignored -> {
    };

    /** Composed host navigation, absent until the actual shell is bound. / 已装配宿主导航，实际外壳绑定前缺省。 */
    private Predicate<PlatformPageKind> platformNavigation;

    /** Settings groups keyed by the owning Mod. / 按所属 Mod 分组的设置。 */
    private final JTabbedPane settingGroups = new JTabbedPane();

    /** Separate surface mounted by the host settings page. / 由宿主设置页面挂载的独立界面。 */
    private final JPanel settingSurface = new JPanel(new BorderLayout());

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
        super(new BorderLayout());
        requireEdt();
        this.closeRequest = Objects.requireNonNull(closeRequest);
        this.ownerTitle = Objects.requireNonNull(ownerTitle);
        this.lockMessage = Objects.requireNonNull(lockMessage);
        this.activate = Objects.requireNonNull(activate);
        setName("mods.workspace");
        pages.setName("mods.workspace.content");
        title.setName("mods.workspace.title");
        title.putClientProperty("html.disable", true);
        close.setName("mods.workspace.close");
        close.setBorderPainted(false);
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.addActionListener(event -> {
            if (selected != null) closeRequest.accept(selected);
        });
        heading.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 8));
        heading.add(title, BorderLayout.CENTER);
        heading.add(close, BorderLayout.EAST);
        heading.setVisible(false);
        add(pages, BorderLayout.CENTER);
        settingSurface.add(settingGroups, BorderLayout.CENTER);
    }

    /** Returns the original compact heading for the host's reserved upper region. / 返回供宿主顶部预留区域挂载的原紧凑标题。
     * @return root title and close action / 根标题及关闭动作 */
    public JComponent headingComponent() {
        return heading;
    }

    /** Binds the host's root selection without taking author ownership. / 绑定宿主根选择，不接管作者归属。
     * @param listener visible root observer / 可见根观察器 */
    public void onSelection(Consumer<PageKey> listener) {
        requireEdt();
        selection = Objects.requireNonNull(listener);
    }

    /** Binds only the actual shell's fixed destination selection. / 仅绑定实际外壳的固定目的地选择。
     * @param selector true only when the destination was actually selected / 仅实际选择目的地时返回true */
    public void platformNavigation(Predicate<PlatformPageKind> selector) {
        requireEdt();
        if (platformNavigation != null) throw new IllegalStateException("Platform navigation is already composed");
        platformNavigation = Objects.requireNonNull(selector);
    }

    /** Opens the host destination while retaining all mounted owner views. / 打开宿主目的地并保留所有已挂载所属视图。
     * @param target fixed platform destination / 固定平台目的地
     * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if not actually composed or selectable / 未实际装配或不可选择时 */
    @Override
    public void openPlatform(PlatformPageKind target) throws gold.debug.windowstolinux.appapi.task.result.ApiException {
        requireEdt();
        if (platformNavigation == null || !platformNavigation.test(Objects.requireNonNull(target)))
            throw new gold.debug.windowstolinux.appapi.task.result.ApiException(
                new gold.debug.windowstolinux.appapi.task.result.Problem(
                    gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNAVAILABLE,
                    "PLATFORM_PAGE_UNAVAILABLE", "The platform destination is not available", Map.of(), Optional.empty()));
    }

    /** Updates platform-owned action copy on the existing surface. / 更新已有界面的平台动作文字。
     * @param text localized close action / 本地化关闭动作 */
    public void closeLabel(String text) {
        close.setToolTipText(text);
        close.getAccessibleContext().setAccessibleName(text);
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

    /** Captures the exact selected business or owner-settings page. / 捕获精确所选业务或所有者设置页面。
     * @param settings whether reading the settings surface / 是否读取设置界面
     * @return selected stable page identity / 所选稳定页面身份 */
    public Optional<PageKey> selectedPage(boolean settings) {
        requireEdt();
        if (!settings) return Optional.ofNullable(selected);
        JTabbedPane selectedTabs = (JTabbedPane) settingGroups.getSelectedComponent();
        if (selectedTabs == null) return Optional.empty();
        return mounted.entrySet().stream().filter(entry -> entry.getValue().tabs() == selectedTabs
            && entry.getValue().component() == selectedTabs.getSelectedComponent()).map(Map.Entry::getKey).findFirst();
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
        if (!spec.settingsPage()) {
            pages.add(component, key.ownerId());
            mounted.put(key, new MountedView(null, component, spec));
            focus(key);
            return;
        }
        JTabbedPane target;
        target = ownerSettings.computeIfAbsent(key.ownerId(), owner -> {
            var group = new JTabbedPane();
            settingGroups.addTab(ownerTitle.apply(owner), group);
            return group;
        });
        mounted.put(key, new MountedView(target, component, spec));
        target.addTab(spec.defaultTitle(), component);
        int index = target.indexOfComponent(component);
        var title = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        title.setOpaque(false);
        var label = new JLabel(spec.defaultTitle());
        label.putClientProperty("html.disable", true);
        JTabbedPane tabOwner = target;
        var select = new MouseAdapter() {
            /** Selects the owned settings tab from its custom label. / 从自定义标签选择所属设置页。
             * @param event original pointer event / 原指针事件 */
            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && tabOwner.indexOfComponent(component) >= 0)
                    tabOwner.setSelectedComponent(component);
            }
        };
        title.addMouseListener(select);
        label.addMouseListener(select);
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
        if (view.tabs() == null) {
            selected = key;
            ((CardLayout) pages.getLayout()).show(pages, key.ownerId());
            pages.setVisible(true);
            title.setText(view.spec().defaultTitle());
            heading.setVisible(true);
            activate.accept(false);
            selection.accept(key);
        } else {
            settingGroups.setSelectedComponent(view.tabs());
            view.tabs().setSelectedComponent(view.component());
            activate.accept(true);
        }
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
        if (view.tabs() == null) {
            pages.remove(view.component());
            if (key.equals(selected)) {
                selected = null;
                heading.setVisible(false);
                pages.setVisible(false);
                selection.accept(null);
            }
        } else {
            view.tabs().remove(view.component());
            if (view.tabs().getTabCount() == 0) {
                settingGroups.remove(view.tabs());
                ownerSettings.remove(key.ownerId(), view.tabs());
            }
        }
        mounted.remove(key);
    }

    /**
     * Preserves page navigation under the host input boundary. / 在宿主输入边界内保留页面导航。
     * @param environment current host language, theme and lock state / 当前宿主语言、主题及锁定状态
     */
    @Override
    public void environment(UiEnvironment environment) {
        requireEdt();
        pages.setToolTipText(environment.locked() ? lockMessage.get() : null);
        settingGroups.setToolTipText(environment.locked() ? lockMessage.get() : null);
        if (environment.locked())
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
        SwingUtilities.updateComponentTreeUI(this);
        SwingUtilities.updateComponentTreeUI(settingSurface);
    }

    /**
     * Concrete mounting coordinates retained until successful removal. / 保留到成功移除的具体挂载坐标。
     * @param tabs owning tab container / 所属标签容器
     * @param component actual author view / 实际作者视图
     * @param spec retained presentation metadata / 保留展示元数据
     */
    private record MountedView(JTabbedPane tabs, JComponent component, PageSpec spec) {
    }
}
