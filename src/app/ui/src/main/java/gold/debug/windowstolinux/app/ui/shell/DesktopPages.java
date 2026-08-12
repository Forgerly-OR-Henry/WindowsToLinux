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
import gold.debug.windowstolinux.app.service.lifecycle.LifecycleOutcome;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationResult;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.message.LocalizedFailure;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSuggestion;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.git.reference.GitReference;
import gold.debug.windowstolinux.shared.git.remote.GitRemote;
import gold.debug.windowstolinux.shared.git.snapshot.GitSourceRequest;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final JComboBox<DeploymentProjectType> projectType = new JComboBox<>(DeploymentProjectType.values());
    private final JComboBox<HealthMode> healthMode = new JComboBox<>(HealthMode.values());
    private final JTextField healthEndpoint = new JTextField(30);
    private final JTextField expectedHttpStatus = new JTextField("200", 4);
    private final JTextField healthTimeoutSeconds = new JTextField("10", 4);
    private final JTextField tcpStabilitySeconds = new JTextField("5", 4);
    private final JTextField userAccessUrl = new JTextField(30);
    private final JTextField runtimePrimary = new JTextField(20);
    private final JTextField runtimeSecondary = new JTextField(20);
    private final JTextField javaVersion = new JTextField(6);
    private final JTextField jvmArguments = new JTextField(20);
    private final JTextField applicationArguments = new JTextField(20);
    private final JComboBox<DeploymentRuntimeSpecification.ContainerEngine> containerEngine =
            new JComboBox<>(DeploymentRuntimeSpecification.ContainerEngine.values());
    private final JTextField containerPorts = new JTextField(20);
    private final JTextField containerVolumes = new JTextField(20);
    private final JTextField configurationEntries = new JTextField(30);
    private final JTextField secretReferences = new JTextField(20);
    private final JTextField managedApplicationId = new JTextField(20);
    private final JTextArea lifecycleOutput = outputArea();
    private final JTextArea aiOutput = outputArea();
    private final JTextField aiEndpoint = new JTextField("https://api.openai.com/v1/chat/completions", 34);
    private final JTextField aiModel = new JTextField("gpt-5", 20);
    private final JPasswordField aiApiKey = new JPasswordField(24);
    private final JComboBox<CredentialStorageMode> aiCredentialMode = new JComboBox<>(CredentialStorageMode.values());
    private final JPasswordField aiMasterPassword = new JPasswordField(20);
    private final JCheckBox rootBuild;
    private ReviewedSourcePreparation reviewedPreparation;
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
        localizeEnumValues(projectType, "project.type.");
        localizeEnumValues(healthMode, "health.mode.");
        localizeEnumValues(containerEngine, "container.engine.");
        projectType.addActionListener(event -> resetRuntimeInputs());
        credentialMode.addActionListener(event -> masterPassword.setEnabled(
                credentialMode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD));
        aiCredentialMode.addActionListener(event -> aiMasterPassword.setEnabled(
                aiCredentialMode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD));
        masterPassword.setEnabled(true);
        aiMasterPassword.setEnabled(true);
        containerEngine.setSelectedItem(null);
        resetRuntimeInputs();
    }

    void currentPage(String page) {
        currentPage = page;
    }

    private void showPage(String page, String titleKey, String descriptionKey) {
        navigator.show(page, titleKey, descriptionKey);
    }

    JPanel deploymentPanel() {
        return DeploymentPage.create(components, messages, projectType, healthMode, healthEndpoint, expectedHttpStatus,
                healthTimeoutSeconds, tcpStabilitySeconds, userAccessUrl, rootBuild, deploymentOutput,
                runtimePrimary, runtimeSecondary, javaVersion, jvmArguments, applicationArguments, containerEngine, containerPorts,
                containerVolumes, configurationEntries, secretReferences,
                this::chooseSource, this::chooseGitSource, this::saveDeploymentSecret,
                () -> showPage(PAGE_SERVERS, "nav.servers", "page.servers.description"), this::deploy);
    }

    JPanel serverPanel() {
        return ServerPage.create(components, messages, serverId, serverHost, serverPort, serverUser,
                serverPassword, credentialMode, masterPassword, serverOutput,
                this::saveServer, this::verifyServer, this::prepareEnvironment);
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
        DeploymentProjectType selectedType = (DeploymentProjectType) projectType.getSelectedItem();
        new SwingWorker<ReviewedSourcePreparation, Void>() {
            @Override
            protected ReviewedSourcePreparation doInBackground() throws Exception {
                return service.prepareReviewedSource(source, selectedType);
            }

            @Override
            protected void done() {
                try {
                    reviewedPreparation = get();
                    if (reviewedPreparation.archive().isPresent()) {
                        applyRuntimeSuggestions(reviewedPreparation);
                        String excluded = reviewedPreparation.excludedEntries().isEmpty() ? ""
                                : t("source.excluded", Map.of("entries", reviewedPreparation.excludedEntries().stream()
                                .map(entry -> "- " + entry + "\n").reduce("", String::concat)));
                        deploymentOutput.setText(t("source.reviewedSuccess", Map.of(
                                "type", t("project.type." + selectedType.name().toLowerCase(Locale.ROOT)),
                                "archive", reviewedPreparation.archive().orElseThrow().localArchive(),
                                "facts", analysisFactsSummary(reviewedPreparation), "excluded", excluded)));
                    } else {
                        deploymentOutput.setText(t("source.reviewedUnavailable", Map.of("reasons",
                                reviewedRejectionSummary(reviewedPreparation))));
                    }
                } catch (Exception exception) {
                    deploymentOutput.setText(t("source.failed", Map.of("detail", safeMessage(exception))));
                }
            }
        }.execute();
    }

    private void chooseGitSource() {
        String remoteText = JOptionPane.showInputDialog(owner, t("git.remote.prompt"), t("git.remote.title"),
                JOptionPane.QUESTION_MESSAGE);
        if (remoteText == null) {
            return;
        }
        String referenceText = JOptionPane.showInputDialog(owner, t("git.reference.prompt"), t("git.reference.title"),
                JOptionPane.QUESTION_MESSAGE);
        if (referenceText == null) {
            return;
        }
        List<String> kinds = List.of(t("git.reference.kind.branch"), t("git.reference.kind.tag"),
                t("git.reference.kind.commit"));
        String kind = (String) JOptionPane.showInputDialog(owner, t("git.reference.kind.prompt"),
                t("git.reference.kind.title"), JOptionPane.QUESTION_MESSAGE, null, kinds.toArray(), kinds.getFirst());
        if (kind == null) {
            return;
        }
        try {
            GitRemote remote = GitRemote.parse(remoteText);
            if (remote.host().isEmpty()) {
                throw new IllegalArgumentException(t("git.remote.networkOnly"));
            }
            GitSourceRequest request = new GitSourceRequest(remote, gitReference(kinds.indexOf(kind), referenceText),
                    java.util.Set.of(remote.host().orElseThrow()), 4L * 1024 * 1024 * 1024, false);
            DeploymentProjectType selectedType = (DeploymentProjectType) projectType.getSelectedItem();
            deploymentOutput.setText(t("git.analyzing"));
            new SwingWorker<ReviewedSourcePreparation, Void>() {
                @Override
                protected ReviewedSourcePreparation doInBackground() throws Exception {
                    return service.prepareReviewedGitSource(request, selectedType);
                }

                @Override
                protected void done() {
                    try {
                        reviewedPreparation = get();
                        if (reviewedPreparation.archive().isPresent()) {
                            applyRuntimeSuggestions(reviewedPreparation);
                            String commit = reviewedPreparation.sourceRevision().orElseThrow().commit().orElseThrow();
                            deploymentOutput.setText(t("git.reviewedSuccess", Map.of("commit", commit,
                                    "archive", reviewedPreparation.archive().orElseThrow().contentSha256(),
                                    "facts", analysisFactsSummary(reviewedPreparation))));
                        } else {
                            deploymentOutput.setText(t("source.reviewedUnavailable", Map.of("reasons",
                                    reviewedRejectionSummary(reviewedPreparation))));
                        }
                    } catch (Exception exception) {
                        deploymentOutput.setText(t("git.failed", Map.of("detail", safeMessage(exception))));
                    }
                }
            }.execute();
        } catch (Exception exception) {
            deploymentOutput.setText(t("git.failed", Map.of("detail", safeMessage(exception))));
        }
    }

    private void applyRuntimeSuggestions(ReviewedSourcePreparation preparation) {
        DeploymentRuntimeSuggestion suggestion = preparation.assessment().runtimeSuggestion().orElse(null);
        if (suggestion == null) {
            return;
        }
        suggestion.value(DeploymentRuntimeSuggestion.RuntimeInput.JAVA_JAR_PATH).ifPresent(runtimePrimary::setText);
        suggestion.value(DeploymentRuntimeSuggestion.RuntimeInput.JAVA_MAIN_CLASS).ifPresent(runtimeSecondary::setText);
        suggestion.value(DeploymentRuntimeSuggestion.RuntimeInput.JAVA_VERSION).ifPresent(javaVersion::setText);
        suggestion.value(DeploymentRuntimeSuggestion.RuntimeInput.NODE_MAJOR_VERSION).ifPresent(runtimePrimary::setText);
        suggestion.value(DeploymentRuntimeSuggestion.RuntimeInput.PYTHON_VERSION).ifPresent(runtimePrimary::setText);
        suggestion.value(DeploymentRuntimeSuggestion.RuntimeInput.PYTHON_ENTRYPOINT).ifPresent(runtimeSecondary::setText);
        suggestion.value(DeploymentRuntimeSuggestion.RuntimeInput.STATIC_OUTPUT_DIRECTORY).ifPresent(runtimePrimary::setText);
        if (!suggestion.suggestedContainerPorts().isEmpty()) {
            containerPorts.setText(suggestion.suggestedContainerPorts().entrySet().stream()
                    .map(entry -> entry.getKey() + ":" + entry.getValue()).reduce((left, right) -> left + ";" + right).orElse(""));
        }
        if (!suggestion.suggestedManagedVolumes().isEmpty()) {
            containerVolumes.setText(suggestion.suggestedManagedVolumes().stream()
                    .map(volume -> volume.name() + ":" + volume.containerPath() + (volume.readOnly() ? ":ro" : ":rw"))
                    .reduce((left, right) -> left + ";" + right).orElse(""));
        }
    }

    private String reviewedRejectionSummary(ReviewedSourcePreparation preparation) {
        if (!preparation.assessment().rejections().isEmpty()) {
            return preparation.assessment().rejections().stream()
                    .map(RejectionReason::message).map(messages::text).map(message -> "- " + message + "\n")
                    .reduce("", String::concat);
        }
        return preparation.assessment().facts().map(facts -> analysisFactsSummary(preparation))
                .orElse(t("diagnostic.unknown"));
    }

    private String analysisFactsSummary(ReviewedSourcePreparation preparation) {
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
        String runtime = preparation.assessment().runtimeSuggestion().map(suggestion -> {
            String inferred = suggestion.evidence().isEmpty() ? "" : t("source.runtimeInferred", Map.of("items",
                    suggestion.evidence().stream().map(item -> "- " + messages.text(item.subject()) + " (" + item.source() + ")\n")
                            .reduce("", String::concat)));
            String required = suggestion.requiredUserInput().isEmpty() ? "" : t("source.runtimeRequired", Map.of("items",
                    suggestion.requiredUserInput().stream().map(messages::text).map(item -> "- " + item + "\n")
                            .reduce("", String::concat)));
            return inferred + required;
        }).orElse("");
        return evidence + conflicts + missing + runtime;
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
        if (reviewedPreparation == null) {
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
                    return service.requestAiExplanation(reviewedPreparation, profile, mode, master,
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

    private void prepareEnvironment(JButton trigger) {
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
            new SwingWorker<EnvironmentPreparationResult, Void>() {
                @Override
                protected EnvironmentPreparationResult doInBackground() throws Exception {
                    return service.prepareEnvironmentWithStoredPassword(profile, mode, master,
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

    private String environmentPreparationSummary(EnvironmentPreparationResult result) {
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

    private String reviewedDeploymentSummary(DeploymentResult result) {
        String events = result.events().stream()
                .map(event -> "- " + t(event.succeeded() ? "deployment.event.succeeded" : "deployment.event.failed",
                        Map.of("step", t("deployment.step." + event.step()))) + "\n" + event.evidence())
                .reduce("", (left, right) -> left + right + "\n");
        String handoff = result.finalObservation().map(this::lifecycleObservation).orElse("");
        return t("deployment.result", Map.of(
                "status", t("deployment.status." + result.status().name().toLowerCase(Locale.ROOT)),
                "events", events, "handoff", handoff));
    }

    private void deploy() {
        if (reviewedPreparation == null || reviewedPreparation.archive().isEmpty()) {
            deploymentOutput.setText(t("deployment.analyzeFirst"));
            return;
        }
        try {
            if (reviewedPreparation.assessment().facts().orElseThrow().projectType() != projectType.getSelectedItem()) {
                throw new IllegalStateException(t("deployment.sourceTypeChanged"));
            }
            ServerProfile profile = profile();
            var server = service.findTrustedServer(profile.id()).orElseThrow(
                    () -> new IllegalStateException(t("deployment.serverFirst"))
            );
            HealthCheck health = healthCheck();
            Optional<UserAccessUrl> accessUrl = userAccessUrlFor(health);
            boolean useRoot = rootBuild.isSelected();
            if (useRoot && JOptionPane.showConfirmDialog(owner,
                    t("deployment.reviewedRootConfirm", Map.of("application", reviewedPreparation.assessment().facts().orElseThrow().applicationId(),
                            "archive", reviewedPreparation.archive().orElseThrow().contentSha256(), "server", server.host())),
                    t("deployment.rootConfirm.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }
            DeploymentRuntimeSpecification runtime = runtimeSpecification(health);
            boolean dockerRiskAccepted = runtime instanceof DeploymentRuntimeSpecification.Container container
                    && container.engine() == DeploymentRuntimeSpecification.ContainerEngine.DOCKER
                    && JOptionPane.showConfirmDialog(owner, t("deployment.dockerRisk"), t("deployment.dockerRisk.title"),
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
            if (runtime instanceof DeploymentRuntimeSpecification.Container container
                    && container.engine() == DeploymentRuntimeSpecification.ContainerEngine.DOCKER && !dockerRiskAccepted) {
                return;
            }
            ConfigurationSnapshot configuration = configurationSnapshot();
            ReviewedDeploymentRequest request = service.createReviewedDeploymentRequest(
                    reviewedPreparation, server, configuration, secretReferences(), runtime, accessUrl,
                    useRoot ? new BuildLimits(1800, 1024, 4096, 4L * 1024 * 1024, 4L * 1024 * 1024 * 1024, true)
                            : BuildLimits.defaultNonRoot(), useRoot, dockerRiskAccepted);
            ReviewedDeploymentPlan plan = service.planDeployment(request);
            if (JOptionPane.showConfirmDialog(owner,
                    t("deployment.reviewedReview", Map.of("type", t("project.type." + runtime.projectType().name().toLowerCase(Locale.ROOT)),
                            "server", server.host(), "archive", request.archive().contentSha256(), "configuration", configuration.sha256(),
                            "access", deploymentAccessReview(accessUrl), "plan", reviewedPlanSummary(plan))),
                    t("deployment.review.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }
            CredentialStorageMode mode = selectedMode();
            char[] master = masterPassword.getPassword();
            deploymentOutput.setText(t("deployment.reviewedRunning"));
            new SwingWorker<DeploymentResult, Void>() {
                @Override
                protected DeploymentResult doInBackground() throws Exception {
                    service.saveDeploymentConfigurationSnapshot(configuration);
                    return service.deployReviewedWithStoredPassword(request, profile, mode, master,
                            DesktopPages.this::confirmFirstUseFingerprint);
                }

                @Override
                protected void done() {
                    try {
                        DeploymentResult result = get();
                        deploymentOutput.setText(reviewedDeploymentSummary(result));
                        if (result.status() == DeploymentStatus.SUCCEEDED) {
                            managedApplicationId.setText(request.facts().applicationId());
                            lifecycleOutput.setText(t("deployment.selected", Map.of("application", request.facts().applicationId())));
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

    private String reviewedPlanSummary(ReviewedDeploymentPlan plan) {
        return plan.steps().stream()
                .map(step -> "- " + t("deployment.plan." + step.name().toLowerCase(Locale.ROOT)) + "\n")
                .reduce("", String::concat);
    }

    private ConfigurationSnapshot configurationSnapshot() {
        String applicationId = reviewedPreparation.assessment().facts().orElseThrow().applicationId();
        List<ConfigurationEntry> entries = new ArrayList<>();
        for (String item : configurationEntries.getText().split(";")) {
            String trimmed = item.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 1 || separator == trimmed.length() - 1) {
                throw new IllegalArgumentException(t("validation.configurationEntry"));
            }
            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            entries.add(new ConfigurationEntry(key, ConfigurationScope.RUNTIME, configurationValue(value)));
        }
        return ConfigurationSnapshot.create(applicationId, Instant.now().toEpochMilli(), "runtime-v1", Instant.now(), entries);
    }

    private List<SecretReference> secretReferences() {
        List<SecretReference> references = new ArrayList<>();
        for (String item : secretReferences.getText().split(";")) {
            String value = item.trim();
            if (value.isEmpty()) {
                continue;
            }
            int separator = value.lastIndexOf(':');
            if (separator < 1 || separator == value.length() - 1) {
                throw new IllegalArgumentException(t("validation.secretReference"));
            }
            references.add(new SecretReference(value.substring(0, separator).trim(),
                    Long.parseLong(value.substring(separator + 1).trim())));
        }
        return List.copyOf(references);
    }

    private void saveDeploymentSecret() {
        try {
            List<SecretReference> references = secretReferences();
            if (references.size() != 1) {
                throw new IllegalArgumentException(t("validation.secretSingleRevision"));
            }
            SecretReference reference = references.getFirst();
            JPasswordField valueField = new JPasswordField(24);
            if (JOptionPane.showConfirmDialog(owner, valueField, t("secret.value.title"), JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
                return;
            }
            CredentialStorageMode mode = selectedMode();
            StoredApplicationSecretRevision revision = new StoredApplicationSecretRevision(reference,
                    "application-secret/" + reference.identifier() + "/" + reference.revision(), mode, Instant.now());
            service.saveDeploymentSecretRevision(revision, mode, masterPassword.getPassword(), valueField.getPassword());
            deploymentOutput.setText(t("secret.saved", Map.of("reference", reference.identifier() + ":" + reference.revision())));
        } catch (Exception exception) {
            deploymentOutput.setText(t("secret.saveFailed", Map.of("detail", safeMessage(exception))));
        }
    }

    private ConfigurationValue configurationValue(String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return new ConfigurationValue.Flag(Boolean.parseBoolean(value));
        }
        if (value.matches("-?[0-9]+")) {
            return new ConfigurationValue.Number(Long.parseLong(value));
        }
        return new ConfigurationValue.Text(value);
    }

    private DeploymentRuntimeSpecification runtimeSpecification(HealthCheck health) {
        DeploymentProjectType type = (DeploymentProjectType) projectType.getSelectedItem();
        return switch (type) {
            case GRADLE_SPRING_BOOT -> new DeploymentRuntimeSpecification.GradleSpringBoot(health);
            case JAVA_JAR -> new DeploymentRuntimeSpecification.JavaJar(runtimePrimary.getText(), runtimeSecondary.getText(),
                    javaVersion.getText(), structuredArguments(jvmArguments.getText()), structuredArguments(applicationArguments.getText()), health);
            case NODE_SERVICE -> new DeploymentRuntimeSpecification.NodeService(
                    Integer.parseInt(runtimePrimary.getText().trim()), health);
            case PYTHON_SERVICE -> new DeploymentRuntimeSpecification.PythonService(runtimePrimary.getText(),
                    runtimeSecondary.getText(), health);
            case STATIC_SITE -> new DeploymentRuntimeSpecification.StaticSite(runtimePrimary.getText(), requireHttpHealthCheck(health));
            case DOCKERFILE_CONTAINER -> new DeploymentRuntimeSpecification.Container(
                    (DeploymentRuntimeSpecification.ContainerEngine) containerEngine.getSelectedItem(),
                    containerPorts(), managedVolumes(), health);
        };
    }

    private HealthCheck.Http requireHttpHealthCheck(HealthCheck health) {
        if (health instanceof HealthCheck.Http http) {
            return http;
        }
        throw new IllegalArgumentException(t("validation.staticHttpRequired"));
    }

    private List<String> structuredArguments(String value) {
        if (value.isBlank()) {
            return List.of();
        }
        return List.of(value.trim().split("\\s+"));
    }

    private Map<Integer, Integer> containerPorts() {
        Map<Integer, Integer> ports = new LinkedHashMap<>();
        for (String pair : containerPorts.getText().split(";")) {
            String[] values = pair.trim().split(":", -1);
            if (values.length != 2) {
                throw new IllegalArgumentException(t("validation.containerPort"));
            }
            ports.put(Integer.parseInt(values[0].trim()), Integer.parseInt(values[1].trim()));
        }
        return ports;
    }

    private List<DeploymentRuntimeSpecification.ManagedVolume> managedVolumes() {
        if (containerVolumes.getText().isBlank()) {
            return List.of();
        }
        List<DeploymentRuntimeSpecification.ManagedVolume> volumes = new ArrayList<>();
        for (String entry : containerVolumes.getText().split(";")) {
            String[] values = entry.trim().split(":", -1);
            if (values.length < 2 || values.length > 3) {
                throw new IllegalArgumentException(t("validation.containerVolume"));
            }
            boolean readOnly = values.length == 3 && values[2].trim().equalsIgnoreCase("ro");
            if (values.length == 3 && !readOnly && !values[2].trim().equalsIgnoreCase("rw")) {
                throw new IllegalArgumentException(t("validation.containerVolume"));
            }
            volumes.add(new DeploymentRuntimeSpecification.ManagedVolume(values[0].trim(), values[1].trim(), readOnly));
        }
        return List.copyOf(volumes);
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

    private String deploymentAccessReview(Optional<UserAccessUrl> accessUrl) {
        return accessUrl
                .map(url -> t("deployment.httpAccess", Map.of("url", url.url().toASCIIString())))
                .orElse(t("deployment.tcpAccess"));
    }

    private void resetRuntimeInputs() {
        runtimePrimary.setText("");
        runtimeSecondary.setText("");
        javaVersion.setText("");
        jvmArguments.setText("");
        applicationArguments.setText("");
        containerPorts.setText("");
        containerVolumes.setText("");
        reviewedPreparation = null;
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
                new DeploymentPageState(((DeploymentProjectType) projectType.getSelectedItem()).name(),
                        ((HealthMode) healthMode.getSelectedItem()).name(), healthEndpoint.getText(),
                        expectedHttpStatus.getText(), healthTimeoutSeconds.getText(), tcpStabilitySeconds.getText(),
                        userAccessUrl.getText(), runtimePrimary.getText(), runtimeSecondary.getText(), javaVersion.getText(),
                        jvmArguments.getText(), applicationArguments.getText(), containerEngine.getSelectedItem() == null ? ""
                                : ((DeploymentRuntimeSpecification.ContainerEngine) containerEngine.getSelectedItem()).name(),
                        containerPorts.getText(), containerVolumes.getText(), configurationEntries.getText(), secretReferences.getText(),
                        rootBuild.isSelected(), deploymentOutput.getText(), reviewedPreparation),
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
        projectType.setSelectedItem(DeploymentProjectType.valueOf(deploymentState.projectType()));
        healthMode.setSelectedItem(HealthMode.valueOf(deploymentState.healthMode()));
        healthEndpoint.setText(deploymentState.healthEndpoint());
        expectedHttpStatus.setText(deploymentState.expectedHttpStatus());
        healthTimeoutSeconds.setText(deploymentState.healthTimeoutSeconds());
        tcpStabilitySeconds.setText(deploymentState.tcpStabilitySeconds());
        userAccessUrl.setText(deploymentState.userAccessUrl());
        runtimePrimary.setText(deploymentState.runtimePrimary());
        runtimeSecondary.setText(deploymentState.runtimeSecondary());
        javaVersion.setText(deploymentState.javaVersion());
        jvmArguments.setText(deploymentState.jvmArguments());
        applicationArguments.setText(deploymentState.applicationArguments());
        containerEngine.setSelectedItem(deploymentState.containerEngine().isBlank() ? null
                : DeploymentRuntimeSpecification.ContainerEngine.valueOf(deploymentState.containerEngine()));
        containerPorts.setText(deploymentState.containerPorts());
        containerVolumes.setText(deploymentState.containerVolumes());
        configurationEntries.setText(deploymentState.configurationEntries());
        secretReferences.setText(deploymentState.secretReferences());
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
        reviewedPreparation = deploymentState.preparation();
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

    private static GitReference gitReference(int kindIndex, String value) {
        return switch (kindIndex) {
            case 0 -> new GitReference.Branch(value);
            case 1 -> new GitReference.Tag(value);
            case 2 -> new GitReference.Commit(value);
            default -> throw new IllegalArgumentException("Unsupported Git reference kind");
        };
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
