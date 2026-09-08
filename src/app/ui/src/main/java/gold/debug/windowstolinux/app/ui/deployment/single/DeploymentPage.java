package gold.debug.windowstolinux.app.ui.deployment.single;

import gold.debug.windowstolinux.app.service.contract.definition.*;

import gold.debug.windowstolinux.app.ui.deployment.ReviewContext;
import gold.debug.windowstolinux.app.service.deployment.automatic.DeploymentRuntimeParser;

import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.deployment.automatic.*;
import gold.debug.windowstolinux.app.service.deployment.single.DeploymentHandoff;
import gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane;
import gold.debug.windowstolinux.app.ui.server.ServerSelectionPane;
import javax.swing.*;
import java.awt.Dimension;
import java.awt.Desktop;
import java.util.LinkedHashMap;
import java.util.Arrays;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.app.service.deployment.single.DeploymentOutcome;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.server.ServerContext;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.git.GitRemote;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;
import gold.debug.windowstolinux.shared.model.project.DeploymentSupportLevel;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

/** Owns source selection, review state, deployment forms, and the complete reviewed deployment workflow. / 持有源码选择、审阅状态、部署表单与完整经审阅部署流程。 */
public final class DeploymentPage implements ReviewContext {
    private final JFrame owner;
    private final AutomaticDeploymentApplicationFacade service;
    private final ServerContext serverContext;
    private final PageMessagePresenter messages;
    private final Consumer<String> applicationSelection;
    private final Runnable openServers;
    private final DeploymentAnalysisPresenter presenter;
    private final DeploymentForm form;
    private final JTextArea output = DesktopComponentFactory.outputArea();
    private final JPanel panel;
    private ReviewedSourcePreparation reviewedPreparation;
    private final JTextField sourcePath = new JTextField();
    private final JTextField gitAddress = new JTextField();
    private final JTextField gitReference = new JTextField();
    private final JComboBox<String> sourceMode = new JComboBox<>();
    private final JComboBox<String> gitKind = new JComboBox<>();
    private final JCheckBox detectType = new JCheckBox();
    private final JPanel handoffs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JScrollPane handoffScroll = new JScrollPane(handoffs,ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    private ServerSelectionPane serverSelection;
    private JButton start;
    private boolean busy;
    private JPanel sourceSelection;
    private final Map<String, DeploymentHandoff> completedHandoffs = new LinkedHashMap<>();
    private DesktopComponentFactory components;

    /** Creates the stateful deployment controller. / 创建有状态部署控制器。 */
    public DeploymentPage(JFrame owner, AutomaticDeploymentApplicationFacade service, ServerContext serverContext,
                          DesktopComponentFactory components, PageMessagePresenter messages, Runnable openServers,
                          Consumer<String> applicationSelection) {
        this.owner = owner;
        this.service = service;
        this.serverContext = serverContext;
        this.messages = messages;
        this.openServers = openServers;
        this.applicationSelection = applicationSelection;
        presenter = new DeploymentAnalysisPresenter(messages);
        form = new DeploymentForm(messages, () -> reviewedPreparation = null);
        panel = createPanel(components);
    }

    /** Returns the page panel. / 返回页面面板。 */
    public JPanel panel() { return panel; }
    /** Performs the {@code reviewedPreparation} operation. / 执行 {@code reviewedPreparation} 操作。 */
    @Override public Optional<ReviewedSourcePreparation> reviewedPreparation() { return Optional.ofNullable(reviewedPreparation); }

    /** Captures all unsaved deployment and review state. / 捕获全部未保存部署与审阅状态。 */
    public DeploymentPageState captureState() {
        return form.capture(output.getText(), reviewedPreparation);
    }

    /** Restores all unsaved deployment and review state. / 恢复全部未保存部署与审阅状态。 */
    public void restoreState(DeploymentPageState state) {
        form.restore(state);
        output.setText(state.output()); reviewedPreparation = state.preparation();
    }

    private JPanel createPanel(DesktopComponentFactory c) {
        components = c;
        JPanel page = c.pagePanel();
        AdvancedOptionsPane advanced = new AdvancedOptionsPane(page, c, messages);
        JPanel selection = c.transparent(new BorderLayout(12, 0));
        JPanel source = c.card(new BorderLayout(0, 12));
        source.add(c.sectionHeading(messages.text("auto.source"), messages.text("auto.source.hint")), BorderLayout.NORTH);
        sourceMode.addItem(messages.text("auto.local")); sourceMode.addItem(messages.text("auto.git"));
        JPanel sourceFields = c.transparent(new BorderLayout(0, 10));
        sourceFields.add(sourceMode, BorderLayout.NORTH);
        JPanel sourceDeck = c.transparent(new java.awt.CardLayout());
        JPanel local = c.transparent(new BorderLayout(0, 8));
        sourcePath.setEditable(false);
        JButton browse = c.secondaryButton(messages.text("auto.browse"));
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) sourcePath.setText(chooser.getSelectedFile().toString());
        });
        local.add(sourcePath, BorderLayout.CENTER); local.add(browse, BorderLayout.EAST);
        sourceDeck.add(local, "local"); sourceDeck.add(gitAddress, "git");
        sourceMode.addActionListener(event -> ((java.awt.CardLayout) sourceDeck.getLayout()).show(sourceDeck,
                sourceMode.getSelectedIndex() == 0 ? "local" : "git"));
        sourceFields.add(sourceDeck, BorderLayout.CENTER); source.add(sourceFields, BorderLayout.CENTER);
        JPanel target = c.card(new BorderLayout(0, 12));
        target.add(c.sectionHeading(messages.text("auto.server"), messages.text("auto.server.hint")), BorderLayout.NORTH);
        serverSelection = new ServerSelectionPane(service, c, messages, serverContext::selectProfile);
        target.add(serverSelection, BorderLayout.CENTER);
        JPanel columns = sourceAndTarget(c, source, target);
        selection.add(columns, BorderLayout.CENTER); sourceSelection = selection;
        JPanel top = c.transparent(new BorderLayout(0, 12)); top.add(selection, BorderLayout.CENTER);
        start = c.primaryButton(messages.text("auto.start")); start.setPreferredSize(new Dimension(160, 36));
        start.addActionListener(event -> startAutomatic());
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0)); actions.add(start);
        top.add(actions, BorderLayout.SOUTH); page.add(top, BorderLayout.NORTH);
        output.setText(messages.text("auto.idle"));
        JPanel log = c.outputCard(messages.text("auto.log"), messages.text("auto.log.hint"), output);
        handoffs.setOpaque(false); handoffScroll.setVisible(false); handoffScroll.setPreferredSize(new Dimension(0,46));
        log.add(handoffScroll, BorderLayout.SOUTH); page.add(log, BorderLayout.CENTER);
        configureAdvanced(advanced, c);
        return advanced;
    }

    private JPanel sourceAndTarget(DesktopComponentFactory c, JPanel source, JPanel target) {
        JPanel columns = c.transparent(new java.awt.GridBagLayout());
        var constraints = new java.awt.GridBagConstraints();
        constraints.fill = java.awt.GridBagConstraints.BOTH; constraints.weightx = 1; constraints.weighty = 1;
        columns.add(source, constraints);
        JLabel direction = new JLabel(messages.text("auto.direction"), SwingConstants.CENTER);
        direction.setPreferredSize(new Dimension(28, 28));
        constraints.weightx = 0; columns.add(direction, constraints);
        constraints.weightx = 1; columns.add(target, constraints);
        return columns;
    }

    private void configureAdvanced(AdvancedOptionsPane advanced, DesktopComponentFactory c) {
        detectType.setSelected(true);
        advanced.field("auto.detectType", detectType);
        gitKind.addItem(messages.text("git.reference.kind.branch")); gitKind.addItem(messages.text("git.reference.kind.tag"));
        gitKind.addItem(messages.text("git.reference.kind.commit"));
        advanced.field("auto.gitKind", gitKind); advanced.field("auto.gitReference", gitReference);
        advanced.field("field.projectType", form.projectType);
        advanced.field("field.healthMode", form.healthMode);
        advanced.field("field.healthEndpoint", form.healthEndpoint);
        advanced.field("field.expectedStatus", form.expectedStatus);
        advanced.field("field.timeout", form.timeout);
        advanced.field("field.tcpStability", form.stability);
        advanced.field("field.userAccessUrl", form.accessUrl);
        advanced.field("field.runtimePrimary", form.runtimePrimary);
        advanced.field("field.runtimeSecondary", form.runtimeSecondary);
        advanced.field("field.runtimeVersion", form.runtimeVersion);
        advanced.field("field.jvmArguments", form.jvmArguments);
        advanced.field("field.applicationArguments", form.applicationArguments);
        advanced.field("field.containerEngine", form.containerEngine);
        advanced.field("field.containerPorts", form.containerPorts);
        advanced.field("field.containerVolumes", form.containerVolumes);
        advanced.field("field.configurationEntries", form.configurationEntries);
        advanced.field("field.databaseReviewMode", form.databaseMode);
        advanced.field("field.databaseDetails", form.databaseDetails);
        advanced.field("field.secretReferences", form.secretReferences);
        advanced.field("rootBuild", form.rootBuild);
        advanced.field("experimentalAdapterRisk", form.experimentalAdapterRisk);
        JButton secret = c.secondaryButton(messages.text("button.saveSecretRevision"));
        secret.addActionListener(event -> saveSecret()); advanced.field("field.secretRevision", secret);
        JButton manual = c.secondaryButton(messages.text("auto.components"));
        manual.addActionListener(event -> openServers.run()); advanced.addOption(manual);
    }

    /** Captures source selection independently from typed advanced inputs. */
    public Map<String, String> captureSelection() {
        return Map.of("sourceMode", Integer.toString(sourceMode.getSelectedIndex()), "source", sourcePath.getText(),
                "git", gitAddress.getText(), "reference", gitReference.getText(), "kind", Integer.toString(gitKind.getSelectedIndex()),
                "detect", Boolean.toString(detectType.isSelected()), "server", serverSelection.profile() == null ? "" : serverSelection.profile().id());
    }

    /** Restores source selection after an appearance change. */
    public void restoreSelection(Map<String, String> state) {
        if (state.isEmpty()) return;
        sourceMode.setSelectedIndex(Integer.parseInt(state.get("sourceMode"))); sourcePath.setText(state.get("source"));
        gitAddress.setText(state.get("git")); gitReference.setText(state.get("reference"));
        gitKind.setSelectedIndex(Integer.parseInt(state.get("kind"))); detectType.setSelected(Boolean.parseBoolean(state.get("detect")));
        serverSelection.select(state.get("server"));
    }

    /** Reports active work so an appearance rebuild cannot detach a running operation. */
    public boolean busy() { return busy; }

    private void startAutomatic() {
        if (busy) return;
        try {
            ServerProfile server = serverSelection.profile();
            if (server == null || (sourceMode.getSelectedIndex() == 0 ? sourcePath.getText().isBlank() : gitAddress.getText().isBlank())) {
                append(messages.text("auto.selectRequired")); return;
            }
            Optional<Path> directory = sourceMode.getSelectedIndex() == 0 ? Optional.of(Path.of(sourcePath.getText())) : Optional.empty();
            Optional<GitSourceRequest> git = Optional.empty();
            if (directory.isEmpty()) {
                GitRemote remote = GitRemote.parse(gitAddress.getText().trim());
                var reference = gitReference.getText().isBlank() ? new gold.debug.windowstolinux.shared.git.GitReference.DefaultBranch()
                        : DeploymentRuntimeParser.gitReference(gitKind.getSelectedIndex(), gitReference.getText().trim());
                git = Optional.of(new GitSourceRequest(remote, reference, java.util.Set.of(remote.host().orElseThrow()),
                        4L * 1024 * 1024 * 1024, false));
            }
            Map<String, String> values = new LinkedHashMap<>();
            if (!detectType.isSelected()) values.put("type", form.projectType().name());
            values.put("primary", form.runtimePrimary.getText()); values.put("secondary", form.runtimeSecondary.getText());
            values.put("version", form.runtimeVersion.getText()); values.put("configuration", form.configurationEntries.getText());
            values.put("secrets", form.secretReferences.getText()); values.put("databaseDetails", form.databaseDetails.getText());
            if (form.databaseMode.getSelectedItem() != DeploymentRuntimeParser.DatabaseReviewMode.UNREVIEWED)
                values.put("databaseMode", ((DeploymentRuntimeParser.DatabaseReviewMode) form.databaseMode.getSelectedItem()).name());
            values.put("expectedStatus", form.expectedStatus.getText()); values.put("timeout", form.timeout.getText());
            values.put("stability", form.stability.getText()); values.put("accessUrl", form.accessUrl.getText());
            values.put("rootBuild", Boolean.toString(form.rootBuild.isSelected()));
            values.put("experimentalAdapterRisk", Boolean.toString(form.experimentalAdapterRisk.isSelected()));
            values.put("jvmArguments", form.jvmArguments.getText()); values.put("arguments", form.applicationArguments.getText());
            values.put("ports", form.containerPorts.getText()); values.put("volumes", form.containerVolumes.getText());
            if (form.containerEngine.getSelectedItem() != null) values.put("containerEngine", form.containerEngine.getSelectedItem().toString());
            form.automaticHealthInputs(values);
            AutomaticDeploymentRequest request = new AutomaticDeploymentRequest(directory, git, server, values);
            char[] entered = serverContext.masterPassword();
            if (server.credentialMode() == CredentialStorageMode.MASTER_PASSWORD && entered.length == 0)
                entered = new DeploymentInputDialog(owner, service, components, messages, () -> new char[0]).requestSecret("field.masterPassword");
            final char[] master = entered;
            DeploymentInputDialog interaction = new DeploymentInputDialog(owner, service, components, messages, master::clone);
            busy = true; start.setEnabled(false); start.setText(messages.text("auto.running"));
            ((AdvancedOptionsPane) panel).setBusy(true);
            handoffs.removeAll(); handoffScroll.setVisible(false); completedHandoffs.clear(); output.setText("");
            DesktopTaskExecutor.run(() -> {
                try { return service.deployAutomatically(request, master.clone(), interaction, serverContext::confirmFingerprint,
                        message -> SwingUtilities.invokeLater(() -> append(messages.catalog().text(message)))); }
                finally { Arrays.fill(master, '\0'); }
            }, result -> {
                finish(); append(messages.text("deployment.status." + result.status().name().toLowerCase(Locale.ROOT)));
                if (result.status() == DeploymentStatus.SUCCEEDED) {
                    applicationSelection.accept(result.applicationId());
                    completedHandoffs.putAll(result.handoffs());
                    result.handoffs().forEach((id, value) -> showHandoff(id, value, true));
                }
            }, failure -> {
                finish();
                Throwable reason = failure; while (reason.getCause() != null) reason = reason.getCause();
                append(reason instanceof java.util.concurrent.CancellationException ? messages.text("auto.cancelled")
                        : reason instanceof gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort.DatabaseFailure database
                        && database.reason() == gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort.FailureType.MANUAL_RESTORE_REQUIRED
                        ? messages.text("db.manualRequired") : messages.safe(failure));
            });
        } catch (Exception failure) { append(messages.safe(failure)); }
    }

    private void append(String text) {
        output.append(text + "\n");
        if (output.getDocument().getLength() > 250_000) output.setText(output.getText().substring(50_000));
        output.setCaretPosition(output.getDocument().getLength());
    }

    private void finish() {
        busy = false; ((AdvancedOptionsPane) panel).setBusy(false);
        start.setEnabled(true); start.setText(messages.text("auto.start"));
    }

    /** Captures only successful delivery entries for appearance rebuilds. */
    public Map<String, DeploymentHandoff> captureHandoffs() { return Map.copyOf(completedHandoffs); }

    /** Restores clickable and copyable entries without duplicating the execution log. */
    public void restoreHandoffs(Map<String, DeploymentHandoff> saved) {
        completedHandoffs.clear(); completedHandoffs.putAll(saved); handoffs.removeAll();
        handoffScroll.setVisible(!saved.isEmpty());
        saved.forEach((id, value) -> showHandoff(id, value, false));
    }

    private void showHandoff(String id, DeploymentHandoff result, boolean log) {
        String text = result instanceof DeploymentHandoff.HttpAccessUrl url ? url.url().toString()
                : ((DeploymentHandoff.SystemdStartCommand) result).command();
        if (log) append(id + ": " + text);
        JButton copy = components.secondaryButton(id + " · " + messages.text("auto.copy"));
        copy.addActionListener(event -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(text), null));
        handoffScroll.setVisible(true); handoffs.add(copy);
        if (result instanceof DeploymentHandoff.HttpAccessUrl url) {
            JButton open = components.secondaryButton(messages.text("auto.open"));
            open.addActionListener(event -> { try { Desktop.getDesktop().browse(url.url()); } catch (Exception failure) { append(messages.safe(failure)); } });
            handoffs.add(open);
        }
        handoffs.revalidate(); handoffs.repaint();
    }

    private void saveSecret() {
        if (busy) return;
        JPasswordField field = new JPasswordField(24);
        try {
            List<SecretReference> references = form.secretReferences();
            if (references.size() != 1) throw new IllegalArgumentException(messages.text("validation.secretSingleRevision"));
            SecretReference reference = references.getFirst();
            if (JOptionPane.showConfirmDialog(owner, field, messages.text("secret.value.title"), JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            CredentialStorageMode mode = serverContext.credentialMode();
            char[] master = serverContext.masterPassword(), value = field.getPassword();
            var revision = new StoredApplicationSecretRevision(reference,
                    "application-secret/" + reference.identifier() + "/" + reference.revision(), mode, Instant.now());
            busy = true; ((AdvancedOptionsPane) panel).setBusy(true);
            DesktopTaskExecutor.run(() -> {
                try { service.saveDeploymentSecretRevision(revision, mode, master, value); return reference; }
                finally { Arrays.fill(master, '\0'); Arrays.fill(value, '\0'); }
            }, saved -> {
                finish(); output.setText(messages.text("secret.saved", Map.of("reference", saved.identifier() + ":" + saved.revision())));
            }, failure -> {
                finish(); output.setText(messages.text("secret.saveFailed", Map.of("detail", messages.safe(failure))));
            });
        } catch (Exception exception) {
            output.setText(messages.text("secret.saveFailed", Map.of("detail", messages.safe(exception))));
        } finally { field.setText(""); }
    }

    private String accessReview(Optional<UserAccessUrl> value) {
        return value.map(url -> messages.text("deployment.httpAccess", Map.of("url", url.url().toASCIIString())))
                .orElse(messages.text("deployment.tcpAccess"));
    }
}
