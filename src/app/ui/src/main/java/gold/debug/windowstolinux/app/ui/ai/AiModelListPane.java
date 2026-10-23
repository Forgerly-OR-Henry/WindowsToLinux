package gold.debug.windowstolinux.app.ui.ai;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;

import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.app.ui.control.*;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;

/** Inventory, model details and independent purpose composition. / 清单、模型详情及独立用途编排。 */
final class AiModelListPane extends JPanel implements PendingCardEdits {
    private final AiModelListAccess service;

    private final DesktopPartFactory components;

    private final PageMessagePresenter messages;

    private final Consumer<AiModelInfo> edit;

    private final Function<AiModelInfo, JComponent> details;

    private final DefaultListModel<String> inventoryModel = new DefaultListModel<>(), purposeModel = new DefaultListModel<>();

    private final DesktopCardList<String> inventory = new DesktopCardList<>(inventoryModel, 92),
        selected = new DesktopCardList<>(purposeModel, 92);

    private final JScrollPane inventoryScroll = new JScrollPane(inventory), purposeScroll = new JScrollPane(selected);

    private final JPanel columns = new JPanel(new GridBagLayout()), left = new JPanel(new BorderLayout(0, 14)),
        leftTools = new JPanel(new BorderLayout(8, 0)), editor = new JPanel(new BorderLayout(0, 14)),
        roles = new JPanel(new GridLayout(1, 4, 6, 0)), arrows = new JPanel(new GridLayout(2, 1, 0, 10)),
        heading = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

    private final JLabel title = new JLabel(), status = new JLabel();

    private final JButton add = new JButton("→"), remove = new JButton("←"), newModel;

    private final Map<AiPurposeType, JButton> buttons = new EnumMap<>(AiPurposeType.class);

    private final CardOrderSession<String> order;

    private final Map<AiPurposeType, CardOrderSession<String>> purposes = new EnumMap<>(AiPurposeType.class);

    private List<AiModelInfo> models = List.of();

    private AiPurposeType purpose;

    private JComponent detail;

    private String detailId;

    private boolean locked, closed, loading, rendering, transition;

    private long epoch, loadRequest;

    AiModelListPane(AiModelListAccess service, DesktopPartFactory c, PageMessagePresenter messages,
                    Consumer<AiModelInfo> edit) {
        this(service, c, messages, edit, info -> new JLabel(info.name()));
    }

    AiModelListPane(AiModelListAccess service, DesktopPartFactory c, PageMessagePresenter messages,
                    Consumer<AiModelInfo> edit, Function<AiModelInfo, JComponent> details) {
        super(new BorderLayout(0, 6));
        this.service = service;
        this.components = c;
        this.messages = messages;
        this.edit = edit;
        this.details = details;
        for (var panel : List.of(this, columns, left, leftTools, editor, roles, arrows, heading))
            panel.setOpaque(false);
        order = new CardOrderSession<>(this, messages, (expected, requested) -> {
            service.reorderAiProviders(expected, requested);
            return requested;
        }, () -> service.listAiConfigurations().stream().map(v -> v.profile().id()).toList(), ignored -> {
        }, this::render);
        for (var type : AiPurposeType.values()) {
            purposes.put(type, new CardOrderSession<>(this, messages, (expected, requested) -> {
                service.saveAiPurpose(type, assignments(expected), assignments(requested));
                return requested;
            }, () -> service.listAiPurpose(type).stream().map(AiPurposeAssignment::profileId).toList(), ignored -> {
            }, this::render));
            var button = c.secondaryButton("");
            button.setName("ai.open." + type.name());
            button.addActionListener(event -> open(type));
            buttons.put(type, button);
            roles.add(button);
        }
        inventory.setName("ai.inventory");
        selected.setName("ai.purpose");
        inventory.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        selected.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        inventory.setCellRenderer(renderer(false));
        selected.setCellRenderer(renderer(true));
        c.cardList(inventory, true);
        c.cardList(selected, true);
        inventoryScroll.setBorder(BorderFactory.createEmptyBorder());
        purposeScroll.setBorder(BorderFactory.createEmptyBorder());
        installOrder(inventory, false);
        installOrder(selected, true);
        inventory.addListSelectionListener(event -> controls());
        selected.addListSelectionListener(event -> controls());
        inventory.putClientProperty("desktop.cardClick", (Runnable) this::choose);
        inventory.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "details");
        inventory.getActionMap().put("details", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                choose();
            }
        });
        newModel = messages.bind(c.primaryButton(""), "ai.models.add");
        newModel.setName("ai.models.add");
        newModel.addActionListener(event -> before(() -> edit.accept(null)));
        heading.add(title);
        left.add(leftTools, BorderLayout.NORTH);
        left.add(inventoryScroll);
        var orderButtons = c.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        var up = c.secondaryButton("↑");
        var down = c.secondaryButton("↓");
        up.addActionListener(event -> move(false, -1));
        down.addActionListener(event -> move(false, 1));
        orderButtons.add(up);
        orderButtons.add(down);
        left.add(orderButtons, BorderLayout.SOUTH);
        add.setName("ai.purpose.add");
        remove.setName("ai.purpose.remove");
        add.setToolTipText(messages.text("ai.purpose.add"));
        remove.setToolTipText(messages.text("ai.purpose.remove"));
        add.addActionListener(event -> member(true));
        remove.addActionListener(event -> member(false));
        arrows.add(add);
        arrows.add(remove);
        add(columns);
        add(status, BorderLayout.SOUTH);
        localize();
        layoutColumns();
    }

    private static List<AiPurposeAssignment> assignments(List<String> ids) {
        return ids.stream().map(AiPurposeAssignment::new).toList();
    }

    private boolean blocked() {
        return locked || closed || loading || transition || order.busy() || purposes.values().stream().anyMatch(CardOrderSession::busy);
    }

    void setLocked(boolean value) {
        if (locked != value) {
            epoch++;
            loadRequest++;
            loading = false;
        }
        locked = value;
        order.setLocked(value);
        purposes.values().forEach(session -> session.setLocked(value));
        if (value) {
            inventory.cancelInteraction();
            selected.cancelInteraction();
        }
        if (detail instanceof ModelDetailsPane pane) pane.setLocked(value);
        controls();
    }

    AiModelInfo current() {
        return find(inventory.getSelectedValue());
    }

    private AiModelInfo find(String id) {
        return models.stream().filter(v -> v.profile().id().equals(id)).findFirst().orElse(null);
    }

    void refresh() {
        if (blocked() || service == null) return;
        before(this::load);
    }

    private void load() {
        if (locked || closed || loading) return;
        loading = true;
        long generation = epoch, request = ++loadRequest;
        controls();
        DesktopTaskRunner.run(() -> {
            var rows = service.listAiConfigurations();
            var values = new EnumMap<AiPurposeType, List<String>>(AiPurposeType.class);
            for (var type : AiPurposeType.values())
                values.put(type, service.listAiPurpose(type).stream().map(AiPurposeAssignment::profileId).toList());
            return new Snapshot(rows, values);
        }, snapshot -> {
            if (closed || generation != epoch || request != loadRequest) return;
            loading = false;
            models = snapshot.models();
            order.accept(models.stream().map(v -> v.profile().id()).toList());
            snapshot.purposes().forEach((type, ids) -> purposes.get(type).accept(ids));
            status.setText("");
            render();
            if (detailId != null) showDetail(find(detailId));
        }, failure -> {
            if (closed || generation != epoch || request != loadRequest) return;
            loading = false;
            status.setText(messages.safe(failure));
            controls();
        });
    }

    private record Snapshot(List<AiModelInfo> models, Map<AiPurposeType, List<String>> purposes) {
    }

    void open(AiPurposeType value) {
        if (blocked() || detailId != null) return;
        transition = true;
        var saving = purpose == null ? CompletableFuture.completedFuture(true) : purposes.get(purpose).saveBeforeLeave();
        saving.whenComplete((ok, failure) -> {
            transition = false;
            if (!closed && !locked && failure == null && Boolean.TRUE.equals(ok)) {
                purpose = purpose == value ? null : value;
                layoutColumns();
                render();
            }
            controls();
        });
    }

    private void choose() {
        if (blocked() || purpose != null) return;
        showDetail(current());
    }

    private void showDetail(AiModelInfo model) {
        if (detail instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
        detailId = model == null ? null : model.profile().id();
        detail = model == null ? null : details.apply(model);
        layoutColumns();
    }

    void collapse() {
        inventory.clearSelection();
        showDetail(null);
    }

    private void layoutColumns() {
        if (newModel == null) return;
        columns.removeAll();
        leftTools.removeAll();
        heading.remove(newModel);
        leftTools.add(heading, BorderLayout.WEST);
        boolean expanded = purpose != null || detailId != null;
        if (expanded) leftTools.add(newModel, BorderLayout.EAST);
        else {
            heading.add(newModel);
            leftTools.add(roles, BorderLayout.EAST);
        }
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.BOTH;
        g.weighty = 1;
        g.weightx = detailId == null ? 1 : .4;
        columns.add(left, g);
        if (purpose != null) {
            editor.removeAll();
            var top = components.transparent(new BorderLayout(0, 8));
            top.add(roles);
            var close = components.secondaryButton("×");
            close.setToolTipText(messages.text("ai.purpose.collapse"));
            close.getAccessibleContext().setAccessibleName(messages.text("ai.purpose.collapse"));
            close.addActionListener(event -> open(purpose));
            top.add(close, BorderLayout.EAST);
            editor.add(top, BorderLayout.NORTH);
            editor.add(purposeScroll);
            var actions = components.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            for (int delta : new int[]{-1, 1}) {
                var button = components.secondaryButton(delta < 0 ? "↑" : "↓");
                button.addActionListener(event -> move(true, delta));
                actions.add(button);
            }
            editor.add(actions, BorderLayout.SOUTH);
            g.gridx = 1;
            g.weightx = 0;
            g.fill = GridBagConstraints.NONE;
            g.insets = new Insets(0, 12, 0, 12);
            columns.add(arrows, g);
            g.gridx = 2;
            g.fill = GridBagConstraints.BOTH;
            g.weightx = 1;
            g.insets = new Insets(0, 0, 0, 0);
            columns.add(editor, g);
        } else if (detail != null) {
            g.gridx = 1;
            g.weightx = .6;
            g.insets = new Insets(0, 24, 0, 0);
            columns.add(detail, g);
        }
        left.setMinimumSize(new Dimension(0, 0));
        left.setPreferredSize(new Dimension(0, 360));
        editor.setMinimumSize(new Dimension(0, 0));
        editor.setPreferredSize(new Dimension(0, 360));
        if (detail != null) {
            detail.setMinimumSize(new Dimension(0, 0));
            detail.setPreferredSize(new Dimension(0, 360));
        }
        roles.setVisible(detailId == null);
        columns.revalidate();
        columns.repaint();
        controls();
    }

    private void render() {
        if (rendering || newModel == null || closed) return;
        rendering = true;
        try {
            var selectedId = inventory.getSelectedValue();
            var memberId = selected.getSelectedValue();
            int scroll = inventoryScroll.getVerticalScrollBar().getValue();
            inventoryModel.clear();
            order.draft().forEach(inventoryModel::addElement);
            purposeModel.clear();
            if (purpose != null) purposes.get(purpose).draft().forEach(purposeModel::addElement);
            inventory.setSelectedValue(selectedId, false);
            selected.setSelectedValue(memberId, false);
            inventoryScroll.getVerticalScrollBar().setValue(scroll);
            localize();
            controls();
        } finally {
            rendering = false;
        }
    }

    void localize() {
        title.setText(messages.text("ai.models.all") + "  " + models.size());
        for (var type : AiPurposeType.values())
            buttons.get(type).setText(messages.text("ai.purpose.short." + type.name()) + "  " + purposes.get(type).draft().size());
        if (detailId != null && !rendering) showDetail(find(detailId));
        repaint();
    }

    private void controls() {
        if (newModel == null) return;
        boolean ready = !blocked() && order.loaded();
        inventory.setEnabled(ready);
        selected.setEnabled(ready);
        newModel.setEnabled(ready);
        buttons.values().forEach(button -> button.setEnabled(ready));
        var model = current();
        add.setEnabled(ready && purpose != null && model != null && model.verified(purpose)
            && !purposes.get(purpose).draft().contains(model.profile().id()));
        remove.setEnabled(ready && purpose != null && selected.getSelectedValue() != null);
    }

    private void member(boolean joining) {
        if (purpose == null || (joining ? !add.isEnabled() : !remove.isEnabled())) return;
        var session = purposes.get(purpose);
        var next = new ArrayList<>(session.draft());
        if (joining) next.add(current().profile().id());
        else next.remove(selected.getSelectedValue());
        session.edit(next, false);
    }

    private void reorder(boolean role, List<String> ids) {
        if (!blocked()) (role ? purposes.get(purpose) : order).edit(ids, true);
    }

    private void move(boolean role, int delta) {
        if (role && purpose == null || blocked()) return;
        var list = role ? selected : inventory;
        var session = role ? purposes.get(purpose) : order;
        var ids = new ArrayList<>(session.draft());
        int from = list.getSelectedIndex();
        if (from < 0 || from + delta < 0 || from + delta >= ids.size()) return;
        Collections.swap(ids, from, from + delta);
        reorder(role, ids);
    }

    private void installOrder(DesktopCardList<String> list, boolean role) {
        list.reorder(ids -> reorder(role, ids), () -> !blocked() && (!role || purpose != null));
        for (int delta : new int[]{-1, 1}) {
            String key = delta < 0 ? "alt UP" : "alt DOWN";
            list.getInputMap().put(KeyStroke.getKeyStroke(key), key);
            list.getActionMap().put(key, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    move(role, delta);
                }
            });
        }
    }

    private ListCellRenderer<? super String> renderer(boolean role) {
        return (list, id, index, chosen, focus) -> {
            var model = find(id);
            var panel = new JPanel(new BorderLayout(14, 0));
            panel.setOpaque(false);
            boolean eligible = role || purpose == null || model != null && model.verified(purpose);
            Color foreground = UIManager.getColor(eligible ? "Label.foreground" : "Label.disabledForeground");
            String icon = !role || purpose == null ? "ai/bot" : switch (purpose) {
                case DEPLOYMENT -> "ai/language";
                case APPROVAL -> "security/shield";
                case VISION -> "ai/vision";
                case VOICE -> "ai/voice";
            };
            panel.setEnabled(eligible);
            panel.add(new JLabel(DesktopIcons.icon(icon, 26, () -> foreground)), BorderLayout.WEST);
            var text = new JPanel(new GridLayout(2, 1, 0, 4));
            text.setOpaque(false);
            var name = new JLabel(model == null ? id : model.name());
            name.putClientProperty("html.disable", true);
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            name.setForeground(foreground);
            var description = new JLabel(model == null ? "" : model.profile().model());
            description.putClientProperty("html.disable", true);
            description.setForeground(eligible ? UIManager.getColor("Label.disabledForeground") : foreground);
            text.add(name);
            text.add(description);
            panel.add(text);
            return panel;
        };
    }

    private void before(Runnable action) {
        if (blocked()) return;
        transition = true;
        saveAll(false).whenComplete((ok, failure) -> {
            transition = false;
            controls();
            if (!closed && !locked && failure == null && Boolean.TRUE.equals(ok)) action.run();
        });
    }

    @Override
    public boolean hasPendingEdits() {
        return order.hasPendingEdits() || purposes.values().stream().anyMatch(CardOrderSession::hasPendingEdits);
    }

    @Override
    public CompletionStage<Boolean> saveBeforeLeave() {
        return saveAll(true);
    }

    CompletionStage<Boolean> savePending() {
        return saveAll(false);
    }

    private CompletionStage<Boolean> saveAll(boolean leave) {
        CompletionStage<Boolean> result = leave ? order.saveBeforeLeave() : order.savePending();
        for (var session : purposes.values())
            result = result.thenCompose(ok -> ok
                ? leave ? session.saveBeforeLeave() : session.savePending() : CompletableFuture.completedFuture(false));
        return result;
    }

    AiModelListState capture() {
        var session = purpose == null ? null : purposes.get(purpose);
        return new AiModelListState(Optional.ofNullable(purpose), session == null ? List.of() : assignments(session.draft()),
            session == null ? List.of() : assignments(session.confirmed()), Objects.toString(inventory.getSelectedValue(), ""),
            Objects.toString(selected.getSelectedValue(), ""), inventoryScroll.getVerticalScrollBar().getValue(),
            purposeScroll.getVerticalScrollBar().getValue());
    }

    void restore(AiModelListState value) {
        purpose = value.purpose().orElse(null);
        if (purpose != null) {
            var session = purposes.get(purpose);
            session.accept(value.original().stream().map(AiPurposeAssignment::profileId).toList());
            session.edit(value.draft().stream().map(AiPurposeAssignment::profileId).toList(), false);
        }
        render();
        inventory.setSelectedValue(value.selected(), false);
        selected.setSelectedValue(value.selectedPurpose(), false);
        inventoryScroll.getVerticalScrollBar().setValue(value.scroll());
        purposeScroll.getVerticalScrollBar().setValue(value.purposeScroll());
        layoutColumns();
    }

    void close() {
        closed = true;
        epoch++;
        inventory.cancelInteraction();
        selected.cancelInteraction();
        if (detail instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
