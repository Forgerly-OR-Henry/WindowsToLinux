package gold.debug.windowstolinux.app.ui.mod;

import java.awt.BorderLayout;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;

import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreCatalog;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreGateway;
import gold.debug.windowstolinux.app.service.mod.store.ModStorePlan;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.OptionalKey;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.ReleaseKey;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;

/** Edits user version pins and optional edges while presenting original service plans. / 编辑用户固定版本及可选边，同时展示原服务计划。 */
final class ModStoreSelectionPane extends JPanel {
    /** Current message support. / 当前消息支持。 */
    private final PageMessagePresenter messages;

    /** Explicit immutable draft change callback. / 明确不可变草稿变化回调。 */
    private final Consumer<ModStoreSelection> changed;

    /** Whether this surface permits changing the reviewed plan. / 此界面是否允许改变审阅计划。 */
    private final boolean editable;

    /** Original owner facts model. / 原所有者事实模型。 */
    private final ModStorePlanModel model;

    /** Stable owner selection. / 稳定所有者选择。 */
    private final JTable table;

    /** Exact catalog versions for the selected owner. / 所选所有者的精确目录版本。 */
    private final JComboBox<String> versions = new JComboBox<>();

    /** Exact root version, independent from the inspected dependency. / 独立于所查看依赖的精确主版本。 */
    private final JComboBox<String> rootVersions = new JComboBox<>();

    private final JTextArea rootDetails = ModStoreSections.text("store.rootDetails", 3);

    private final JTextArea source = ModStoreSections.text("store.sourceDetails", 4);

    private final JLabel rootInstallation = new JLabel();

    private final JComponent conflictBlock;

    /** Explicit removal of a dependency version pin. / 明确移除依赖固定版本。 */
    private final JButton unpin = new JButton();

    /** Plain original manifest details. / 原清单纯文本详情。 */
    private final JTextArea details = new JTextArea(5, 24);

    /** Actual saved installation switch and exact selected-version status. / 实际已存安装开关及精确所选版本状态。 */
    private final JLabel installation = new JLabel();

    /** Plain original conflict evidence. / 原冲突纯文本依据。 */
    private final JTextArea conflicts = new JTextArea(4, 24);

    /** Exact required and optional consumer edges. / 精确必需及可选消费方边。 */
    private final JPanel relations = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));

    /** Actual full catalog. / 实际完整目录。 */
    private ModStoreCatalog catalog;

    /** Current actual plan. / 当前实际计划。 */
    private ModStorePlan plan;

    /** Current immutable user draft, independent of pending workers. / 独立于待处理工作线程的当前不可变用户草稿。 */
    private ModStoreSelection selection;

    /** Exact declarations observed for this same catalog and root, never a failed-branch merge. / 此相同目录及主项已观察的精确声明，绝不合并失败分支。 */
    private List<ModStorePlan.Relation> observed = List.of();

    /** View availability and programmatic control-update guard. / 视图可用状态及程序控件更新保护。 */
    private boolean active, updating;

    /** Creates stable controls without resolving or downloading. / 创建稳定控件，不解析或下载。
     * @param messages current messages / 当前消息
     * @param editable whether draft edits are permitted / 是否允许草稿编辑
     * @param changed immutable draft callback / 不可变草稿回调 */
    ModStoreSelectionPane(DesktopPartFactory components, PageMessagePresenter messages, boolean editable, Consumer<ModStoreSelection> changed) {
        super(new BorderLayout());
        this.messages = messages;
        this.editable = editable;
        this.changed = changed;
        model = new ModStorePlanModel(messages);
        table = new JTable(model);
        table.setName(editable ? "store.plan" : "store.confirm.plan");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        var renderer = new javax.swing.table.DefaultTableCellRenderer();
        renderer.putClientProperty("html.disable", true);
        table.setDefaultRenderer(Object.class, renderer);
        table.getSelectionModel().addListSelectionListener(event -> selected());
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setPreferredScrollableViewportSize(new java.awt.Dimension(480, table.getRowHeight() * 5));
        var body = new JPanel(new BorderLayout(4, 8));
        body.setOpaque(false);
        body.add(new JScrollPane(table));
        var versionBar = new JPanel(new BorderLayout(4, 4));
        versionBar.add(messages.bind(new JLabel(), "store.chooseVersion"), BorderLayout.WEST);
        versions.setName("store.versions");
        versions.addActionListener(event -> pin());
        versionBar.add(versions);
        messages.bind(unpin, "store.unpin");
        unpin.setName("store.unpin");
        unpin.addActionListener(event -> removePin());
        versionBar.add(unpin, BorderLayout.EAST);
        versionBar.setOpaque(false);
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setName(editable ? "store.details" : "store.confirm.details");
        conflicts.setEditable(false);
        conflicts.setLineWrap(true);
        conflicts.setWrapStyleWord(true);
        conflicts.setName(editable ? "store.conflicts" : "store.confirm.conflicts");
        details.setOpaque(false);
        conflicts.setOpaque(false);
        var content = ModStoreSections.column();
        var info = new JPanel(new BorderLayout(0, 10));
        info.setOpaque(false);
        var rootVersion = new JPanel(new BorderLayout(12, 0));
        rootVersion.setOpaque(false);
        rootVersion.add(messages.bind(new JLabel(), "modui.store.version"), BorderLayout.WEST);
        rootVersions.setName(editable ? "store.rootVersions" : "store.confirm.rootVersions");
        rootVersions.addActionListener(event -> rootVersion());
        rootVersion.add(rootVersions);
        info.add(rootDetails, BorderLayout.NORTH);
        if (editable) info.add(rootVersion);
        info.add(rootInstallation, BorderLayout.SOUTH);
        ModStoreSections.add(content, ModStoreSections.block(components, messages, "modui.store.info", info));
        var sourcePanel = new JPanel(new BorderLayout(0, 8));
        sourcePanel.setOpaque(false);
        var expandSource = messages.bind(new JToggleButton(), "store.detailsTitle");
        expandSource.setName(editable ? "store.sourceToggle" : "store.confirm.sourceToggle");
        source.setVisible(false);
        expandSource.addActionListener(event -> {
            source.setVisible(expandSource.isSelected());
            revalidate();
        });
        sourcePanel.add(expandSource, BorderLayout.NORTH);
        sourcePanel.add(source);
        ModStoreSections.add(content, ModStoreSections.block(components, messages, "modui.store.source", sourcePanel));
        relations.setOpaque(false);
        ModStoreSections.add(content, ModStoreSections.block(components, messages, "modui.store.dependencies", relations));
        ModStoreSections.add(content, ModStoreSections.block(components, messages, "modui.store.acquisition", body));
        var metadata = new JPanel(new BorderLayout(4, 4));
        metadata.setOpaque(false);
        metadata.add(installation, BorderLayout.NORTH);
        metadata.add(details);
        if (editable) metadata.add(versionBar, BorderLayout.SOUTH);
        ModStoreSections.add(content, ModStoreSections.block(components, messages, "modui.store.selected", metadata));
        conflictBlock = ModStoreSections.block(components, messages, "store.conflictsTitle", conflicts);
        ModStoreSections.add(content, conflictBlock);
        add(content);
        setOpaque(false);
        interactive(false);
    }

    /** Displays one actual immutable plan while keeping the selected owner when possible. / 展示实际不可变计划，并尽可能保留所选所有者。
     * @param catalog original full catalog / 原完整目录
     * @param plan original plan / 原计划 */
    void display(ModStoreCatalog catalog, ModStorePlan plan) {
        String previous = selectedOwner();
        if (catalog == null || this.catalog == null || catalog.revision() != this.catalog.revision() || plan == null
            || this.plan == null || !plan.selection().root().equals(this.plan.selection().root())) observed = List.of();
        if (plan != null && plan.valid()) observed = plan.relations();
        this.catalog = catalog;
        this.plan = plan;
        selection = plan == null ? null : plan.selection();
        updating = true;
        try {
            model.display(catalog, plan);
            rootVersions.removeAllItems();
            rootDetails.setText("");
            source.setText("");
            gold.debug.windowstolinux.app.ui.language.LocaleBinding.unbind(rootInstallation);
            rootInstallation.setText("");
            relations.removeAll();
            conflicts.setText("");
            if (plan != null) {
                rootInfo();
                var editableRelations = editorRelations();
                editableRelations.forEach(this::relation);
                selection.optionals().stream().filter(key -> editableRelations.stream().noneMatch(value -> value.optional() && value.key().equals(key)))
                    .forEach(this::pending);
                var text = new StringBuilder();
                for (var conflict : plan.conflicts()) {
                    text.append(conflict.code()).append("  ").append(conflict.owner()).append("  ").append(conflict.detail()).append('\n');
                    conflict.constraints().forEach(constraint -> text.append(constraint.consumer()).append(" → ")
                        .append(constraint.dependency()).append("  ").append(constraint.range()).append('\n'));
                }
                catalog.installedIssues().forEach(issue -> text.append(issue.key().owner()).append('@').append(issue.key().version())
                    .append("  ").append(messages.safe(issue.problem())).append('\n'));
                conflicts.setText(text.toString());
                int row = 0;
                for (int index = 0; index < model.getRowCount(); index++)
                    if (model.owner(index).equals(previous)) row = index;
                if (model.getRowCount() > 0) table.setRowSelectionInterval(row, row);
            }
            conflictBlock.setVisible(!conflicts.getText().isEmpty());
        } finally {
            updating = false;
        }
        selected();
        interactive(active);
        relations.revalidate();
        relations.repaint();
    }

    /** Displays exact root metadata and versions without inspecting another owner. / 不切换查看对象地展示精确主项元数据及版本。 */
    private void rootInfo() {
        String owner = selection.root().owner();
        var candidates = new TreeSet<SemanticVersion>(Comparator.reverseOrder());
        catalog.releases().stream().filter(release -> release.owner().equals(owner)).forEach(release -> candidates.add(release.manifest().version()));
        candidates.forEach(version -> rootVersions.addItem(version.toString()));
        rootVersions.setSelectedItem(selection.root().version());
        var text = new StringBuilder();
        model.manifest(owner).ifPresent(manifest -> text.append(manifest.description().orElse(""))
            .append("\n").append(messages.text("modui.store.api")).append("  ").append(manifest.platformApiRange())
            .append("    ").append(messages.text("modui.store.java")).append(" ≥ ").append(manifest.minJavaVersion()));
        rootDetails.setText(text.toString());
        catalog.releases().stream().filter(release -> release.owner().equals(owner) && release.version().equals(selection.root().version()))
            .findFirst().ifPresent(release -> source.setText(catalog.source() + "\n" + release.packageUri() + "\nSHA-256 " + release.sha256()));
        String state = catalog.installed().stream().filter(value -> value.manifest().id().equals(owner)
                && value.manifest().version().toString().equals(selection.root().version())).findFirst()
            .map(value -> "store.installed." + (value.enabled() ? "enabled" : "disabled") + "." + (value.selected() ? "selected" : "unselected"))
            .orElse("store.notInstalled");
        messages.bind(rootInstallation, state);
    }

    private void rootVersion() {
        if (updating || !editable || !active || selection == null || rootVersions.getSelectedItem() == null) return;
        var root = new ReleaseKey(selection.root().owner(), rootVersions.getSelectedItem().toString());
        if (root.equals(selection.root())) return;
        selection = new ModStoreSelection(root, selection.optionals(), selection.pins());
        changed.accept(selection);
    }

    /** Shows actual service relations, or exact declarations solely for editing an unresolved draft. / 展示实际服务关系，或仅供编辑未解析草稿的精确声明。
     * @return actual declaration rows without assigning unresolved providers / 不给未解析提供方分配版本的实际声明行 */
    private List<ModStorePlan.Relation> editorRelations() {
        if (!plan.relations().isEmpty()) return plan.relations();
        var consumers = new LinkedHashSet<String>();
        consumers.add(selection.root().owner());
        selection.optionals().forEach(key -> consumers.add(key.consumer()));
        var declarations = new ArrayList<ModStorePlan.Relation>();
        for (String consumer : consumers) {
            String version = consumer.equals(selection.root().owner()) ? selection.root().version() : selection.pins().get(consumer);
            if (version == null) version = observed.stream().filter(value -> value.key().consumer().equals(consumer))
                .map(ModStorePlan.Relation::consumerVersion).findFirst().orElse("");
            String exact = version;
            var release = catalog.releases().stream().filter(value -> value.owner().equals(consumer) && value.version().equals(exact)).findFirst();
            var manifest = release.map(ModStoreGateway.Release::manifest).or(() -> catalog.installed().stream()
                .filter(value -> value.manifest().id().equals(consumer) && value.manifest().version().toString().equals(exact))
                .map(ModStoreCatalog.Installed::manifest).findFirst());
            manifest.ifPresent(value -> value.dependencies().forEach(dependency -> {
                var key = new OptionalKey(consumer, dependency.id());
                declarations.add(new ModStorePlan.Relation(key, exact, dependency.versionRange().toString(), dependency.optional(),
                    !dependency.optional() || selection.optionals().contains(key), release.map(source -> source.purposes().getOrDefault(dependency.id(), ""))
                    .orElse(""), Optional.empty()));
            }));
        }
        return List.copyOf(declarations);
    }

    /** Keeps an invalidated selected relationship explicitly removable. / 保持已失效的已选关系可明确撤销。
     * @param key actual retained draft edge / 实际保留的草稿边 */
    private void pending(OptionalKey key) {
        var box = new JCheckBox(key.consumer() + " → " + key.dependency() + " · " + messages.text("store.edgePending"), true);
        box.putClientProperty("html.disable", true);
        box.putClientProperty("store.optional", true);
        box.setName("store.optional." + key.consumer() + "." + key.dependency());
        box.addActionListener(event -> toggle(key, box.isSelected()));
        var row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        box.setOpaque(false);
        row.add(box);
        relations.add(row);
    }

    /** Edits only explicit optional intent before the next authoritative worker plan. / 仅编辑明确可选意图，再请求下次权威工作线程计划。
     * @param key actual draft edge / 实际草稿边
     * @param included whether the user selected this edge / 用户是否选中此边 */
    private void toggle(OptionalKey key, boolean included) {
        if (updating || !active || !editable || selection == null) return;
        var selected = new HashSet<>(selection.optionals());
        if (included) selected.add(key);
        else selected.remove(key);
        selection = new ModStoreSelection(selection.root(), selected, selection.pins());
        changed.accept(selection);
    }

    /** Mounts an actual dependency relationship and its author-supplied purpose. / 挂载实际依赖关系及作者声明用途。
     * @param relation exact service relationship / 精确服务关系 */
    private void relation(ModStorePlan.Relation relation) {
        String label = relation.key().consumer() + "@" + relation.consumerVersion() + " → " + relation.key().dependency()
            + "  " + relation.range() + (plan.valid() ? "" : " · " + messages.text("store.declarationOnly"));
        String title = model.manifest(relation.key().dependency()).map(value -> value.name())
            .orElseGet(() -> catalog.releases().stream().filter(value -> value.owner().equals(relation.key().dependency()))
                .findFirst().map(ModStoreGateway.Release::title).orElse(relation.key().dependency()));
        var box = new JCheckBox(title + " · " + messages.text(relation.optional() ? "store.optional" : "store.required"), relation.selected());
        box.putClientProperty("html.disable", true);
        box.putClientProperty("store.optional", relation.optional());
        box.setName((relation.optional() ? "store.optional." : "store.required.") + relation.key().consumer() + "." + relation.key().dependency());
        box.setToolTipText(label);
        box.addActionListener(event -> {
            if (relation.optional()) toggle(relation.key(), box.isSelected());
        });
        var row = new JPanel(new BorderLayout(4, 4));
        row.setOpaque(false);
        box.setOpaque(false);
        row.add(new JLabel(gold.debug.windowstolinux.app.ui.control.DesktopIcons.icon("mod/puzzle", 20,
            () -> UIManager.getColor("Component.focusColor"))), BorderLayout.WEST);
        var labels = new JPanel(new BorderLayout(4, 4));
        labels.setOpaque(false);
        labels.add(box, BorderLayout.NORTH);
        var constraint = new JLabel(label);
        constraint.putClientProperty("html.disable", true);
        constraint.setToolTipText(label);
        constraint.setForeground(UIManager.getColor("Label.disabledForeground"));
        labels.add(constraint);
        var purpose = new JLabel();
        purpose.putClientProperty("html.disable", true);
        if (relation.purpose().isEmpty()) messages.bind(purpose, "store.purposeMissing");
        else purpose.setText(relation.purpose());
        labels.add(purpose, BorderLayout.SOUTH);
        row.add(labels);
        relations.add(row);
    }

    /** Returns the actual owner selected in the facts table. / 返回事实表格实际所选所有者。
     * @return owner or empty when no selection exists / 所有者，无选择时为空 */
    private String selectedOwner() {
        int row = table.getSelectedRow();
        return row < 0 || row >= model.getRowCount() ? "" : model.owner(table.convertRowIndexToModel(row));
    }

    /** Refreshes exact version candidates and original manifest details. / 刷新精确版本候选及原清单详情。 */
    private void selected() {
        if (updating) return;
        String owner = selectedOwner();
        updating = true;
        try {
            versions.removeAllItems();
            details.setText("");
            gold.debug.windowstolinux.app.ui.language.LocaleBinding.unbind(installation);
            installation.setText("");
            if (plan == null || owner.isEmpty()) return;
            var candidates = new TreeSet<SemanticVersion>(Comparator.reverseOrder());
            catalog.releases().stream().filter(release -> release.owner().equals(owner))
                .forEach(release -> candidates.add(release.manifest().version()));
            catalog.installed().stream().filter(value -> value.manifest().id().equals(owner))
                .forEach(value -> candidates.add(value.manifest().version()));
            candidates.forEach(version -> versions.addItem(version.toString()));
            versions.setSelectedIndex(-1);
            if (candidates.stream().anyMatch(version -> version.toString().equals(model.version(owner))))
                versions.setSelectedItem(model.version(owner));
            var text = new StringBuilder(owner).append("  ").append(model.version(owner)).append('\n');
            model.manifest(owner).ifPresent(manifest -> text.append(manifest.name()).append('\n')
                .append(manifest.description().orElse("")).append("\nAPI ").append(manifest.platformApiRange())
                .append("\nJava ≥ ").append(manifest.minJavaVersion()).append('\n'));
            catalog.releases().stream().filter(release -> release.owner().equals(owner) && release.version().equals(model.version(owner)))
                .findFirst().ifPresent(release -> text.append(catalog.source()).append('\n').append(release.packageUri())
                    .append("\nSHA-256 ").append(release.sha256()).append('\n'));
            catalog.installed().stream().filter(value -> value.manifest().id().equals(owner)
                && value.manifest().version().toString().equals(model.version(owner))).findFirst().ifPresent(value ->
                messages.bind(installation, "store.installed." + (value.enabled() ? "enabled" : "disabled") + "." + (value.selected() ? "selected" : "unselected")));
            details.setText(text.toString());
            details.setCaretPosition(0);
        } finally {
            updating = false;
            interactive(active);
        }
    }

    /** Pins the explicitly chosen exact version and lets the service recompute everything. / 固定明确选择的精确版本，并由服务重算全部内容。 */
    private void pin() {
        if (updating || !editable || !active || selection == null || versions.getSelectedItem() == null) return;
        String owner = selectedOwner(), version = (String) versions.getSelectedItem();
        if (owner.isEmpty()) return;
        var pins = new HashMap<>(selection.pins());
        var root = selection.root();
        if (root.owner().equals(owner)) root = new ReleaseKey(owner, version);
        else pins.put(owner, version);
        selection = new ModStoreSelection(root, selection.optionals(), pins);
        changed.accept(selection);
    }

    /** Removes only the selected explicit pin, without changing optional edges. / 仅移除所选明确固定版本，不改变可选边。 */
    private void removePin() {
        if (!editable || !active || selection == null) return;
        var pins = new HashMap<>(selection.pins());
        pins.remove(selectedOwner());
        selection = new ModStoreSelection(selection.root(), selection.optionals(), pins);
        changed.accept(selection);
    }

    /** Enables reading and draft controls only within the current host boundary. / 仅在当前宿主边界内启用读取及草稿控件。
     * @param value whether this view is interactive / 此视图是否可交互 */
    void interactive(boolean value) {
        active = value;
        table.setEnabled(value);
        versions.setEnabled(value && editable && versions.getItemCount() > 0);
        rootVersions.setEnabled(value && editable && rootVersions.getItemCount() > 0);
        unpin.setEnabled(value && editable && selection != null && selection.pins().containsKey(selectedOwner()));
        relationControls(relations, value);
    }

    private void relationControls(java.awt.Container parent, boolean value) {
        for (var component : parent.getComponents()) {
            if (component instanceof JCheckBox box)
                box.setEnabled(value && editable && Boolean.TRUE.equals(box.getClientProperty("store.optional")));
            else if (component instanceof java.awt.Container container) relationControls(container, value);
        }
    }
}
