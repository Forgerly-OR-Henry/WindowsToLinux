package gold.debug.windowstolinux.app.ui.backup;

import gold.debug.windowstolinux.app.service.backup.BackupArchiveInspection;
import gold.debug.windowstolinux.app.service.backup.ManagedBackupInputAssessment;
import gold.debug.windowstolinux.app.service.backup.PreparedBackupCandidate;
import gold.debug.windowstolinux.app.service.backup.PreparedBackupSecrets;
import gold.debug.windowstolinux.app.service.backup.CreatedBackupArchive;
import gold.debug.windowstolinux.app.service.backup.ManagedRestoreOutcome;
import gold.debug.windowstolinux.app.service.backup.ManagedOfflineMigrationOutcome;
import gold.debug.windowstolinux.app.service.contract.BackupApplicationFacade;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns local backup validation and isolated candidate preparation. / 持有本地备份校验与隔离候选准备。 */
public final class BackupPage {
    private final Component owner;
    private final BackupApplicationFacade service;
    private final PageMessagePresenter messages;
    private final JTextField applicationId = new JTextField();
    private final JTextField targetServerId = new JTextField();
    private final JTextField archivePath = new JTextField();
    private final JTextField destinationPath = new JTextField();
    private final JPasswordField backupPassword = new JPasswordField();
    private final JPasswordField masterPassword = new JPasswordField();
    private final JTextArea output = DesktopComponentFactory.outputArea();
    private final JPanel panel;
    private JButton inspectButton;
    private JButton prepareButton;
    private JButton discardButton;
    private JButton assessButton;
    private JButton prepareSecretsButton;
    private JButton createButton;
    private JButton restoreButton;
    private JButton migrateButton;
    private PreparedBackupCandidate preparedCandidate;

    /** Creates the functional local backup page. / 创建本地备份功能页面。 */
    public BackupPage(Component owner, BackupApplicationFacade service,
                      DesktopComponentFactory components, PageMessagePresenter messages) {
        this.owner = owner;
        this.service = service;
        this.messages = messages;
        archivePath.setEditable(false);
        destinationPath.setEditable(false);
        panel = createPanel(components);
    }

    /** Returns the page panel. / 返回页面面板。 */
    public JPanel panel() {
        return panel;
    }

    /** Captures page-owned values. / 捕获页面持有的值。 */
    public BackupPageState captureState() {
        return new BackupPageState(applicationId.getText(), targetServerId.getText(), archivePath.getText(), destinationPath.getText(),
                output.getText(), preparedCandidate);
    }

    /** Restores page-owned values. / 恢复页面持有的值。 */
    public void restoreState(BackupPageState state) {
        applicationId.setText(state.applicationId());
        targetServerId.setText(state.targetServerId());
        archivePath.setText(state.archivePath());
        destinationPath.setText(state.destinationPath());
        output.setText(state.output());
        preparedCandidate = state.preparedCandidate();
        setBusy(false);
    }

    private JPanel createPanel(DesktopComponentFactory components) {
        JPanel page = components.pagePanel();
        JPanel controls = components.card(new BorderLayout(0, 12));
        controls.add(components.sectionHeading(messages.text("backup.section.title"),
                messages.text("backup.section.description")), BorderLayout.NORTH);

        JPanel inputRows = components.transparent(new GridLayout(6, 1, 0, 8));
        JPanel managedInput = components.transparent(new BorderLayout(8, 0));
        managedInput.add(new JLabel(messages.text("backup.field.applicationId")), BorderLayout.WEST);
        managedInput.add(applicationId, BorderLayout.CENTER);
        assessButton = components.secondaryButton(messages.text("backup.button.assessInputs"));
        assessButton.addActionListener(event -> assessManagedInputs());
        managedInput.add(assessButton, BorderLayout.EAST);
        inputRows.add(managedInput);

        JPanel targetInput = components.transparent(new BorderLayout(8, 0));
        targetInput.add(new JLabel(messages.text("backup.field.targetServerId")), BorderLayout.WEST);
        targetInput.add(targetServerId, BorderLayout.CENTER);
        inputRows.add(targetInput);

        JPanel selection = components.transparent(new BorderLayout(8, 0));
        selection.add(archivePath, BorderLayout.CENTER);
        JButton selectButton = components.secondaryButton(messages.text("backup.button.select"));
        selectButton.addActionListener(event -> selectArchive());
        selection.add(selectButton, BorderLayout.EAST);
        inputRows.add(selection);

        JPanel destination = components.transparent(new BorderLayout(8, 0));
        destination.add(new JLabel(messages.text("backup.field.destination")), BorderLayout.WEST);
        destination.add(destinationPath, BorderLayout.CENTER);
        JButton destinationButton = components.secondaryButton(messages.text("backup.button.destination"));
        destinationButton.addActionListener(event -> selectDestination());
        destination.add(destinationButton, BorderLayout.EAST);
        inputRows.add(destination);

        JPanel secretInput = components.transparent(new BorderLayout(8, 0));
        secretInput.add(new JLabel(messages.text("backup.field.password")), BorderLayout.WEST);
        secretInput.add(backupPassword, BorderLayout.CENTER);
        inputRows.add(secretInput);

        JPanel masterInput = components.transparent(new BorderLayout(8, 0));
        masterInput.add(new JLabel(messages.text("backup.field.masterPassword")), BorderLayout.WEST);
        masterInput.add(masterPassword, BorderLayout.CENTER);
        inputRows.add(masterInput);
        controls.add(inputRows, BorderLayout.CENTER);

        JPanel actions = components.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        inspectButton = components.secondaryButton(messages.text("backup.button.inspect"));
        inspectButton.addActionListener(event -> inspectSelectedArchive());
        prepareButton = components.primaryButton(messages.text("backup.button.prepare"));
        prepareButton.addActionListener(event -> prepareSelectedArchive());
        prepareSecretsButton = components.secondaryButton(messages.text("backup.button.prepareSecrets"));
        prepareSecretsButton.addActionListener(event -> prepareSelectedArchiveWithSecrets());
        discardButton = components.secondaryButton(messages.text("backup.button.discard"));
        discardButton.addActionListener(event -> discardPreparedCandidate());
        createButton = components.primaryButton(messages.text("backup.button.create"));
        createButton.addActionListener(event -> createManagedBackup());
        restoreButton = components.primaryButton(messages.text("backup.button.restore"));
        restoreButton.addActionListener(event -> restoreManagedBackup());
        migrateButton = components.primaryButton(messages.text("backup.button.migrate"));
        migrateButton.addActionListener(event -> prepareOfflineMigration());
        actions.add(createButton);
        actions.add(restoreButton);
        actions.add(migrateButton);
        actions.add(inspectButton);
        actions.add(prepareButton);
        actions.add(prepareSecretsButton);
        actions.add(discardButton);
        controls.add(actions, BorderLayout.SOUTH);
        page.add(controls, BorderLayout.NORTH);
        page.add(components.outputCard(messages.text("backup.output.title"),
                messages.text("backup.output.description"), output), BorderLayout.CENTER);
        setBusy(false);
        return page;
    }

    private void restoreManagedBackup() {
        Path selected = selectedArchive();
        if (selected == null) return;
        String target = targetServerId.getText().trim();
        if (target.isBlank()) {
            output.setText(messages.text("backup.targetRequired"));
            return;
        }
        char[] independent = backupPassword.getPassword();
        char[] master = masterPassword.getPassword();
        backupPassword.setText(""); masterPassword.setText("");
        if (independent.length == 0) {
            Arrays.fill(independent, '\0'); Arrays.fill(master, '\0');
            output.setText(messages.text("backup.secretPasswordRequired"));
            return;
        }
        setBusy(true); output.setText(messages.text("backup.restoring"));
        DesktopTaskExecutor.run(() -> {
            try {
                return service.restoreManagedBackup(selected, target, independent, master, this::confirmFingerprint);
            } finally {
                Arrays.fill(independent, '\0'); Arrays.fill(master, '\0');
            }
        }, restored -> {
            output.setText(formatRestored(restored)); output.setCaretPosition(0); setBusy(false);
        }, this::showFailure);
    }

    private void prepareOfflineMigration() {
        String application = applicationId.getText().trim();
        String target = targetServerId.getText().trim();
        if (application.isBlank()) {
            output.setText(messages.text("backup.inputs.applicationRequired")); return;
        }
        if (target.isBlank()) {
            output.setText(messages.text("backup.targetRequired")); return;
        }
        if (JOptionPane.showConfirmDialog(owner, messages.text("backup.migration.confirm"),
                messages.text("backup.migration.confirm.title"), JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            output.setText(messages.text("backup.migration.notApproved")); return;
        }
        char[] independent = backupPassword.getPassword();
        char[] master = masterPassword.getPassword();
        backupPassword.setText(""); masterPassword.setText("");
        if (independent.length == 0) {
            Arrays.fill(independent, '\0'); Arrays.fill(master, '\0');
            output.setText(messages.text("backup.secretPasswordRequired")); return;
        }
        setBusy(true); output.setText(messages.text("backup.migrating"));
        DesktopTaskExecutor.run(() -> {
            try {
                return service.prepareManagedOfflineMigration(application, target, independent, master,
                        true, this::confirmFingerprint);
            } finally {
                Arrays.fill(independent, '\0'); Arrays.fill(master, '\0');
            }
        }, migrated -> {
            output.setText(formatMigrated(migrated)); output.setCaretPosition(0); setBusy(false);
        }, this::showFailure);
    }

    private void selectDestination() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showSaveDialog(owner) == JFileChooser.APPROVE_OPTION) {
            Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            destinationPath.setText(selected.toString());
            output.setText(messages.text("backup.destinationSelected", Map.of("path", selected)));
        }
    }

    private void createManagedBackup() {
        String application = applicationId.getText().trim();
        Path destination;
        if (application.isBlank()) {
            output.setText(messages.text("backup.inputs.applicationRequired"));
            return;
        }
        try {
            destination = destinationPath.getText().isBlank() ? null
                    : Path.of(destinationPath.getText()).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            destination = null;
        }
        if (destination == null) {
            output.setText(messages.text("backup.destinationRequired"));
            return;
        }
        char[] independent = backupPassword.getPassword();
        char[] master = masterPassword.getPassword();
        backupPassword.setText("");
        masterPassword.setText("");
        if (independent.length == 0) {
            Arrays.fill(independent, '\0'); Arrays.fill(master, '\0');
            output.setText(messages.text("backup.secretPasswordRequired"));
            return;
        }
        setBusy(true);
        output.setText(messages.text("backup.creating"));
        Path selectedDestination = destination;
        DesktopTaskExecutor.run(() -> {
            try {
                return service.createManagedBackup(application, selectedDestination, independent, master,
                        this::confirmFingerprint);
            } finally {
                Arrays.fill(independent, '\0'); Arrays.fill(master, '\0');
            }
        }, created -> {
            output.setText(formatCreated(created));
            output.setCaretPosition(0);
            setBusy(false);
        }, this::showFailure);
    }

    private boolean confirmFingerprint(String fingerprint) {
        AtomicBoolean accepted = new AtomicBoolean(false);
        try {
            SwingUtilities.invokeAndWait(() -> accepted.set(JOptionPane.showConfirmDialog(owner,
                    messages.text("fingerprint.confirm", Map.of("fingerprint", fingerprint)),
                    messages.text("fingerprint.confirm.title"), JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION));
        } catch (Exception ignored) {
            // A failed or interrupted confirmation must reject host trust. / 确认失败或中断时必须拒绝主机信任。
            return false;
        }
        return accepted.get();
    }

    private void assessManagedInputs() {
        String selected = applicationId.getText().trim();
        if (selected.isBlank()) {
            output.setText(messages.text("backup.inputs.applicationRequired"));
            return;
        }
        setBusy(true);
        output.setText(messages.text("backup.inputs.assessing"));
        DesktopTaskExecutor.run(() -> service.assessManagedBackupInputs(selected), assessment -> {
            output.setText(formatAssessment(assessment));
            output.setCaretPosition(0);
            setBusy(false);
        }, this::showFailure);
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
            preparedCandidate = candidate;
            output.setText(formatCandidate(candidate));
            output.setCaretPosition(0);
            setBusy(false);
        }, this::showFailure);
    }

    private void prepareSelectedArchiveWithSecrets() {
        Path selected = selectedArchive();
        if (selected == null) return;
        char[] password = backupPassword.getPassword();
        backupPassword.setText("");
        if (password.length == 0) {
            Arrays.fill(password, '\0');
            output.setText(messages.text("backup.secretPasswordRequired"));
            return;
        }
        setBusy(true);
        output.setText(messages.text("backup.preparingSecrets"));
        DesktopTaskExecutor.run(() -> {
            try (PreparedBackupSecrets prepared = service.prepareBackupCandidateWithSecrets(selected, password)) {
                return new AuthenticatedCandidate(
                        prepared.candidate(), prepared.secrets().revisions().size());
            } finally {
                Arrays.fill(password, '\0');
            }
        }, authenticated -> {
            preparedCandidate = authenticated.candidate();
            output.setText(formatAuthenticatedCandidate(
                    authenticated.candidate(), authenticated.secretRevisionCount()));
            output.setCaretPosition(0);
            setBusy(false);
        }, this::showFailure);
    }

    private void discardPreparedCandidate() {
        PreparedBackupCandidate candidate = preparedCandidate;
        if (candidate == null) return;
        setBusy(true);
        output.setText(messages.text("backup.discarding", Map.of("path", candidate.candidateRoot())));
        DesktopTaskExecutor.run(() -> {
            service.discardBackupCandidate(candidate);
            return candidate;
        }, discarded -> {
            preparedCandidate = null;
            output.setText(messages.text("backup.discarded", Map.of("path", discarded.candidateRoot())));
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

    private String formatCreated(CreatedBackupArchive created) {
        BackupArchiveInspection inspection = created.inspection();
        return messages.text("backup.created", Map.of(
                "application", inspection.applicationId(), "path", created.archive(),
                "components", inspection.componentCount(), "members", inspection.memberCount(),
                "bytes", inspection.verifiedBytes(), "sha256", inspection.archiveSha256()));
    }

    private String formatRestored(ManagedRestoreOutcome restored) {
        String warnings = restored.warnings().isEmpty() ? messages.text("backup.inputs.none")
                : String.join("\n", restored.warnings());
        return messages.text("backup.restored", Map.of(
                "target", restored.targetServerId(), "status", restored.restore().status(),
                "control", restored.controlState(), "warnings", warnings));
    }

    private String formatMigrated(ManagedOfflineMigrationOutcome migrated) {
        String archive = migrated.retainedFinalArchive().map(Path::toString)
                .orElse(messages.text("backup.inputs.none"));
        String warnings = migrated.warnings().isEmpty() ? messages.text("backup.inputs.none")
                : String.join("\n", migrated.warnings());
        return messages.text("backup.migrated", Map.of(
                "status", migrated.migration().status(),
                "sourceStopped", migrated.migration().sourceWritesStopped(),
                "targetReady", migrated.migration().targetCandidateReady(),
                "archive", archive, "warnings", warnings));
    }

    String formatAssessment(ManagedBackupInputAssessment assessment) {
        String components = assessment.componentIds().isEmpty()
                ? messages.text("backup.inputs.none") : String.join(", ", assessment.componentIds());
        String releases = assessment.currentReleaseIdentities().entrySet().stream()
                .map(entry -> messages.text("backup.inputs.release", Map.of(
                        "component", entry.getKey(), "release", entry.getValue())))
                .reduce((left, right) -> left + "\n" + right)
                .orElse(messages.text("backup.inputs.none"));
        java.util.List<String> missing = new java.util.ArrayList<>();
        assessment.applicationMissingInputs().forEach(reason -> missing.add(messages.text(
                "backup.inputs.applicationBlocker", Map.of("reason", missingInput(reason)))));
        assessment.componentMissingInputs().forEach((component, reasons) -> missing.add(messages.text(
                "backup.inputs.componentBlocker", Map.of("component", component,
                        "reasons", reasons.stream().map(this::missingInput)
                                .reduce((left, right) -> left + ", " + right).orElseThrow()))));
        String blockers = missing.isEmpty()
                ? messages.text("backup.inputs.none") : String.join("\n", missing);
        String status = messages.text(assessment.persistedInputsComplete()
                ? "backup.inputs.status.complete" : "backup.inputs.status.incomplete");
        return messages.text("backup.inputs.result", Map.of(
                "application", assessment.applicationId(),
                "status", status,
                "components", components,
                "releases", releases,
                "missing", blockers));
    }

    String formatAuthenticatedCandidate(PreparedBackupCandidate candidate, int secretRevisionCount) {
        if (secretRevisionCount < 1) {
            throw new IllegalArgumentException("authenticated secret revision count must be positive");
        }
        return messages.text("backup.preparedSecrets", Map.of(
                "application", candidate.inspection().applicationId(),
                "path", candidate.candidateRoot(),
                "count", secretRevisionCount,
                "sha256", candidate.inspection().archiveSha256()));
    }

    private String missingInput(ManagedBackupInputAssessment.MissingInputType reason) {
        return messages.text("backup.inputs.missing." + reason.name().toLowerCase(Locale.ROOT));
    }

    private void showFailure(Exception exception) {
        output.setText(messages.text("backup.failed", Map.of("detail", messages.safe(exception))));
        output.setCaretPosition(0);
        setBusy(false);
    }

    private void setBusy(boolean busy) {
        assessButton.setEnabled(!busy);
        createButton.setEnabled(!busy);
        restoreButton.setEnabled(!busy);
        migrateButton.setEnabled(!busy);
        targetServerId.setEnabled(!busy);
        inspectButton.setEnabled(!busy);
        prepareButton.setEnabled(!busy && preparedCandidate == null);
        prepareSecretsButton.setEnabled(!busy && preparedCandidate == null);
        backupPassword.setEnabled(!busy && preparedCandidate == null);
        masterPassword.setEnabled(!busy);
        discardButton.setEnabled(!busy && preparedCandidate != null);
    }

    private record AuthenticatedCandidate(
            PreparedBackupCandidate candidate,
            int secretRevisionCount
    ) {
    }
}
