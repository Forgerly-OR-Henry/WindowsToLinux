package gold.debug.windowstolinux.app.ui.mod;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.formdev.flatlaf.util.UIScale;

import gold.debug.windowstolinux.app.service.mod.PlatformModIntents.*;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents.Choice;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.app.service.mod.install.PlatformModDeletion;
import gold.debug.windowstolinux.app.service.mod.install.PlatformModInstallation;
import gold.debug.windowstolinux.app.service.mod.install.PlatformModReinstallation;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.control.DesktopCardList;
import gold.debug.windowstolinux.app.ui.control.DesktopIcons;
import gold.debug.windowstolinux.app.ui.control.WrappingFlowLayout;
import gold.debug.windowstolinux.app.ui.display.DesktopListCard;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.LocaleBinding;
import gold.debug.windowstolinux.app.ui.language.LocalizedMessage;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageContribution;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** Manages saved Mod choices and explicit data maintenance through the original host. / 经原宿主管理 Mod 已存选择及明确数据维护。 */
public final class PlatformModsPane extends JPanel implements AutoCloseable {
    /** Original graph maintenance supplied by the main composition root. / 主装配根提供的原图维护。 */
    public interface SchemaActions {
        /** Reviews the exact failed target and confirmed source. / 审阅精确失败目标及已确认来源。
         * @param owner selected owner / 已选所有者
         * @param source explicitly selected source / 明确选定来源
         * @return original opaque review / 原不透明审阅
         * @throws Exception if review is unavailable / 审阅不可用时 */
        RetryReview review(String owner, Optional<SchemaIdentity> source) throws Exception;

        /** Executes or finishes the original retry. / 执行或收尾原重试。
         * @param review confirmed original review / 已确认原审阅
         * @return observed data outcome / 观察到的数据结果
         * @throws Exception if acceptance or cleanup remains uncertain / 接纳或清理仍不确定时 */
        TaskResult retry(RetryReview review) throws Exception;

        /** Reconciles the original interrupted preparation without replay. / 不重放地核对原中断准备。
         * @param operation original preparation / 原准备
         * @return verified data outcome / 已核实数据结果
         * @throws Exception if evidence or cleanup remains uncertain / 证据或清理仍不确定时 */
        TaskResult reconcile(UUID operation) throws Exception;
    }

    /** Original installed-program maintenance collaborator. / 原已安装程序维护协作者。 */
    private final InstalledMaintenance maintenance = new InstalledMaintenance();

    /** Action controls kept separate from persistent selection and worker state. / 独立于持久选择及工作线程状态的动作控件。 */
    private final ActionControls view;

    /** Actual serialized management service. / 实际串行管理服务。 */
    private final PlatformModIntents service;

    /** Actual original graph maintenance. / 实际原图维护。 */
    private final SchemaActions schemas;

    /** Localized safe feedback. / 本地化安全反馈。 */
    private final PageMessagePresenter messages;

    /** Current themed controls. / 当前主题控件。 */
    private final DesktopPartFactory components;

    /** Shared exact page publication and persisted navigation preferences. / 共用的精确页面发布及持久导航偏好。 */
    private final ModNavigationPane navigation;

    /** Actual published pages for the selected owner. / 所选所有者的实际已发布页面。 */
    private final JComboBox<PageContribution> pagePicker = new JComboBox<>();

    /** Host subscription released with this view. / 随此视图释放的宿主订阅。 */
    private final AutoCloseable navigationSubscription;

    /** Installed identities without mutable authority. / 不含修改权限的已安装身份。 */
    private final DefaultListModel<ModView> rows = new DefaultListModel<>();

    /** Single selected Mod, independent of its version choice. / 独立于版本选择的单选 Mod。 */
    private final DesktopCardList<ModView> inventory = new DesktopCardList<>(rows, 116);

    private final JTextField search = new JTextField();

    private final ModCatalogSurface surface;

    private final ModSelectionDraft draft = new ModSelectionDraft();

    private List<ModView> installed = List.of();

    private List<PendingChange> pending = List.of();

    private final JPanel detailBody = new JPanel();

    private final JPanel dependencies = new JPanel();

    private final JTextArea description = new JTextArea();

    private final JTextArea savedFacts = new JTextArea();

    private final JPanel feedback;

    private Runnable showStore = () -> {
    }, showOriginals = () -> {
    }, restart = () -> {
    };

    private Consumer<String> showStoreMod = ignored -> {
    };

    private JDialog pendingDialog;

    /** Draft whole-Mod switch reviewed before saving. / 保存前需审阅的整个 Mod 开关草稿。 */
    private final JCheckBox enabled = new JCheckBox();

    /** Actual retained facts, never inferred from the draft switch. / 不从草稿开关推断的实际保留事实。 */
    private final JTextArea details = new JTextArea();

    /** Exact downloaded release, independent of the saved choice. / 独立于已存选择的精确已下载版本。 */
    private final JComboBox<String> versions = new JComboBox<>();

    /** Original retry handles retained only for unfinished physical cleanup. / 仅为未完成物理清理保留的原重试句柄。 */
    private final Map<String, RetryReview> retained = new HashMap<>();

    /** Draft selected version, which does not change persistent choice. / 不改变持久选择的草稿单选版本。 */
    private String selectedVersion;

    /** Current privacy, lifetime and worker exclusion. / 当前隐私、寿命及工作线程互斥。 */
    private boolean locked = true, closed, busy;

    /** Keeps model replacement separate from explicit user selection and editing. / 区分模型替换与用户主动选择及编辑。 */
    private boolean updating;

    /** Invalidates results and confirmations across lock changes. / 使跨锁定变化的结果及确认失效。 */
    private long epoch;

    /** Owned confirmation closed when its private review becomes stale. / 私有审阅过期时关闭的从属确认。 */
    private JDialog confirmation;

    /** Builds the permanent management page on the event thread. / 在事件线程构造常驻管理页面。
     * @param service actual intent owner / 实际意图所有者
     * @param schemas actual maintenance callbacks / 实际维护回调
     * @param components themed components / 主题控件
     * @param messages safe localized feedback / 安全本地化反馈
     * @param showLogs opens existing platform log management / 打开既有平台日志管理
     * @param navigation actual publication and navigation owner / 实际发布及导航所有者 */
    public PlatformModsPane(PlatformModIntents service, SchemaActions schemas, DesktopPartFactory components,
                            PageMessagePresenter messages, Runnable showLogs, ModNavigationPane navigation) {
        super(new BorderLayout(12, 12));
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Mod management requires the EDT");
        this.service = Objects.requireNonNull(service);
        this.schemas = Objects.requireNonNull(schemas);
        this.components = components;
        this.messages = messages;
        this.navigation = navigation;
        setName("platform.mods");
        components.stylePage(this);
        inventory.setName("mods.inventory");
        inventory.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        components.cardList(inventory, false);
        inventory.setCellRenderer((list, value, index, selected, focus) -> renderCard(value, selected));
        inventory.addListSelectionListener(event -> {
            if (!updating && !event.getValueIsAdjusting())
                render();
        });
        enabled.setName("mods.enabled");
        messages.bind(enabled, "mods.enabled");
        enabled.setOpaque(false);
        enabled.addActionListener(event -> edited());
        versions.setName("mods.versions");
        versions.setPrototypeDisplayValue("000.000.000");
        versions.addActionListener(event -> {
            if (!updating) {
                selectedVersion = (String) versions.getSelectedItem();
                edited();
                renderDependencies();
            }
        });
        details.setName("mods.details");
        savedFacts.setName("mods.savedFacts");
        for (var area : List.of(details, savedFacts, description)) {
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setOpaque(false);
            area.setBorder(BorderFactory.createEmptyBorder());
        }
        pagePicker.setName("mods.pages");
        pagePicker.setRenderer((list, value, index, selected, focus) -> {
            var renderer = new DefaultListCellRenderer();
            renderer.putClientProperty("html.disable", true);
            return renderer.getListCellRendererComponent(list,
                value == null
                    ? ""
                    : value.spec().defaultTitle()
                    + (value.spec().settingsPage() ? " · " + messages.text("mods.settingsPage") : ""),
                index, selected, focus);
        });
        pagePicker.addActionListener(event -> pageControls());
        view = new ActionControls(showLogs);
        search.setName("mods.search");
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) {
                filter();
            }

            public void removeUpdate(DocumentEvent event) {
                filter();
            }

            public void changedUpdate(DocumentEvent event) {
                filter();
            }
        });
        surface = new ModCatalogSurface(components, messages, inventory, search, view.refresh, false);
        surface.modeAction(() -> {
            leaveDetails();
            this.showStore.run();
        });
        detailBody.setOpaque(false);
        detailBody.setLayout(new BoxLayout(detailBody, BoxLayout.Y_AXIS));
        var status = components.transparent(new BorderLayout(0, UIScale.scale(8)));
        description.putClientProperty("html.disable", true);
        status.add(description, BorderLayout.NORTH);
        status.add(savedFacts);
        detailBody.add(section("modui.status", status));
        var edit = components.transparent(new WrappingFlowLayout(new FlowLayout(FlowLayout.LEFT, UIScale.scale(8), UIScale.scale(4))));
        var versionField = components.transparent(new FlowLayout(FlowLayout.LEFT, UIScale.scale(8), 0));
        versionField.add(messages.bind(new JLabel(), "modui.downloadedVersion"));
        versionField.add(versions);
        edit.add(enabled);
        edit.add(new JComponent() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(Math.max(0, edit.getWidth() - enabled.getPreferredSize().width
                    - versionField.getPreferredSize().width - UIScale.scale(8) * 4), 1);
            }
        });
        edit.add(versionField);
        detailBody.add(section("modui.enableVersion", edit));
        dependencies.setOpaque(false);
        dependencies.setLayout(new BoxLayout(dependencies, BoxLayout.Y_AXIS));
        detailBody.add(section("modui.dependencies", dependencies));
        var pages = components.transparent(new BorderLayout(UIScale.scale(8), 0));
        pages.add(pagePicker);
        pages.add(view.openPage, BorderLayout.EAST);
        detailBody.add(section("modui.pages", pages));
        feedback = components.transparent(new BorderLayout(0, UIScale.scale(8)));
        feedback.add(details, BorderLayout.CENTER);
        feedback.add(view.progress, BorderLayout.NORTH);
        feedback.add(view.status, BorderLayout.SOUTH);
        detailBody.add(section("modui.operationState", feedback));
        surface.details(detailBody);
        surface.footer(view.footer);
        add(surface);
        navigationSubscription = navigation.onChange(this::renderPages);
        controls();
        render();
    }

    private JPanel section(String key, JComponent body) {
        var card = components.outlinedCard(new BorderLayout(0, UIScale.scale(12)));
        var title = messages.bind(new ModTextLabel("", 18, true, false), key);
        card.add(title, BorderLayout.NORTH);
        card.add(body);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return wrap(card);
    }

    private JPanel wrap(JPanel card) {
        var wrapper = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(0, 0, UIScale.scale(14), 0));
        wrapper.add(card);
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        return wrapper;
    }

    /** Adds installed-layout maintenance after actual installation services are composed. / 实际安装服务装配后加入安装布局维护。
     * @param installation original installed program owner / 原安装程序所有者
     * @param refreshOriginals original observation refresh / 原包观察刷新 */
    public void installMaintenance(PlatformModInstallation installation, Runnable refreshOriginals) {
        if (!SwingUtilities.isEventDispatchThread() || maintenance.installation != null)
            throw new IllegalStateException("Installed maintenance must be composed once on the EDT");
        maintenance.installation = Objects.requireNonNull(installation);
        maintenance.deletion = installation.deletion();
        maintenance.refreshOriginals = Objects.requireNonNull(refreshOriginals);
        controls();
    }

    /** Creates one named action under the current view boundary. / 在当前视图边界下创建一个命名操作。
     * @param key action key / 操作键
     * @param action explicit user action / 明确用户操作
     * @return themed named button / 主题命名按钮 */
    private JButton button(String key, Runnable action) {
        var button = messages.bind(components.secondaryButton(""), "mods." + key);
        button.setName("mods." + key);
        button.addActionListener(event -> {
            if (!locked && !closed && !busy)
                action.run();
        });
        return button;
    }

    /** Applies actual privacy and discards stale visible evidence. / 应用实际隐私并丢弃过期可见证据。
     * @param value current lock state / 当前锁定状态 */
    public void setLocked(boolean value) {
        locked = value;
        epoch++;
        if (confirmation != null)
            confirmation.dispose();
        if (pendingDialog != null)
            pendingDialog.dispose();
        if (locked) {
            installed = List.of();
            pending = List.of();
            rows.clear();
            draft.bind(null, null);
        }
        LocaleBinding.unbind(view.status);
        view.status.setText("");
        render();
        if (!locked)
            refresh();
    }

    /** Reloads durable facts while preserving this visible editor. / 后台重读持久事实并保留当前可见编辑。 */
    public void refresh() {
        run(() -> new Snapshot(service.list(), service.pendingChanges()), snapshot -> {
            installed = snapshot.mods();
            pending = snapshot.changes();
            for (var value : installed) {
                if (value.operations().stream()
                    .noneMatch(o -> o.type().equals("MIGRATE") && o.state().equals("RUNNING")))
                    retained.remove(value.owner());
            }
            filter();
            if (rows.isEmpty() && view.status.getText().isBlank())
                messages.bind(view.status, "mods.empty");
        });
    }

    /** Wires navigation without handing business mutation authority to the shell. / 接入导航，不向外壳移交业务修改权限。
     * @param showStore opens the existing store / 打开原商店
     * @param showOriginals opens the original package manager / 打开原包管理
     * @param showStoreMod opens one exact store owner / 打开指定商店所有者
     * @param restart requests the host-owned restart / 请求宿主持有的重启 */
    public void workspaceActions(Runnable showStore, Runnable showOriginals, Consumer<String> showStoreMod,
                                 Runnable restart) {
        this.showStore = Objects.requireNonNull(showStore);
        this.showOriginals = Objects.requireNonNull(showOriginals);
        this.showStoreMod = Objects.requireNonNull(showStoreMod);
        this.restart = Objects.requireNonNull(restart);
    }

    /** Selects an already downloaded dependency by stable owner. / 按稳定所有者选择已下载依赖。
     * @param owner exact installed identity / 精确已安装身份 */
    public void selectOwner(String owner) {
        if (locked || closed || busy) return;
        search.setText("");
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).owner().equals(owner)) {
                inventory.setSelectedIndex(index);
                inventory.ensureIndexIsVisible(index);
                return;
            }
        }
    }

    /** Leaving a detail discards only its unsaved controls. / 离开详情仅丢弃未保存控件草稿。 */
    public void leaveDetails() {
        if (confirmation != null) confirmation.dispose();
        if (pendingDialog != null) pendingDialog.dispose();
        inventory.clearSelection();
        draft.bind(null, null);
        render();
    }

    private void filter() {
        var selected = inventory.getSelectedValue();
        String owner = selected == null ? null : selected.owner();
        String keyword = search.getText().strip().toLowerCase(Locale.ROOT);
        updating = true;
        try {
            rows.clear();
            for (var value : installed) {
                String text = name(value) + " " + value.owner() + " "
                    + String.join(" ", value.versions().stream().map(v -> v.manifest().version()).toList());
                if (!text.toLowerCase(Locale.ROOT).contains(keyword)) continue;
                rows.addElement(value);
                if (value.owner().equals(owner)) inventory.setSelectedIndex(rows.size() - 1);
            }
        } finally {
            updating = false;
        }
        surface.count(rows.size());
        render();
    }

    /** Displays saved intention, actual state and all retained data separately. / 分开展示已存意图、实际状态及全部保留数据。 */
    private void render() {
        var value = inventory.getSelectedValue();
        draft.bind(value == null ? null : value.owner(), value == null ? null : value.choice());
        updating = true;
        versions.removeAllItems();
        details.setText("");
        savedFacts.setText("");
        description.setText("");
        enabled.setSelected(value != null && draft.choice().enabled());
        selectedVersion = value == null ? null : draft.choice().version().orElse(null);
        if (value != null) {
            var text = new StringBuilder(messages.text("mods.saved",
                Map.of("choice", messages.text(value.choice().enabled() ? "mods.on" : "mods.off"), "version",
                    value.choice().version().orElse("—"))));
            text.append('\n').append(messages.text("mods.actual",
                Map.of("state", state(value.runtime()), "version", value.actual().orElse("—"))));
            savedFacts.setText(text.toString());
            text.setLength(0);
            if (maintenance.installation != null && !value.choice().enabled() && !value.stopAcknowledged())
                text.append('\n').append(messages.text("mods.repairNotice"));
            value.failure().ifPresent(problem -> text.append('\n').append(messages.safe(problem)));
            for (var data : value.data()) {
                text.append('\n').append("Schema ").append(data.identity().schemaVersion()).append(" · ")
                    .append(state(data.state()));
                if (data.state().equals("FAILED")) text.append(" · ").append(messages.text("mods.dataFailed"));
            }
            for (var operation : value.operations())
                text.append('\n')
                    .append(messages.text(operation.request().isPresent() ? "mods.pending" : "mods.operation",
                        Map.of("type", state(operation.type()), "state", state(operation.state()), "phase",
                            state(operation.phase()))));
            details.setText(text.toString());
            details.setCaretPosition(0);
            for (var release : value.versions()) {
                var manifest = release.manifest();
                versions.addItem(manifest.version());
                text.append('\n').append(manifest.version()).append(" · ")
                    .append(manifest.schemaVersion().map(s -> "Schema " + s).orElse(messages.text("mods.noData")))
                    .append(" · ").append(messages.text("mods.programState", Map.of("state", state(release.state()))));
            }
            details.setText(text.toString().strip());
            versions.setSelectedItem(selectedVersion);
            value.versions().stream().filter(v -> v.manifest().version().equals(selectedVersion)).findFirst()
                .flatMap(v -> v.manifest().description()).ifPresent(description::setText);
            surface.detailHeading(name(value), value.owner(), this::leaveDetails);
        }
        updating = false;
        surface.expanded(value != null);
        renderDependencies();
        renderPages();
        controls();
        detailBody.revalidate();
        detailBody.repaint();
    }

    private void edited() {
        if (updating || inventory.getSelectedValue() == null) return;
        draft.edit(new Choice(enabled.isSelected(), Optional.ofNullable(selectedVersion)));
        controls();
    }

    private static String name(ModView value) {
        return value.versions().isEmpty() ? value.owner() : value.versions().getFirst().manifest().name();
    }

    private JComponent renderCard(ModView value, boolean selected) {
        var content = components.transparent(new BorderLayout(UIScale.scale(14), 0));
        content.add(new JLabel(DesktopIcons.icon("mod/puzzle", 30,
            () -> UIManager.getColor("Component.accentColor"))), BorderLayout.WEST);
        var text = components.transparent(new GridBagLayout());
        var title = new ModTextLabel(name(value), 16, true, false);
        var subtitle = new ModTextLabel(value.owner() + "  ·  " + value.choice().version().orElse("—"), 13, false, true);
        var cell = new GridBagConstraints();
        cell.gridx = 0;
        cell.gridy = 0;
        cell.weightx = 1;
        cell.fill = GridBagConstraints.HORIZONTAL;
        cell.anchor = GridBagConstraints.WEST;
        text.add(title, cell);
        cell.gridy++;
        cell.insets = UIScale.scale(new Insets(4, 0, 0, 0));
        text.add(subtitle, cell);
        content.add(text);
        var states = components.transparent(new GridBagLayout());
        var badges = new ArrayList<JLabel>();
        badges.add(statusBadge(messages.text(value.choice().enabled() ? "mods.on" : "mods.off"),
            value.choice().enabled() ? 1 : 0));
        if (value.operations().stream().anyMatch(o -> o.request().isPresent()))
            badges.add(statusBadge(messages.text("modui.pendingBadge"), 3));
        badges.add(statusBadge(state(value.runtime()), value.runtime().equals("RUNNING") ? 2
            : value.runtime().equals("FAILED") ? 4 : 0));
        cell.gridy = 0;
        for (var badge : badges) {
            cell.insets = UIScale.scale(new Insets(cell.gridy == 0 ? 0 : 4, 0, 0, 0));
            states.add(badge, cell);
            cell.gridy++;
        }
        content.add(states, BorderLayout.EAST);
        content.setToolTipText(name(value) + " · " + value.owner());
        return DesktopListCard.row(content, selected, false);
    }

    private JLabel statusBadge(String text, int tone) {
        var result = components.badge(text);
        result.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, UIScale.scale(12f)));
        result.setHorizontalAlignment(SwingConstants.CENTER);
        result.setBorder(new com.formdev.flatlaf.ui.FlatEmptyBorder(3, 8, 3, 8));
        boolean dark = com.formdev.flatlaf.FlatLaf.isLafDark();
        if (tone == 0) {
            result.setForeground(UIManager.getColor("Label.disabledForeground"));
            result.setBackground(UIManager.getColor("Button.background"));
        } else if (tone == 2) {
            result.setForeground(new Color(dark ? 0x77D9A0 : 0x147A46));
            result.setBackground(new Color(dark ? 0x214B36 : 0xDCF6E8));
        } else if (tone == 3) {
            result.setForeground(new Color(dark ? 0xF4C66D : 0x926000));
            result.setBackground(new Color(dark ? 0x4D3D22 : 0xFFF0CC));
        } else if (tone == 4) {
            result.setForeground(new Color(dark ? 0xFF9999 : 0xB83232));
            result.setBackground(new Color(dark ? 0x572E32 : 0xFFE4E4));
        }
        return result;
    }

    private void renderDependencies() {
        dependencies.removeAll();
        var value = inventory.getSelectedValue();
        if (value != null) {
            var release = value.versions().stream().filter(v -> v.manifest().version().equals(selectedVersion)).findFirst();
            description.setText(release.flatMap(v -> v.manifest().description()).orElse(""));
            var declared = release.map(v -> v.manifest().dependencies()).orElse(List.of());
            for (var dependency : declared) {
                var target = installed.stream().filter(v -> v.owner().equals(dependency.id()) && !v.versions().isEmpty()).findFirst();
                var row = components.transparent(new BorderLayout(UIScale.scale(10), UIScale.scale(4)));
                row.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(8), 0, UIScale.scale(8), 0));
                row.add(new JLabel(DesktopIcons.icon("mod/puzzle", 22,
                    () -> UIManager.getColor("Component.accentColor"))), BorderLayout.WEST);
                var copy = components.transparent(new GridLayout(0, 1, 0, UIScale.scale(4)));
                copy.add(new ModTextLabel(target.map(PlatformModsPane::name).orElse(dependency.id()) + "  ·  "
                    + messages.text(dependency.optional() ? "modui.optional" : "modui.required"), 14, true, false));
                copy.add(new ModTextLabel(target.isPresent()
                    ? messages.text("modui.dependencyVersion", Map.of("range", dependency.range(), "version",
                    target.get().choice().version().orElse(target.get().versions().getFirst().manifest().version())))
                    : messages.text("modui.dependencyMissing", Map.of("range", dependency.range())), 12, false, true));
                row.add(copy);
                var jump = messages.bind(components.secondaryButton(""), target.isPresent() ? "modui.viewDetails" : "modui.visitStore");
                jump.setName("mods.dependency." + dependency.id());
                jump.setEnabled(!locked && !closed && !busy);
                jump.addActionListener(event -> {
                    if (locked || closed || busy) return;
                    if (target.isPresent()) selectOwner(dependency.id());
                    else {
                        leaveDetails();
                        showStoreMod.accept(dependency.id());
                    }
                });
                row.add(jump, BorderLayout.EAST);
                dependencies.add(row);
                if (declared.indexOf(dependency) < declared.size() - 1) dependencies.add(components.cardDivider());
            }
            if (declared.isEmpty()) dependencies.add(messages.bind(new JLabel(), "modui.noDependencies"));
        }
        dependencies.revalidate();
        dependencies.repaint();
    }

    private record Snapshot(List<ModView> mods, List<PendingChange> changes) {
    }

    /** Refreshes exact current-owner contributions while preserving the selected page identity. / 刷新当前所有者精确扩展并保留所选页面身份。 */
    private void renderPages() {
        var previous = (PageContribution) pagePicker.getSelectedItem();
        pagePicker.removeAllItems();
        var owner = inventory.getSelectedValue();
        if (!locked && !closed && owner != null) {
            for (var page : navigation.published(owner.owner())) {
                pagePicker.addItem(page);
                if (previous != null && previous.key().equals(page.key()))
                    pagePicker.setSelectedItem(page);
            }
        }
        pageControls();
    }

    /** Enables page controls only for current published identities and interactive host state. / 仅为当前已发布身份及可交互宿主状态启用页面控件。 */
    private void pageControls() {
        var page = (PageContribution) pagePicker.getSelectedItem();
        boolean active = !locked && !closed && !busy && navigation.available() && page != null;
        pagePicker.setEnabled(active);
        view.openPage.setEnabled(active);
    }

    /** Localizes a persisted state without changing its meaning. / 本地化持久状态，不改变其含义。
     * @param value persisted state / 持久状态
     * @return localized state or original code / 本地化状态或原码 */
    private String state(String value) {
        String key = "mods.state." + value;
        try {
            return messages.text(key);
        } catch (IllegalArgumentException unavailable) {
            if (unavailable.getCause() instanceof MissingResourceException)
                return value;
            throw unavailable;
        }
    }

    /** Enables actions from visible evidence while backend admission remains authoritative. / 根据可见证据启用操作，后端准入仍为依据。 */
    private void controls() {
        boolean active = !locked && !closed && !busy;
        view.progress.setVisible(busy && !locked && !closed);
        var value = inventory.getSelectedValue();
        boolean deleting = value != null
            && value.operations().stream().anyMatch(InstalledMaintenance::deletionOperation);
        boolean repairing = value != null && value.operations().stream().anyMatch(o -> o.type().equals("REINSTALL"));
        boolean maintaining = deleting || repairing;
        inventory.setEnabled(active);
        enabled.setEnabled(active && value != null && !maintaining);
        view.refresh.setEnabled(active);
        view.logs.setEnabled(active);
        view.save.setEnabled(
            active && value != null && !maintaining && (!enabled.isSelected() || selectedVersion != null)
                && draft.changed());
        search.setEnabled(active);
        view.more.setEnabled(active);
        view.changes.setEnabled(active && !pending.isEmpty());
        view.saveRestart.setEnabled(active && (view.save.isEnabled() || !pending.isEmpty()));
        view.prepare.setVisible(value != null && value.choice().enabled() && value.actual().isEmpty()
            && Set.of("STOPPED", "FAILED").contains(value.runtime()) && value.operations().isEmpty()
            && value.versions().stream().anyMatch(v -> v.state().equals("READY")
            && value.choice().version().equals(Optional.of(v.manifest().version()))
            && v.manifest().schemaVersion().isPresent() && value.data().stream()
            .noneMatch(d -> v.manifest().schemaVersion().equals(Optional.of(d.identity().schemaVersion())))));
        view.prepare.setEnabled(active && value != null && !maintaining
            && enabled.isSelected() == value.choice().enabled()
            && Optional.ofNullable(selectedVersion).equals(value.choice().version()));
        view.cancel.setVisible(value != null && value.operations().stream().anyMatch(o -> o.request().isPresent()));
        view.cancel.setEnabled(active && value != null);
        view.reconcile.setVisible(value != null && value.operations().stream()
            .anyMatch(o -> o.type().equals("MIGRATE") && o.state().equals("UNKNOWN")));
        view.reconcile.setEnabled(active && value != null);
        boolean finishing = value != null && retained.containsKey(value.owner());
        messages.bind(view.retry, finishing ? "mods.finish" : "mods.retry");
        view.retry.setVisible(finishing || value != null && value.choice().enabled() && value.versions().stream()
            .anyMatch(v -> value.choice().version().equals(Optional.of(v.manifest().version().toString()))
                && value.data().stream().anyMatch(d -> d.retryable()
                && v.manifest().schemaVersion().equals(Optional.of(d.identity().schemaVersion())))));
        view.retry.setEnabled(active && value != null);
        view.deleteVersion.setVisible(maintenance.deletion != null);
        view.deleteVersion.setEnabled(active && maintenance.deletion != null && value != null
            && !value.choice().enabled() && value.stopAcknowledged() && !enabled.isSelected()
            && selectedVersion != null && value.operations().isEmpty());
        view.deleteMod.setVisible(maintenance.deletion != null);
        view.deleteMod.setEnabled(active && maintenance.deletion != null && value != null && !value.choice().enabled()
            && value.stopAcknowledged() && !enabled.isSelected() && value.operations().isEmpty());
        view.reconcileDeletion.setVisible(maintenance.deletion != null && deleting);
        view.reconcileDeletion.setEnabled(active && value != null && value.operations().stream()
            .anyMatch(o -> InstalledMaintenance.deletionOperation(o) && o.state().equals("UNKNOWN")));
        view.repair.setVisible(maintenance.installation != null);
        view.repair.setEnabled(active && maintenance.installation != null && value != null && value.stopAcknowledged()
            && !enabled.isSelected() && selectedVersion != null && value.operations().isEmpty()
            && value.versions().stream().anyMatch(
            v -> v.manifest().version().toString().equals(selectedVersion) && v.state().equals("READY")));
        view.reconcileRepair.setVisible(maintenance.installation != null && repairing);
        view.reconcileRepair.setEnabled(active && value != null && value.operations().stream()
            .anyMatch(o -> o.type().equals("REINSTALL") && o.state().equals("UNKNOWN")));
        view.restoreProgram.setVisible(maintenance.installation != null && repairing);
        view.restoreProgram.setEnabled(active && value != null
            && value.operations().stream().anyMatch(o -> o.type().equals("REINSTALL") && o.state().equals("UNKNOWN")
            && Set.of("VALIDATED", "STAGED", "OLD_SAVED", "FILES_PUBLISHED").contains(o.phase())));
        versions.setEnabled(active && value != null && !maintaining && versions.getItemCount() > 0);
        for (var child : dependencies.getComponents())
            if (child instanceof JPanel row)
                for (var field : row.getComponents())
                    if (field instanceof JButton button) button.setEnabled(active);
        pageControls();
    }

    /** Selects and explicitly confirms a source using actual runtime then semantic version order. / 按实际运行再语义版本顺序选择并明确确认来源。
     * @param value original management snapshot / 原管理快照
     * @param version target release / 目标发布
     * @param retry whether rebuilding a failed target / 是否重建失败目标
     * @return source, empty for no migration, or null for cancellation / 来源，无迁移为空，取消为 null */
    private Optional<SchemaIdentity> source(ModView value, String version, boolean retry) {
        var target = value.versions().stream().filter(v -> v.manifest().version().toString().equals(version))
            .findFirst().orElseThrow();
        var schema = target.manifest().schemaVersion();
        if (schema.isEmpty() || !retry && value.data().stream()
            .anyMatch(d -> schema.get().equals(d.identity().schemaVersion()) && d.state().equals("READY")))
            return Optional.empty();
        var data = value.data().stream().filter(d -> !retry || !schema.get().equals(d.identity().schemaVersion()))
            .toList();
        if (data.isEmpty())
            return Optional.empty();
        var choices = new ArrayList<SourceChoice>();
        for (var release : value.versions())
            for (var candidate : data)
                if (release.manifest().schemaVersion().equals(Optional.of(candidate.identity().schemaVersion())))
                    choices.add(new SourceChoice(release.manifest().version().toString(), candidate));
        var select = new JComboBox<>(choices.toArray(SourceChoice[]::new));
        select.setName("mods.source");
        select.setRenderer(
            (list, item, index, selected, focus) -> new DefaultListCellRenderer().getListCellRendererComponent(list,
                item == null
                    ? ""
                    : item.version() + " · Schema " + item.data().identity().schemaVersion() + " · "
                    + state(item.data().state()),
                index, selected, focus));
        if (value.runtime().equals("RUNNING"))
            choices.stream().filter(c -> value.actual().equals(Optional.of(c.version()))).findFirst()
                .ifPresent(select::setSelectedItem);
        var panel = new JPanel(new BorderLayout(8, 8));
        panel.add(messages.bind(new JLabel(), "mods.sourceNotice"), BorderLayout.NORTH);
        panel.add(select);
        long original = epoch;
        if (JOptionPane.showConfirmDialog(this, panel, messages.text("mods.sourceTitle"),
            JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION || locked || closed || epoch != original)
            return null;
        var chosen = (SourceChoice) select.getSelectedItem();
        return chosen == null ? Optional.empty() : Optional.of(chosen.data().identity());
    }

    /** Shares truthful repair and separate archive-cleanup feedback across both management pages. / 两个管理页共用准确修复及独立原包清理反馈。
     * @param messages localized safe presenter / 本地化安全呈现器
     * @param result original backend result / 原后端结果
     * @return localized actual outcome / 本地化实际结果 */
    static String repairMessage(PageMessagePresenter messages,
                                OperationResult<PlatformModReinstallation.Receipt> result) {
        if (result.outcome() == TaskResult.SUCCEEDED)
            return messages.text("mods.repaired") + " "
                + messages.text("originals.cleanup." + result.value().orElseThrow().originalCleanup());
        if (result.outcome() == TaskResult.FAILED
            && result.value().map(PlatformModReinstallation.Receipt::restored).orElse(false))
            return messages.text("mods.restoredProgram");
        return messages.text("mods.repairPending");
    }

    /** Reviews a draft or exact absent data target before confirmation. / 在确认前审阅草稿或精确缺失数据目标。
     * @param preparing whether explicitly preparing the unchanged selection / 是否明确准备未改变的选择 */
    private void reviewChoice(boolean preparing) {
        reviewChoice(preparing, false);
    }

    private void reviewChoice(boolean preparing, boolean restartAfter) {
        var value = inventory.getSelectedValue();
        if (value == null) return;
        String owner = value.owner();
        long session = draft.session();
        var target = new Choice(enabled.isSelected(), Optional.ofNullable(selectedVersion));
        var source = target.enabled() ? source(value, selectedVersion, false) : Optional.<SchemaIdentity>empty();
        if (source == null || !draft.matches(owner, session))
            return;
        run(() -> preparing ? service.reviewPreparation(value.owner(), source) : service.review(value.owner(), target, source), review -> {
            if (!draft.matches(owner, session)) return;
            if (confirm((preparing ? messages.text("mods.confirmPreparation") + "\n" : "") + summary(review, source))
                && draft.matches(owner, session)) {
                run(() -> service.apply(review, true), ignored -> {
                    if (!draft.matches(owner, session)) {
                        refresh();
                        return;
                    }
                    draft.accepted(owner, session, target);
                    messages.bind(view.status, "mods.savedRestart");
                    if (restartAfter) reviewRestart();
                    else refresh();
                }, true);
            }
        });
    }

    private void saveAndRestart() {
        if (draft.changed()) reviewChoice(false, true);
        else reviewRestart();
    }

    private void reviewRestart() {
        var selected = inventory.getSelectedValue();
        String owner = selected == null ? null : selected.owner();
        long session = draft.session();
        run(() -> new Snapshot(service.list(), service.pendingChanges()), snapshot -> {
            installed = snapshot.mods();
            pending = snapshot.changes();
            filter();
            var changes = pending;
            controls();
            if (changes.isEmpty() || !draft.matches(owner, session)) return;
            var summary = new StringBuilder(messages.text("modui.restartConfirm"));
            for (var change : changes)
                summary.append("\n\n").append(change.name()).append(" · ").append(change.owner())
                    .append('\n').append(changeText(change));
            if (confirm(summary.toString()) && draft.matches(owner, session)) {
                run(service::pendingChanges, actual -> {
                    if (!draft.matches(owner, session)) return;
                    if (actual.equals(changes)) restart.run();
                    else {
                        pending = actual;
                        messages.bind(view.status, "modui.pendingChanged");
                        controls();
                    }
                });
            }
        });
    }

    /** Shows all changed switches and the original admission scope. / 展示全部变化开关及原准入范围。
     * @param review original review / 原审阅
     * @param source confirmed data source / 已确认数据来源
     * @return concrete confirmation text / 具体确认正文 */
    private String summary(Review review, Optional<SchemaIdentity> source) {
        var text = new StringBuilder(messages.text("mods.confirmChoice"));
        review.choices()
            .forEach((owner, choice) -> text.append('\n').append(owner).append(" → ")
                .append(messages.text(choice.enabled() ? "mods.on" : "mods.off")).append(" · ")
                .append(choice.version().orElse("—")));
        source.ifPresent(s -> text.append('\n')
            .append(messages.text("mods.confirmSource", Map.of("schema", s.schemaVersion()))));
        if (!review.cascade().isEmpty())
            text.append('\n')
                .append(messages.text("mods.cascade", Map.of("owners", String.join(", ", review.cascade()))));
        text.append('\n')
            .append(messages.text("mods.affected", Map.of("owners", String.join(", ", review.affected()))));
        return text.toString();
    }

    /** Reviews withdrawal of the entire original pending group. / 审阅撤销整个原待执行组。 */
    private void cancelRequest() {
        var value = inventory.getSelectedValue();
        if (value == null) return;
        var request = value.operations().stream().flatMap(o -> o.request().stream()).findFirst()
            .orElseThrow();
        run(() -> service.reviewCancellation(request), review -> {
            if (confirm(messages.text("mods.confirmCancel") + "\n" + summary(review, Optional.empty())))
                run(() -> service.apply(review, true), ignored -> {
                    var selected = inventory.getSelectedValue();
                    if (selected != null && review.choices().containsKey(selected.owner()))
                        draft.accepted(selected.owner(), draft.session(), review.choices().get(selected.owner()));
                    completed("mods.cancelled");
                }, true);
        });
    }

    private void showChanges() {
        run(service::pendingChanges, changes -> {
            pending = changes;
            controls();
            var dialog = new JDialog(SwingUtilities.getWindowAncestor(this), messages.text("modui.pendingTitle"),
                Dialog.ModalityType.APPLICATION_MODAL);
            dialog.setName("mods.changes");
            LocaleBinding.title(dialog, LocalizedMessage.of("modui.pendingTitle"), messages.catalog());
            var body = components.pagePanel();
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(16), UIScale.scale(18),
                UIScale.scale(16), UIScale.scale(18)));
            var caption = messages.bind(new ModTextLabel("", 14, true, false), "modui.pendingRestart");
            caption.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(caption);
            body.add(Box.createVerticalStrut(UIScale.scale(12)));
            for (var change : changes) {
                var content = components.transparent(new BorderLayout(UIScale.scale(12), UIScale.scale(8)));
                content.add(new JLabel(DesktopIcons.icon("mod/puzzle", 30,
                    () -> UIManager.getColor("Component.accentColor"))), BorderLayout.WEST);
                var copy = components.transparent(new BorderLayout(0, UIScale.scale(8)));
                var identity = components.transparent(new BorderLayout(0, UIScale.scale(3)));
                var name = new ModTextLabel(change.name(), 16, true, false);
                name.setToolTipText(change.name());
                identity.add(name, BorderLayout.NORTH);
                identity.add(new ModTextLabel(change.owner(), 14, false, true), BorderLayout.CENTER);
                copy.add(identity, BorderLayout.NORTH);
                var facts = new JTextArea(changeText(change));
                facts.setName("mods.change." + change.owner());
                facts.setEditable(false);
                facts.setLineWrap(true);
                facts.setWrapStyleWord(true);
                facts.setOpaque(false);
                copy.add(facts);
                content.add(copy);
                var undo = messages.bind(components.secondaryButton(""), "modui.undo");
                undo.setName("mods.undo." + change.owner());
                undo.addActionListener(event -> {
                    if (locked || closed || busy) return;
                    dialog.dispose();
                    withdraw(change.requestId());
                });
                var undoHolder = components.transparent(new GridBagLayout());
                undoHolder.add(undo);
                content.add(undoHolder, BorderLayout.EAST);
                var card = components.card(new BorderLayout());
                card.add(content);
                body.add(wrap(card));
            }
            if (changes.isEmpty()) body.add(messages.bind(new JLabel(), "modui.noChanges"));
            var scroll = new JScrollPane(body, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setName("mods.changes.scroll");
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().putClientProperty("desktop.inventory.scrollbar", true);
            scroll.getVerticalScrollBar().setUnitIncrement(UIScale.scale(20));
            var close = messages.bind(components.secondaryButton(""), "modui.close");
            close.setName("mods.changes.close");
            close.addActionListener(event -> dialog.dispose());
            var actions = components.transparent(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(12), UIScale.scale(12)));
            actions.add(close);
            dialog.add(scroll);
            dialog.add(actions, BorderLayout.SOUTH);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setSize(UIScale.scale(700), UIScale.scale(440));
            dialog.setLocationRelativeTo(this);
            pendingDialog = dialog;
            try {
                dialog.setVisible(true);
            } finally {
                dialog.dispose();
                if (pendingDialog == dialog) pendingDialog = null;
            }
        });
    }

    private String changeText(PendingChange change) {
        var text = new StringBuilder();
        if (change.before().enabled() != change.after().enabled()) text.append(messages.text("modui.changeEnabled",
            Map.of("before", messages.text(change.before().enabled() ? "mods.on" : "mods.off"),
                "after", messages.text(change.after().enabled() ? "mods.on" : "mods.off"))));
        if (!change.before().version().equals(change.after().version())) {
            if (!text.isEmpty()) text.append('\n');
            text.append(messages.text("modui.changeVersion", Map.of("before", change.before().version().orElse("—"),
                "after", change.after().version().orElse("—"))));
        }
        if (change.before().equals(change.after())) text.append(messages.text("modui.changePreparation"));
        if (!change.preparation().isBlank()) {
            String[] preparation = change.preparation().split(" · ", 2);
            String mode = switch (preparation[0]) {
                case "INITIALIZE" -> messages.text("modui.preparationInitialize");
                case "MIGRATE" -> messages.text("modui.preparationMigrate");
                case "REUSE" -> messages.text("modui.preparationReuse");
                default -> state(preparation[0]);
            };
            text.append('\n').append(messages.text("modui.preparationState", Map.of("state", mode
                + (preparation.length == 2 ? " · " + preparation[1] : ""))));
        }
        text.append('\n').append(messages.text("mods.affected", Map.of("owners", String.join(", ", change.affected()))));
        return text.toString();
    }

    private void withdraw(UUID request) {
        run(() -> service.reviewCancellation(request), review -> {
            if (confirm(messages.text("mods.confirmCancel") + "\n" + summary(review, Optional.empty())))
                run(() -> service.apply(review, true), ignored -> {
                    var selected = inventory.getSelectedValue();
                    if (selected != null && review.choices().containsKey(selected.owner()))
                        draft.accepted(selected.owner(), draft.session(), review.choices().get(selected.owner()));
                    completed("mods.cancelled");
                }, true);
        });
    }

    /** Explicitly validates the original unknown target without replaying migration. / 明确验证原未知目标，不重放迁移。 */
    private void reconcile() {
        var value = inventory.getSelectedValue();
        if (value == null) return;
        var operation = value.operations().stream()
            .filter(o -> o.type().equals("MIGRATE") && o.state().equals("UNKNOWN")).findFirst().orElseThrow();
        if (confirm(messages.text("mods.confirmReconcile")))
            run(() -> schemas.reconcile(operation.id()), this::outcome, true);
    }

    /** Rebuilds one reviewed failed target or only finishes its retained original cleanup. / 重建一个已审阅失败目标，或仅完成其保留原清理。 */
    private void retry() {
        var value = inventory.getSelectedValue();
        if (value == null) return;
        var original = retained.get(value.owner());
        if (original != null) {
            executeRetry(value.owner(), original);
            return;
        }
        var source = source(value, value.choice().version().orElseThrow(), true);
        if (source == null)
            return;
        run(() -> schemas.review(value.owner(), source), review -> {
            String text = messages.text("mods.confirmRetry",
                Map.of("owner", value.owner(), "version", review.manifest().version().toString(), "schema",
                    review.target().schemaVersion()))
                + source.map(s -> "\n" + messages.text("mods.confirmSource", Map.of("schema", s.schemaVersion())))
                .orElse("");
            if (confirm(text))
                executeRetry(value.owner(), review);
        });
    }

    /** Retains the original review until outcome or refreshed operation facts prove completion. / 保留原审阅，直至结果或刷新后的操作事实证明完成。
     * @param owner exact owner / 精确所有者
     * @param review confirmed original review / 已确认原审阅 */
    private void executeRetry(String owner, RetryReview review) {
        retained.put(owner, review);
        run(() -> schemas.retry(review), result -> {
            retained.remove(owner);
            outcome(result);
        }, true);
    }

    /** Displays data outcome separately from business runtime. / 分开显示数据结果和业务运行。
     * @param result verified or unknown data result / 已核实或未知数据结果 */
    private void outcome(TaskResult result) {
        completed("mods.result." + result.name());
    }

    /** Refreshes actual facts while retaining an explicit completion message. / 保留明确完成消息并刷新实际事实。
     * @param key localized result key / 本地化结果键 */
    private void completed(String key) {
        messages.bind(view.status, key);
        refresh();
    }

    /** Opens an owned plain-text confirmation invalidated by any lock transition. / 打开任意锁定变化均使其失效的从属纯文本确认。
     * @param text concrete reviewed effects / 具体已审阅影响
     * @return whether user confirmed within the same privacy epoch / 用户是否在同一隐私代次确认 */
    private boolean confirm(String text) {
        var area = new JTextArea(text, 12, 62);
        area.setName("mods.confirmation");
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return confirm(new JScrollPane(area));
    }

    /** Presents an owned review whose contents and authority end at the next lock transition. / 展示从属审阅，其内容及权限在下一锁定变化时结束。
     * @param body concrete review controls / 具体审阅控件
     * @return explicit confirmation within the original privacy epoch / 原隐私代次内的明确确认 */
    private boolean confirm(Component body) {
        long original = epoch;
        var option = new JOptionPane(body, JOptionPane.WARNING_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        confirmation = option.createDialog(this, messages.text("mods.confirm"));
        LocaleBinding.title(confirmation, LocalizedMessage.of("mods.confirm"), messages.catalog());
        try {
            confirmation.setVisible(true);
            return Objects.equals(option.getValue(), JOptionPane.OK_OPTION) && !locked && !closed && original == epoch;
        } finally {
            confirmation.dispose();
            confirmation = null;
        }
    }

    /** Runs one original operation off the EDT without queuing repeat clicks. / 在 EDT 外运行一个原操作，不排队重复点击。
     * @param <T> operation result / 操作结果
     * @param action original operation / 原操作
     * @param success current-view completion / 当前视图完成 */
    private <T> void run(Callable<T> action, Consumer<T> success) {
        run(action, success, false);
    }

    /** Refreshes retained facts after a possibly partially accepted mutation failure. / 可能部分接纳的修改失败后刷新保留事实。
     * @param <T> operation result / 操作结果
     * @param action original operation / 原操作
     * @param success current-view completion / 当前视图完成
     * @param reloadOnFailure whether a mutation may have changed durable facts / 修改是否可能改变持久事实 */
    private <T> void run(Callable<T> action, Consumer<T> success, boolean reloadOnFailure) {
        if (locked || closed || busy)
            return;
        busy = true;
        long original = epoch;
        controls();
        DesktopTaskRunner.run(action, value -> {
            busy = false;
            controls();
            if (!closed && !locked) {
                if (original == epoch)
                    success.accept(value);
                else
                    refresh();
            }
        }, failure -> {
            busy = false;
            controls();
            if (!closed && !locked) {
                LocaleBinding.unbind(view.status);
                view.status.setText(messages.safe(failure));
                if (original != epoch || reloadOnFailure)
                    refresh();
            }
        });
    }

    /** Discards the view without cancelling accepted maintenance owned by the runtime. / 丢弃视图，不取消运行时持有的已接纳维护。 */
    @Override
    public void close() {
        closed = true;
        epoch++;
        if (confirmation != null)
            confirmation.dispose();
        if (pendingDialog != null)
            pendingDialog.dispose();
        installed = List.of();
        pending = List.of();
        draft.bind(null, null);
        rows.clear();
        retained.clear();
        try {
            navigationSubscription.close();
        } catch (Exception failure) {
            LocaleBinding.unbind(view.status);
            view.status.setText(messages.safe(failure));
        }
        LocaleBinding.unbind(view.status);
        view.status.setText("");
        render();
    }

    /** One release label referring to its exact retained data. / 指向精确保留数据的一个发布标签。
     * @param version registered release / 登记发布
     * @param data original data identity / 原数据身份 */
    private record SourceChoice(String version, SchemaView data) {
    }

    /** Owns installed-program review and original cleanup continuation. / 持有已安装程序审阅及原清理继续职责。 */
    private final class InstalledMaintenance {
        /** Installed source inventory and same-release repair owner, absent in CLASS. / 安装来源清单及同版本修复所有者，CLASS 缺省。 */
        private PlatformModInstallation installation;

        /** Actual installed-layout deletion, absent for CLASS. / 实际安装布局删除，CLASS 缺省。 */
        private PlatformModDeletion deletion;

        /** Refreshes the original-package observation after accepted cleanup. / 已接纳清理后刷新原包观察。 */
        private Runnable refreshOriginals;

        /** Creates the maintenance owner without admitting an operation. / 创建维护所有者，不接纳操作。 */
        private InstalledMaintenance() {
        }

        /** Reviews actual installed bytes and data impact before explicit removal. / 明确移除前审阅实际安装字节及数据影响。 */
        private void deleteVersion() {
            reviewDeletion(selectedVersion);
        }

        /** Reviews a concrete installed release or the entire selected owner before confirmation. / 确认前审阅具体安装发布或完整已选所有者。
         * @param version selected release, null for the complete owner / 已选发布，完整所有者为空 */
        private void reviewDeletion(String version) {
            var selectedMod = inventory.getSelectedValue();
            if (selectedMod == null) return;
            String owner = selectedMod.owner();
            run(() -> deletion.review(owner, version), review -> {
                var text = new StringBuilder(review.whole()
                    ? messages.text("mods.confirmDeleteMod",
                    Map.of("owner", review.owner(), "versions", String.join(", ", review.versions()),
                        "bytes", review.bytes(), "ownedBytes", review.ownedBytes()))
                    : messages.text("mods.confirmDeleteVersion",
                    Map.of("owner", review.owner(), "version", review.version(), "bytes", review.bytes())));
                if (review.whole()) {
                    for (var schema : review.removedSchemas())
                        text.append('\n').append(
                            messages.text("mods.deleteOwnedSchema", Map.of("schema", schema.schemaVersion())));
                    text.append('\n').append(messages.text("mods.deleteOwnedReferences",
                        Map.of("logs", String.join(", ", review.removedLogCategories()), "pages",
                            String.join(", ", review.removedNavigationPages()), "removed",
                            review.removedSecrets().size(), "retained", review.retainedSecrets().size())));
                } else
                    text.append('\n')
                        .append(review.removedSchema()
                            .map(s -> messages.text("mods.deleteSchema", Map.of("schema", s.schemaVersion())))
                            .orElseGet(() -> messages.text("mods.keepSchema",
                                Map.of("versions", String.join(", ", review.retainedSchemaVersions())))));
                for (var path : review.programs())
                    text.append('\n').append(path);
                for (var path : review.ownedFiles())
                    text.append('\n').append(path);
                text.append("\n\n").append(messages.text("mods.deleteOriginalCandidates"));
                for (var path : review.originals())
                    text.append('\n').append(path);
                var area = new JTextArea(text.toString(), 14, 66);
                area.setName("mods.confirmation");
                area.setEditable(false);
                area.setLineWrap(true);
                area.setWrapStyleWord(true);
                var originals = messages.bind(new JCheckBox("", false), "mods.deleteOriginals");
                originals.setName("mods.deleteOriginals");
                originals.setEnabled(!review.originals().isEmpty());
                var panel = new JPanel(new BorderLayout(8, 8));
                panel.add(new JScrollPane(area));
                panel.add(originals, BorderLayout.SOUTH);
                if (confirm(panel)) {
                    boolean selected = originals.isSelected();
                    run(() -> deletion.delete(review, selected), result -> deleted(result.outcome(), review.whole()),
                        true);
                }
            });
        }

        /** Continues an original accepted deletion without choosing a fresh file scope. / 继续原已接纳删除，不选择新的文件范围。 */
        private void reconcileDeletion() {
            var selected = inventory.getSelectedValue();
            if (selected == null) return;
            var operation = selected.operations().stream()
                .filter(o -> InstalledMaintenance.deletionOperation(o) && o.state().equals("UNKNOWN")).findFirst()
                .orElseThrow();
            if (confirm(messages.text("mods.confirmDeletionReconcile")))
                run(() -> deletion.reconcile(operation.id()),
                    result -> deleted(result.outcome(), operation.type().equals("DELETE_MOD")), true);
        }

        /** Recognizes the two installed cleanup operations without interpreting their recovery payload. / 识别两类安装清理操作，不解释其恢复载荷。
         * @param operation retained management fact / 保留管理事实
         * @return whether the original deletion service owns continuation / 是否由原删除服务持有继续权限 */
        private static boolean deletionOperation(OperationView operation) {
            return operation.type().equals("DELETE_VERSION") || operation.type().equals("DELETE_MOD");
        }

        /** Reports actual cleanup and refreshes both durable catalog and original observations. / 报告实际清理并刷新持久目录及原包观察。
         * @param result observed deletion result / 观察删除结果
         * @param whole whether the complete owner was selected / 是否选择完整所有者 */
        private void deleted(TaskResult result, boolean whole) {
            completed(result == TaskResult.SUCCEEDED ? whole ? "mods.deletedMod" : "mods.deleted" : "mods.deletePending");
            if (whole)
                navigation.refreshPreferences();
            refreshOriginals.run();
        }

        /** Lists matching managed archives before allowing an explicit external file selection. / 允许明确选择外部文件前列出匹配受管归档。 */
        private void chooseRepair() {
            var selectedMod = inventory.getSelectedValue();
            if (selectedMod == null) return;
            String owner = selectedMod.owner(), version = selectedVersion;
            run(installation::scan, observation -> {
                var paths = observation.originals().stream()
                    .filter(o -> o.state().equals("INSTALLED") && o.packageInfo().isPresent()
                        && o.packageInfo().orElseThrow().id().equals(owner)
                        && o.packageInfo().orElseThrow().version().toString().equals(version))
                    .map(PlatformModInstallation.OriginalView::file).toList();
                Path selected = null;
                if (!paths.isEmpty()) {
                    var choices = new JComboBox<Path>();
                    choices.setName("mods.repairSources");
                    paths.forEach(choices::addItem);
                    choices.addItem(null);
                    choices.setRenderer((list, value, index, chosen, focus) -> {
                        var renderer = new DefaultListCellRenderer();
                        renderer.putClientProperty("html.disable", true);
                        return renderer.getListCellRendererComponent(list,
                            value == null ? messages.text("mods.chooseRepairFile") : value.toString(), index,
                            chosen, focus);
                    });
                    var panel = new JPanel(new BorderLayout(8, 8));
                    var notice = new JTextArea(
                        messages.text("mods.chooseRepairSource", Map.of("owner", owner, "version", version)), 3,
                        58);
                    notice.setEditable(false);
                    notice.setLineWrap(true);
                    notice.setWrapStyleWord(true);
                    panel.add(notice, BorderLayout.NORTH);
                    panel.add(choices);
                    if (!confirm(panel))
                        return;
                    selected = (Path) choices.getSelectedItem();
                }
                if (selected == null)
                    selected = chooseRepairFile();
                if (selected != null)
                    reviewRepair(owner, version, selected);
            });
        }

        /** Owns the file picker so locking also revokes a pending external source selection. / 持有文件选择器，使锁定同样撤销待定外部来源选择。
         * @return explicitly selected input or null / 明确选定输入或 null */
        private Path chooseRepairFile() {
            long original = epoch;
            var chooser = new JFileChooser();
            chooser.setName("mods.repairFile");
            chooser.setDialogType(JFileChooser.OPEN_DIALOG);
            chooser.setApproveButtonText(messages.text("mods.selectRepairFile"));
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                messages.text("mods.repairFileType"), "wtlmod"));
            Path[] selected = {null};
            var dialog = new JDialog(SwingUtilities.getWindowAncestor(PlatformModsPane.this),
                messages.text("mods.chooseRepairFile"), Dialog.ModalityType.APPLICATION_MODAL);
            LocaleBinding.title(dialog, LocalizedMessage.of("mods.chooseRepairFile"), messages.catalog());
            confirmation = dialog;
            chooser.addActionListener(event -> {
                if (JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())
                    && chooser.getSelectedFile() != null)
                    selected[0] = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
                dialog.dispose();
            });
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setContentPane(chooser);
            dialog.pack();
            dialog.setLocationRelativeTo(PlatformModsPane.this);
            try {
                dialog.setVisible(true);
                return !locked && !closed && original == epoch ? selected[0] : null;
            } finally {
                dialog.dispose();
                confirmation = null;
            }
        }

        /** Reviews exact replacement bytes and retention before showing destructive confirmation. / 展示替换确认前审阅精确字节及留存选择。
         * @param owner selected installed owner / 选定安装所有者
         * @param version selected release, independent of saved choice / 选定发布，独立于已存选择
         * @param original selected archive / 选定归档 */
        private void reviewRepair(String owner, String version, Path original) {
            run(() -> installation.reinstallation().review(owner, version, original), review -> {
                var text = new StringBuilder(messages.text("mods.confirmRepair",
                    Map.of("owner", owner, "version", version, "path", review.packageInfo().archive().toString(),
                        "retention",
                        messages.text(review.keepOriginal() ? "originals.keep" : "originals.removeAfter"))));
                text.append("\n\n").append(messages.text("mods.repairOldFiles"));
                if (review.oldFiles().isEmpty())
                    text.append('\n').append(messages.text("mods.repairNoOldFiles"));
                else
                    review.oldFiles().forEach(path -> text.append('\n').append(path));
                if (confirm(text.toString()))
                    run(() -> installation.reinstallation().reinstall(review), this::repaired, true);
            });
        }

        /** Continues the original durable replacement or already selected restoration. / 继续原持久替换或已选定恢复。 */
        private void reconcileRepair() {
            if (inventory.getSelectedValue() == null) return;
            var operation = repairOperation();
            if (confirm(messages.text("mods.confirmRepairReconcile", Map.of("id", operation.id().toString()))))
                run(() -> installation.reinstallation().reconcile(operation.id()), this::repaired, true);
        }

        /** Reviews exactly which new files will be removed before restoring the saved old program. / 恢复保留旧程序前审阅精确将移除的新文件。 */
        private void restoreProgram() {
            if (inventory.getSelectedValue() == null) return;
            var operation = repairOperation();
            run(() -> installation.reinstallation().reviewRestore(operation.id()), review -> {
                var text = new StringBuilder(messages.text("mods.confirmRestoreProgram",
                    Map.of("owner", review.owner(), "version", review.version(), "id", review.id().toString())));
                if (review.discardedFiles().isEmpty())
                    text.append('\n').append(messages.text("mods.restoreNoFiles"));
                else
                    review.discardedFiles().forEach(path -> text.append('\n').append(path));
                if (confirm(text.toString()))
                    run(() -> installation.reinstallation().restore(review), this::repaired, true);
            });
        }

        /** Selects the original unknown repair without inferring its version from the draft. / 选择原未知修复，不从当前草稿推断其版本。
         * @return original maintenance identity / 原维护身份 */
        private OperationView repairOperation() {
            return inventory.getSelectedValue().operations().stream()
                .filter(o -> o.type().equals("REINSTALL") && o.state().equals("UNKNOWN")).findFirst().orElseThrow();
        }

        /** Refreshes saved state and original inventory after an actual repair outcome. / 实际修复结果后刷新已存状态和原包清单。
         * @param result actual original repair result / 实际原修复结果 */
        private void repaired(OperationResult<PlatformModReinstallation.Receipt> result) {
            LocaleBinding.unbind(view.status);
            view.status.setText(repairMessage(messages, result));
            refresh();
            refreshOriginals.run();
        }
    }

    /** Owns action controls and their visual arrangement, preserving pane admission. / 持有动作控件及布局，保留原页面准入。 */
    private final class ActionControls {
        /** Explicit opening and pinning, independent of version selection. / 独立于版本选择的明确打开及固定操作。 */
        private final JButton openPage;

        /** Explicit management actions. / 明确管理操作。 */
        private final JButton refresh, save, prepare, cancel, reconcile, retry, logs, deleteVersion, deleteMod,
            reconcileDeletion;

        /** Explicit same-release repair and original recovery actions. / 明确同版本修复及原恢复操作。 */
        private final JButton repair, reconcileRepair, restoreProgram;

        private final JButton more, changes, saveRestart;

        /** Safe operation feedback outside the retained snapshot. / 保留快照之外的安全操作反馈。 */
        private final JLabel status = new JLabel();

        /** Visible indeterminate worker progress, never a completion claim. / 可见的不定进度，不代表完成。 */
        private final JProgressBar progress = new JProgressBar();

        /** Exact footer attached after the original content split. / 原内容分栏后挂接的精确页脚。 */
        private final JPanel footer;

        /** Builds controls on the pane's original event thread. / 在页面原事件线程构造控件。
         * @param showLogs original platform log action / 原平台日志动作 */
        private ActionControls(Runnable showLogs) {
            openPage = button("openPage", () -> {
                var page = (PageContribution) pagePicker.getSelectedItem();
                if (page != null)
                    navigation.open(page.key());
            });
            refresh = button("refresh", PlatformModsPane.this::refresh);
            save = button("save", () -> reviewChoice(false));
            messages.bind(save, "modui.saveOnly");
            prepare = button("prepare", () -> reviewChoice(true));
            cancel = button("cancel", PlatformModsPane.this::cancelRequest);
            reconcile = button("reconcile", PlatformModsPane.this::reconcile);
            retry = button("retry", PlatformModsPane.this::retry);
            logs = button("logs", showLogs);
            deleteVersion = button("deleteVersion", maintenance::deleteVersion);
            LocaleBinding.tooltip(deleteVersion, LocalizedMessage.of("mods.deleteNotice"), messages.catalog());
            deleteMod = button("deleteMod", () -> maintenance.reviewDeletion(null));
            LocaleBinding.tooltip(deleteMod, LocalizedMessage.of("mods.deleteNotice"), messages.catalog());
            reconcileDeletion = button("reconcileDeletion", maintenance::reconcileDeletion);
            repair = button("repair", maintenance::chooseRepair);
            LocaleBinding.tooltip(repair, LocalizedMessage.of("mods.repairNotice"), messages.catalog());
            reconcileRepair = button("reconcileRepair", maintenance::reconcileRepair);
            restoreProgram = button("restoreProgram", maintenance::restoreProgram);
            more = messages.bind(components.secondaryButton(""), "modui.more");
            more.setName("mods.more");
            more.addActionListener(event -> moreActions());
            changes = messages.bind(components.secondaryButton(""), "modui.changes");
            changes.setName("mods.viewChanges");
            changes.addActionListener(event -> {
                if (!locked && !closed && !busy) showChanges();
            });
            saveRestart = messages.bind(components.primaryButton(""), "modui.saveRestart");
            saveRestart.setName("mods.saveRestart");
            saveRestart.addActionListener(event -> {
                if (!locked && !closed && !busy) saveAndRestart();
            });
            footer = components.transparent(new WrappingFlowLayout(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(8), UIScale.scale(4))));
            footer.add(logs);
            footer.add(more);
            footer.add(new JComponent() {
                @Override
                public Dimension getPreferredSize() {
                    int controlsWidth = List.of(logs, more, changes, save, saveRestart).stream()
                        .mapToInt(button -> button.getPreferredSize().width).sum();
                    return new Dimension(Math.max(0, footer.getWidth() - controlsWidth - UIScale.scale(8) * 7), 1);
                }
            });
            for (var button : List.of(changes, save, saveRestart)) footer.add(button);
            status.setName("mods.status");
            status.putClientProperty("html.disable", true);
            progress.setIndeterminate(true);
            progress.setName("mods.progress");
        }

        private void moreActions() {
            if (locked || closed || busy) return;
            var menu = new JPopupMenu();
            var originals = messages.bind(new JMenuItem(), "modui.originals");
            originals.setName("mods.originals");
            originals.addActionListener(event -> {
                menu.setVisible(false);
                leaveDetails();
                showOriginals.run();
            });
            menu.add(originals);
            boolean first = true;
            for (var button : List.of(prepare, cancel, reconcile, retry, repair, deleteVersion, deleteMod,
                reconcileDeletion, reconcileRepair, restoreProgram)) {
                if (!button.isVisible()) continue;
                if (first) {
                    menu.addSeparator();
                    first = false;
                }
                var item = new JMenuItem(button.getText());
                item.setName(button.getName());
                item.setToolTipText(button.getToolTipText());
                item.setEnabled(button.isEnabled());
                item.addActionListener(event -> {
                    menu.setVisible(false);
                    button.doClick();
                });
                menu.add(item);
            }
            menu.show(more, 0, more.getHeight());
        }
    }
}
