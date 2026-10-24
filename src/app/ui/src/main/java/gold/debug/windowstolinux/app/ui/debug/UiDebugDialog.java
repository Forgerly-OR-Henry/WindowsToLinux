package gold.debug.windowstolinux.app.ui.debug;

import java.awt.*;
import java.util.Arrays;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.control.DesktopDialogs;
import gold.debug.windowstolinux.app.ui.control.DesktopCardList;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.server.ServerProfileDialog;

/** CLASS-only fixed-data catalog without production write, SSH or model capabilities. / 不持有生产写入、SSH或模型能力的仅CLASS固定数据目录。 */
public final class UiDebugDialog extends JDialog {
    /** One explicit link to the actual workspace, separate from isolated previews. / 与隔离预览分离的一项真实工作区明确链接。
     * @param id stable local navigation identity / 稳定本地导航身份
     * @param label current localized visible title / 当前本地化可见标题
     * @param open actual workspace selection / 实际工作区选择 */
    public record PageLink(String id, String label, Runnable open) {
    }

    /** Current actual workspace navigation controls. / 当前真实工作区导航控件。 */
    private final JPanel navigation = new JPanel(new GridLayout(0, 3, 8, 8));

    /** Original author-owned preview registrations. / 原作者持有预览登记。 */
    private final ModPageRegistry pages;

    /** Localized current display. / 本地化当前显示。 */
    private final PageMessagePresenter messages;

    /** Same current theme control factory. / 同一当前主题控件工厂。 */
    private final DesktopPartFactory components;

    /** Current preview choices, without creating views. / 当前预览选项；不创建视图。 */
    private final DefaultListModel<ModPageRegistry.PageContribution> entries = new DefaultListModel<>();

    /** Pure local choice control. / 纯本地选项控件。 */
    private final DesktopCardList<ModPageRegistry.PageContribution> list = new DesktopCardList<>(entries, 48);

    /** Opens only the current explicit preview selection. / 仅打开当前明确选择的预览。 */
    private final JButton open;

    /** Distinguishes initial convenience selection from a user's explicit clear. / 区分初始默认选择与用户明确清空。 */
    private boolean initialized;

    /** Removable original metadata listener. / 可移除原元数据监听器。 */
    private AutoCloseable subscription;

    /** Builds one isolated gallery under the real platform window. / 在真实平台窗口下构造一个隔离目录。
     * @param owner real platform owner / 真实平台所有者
     * @param pages original author registry / 原作者登记
     * @param components current display controls / 当前显示控件
     * @param messages current localized messages / 当前本地化消息
     * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if metadata is unavailable / 元数据不可用时 */
    public UiDebugDialog(Window owner, ModPageRegistry pages, DesktopPartFactory components,
                         PageMessagePresenter messages) throws gold.debug.windowstolinux.appapi.task.result.ApiException {
        super(owner, messages.text("settings.debug.open"), ModalityType.MODELESS);
        this.pages = pages;
        this.components = components;
        this.messages = new PageMessagePresenter(messages.catalog());
        gold.debug.windowstolinux.app.ui.language.LocaleBinding.title(this,
            gold.debug.windowstolinux.app.ui.language.LocalizedMessage.of("settings.debug.open"), messages.catalog());
        setName("settings.uiDebug.gallery");
        var body = new JPanel(new BorderLayout(12, 12));
        var heading = new JPanel(new BorderLayout(8, 8));
        heading.add(messages.bind(new JLabel(), "debug.pages.hint"), BorderLayout.NORTH);
        heading.add(navigation, BorderLayout.CENTER);
        heading.add(messages.bind(new JLabel(), "debug.dialogs.hint"), BorderLayout.SOUTH);
        body.add(heading, BorderLayout.NORTH);
        list.setName("debug.previews");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((values, value, index, selected, focused) -> {
            var label = new JLabel(value == null ? "" : value.spec().defaultTitle());
            label.setOpaque(true);
            label.setBackground(selected ? values.getSelectionBackground() : values.getBackground());
            label.setForeground(selected ? values.getSelectionForeground() : values.getForeground());
            return label;
        });
        components.cardList(list, false);
        body.add(new JScrollPane(list));
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var server = messages.bind(components.secondaryButton(""), "auto.addServer");
        server.setName("debug.server");
        server.addActionListener(event -> server());
        open = messages.bind(components.primaryButton(""), "debug.open");
        open.setName("debug.open");
        open.addActionListener(event -> open());
        list.addListSelectionListener(event -> open.setEnabled(list.getSelectedValue() != null));
        open.setEnabled(false);
        var model = messages.bind(components.secondaryButton(""), "debug.ai.edit");
        model.setName("debug.model");
        model.addActionListener(event -> {
            var dialog = gold.debug.windowstolinux.app.ui.ai.PlatformModelsPane.preview(this, components, messages);
            dialog.setName("debug.preview.model");
            dialog.setVisible(true);
        });
        actions.add(server);
        actions.add(model);
        actions.add(open);
        DesktopDialogs.install(this, body, actions, 760, 620);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        subscription = pages.onChange(this::refresh);
        refresh();
    }

    /** Installs actual current workspace links without opening or pinning any page. / 不打开或固定页面地安装真实当前工作区链接。
     * @param links exact current host selections / 精确当前宿主选择 */
    public void navigation(java.util.List<PageLink> links) {
        navigation.removeAll();
        for (var link : links) {
            var button = components.secondaryButton(link.label());
            button.setName("debug.page." + link.id());
            button.addActionListener(event -> link.open().run());
            navigation.add(button);
        }
        navigation.revalidate();
        navigation.repaint();
    }

    /** Refreshes registered metadata without opening any author view. / 不打开作者视图地刷新登记元数据。 */
    private void refresh() {
        var selected = list.getSelectedValue();
        try {
            entries.clear();
            for (var contribution : pages.previews())
                entries.addElement(contribution);
            if (selected != null)
                for (int i = 0; i < entries.size(); i++)
                    if (entries.get(i).key().equals(selected.key()))
                        list.setSelectedIndex(i);
            if (!initialized && !entries.isEmpty())
                list.setSelectedIndex(0);
            initialized = true;
            open.setEnabled(list.getSelectedValue() != null);
        } catch (gold.debug.windowstolinux.appapi.task.result.ApiException failure) {
            getRootPane().putClientProperty("debug.problem", failure.problem());
        }
    }

    /** Opens only the selected exact original preview. / 仅打开所选精确原预览。 */
    private void open() {
        if (list.getSelectedValue() == null)
            return;
        try {
            pages.openPreview(list.getSelectedValue().key());
        } catch (gold.debug.windowstolinux.appapi.task.result.ApiException failure) {
            getRootPane().putClientProperty("debug.problem", failure.problem());
        }
    }

    /** Reuses the actual connection editor with an isolated forbidden-write callback. / 使用隔离禁写回调复用真实连接编辑器。 */
    private void server() {
        var sample = new ServerProfile("preview-server", "192.0.2.10", 22, "preview", "preview/credential",
            messages.text("debug.sample.server"));
        var dialog = new ServerProfileDialog(this, (profile, password) -> {
            Arrays.fill(password, '\0');
            throw new UnsupportedOperationException("Isolated UI preview cannot save");
        }, components, messages, sample, ignored -> {
        });
        dialog.setName("debug.preview.server");
        disableWrite(dialog);
        dialog.setVisible(true);
    }

    /** Disables the actual editor write affordance while retaining its guarded isolated callback. / 保留受控隔离回调并禁用真实编辑器写入入口。
     * @param dialog actual isolated editor / 实际隔离编辑器 */
    private static void disableWrite(JDialog dialog) {
        var pending = new java.util.ArrayDeque<Component>();
        pending.add(dialog);
        while (!pending.isEmpty()) {
            var component = pending.removeFirst();
            if (component instanceof JButton button && "server.save".equals(button.getName()))
                button.setEnabled(false);
            if (component instanceof Container container)
                for (var child : container.getComponents())
                    pending.add(child);
        }
    }

    /** Refreshes the same gallery and nested platform previews after an appearance change. / 外观改变后刷新同一目录及嵌套平台预览。
     */
    public void localize() {
        components.refreshTheme(this);
        gold.debug.windowstolinux.app.ui.language.LocaleBinding.refresh(this, messages.catalog());
        for (var owned : getOwnedWindows()) {
            components.refreshTheme(owned);
            gold.debug.windowstolinux.app.ui.language.LocaleBinding.refresh(owned, messages.catalog());
        }
        refresh();
    }

    /** Closes author previews before releasing metadata observation and owned windows. / 释放元数据观察及从属窗口前关闭作者预览。 */
    @Override
    public void dispose() {
        try {
            pages.closePreviews();
            if (subscription != null)
                subscription.close();
            subscription = null;
            for (var owned : getOwnedWindows())
                owned.dispose();
            super.dispose();
        } catch (Exception failure) {
            getRootPane().putClientProperty("debug.cleanupProblem",
                failure instanceof gold.debug.windowstolinux.appapi.task.result.ApiException api
                    ? api.problem()
                    : new gold.debug.windowstolinux.appapi.task.result.Problem(
                    gold.debug.windowstolinux.appapi.task.result.ProblemCode.CONTRACT_VIOLATION,
                    "PREVIEW_CLEANUP_FAILED", "Preview cleanup remains incomplete", java.util.Map.of(),
                    java.util.Optional.empty()));
        }
    }
}
