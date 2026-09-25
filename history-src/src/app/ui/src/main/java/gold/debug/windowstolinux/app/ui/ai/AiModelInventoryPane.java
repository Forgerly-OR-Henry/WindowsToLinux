package gold.debug.windowstolinux.app.ui.ai;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.ai.AiProviderSummary;
import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.ai.*;

/** Keeps the original inventory visible while editing a purpose inline. / 内联编辑用途时保持原模型清单可见。 */
final class AiModelInventoryPane extends JPanel {
    /** Model application boundary. / 模型应用边界。 */
    private final AiApplicationFacade service;

    /** Localized messages. / 本地化消息。 */
    private final PageMessagePresenter messages;

    /** Full inventory model. / 完整清单模型。 */
    private final DefaultListModel<String> inventoryModel = new DefaultListModel<>();

    /** Purpose membership model. / 用途成员模型。 */
    private final DefaultListModel<String> purposeModel = new DefaultListModel<>();

    /** Original inventory list. / 原始清单列表。 */
    private final JList<String> inventory = new JList<>(inventoryModel);

    /** Purpose list. / 用途列表。 */
    private final JList<String> selected = new JList<>(purposeModel);

    /** Original scroll pane retained when expanding. / 展开时保留的原滚动面板。 */
    private final JScrollPane inventoryScroll = new JScrollPane(inventory);

    /** Purpose scroll pane. / 用途滚动面板。 */
    private final JScrollPane purposeScroll = new JScrollPane(selected);

    /** Shared column container. / 共用列容器。 */
    private final JPanel columns = new JPanel(new GridBagLayout());

    /** Purpose editing column. / 用途编辑列。 */
    private final JPanel editor = new JPanel(new BorderLayout(6, 8));

    /** Membership arrow column. / 成员箭头列。 */
    private final JPanel arrows = new JPanel(new GridLayout(2, 1, 0, 8));

    /** Feedback label. / 反馈标签。 */
    private final JLabel status = new JLabel();

    /** Purpose heading. / 用途标题。 */
    private final JLabel title = new JLabel();

    /** Add membership button. / 添加成员按钮。 */
    private final JButton add = new JButton("→");

    /** Remove membership button. / 移除成员按钮。 */
    private final JButton remove = new JButton("←");

    /** Purpose enablement. / 用途启用开关。 */
    private final JCheckBox enabled = new JCheckBox();

    /** Role navigation controls. / 用途导航控件。 */
    private final Map<AiPurposeType, JButton> buttons = new EnumMap<>(AiPurposeType.class);

    /** Stable inventory snapshot. / 稳定清单快照。 */
    private List<AiProviderSummary> models = List.of();

    /** Saved membership snapshots. / 已保存成员快照。 */
    private Map<AiPurposeType, List<AiPurposeAssignment>> memberships = new EnumMap<>(AiPurposeType.class);

    /** Current unsaved membership. / 当前未保存成员。 */
    private List<AiPurposeAssignment> draft = List.of();

    /** Original membership for dirty comparison. / 用于比较修改的原成员。 */
    private List<AiPurposeAssignment> original = List.of();

    /** Currently open purpose. / 当前打开用途。 */
    private AiPurposeType purpose;

    /** Loading or writing operation. / 读取或写入操作。 */
    private boolean busy;

    /** Parent operation lock. / 父级操作锁。 */
    private boolean locked;

    /** State waiting for the asynchronous inventory read. / 等待异步清单读取的状态。 */
    private AiInventoryState restored;

    /** Builds inventory and one inline purpose editor. / 构建清单及单个内联用途编辑器。
     * @param service application boundary / 应用边界
     * @param c component factory / 控件工厂
     * @param messages localized messages / 本地化消息
     * @param edit model editing callback / 模型编辑回调
     */
    AiModelInventoryPane(AiApplicationFacade service, DesktopComponentFactory c, PageMessagePresenter messages,
            Consumer<AiProviderSummary> edit) {
        super(new BorderLayout(8, 8));
        this.service = service;
        this.messages = messages;
        setOpaque(false);
        columns.setOpaque(false);
        editor.setOpaque(false);
        arrows.setOpaque(false);
        inventory.setName("ai.inventory");
        selected.setName("ai.purpose");
        inventory.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        selected.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        inventory.setCellRenderer(renderer(false));
        selected.setCellRenderer(renderer(true));
        inventory.setFixedCellHeight(56);
        selected.setFixedCellHeight(56);
        installOrder(inventory, false);
        installOrder(selected, true);
        inventory.addListSelectionListener(event -> updateControls());
        selected.addListSelectionListener(event -> updateControls());
        toolbar(c);
        editor(c);
        footer(c, edit);
        add(columns);
        layoutColumns();
    }

    /** Builds purpose navigation. / 构建用途导航。
     * @param c component factory / 控件工厂
     */
    private void toolbar(DesktopComponentFactory c) {
        JPanel toolbar = c.transparent(new BorderLayout(8, 0));
        toolbar.add(c.sectionHeading(messages.text("ai.models.title"), messages.text("ai.models.description")));
        JPanel roles = c.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        for (var value : AiPurposeType.values()) {
            JButton button = c.secondaryButton(messages.text("ai.purpose." + value.name()));
            button.setName("ai.open." + value.name());
            button.addActionListener(event -> open(value));
            buttons.put(value, button);
            roles.add(button);
        }
        toolbar.add(roles, BorderLayout.EAST);
        add(toolbar, BorderLayout.NORTH);
    }

    /** Builds inline membership controls. / 构建内联成员控件。
     * @param c component factory / 控件工厂
     */
    private void editor(DesktopComponentFactory c) {
        add.setToolTipText(messages.text("ai.purpose.add"));
        remove.setToolTipText(messages.text("ai.purpose.remove"));
        add.getAccessibleContext().setAccessibleName(messages.text("ai.purpose.add"));
        remove.getAccessibleContext().setAccessibleName(messages.text("ai.purpose.remove"));
        add.addActionListener(event -> addMember());
        remove.addActionListener(event -> removeMember());
        arrows.add(add);
        arrows.add(remove);
        editor.add(title, BorderLayout.NORTH);
        editor.add(purposeScroll);
        enabled.setText(messages.text("ai.models.enabled"));
        enabled.addActionListener(event -> setEnabledMember());
        JPanel controls = c.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.add(enabled);
        JButton up = c.secondaryButton("↑"), down = c.secondaryButton("↓");
        up.addActionListener(event -> move(true, -1));
        down.addActionListener(event -> move(true, 1));
        controls.add(up);
        controls.add(down);
        JButton cancel = c.secondaryButton(messages.text("button.cancel")),
                save = c.primaryButton(messages.text("button.save"));
        cancel.setName("ai.purpose.cancel");
        save.setName("ai.purpose.save");
        cancel.addActionListener(event -> {
            if (!blocked())
                closeEditor();
        });
        save.addActionListener(event -> save());
        controls.add(cancel);
        controls.add(save);
        editor.add(controls, BorderLayout.SOUTH);
    }

    /** Builds model inventory actions. / 构建模型清单操作。
     * @param c component factory / 控件工厂
     * @param edit model editor callback / 模型编辑回调
     */
    private void footer(DesktopComponentFactory c, Consumer<AiProviderSummary> edit) {
        JPanel footer = c.transparent(new BorderLayout(8, 0));
        footer.add(status);
        JPanel modelActions = c.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton newModel = c.primaryButton(messages.text("ai.models.add")),
                editModel = c.secondaryButton(messages.text("ai.models.edit"));
        newModel.addActionListener(event -> {
            if (!blocked())
                edit.accept(null);
        });
        editModel.addActionListener(event -> {
            if (!blocked() && current() != null)
                edit.accept(current());
        });
        JButton upInventory = c.secondaryButton("↑"), downInventory = c.secondaryButton("↓");
        upInventory.addActionListener(event -> move(false, -1));
        downInventory.addActionListener(event -> move(false, 1));
        modelActions.add(upInventory);
        modelActions.add(downInventory);
        modelActions.add(editModel);
        modelActions.add(newModel);
        footer.add(modelActions, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    /** Checks concurrent operations. / 检查并发操作。
     * @return whether edits are blocked / 是否禁止编辑
     */
    private boolean blocked() {
        return busy || locked;
    }

    /** Changes the parent operation lock. / 改变父级操作锁。
     * @param value lock state / 锁状态
     */
    void setLocked(boolean value) {
        locked = value;
        updateControls();
    }

    /** Finds the selected model. / 查找所选模型。
     * @return selected row or absent / 所选行或空
     */
    AiProviderSummary current() {
        return find(inventory.getSelectedValue());
    }

    /** Resolves a stable identity in the inventory. / 在清单中解析稳定标识。
     * @param id model identity / 模型标识
     * @return matching row or absent / 匹配行或空
     */
    private AiProviderSummary find(String id) {
        return models.stream().filter(v -> v.profile().id().equals(id)).findFirst().orElse(null);
    }

    /** Reloads saved data without discarding an open draft. / 重读保存数据，不丢弃打开的草稿。 */
    void refresh() {
        if (blocked() || service == null)
            return;
        busy = true;
        updateControls();
        DesktopTaskExecutor.run(() -> {
            var rows = service.listAiConfigurations();
            var roles = new EnumMap<AiPurposeType, List<AiPurposeAssignment>>(AiPurposeType.class);
            for (var type : AiPurposeType.values())
                roles.put(type, service.listAiPurpose(type));
            return new InventorySnapshot(rows, roles);
        }, value -> {
            busy = false;
            models = value.models();
            memberships = value.memberships();
            render();
        }, failure -> {
            busy = false;
            status.setText(messages.safe(failure));
            updateControls();
        });
    }
    /** Combined inventory read. / 合并清单读取。
     * @param models all models / 全部模型
     * @param memberships saved purpose lists / 已保存用途列表
     */
    private record InventorySnapshot(List<AiProviderSummary> models,
            Map<AiPurposeType, List<AiPurposeAssignment>> memberships) {
    }
    /** Opens a selected purpose while retaining the original list. / 保留原列表并打开所选用途。
     * @param value selected purpose / 所选用途
     */
    void open(AiPurposeType value) {
        if (blocked())
            return;
        if (purpose != null && !draft.equals(original)) {
            status.setText(messages.text("ai.purpose.unsaved"));
            return;
        }
        purpose = value;
        original = List.copyOf(memberships.getOrDefault(value, List.of()));
        draft = original;
        title.setText(messages.text("ai.purpose." + value.name()));
        layoutColumns();
        render();
    }

    /** Closes the editor and discards only its unsaved draft. / 关闭编辑器且仅丢弃其未保存草稿。 */
    private void closeEditor() {
        purpose = null;
        draft = List.of();
        original = List.of();
        layoutColumns();
        render();
    }

    /** Splits the available width without replacing the inventory control. / 不替换清单控件而分配可用宽度。 */
    private void layoutColumns() {
        int scroll = inventoryScroll.getVerticalScrollBar().getValue();
        inventoryScroll.setMinimumSize(new Dimension(0, 0));
        editor.setMinimumSize(new Dimension(0, 0));
        inventoryScroll.setPreferredSize(new Dimension(0, 320));
        editor.setPreferredSize(new Dimension(0, 320));
        columns.removeAll();
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.BOTH;
        g.weighty = 1;
        g.weightx = 1;
        columns.add(inventoryScroll, g);
        if (purpose != null) {
            g.gridx = 1;
            g.weightx = 0;
            g.fill = GridBagConstraints.NONE;
            g.insets = new Insets(0, 8, 0, 8);
            columns.add(arrows, g);
            g.gridx = 2;
            g.weightx = 1;
            g.fill = GridBagConstraints.BOTH;
            g.insets = new Insets(0, 0, 0, 0);
            columns.add(editor, g);
        }
        columns.revalidate();
        columns.repaint();
        SwingUtilities.invokeLater(() -> inventoryScroll.getVerticalScrollBar().setValue(scroll));
    }

    /** Updates rows while preserving selections and scroll positions. / 更新行且保留选择和滚动位置。 */
    private void render() {
        var state = restored == null ? capture() : restored;
        restored = null;
        inventoryModel.clear();
        models.forEach(v -> inventoryModel.addElement(v.profile().id()));
        purposeModel.clear();
        draft.forEach(v -> purposeModel.addElement(v.profileId()));
        inventory.setSelectedValue(state.selected(), false);
        selected.setSelectedValue(state.selectedPurpose(), false);
        for (var type : AiPurposeType.values())
            buttons.get(type).setText(messages.text("ai.purpose." + type.name()) + " ("
                    + memberships.getOrDefault(type, List.of()).size() + ")");
        status.setText(messages.text(models.isEmpty() ? "ai.models.empty" : "ai.models.orderHint"));
        updateControls();
        SwingUtilities.invokeLater(() -> {
            inventoryScroll.getVerticalScrollBar().setValue(state.scroll());
            purposeScroll.getVerticalScrollBar().setValue(state.purposeScroll());
        });
    }

    /** Updates membership availability and current role enablement. / 更新成员操作及当前用途启用状态。 */
    private void updateControls() {
        var model = current();
        boolean joined = model != null && draft.stream().anyMatch(v -> v.profileId().equals(model.profile().id()));
        add.setEnabled(!blocked() && purpose != null && model != null && model.verified(purpose) && !joined);
        remove.setEnabled(!blocked() && selected.getSelectedValue() != null);
        var member = draft.stream().filter(v -> v.profileId().equals(selected.getSelectedValue())).findFirst();
        enabled.setSelected(member.map(AiPurposeAssignment::enabled).orElse(false));
        var chosen = find(selected.getSelectedValue());
        enabled.setEnabled(!blocked() && member.isPresent() && chosen != null
                && (member.get().enabled() || chosen.verified(purpose)));
        buttons.values().forEach(v -> v.setEnabled(!blocked()));
        inventory.setEnabled(!blocked());
        selected.setEnabled(!blocked());
    }

    /** Appends an explicitly selected verified model. / 追加明确选择的已验证模型。 */
    private void addMember() {
        if (!add.isEnabled())
            return;
        var next = new ArrayList<>(draft);
        next.add(new AiPurposeAssignment(current().profile().id(), true));
        draft = List.copyOf(next);
        render();
    }

    /** Removes only the selected membership. / 仅移除所选用途成员。 */
    private void removeMember() {
        if (!remove.isEnabled())
            return;
        draft = draft.stream().filter(v -> !v.profileId().equals(selected.getSelectedValue())).toList();
        render();
    }

    /** Changes only the selected purpose's enablement. / 仅改变所选用途的启用状态。 */
    private void setEnabledMember() {
        if (blocked() || purpose == null)
            return;
        String id = selected.getSelectedValue();
        boolean active = enabled.isSelected();
        draft = draft.stream().map(v -> v.profileId().equals(id) ? new AiPurposeAssignment(id, active) : v).toList();
        render();
    }

    /** Commits an entire purpose draft, retaining it after failure. / 提交完整用途草稿，失败时保留。 */
    private void save() {
        if (blocked() || purpose == null)
            return;
        var role = purpose;
        var values = List.copyOf(draft);
        busy = true;
        updateControls();
        DesktopTaskExecutor.run(() -> {
            service.saveAiPurpose(role, values);
            return true;
        }, done -> {
            busy = false;
            memberships.put(role, values);
            closeEditor();
        }, failure -> {
            busy = false;
            status.setText(messages.safe(failure));
            updateControls();
        });
    }

    /** Changes one list order through its own persistence path. / 通过各自保存路径调整一个列表顺序。
     * @param role whether ordering the purpose / 是否排序用途
     * @param ids complete ordered identities / 完整有序标识
     */
    private void reorder(boolean role, List<String> ids) {
        if (blocked())
            return;
        if (role) {
            var prior = draft;
            draft = ids.stream()
                    .map(id -> prior.stream().filter(v -> v.profileId().equals(id)).findFirst().orElseThrow()).toList();
            render();
        } else {
            busy = true;
            updateControls();
            DesktopTaskExecutor.run(() -> {
                service.reorderAiProviders(ids);
                return true;
            }, done -> {
                busy = false;
                var prior = models;
                models = ids.stream()
                        .map(id -> prior.stream().filter(v -> v.profile().id().equals(id)).findFirst().orElseThrow())
                        .toList();
                render();
            }, failure -> {
                busy = false;
                status.setText(messages.safe(failure));
                updateControls();
            });
        }
    }

    /** Moves a selected row by one position. / 将所选行移动一个位置。
     * @param role whether ordering the purpose / 是否排序用途
     * @param delta direction / 方向
     */
    private void move(boolean role, int delta) {
        var list = role ? selected : inventory;
        int from = list.getSelectedIndex();
        if (blocked() || from < 0 || from + delta < 0 || from + delta >= list.getModel().getSize())
            return;
        var ids = new ArrayList<String>();
        for (int i = 0; i < list.getModel().getSize(); i++)
            ids.add(list.getModel().getElementAt(i));
        Collections.swap(ids, from, from + delta);
        reorder(role, ids);
    }

    /** Installs drag and keyboard ordering. / 安装拖动及键盘排序。
     * @param list target list / 目标列表
     * @param role whether editing purpose order / 是否编辑用途顺序
     */
    private void installOrder(JList<String> list, boolean role) {
        list.setTransferHandler(new AiProviderDragTransfer(list, ids -> reorder(role, ids), this::blocked));
        list.setDropMode(DropMode.INSERT);
        if (!GraphicsEnvironment.isHeadless())
            list.setDragEnabled(true);
        for (int delta : new int[]{-1, 1}) {
            String key = delta < 0 ? "alt UP" : "alt DOWN";
            list.getInputMap().put(KeyStroke.getKeyStroke(key), key);
            list.getActionMap().put(key, new AbstractAction() {
                /** Moves the selection. / 移动选择。
                 * @param event keyboard event / 键盘事件
                 */
                @Override
                public void actionPerformed(ActionEvent event) {
                    move(role, delta);
                }
            });
        }
    }

    /** Builds labels for inventory or purpose rows. / 构建清单或用途行标签。
     * @param role whether rendering purpose membership / 是否绘制用途成员
     * @return row renderer / 行渲染器
     */
    private ListCellRenderer<? super String> renderer(boolean role) {
        return (list, id, index, isSelected, focus) -> {
            JLabel label = (JLabel) new DefaultListCellRenderer().getListCellRendererComponent(list, id, index,
                    isSelected, focus);
            var model = find(id);
            String suffix = "";
            if (role) {
                var member = draft.stream().filter(v -> v.profileId().equals(id)).findFirst().orElseThrow();
                suffix = " · " + messages.text(member.enabled() ? "ai.models.enabled" : "ai.purpose.disabled");
            } else if (purpose != null && draft.stream().anyMatch(v -> v.profileId().equals(id)))
                suffix = " · " + messages.text("ai.purpose.joined");
            if (model != null) {
                label.setText((index + 1) + "  " + model.name() + " · " + model.profile().model() + suffix);
                label.setToolTipText(model.profile().chatCompletionsEndpoint().toString());
            }
            label.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            return label;
        };
    }

    /** Captures nonsecret editor state. / 捕获非秘密编辑状态。
     * @return editor snapshot / 编辑快照
     */
    AiInventoryState capture() {
        if (restored != null)
            return restored;
        return new AiInventoryState(Optional.ofNullable(purpose), draft, original,
                Objects.toString(inventory.getSelectedValue(), ""), Objects.toString(selected.getSelectedValue(), ""),
                inventoryScroll.getVerticalScrollBar().getValue(), purposeScroll.getVerticalScrollBar().getValue());
    }

    /** Restores editor state after theme or language rebuild. / 主题或语言重建后恢复编辑状态。
     * @param state captured state / 捕获状态
     */
    void restore(AiInventoryState state) {
        restored = state;
        purpose = state.purpose().orElse(null);
        draft = state.draft();
        original = state.original();
        if (purpose != null)
            title.setText(messages.text("ai.purpose." + purpose.name()));
        layoutColumns();
        inventory.setSelectedValue(state.selected(), false);
        selected.setSelectedValue(state.selectedPurpose(), false);
        inventoryScroll.getVerticalScrollBar().setValue(state.scroll());
        purposeScroll.getVerticalScrollBar().setValue(state.purposeScroll());
    }
}
