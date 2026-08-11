package gold.debug.windowstolinux.app.ui.shell;

import gold.debug.windowstolinux.app.ui.appearance.DesktopAppearance;
import gold.debug.windowstolinux.app.ui.appearance.DesktopAppearanceChangeListener;
import gold.debug.windowstolinux.app.ui.component.DesktopComponents;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.ai.AiPage;
import gold.debug.windowstolinux.app.ui.ai.AiPageState;
import gold.debug.windowstolinux.app.ui.deployment.DeploymentPage;
import gold.debug.windowstolinux.app.ui.deployment.DeploymentPageState;
import gold.debug.windowstolinux.app.ui.managed.ManagedPage;
import gold.debug.windowstolinux.app.ui.managed.ManagedPageState;
import gold.debug.windowstolinux.app.ui.server.ServerPage;
import gold.debug.windowstolinux.app.ui.server.ServerPageState;
import gold.debug.windowstolinux.app.ui.settings.SettingsPage;
import gold.debug.windowstolinux.app.ui.settings.SettingsPageState;
import gold.debug.windowstolinux.app.service.DesktopApplicationService;
import gold.debug.windowstolinux.app.service.ai.AiAnalysisOutcome;
import gold.debug.windowstolinux.app.service.ai.AiProfile;
import gold.debug.windowstolinux.app.service.deployment.DeploymentHandoff;
import gold.debug.windowstolinux.app.service.deployment.DeploymentOutcome;
import gold.debug.windowstolinux.app.service.lifecycle.LifecycleOutcome;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.SourcePreparation;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.deployment.PhaseOneEnvironmentPreparationResult;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.message.LocalizedFailure;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.Component;
import java.awt.LayoutManager;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns page forms, page workflows and page state; the frame only assembles the shell.
 *
 * <p>持有页面表单、页面流程和页面状态；框架只负责装配外壳。
 */
final class DesktopPages {
    private static final String PAGE_DEPLOYMENT = "deployment";
    private static final String PAGE_APPLICATIONS = "applications";
    private static final String PAGE_SERVERS = "servers";
    private static final String PAGE_AI = "ai";
    private static final String PAGE_SETTINGS = "settings";

    private final DesktopFrame owner;
    private final DesktopApplicationService service;
    private final MessageCatalog messages;
    private final DesktopAppearance appearance;
    private final DesktopComponents components;
    private final DesktopAppearanceChangeListener appearanceChangeListener;
    private final PageNavigator navigator;
    private final JTextArea deploymentOutput = outputArea();
    private final JTextArea serverOutput = outputArea();
    private final JTextField serverId = new JTextField("server-one", 20);
    private final JTextField serverHost = new JTextField(20);
    private final JTextField serverPort = new JTextField("22", 6);
    private final JTextField serverUser = new JTextField(20);
    private final JPasswordField serverPassword = new JPasswordField(20);
    private final JComboBox<CredentialStorageMode> credentialMode = new JComboBox<>(CredentialStorageMode.values());
    private final JPasswordField masterPassword = new JPasswordField(20);
    private final JComboBox<HealthMode> healthMode = new JComboBox<>(HealthMode.values());
    private final JTextField healthEndpoint = new JTextField("http://127.0.0.1:8080/actuator/health", 30);
    private final JTextField expectedHttpStatus = new JTextField("200", 4);
    private final JTextField healthTimeoutSeconds = new JTextField("10", 4);
    private final JTextField tcpStabilitySeconds = new JTextField("5", 4);
    private final JTextField userAccessUrl = new JTextField(30);
    private final JTextField managedApplicationId = new JTextField(20);
    private final JTextArea lifecycleOutput = outputArea();
    private final JTextArea aiOutput = outputArea();
    private final JTextField aiEndpoint = new JTextField("https://api.openai.com/v1/chat/completions", 34);
    private final JTextField aiModel = new JTextField("gpt-5", 20);
    private final JPasswordField aiApiKey = new JPasswordField(24);
    private final JComboBox<CredentialStorageMode> aiCredentialMode = new JComboBox<>(CredentialStorageMode.values());
    private final JPasswordField aiMasterPassword = new JPasswordField(20);
    private final JCheckBox rootBuild;
    private SourcePreparation preparation;
    private String currentPage = PAGE_DEPLOYMENT;

    DesktopPages(DesktopFrame owner, DesktopApplicationService service, MessageCatalog messages,
                 DesktopAppearance appearance, DesktopComponents components,
                 DesktopAppearanceChangeListener appearanceChangeListener, PageNavigator navigator) {
        this.owner = owner;
        this.service = service;
        this.messages = messages;
        this.appearance = appearance;
        this.components = components;
        this.appearanceChangeListener = appearanceChangeListener;
        this.navigator = navigator;
        this.rootBuild = new JCheckBox(t("rootBuild"));
        localizeEnumValues(credentialMode, "credential.mode.");
        localizeEnumValues(aiCredentialMode, "credential.mode.");
        localizeEnumValues(healthMode, "health.mode.");
        credentialMode.addActionListener(event -> masterPassword.setEnabled(
                credentialMode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD));
        aiCredentialMode.addActionListener(event -> aiMasterPassword.setEnabled(
                aiCredentialMode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD));
        masterPassword.setEnabled(true);
        aiMasterPassword.setEnabled(true);
    }

    void currentPage(String page) {
        currentPage = page;
    }

    private void showPage(String page, String titleKey, String descriptionKey) {
        navigator.show(page, titleKey, descriptionKey);
    }

    JPanel deploymentPanel() {
        return DeploymentPage.create(components, messages, healthMode, healthEndpoint, expectedHttpStatus,
                healthTimeoutSeconds, tcpStabilitySeconds, userAccessUrl, rootBuild, deploymentOutput,
                this::chooseSource,
                () -> showPage(PAGE_SERVERS, "nav.servers", "page.servers.description"), this::deploy);
    }

    JPanel serverPanel() {
        return ServerPage.create(components, messages, serverId, serverHost, serverPort, serverUser,
                serverPassword, credentialMode, masterPassword, serverOutput,
                this::saveServer, this::verifyServer, this::preparePhaseOneEnvironment);
    }

    JPanel managedApplicationsPanel() {
        return ManagedPage.create(components, messages, managedApplicationId, lifecycleOutput,
                this::listManagedApplications, action -> lifecycleButton(switch (action) {
                    case REFRESH_STATUS -> t("button.refreshStatus");
                    case START -> t("button.start");
                    case STOP -> t("button.stop");
                    case RESTART -> t("button.restart");
                    case ENABLE_AUTOSTART -> t("button.enableAutostart");
                    case DISABLE_AUTOSTART -> t("button.disableAutostart");
                }, action));
    }

    JPanel aiPanel() {
        return AiPage.create(components, messages, aiEndpoint, aiModel, aiApiKey, aiCredentialMode,
                aiMasterPassword, aiOutput, this::saveAiProfile, this::requestAiExplanation);
    }

    JPanel settingsPanel() {
        return SettingsPage.create(components, messages, appearance,
                selected -> appearanceChangeListener.apply(owner, selected));
    }

    private JPanel pagePanel() {
        return components.pagePanel();
    }

    private JPanel transparent(LayoutManager layout) {
        return components.transparent(layout);
    }

    private JLabel badge(String text) {
        return components.badge(text);
    }

    private JPanel card(LayoutManager layout) {
        return components.card(layout);
    }

    private JComponent sectionHeading(String title, String description) {
        return components.sectionHeading(title, description);
    }

    private JPanel stepCard(String index, String title, String description, JButton action) {
        return components.stepCard(index, title, description, action);
    }

    private JButton primaryButton(String text) {
        return components.primaryButton(text);
    }

    private JButton secondaryButton(String text) {
        return components.secondaryButton(text);
    }

    private JButton lifecycleButton(String text, gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction action) {
        JButton button = secondaryButton(text);
        button.addActionListener(event -> executeLifecycle(action));
        return button;
    }

    private void addField(JPanel panel, int row, int column, String label, Component component) {
        components.addField(panel, row, column, label, component);
    }

    private JPanel outputCard(String title, String description, JTextArea output) {
        return components.outputCard(title, description, output);
    }

    private JPanel informationCard(String title, String message) {
        return components.informationCard(title, message);
    }

    private void chooseSource() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path source = chooser.getSelectedFile().toPath();
        deploymentOutput.setText(t("source.analyzing"));
        new SwingWorker<SourcePreparation, Void>() {
            @Override
            protected SourcePreparation doInBackground() throws Exception {
                return service.prepareSource(source);
            }

            @Override
            protected void done() {
                try {
                    preparation = get();
                    if (preparation.archive().isPresent()) {
                        String excluded = preparation.excludedEntries().isEmpty() ? ""
                                : t("source.excluded", Map.of("entries", preparation.excludedEntries().stream()
                                .map(entry -> "- " + entry + "\n").reduce("", String::concat)));
                        deploymentOutput.setText(t("source.success", Map.of(
                                "archive", preparation.archive().orElseThrow().localArchive(),
                                "facts", analysisFactsSummary(preparation), "excluded", excluded)));
                    } else {
                        deploymentOutput.setText(t("source.unsupported", Map.of("reasons", preparation.assessment().rejections().stream()
                                .map(RejectionReason::message).map(messages::text).map(message -> "- " + message + "\n")
                                .reduce("", String::concat))));
                    }
                } catch (Exception exception) {
                    deploymentOutput.setText(t("source.failed", Map.of("detail", safeMessage(exception))));
                }
            }
        }.execute();
    }

    private String analysisFactsSummary(SourcePreparation preparation) {
        var facts = preparation.assessment().facts().orElseThrow();
        String evidence = facts.evidence().isEmpty() ? "" : t("source.facts", Map.of("items",
                facts.evidence().stream()
                .map(item -> t("source.factItem", Map.of(
                        "subject", messages.text(item.subject()),
                        "conclusion", messages.text(item.conclusion()),
                        "source", item.source(),
                        "confidence", t("analysis.confidence."
                                + item.confidence().name().toLowerCase(Locale.ROOT)))) + "\n")
                .reduce("", String::concat)));
        String conflicts = facts.conflicts().isEmpty() ? "" : t("source.conflicts", Map.of("items",
                facts.conflicts().stream().map(messages::text).map(item -> "- " + item + "\n")
                        .reduce("", String::concat)));
        String missing = facts.missingInformation().isEmpty() ? "" : t("source.missing", Map.of("items",
                facts.missingInformation().stream().map(messages::text).map(item -> "- " + item + "\n")
                        .reduce("", String::concat)));
        return evidence + conflicts + missing;
    }

    private void saveServer() {
        try {
            ServerProfile profile = profile();
            CredentialStorageMode mode = selectedMode();
            char[] master = masterPassword.getPassword();
            char[] password = serverPassword.getPassword();
            service.saveServerProfile(profile, mode, master, password);
            serverOutput.setText(t("server.saved"));
            serverPassword.setText("");
            masterPassword.setText("");
        } catch (Exception exception) {
            serverOutput.setText(t("server.saveFailed", Map.of("detail", safeMessage(exception))));
        }
    }

    private void saveAiProfile() {
        try {
            CredentialStorageMode mode = selectedAiMode();
            AiProfile profile = new AiProfile(URI.create(aiEndpoint.getText().trim()), aiModel.getText().trim(),
                    "ai/default/api-key", mode);
            service.saveAiProfile(profile, mode, aiMasterPassword.getPassword(), aiApiKey.getPassword());
            aiApiKey.setText("");
            aiMasterPassword.setText("");
            aiOutput.setText(t("ai.saved"));
        } catch (Exception exception) {
            aiOutput.setText(t("ai.saveFailed", Map.of("detail", safeMessage(exception))));
        }
    }

    private void requestAiExplanation() {
        if (preparation == null) {
            aiOutput.setText(t("ai.analyzeFirst"));
            return;
        }
        try {
            AiProfile profile = service.findAiProfile().orElseThrow(
                    () -> new IllegalStateException(t("ai.saveFirst"))
            );
            CredentialStorageMode mode = profile.credentialMode();
            char[] master = aiMasterPassword.getPassword();
            aiOutput.setText(t("ai.requesting"));
            new SwingWorker<AiAnalysisOutcome, Void>() {
                @Override
                protected AiAnalysisOutcome doInBackground() {
                    return service.requestAiExplanation(preparation, profile, mode, master,
                            messages.locale().toLanguageTag());
                }

                @Override
                protected void done() {
                    try {
                        AiAnalysisOutcome result = get();
                        aiOutput.setText(result.available()
                                ? t("ai.available", Map.of("detail", result.content()))
                                : localizedFailureSummary(result.status(), result.diagnostic()));
                    } catch (Exception exception) {
                        aiOutput.setText(t("ai.failed", Map.of("detail", safeMessage(exception))));
                    }
                }
            }.execute();
        } catch (Exception exception) {
            aiOutput.setText(t("ai.requestFailed", Map.of("detail", safeMessage(exception))));
        }
    }

    private void verifyServer() {
        try {
            ServerProfile profile = profile();
            CredentialStorageMode mode = selectedMode();
            char[] master = masterPassword.getPassword();
            serverOutput.setText(t("server.connecting"));
            new SwingWorker<gold.debug.windowstolinux.shared.model.server.ServerCapabilities, Void>() {
                @Override
                protected gold.debug.windowstolinux.shared.model.server.ServerCapabilities doInBackground() throws Exception {
                    return service.verifyServer(profile, mode, master, DesktopPages.this::confirmFirstUseFingerprint);
                }

                @Override
                protected void done() {
                    try {
                        var capabilities = get();
                        serverOutput.setText(t("server.capabilities", Map.of(
                                "os", capabilities.operatingSystem(), "architecture", capabilities.architecture(),
                                "java21", capabilities.java21Available(), "maven", capabilities.mavenAvailable(),
                                "tar", capabilities.tarAvailable(), "curl", capabilities.curlAvailable(),
                                "systemd", capabilities.systemdAvailable(), "sudo", capabilities.nonInteractiveSudoAvailable(),
                                "limits", capabilities.buildLimitToolsAvailable(), "space", capabilities.availableBytes())));
                    } catch (Exception exception) {
                        serverOutput.setText(t("server.verifyFailed", Map.of("detail", safeMessage(exception))));
                    }
                }
            }.execute();
        } catch (Exception exception) {
            serverOutput.setText(t("server.invalid", Map.of("detail", safeMessage(exception))));
        }
    }

    private void preparePhaseOneEnvironment(JButton trigger) {
        try {
            ServerProfile enteredProfile = profile();
            ServerProfile profile = service.findServerProfile(enteredProfile.id()).orElseThrow(
                    () -> new IllegalStateException(t("environment.serverSaveFirst"))
            );
            if (!profile.equals(enteredProfile)) {
                throw new IllegalStateException(t("environment.serverChanged"));
            }
            if (JOptionPane.showConfirmDialog(owner, environmentPreparationConfirmation(profile),
                    t("environment.confirm.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                    != JOptionPane.YES_OPTION) {
                return;
            }
            CredentialStorageMode mode = profile.credentialMode();
            char[] master = masterPassword.getPassword();
            trigger.setEnabled(false);
            serverOutput.setText(t("environment.preparing"));
            new SwingWorker<PhaseOneEnvironmentPreparationResult, Void>() {
                @Override
                protected PhaseOneEnvironmentPreparationResult doInBackground() throws Exception {
                    return service.preparePhaseOneEnvironmentWithStoredPassword(profile, mode, master,
                            DesktopPages.this::confirmFirstUseFingerprint, true);
                }

                @Override
                protected void done() {
                    trigger.setEnabled(true);
                    try {
                        serverOutput.setText(environmentPreparationSummary(get()));
                    } catch (Exception exception) {
                        serverOutput.setText(t("environment.incomplete", Map.of("detail", safeMessage(exception))));
                    }
                }
            }.execute();
        } catch (Exception exception) {
            serverOutput.setText(t("environment.failed", Map.of("detail", safeMessage(exception))));
        }
    }

    private String environmentPreparationConfirmation(ServerProfile profile) {
        return t("environment.confirm", Map.of("serverId", profile.id(), "host", profile.host(),
                "port", profile.sshPort(), "username", profile.username()));
    }

    private String environmentPreparationSummary(PhaseOneEnvironmentPreparationResult result) {
        var capabilities = result.capabilities();
        return t("environment.completed", Map.ofEntries(
                Map.entry("os", capabilities.operatingSystem()),
                Map.entry("architecture", capabilities.architecture()),
                Map.entry("java21", availability(capabilities.java21Available())),
                Map.entry("maven", availability(capabilities.mavenAvailable())),
                Map.entry("tar", availability(capabilities.tarAvailable())),
                Map.entry("curl", availability(capabilities.curlAvailable())),
                Map.entry("systemd", availability(capabilities.systemdAvailable())),
                Map.entry("socket", availability(capabilities.socketInspectionAvailable())),
                Map.entry("limits", availability(capabilities.buildLimitToolsAvailable())),
                Map.entry("sudo", availability(capabilities.nonInteractiveSudoAvailable())),
                Map.entry("space", capabilities.availableBytes())));
    }

    private String availability(boolean available) {
        return t(available ? "availability.ready" : "availability.notReady");
    }

    private String deploymentOutcomeSummary(DeploymentOutcome result) {
        String events = result.events().stream()
                .map(event -> "- " + t(event.succeeded() ? "deployment.event.succeeded" : "deployment.event.failed",
                        Map.of("step", t("deployment.step." + event.step()))) + "\n" + event.evidence())
                .reduce("", (left, right) -> left + right + "\n");
        String handoff = result.handoff().map(this::handoffSummary).orElse("");
        return t("deployment.result", Map.of(
                "status", t("deployment.status." + result.status().name().toLowerCase(Locale.ROOT)),
                "events", events, "handoff", handoff));
    }

    private String handoffSummary(DeploymentHandoff handoff) {
        if (handoff instanceof DeploymentHandoff.HttpAccessUrl accessUrl) {
            return t("deployment.httpHandoff", Map.of("url", accessUrl.url().toASCIIString()));
        }
        DeploymentHandoff.SystemdStartCommand start = (DeploymentHandoff.SystemdStartCommand) handoff;
        return t("deployment.commandHandoff", Map.of("unit", start.systemdUnit(), "command", start.command()));
    }

    private void deploy() {
        if (preparation == null || preparation.archive().isEmpty()) {
            deploymentOutput.setText(t("deployment.analyzeFirst"));
            return;
        }
        try {
            ServerProfile profile = profile();
            var server = service.findTrustedServer(profile.id()).orElseThrow(
                    () -> new IllegalStateException(t("deployment.serverFirst"))
            );
            HealthCheck health = healthCheck();
            Optional<UserAccessUrl> accessUrl = userAccessUrlFor(health);
            boolean useRoot = rootBuild.isSelected();
            if (useRoot && JOptionPane.showConfirmDialog(owner,
                    t("deployment.rootConfirm", Map.of("application", preparation.assessment().facts().orElseThrow().applicationName(),
                            "archive", preparation.archive().orElseThrow().contentSha256(), "server", server.host())),
                    t("deployment.rootConfirm.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }
            var request = service.createDeploymentRequest(
                    preparation, server, health, accessUrl,
                    useRoot ? new BuildLimits(1800, 1024, 4096, 4L * 1024 * 1024, 4L * 1024 * 1024 * 1024, true)
                            : BuildLimits.defaultNonRoot(), useRoot
            );
            if (JOptionPane.showConfirmDialog(owner,
                    t("deployment.review", Map.of("server", server.host(), "archive", request.archive().contentSha256(),
                            "access", deploymentAccessReview(request))),
                    t("deployment.review.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }
            CredentialStorageMode mode = selectedMode();
            char[] master = masterPassword.getPassword();
            deploymentOutput.setText(t("deployment.running"));
            new SwingWorker<DeploymentOutcome, Void>() {
                @Override
                protected DeploymentOutcome doInBackground() throws Exception {
                    return service.deployWithStoredPassword(request, profile, mode, master,
                            DesktopPages.this::confirmFirstUseFingerprint);
                }

                @Override
                protected void done() {
                    try {
                        DeploymentOutcome result = get();
                        deploymentOutput.setText(deploymentOutcomeSummary(result));
                        if (result.status() == DeploymentStatus.SUCCEEDED) {
                            managedApplicationId.setText(request.application().id());
                            lifecycleOutput.setText(t("deployment.selected", Map.of("application", request.application().id())));
                        }
                    } catch (Exception exception) {
                        deploymentOutput.setText(t("deployment.failed", Map.of("detail", safeMessage(exception))));
                    }
                }
            }.execute();
        } catch (Exception exception) {
            deploymentOutput.setText(t("deployment.createFailed", Map.of("detail", safeMessage(exception))));
        }
    }

    private void listManagedApplications() {
        try {
            var applications = service.listManagedApplicationSummaries();
            lifecycleOutput.setText(applications.isEmpty() ? t("applications.none") : applications.stream()
                    .map(summary -> t("applications.summary", Map.of("application", summary.application().id(),
                            "server", summary.application().server().host(), "unit", summary.application().systemdUnit(),
                            "release", summary.currentArtifactSha256().orElse(t("applications.noRelease")),
                            "runtime", summary.runtimeConfiguration().map(this::runtimeConfigurationSummary)
                                    .orElse(t("applications.legacyRuntime")))))
                    .reduce("", (left, right) -> left + right + "\n"));
        } catch (Exception exception) {
            lifecycleOutput.setText(t("applications.failed", Map.of("detail", safeMessage(exception))));
        }
    }

    private void executeLifecycle(gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction action) {
        try {
            String applicationId = managedApplicationId.getText().trim();
            if (applicationId.isBlank()) {
                throw new IllegalArgumentException(t("lifecycle.selectApplication"));
            }
            char[] master = masterPassword.getPassword();
            lifecycleOutput.setText(t("lifecycle.running", Map.of(
                    "action", t("lifecycle.action." + action.name().toLowerCase(Locale.ROOT)))));
            new SwingWorker<LifecycleOutcome, Void>() {
                @Override
                protected LifecycleOutcome doInBackground() throws Exception {
                    return service.executePersistedLifecycleWithStoredPassword(applicationId, action, master);
                }

                @Override
                protected void done() {
                    try {
                        var result = get();
                        lifecycleOutput.setText(t(result.accepted() ? "lifecycle.accepted" : "lifecycle.rejected",
                                Map.of("message", messages.text(result.message()),
                                        "observation", lifecycleObservation(result.observation().orElse(null)))));
                    } catch (Exception exception) {
                        lifecycleOutput.setText(t("lifecycle.failed", Map.of("detail", safeMessage(exception))));
                    }
                }
            }.execute();
        } catch (Exception exception) {
            lifecycleOutput.setText(t("lifecycle.startFailed", Map.of("detail", safeMessage(exception))));
        }
    }

    private boolean confirmFirstUseFingerprint(String fingerprint) {
        AtomicBoolean accepted = new AtomicBoolean(false);
        try {
            SwingUtilities.invokeAndWait(() -> accepted.set(JOptionPane.showConfirmDialog(
                    owner, t("fingerprint.confirm", Map.of("fingerprint", fingerprint)),
                    t("fingerprint.confirm.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION));
        } catch (Exception ignored) {
            return false;
        }
        return accepted.get();
    }

    private ServerProfile profile() {
        CredentialStorageMode mode = selectedMode();
        String id = serverId.getText().trim();
        return new ServerProfile(id, serverHost.getText().trim(), Integer.parseInt(serverPort.getText().trim()),
                serverUser.getText().trim(), "ssh/" + id + "/password", mode);
    }

    private CredentialStorageMode selectedMode() {
        return (CredentialStorageMode) credentialMode.getSelectedItem();
    }

    private CredentialStorageMode selectedAiMode() {
        return (CredentialStorageMode) aiCredentialMode.getSelectedItem();
    }

    private HealthCheck healthCheck() {
        int timeout = Integer.parseInt(healthTimeoutSeconds.getText().trim());
        return switch ((HealthMode) healthMode.getSelectedItem()) {
            case HTTP -> new HealthCheck.Http(URI.create(healthEndpoint.getText().trim()),
                    Integer.parseInt(expectedHttpStatus.getText().trim()), timeout);
            case TCP -> new HealthCheck.Tcp(Integer.parseInt(healthEndpoint.getText().trim()), timeout,
                    Integer.parseInt(tcpStabilitySeconds.getText().trim()));
        };
    }

    private Optional<UserAccessUrl> userAccessUrlFor(HealthCheck healthCheck) {
        String value = userAccessUrl.getText().trim();
        if (healthCheck instanceof HealthCheck.Http) {
            if (value.isBlank()) {
                throw new IllegalArgumentException(t("validation.httpAccessRequired"));
            }
            return Optional.of(new UserAccessUrl(URI.create(value)));
        }
        if (!value.isBlank()) {
            throw new IllegalArgumentException(t("validation.tcpAccessForbidden"));
        }
        return Optional.empty();
    }

    private String runtimeConfigurationSummary(
            gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration configuration
    ) {
        if (configuration.healthCheck() instanceof HealthCheck.Http http) {
            return t("runtime.http", Map.of("endpoint", http.endpoint().toASCIIString(),
                    "access", configuration.userAccessUrl().orElseThrow().url().toASCIIString()));
        }
        HealthCheck.Tcp tcp = (HealthCheck.Tcp) configuration.healthCheck();
        return t("runtime.tcp", Map.of("port", tcp.port()));
    }

    private String deploymentAccessReview(gold.debug.windowstolinux.shared.deploy.plan.DeploymentRequest request) {
        return request.userAccessUrl()
                .map(url -> t("deployment.httpAccess", Map.of("url", url.url().toASCIIString())))
                .orElse(t("deployment.tcpAccess"));
    }

    /**
     * Captures unsaved form values before the window is rebuilt for a hot appearance update.
     *
     * <p>在为外观热更新重建窗口前捕获尚未保存的表单值。
     *
     * @return the operation result / 操作结果
     */
    public DesktopViewState captureViewState() {
        return new DesktopViewState(currentPage,
                new DeploymentPageState(((HealthMode) healthMode.getSelectedItem()).name(), healthEndpoint.getText(),
                        expectedHttpStatus.getText(), healthTimeoutSeconds.getText(), tcpStabilitySeconds.getText(),
                        userAccessUrl.getText(), rootBuild.isSelected(), deploymentOutput.getText(), preparation),
                new ServerPageState(serverId.getText(), serverHost.getText(), serverPort.getText(), serverUser.getText(),
                        serverPassword.getPassword(), selectedMode(), masterPassword.getPassword(), serverOutput.getText()),
                new ManagedPageState(managedApplicationId.getText(), lifecycleOutput.getText()),
                new AiPageState(aiEndpoint.getText(), aiModel.getText(), aiApiKey.getPassword(), selectedAiMode(),
                        aiMasterPassword.getPassword(), aiOutput.getText()),
                new SettingsPageState());
    }

    void restoreViewState(DesktopViewState state) {
        ServerPageState serverState = state.server();
        serverId.setText(serverState.id());
        serverHost.setText(serverState.host());
        serverPort.setText(serverState.port());
        serverUser.setText(serverState.username());
        serverPassword.setText(new String(serverState.password()));
        credentialMode.setSelectedItem(serverState.credentialMode());
        masterPassword.setText(new String(serverState.masterPassword()));
        DeploymentPageState deploymentState = state.deployment();
        healthMode.setSelectedItem(HealthMode.valueOf(deploymentState.healthMode()));
        healthEndpoint.setText(deploymentState.healthEndpoint());
        expectedHttpStatus.setText(deploymentState.expectedHttpStatus());
        healthTimeoutSeconds.setText(deploymentState.healthTimeoutSeconds());
        tcpStabilitySeconds.setText(deploymentState.tcpStabilitySeconds());
        userAccessUrl.setText(deploymentState.userAccessUrl());
        managedApplicationId.setText(state.managed().applicationId());
        AiPageState aiState = state.ai();
        aiEndpoint.setText(aiState.endpoint());
        aiModel.setText(aiState.model());
        aiApiKey.setText(new String(aiState.apiKey()));
        aiCredentialMode.setSelectedItem(aiState.credentialMode());
        aiMasterPassword.setText(new String(aiState.masterPassword()));
        rootBuild.setSelected(deploymentState.rootBuild());
        deploymentOutput.setText(deploymentState.output());
        serverOutput.setText(serverState.output());
        lifecycleOutput.setText(state.managed().output());
        aiOutput.setText(aiState.output());
        preparation = deploymentState.preparation();
        switch (state.page()) {
            case PAGE_APPLICATIONS -> showPage(PAGE_APPLICATIONS, "nav.applications", "page.applications.description");
            case PAGE_SERVERS -> showPage(PAGE_SERVERS, "nav.servers", "page.servers.description");
            case PAGE_AI -> showPage(PAGE_AI, "nav.ai", "page.ai.description");
            case PAGE_SETTINGS -> showPage(PAGE_SETTINGS, "nav.settings", "page.settings.description");
            default -> showPage(PAGE_DEPLOYMENT, "nav.deployment", "page.deployment.description");
        }
    }

    private static JTextArea outputArea() {
        return DesktopComponents.outputArea();
    }

    private String lifecycleObservation(LifecycleObservation observation) {
        if (observation == null) {
            return t("lifecycle.noObservation");
        }
        return t("lifecycle.observation", Map.of(
                "runtime", t("runtime.state." + observation.runtimeState().name().toLowerCase(Locale.ROOT)),
                "autostart", t("autostart.state."
                        + observation.autostartState().name().toLowerCase(Locale.ROOT)),
                "ownership", t(observation.ownershipVerified()
                        ? "ownership.verified" : "ownership.unverified"),
                "evidence", observation.evidence()));
    }

    private String safeMessage(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof LocalizedFailure failure) {
                return localizedFailureSummary(failure.userMessage(), failure.diagnostic());
            }
            current = current.getCause();
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? t("diagnostic.unknown") : message;
    }

    private String localizedFailureSummary(gold.debug.windowstolinux.shared.model.message.LocalizedMessage message,
                                           String diagnostic) {
        String headline = messages.text(message);
        return diagnostic == null || diagnostic.isBlank() ? headline : headline + "\n" + diagnostic;
    }

    private String t(String key) {
        return messages.text(key);
    }

    private String t(String key, Map<String, ?> arguments) {
        return messages.text(key, arguments);
    }

    private <T extends Enum<T>> void localizeEnumValues(JComboBox<T> comboBox, String keyPrefix) {
        comboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                Object label = value instanceof Enum<?> item
                        ? t(keyPrefix + item.name().toLowerCase(Locale.ROOT)) : value;
                return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus);
            }
        });
    }

    private enum HealthMode {
        /**
         * Represents the {@code HTTP} option.
         *
         * <p>表示 {@code HTTP} 选项。
         */
        HTTP,
        /**
         * Represents the {@code TCP} option.
         *
         * <p>表示 {@code TCP} 选项。
         */
        TCP
    }

    @FunctionalInterface
    interface PageNavigator {
        /**
         * Performs the {@code show} operation.
         *
         * <p>执行 {@code show} 操作。
         *
         * @param page the {@code page} value / {@code page} 值
         * @param titleKey the {@code titleKey} value / {@code titleKey} 值
         * @param descriptionKey the {@code descriptionKey} value / {@code descriptionKey} 值
         */
        void show(String page, String titleKey, String descriptionKey);
    }
}
