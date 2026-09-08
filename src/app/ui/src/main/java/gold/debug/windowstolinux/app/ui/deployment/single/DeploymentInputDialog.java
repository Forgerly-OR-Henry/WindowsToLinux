package gold.debug.windowstolinux.app.ui.deployment.single;

import gold.debug.windowstolinux.app.service.contract.definition.*;

import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.definition.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.shared.ai.collaboration.role.DeploymentInputRoleContext;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

/** One grouped missing-input form with a contextual AI conversation in its right inspector. */
public final class DeploymentInputDialog implements AutomaticDeploymentInteraction {
    private final Component owner;
    private final AiApplicationFacade service;
    private final DesktopComponentFactory components;
    private final PageMessagePresenter messages;
    private final Supplier<char[]> master;

    /** Binds the optional assistant and secret-copy supplier without retaining secret values. */
    public DeploymentInputDialog(Component owner, AiApplicationFacade service, DesktopComponentFactory components,
                                 PageMessagePresenter messages, Supplier<char[]> master) {
        this.owner = owner; this.service = service; this.components = components; this.messages = messages; this.master = master;
    }

    /** Displays all current non-secret missing fields together. */
    @Override public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
        return onEdt(() -> {
            JPanel form = components.transparent(new GridBagLayout());
            Map<String, JComponent> inputs = new LinkedHashMap<>();
            int row = 0;
            for (var field : fields) {
                JComponent input = input(field);
                JPanel line = components.transparent(new BorderLayout(6, 0));
                line.add(input, BorderLayout.CENTER);
                line.add(AdvancedOptionsPane.help(messages.text(field.helpKey())), BorderLayout.EAST);
                int separator = field.id().lastIndexOf('/');
                String group = separator < 0 ? field.id() : field.id().substring(0, separator);
                if (group.startsWith("db/")) group = group.substring(3);
                components.addField(form, row++, 0, group + " · " + messages.text(field.labelKey()), line);
                inputs.put(field.id(), input);
            }
            JScrollPane formScroll = new JScrollPane(form);
            formScroll.setPreferredSize(new Dimension(520, Math.min(430, 70 + fields.size() * 48)));
            AdvancedOptionsPane pane = new AdvancedOptionsPane(formScroll, components, messages);
            JTextArea transcript = DesktopComponentFactory.outputArea();
            transcript.setRows(11); transcript.setColumns(24);
            JTextField question = new JTextField(24);
            JButton send = components.secondaryButton(messages.text("auto.ai.send"));
            List<String> history = new ArrayList<>();
            send.addActionListener(event -> {
                String text = question.getText().trim();
                if (text.isEmpty()) return;
                question.setText(""); transcript.append(text + "\n"); send.setEnabled(false);
                var context = new DeploymentInputRoleContext(fields.stream().limit(64).toList(), text, List.copyOf(history));
                char[] unlock = master.get();
                DesktopTaskExecutor.run(() -> {
                    try { return service.invokeAiRole(context, unlock); }
                    finally { Arrays.fill(unlock, '\0'); }
                }, result -> {
                    send.setEnabled(true);
                    String explanation = result.flatMap(value -> value.evidence().output()).map(value -> value.summary())
                            .orElseGet(() -> messages.text("auto.ai.unavailable"));
                    transcript.append(explanation + "\n\n");
                    history.add(context.question()); history.add(explanation);
                    while (history.size() > 10) history.removeFirst();
                }, failure -> { send.setEnabled(true); transcript.append(messages.text("auto.ai.unavailable") + "\n"); });
            });
            pane.addOption(new JLabel(messages.text("auto.ai.show")));
            pane.addOption(new JScrollPane(transcript)); pane.addOption(question); pane.addOption(send);
            JButton setup = components.secondaryButton(messages.text("auto.ai.setup"));
            setup.addActionListener(event -> {
                var ai = new gold.debug.windowstolinux.app.ui.ai.AiPage(service, Optional::empty, components, messages);
                JOptionPane.showMessageDialog(pane, ai.panel(), messages.text("auto.ai.setup"), JOptionPane.PLAIN_MESSAGE);
            });
            pane.addOption(setup);
            int choice = JOptionPane.showConfirmDialog(owner, pane, messages.text("auto.input.title"),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return Optional.empty();
            Map<String, String> values = new LinkedHashMap<>();
            inputs.forEach((id, input) -> values.put(id, input instanceof JTextField text ? text.getText().trim()
                    : Objects.toString(((JComboBox<?>) input).getSelectedItem(), "")));
            return Optional.of(values);
        });
    }

    private JComponent input(DeploymentInputField field) {
        if (field.choices().isEmpty()) return new JTextField(field.value(), 24);
        JComboBox<String> choices = new JComboBox<>(field.choices().toArray(String[]::new));
        choices.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, messages.inputChoice(field, Objects.toString(value, "")), index, selected, focus);
            }
        });
        if (!field.value().isBlank()) choices.setSelectedItem(field.value());
        return choices;
    }

    /** Requires a separate affirmative decision for a concrete risk. */
    @Override public boolean confirmDatabaseReplacement(Map<String, ?> details) {
        return onEdt(() -> {
            JPanel panel = components.transparent(new BorderLayout(0, 12));
            JTextArea explanation = DesktopComponentFactory.outputArea();
            explanation.setText(messages.text("db.replaceConfirmed", details)); explanation.setRows(7); explanation.setColumns(48);
            panel.add(explanation, BorderLayout.CENTER);
            JPanel checks = components.transparent(new GridLayout(0, 1, 0, 8));
            JCheckBox backup = new JCheckBox(messages.text("db.replace.backup"));
            JCheckBox downtime = new JCheckBox(messages.text("db.replace.downtime"));
            JCheckBox replacement = new JCheckBox(messages.text("db.replace.software"));
            checks.add(backup); checks.add(downtime); checks.add(replacement); panel.add(checks, BorderLayout.SOUTH);
            while (JOptionPane.showConfirmDialog(owner, panel, messages.text("auto.waiting"), JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
                if (backup.isSelected() && downtime.isSelected() && replacement.isSelected()) return true;
                JOptionPane.showMessageDialog(owner, messages.text("db.replace.checkAll"));
            }
            return false;
        });
    }

    /** Requires a separate affirmative decision for a concrete risk. */
    @Override public boolean confirm(String key, Map<String, ?> details) {
        Map<String,?> arguments = key.equals("db.conflict") ? Map.of("detail", ((List<?>)details.get("conflicts")).stream()
                .map(value -> value.toString().split("\\|",2)).map(parts -> messages.text("db.conflict."+parts[0],
                        Map.of("instance",parts.length > 1 ? parts[1] : ""))).collect(java.util.stream.Collectors.joining("\n"))) : details;
        return onEdt(() -> JOptionPane.showConfirmDialog(owner, messages.text(key, arguments), messages.text("auto.waiting"),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION);
    }

    /** Uses a password field and returns only a caller-owned character array. */
    @Override public char[] requestSecret(String key) {
        return onEdt(() -> {
            JPasswordField input = new JPasswordField(24);
            try {
                if (JOptionPane.showConfirmDialog(owner, input, messages.text(key), JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) throw new java.util.concurrent.CancellationException();
                return input.getPassword();
            } finally { input.setText(""); }
        });
    }

    private static <T> T onEdt(Callable<T> operation) {
        try {
            if (SwingUtilities.isEventDispatchThread()) return operation.call();
            FutureTask<T> task = new FutureTask<>(operation);
            SwingUtilities.invokeAndWait(task); return task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("input dialog interrupted");
        } catch (Exception failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("input dialog failed", cause);
        }
    }
}
