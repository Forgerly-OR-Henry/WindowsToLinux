package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.service.DesktopApplicationService;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.app.ui.component.DesktopComponents;
import gold.debug.windowstolinux.app.ui.server.ServerContext;
import gold.debug.windowstolinux.app.ui.shell.PageMessages;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.git.reference.GitReference;
import gold.debug.windowstolinux.shared.git.remote.GitRemote;
import gold.debug.windowstolinux.shared.git.snapshot.GitSourceRequest;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSuggestion;
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
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

/** Owns source selection, review state, deployment forms, and the complete reviewed deployment workflow. / 持有源码选择、审阅状态、部署表单与完整经审阅部署流程。 */
public final class DeploymentPage implements ReviewContext {
    private final JFrame owner;
    private final DesktopApplicationService service;
    private final ServerContext serverContext;
    private final PageMessages messages;
    private final Consumer<String> applicationSelection;
    private final Runnable openServers;
    private final JComboBox<DeploymentProjectType> projectType = new JComboBox<>(DeploymentProjectType.values());
    private final JComboBox<HealthMode> healthMode = new JComboBox<>(HealthMode.values());
    private final JTextField healthEndpoint = new JTextField(30);
    private final JTextField expectedStatus = new JTextField("200", 4);
    private final JTextField timeout = new JTextField("10", 4);
    private final JTextField stability = new JTextField("5", 4);
    private final JTextField accessUrl = new JTextField(30);
    private final JTextField runtimePrimary = new JTextField(20);
    private final JTextField runtimeSecondary = new JTextField(20);
    private final JTextField runtimeVersion = new JTextField(6);
    private final JTextField jvmArguments = new JTextField(20);
    private final JTextField applicationArguments = new JTextField(20);
    private final JComboBox<DeploymentRuntimeSpecification.ContainerEngine> containerEngine =
            new JComboBox<>(DeploymentRuntimeSpecification.ContainerEngine.values());
    private final JTextField containerPorts = new JTextField(20);
    private final JTextField containerVolumes = new JTextField(20);
    private final JTextField configurationEntries = new JTextField(30);
    private final JTextField secretReferences = new JTextField(20);
    private final JCheckBox rootBuild;
    private final JTextArea output = DesktopComponents.outputArea();
    private final JPanel panel;
    private ReviewedSourcePreparation reviewedPreparation;

    /** Creates the stateful deployment controller. / 创建有状态部署控制器。 */
    public DeploymentPage(JFrame owner, DesktopApplicationService service, ServerContext serverContext,
                          DesktopComponents components, PageMessages messages, Runnable openServers,
                          Consumer<String> applicationSelection) {
        this.owner = owner;
        this.service = service;
        this.serverContext = serverContext;
        this.messages = messages;
        this.openServers = openServers;
        this.applicationSelection = applicationSelection;
        rootBuild = new JCheckBox(messages.text("rootBuild"));
        messages.localize(projectType, "project.type.");
        messages.localize(healthMode, "health.mode.");
        messages.localize(containerEngine, "container.engine.");
        projectType.addActionListener(event -> resetRuntimeInputs());
        containerEngine.setSelectedItem(null);
        panel = createPanel(components);
    }

    /** Returns the page panel. / 返回页面面板。 */
    public JPanel panel() { return panel; }
    @Override public Optional<ReviewedSourcePreparation> reviewedPreparation() { return Optional.ofNullable(reviewedPreparation); }

    /** Captures all unsaved deployment and review state. / 捕获全部未保存部署与审阅状态。 */
    public DeploymentPageState captureState() {
        return new DeploymentPageState(((DeploymentProjectType) projectType.getSelectedItem()).name(),
                ((HealthMode) healthMode.getSelectedItem()).name(), healthEndpoint.getText(), expectedStatus.getText(),
                timeout.getText(), stability.getText(), accessUrl.getText(), runtimePrimary.getText(), runtimeSecondary.getText(),
                runtimeVersion.getText(), jvmArguments.getText(), applicationArguments.getText(),
                containerEngine.getSelectedItem() == null ? "" : ((DeploymentRuntimeSpecification.ContainerEngine)
                        containerEngine.getSelectedItem()).name(), containerPorts.getText(), containerVolumes.getText(),
                configurationEntries.getText(), secretReferences.getText(), rootBuild.isSelected(), output.getText(), reviewedPreparation);
    }

    /** Restores all unsaved deployment and review state. / 恢复全部未保存部署与审阅状态。 */
    public void restoreState(DeploymentPageState state) {
        projectType.setSelectedItem(DeploymentProjectType.valueOf(state.projectType()));
        healthMode.setSelectedItem(HealthMode.valueOf(state.healthMode()));
        healthEndpoint.setText(state.healthEndpoint()); expectedStatus.setText(state.expectedHttpStatus());
        timeout.setText(state.healthTimeoutSeconds()); stability.setText(state.tcpStabilitySeconds());
        accessUrl.setText(state.userAccessUrl()); runtimePrimary.setText(state.runtimePrimary());
        runtimeSecondary.setText(state.runtimeSecondary()); runtimeVersion.setText(state.javaVersion());
        jvmArguments.setText(state.jvmArguments()); applicationArguments.setText(state.applicationArguments());
        containerEngine.setSelectedItem(state.containerEngine().isBlank() ? null
                : DeploymentRuntimeSpecification.ContainerEngine.valueOf(state.containerEngine()));
        containerPorts.setText(state.containerPorts()); containerVolumes.setText(state.containerVolumes());
        configurationEntries.setText(state.configurationEntries()); secretReferences.setText(state.secretReferences());
        rootBuild.setSelected(state.rootBuild()); output.setText(state.output()); reviewedPreparation = state.preparation();
    }

    private JPanel createPanel(DesktopComponents c) {
        JPanel page = c.pagePanel();
        JPanel steps = c.transparent(new GridLayout(1, 4, 12, 0));
        JButton source = c.primaryButton(messages.text("button.analyzeSource")); source.addActionListener(event -> chooseSource());
        JButton git = c.secondaryButton(messages.text("button.analyzeGitSource")); git.addActionListener(event -> chooseGitSource());
        JButton server = c.secondaryButton(messages.text("button.goServer")); server.addActionListener(event -> openServers.run());
        JButton deploy = c.primaryButton(messages.text("button.reviewDeploy")); deploy.addActionListener(event -> deploy());
        steps.add(c.stepCard("01", messages.text("step.analyze.title"), messages.text("step.analyze.description"), source));
        steps.add(c.stepCard("01b", messages.text("step.git.title"), messages.text("step.git.description"), git));
        steps.add(c.stepCard("02", messages.text("step.target.title"), messages.text("step.target.description"), server));
        steps.add(c.stepCard("03", messages.text("step.deploy.title"), messages.text("step.deploy.description"), deploy));
        page.add(steps, BorderLayout.NORTH);
        JPanel body = c.transparent(new BorderLayout(0, 12));
        JPanel health = c.card(new BorderLayout(0, 12));
        health.add(c.sectionHeading(messages.text("section.deployHealth.title"), messages.text("section.deployHealth.description")),
                BorderLayout.NORTH);
        JPanel healthForm = c.transparent(new GridBagLayout());
        c.addField(healthForm, 0, 0, messages.text("field.healthMode"), healthMode);
        c.addField(healthForm, 0, 1, messages.text("field.healthEndpoint"), healthEndpoint);
        c.addField(healthForm, 1, 0, messages.text("field.expectedStatus"), expectedStatus);
        c.addField(healthForm, 1, 1, messages.text("field.timeout"), timeout);
        c.addField(healthForm, 2, 0, messages.text("field.tcpStability"), stability);
        c.addField(healthForm, 2, 1, messages.text("field.userAccessUrl"), accessUrl);
        health.add(healthForm, BorderLayout.CENTER);
        JPanel risk = c.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        rootBuild.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0)); risk.add(rootBuild); health.add(risk, BorderLayout.SOUTH);
        JPanel runtime = c.card(new BorderLayout(0, 12));
        runtime.add(c.sectionHeading(messages.text("section.runtime.title"), messages.text("section.runtime.description")), BorderLayout.NORTH);
        JPanel form = c.transparent(new GridBagLayout());
        c.addField(form, 0, 0, messages.text("field.projectType"), projectType);
        c.addField(form, 0, 1, messages.text("field.runtimePrimary"), runtimePrimary);
        c.addField(form, 1, 0, messages.text("field.runtimeSecondary"), runtimeSecondary);
        c.addField(form, 1, 1, messages.text("field.runtimeVersion"), runtimeVersion);
        c.addField(form, 2, 0, messages.text("field.jvmArguments"), jvmArguments);
        c.addField(form, 2, 1, messages.text("field.applicationArguments"), applicationArguments);
        c.addField(form, 3, 0, messages.text("field.containerEngine"), containerEngine);
        c.addField(form, 3, 1, messages.text("field.containerPorts"), containerPorts);
        c.addField(form, 4, 0, messages.text("field.containerVolumes"), containerVolumes);
        c.addField(form, 4, 1, messages.text("field.configurationEntries"), configurationEntries);
        c.addField(form, 5, 0, messages.text("field.secretReferences"), secretReferences);
        JButton secret = c.secondaryButton(messages.text("button.saveSecretRevision")); secret.addActionListener(event -> saveSecret());
        c.addField(form, 5, 1, messages.text("field.secretRevision"), secret);
        runtime.add(form, BorderLayout.CENTER);
        JPanel forms = c.transparent(new GridLayout(2, 1, 0, 12)); forms.add(health); forms.add(runtime);
        body.add(forms, BorderLayout.NORTH);
        body.add(c.outputCard(messages.text("section.execution.title"), messages.text("section.execution.description"), output),
                BorderLayout.CENTER);
        page.add(body, BorderLayout.CENTER);
        return page;
    }

    private void chooseSource() {
        JFileChooser chooser = new JFileChooser(); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        analyze(chooser.getSelectedFile().toPath());
    }

    private void analyze(Path source) {
        DeploymentProjectType selected = (DeploymentProjectType) projectType.getSelectedItem();
        output.setText(messages.text("source.analyzing"));
        new SwingWorker<ReviewedSourcePreparation, Void>() {
            @Override protected ReviewedSourcePreparation doInBackground() throws Exception {
                return service.prepareReviewedSource(source, selected);
            }
            @Override protected void done() {
                try {
                    applyAnalysis(get(), selected, false);
                } catch (Exception exception) {
                    output.setText(messages.text("source.failed", Map.of("detail", messages.safe(exception))));
                }
            }
        }.execute();
    }

    private void chooseGitSource() {
        String remoteText = JOptionPane.showInputDialog(owner, messages.text("git.remote.prompt"),
                messages.text("git.remote.title"), JOptionPane.QUESTION_MESSAGE);
        if (remoteText == null) return;
        String referenceText = JOptionPane.showInputDialog(owner, messages.text("git.reference.prompt"),
                messages.text("git.reference.title"), JOptionPane.QUESTION_MESSAGE);
        if (referenceText == null) return;
        List<String> kinds = List.of(messages.text("git.reference.kind.branch"), messages.text("git.reference.kind.tag"),
                messages.text("git.reference.kind.commit"));
        String kind = (String) JOptionPane.showInputDialog(owner, messages.text("git.reference.kind.prompt"),
                messages.text("git.reference.kind.title"), JOptionPane.QUESTION_MESSAGE, null, kinds.toArray(), kinds.getFirst());
        if (kind == null) return;
        try {
            GitRemote remote = GitRemote.parse(remoteText);
            if (remote.host().isEmpty()) throw new IllegalArgumentException(messages.text("git.remote.networkOnly"));
            GitSourceRequest request = new GitSourceRequest(remote, gitReference(kinds.indexOf(kind), referenceText),
                    java.util.Set.of(remote.host().orElseThrow()), 4L * 1024 * 1024 * 1024, false);
            DeploymentProjectType selected = (DeploymentProjectType) projectType.getSelectedItem();
            output.setText(messages.text("git.analyzing"));
            new SwingWorker<ReviewedSourcePreparation, Void>() {
                @Override protected ReviewedSourcePreparation doInBackground() throws Exception {
                    return service.prepareReviewedGitSource(request, selected);
                }
                @Override protected void done() {
                    try {
                        applyAnalysis(get(), selected, true);
                    } catch (Exception exception) {
                        output.setText(messages.text("git.failed", Map.of("detail", messages.safe(exception))));
                    }
                }
            }.execute();
        } catch (Exception exception) {
            output.setText(messages.text("git.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    private void applyAnalysis(ReviewedSourcePreparation preparation, DeploymentProjectType selected, boolean git) {
        reviewedPreparation = preparation;
        if (preparation.archive().isEmpty()) {
            output.setText(messages.text("source.reviewedUnavailable", Map.of("reasons", rejectionSummary(preparation))));
            return;
        }
        applyRuntimeSuggestions(preparation);
        if (git) {
            output.setText(messages.text("git.reviewedSuccess", Map.of(
                    "commit", preparation.sourceRevision().orElseThrow().commit().orElseThrow(),
                    "archive", preparation.archive().orElseThrow().contentSha256(), "facts", factsSummary(preparation))));
        } else {
            String excluded = preparation.excludedEntries().isEmpty() ? "" : messages.text("source.excluded", Map.of("entries",
                    preparation.excludedEntries().stream().map(entry -> "- " + entry + "\n").reduce("", String::concat)));
            output.setText(messages.text("source.reviewedSuccess", Map.of(
                    "type", messages.text("project.type." + selected.name().toLowerCase(Locale.ROOT)),
                    "archive", preparation.archive().orElseThrow().localArchive(), "facts", factsSummary(preparation),
                    "excluded", excluded)));
        }
    }

    private void applyRuntimeSuggestions(ReviewedSourcePreparation preparation) {
        DeploymentRuntimeSuggestion suggestion = preparation.assessment().runtimeSuggestion().orElse(null);
        if (suggestion == null) return;
        suggestion.value(DeploymentRuntimeSuggestion.RuntimeInput.JAVA_JAR_PATH).ifPresent(runtimePrimary::setText);
        suggestion.value(DeploymentRuntimeSuggestion.RuntimeInput.JAVA_MAIN_CLASS).ifPresent(runtimeSecondary::setText);
        suggestion.value(DeploymentRuntimeSuggestion.RuntimeInput.JAVA_VERSION).ifPresent(runtimeVersion::setText);
        suggestion.value(DeploymentRuntimeSuggestion.RuntimeInput.NODE_MAJOR_VERSION).ifPresent(runtimeVersion::setText);
        suggestion.value(DeploymentRuntimeSuggestion.RuntimeInput.PYTHON_VERSION).ifPresent(runtimePrimary::setText);
        suggestion.value(DeploymentRuntimeSuggestion.RuntimeInput.PYTHON_ENTRYPOINT).ifPresent(runtimeSecondary::setText);
        suggestion.value(DeploymentRuntimeSuggestion.RuntimeInput.STATIC_OUTPUT_DIRECTORY).ifPresent(runtimePrimary::setText);
        if (!suggestion.suggestedContainerPorts().isEmpty()) containerPorts.setText(suggestion.suggestedContainerPorts().entrySet()
                .stream().map(entry -> entry.getKey() + ":" + entry.getValue()).reduce((a, b) -> a + ";" + b).orElse(""));
        if (!suggestion.suggestedManagedVolumes().isEmpty()) containerVolumes.setText(suggestion.suggestedManagedVolumes().stream()
                .map(volume -> volume.name() + ":" + volume.containerPath() + (volume.readOnly() ? ":ro" : ":rw"))
                .reduce((a, b) -> a + ";" + b).orElse(""));
    }

    private String factsSummary(ReviewedSourcePreparation preparation) {
        var facts = preparation.assessment().facts().orElseThrow();
        var language = facts.languageFacts();
        String languages = messages.text("source.languageSummary", Map.of(
                "ecosystems", language.ecosystems().stream().map(item -> messages.text("language.ecosystem."
                        + item.name().toLowerCase(Locale.ROOT))).sorted().reduce((a, b) -> a + ", " + b).orElse("-"),
                "sources", language.sourceLanguages().stream().map(item -> messages.text("language.source."
                        + item.name().toLowerCase(Locale.ROOT))).sorted().reduce((a, b) -> a + ", " + b).orElse("-")));
        String evidence = facts.evidence().isEmpty() ? "" : messages.text("source.facts", Map.of("items", facts.evidence().stream()
                .map(item -> messages.text("source.factItem", Map.of("subject", messages.catalog().text(item.subject()),
                        "conclusion", messages.catalog().text(item.conclusion()), "source", item.source(), "confidence",
                        messages.text("analysis.confidence." + item.confidence().name().toLowerCase(Locale.ROOT)))) + "\n")
                .reduce("", String::concat)));
        String conflicts = facts.conflicts().isEmpty() ? "" : messages.text("source.conflicts", Map.of("items",
                facts.conflicts().stream().map(messages.catalog()::text).map(item -> "- " + item + "\n").reduce("", String::concat)));
        String missing = facts.missingInformation().isEmpty() ? "" : messages.text("source.missing", Map.of("items",
                facts.missingInformation().stream().map(messages.catalog()::text).map(item -> "- " + item + "\n").reduce("", String::concat)));
        String runtime = preparation.assessment().runtimeSuggestion().map(suggestion ->
                (suggestion.evidence().isEmpty() ? "" : messages.text("source.runtimeInferred", Map.of("items",
                        suggestion.evidence().stream().map(item -> "- " + messages.catalog().text(item.subject()) + " ("
                                + item.source() + ")\n").reduce("", String::concat))))
                + (suggestion.requiredUserInput().isEmpty() ? "" : messages.text("source.runtimeRequired", Map.of("items",
                        suggestion.requiredUserInput().stream().map(messages.catalog()::text).map(item -> "- " + item + "\n")
                                .reduce("", String::concat))))).orElse("");
        return languages + evidence + conflicts + missing + runtime;
    }

    private String rejectionSummary(ReviewedSourcePreparation preparation) {
        if (!preparation.assessment().rejections().isEmpty()) return preparation.assessment().rejections().stream()
                .map(RejectionReason::message).map(messages.catalog()::text).map(item -> "- " + item + "\n").reduce("", String::concat);
        return preparation.assessment().facts().map(facts -> factsSummary(preparation)).orElse(messages.text("diagnostic.unknown"));
    }

    private void deploy() {
        if (reviewedPreparation == null || reviewedPreparation.archive().isEmpty()) {
            output.setText(messages.text("deployment.analyzeFirst")); return;
        }
        try {
            if (reviewedPreparation.assessment().facts().orElseThrow().projectType() != projectType.getSelectedItem())
                throw new IllegalStateException(messages.text("deployment.sourceTypeChanged"));
            ServerProfile profile = serverContext.profile();
            var server = service.findTrustedServer(profile.id()).orElseThrow(
                    () -> new IllegalStateException(messages.text("deployment.serverFirst")));
            HealthCheck health = healthCheck(); Optional<UserAccessUrl> userAccess = userAccessUrl(health);
            boolean useRoot = rootBuild.isSelected();
            if (useRoot && JOptionPane.showConfirmDialog(owner, messages.text("deployment.reviewedRootConfirm", Map.of(
                    "application", reviewedPreparation.assessment().facts().orElseThrow().applicationId(),
                    "archive", reviewedPreparation.archive().orElseThrow().contentSha256(), "server", server.host())),
                    messages.text("deployment.rootConfirm.title"), JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            DeploymentRuntimeSpecification runtime = runtimeSpecification(health);
            boolean dockerRisk = runtime instanceof DeploymentRuntimeSpecification.Container container
                    && container.engine() == DeploymentRuntimeSpecification.ContainerEngine.DOCKER
                    && JOptionPane.showConfirmDialog(owner, messages.text("deployment.dockerRisk"),
                    messages.text("deployment.dockerRisk.title"), JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
            if (runtime instanceof DeploymentRuntimeSpecification.Container container
                    && container.engine() == DeploymentRuntimeSpecification.ContainerEngine.DOCKER && !dockerRisk) return;
            ConfigurationSnapshot configuration = configurationSnapshot();
            ReviewedDeploymentRequest request = service.createReviewedDeploymentRequest(reviewedPreparation, server, configuration,
                    secretReferences(), runtime, userAccess, useRoot ? new BuildLimits(1800, 1024, 4096,
                            4L * 1024 * 1024, 4L * 1024 * 1024 * 1024, true) : BuildLimits.defaultNonRoot(), useRoot, dockerRisk);
            ReviewedDeploymentPlan plan = service.planDeployment(request);
            if (JOptionPane.showConfirmDialog(owner, messages.text("deployment.reviewedReview", Map.of(
                    "type", messages.text("project.type." + runtime.projectType().name().toLowerCase(Locale.ROOT)),
                    "server", server.host(), "archive", request.archive().contentSha256(), "configuration", configuration.sha256(),
                    "access", accessReview(userAccess), "plan", plan.steps().stream().map(step -> "- " + messages.text(
                            "deployment.plan." + step.name().toLowerCase(Locale.ROOT)) + "\n").reduce("", String::concat))),
                    messages.text("deployment.review.title"), JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            char[] master = serverContext.masterPassword(); output.setText(messages.text("deployment.reviewedRunning"));
            new SwingWorker<DeploymentResult, Void>() {
                @Override protected DeploymentResult doInBackground() throws Exception {
                    service.saveDeploymentConfigurationSnapshot(configuration);
                    return service.deployReviewedWithStoredPassword(request, profile, serverContext.credentialMode(), master,
                            serverContext::confirmFingerprint);
                }
                @Override protected void done() {
                    try {
                        DeploymentResult result = get(); output.setText(resultSummary(result));
                        if (result.status() == DeploymentStatus.SUCCEEDED) applicationSelection.accept(request.facts().applicationId());
                    } catch (Exception exception) {
                        output.setText(messages.text("deployment.failed", Map.of("detail", messages.safe(exception))));
                    }
                }
            }.execute();
        } catch (Exception exception) {
            output.setText(messages.text("deployment.createFailed", Map.of("detail", messages.safe(exception))));
        }
    }

    private String resultSummary(DeploymentResult result) {
        String events = result.events().stream().map(event -> "- " + messages.text(event.succeeded()
                ? "deployment.event.succeeded" : "deployment.event.failed", Map.of("step", messages.text(
                "deployment.step." + event.step()))) + "\n" + event.evidence()).reduce("", (a, b) -> a + b + "\n");
        return messages.text("deployment.result", Map.of("status", messages.text("deployment.status."
                + result.status().name().toLowerCase(Locale.ROOT)), "events", events,
                "handoff", result.finalObservation().map(messages::lifecycle).orElse("")));
    }

    private ConfigurationSnapshot configurationSnapshot() {
        String applicationId = reviewedPreparation.assessment().facts().orElseThrow().applicationId();
        List<ConfigurationEntry> entries = new ArrayList<>();
        for (String item : configurationEntries.getText().split(";")) {
            String value = item.trim(); if (value.isBlank()) continue; int separator = value.indexOf('=');
            if (separator < 1 || separator == value.length() - 1)
                throw new IllegalArgumentException(messages.text("validation.configurationEntry"));
            entries.add(new ConfigurationEntry(value.substring(0, separator).trim(), ConfigurationScope.RUNTIME,
                    configurationValue(value.substring(separator + 1).trim())));
        }
        return ConfigurationSnapshot.create(applicationId, Instant.now().toEpochMilli(), "runtime-v1", Instant.now(), entries);
    }

    private List<SecretReference> secretReferences() {
        List<SecretReference> references = new ArrayList<>();
        for (String item : secretReferences.getText().split(";")) {
            String value = item.trim(); if (value.isEmpty()) continue; int separator = value.lastIndexOf(':');
            if (separator < 1 || separator == value.length() - 1)
                throw new IllegalArgumentException(messages.text("validation.secretReference"));
            references.add(new SecretReference(value.substring(0, separator).trim(),
                    Long.parseLong(value.substring(separator + 1).trim())));
        }
        return List.copyOf(references);
    }

    private void saveSecret() {
        try {
            List<SecretReference> references = secretReferences();
            if (references.size() != 1) throw new IllegalArgumentException(messages.text("validation.secretSingleRevision"));
            SecretReference reference = references.getFirst(); JPasswordField field = new JPasswordField(24);
            if (JOptionPane.showConfirmDialog(owner, field, messages.text("secret.value.title"), JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            CredentialStorageMode mode = serverContext.credentialMode();
            service.saveDeploymentSecretRevision(new StoredApplicationSecretRevision(reference,
                    "application-secret/" + reference.identifier() + "/" + reference.revision(), mode, Instant.now()),
                    mode, serverContext.masterPassword(), field.getPassword());
            output.setText(messages.text("secret.saved", Map.of("reference", reference.identifier() + ":" + reference.revision())));
        } catch (Exception exception) {
            output.setText(messages.text("secret.saveFailed", Map.of("detail", messages.safe(exception))));
        }
    }

    private DeploymentRuntimeSpecification runtimeSpecification(HealthCheck health) {
        return switch ((DeploymentProjectType) projectType.getSelectedItem()) {
            case GRADLE_SPRING_BOOT -> new DeploymentRuntimeSpecification.GradleSpringBoot(health);
            case JAVA_JAR -> new DeploymentRuntimeSpecification.JavaJar(runtimePrimary.getText(), runtimeSecondary.getText(),
                    runtimeVersion.getText(), arguments(jvmArguments.getText()), arguments(applicationArguments.getText()), health);
            case NODE_SERVICE -> new DeploymentRuntimeSpecification.NodeService(Integer.parseInt(runtimeVersion.getText().trim()), health);
            case PYTHON_SERVICE -> new DeploymentRuntimeSpecification.PythonService(runtimePrimary.getText(), runtimeSecondary.getText(), health);
            case STATIC_SITE -> new DeploymentRuntimeSpecification.StaticSite(runtimePrimary.getText(), runtimeVersion.getText().isBlank()
                    ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(runtimeVersion.getText().trim())), requireHttp(health));
            case DOCKERFILE_CONTAINER -> new DeploymentRuntimeSpecification.Container(
                    (DeploymentRuntimeSpecification.ContainerEngine) containerEngine.getSelectedItem(), ports(), volumes(), health);
        };
    }

    private HealthCheck healthCheck() {
        int seconds = Integer.parseInt(timeout.getText().trim());
        return switch ((HealthMode) healthMode.getSelectedItem()) {
            case HTTP -> new HealthCheck.Http(URI.create(healthEndpoint.getText().trim()),
                    Integer.parseInt(expectedStatus.getText().trim()), seconds);
            case TCP -> new HealthCheck.Tcp(Integer.parseInt(healthEndpoint.getText().trim()), seconds,
                    Integer.parseInt(stability.getText().trim()));
        };
    }

    private Optional<UserAccessUrl> userAccessUrl(HealthCheck health) {
        String value = accessUrl.getText().trim();
        if (health instanceof HealthCheck.Http) {
            if (value.isBlank()) throw new IllegalArgumentException(messages.text("validation.httpAccessRequired"));
            return Optional.of(new UserAccessUrl(URI.create(value)));
        }
        if (!value.isBlank()) throw new IllegalArgumentException(messages.text("validation.tcpAccessForbidden"));
        return Optional.empty();
    }

    private Map<Integer, Integer> ports() {
        Map<Integer, Integer> values = new LinkedHashMap<>();
        for (String pair : containerPorts.getText().split(";")) {
            String[] items = pair.trim().split(":", -1);
            if (items.length != 2) throw new IllegalArgumentException(messages.text("validation.containerPort"));
            values.put(Integer.parseInt(items[0].trim()), Integer.parseInt(items[1].trim()));
        }
        return values;
    }

    private List<DeploymentRuntimeSpecification.ManagedVolume> volumes() {
        if (containerVolumes.getText().isBlank()) return List.of();
        List<DeploymentRuntimeSpecification.ManagedVolume> values = new ArrayList<>();
        for (String entry : containerVolumes.getText().split(";")) {
            String[] items = entry.trim().split(":", -1);
            if (items.length < 2 || items.length > 3) throw new IllegalArgumentException(messages.text("validation.containerVolume"));
            boolean readOnly = items.length == 3 && items[2].trim().equalsIgnoreCase("ro");
            if (items.length == 3 && !readOnly && !items[2].trim().equalsIgnoreCase("rw"))
                throw new IllegalArgumentException(messages.text("validation.containerVolume"));
            values.add(new DeploymentRuntimeSpecification.ManagedVolume(items[0].trim(), items[1].trim(), readOnly));
        }
        return List.copyOf(values);
    }

    private void resetRuntimeInputs() {
        runtimePrimary.setText(""); runtimeSecondary.setText(""); runtimeVersion.setText("");
        jvmArguments.setText(""); applicationArguments.setText(""); containerPorts.setText("");
        containerVolumes.setText(""); reviewedPreparation = null;
    }

    private String accessReview(Optional<UserAccessUrl> value) {
        return value.map(url -> messages.text("deployment.httpAccess", Map.of("url", url.url().toASCIIString())))
                .orElse(messages.text("deployment.tcpAccess"));
    }
    private HealthCheck.Http requireHttp(HealthCheck health) {
        if (health instanceof HealthCheck.Http http) return http;
        throw new IllegalArgumentException(messages.text("validation.staticHttpRequired"));
    }
    private static List<String> arguments(String value) { return value.isBlank() ? List.of() : List.of(value.trim().split("\\s+")); }
    private static ConfigurationValue configurationValue(String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return new ConfigurationValue.Flag(Boolean.parseBoolean(value));
        if (value.matches("-?[0-9]+")) return new ConfigurationValue.Number(Long.parseLong(value));
        return new ConfigurationValue.Text(value);
    }
    private static GitReference gitReference(int index, String value) {
        return switch (index) { case 0 -> new GitReference.Branch(value); case 1 -> new GitReference.Tag(value);
            case 2 -> new GitReference.Commit(value); default -> throw new IllegalArgumentException("Unsupported Git reference kind"); };
    }
    private enum HealthMode { HTTP, TCP }
}
