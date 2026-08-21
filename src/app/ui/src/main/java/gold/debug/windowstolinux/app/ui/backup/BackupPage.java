package gold.debug.windowstolinux.app.ui.backup;

import gold.debug.windowstolinux.app.service.backup.BackupArchiveInspection;
import gold.debug.windowstolinux.app.service.backup.PreparedBackupCandidate;
import gold.debug.windowstolinux.app.service.contract.BackupApplicationFacade;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Owns local backup validation and isolated candidate preparation. / 持有本地备份校验与隔离候选准备。 */
public final class BackupPage {
    private final Component owner;
    private final BackupApplicationFacade service;
    private final PageMessagePresenter messages;
    private final JTextField archivePath = new JTextField();
    private final JTextArea output = DesktopComponentFactory.outputArea();
    private final JPanel panel;
    private JButton inspectButton;
    private JButton prepareButton;

    /** Creates the functional local backup page. / 创建本地备份功能页面。 */
    public BackupPage(Component owner, BackupApplicationFacade service,
                      DesktopComponentFactory components, PageMessagePresenter messages) {
        this.owner = owner;
        this.service = service;
        this.messages = messages;
        archivePath.setEditable(false);
        panel = createPanel(components);
    }

    /** Returns the page panel. / 返回页面面板。 */
    public JPanel panel() {
        return panel;
    }

    /** Captures page-owned values. / 捕获页面持有的值。 */
    public BackupPageState captureState() {
        return new BackupPageState(archivePath.getText(), output.getText());
    }

    /** Restores page-owned values. / 恢复页面持有的值。 */
    public void restoreState(BackupPageState state) {
        archivePath.setText(state.archivePath());
        output.setText(state.output());
    }

    private JPanel createPanel(DesktopComponentFactory components) {
        JPanel page = components.pagePanel();
        JPanel controls = components.card(new BorderLayout(0, 12));
        controls.add(components.sectionHeading(messages.text("backup.section.title"),
                messages.text("backup.section.description")), BorderLayout.NORTH);

        JPanel selection = components.transparent(new BorderLayout(8, 0));
        selection.add(archivePath, BorderLayout.CENTER);
        JButton selectButton = components.secondaryButton(messages.text("backup.button.select"));
        selectButton.addActionListener(event -> selectArchive());
        selection.add(selectButton, BorderLayout.EAST);
        controls.add(selection, BorderLayout.CENTER);

        JPanel actions = components.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        inspectButton = components.secondaryButton(messages.text("backup.button.inspect"));
        inspectButton.addActionListener(event -> inspectSelectedArchive());
        prepareButton = components.primaryButton(messages.text("backup.button.prepare"));
        prepareButton.addActionListener(event -> prepareSelectedArchive());
        actions.add(inspectButton);
        actions.add(prepareButton);
        controls.add(actions, BorderLayout.SOUTH);
        page.add(controls, BorderLayout.NORTH);
        page.add(components.outputCard(messages.text("backup.output.title"),
                messages.text("backup.output.description"), output), BorderLayout.CENTER);
        return page;
    }

    private void selectArchive() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (!archivePath.getText().isBlank()) {
            try {
                Path selected = Path.of(archivePath.getText()).toAbsolutePath().normalize();
                chooser.setCurrentDirectory(selected.getParent().toFile());
            } catch (InvalidPathException | NullPointerException ignored) {
                // The chooser remains at its safe platform default.
            }
        }
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            archivePath.setText(selected.toString());
            output.setText(messages.text("backup.selected", Map.of("path", selected)));
        }
    }

    private void inspectSelectedArchive() {
        Path selected = selectedArchive();
        if (selected == null) return;
        setBusy(true);
        output.setText(messages.text("backup.inspecting"));
        DesktopTaskExecutor.run(() -> service.inspectBackup(selected), inspection -> {
            output.setText(formatInspection(inspection));
            output.setCaretPosition(0);
            setBusy(false);
        }, this::showFailure);
    }

    private void prepareSelectedArchive() {
        Path selected = selectedArchive();
        if (selected == null) return;
        setBusy(true);
        output.setText(messages.text("backup.preparing"));
        DesktopTaskExecutor.run(() -> service.prepareBackupCandidate(selected), candidate -> {
            output.setText(formatCandidate(candidate));
            output.setCaretPosition(0);
            setBusy(false);
        }, this::showFailure);
    }

    private Path selectedArchive() {
        if (archivePath.getText().isBlank()) {
            output.setText(messages.text("backup.selectRequired"));
            return null;
        }
        try {
            return Path.of(archivePath.getText()).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            output.setText(messages.text("backup.selectRequired"));
            return null;
        }
    }

    private String formatInspection(BackupArchiveInspection inspection) {
        String provenance = messages.text("backup.provenance."
                + inspection.provenanceStatus().name().toLowerCase(Locale.ROOT));
        return messages.text("backup.inspection", Map.of(
                "application", inspection.applicationId(),
                "schema", inspection.schemaVersion(),
                "created", inspection.createdAtUtc(),
                "components", inspection.componentCount(),
                "members", inspection.memberCount(),
                "bytes", inspection.verifiedBytes(),
                "sha256", inspection.archiveSha256(),
                "provenance", provenance));
    }

    private String formatCandidate(PreparedBackupCandidate candidate) {
        BackupArchiveInspection inspection = candidate.inspection();
        return messages.text("backup.prepared", Map.of(
                "application", inspection.applicationId(),
                "path", candidate.candidateRoot(),
                "bytes", candidate.extractedBytes(),
                "sha256", inspection.archiveSha256()));
    }

    private void showFailure(Exception exception) {
        output.setText(messages.text("backup.failed", Map.of("detail", messages.safe(exception))));
        output.setCaretPosition(0);
        setBusy(false);
    }

    private void setBusy(boolean busy) {
        inspectButton.setEnabled(!busy);
        prepareButton.setEnabled(!busy);
    }
}
