package gold.debug.windowstolinux.app.ui.mod;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.mod.PlatformModDeletion;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents.*;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents.Choice;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.shell.ModPageRegistry.PageContribution;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.Outcome;

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
        Outcome retry(RetryReview review) throws Exception;

        /** Reconciles the original interrupted preparation without replay. / 不重放地核对原中断准备。
         * @param operation original preparation / 原准备
         * @return verified data outcome / 已核实数据结果
         * @throws Exception if evidence or cleanup remains uncertain / 证据或清理仍不确定时 */
        Outcome reconcile(UUID operation) throws Exception;
    }

    /** Actual serialized management service. / 实际串行管理服务。 */
    private final PlatformModIntents service;

    /** Actual original graph maintenance. / 实际原图维护。 */
    private final SchemaActions schemas;

    /** Localized safe feedback. / 本地化安全反馈。 */
    private final PageMessagePresenter messages;

    /** Current themed controls. / 当前主题控件。 */
    private final DesktopComponentFactory components;

    /** Shared exact page publication and persisted navigation preferences. / 共用的精确页面发布及持久导航偏好。 */
    private final ModNavigationPane navigation;

    /** Actual published pages for the selected owner. / 所选所有者的实际已发布页面。 */
    private final JComboBox<PageContribution> pagePicker = new JComboBox<>();

    /** Explicit opening and pinning, independent of version selection. / 独立于版本选择的明确打开及固定操作。 */
    private final JButton openPage, pinPage;

    /** Host subscription released with this view. / 随此视图释放的宿主订阅。 */
    private final AutoCloseable navigationSubscription;

    /** Installed identities without mutable authority. / 不含修改权限的已安装身份。 */
    private final DefaultListModel<ModView> rows = new DefaultListModel<>();

    /** Single selected Mod, independent of its version choice. / 独立于版本选择的单选 Mod。 */
    private final JList<ModView> inventory = new JList<>(rows);

    /** Draft whole-Mod switch reviewed before saving. / 保存前需审阅的整个 Mod 开关草稿。 */
    private final JCheckBox enabled = new JCheckBox();

    /** Actual retained facts, never inferred from the draft switch. / 不从草稿开关推断的实际保留事实。 */
    private final JTextArea details = new JTextArea();

    /** One radio group of installed releases. / 已安装发布的单一单选组。 */
    private final JPanel versions = new JPanel();

    /** Explicit management actions. / 明确管理操作。 */
    private final JButton refresh, save, cancel, reconcile, retry, logs, deleteVersion, reconcileDeletion;

    /** Actual installed-layout deletion, absent for CLASS. / 实际安装布局删除，CLASS 缺省。 */
    private PlatformModDeletion deletion;

    /** Refreshes the original-package observation after version cleanup. / 版本清理后刷新原包观察。 */
    private Runnable refreshOriginals;

    /** Safe operation feedback outside the retained snapshot. / 保留快照之外的安全操作反馈。 */
    private final JLabel status = new JLabel();

    /** Visible indeterminate worker progress, never a completion claim. / 可见的不定进度，不代表完成。 */
    private final JProgressBar progress = new JProgressBar();

    /** Original retry handles retained only for unfinished physical cleanup. / 仅为未完成物理清理保留的原重试句柄。 */
    private final Map<String, RetryReview> retained = new HashMap<>();

    /** Draft selected version, which does not change persistent choice. / 不改变持久选择的草稿单选版本。 */
    private String selectedVersion;

    /** Current privacy, lifetime and worker exclusion. / 当前隐私、寿命及工作线程互斥。 */
    private boolean locked = true, closed, busy;

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
    public PlatformModsPane(PlatformModIntents service, SchemaActions schemas, DesktopComponentFactory components,
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
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        inventory.setName("mods.inventory");
        inventory.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        inventory.setCellRenderer((list, value, index, selected, focus) -> {
            var label = new DefaultListCellRenderer();
            label.putClientProperty("html.disable", true);
            String name = value.versions().isEmpty() ? value.owner() : value.versions().getFirst().manifest().name();
            return label.getListCellRendererComponent(list, name + " · " + value.owner(), index, selected, focus);
        });
        inventory.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting())
                render();
        });
        enabled.setName("mods.enabled");
        enabled.setText(messages.text("mods.enabled"));
        enabled.addActionListener(event -> controls());
        versions.setLayout(new BoxLayout(versions, BoxLayout.Y_AXIS));
        details.setName("mods.details");
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setRows(9);
        var right = new JPanel(new BorderLayout(8, 8));
        right.add(enabled, BorderLayout.NORTH);
        var facts = new JPanel(new BorderLayout(8, 8));
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
        openPage = button("openPage", () -> {
            var page = (PageContribution) pagePicker.getSelectedItem();
            if (page != null)
                navigation.open(page.key());
        });
        pinPage = button("pinPage", () -> {
            var page = (PageContribution) pagePicker.getSelectedItem();
            if (page != null)
                navigation.pin(page.key(), !navigation.pinned(page.key()));
        });
        var pageActions = new JPanel(new BorderLayout(6, 0));
        pageActions.add(pagePicker);
        var pageButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        pageButtons.add(openPage);
        pageButtons.add(pinPage);
        pageActions.add(pageButtons, BorderLayout.EAST);
        facts.add(pageActions, BorderLayout.NORTH);
        facts.add(new JScrollPane(details), BorderLayout.CENTER);
        var versionScroll = new JScrollPane(versions);
        versionScroll.setBorder(BorderFactory.createTitledBorder(messages.text("mods.versions")));
        versionScroll.setPreferredSize(new Dimension(420, 150));
        facts.add(versionScroll, BorderLayout.SOUTH);
        right.add(facts, BorderLayout.CENTER);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(inventory), right);
        split.setResizeWeight(0.28);
        split.setDividerLocation(270);
        add(split);
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        refresh = button("refresh", this::refresh);
        save = button("save", this::reviewChoice);
        cancel = button("cancel", this::cancelRequest);
        reconcile = button("reconcile", this::reconcile);
        retry = button("retry", this::retry);
        logs = button("logs", showLogs);
        deleteVersion = button("deleteVersion", this::deleteVersion);
        deleteVersion.setToolTipText(messages.text("mods.deleteNotice"));
        reconcileDeletion = button("reconcileDeletion", this::reconcileDeletion);
        for (var button : List.of(refresh, save, cancel, reconcile, retry, deleteVersion, reconcileDeletion, logs))
            actions.add(button);
        var footer = new JPanel(new BorderLayout(8, 8));
        footer.add(actions);
        status.setName("mods.status");
        footer.add(status, BorderLayout.SOUTH);
        progress.setIndeterminate(true);
        progress.setName("mods.progress");
        footer.add(progress, BorderLayout.NORTH);
        add(footer, BorderLayout.SOUTH);
        navigationSubscription = navigation.onChange(this::renderPages);
        controls();
    }

    /** Adds installed-layout maintenance after actual installation services are composed. / 实际安装服务装配后加入安装布局维护。
     * @param deletion original installed deletion owner / 原安装删除所有者
     * @param refreshOriginals original observation refresh / 原包观察刷新 */
    public void installDeletion(PlatformModDeletion deletion, Runnable refreshOriginals) {
        if (!SwingUtilities.isEventDispatchThread() || this.deletion != null)
            throw new IllegalStateException("Installed deletion must be composed once on the EDT");
        this.deletion = Objects.requireNonNull(deletion);
        this.refreshOriginals = Objects.requireNonNull(refreshOriginals);
        controls();
    }

    /** Creates one named action under the current view boundary. / 在当前视图边界下创建一个命名操作。
     * @param key action key / 操作键
     * @param action explicit user action / 明确用户操作
     * @return themed named button / 主题命名按钮 */
    private JButton button(String key, Runnable action) {
        var button = components.secondaryButton(messages.text("mods." + key));
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
        rows.clear();
        status.setText("");
        render();
        if (!locked)
            refresh();
    }

    /** Reloads durable facts in the background, preserving only the selected owner. / 后台重读持久事实，仅保留所选所有者。 */
    public void refresh() {
        var current = inventory.getSelectedValue();
        String owner = current == null ? null : current.owner();
        run(service::list, values -> {
            rows.clear();
            for (var value : values) {
                if (value.operations().stream()
                        .noneMatch(o -> o.type().equals("MIGRATE") && o.state().equals("RUNNING")))
                    retained.remove(value.owner());
                rows.addElement(value);
            }
            for (int i = 0; i < rows.size(); i++)
                if (rows.get(i).owner().equals(owner))
                    inventory.setSelectedIndex(i);
            if (inventory.getSelectedIndex() < 0 && !rows.isEmpty())
                inventory.setSelectedIndex(0);
            if (rows.isEmpty())
                status.setText(messages.text("mods.empty"));
            render();
        });
    }

    /** Displays saved intention, actual state and all retained data separately. / 分开展示已存意图、实际状态及全部保留数据。 */
    private void render() {
        var value = inventory.getSelectedValue();
        versions.removeAll();
        details.setText("");
        enabled.setSelected(value != null && value.choice().enabled());
        selectedVersion = value == null ? null : value.choice().version().orElse(null);
        if (value != null) {
            var text = new StringBuilder(messages.text("mods.saved",
                    Map.of("choice", messages.text(value.choice().enabled() ? "mods.on" : "mods.off"), "version",
                            value.choice().version().orElse("—"))));
            text.append('\n').append(messages.text("mods.actual",
                    Map.of("state", state(value.runtime()), "version", value.actual().orElse("—"))));
            value.failure().ifPresent(problem -> text.append('\n').append(messages.safe(problem)));
            for (var data : value.data())
                text.append('\n').append("Schema ").append(data.identity().schemaVersion()).append(" · ")
                        .append(state(data.state()));
            for (var operation : value.operations())
                text.append('\n')
                        .append(messages.text(operation.request().isPresent() ? "mods.pending" : "mods.operation",
                                Map.of("type", state(operation.type()), "state", state(operation.state()), "phase",
                                        state(operation.phase()))));
            details.setText(text.toString());
            details.setCaretPosition(0);
            var group = new ButtonGroup();
            for (var release : value.versions()) {
                var manifest = release.manifest();
                var radio = new JRadioButton(manifest.version() + " · "
                        + manifest.schemaVersion().map(s -> "Schema " + s).orElse(messages.text("mods.noData")) + " · "
                        + messages.text("mods.programState", Map.of("state", state(release.state()))));
                radio.setName("mods.version." + manifest.version());
                radio.putClientProperty("html.disable", true);
                radio.setSelected(manifest.version().toString().equals(selectedVersion));
                radio.addActionListener(event -> {
                    selectedVersion = manifest.version().toString();
                    controls();
                });
                group.add(radio);
                versions.add(radio);
                var data = value.data().stream()
                        .filter(d -> manifest.schemaVersion().equals(Optional.of(d.identity().schemaVersion())))
                        .findFirst();
                if (data.isPresent() && data.get().state().equals("FAILED")) {
                    radio.setBorder(BorderFactory.createLineBorder(new Color(178, 45, 45)));
                    radio.setBorderPainted(true);
                    radio.setText(radio.getText() + " · " + messages.text("mods.dataFailed"));
                }
            }
        }
        versions.revalidate();
        versions.repaint();
        renderPages();
        controls();
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
        openPage.setEnabled(active);
        pinPage.setEnabled(active);
        pinPage.setText(
                messages.text(page != null && navigation.pinned(page.key()) ? "mods.unpinPage" : "mods.pinPage"));
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
        progress.setVisible(busy && !locked && !closed);
        var value = inventory.getSelectedValue();
        inventory.setEnabled(active);
        enabled.setEnabled(active && value != null);
        refresh.setEnabled(active);
        logs.setEnabled(active);
        save.setEnabled(active && value != null && (!enabled.isSelected() || selectedVersion != null)
                && (enabled.isSelected() != value.choice().enabled()
                        || !Optional.ofNullable(selectedVersion).equals(value.choice().version())));
        cancel.setVisible(value != null && value.operations().stream().anyMatch(o -> o.request().isPresent()));
        cancel.setEnabled(active);
        reconcile.setVisible(value != null && value.operations().stream()
                .anyMatch(o -> o.type().equals("MIGRATE") && o.state().equals("UNKNOWN")));
        reconcile.setEnabled(active);
        boolean finishing = value != null && retained.containsKey(value.owner());
        retry.setText(messages.text(finishing ? "mods.finish" : "mods.retry"));
        retry.setVisible(finishing || value != null && value.choice().enabled() && value.versions().stream()
                .anyMatch(v -> value.choice().version().equals(Optional.of(v.manifest().version().toString()))
                        && value.data().stream().anyMatch(d -> d.retryable()
                                && v.manifest().schemaVersion().equals(Optional.of(d.identity().schemaVersion())))));
        retry.setEnabled(active);
        deleteVersion.setVisible(deletion != null);
        deleteVersion.setEnabled(active && deletion != null && value != null && !value.choice().enabled()
                && !enabled.isSelected() && selectedVersion != null && value.operations().isEmpty());
        reconcileDeletion.setVisible(deletion != null && value != null
                && value.operations().stream().anyMatch(o -> o.type().equals("DELETE_VERSION")));
        reconcileDeletion.setEnabled(active && value != null && value.operations().stream()
                .anyMatch(o -> o.type().equals("DELETE_VERSION") && o.state().equals("UNKNOWN")));
        for (var child : versions.getComponents())
            child.setEnabled(active);
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
        panel.add(new JLabel(messages.text("mods.sourceNotice")), BorderLayout.NORTH);
        panel.add(select);
        long original = epoch;
        if (JOptionPane.showConfirmDialog(this, panel, messages.text("mods.sourceTitle"),
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION || locked || closed || epoch != original)
            return null;
        var chosen = (SourceChoice) select.getSelectedItem();
        return chosen == null ? Optional.empty() : Optional.of(chosen.data().identity());
    }

    /** Reviews actual installed bytes and data impact before explicit removal. / 明确移除前审阅实际安装字节及数据影响。 */
    private void deleteVersion() {
        String owner = inventory.getSelectedValue().owner(), version = selectedVersion;
        run(() -> deletion.review(owner, version), review -> {
            var text = new StringBuilder(messages.text("mods.confirmDeleteVersion",
                    Map.of("owner", review.owner(), "version", review.version(), "bytes", review.bytes())));
            text.append('\n')
                    .append(review.removedSchema()
                            .map(s -> messages.text("mods.deleteSchema", Map.of("schema", s.schemaVersion())))
                            .orElseGet(() -> messages.text("mods.keepSchema",
                                    Map.of("versions", String.join(", ", review.retainedSchemaVersions())))));
            for (var path : review.programs())
                text.append('\n').append(path);
            text.append("\n\n").append(messages.text("mods.deleteOriginalCandidates"));
            for (var path : review.originals())
                text.append('\n').append(path);
            var area = new JTextArea(text.toString(), 14, 66);
            area.setName("mods.confirmation");
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            var originals = new JCheckBox(messages.text("mods.deleteOriginals"), false);
            originals.setName("mods.deleteOriginals");
            originals.setEnabled(!review.originals().isEmpty());
            var panel = new JPanel(new BorderLayout(8, 8));
            panel.add(new JScrollPane(area));
            panel.add(originals, BorderLayout.SOUTH);
            if (confirm(panel)) {
                boolean selected = originals.isSelected();
                run(() -> deletion.delete(review, selected), result -> deleted(result.outcome()), true);
            }
        });
    }

    /** Continues an original accepted deletion without choosing a fresh file scope. / 继续原已接纳删除，不选择新的文件范围。 */
    private void reconcileDeletion() {
        var operation = inventory.getSelectedValue().operations().stream()
                .filter(o -> o.type().equals("DELETE_VERSION") && o.state().equals("UNKNOWN")).findFirst()
                .orElseThrow();
        if (confirm(messages.text("mods.confirmDeletionReconcile")))
            run(() -> deletion.reconcile(operation.id()), result -> deleted(result.outcome()), true);
    }

    /** Reports actual cleanup and refreshes both durable catalog and original observations. / 报告实际清理并刷新持久目录及原包观察。
     * @param result observed deletion result / 观察删除结果 */
    private void deleted(Outcome result) {
        completed(result == Outcome.SUCCEEDED ? "mods.deleted" : "mods.deletePending");
        refreshOriginals.run();
    }

    /** Reviews a draft without changing persistent choice before confirmation. / 在确认前不改变持久选择地审阅草稿。 */
    private void reviewChoice() {
        var value = inventory.getSelectedValue();
        var target = new Choice(enabled.isSelected(), Optional.ofNullable(selectedVersion));
        var source = target.enabled() ? source(value, selectedVersion, false) : Optional.<SchemaIdentity>empty();
        if (source == null)
            return;
        run(() -> service.review(value.owner(), target, source), review -> {
            if (confirm(summary(review, source)))
                run(() -> service.apply(review, true), ignored -> completed("mods.savedRestart"), true);
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
        var request = inventory.getSelectedValue().operations().stream().flatMap(o -> o.request().stream()).findFirst()
                .orElseThrow();
        run(() -> service.reviewCancellation(request), review -> {
            if (confirm(messages.text("mods.confirmCancel") + "\n" + summary(review, Optional.empty())))
                run(() -> service.apply(review, true), ignored -> completed("mods.cancelled"), true);
        });
    }

    /** Explicitly validates the original unknown target without replaying migration. / 明确验证原未知目标，不重放迁移。 */
    private void reconcile() {
        var operation = inventory.getSelectedValue().operations().stream()
                .filter(o -> o.type().equals("MIGRATE") && o.state().equals("UNKNOWN")).findFirst().orElseThrow();
        if (confirm(messages.text("mods.confirmReconcile")))
            run(() -> schemas.reconcile(operation.id()), this::outcome, true);
    }

    /** Rebuilds one reviewed failed target or only finishes its retained original cleanup. / 重建一个已审阅失败目标，或仅完成其保留原清理。 */
    private void retry() {
        var value = inventory.getSelectedValue();
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
    private void outcome(Outcome result) {
        completed("mods.result." + result.name());
    }

    /** Refreshes actual facts while retaining an explicit completion message. / 保留明确完成消息并刷新实际事实。
     * @param key localized result key / 本地化结果键 */
    private void completed(String key) {
        status.setText(messages.text(key));
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
        DesktopTaskExecutor.run(action, value -> {
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
                status.setText(messages.safe(failure));
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
        rows.clear();
        retained.clear();
        try {
            navigationSubscription.close();
        } catch (Exception failure) {
            status.setText(messages.safe(failure));
        }
        status.setText("");
        render();
    }

    /** One release label referring to its exact retained data. / 指向精确保留数据的一个发布标签。
     * @param version registered release / 登记发布
     * @param data original data identity / 原数据身份 */
    private record SourceChoice(String version, DataView data) {
    }
}
