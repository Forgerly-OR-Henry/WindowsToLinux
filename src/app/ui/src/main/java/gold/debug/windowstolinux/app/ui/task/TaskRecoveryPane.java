package gold.debug.windowstolinux.app.ui.task;

import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.*;

import com.formdev.flatlaf.ui.FlatEmptyBorder;
import com.formdev.flatlaf.ui.FlatLineBorder;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.service.task.TaskManagement;
import gold.debug.windowstolinux.app.service.task.TaskManagement.Entry;
import gold.debug.windowstolinux.app.service.task.TaskManagement.RecoveryState;
import gold.debug.windowstolinux.app.ui.control.*;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.appapi.task.control.*;
import gold.debug.windowstolinux.appapi.task.model.*;
import gold.debug.windowstolinux.appapi.task.model.TaskViewSnapshot.Category;

/** Shared all-task and exact-Mod inventory; business controls remain in the original Mod. */
public final class TaskRecoveryPane extends JPanel implements AutoCloseable {
    private final TaskManagement service;

    private final DesktopPartFactory components;

    private final PageMessagePresenter messages;

    private final Runnable history;

    private final DefaultListModel<Object> model = new DefaultListModel<>();

    private final JList<Object> list = new JList<>(model);

    private final JTextField search = new JTextField();

    private final JLabel count = new JLabel(), status = new JLabel(), empty = new JLabel();

    private final JButton reload, logs, primary, intervene, collapse;

    private final JPanel inventory, detail;

    private final gold.debug.windowstolinux.app.ui.display.DesktopSplitPane workspace;

    private final DetailContent detailBody;

    private final JScrollPane detailScroll;

    private final JProgressBar progress = new JProgressBar(0, 1000);

    private final javax.swing.Timer timer;

    private List<Entry> entries = List.of();

    private InventoryState renderedInventory;

    private Entry renderedEntry;

    private UUID selection, requestedSelection, renderedSelection;

    private JDialog confirmation, logWindow;

    private boolean locked = true, busy, refreshing, closed, rendering;

    private boolean detailRendered, renderedLocked;

    private long epoch;

    public TaskRecoveryPane(TaskManagement service, DesktopPartFactory components,
                            PageMessagePresenter messages, Runnable history) {
        super(new BorderLayout());
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Task controls require EDT");
        this.service = Objects.requireNonNull(service);
        this.components = components;
        this.messages = messages;
        this.history = history;
        setName("platform.tasks");
        components.stylePage(this);
        setBorder(new FlatEmptyBorder(16, 20, 16, 20));
        reload = button("tasks.reload", "tasks.reload", "action/refresh-cw");
        logs = button("tasks.history", "tasks.history", "mod/archive");
        primary = components.primaryButton("");
        primary.setMargin(new Insets(8, 14, 8, 14));
        primary.setName("tasks.primary");
        intervene = button("tasks.intervene", "tasks.intervene", "security/hand");
        collapse = button("tasks.collapse", "tasks.collapse", "navigation/chevron-right");
        reload.addActionListener(event -> refresh());
        logs.addActionListener(event -> showLogs());
        primary.addActionListener(event -> runPrimary());
        intervene.addActionListener(event -> runControl(TaskCommand.INTERVENE));
        collapse.addActionListener(event -> {
            selection = null;
            list.clearSelection();
            showSelection();
        });
        inventory = components.outerCard(new BorderLayout(0, UIScale.scale(10)));
        inventory.setName("tasks.inventory");
        var heading = components.transparent(new BorderLayout(0, UIScale.scale(8)));
        var tools = components.transparent(new WrappingFlowLayout(new FlowLayout(FlowLayout.LEFT, 8, 0)));
        var title = messages.bind(new JLabel(), "tasks.list");
        title.setFont(title.getFont().deriveFont(Font.BOLD, UIScale.scale(18f)));
        tools.add(title);
        tools.add(count);
        var listActions = components.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        listActions.add(logs);
        listActions.add(reload);
        tools.add(listActions);
        heading.add(tools, BorderLayout.NORTH);
        search.setName("tasks.search");
        components.styleSearchField(search);
        search.putClientProperty("JTextField.leadingIcon", DesktopIcons.icon("action/search", 16,
            () -> UIManager.getColor("Label.disabledForeground")));
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
        heading.add(search, BorderLayout.CENTER);
        inventory.add(heading, BorderLayout.NORTH);
        list.setName("tasks.list");
        list.putClientProperty("desktop.inventory.cards", false);
        list.putClientProperty(gold.debug.windowstolinux.app.ui.display.DesktopListCard.FEEDBACK_OUTLINE,
            (java.util.function.IntFunction<Shape>) index -> {
                if (index < 0 || index >= model.size() || !(model.get(index) instanceof Entry)) return null;
                Rectangle bounds = list.getCellBounds(index, index);
                if (bounds == null) return null;
                float inset = UIScale.scale(.75f), arc = UIScale.scale(12f);
                return new java.awt.geom.RoundRectangle2D.Float(bounds.x + inset, bounds.y + inset,
                    bounds.width - inset * 2, bounds.height - UIScale.scale(8) - inset * 2, arc, arc);
            });
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(this::render);
        list.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || rendering) return;
            selection = list.getSelectedValue() instanceof Entry entry ? entry.snapshot().id() : null;
            showSelection();
        });
        var listScroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        components.cardScroll(listScroll);
        list.setBackground(inventory.getBackground());
        var listRegion = components.transparent(new BorderLayout());
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        listRegion.add(listScroll, BorderLayout.CENTER);
        listRegion.add(empty, BorderLayout.SOUTH);
        inventory.add(listRegion, BorderLayout.CENTER);
        status.setName("tasks.status");
        inventory.add(status, BorderLayout.SOUTH);
        detail = components.outerCard(new BorderLayout());
        detail.setName("tasks.details");
        detailBody = new DetailContent();
        detailScroll = new JScrollPane(detailBody, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        components.cardScroll(detailScroll);
        detail.add(detailScroll);
        detail.setVisible(false);
        progress.setName("tasks.progress");
        workspace = new gold.debug.windowstolinux.app.ui.display.DesktopSplitPane(inventory, detail, .44, 16, new Dimension(1100, 650));
        add(workspace);
        timer = new javax.swing.Timer(1000, event -> {
            if (isShowing() && !locked && !closed && !busy && confirmation == null && logWindow == null) refresh();
        });
        localize();
    }

    private JButton button(String name, String key, String icon) {
        var button = messages.bind(components.secondaryButton(""), key);
        button.setMargin(new Insets(8, 14, 8, 14));
        button.setName(name);
        button.setIcon(DesktopIcons.icon(icon, 16, button::getForeground));
        return button;
    }

    public void setLocked(boolean value) {
        epoch++;
        locked = value;
        dismissDialogs();
        if (value) {
            workspace.reset(false);
            timer.stop();
            progress.setIndeterminate(false);
        } else {
            timer.start();
            refresh();
        }
        filter();
    }

    public void refresh() {
        if (locked || busy || refreshing || closed || confirmation != null) return;
        long observed = epoch;
        refreshing = true;
        DesktopTaskRunner.run(service::list, values -> {
            refreshing = false;
            if (closed || locked || confirmation != null) return;
            if (observed != epoch) {
                refresh();
                return;
            }
            if (List.of(primary, intervene, collapse).stream().anyMatch(button ->
                button.getModel().isArmed() || button.getModel().isPressed())) return;
            entries = List.copyOf(values);
            if (requestedSelection != null) {
                selection = requestedSelection;
                requestedSelection = null;
            }
            filter();
        }, failure -> {
            refreshing = false;
            if (!closed && !locked && confirmation == null) {
                if (observed != epoch) refresh();
                else status.setText(messages.safe(failure));
            }
        });
    }

    public void selectTask(UUID id) {
        requestedSelection = Objects.requireNonNull(id);
        search.setText("");
        refresh();
    }

    private void filter() {
        if (closed) return;
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        var next = new InventoryState(entries, query, locked);
        rendering = true;
        if (!next.equals(renderedInventory)) {
            var visibleRows = new ArrayList<Object>();
            int visible = 0;
            if (!locked) for (var category : Category.values()) {
                var group = entries.stream().filter(entry -> entry.snapshot().state() != TaskState.QUEUED
                        && entry.snapshot().state() != TaskState.ENDED && entry.view().category() == category)
                    .filter(entry -> (title(entry) + " " + entry.snapshot().type() + " " + entry.snapshot().ownerId())
                        .toLowerCase(Locale.ROOT).contains(query)).toList();
                if (group.isEmpty()) continue;
                visibleRows.add(new Group(category, group.size()));
                visibleRows.addAll(group);
                visible += group.size();
            }
            for (int i = 0; i < visibleRows.size(); i++) {
                if (i >= model.size()) model.addElement(visibleRows.get(i));
                else if (!Objects.equals(model.get(i), visibleRows.get(i))) model.set(i, visibleRows.get(i));
            }
            while (model.size() > visibleRows.size()) model.remove(model.size() - 1);
            count.setText(Integer.toString(visible));
            count.setName("tasks.count");
            empty.setText(visible == 0 && !locked ? messages.text(query.isEmpty() ? "tasks.empty" : "tasks.noMatches") : "");
            renderedInventory = next;
        }
        int selectedIndex = -1;
        for (int i = 0; i < model.size(); i++)
            if (model.get(i) instanceof Entry entry && entry.snapshot().id().equals(selection)) selectedIndex = i;
        if (list.getSelectedIndex() != selectedIndex) list.setSelectedIndex(selectedIndex);
        if (selectedIndex < 0) selection = null;
        rendering = false;
        showSelection();
    }

    private Component render(JList<?> owner, Object value, int index, boolean selected, boolean focus) {
        if (value instanceof Group group) {
            var label = new JLabel(messages.text("tasks.group." + group.category()) + "  " + group.count());
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            label.setBorder(new FlatEmptyBorder(14, 0, 7, 0));
            return label;
        }
        var entry = (Entry) value;
        var row = components.card(new BorderLayout(UIScale.scale(12), 0));
        if (row.getBackground().getRed() > 100) row.setBackground(Color.WHITE);
        row.setName("tasks.card");
        Color border = selected ? UIManager.getColor("Component.focusColor") : UIManager.getColor("Component.borderColor");
        row.setBorder(new FlatLineBorder(new Insets(12, 14, 12, 14), border, 1, 12));
        var icon = new JLabel(DesktopIcons.icon("navigation/panels-top-left", 25,
            () -> UIManager.getColor("Label.disabledForeground")));
        row.add(icon, BorderLayout.WEST);
        var text = components.transparent(new GridLayout(3, 1, 0, UIScale.scale(4)));
        var name = literal(title(entry));
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        name.setToolTipText(title(entry));
        text.add(name);
        String ownerId = entry.snapshot().ownerId();
        text.add(subdued(ownerId.substring(ownerId.lastIndexOf('.') + 1) + " · " + entry.snapshot().ownerVersion()));
        text.add(subdued(entry.snapshot().progressText()));
        row.add(text, BorderLayout.CENTER);
        var right = components.transparent(new BorderLayout(0, UIScale.scale(8)));
        var capsule = components.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        capsule.add(pill(entry.view().category()));
        right.add(capsule, BorderLayout.NORTH);
        right.add(subdued(DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
            .format(entry.snapshot().startedAt())), BorderLayout.SOUTH);
        row.add(right, BorderLayout.EAST);
        var gap = components.transparent(new BorderLayout());
        gap.setBorder(new FlatEmptyBorder(0, 0, 8, 0));
        gap.add(row);
        gap.setPreferredSize(new Dimension(100, row.getPreferredSize().height + UIScale.scale(8)));
        return gap;
    }

    private JLabel pill(Category category) {
        var label = new JLabel(messages.text("tasks.badge." + category)) {
            @Override
            protected void paintComponent(Graphics graphics) {
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(getBackground());
                g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        boolean dark = inventory.getBackground().getRed() < 100;
        Color ink = switch (category) {
            case RUNNING -> new Color(dark ? 0x73D7A5 : 0x148451);
            case WAITING_USER -> new Color(dark ? 0xE9C75E : 0x926C00);
            case WAITING_RECOVERY -> new Color(dark ? 0xC2C6CD : 0x667080);
        };
        Color surface = switch (category) {
            case RUNNING -> new Color(dark ? 0x183D2E : 0xE5F5EC);
            case WAITING_USER -> new Color(dark ? 0x403619 : 0xFFF5D5);
            case WAITING_RECOVERY -> new Color(dark ? 0x34373D : 0xECEEF2);
        };
        label.setName("tasks.badge");
        label.setForeground(ink);
        label.setBackground(surface);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(new FlatEmptyBorder(5, 10, 5, 10));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        return label;
    }

    private Entry selected() {
        return selection == null ? null : entries.stream().filter(value -> value.snapshot().id().equals(selection)).findFirst().orElse(null);
    }

    private void showSelection() {
        boolean newSelection = !Objects.equals(renderedSelection, selection);
        var entry = locked ? null : selected();
        if (detailRendered && !newSelection && renderedLocked == locked && Objects.equals(entry, renderedEntry)) {
            controls();
            return;
        }
        renderedSelection = selection;
        progress.setIndeterminate(false);
        workspace.expanded(entry != null && !locked);
        detailBody.content(entry == null || locked ? null : entry.snapshot().id());
        detailBody.removeAll();
        if (entry != null && !locked) {
            var body = components.transparent(new BorderLayout(0, UIScale.scale(20)));
            var header = components.transparent(new BorderLayout(8, 8));
            var title = literal(title(entry));
            title.setToolTipText(title(entry));
            title.setFont(title.getFont().deriveFont(Font.BOLD, UIScale.scale(19f)));
            header.add(title, BorderLayout.CENTER);
            var right = components.transparent(new WrappingFlowLayout(new FlowLayout(FlowLayout.RIGHT, 6, 0)));
            right.add(pill(entry.view().category()));
            right.add(collapse);
            header.add(right, BorderLayout.NORTH);
            header.add(subdued(entry.snapshot().ownerId() + " · " + entry.snapshot().ownerVersion()), BorderLayout.SOUTH);
            body.add(header, BorderLayout.NORTH);
            var sections = components.transparent(null);
            sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
            sections.add(section("tasks.progressTitle"));
            var current = prose(entry.snapshot().progressText());
            current.setName("tasks.progressText");
            sections.add(current);
            progress.setStringPainted(true);
            if (entry.view().fraction().isPresent()) {
                double fraction = entry.view().fraction().get();
                progress.setValue((int) Math.round(fraction * 1000));
                progress.setString(Math.round(fraction * 100) + "%");
            } else {
                progress.setValue(0);
                progress.setString(messages.text("tasks.progressUnknown"));
                progress.setIndeterminate(true);
            }
            progress.setForeground(pill(entry.view().category()).getForeground());
            progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(24)));
            sections.add(Box.createVerticalStrut(UIScale.scale(8)));
            sections.add(progress);
            var actions = components.transparent(new WrappingFlowLayout(new FlowLayout(FlowLayout.LEFT, 8, 10)));
            actions.add(primary);
            actions.add(intervene);
            sections.add(actions);
            var reason = prose(reason(entry));
            reason.setName("tasks.controlReason");
            sections.add(reason);
            sections.add(Box.createVerticalStrut(UIScale.scale(16)));
            sections.add(section("tasks.information"));
            field(sections, "tasks.id", entry.snapshot().id().toString());
            field(sections, "tasks.owner", entry.snapshot().ownerId());
            field(sections, "tasks.version", entry.snapshot().ownerVersion());
            field(sections, "tasks.type", entry.snapshot().type());
            field(sections, "tasks.started", time(entry.snapshot().startedAt()));
            field(sections, "tasks.state", messages.text("tasks.state." + entry.snapshot().state()));
            field(sections, "tasks.availability", messages.text("tasks.recovery." + entry.recovery()));
            sections.add(Box.createVerticalStrut(UIScale.scale(16)));
            sections.add(section("tasks.blockers"));
            sections.add(prose(String.join("\n", entry.snapshot().blockers())));
            for (var child : sections.getComponents())
                if (child instanceof JComponent component) {
                    component.setAlignmentX(Component.LEFT_ALIGNMENT);
                    if (component instanceof JLabel)
                        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
                }
            sections.add(Box.createVerticalGlue());
            body.add(sections, BorderLayout.CENTER);
            detailBody.add(body, BorderLayout.NORTH);
        }
        controls();
        workspace.revalidate();
        workspace.repaint();
        if (newSelection) detailScroll.getViewport().setViewPosition(new Point());
        renderedEntry = entry;
        renderedLocked = locked;
        detailRendered = true;
    }

    private void field(JPanel parent, String key, String value) {
        var row = components.transparent(new BorderLayout(UIScale.scale(12), 0));
        row.setBorder(new FlatEmptyBorder(8, 0, 8, 0));
        var caption = subdued(messages.text(key));
        row.add(caption, BorderLayout.WEST);
        row.add(prose(value), BorderLayout.CENTER);
        parent.add(row);
        parent.add(components.divider());
    }

    private JLabel section(String key) {
        var label = literal(messages.text(key));
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(new FlatEmptyBorder(0, 0, 8, 0));
        return label;
    }

    private static JLabel literal(String text) {
        var label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        return label;
    }

    private static JLabel subdued(String text) {
        var label = literal(text);
        label.setToolTipText(text);
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        label.setFont(label.getFont().deriveFont(UIScale.scale(12f)));
        return label;
    }

    private static JTextArea prose(String text) {
        var area = new WrappingText(text);
        area.setEditable(false);
        area.setOpaque(false);
        var caret = new javax.swing.text.DefaultCaret() {
            @Override
            protected void adjustVisibility(Rectangle bounds) {
                if (area.isFocusOwner()) super.adjustVisibility(bounds);
            }
        };
        caret.setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        area.setCaret(caret);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setRows(1);
        area.setFont(UIManager.getFont("Label.font"));
        return area;
    }

    private boolean recoverable(Entry entry) {
        return Set.of(RecoveryState.RECONCILE, RecoveryState.ARCHIVE_ONLY, RecoveryState.CONFIRM_RESCUE).contains(entry.recovery());
    }

    private String reason(Entry entry) {
        if (entry.view().controls().state() == TaskControlSnapshot.State.PAUSE_REQUESTED)
            return messages.text("tasks.pausePending");
        if (entry.view().category() == Category.WAITING_USER) return messages.text("tasks.handleFirst");
        if (recoverable(entry) || entry.view().controls().pause() || entry.view().controls().resume()) return "";
        if (entry.recovery() == RecoveryState.UNAVAILABLE) return messages.text("tasks.recovery.UNAVAILABLE");
        String reason = entry.view().controls().reason();
        return reason.startsWith("TASK_") || reason.isBlank() ? messages.text("tasks.controlUnavailable") : reason;
    }

    private void controls() {
        boolean available = !locked && !closed && !busy && confirmation == null;
        var entry = selected();
        boolean running = entry != null && entry.view().category() == Category.RUNNING;
        messages.bind(primary, running ? "tasks.pause" : "tasks.resume");
        primary.setIcon(DesktopIcons.icon(running ? "action/pause" : "action/play", 16, primary::getForeground));
        reload.setEnabled(available);
        logs.setEnabled(available && (history != null || entry != null));
        search.setEnabled(!locked && !closed);
        list.setEnabled(!locked && !closed);
        primary.setEnabled(available && entry != null && (running ? entry.view().controls().pause()
            : entry.view().category() == Category.WAITING_RECOVERY && (entry.view().controls().resume() || recoverable(entry))));
        intervene.setEnabled(available && entry != null && entry.view().controls().intervene());
        primary.setToolTipText(entry == null ? null : reason(entry));
        intervene.setToolTipText(entry == null || entry.view().controls().intervene() ? null : messages.text("tasks.interventionUnavailable"));
    }

    private void runPrimary() {
        var entry = selected();
        if (entry == null || !primary.isEnabled()) return;
        if (entry.view().category() == Category.RUNNING) runControl(TaskCommand.PAUSE);
        else if (entry.view().controls().resume()) runControl(TaskCommand.RESUME);
        else if (recoverable(entry) && confirm(entry))
            work(() -> {
                service.reconcile(entry.snapshot().id(), entry.recovery() == RecoveryState.CONFIRM_RESCUE)
                    .toCompletableFuture().get();
                return service.list();
            }, values -> {
                entries = values;
                filter();
            });
    }

    private void runControl(TaskCommand command) {
        var entry = selected();
        if (entry == null || locked || busy) return;
        work(() -> {
            service.control(entry.snapshot().id(), command);
            return service.list();
        }, values -> {
            entries = values;
            filter();
        });
    }

    private boolean confirm(Entry entry) {
        long observed = epoch;
        String key = switch (entry.recovery()) {
            case ARCHIVE_ONLY -> "tasks.archiveConfirm";
            case CONFIRM_RESCUE -> "recovery.taskReconcileConfirm";
            default -> "tasks.reconcileConfirm";
        };
        var content = new JScrollPane(prose(messages.text(key) + "\n\n" + title(entry) + "\n" + entry.snapshot().id()
            + "\n" + entry.snapshot().ownerId() + " @ " + entry.snapshot().ownerVersion()));
        content.setPreferredSize(UIScale.scale(new Dimension(560, 200)));
        var choice = new JOptionPane(content, JOptionPane.WARNING_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        confirmation = DesktopDialogs.create(choice, this, messages.text("tasks.title"));
        confirmation.setName("tasks.confirmation");
        controls();
        try {
            confirmation.setVisible(true);
            return !locked && !closed && observed == epoch
                && Integer.valueOf(JOptionPane.OK_OPTION).equals(choice.getValue());
        } finally {
            if (confirmation != null) confirmation.dispose();
            confirmation = null;
            controls();
        }
    }

    private void showLogs() {
        if (history != null) {
            history.run();
            return;
        }
        var entry = selected();
        if (entry == null) return;
        work(() -> service.readLogs(entry.snapshot().id()), value -> {
            String text = value.records().stream().map(record -> record.recordedAt() + " " + record.event().level()
                + " " + record.event().message()).collect(java.util.stream.Collectors.joining("\n"));
            if (value.problem().isPresent()) text += "\n" + messages.safe(value.problem().get());
            var pane = new JOptionPane(new JScrollPane(prose(text)), JOptionPane.INFORMATION_MESSAGE);
            logWindow = DesktopDialogs.create(pane, this, messages.text("tasks.history"));
            logWindow.setName("tasks.logs");
            logWindow.setSize(UIScale.scale(new Dimension(700, 440)));
            logWindow.setVisible(true);
            if (logWindow != null) logWindow.dispose();
            logWindow = null;
        });
    }

    private <T> void work(Callable<T> operation, Consumer<T> success) {
        if (locked || busy || closed || confirmation != null) return;
        long observed = ++epoch;
        busy = true;
        controls();
        DesktopTaskRunner.run(operation, result -> {
            busy = false;
            if (closed) return;
            if (locked || observed != epoch) {
                controls();
                if (!locked) refresh();
                return;
            }
            success.accept(result);
            controls();
            if (requestedSelection != null) refresh();
        }, failure -> {
            busy = false;
            if (closed) return;
            if (!locked && observed == epoch) status.setText(messages.safe(failure));
            controls();
            if (!locked && observed != epoch) refresh();
        });
    }

    public void localize() {
        if (closed) return;
        renderedInventory = null;
        detailRendered = false;
        list.setCellRenderer(this::render);
        search.putClientProperty("JTextField.placeholderText", messages.text("tasks.search"));
        filter();
    }

    private static String title(Entry entry) {
        return entry.title().isBlank() ? entry.snapshot().type() : entry.title();
    }

    private static String time(java.time.Instant instant) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(instant);
    }

    private record Group(Category category, int count) {
    }

    private record InventoryState(List<Entry> entries, String query, boolean locked) {
    }

    private static final class WrappingText extends JTextArea {
        WrappingText(String text) {
            super(text);
        }

        @Override
        public Dimension getPreferredSize() {
            int width = getWidth() > 0 ? getWidth() : UIScale.scale(320);
            var insets = getInsets();
            var view = getUI().getRootView(this);
            view.setSize(Math.max(1, width - insets.left - insets.right), Integer.MAX_VALUE);
            return new Dimension(Math.min(width, UIScale.scale(320)),
                (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + insets.top + insets.bottom);
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            boolean changed = width != getWidth();
            super.setBounds(x, y, width, height);
            if (changed && getParent() != null) SwingUtilities.invokeLater(() -> {
                if (getParent() != null) getParent().revalidate();
            });
        }
    }

    private static final class DetailContent extends gold.debug.windowstolinux.app.ui.display.DesktopFadingPanel implements Scrollable {
        DetailContent() {
            super(new BorderLayout());
            setOpaque(false);
        }

        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return UIScale.scale(20);
        }

        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(20, visible.height - 20);
        }

        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public boolean getScrollableTracksViewportHeight() {
            return getPreferredSize().height < getParent().getHeight();
        }
    }

    private void dismissDialogs() {
        if (confirmation != null) {
            confirmation.dispose();
            confirmation = null;
        }
        if (logWindow != null) {
            logWindow.dispose();
            logWindow = null;
        }
    }

    @Override
    public void close() {
        closed = true;
        epoch++;
        workspace.close();
        detailBody.close();
        timer.stop();
        progress.setIndeterminate(false);
        dismissDialogs();
        entries = List.of();
        renderedInventory = null;
        renderedEntry = null;
        model.clear();
        selection = null;
        detail.setVisible(false);
        controls();
    }
}
