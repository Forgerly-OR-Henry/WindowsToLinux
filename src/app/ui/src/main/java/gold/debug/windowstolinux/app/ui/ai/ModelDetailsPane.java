package gold.debug.windowstolinux.app.ui.ai;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityStatus;
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.app.ui.control.*;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.appapi.task.model.*;

/** Checks and unfinished tasks owned by one stable model identity. / 一个稳定模型身份的核验及未结束任务。 */
final class ModelDetailsPane extends JPanel implements AutoCloseable {
    private static final List<AiCapabilityType> CAPABILITIES = List.of(AiCapabilityType.TEXT, AiCapabilityType.TOOL_CALLING,
        AiCapabilityType.VISION, AiCapabilityType.VOICE_FILE, AiCapabilityType.VOICE_REALTIME);

    private final PlatformModelService service;

    private final AiModelInfo model;

    private final PageMessagePresenter messages;

    private final Map<AiCapabilityType, JButton> verify = new EnumMap<>(AiCapabilityType.class);

    private final JComboBox<TaskSnapshot> tasks = new JComboBox<>();

    private final JButton edit, cancel, reconcile;

    private final JLabel status = new JLabel();

    private final javax.swing.Timer timer;

    private final Runnable refresh;

    private boolean locked, busy, closed;

    ModelDetailsPane(PlatformModelService service, AiModelInfo model, DesktopPartFactory c,
                     PageMessagePresenter messages, Runnable editAction, Runnable collapse, Runnable refresh) {
        super(new BorderLayout(0, 14));
        this.service = service;
        this.model = model;
        this.messages = messages;
        this.refresh = refresh;
        setName("models.details");
        setOpaque(false);
        var top = c.transparent(new BorderLayout(8, 0));
        var name = new JLabel(model.name());
        name.setName("models.details.title");
        name.setIcon(DesktopIcons.icon("ai/bot", 32, () -> UIManager.getColor("Component.focusColor")));
        name.setIconTextGap(com.formdev.flatlaf.util.UIScale.scale(16));
        name.putClientProperty("html.disable", true);
        name.setFont(name.getFont().deriveFont(Font.BOLD, com.formdev.flatlaf.util.UIScale.scale(24f)));
        top.add(name);
        var close = c.secondaryButton(messages.text("ai.models.collapse"));
        close.setMargin(new Insets(8, 22, 8, 22));
        close.setName("models.collapse");
        close.addActionListener(event -> collapse.run());
        top.add(close, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        var body = c.pagePanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        var config = c.card(new BorderLayout(0, 14));
        var configTitle = c.transparent(new BorderLayout());
        configTitle.add(sectionTitle("ai.models.configuration"));
        edit = c.secondaryButton(messages.text("ai.models.edit"));
        edit.setMargin(new Insets(8, 14, 8, 14));
        edit.setName("ai.models.edit");
        edit.addActionListener(event -> {
            if (!locked && !busy) editAction.run();
        });
        configTitle.add(edit, BorderLayout.EAST);
        config.add(configTitle, BorderLayout.NORTH);
        var properties = c.transparent(new GridBagLayout());
        var captions = List.of("field.model", "ai.models.protocol", "ai.models.endpoint");
        var values = List.of(model.profile().model(),
            messages.text("ai.models.protocol." + model.profile().protocol().name().toLowerCase(Locale.ROOT)),
            model.profile().endpoint().toString());
        var field = new GridBagConstraints();
        field.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < values.size(); i++) {
            field.gridx = 0;
            field.gridy = i * 2;
            field.gridwidth = 1;
            field.weightx = 0;
            field.insets = UIScale.scale(new Insets(6, 0, 6, 16));
            var caption = new JLabel(messages.text(captions.get(i)));
            caption.setForeground(UIManager.getColor("Component.focusColor"));
            properties.add(caption, field);
            field.gridx = 1;
            field.weightx = 1;
            field.insets = UIScale.scale(new Insets(6, 0, 6, 0));
            var value = new JLabel(values.get(i));
            value.putClientProperty("html.disable", true);
            value.setToolTipText(values.get(i));
            value.setMinimumSize(new Dimension(0, value.getPreferredSize().height));
            properties.add(value, field);
            if (i + 1 < values.size()) {
                field.gridx = 0;
                field.gridy++;
                field.gridwidth = 2;
                field.insets = new Insets(0, 0, 0, 0);
                properties.add(c.divider(), field);
            }
        }
        config.add(properties);
        body.add(config);
        body.add(Box.createVerticalStrut(18));
        var checks = c.card(new BorderLayout(0, 14));
        checks.add(sectionTitle("ai.models.capabilities"), BorderLayout.NORTH);
        var rows = c.transparent(new GridBagLayout());
        rows.setName("models.capabilities");
        var cell = new GridBagConstraints();
        cell.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < CAPABILITIES.size(); i++) {
            var capability = CAPABILITIES.get(i);
            var state = model.checks().get(capability);
            var text = new JLabel(messages.text("ai.capability." + capability.name().toLowerCase(Locale.ROOT)));
            text.setName("models.capability." + capability.name());
            text.setForeground(UIManager.getColor("Component.focusColor"));
            text.setMinimumSize(new Dimension(0, text.getPreferredSize().height));
            cell.gridx = 0;
            cell.gridy = i * 2;
            cell.gridwidth = 1;
            cell.weightx = 1;
            cell.insets = UIScale.scale(new Insets(5, 0, 5, 16));
            rows.add(text, cell);
            var result = new JLabel(messages.text("ai.check." + state.state().name()));
            result.setName("models.state." + capability.name());
            result.setForeground(stateColor(state.state()));
            String icon = switch (state.state()) {
                case AVAILABLE -> "status/check";
                case UNVERIFIED -> "status/minus";
                case UNSUPPORTED, FAILED -> "status/cross";
            };
            result.setIcon(DesktopIcons.icon(icon, 20, () -> stateColor(state.state())));
            result.setIconTextGap(UIScale.scale(8));
            if (!state.reason().isEmpty()) result.setToolTipText(messages.text("ai.check.reason." + state.reason()));
            cell.gridx = 1;
            cell.weightx = 0;
            rows.add(result, cell);
            var button = c.secondaryButton(messages.text("ai.models.verify"));
            button.setMargin(new Insets(8, 14, 8, 14));
            button.setName("models.verify." + capability.name());
            button.addActionListener(event -> check(capability));
            verify.put(capability, button);
            cell.gridx = 2;
            cell.insets = UIScale.scale(new Insets(5, 0, 5, 0));
            rows.add(button, cell);
            if (i + 1 < CAPABILITIES.size()) {
                cell.gridx = 0;
                cell.gridy++;
                cell.gridwidth = 3;
                cell.weightx = 1;
                cell.insets = new Insets(0, 0, 0, 0);
                rows.add(c.divider(), cell);
            }
        }
        checks.add(rows);
        body.add(checks);
        body.add(Box.createVerticalStrut(18));
        var taskCard = c.card(new BorderLayout(0, 12));
        taskCard.add(sectionTitle("ai.models.checkTasks"), BorderLayout.NORTH);
        tasks.setName("models.checks");
        tasks.setRenderer((list, task, index, selected, focused) -> {
            var label = new JLabel(task == null ? "" : task.progressText() + " · " + task.state());
            label.putClientProperty("html.disable", true);
            return label;
        });
        tasks.addActionListener(event -> controls());
        taskCard.add(tasks);
        var actions = c.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        cancel = c.secondaryButton(messages.text("ai.models.cancelTask"));
        cancel.setName("models.cancel");
        cancel.addActionListener(event -> taskAction(false));
        reconcile = c.secondaryButton(messages.text("platform.models.reconcile"));
        reconcile.setName("models.reconcile");
        reconcile.addActionListener(event -> taskAction(true));
        var reload = c.secondaryButton(messages.text("server.refresh"));
        for (var button : List.of(cancel, reconcile, reload)) button.setMargin(new Insets(8, 14, 8, 14));
        reload.setName("models.refresh");
        reload.addActionListener(event -> refresh.run());
        actions.add(cancel);
        actions.add(reconcile);
        actions.add(reload);
        taskCard.add(actions, BorderLayout.SOUTH);
        body.add(taskCard);
        body.add(Box.createVerticalGlue());
        var scroll = new JScrollPane(body, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        add(scroll);
        add(status, BorderLayout.SOUTH);
        timer = new javax.swing.Timer(500, event -> observe());
        timer.start();
        observe();
    }

    private JLabel sectionTitle(String key) {
        var title = new JLabel(messages.text(key));
        title.setFont(title.getFont().deriveFont(Font.BOLD, UIScale.scale(18f)));
        return title;
    }

    private static Color stateColor(AiCapabilityStatus state) {
        boolean dark = FlatLaf.isLafDark();
        return switch (state) {
            case AVAILABLE -> new Color(dark ? 0x68D391 : 0x14804A);
            case UNSUPPORTED -> new Color(dark ? 0xF6C177 : 0x996515);
            case FAILED -> new Color(dark ? 0xFF9292 : 0xC23939);
            case UNVERIFIED -> UIManager.getColor("Label.disabledForeground");
        };
    }

    private void check(AiCapabilityType capability) {
        if (locked || busy || closed || !service.checks(model.profile().id()).isEmpty()) return;
        busy = true;
        controls();
        DesktopTaskRunner.run(() -> service.verify(model.profile().id(), capability), handle -> {
            busy = false;
            if (closed) return;
            observe();
            handle.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                refresh.run();
            }));
        }, failure -> {
            busy = false;
            if (!closed) status.setText(messages.safe(new Exception(failure)));
            controls();
        });
    }

    private void taskAction(boolean recovery) {
        var task = (TaskSnapshot) tasks.getSelectedItem();
        if (task == null || busy || locked || closed) return;
        busy = true;
        controls();
        DesktopTaskRunner.run(() -> {
            if (recovery) return service.reconcile(task.id());
            service.cancel(task.id());
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }, completion -> completion.whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            busy = false;
            if (!closed) {
                if (failure != null) status.setText(messages.safe(new Exception(failure)));
                observe();
                if (recovery) refresh.run();
            }
        })), failure -> {
            busy = false;
            if (!closed) status.setText(messages.safe(new Exception(failure)));
            controls();
        });
    }

    private void observe() {
        if (closed) return;
        var rows = service.checks(model.profile().id());
        var current = (TaskSnapshot) tasks.getSelectedItem();
        var ids = rows.stream().map(TaskSnapshot::id).toList();
        var displayed = new ArrayList<java.util.UUID>();
        for (int i = 0; i < tasks.getItemCount(); i++) displayed.add(tasks.getItemAt(i).id());
        if (!ids.equals(displayed) || rows.stream().anyMatch(task -> tasks.getSelectedItem() instanceof TaskSnapshot old
            && old.id().equals(task.id()) && !old.equals(task))) {
            tasks.removeAllItems();
            rows.forEach(tasks::addItem);
            if (current != null)
                rows.stream().filter(task -> task.id().equals(current.id())).findFirst().ifPresent(tasks::setSelectedItem);
        }
        controls();
    }

    private void controls() {
        if (edit == null || cancel == null || reconcile == null) return;
        boolean ready = !closed && !locked && !busy;
        boolean unfinished = !service.checks(model.profile().id()).isEmpty();
        edit.setEnabled(ready && !unfinished);
        verify.forEach((capability, button) -> {
            boolean supported = PlatformModelService.supports(model.profile().endpoint().toString(), capability);
            button.setEnabled(ready && !unfinished && supported);
            button.setToolTipText(supported ? null : messages.text("ai.models.incompatibleTransport"));
        });
        var task = (TaskSnapshot) tasks.getSelectedItem();
        cancel.setEnabled(ready && task != null && Set.of(TaskState.RUNNING, TaskState.QUEUED).contains(task.state()));
        reconcile.setEnabled(ready && task != null && Set.of(TaskState.UNKNOWN, TaskState.FINISHING).contains(task.state()));
    }

    void setLocked(boolean value) {
        locked = value;
        controls();
    }

    @Override
    public void close() {
        closed = true;
        timer.stop();
    }
}
