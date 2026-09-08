package gold.debug.windowstolinux.app.ui.backup;

import gold.debug.windowstolinux.app.service.contract.BackupApplicationFacade;
import gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/** Owns saved backup/migration choices and keeps their exact identifiers in the advanced drawer. */
final class BackupSelectionPane extends JPanel {
    private final BackupApplicationFacade service;
    private final Consumer<Exception> failure;
    private final JTextField applicationId = new JTextField();
    private final JTextField targetServerId = new JTextField();
    private final JComboBox<SavedChoice> applications = new JComboBox<>();
    private final JComboBox<SavedChoice> servers = new JComboBox<>();
    private final JPanel applicationRow;
    private final JPanel serverRow;
    private boolean loading;

    BackupSelectionPane(BackupApplicationFacade service, PageMessagePresenter messages, Consumer<Exception> failure) {
        super(new GridBagLayout()); this.service = service; this.failure = failure; setOpaque(false);
        applicationRow = row(messages.text("backup.field.application"), applications);
        serverRow = row(messages.text("backup.field.server"), servers);
        var constraints = new GridBagConstraints(); constraints.gridwidth = GridBagConstraints.REMAINDER;
        constraints.weightx = 1; constraints.fill = GridBagConstraints.HORIZONTAL;
        add(applicationRow, constraints); add(serverRow, constraints);
        applications.addActionListener(event -> select(applications, applicationId));
        servers.addActionListener(event -> select(servers, targetServerId));
        addHierarchyListener(event -> { if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) refresh(); });
    }

    String applicationId() { return applicationId.getText(); }
    String targetServerId() { return targetServerId.getText(); }
    void restore(String application, String server) { applicationId.setText(application); targetServerId.setText(server); }
    void advanced(AdvancedOptionsPane advanced) {
        advanced.field("backup.field.applicationId", applicationId);
        advanced.field("backup.field.targetServerId", targetServerId);
    }
    void task(int selected) { applicationRow.setVisible(selected != 1); serverRow.setVisible(selected != 0); revalidate(); }
    void busy(boolean busy) {
        applications.setEnabled(!busy); servers.setEnabled(!busy);
        applicationId.setEnabled(!busy); targetServerId.setEnabled(!busy);
    }
    void refresh() {
        if (service == null || loading) return;
        loading = true;
        DesktopTaskExecutor.run(() -> new SavedLists(service.listManagedApplicationSummaries().stream()
                .map(value -> new SavedChoice(value.application().id(), value.application().id() + "  ·  " + value.application().server().host())).toList(),
                service.listServerProfiles().stream().map(value -> new SavedChoice(value.id(),value.id() + "  ·  " + value.host())).toList()), lists -> {
            fill(applications, lists.applications(), applicationId); fill(servers, lists.servers(), targetServerId); loading = false;
        }, error -> { loading = false; failure.accept(error); });
    }
    private void select(JComboBox<SavedChoice> choices, JTextField field) {
        if (!loading && choices.getSelectedItem() instanceof SavedChoice choice) field.setText(choice.id());
    }
    private void fill(JComboBox<SavedChoice> choices, List<SavedChoice> values, JTextField field) {
        String selected = field.getText(); choices.removeAllItems(); values.forEach(choices::addItem); choices.setSelectedIndex(-1);
        values.stream().filter(value -> value.id().equals(selected)).findFirst().ifPresent(choices::setSelectedItem);
        if (selected.isBlank() && !values.isEmpty()) { choices.setSelectedIndex(0); field.setText(values.getFirst().id()); }
    }
    private static JPanel row(String label, JComponent input) {
        JPanel row = new JPanel(new BorderLayout(8,0)); row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(0,0,8,0));
        JLabel title = new JLabel(label); title.setLabelFor(input); row.add(title,BorderLayout.WEST); row.add(input,BorderLayout.CENTER);
        return row;
    }
    private record SavedChoice(String id, String label) { @Override public String toString() { return label; } }
    private record SavedLists(List<SavedChoice> applications, List<SavedChoice> servers) { }
}
