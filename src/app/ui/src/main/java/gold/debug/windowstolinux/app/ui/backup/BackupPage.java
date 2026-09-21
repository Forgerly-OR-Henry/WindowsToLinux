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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns local backup validation and isolated candidate preparation. / 持有本地备份校验与隔离候选准备。
 */
public final class BackupPage {
    /**
     * Component or resource identity owning the operation.
     * <p>持有操作的组件或资源身份。
     */
    private final Component owner;
    /**
     * Bound backup application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的备份应用门面协作对象。
     */
    private final BackupApplicationFacade service;
    /**
     * Bound page message presenter collaborator for localized message resolver.
     * <p>处理本地化消息解析器的页面消息展示器协作对象。
     */
    private final PageMessagePresenter messages;
    /**
     * Selections.
     * <p>选择集合。
     */
    private final BackupSelectionPane selections;
    /**
     * Swing control for archive path.
     * <p>归档路径对应的 Swing 控件。
     */
    private final JTextField archivePath = new JTextField();
    /**
     * Swing control for destination path.
     * <p>目的地路径对应的 Swing 控件。
     */
    private final JTextField destinationPath = new JTextField();
    /**
     * Swing control for backup password.
     * <p>备份密码对应的 Swing 控件。
     */
    private final JPasswordField backupPassword = new JPasswordField();
    /**
     * Swing control for master password.
     * <p>主密码对应的 Swing 控件。
     */
    private final JPasswordField masterPassword = new JPasswordField();
    /**
     * Swing control for output.
     * <p>输出对应的 Swing 控件。
     */
    private final JTextArea output = DesktopComponentFactory.outputArea();
    /**
     * Swing control for panel.
     * <p>面板对应的 Swing 控件。
     */
    private final JPanel panel;
    /**
     * Swing control for inspect button.
     * <p>检查按钮对应的 Swing 控件。
     */
    private JButton inspectButton;
    /**
     * Swing control for prepare button.
     * <p>准备按钮对应的 Swing 控件。
     */
    private JButton prepareButton;
    /**
     * Swing control for discard button.
     * <p>丢弃按钮对应的 Swing 控件。
     */
    private JButton discardButton;
    /**
     * Swing control for assess button.
     * <p>评估按钮对应的 Swing 控件。
     */
    private JButton assessButton;
    /**
     * Swing control for prepare secrets button.
     * <p>准备秘密集合按钮对应的 Swing 控件。
     */
    private JButton prepareSecretsButton;
    /**
     * Swing control for create button.
     * <p>创建按钮对应的 Swing 控件。
     */
    private JButton createButton;
    /**
     * Swing control for restore button.
     * <p>恢复按钮对应的 Swing 控件。
     */
    private JButton restoreButton;
    /**
     * Swing control for migrate button.
     * <p>迁移按钮对应的 Swing 控件。
     */
    private JButton migrateButton;
    /**
     * Prepared candidate.
     * <p>已准备候选。
     */
    private PreparedBackupCandidate preparedCandidate;
    /**
     * Task.
     * <p>任务。
     */
    private final javax.swing.JComboBox<String> task = new javax.swing.JComboBox<>();
    /**
     * Swing control for archive row.
     * <p>归档数据行对应的 Swing 控件。
     * <p>destinationRow:
     * Swing control for destination row.
     * <p>目的地数据行对应的 Swing 控件。
     */
    private JPanel archiveRow, destinationRow;

    /**
     * Creates the functional local backup page. / 创建本地备份功能页面。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param messages localized message resolver / 本地化消息解析器
     */
    public BackupPage(Component owner, BackupApplicationFacade service,
                      DesktopComponentFactory components, PageMessagePresenter messages) {
        this.owner = owner;
        this.service = service;
        this.messages = messages;
        selections = new BackupSelectionPane(service, messages, this::showFailure);
        archivePath.setEditable(false);
        destinationPath.setEditable(false);
        panel = createPanel(components);
    }

    /**
     * Returns the page panel. / 返回页面面板。
     *
     * @return the page panel / 页面面板
     */
    public JPanel panel() {
        return panel;
    }

    /**
     * Captures page-owned values. / 捕获页面持有的值。
     *
     * @return constructed or resolved backup page state / 构造或解析得到的备份页面状态
     */
    public BackupPageState captureState() {
        return new BackupPageState(selections.applicationId(), selections.targetServerId(), archivePath.getText(), destinationPath.getText(),
                output.getText(), preparedCandidate, task.getSelectedIndex(), backupPassword.getPassword(), masterPassword.getPassword());
    }

    /**
     * Restores page-owned values. / 恢复页面持有的值。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     */
    public void restoreState(BackupPageState state) {
        selections.restore(state.applicationId(),state.targetServerId());
        archivePath.setText(state.archivePath());
        destinationPath.setText(state.destinationPath());
        output.setText(state.output());
        preparedCandidate = state.preparedCandidate();
        backupPassword.setText(new String(state.backupPassword()));
        masterPassword.setText(new String(state.masterPassword()));
        task.setSelectedIndex(state.task());
        updateTask();
        setBusy(false);
    }

    /**
     * Builds the backup task page with source selection, protected inputs and operation controls.
     * <p>构建备份任务页面，包含源码选择、受保护输入及操作控件。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @return the backup task page with source selection, protected inputs and operation controls / 备份任务页面，包含源码选择、受保护输入及操作控件
     */
    private JPanel createPanel(DesktopComponentFactory components) {
        JPanel page = components.pagePanel();
        var advanced = new gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane(page, components, messages);
        JPanel controls = components.card(new BorderLayout(0, 12));
        controls.add(components.sectionHeading(messages.text("backup.section.title"),
                messages.text("backup.section.description")), BorderLayout.NORTH);

        JPanel inputRows = components.transparent(new java.awt.GridBagLayout());
        task.addItem(messages.text("backup.task.create")); task.addItem(messages.text("backup.task.restore")); task.addItem(messages.text("backup.task.migrate"));
        inputRows.add(task);
        inputRows.add(selections); selections.advanced(advanced);
        assessButton = components.secondaryButton(messages.text("backup.button.assessInputs"));
        assessButton.addActionListener(event -> assessManagedInputs()); advanced.addOption(assessButton);

        JPanel selection = components.transparent(new BorderLayout(8, 0));
        selection.add(archivePath, BorderLayout.CENTER);
        JButton selectButton = components.secondaryButton(messages.text("backup.button.select"));
        selectButton.addActionListener(event -> selectArchive());
        selection.add(selectButton, BorderLayout.EAST);
        inputRows.add(selection); archiveRow = selection;

        JPanel destination = components.transparent(new BorderLayout(8, 0));
        destination.add(new JLabel(messages.text("backup.field.destination")), BorderLayout.WEST);
        destination.add(destinationPath, BorderLayout.CENTER);
        JButton destinationButton = components.secondaryButton(messages.text("backup.button.destination"));
        destinationButton.addActionListener(event -> selectDestination());
        destination.add(destinationButton, BorderLayout.EAST);
        inputRows.add(destination); destinationRow = destination;

        JPanel secretInput = components.transparent(new BorderLayout(8, 0));
        secretInput.add(new JLabel(messages.text("backup.field.password")), BorderLayout.WEST);
        secretInput.add(backupPassword, BorderLayout.CENTER);
        inputRows.add(secretInput);
        var rowConstraints = new java.awt.GridBagConstraints();
        rowConstraints.gridwidth = java.awt.GridBagConstraints.REMAINDER; rowConstraints.weightx = 1;
        rowConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL; rowConstraints.insets = new java.awt.Insets(0,0,8,0);
        for (Component row : inputRows.getComponents()) ((java.awt.GridBagLayout)inputRows.getLayout()).setConstraints(row,rowConstraints);

        JPanel masterInput = components.transparent(new BorderLayout(8, 0));
        masterInput.add(new JLabel(messages.text("backup.field.masterPassword")), BorderLayout.WEST);
        masterInput.add(masterPassword, BorderLayout.CENTER);
        advanced.field("backup.field.masterPassword", masterPassword);
        controls.add(inputRows, BorderLayout.CENTER);

        JPanel actions = actionButtons(components, advanced);
        controls.add(actions, BorderLayout.SOUTH);
        page.add(controls, BorderLayout.NORTH);
        page.add(components.outputCard(messages.text("backup.output.title"),
                messages.text("backup.output.description"), output), BorderLayout.CENTER);
        task.addActionListener(event -> updateTask());
        updateTask();
        setBusy(false);
        return advanced;
    }

    /**
     * Builds the backup inspection, creation and restore action controls.
     * <p>构建备份检查、创建及恢复动作控件。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param advanced advanced / 高级
     * @return the backup inspection, creation and restore action controls / 备份检查、创建及恢复动作控件
     */
    private JPanel actionButtons(DesktopComponentFactory components, gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane advanced) {
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
        advanced.addOption(inspectButton);
        advanced.addOption(prepareButton);
        advanced.addOption(prepareSecretsButton);
        advanced.addOption(discardButton);
        JButton refresh = components.secondaryButton(messages.text("backup.refreshChoices"));
        refresh.addActionListener(event -> selections.refresh()); actions.add(refresh);
        return actions;
    }

    /**
     * Updates task.
     * <p>更新任务。
     */
    private void updateTask() {
        int selected = task.getSelectedIndex();
        selections.task(selected);
        archiveRow.setVisible(selected == 1); destinationRow.setVisible(selected == 0);
        createButton.setVisible(selected == 0); restoreButton.setVisible(selected == 1); migrateButton.setVisible(selected == 2);
        if (panel != null) panel.revalidate();
    }

    /**
     * Submits the reviewed backup restore from the desktop page and displays its classified completion outcome.
     * <p>从桌面页面提交已审阅备份恢复，并展示分类完成结果。
     */
    private void restoreManagedBackup() {
        Path selected = selectedArchive();
        if (selected == null) return;
        String target = selections.targetServerId().trim();
        if (target.isBlank()) {
            output.setText(messages.text("backup.targetRequired"));
            return;
        }
        if (!confirmOperation("backup.restore.confirm", Map.of("target", target, "archive", selected))) return;
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

    /**
     * Collects the selected source and target inputs and starts the controlled offline migration workflow.
     * <p>收集所选源和目标输入，并启动受控离线迁移流程。
     */
    private void prepareOfflineMigration() {
        String application = selections.applicationId().trim();
        String target = selections.targetServerId().trim();
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

    /**
     * Selects caller-selected destination inside the permitted boundary.
     * <p>选择调用方选择的许可边界内目的地。
     */
    private void selectDestination() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showSaveDialog(owner) == JFileChooser.APPROVE_OPTION) {
            Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            destinationPath.setText(selected.toString());
            output.setText(messages.text("backup.destinationSelected", Map.of("path", selected)));
        }
    }

    /**
     * Collects the selected application and backup destination and starts a cancellable desktop backup task.
     * <p>收集所选应用及备份目的地，并启动可取消桌面备份任务。
     */
    private void createManagedBackup() {
        String application = selections.applicationId().trim();
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
        if (!confirmOperation("backup.create.confirm", Map.of("application", application))) return;
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

    /**
     * Confirms pinned or freshly observed host-key fingerprint.
     * <p>确认固定或新近观测的主机密钥指纹。
     *
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @return true when confirms pinned or freshly observed host-key fingerprint, false otherwise / 确认固定或新近观测的主机密钥指纹时为 true，否则为 false
     */
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

    /**
     * Confirms operation.
     * <p>确认操作。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param details details / 详情
     * @return true when confirms operation, false otherwise / 确认操作时为 true，否则为 false
     */
    private boolean confirmOperation(String key, Map<String, ?> details) {
        boolean accepted = JOptionPane.showConfirmDialog(owner, messages.text(key, details),
                messages.text("backup.operation.confirm.title"), JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
        if (!accepted) output.setText(messages.text("auto.cancelled"));
        return accepted;
    }

    /**
     * Checks the selected application's backup prerequisites asynchronously and displays missing input evidence.
     * <p>异步检查所选应用的备份前提条件，并展示缺失输入证据。
     */
    private void assessManagedInputs() {
        String selected = selections.applicationId().trim();
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

    /**
     * Selects source or backup archive descriptor or filesystem path.
     * <p>选择源码或备份归档描述或文件系统路径。
     */
    private void selectArchive() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (!archivePath.getText().isBlank()) {
            try {
                Path selected = Path.of(archivePath.getText()).toAbsolutePath().normalize();
                chooser.setCurrentDirectory(selected.getParent().toFile());
            } catch (InvalidPathException | NullPointerException ignored) {
                // The chooser remains at its safe platform default. / 选择器保持平台的安全默认设置。
            }
        }
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            archivePath.setText(selected.toString());
            output.setText(messages.text("backup.selected", Map.of("path", selected)));
        }
    }

    /**
     * Inspects selected archive.
     * <p>检查已选归档。
     */
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

    /**
     * Prepares selected archive.
     * <p>准备已选归档。
     */
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

    /**
     * Authenticates the selected archive in a worker, closes decrypted secret material and clears the entered password before displaying the candidate.
     * <p>在工作线程认证所选归档，展示候选前关闭解密秘密素材并清空输入密码。
     */
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

    /**
     * Discards prepared candidate.
     * <p>清理已准备候选。
     */
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

    /**
     * Returns selected archive.
     * <p>返回已选归档。
     *
     * @return selected archive; null when no matching value is available / 已选归档；没有匹配值时为 null
     */
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

    /**
     * Formats inspection.
     * <p>格式化检查。
     *
     * @param inspection inspection / 检查
     * @return inspection / 检查
     */
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

    /**
     * Formats candidate.
     * <p>格式化候选。
     *
     * @param candidate candidate / 候选
     * @return candidate / 候选
     */
    private String formatCandidate(PreparedBackupCandidate candidate) {
        BackupArchiveInspection inspection = candidate.inspection();
        return messages.text("backup.prepared", Map.of(
                "application", inspection.applicationId(),
                "path", candidate.candidateRoot(),
                "bytes", candidate.extractedBytes(),
                "sha256", inspection.archiveSha256()));
    }

    /**
     * Formats created.
     * <p>格式化已创建。
     *
     * @param created created / 已创建
     * @return created / 已创建
     */
    private String formatCreated(CreatedBackupArchive created) {
        BackupArchiveInspection inspection = created.inspection();
        return messages.text("backup.created", Map.of(
                "application", inspection.applicationId(), "path", created.archive(),
                "components", inspection.componentCount(), "members", inspection.memberCount(),
                "bytes", inspection.verifiedBytes(), "sha256", inspection.archiveSha256()));
    }

    /**
     * Formats restored.
     * <p>格式化已恢复。
     *
     * @param restored restored / 已恢复
     * @return restored / 已恢复
     */
    private String formatRestored(ManagedRestoreOutcome restored) {
        String warnings = restored.warnings().isEmpty() ? messages.text("backup.inputs.none")
                : restored.warnings().stream().map(messages::text).collect(java.util.stream.Collectors.joining("\n"));
        return messages.text("backup.restored", Map.of(
                "target", restored.targetServerId(), "status", messages.text("backup.restore.status." + restored.restore().status().name().toLowerCase(Locale.ROOT)),
                "control", messages.text("backup.restore.control." + restored.controlState().name().toLowerCase(Locale.ROOT)), "warnings", warnings));
    }

    /**
     * Formats migrated.
     * <p>格式化已迁移。
     *
     * @param migrated migrated / 已迁移
     * @return migrated / 已迁移
     */
    private String formatMigrated(ManagedOfflineMigrationOutcome migrated) {
        String archive = migrated.retainedFinalArchive().map(Path::toString)
                .orElse(messages.text("backup.inputs.none"));
        String warnings = migrated.warnings().isEmpty() ? messages.text("backup.inputs.none")
                : migrated.warnings().stream().map(messages::text).collect(java.util.stream.Collectors.joining("\n"));
        return messages.text("backup.migrated", Map.of(
                "status", messages.text("backup.migration.status." + migrated.migration().status().name().toLowerCase(Locale.ROOT)),
                "sourceStopped", messages.text(migrated.migration().sourceWritesStopped() ? "value.yes" : "value.no"),
                "targetReady", messages.text(migrated.migration().targetCandidateReady() ? "value.yes" : "value.no"),
                "archive", archive, "warnings", warnings));
    }

    /**
     * Formats the typed static assessment.
     * <p>格式化类型化静态评估。
     *
     * @param assessment the typed static assessment / 类型化静态评估
     * @return the typed static assessment / 类型化静态评估
     */
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

    /**
     * Formats authenticated candidate.
     * <p>格式化已认证候选。
     *
     * @param candidate candidate / 候选
     * @param secretRevisionCount secret revision count / 秘密修订数量
     * @return authenticated candidate / 已认证候选
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
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

    /**
     * Localizes the specific missing backup-input reason.
     * <p>本地化具体的备份输入缺失原因。
     *
     * @param reason reason / 原因
     * @return missing input text / 缺失输入文本
     */
    private String missingInput(ManagedBackupInputAssessment.MissingInputType reason) {
        return messages.text("backup.inputs.missing." + reason.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Displays structured failure occurrence retained for safe reporting.
     * <p>展示保留用于安全报告的结构化失败实例。
     *
     * @param exception original exception being classified or translated / 正在分类或转换的原始异常
     */
    private void showFailure(Exception exception) {
        output.setText(messages.text("backup.failed", Map.of("detail", messages.safe(exception))));
        output.setCaretPosition(0);
        setBusy(false);
    }

    /**
     * Updates whether a page action is in progress and conflicting controls must remain disabled.
     * <p>更新页面动作是否正在进行且冲突控件须保持禁用。
     *
     * @param busy whether a page action is in progress and conflicting controls must remain disabled / 页面动作是否正在进行且冲突控件须保持禁用
     */
    private void setBusy(boolean busy) {
        if (panel instanceof gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane advanced) advanced.setBusy(busy);
        task.setEnabled(!busy); selections.busy(busy);
        assessButton.setEnabled(!busy);
        createButton.setEnabled(!busy);
        restoreButton.setEnabled(!busy);
        migrateButton.setEnabled(!busy);
        inspectButton.setEnabled(!busy);
        prepareButton.setEnabled(!busy && preparedCandidate == null);
        prepareSecretsButton.setEnabled(!busy && preparedCandidate == null);
        backupPassword.setEnabled(!busy && preparedCandidate == null);
        masterPassword.setEnabled(!busy);
        discardButton.setEnabled(!busy && preparedCandidate != null);
    }

    /**
     * Pairs a validated restore candidate with the secrets unlocked for that attempt.
     * <p>将已验证恢复候选与为本次尝试解锁的秘密配对。
     *
     * @param candidate candidate / 候选
     * @param secretRevisionCount secret revision count / 秘密修订数量
     */
    private record AuthenticatedCandidate(
            PreparedBackupCandidate candidate,
            int secretRevisionCount
    ) {
    }
}
