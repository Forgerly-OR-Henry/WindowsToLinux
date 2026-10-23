package gold.debug.windowstolinux.app.ui.mod;

import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.mod.PlatformModIntents.PagePin;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.app.ui.control.DesktopIcons;
import gold.debug.windowstolinux.app.ui.control.DesktopButtonStyle;
import gold.debug.windowstolinux.app.ui.control.DesktopNavigationButton;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.LocaleBinding;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.*;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** Shows each published root once, without constructing author views. / 每个已发布根仅显示一次，不构造作者视图。 */
public final class ModNavigationPane extends JPanel implements AutoCloseable {
    /** Original persistence and management authorization. / 原持久化及管理授权。 */
    private final PlatformModIntents service;

    /** Actual publication and view lifetime owner. / 实际发布及视图寿命所有者。 */
    private final ModPageRegistry pages;

    /** Safe localized messages. / 安全本地化消息。 */
    private final PageMessagePresenter messages;

    /** Themed navigation controls. / 主题导航控件。 */
    private final DesktopPartFactory components;

    /** Main navigation entries, never author components. / 主导航入口，不持有作者组件。 */
    private final JPanel entries = new JPanel();

    /** Current safe persistence or opening feedback. / 当前安全持久化或打开反馈。 */
    private final JLabel status = new JLabel();

    /** Original committed preference order. / 原已提交偏好顺序。 */
    private List<PagePin> pins = List.of();

    /** Current immutable published metadata. / 当前不可变发布元数据。 */
    private volatile List<PageContribution> published = List.of();

    /** Current visible root selection, independent of retained instances. / 当前可见根选择，独立于保留实例。 */
    private PageKey selected;

    /** Host metadata subscription removed with this view. / 随视图移除的宿主元数据订阅。 */
    private final AutoCloseable subscription;

    /** Installed-page observers sharing this navigation state. / 共用此导航状态的已下载页面观察器。 */
    private final Set<Runnable> listeners = new LinkedHashSet<>();

    /** Current privacy and worker state. / 当前隐私与工作线程状态。 */
    private boolean locked = true, closed, busy;

    /** Invalidates stale preference responses after lock changes. / 锁定变化后使过期偏好响应失效。 */
    private long epoch;

    /** Current sidebar presentation, independent of pin preferences. / 独立于固定偏好的侧栏展示。 */
    private double folded;

    /** Applies sidebar presentation without changing saved pins. / 应用侧栏展示而不修改已存固定项。
     * @param progress expanded-to-collapsed progress / 展开至折叠进度 */
    public void presentation(double progress) {
        folded = Math.max(0, Math.min(1, progress));
        for (var component : entries.getComponents())
            ((DesktopNavigationButton) component).presentation(folded);
    }

    /** Builds navigation without reading storage or constructing author views. / 构造导航，不读取存储或构造作者视图。
     * @param service actual management owner / 实际管理所有者
     * @param pages original publication registry / 原发布登记
     * @param components themed controls / 主题控件
     * @param messages safe messages / 安全消息
     * @throws ApiException if publication observation cannot be registered / 无法登记发布观察时 */
    public ModNavigationPane(PlatformModIntents service, ModPageRegistry pages, DesktopPartFactory components,
                             PageMessagePresenter messages) throws ApiException {
        super();
        this.service = service;
        this.pages = pages;
        this.components = components;
        this.messages = messages;
        setName("mods.navigation");
        setOpaque(false);
        entries.setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        entries.setLayout(new BoxLayout(entries, BoxLayout.Y_AXIS));
        add(entries);
        status.setName("mods.navigationStatus");
        add(status);
        subscription = pages.onChange(this::publicationChanged);
    }

    /** Registers a host listener without invoking author code. / 登记宿主监听器，不执行作者代码。
     * @param listener event-thread listener / 事件线程监听器
     * @return removable listener / 可移除监听器 */
    public AutoCloseable onChange(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Lists visible metadata for one exact owner. / 列出一个精确所有者的可见元数据。
     * @param owner actual Mod identity / 实际 Mod 身份
     * @return current published pages / 当前已发布页面 */
    public List<PageContribution> published(String owner) {
        return locked || closed ? List.of() : published.stream().filter(p -> p.key().ownerId().equals(owner)).toList();
    }

    /** Reports the saved preference independently of whether the page currently exists. / 独立于页面当前是否存在地报告已存偏好。
     * @param key exact page / 精确页面
     * @return whether explicitly pinned / 是否明确固定 */
    public boolean pinned(PageKey key) {
        return pins.contains(new PagePin(key.ownerId(), key.pageId()));
    }

    /** Reports whether another explicit pin mutation can be requested. / 报告是否可请求另一明确固定修改。
     * @return current interactive availability / 当前交互可用性 */
    public boolean available() {
        return !locked && !closed && !busy;
    }

    /** Projects saved identities to published pins without opening author views. / 将已存身份投影到已发布固定项，不打开作者视图。
     * @param saved complete preference / 完整偏好
     * @return visible subset / 可见子集 */
    public List<PagePin> visiblePins(List<PagePin> saved) {
        var roots = roots();
        return closed ? List.of() : PagePin.workspaceOrder(saved, roots).stream()
            .filter(roots::contains).toList();
    }

    /** Reads and normalizes root order on a worker through original authorization. / 在工作线程经原授权读取并归并根顺序。
     * @return confirmed complete order / 已确认完整顺序
     * @throws ApiException if persistence is unavailable / 持久化不可用时 */
    public List<PagePin> savedWorkspaces() throws ApiException {
        return service.workspacePins(roots());
    }

    /** Captures metadata only; settings never contribute a root. / 仅捕获元数据；设置不贡献根。
     * @return published root identities / 已发布根身份 */
    private List<PagePin> roots() {
        return published.stream().filter(page -> !page.spec().settingsPage())
            .map(page -> new PagePin(page.key().ownerId(), page.key().pageId())).toList();
    }

    /** Highlights only the currently displayed root. / 仅突出当前显示的根。
     * @param key visible root, or null on a platform page / 可见根，平台页时为空 */
    public void selected(PageKey key) {
        if (Objects.equals(selected, key)) return;
        selected = key;
        render();
    }

    /** Returns published display text with stable identity fallback. / 返回发布展示文字，以稳定身份为后备。
     * @param pin exact pin / 精确固定项
     * @return display label / 展示标签 */
    public String pinTitle(PagePin pin) {
        return published.stream().filter(page -> page.key().ownerId().equals(pin.ownerId())
                && page.key().pageId().equals(pin.pageId())).map(page -> page.spec().defaultTitle() + " · " + pin.ownerId())
            .findFirst().orElse(pin.ownerId() + "/" + pin.pageId());
    }

    /** Applies confirmed ordering without repeating persistence. / 应用已确认顺序，不重复持久化。
     * @param saved complete confirmed pins / 完整已确认固定项 */
    public void confirmedOrder(List<PagePin> saved) {
        if (!available() || pins.equals(saved)) return;
        pins = List.copyOf(saved);
        render();
    }

    /** Navigates through the host, retaining the locked page environment. / 经宿主导航，保留页面的锁定环境。
     * @param key exact selected page / 精确已选页面 */
    public void open(PageKey key) {
        if (closed)
            return;
        try {
            pages.navigateView(key);
            LocaleBinding.unbind(status);
            status.setText("");
        } catch (ApiException failure) {
            LocaleBinding.unbind(status);
            status.setText(messages.safe(failure));
            publicationChanged();
        }
    }

    /** Preserves navigation across lock boundaries and reloads preferences after unlock. / 跨锁定边界保留导航，解锁后重读偏好。
     * @param value current lock state / 当前锁定状态 */
    public void setLocked(boolean value) {
        epoch++;
        locked = value;
        if (value) busy = false;
        LocaleBinding.unbind(status);
        status.setText("");
        publicationChanged();
        if (!locked && !busy)
            load(this::savedWorkspaces, false);
    }

    /** Reloads preferences after host-owned cleanup, invalidating older in-flight observations. / 宿主所属清理后重读偏好，使较早的在途观察失效。 */
    public void refreshPreferences() {
        if (closed)
            return;
        epoch++;
        pins = List.of();
        publicationChanged();
        load(this::savedWorkspaces, false);
    }

    /** Reloads publication metadata without opening or pinning new registrations. / 重读发布元数据，不打开或固定新登记。 */
    private void publicationChanged() {
        if (closed)
            return;
        try {
            var previous = published;
            published = pages.contributions();
            if (!published.equals(previous)) epoch++;
            if (!locked && !busy && !published.equals(previous)) {
                load(this::savedWorkspaces, false);
                return;
            }
        } catch (ApiException failure) {
            published = List.of();
            LocaleBinding.unbind(status);
            status.setText(messages.safe(failure));
        }
        render();
    }

    /** Renders the intersection of saved preferences and current published identities. / 渲染已存偏好与当前发布身份的交集。 */
    private void render() {
        entries.removeAll();
        if (!closed)
            for (var pin : visiblePins(pins)) {
                var page = published.stream()
                    .filter(p -> p.key().ownerId().equals(pin.ownerId()) && p.key().pageId().equals(pin.pageId()))
                    .findFirst();
                if (page.isEmpty())
                    continue;
                var entry = page.get();
                var button = components.navigationButton(entry.spec().defaultTitle());
                button.presentation(folded);
                button.setIcon(DesktopIcons.icon("navigation/panels-top-left", 19, button::getForeground));
                button.putClientProperty("JComponent.minimumWidth", 0);
                button.setMinimumSize(new java.awt.Dimension(0, com.formdev.flatlaf.util.UIScale.scale(38)));
                button.setMaximumSize(
                    new java.awt.Dimension(Integer.MAX_VALUE, com.formdev.flatlaf.util.UIScale.scale(38)));
                button.setPreferredSize(new java.awt.Dimension(com.formdev.flatlaf.util.UIScale.scale(140),
                    com.formdev.flatlaf.util.UIScale.scale(38)));
                button.setMargin(new java.awt.Insets(8, 10, 8, 10));
                button.setHorizontalAlignment(SwingConstants.LEFT);
                button.getAccessibleContext().setAccessibleName(entry.spec().defaultTitle());
                button.putClientProperty("html.disable", true);
                button.putClientProperty("mod.pageKey", entry.key());
                button.setName("mods.pinned." + entries.getComponentCount());
                button.setToolTipText(entry.spec().defaultTitle() + " · " + pin.ownerId());
                button.putClientProperty("desktop.navigation", true);
                button.setBorderPainted(false);
                button.putClientProperty("JButton.buttonType", null);
                boolean active = entry.key().equals(selected);
                var background = UIManager.getColor(active ? "List.selectionBackground" : "Panel.background");
                DesktopButtonStyle.navigation(button, background,
                    UIManager.getColor(active ? "List.selectionForeground" : "Label.foreground"),
                    active ? background.darker() : UIManager.getColor("Button.background"),
                    active ? background.darker().darker() : UIManager.getColor("List.selectionBackground"));
                button.setEnabled(!closed);
                button.addActionListener(event -> open(entry.key()));
                entries.add(button);
            }
        entries.revalidate();
        entries.repaint();
        for (var listener : List.copyOf(listeners))
            listener.run();
    }

    /** Reads or writes preferences off the EDT without replaying uncertain mutations. / 在 EDT 外读写偏好，不重放未确认修改。
     * @param action original persistence operation / 原持久化操作
     * @param mutation whether failure needs a read-only state refresh / 失败后是否需要只读刷新状态 */
    private void load(Callable<List<PagePin>> action, boolean mutation) {
        if (!available())
            return;
        busy = true;
        long original = epoch;
        render();
        DesktopTaskRunner.run(action, value -> {
            busy = false;
            if (closed || locked)
                return;
            if (epoch != original) {
                load(this::savedWorkspaces, false);
                return;
            }
            pins = value;
            if (mutation) {
                LocaleBinding.unbind(status);
                status.setText("");
            }
            render();
        }, failure -> {
            busy = false;
            if (closed || locked)
                return;
            LocaleBinding.unbind(status);
            status.setText(messages.safe(failure));
            render();
            if (mutation || epoch != original)
                load(this::savedWorkspaces, false);
        });
    }

    /** Releases host observers without closing any author view or changing saved pins. / 释放宿主观察器，不关闭作者视图或改变已存固定项。 */
    @Override
    public void close() {
        closed = true;
        epoch++;
        pins = List.of();
        published = List.of();
        LocaleBinding.unbind(status);
        status.setText("");
        try {
            subscription.close();
        } catch (Exception failure) {
            LocaleBinding.unbind(status);
            status.setText(messages.safe(failure));
        }
        render();
        listeners.clear();
    }
}
