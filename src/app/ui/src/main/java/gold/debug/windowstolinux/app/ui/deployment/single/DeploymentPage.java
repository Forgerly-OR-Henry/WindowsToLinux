package gold.debug.windowstolinux.app.ui.deployment.single;

import gold.debug.windowstolinux.app.ui.deployment.ReviewContext;
import gold.debug.windowstolinux.app.ui.deployment.DeploymentRuntimeParser;

import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.service.contract.DeploymentApplicationFacade;
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
    private final DeploymentApplicationFacade service;
    private final ServerContext serverContext;
    private final PageMessagePresenter messages;
    private final Consumer<String> applicationSelection;
    private final Runnable openServers;
    private final DeploymentAnalysisPresenter presenter;
    private final DeploymentForm form;
    private final JTextArea output = DesktopComponentFactory.outputArea();
    private final JPanel panel;
    private ReviewedSourcePreparation reviewedPreparation;

    /** Creates the stateful deployment controller. / 创建有状态部署控制器。 */
    public DeploymentPage(JFrame owner, DeploymentApplicationFacade service, ServerContext serverContext,
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
        c.addField(healthForm, 0, 0, messages.text("field.healthMode"), form.healthMode);
        c.addField(healthForm, 0, 1, messages.text("field.healthEndpoint"), form.healthEndpoint);
        c.addField(healthForm, 1, 0, messages.text("field.expectedStatus"), form.expectedStatus);
        c.addField(healthForm, 1, 1, messages.text("field.timeout"), form.timeout);
        c.addField(healthForm, 2, 0, messages.text("field.tcpStability"), form.stability);
        c.addField(healthForm, 2, 1, messages.text("field.userAccessUrl"), form.accessUrl);
        health.add(healthForm, BorderLayout.CENTER);
        JPanel risk = c.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        form.rootBuild.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0)); risk.add(form.rootBuild);
        form.experimentalAdapterRisk.setBorder(BorderFactory.createEmptyBorder(4, 16, 0, 0));
        risk.add(form.experimentalAdapterRisk); health.add(risk, BorderLayout.SOUTH);
        JPanel runtime = c.card(new BorderLayout(0, 12));
        runtime.add(c.sectionHeading(messages.text("section.runtime.title"), messages.text("section.runtime.description")), BorderLayout.NORTH);
        JPanel form = c.transparent(new GridBagLayout());
        c.addField(form, 0, 0, messages.text("field.projectType"), this.form.projectType);
        c.addField(form, 0, 1, messages.text("field.runtimePrimary"), this.form.runtimePrimary);
        c.addField(form, 1, 0, messages.text("field.runtimeSecondary"), this.form.runtimeSecondary);
        c.addField(form, 1, 1, messages.text("field.runtimeVersion"), this.form.runtimeVersion);
        c.addField(form, 2, 0, messages.text("field.jvmArguments"), this.form.jvmArguments);
        c.addField(form, 2, 1, messages.text("field.applicationArguments"), this.form.applicationArguments);
        c.addField(form, 3, 0, messages.text("field.containerEngine"), this.form.containerEngine);
        c.addField(form, 3, 1, messages.text("field.containerPorts"), this.form.containerPorts);
        c.addField(form, 4, 0, messages.text("field.containerVolumes"), this.form.containerVolumes);
        c.addField(form, 4, 1, messages.text("field.configurationEntries"), this.form.configurationEntries);
        c.addField(form, 5, 0, messages.text("field.databaseReviewMode"), this.form.databaseMode);
        c.addField(form, 5, 1, messages.text("field.databaseDetails"), this.form.databaseDetails);
        c.addField(form, 6, 0, messages.text("field.secretReferences"), this.form.secretReferences);
        JButton secret = c.secondaryButton(messages.text("button.saveSecretRevision")); secret.addActionListener(event -> saveSecret());
        c.addField(form, 6, 1, messages.text("field.secretRevision"), secret);
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
        DeploymentProjectType selected = form.projectType();
        output.setText(messages.text("source.analyzing"));
        DesktopTaskExecutor.run(
                () -> service.prepareReviewedSource(source, selected),
                preparation -> applyAnalysis(preparation, selected, false),
                exception -> output.setText(messages.text("source.failed",
                        Map.of("detail", messages.safe(exception)))));
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
            GitSourceRequest request = new GitSourceRequest(remote,
                    DeploymentRuntimeParser.gitReference(kinds.indexOf(kind), referenceText),
                    java.util.Set.of(remote.host().orElseThrow()), 4L * 1024 * 1024 * 1024, false);
            DeploymentProjectType selected = form.projectType();
            output.setText(messages.text("git.analyzing"));
            DesktopTaskExecutor.run(
                    () -> service.prepareReviewedGitSource(request, selected),
                    preparation -> applyAnalysis(preparation, selected, true),
                    exception -> output.setText(messages.text("git.failed",
                            Map.of("detail", messages.safe(exception)))));
        } catch (Exception exception) {
            output.setText(messages.text("git.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    private void applyAnalysis(ReviewedSourcePreparation preparation, DeploymentProjectType selected, boolean git) {
        reviewedPreparation = preparation;
        if (preparation.archive().isEmpty()) {
            output.setText(messages.text("source.reviewedUnavailable", Map.of("reasons", presenter.rejections(preparation))));
            return;
        }
        form.applyRuntimeSuggestions(preparation);
        if (git) {
            output.setText(messages.text("git.reviewedSuccess", Map.of(
                    "commit", preparation.sourceRevision().orElseThrow().commit().orElseThrow(),
                    "archive", preparation.archive().orElseThrow().contentSha256(), "facts", presenter.facts(preparation))));
        } else {
            String excluded = preparation.excludedEntries().isEmpty() ? "" : messages.text("source.excluded", Map.of("entries",
                    preparation.excludedEntries().stream().map(entry -> "- " + entry + "\n").reduce("", String::concat)));
            output.setText(messages.text("source.reviewedSuccess", Map.of(
                    "type", messages.text("project.type." + selected.name().toLowerCase(Locale.ROOT)),
                    "archive", preparation.archive().orElseThrow().localArchive(), "facts", presenter.facts(preparation),
                    "excluded", excluded)));
        }
    }

    private void deploy() {
        if (reviewedPreparation == null || reviewedPreparation.archive().isEmpty()) {
            output.setText(messages.text("deployment.analyzeFirst")); return;
        }
        try {
            if (reviewedPreparation.assessment().facts().orElseThrow().projectType() != form.projectType())
                throw new IllegalStateException(messages.text("deployment.sourceTypeChanged"));
            ServerProfile profile = serverContext.profile();
            var server = service.findTrustedServer(profile.id()).orElseThrow(
                    () -> new IllegalStateException(messages.text("deployment.serverFirst")));
            HealthCheck health = form.healthCheck();
            Optional<UserAccessUrl> userAccess = form.userAccessUrl(health);
            boolean useRoot = form.rootBuild.isSelected();
            if (useRoot && JOptionPane.showConfirmDialog(owner, messages.text("deployment.reviewedRootConfirm", Map.of(
                    "application", reviewedPreparation.assessment().facts().orElseThrow().applicationId(),
                    "archive", reviewedPreparation.archive().orElseThrow().contentSha256(), "server", server.host())),
                    messages.text("deployment.rootConfirm.title"), JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            DeploymentRuntimeSpecification runtime = form.runtimeSpecification(health);
            boolean experimentalRisk = reviewedPreparation.assessment().facts().orElseThrow().support().level()
                    != DeploymentSupportLevel.EXPERIMENTAL_ADAPTER || form.experimentalAdapterRisk.isSelected();
            if (!experimentalRisk) {
                output.setText(messages.text("deployment.experimentalRiskRequired"));
                return;
            }
            boolean dockerRisk = runtime instanceof DeploymentRuntimeSpecification.Container container
                    && container.engine() == DeploymentRuntimeSpecification.ContainerEngineType.DOCKER
                    && JOptionPane.showConfirmDialog(owner, messages.text("deployment.dockerRisk"),
                    messages.text("deployment.dockerRisk.title"), JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
            if (runtime instanceof DeploymentRuntimeSpecification.Container container
                    && container.engine() == DeploymentRuntimeSpecification.ContainerEngineType.DOCKER && !dockerRisk) return;
            ConfigurationSnapshot configuration = form.configurationSnapshot(
                    reviewedPreparation.assessment().facts().orElseThrow().applicationId());
            List<SecretReference> secretReferences = form.secretReferences();
            Optional<List<ManagedDatabaseBinding>> databaseBindings = form.databaseBindings();
            ReviewedDeploymentRequest request = service.createReviewedDeploymentRequest(reviewedPreparation, server, configuration,
                    secretReferences, databaseBindings, runtime, userAccess,
                    useRoot ? new BuildLimitConfiguration(1800, 1024, 4096,
                             4L * 1024 * 1024, 4L * 1024 * 1024 * 1024, true) : BuildLimitConfiguration.defaultNonRoot(), useRoot,
                    dockerRisk, experimentalRisk);
            ReviewedDeploymentPlan plan = service.planDeployment(request);
            if (JOptionPane.showConfirmDialog(owner, messages.text("deployment.reviewedReview", Map.of(
                     "type", messages.text("project.type." + runtime.projectType().name().toLowerCase(Locale.ROOT)),
                     "server", server.host(), "archive", request.archive().contentSha256(), "configuration", configuration.sha256(),
                     "database", databaseReview(databaseBindings), "access", accessReview(userAccess),
                     "plan", plan.steps().stream().map(step -> "- " + messages.text(
                             "deployment.plan." + step.name().toLowerCase(Locale.ROOT)) + "\n").reduce("", String::concat))),
                    messages.text("deployment.review.title"), JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            char[] master = serverContext.masterPassword(); output.setText(messages.text("deployment.reviewedRunning"));
            DesktopTaskExecutor.run(() -> {
                    service.saveDeploymentConfigurationSnapshot(configuration);
                    return service.deployReviewedWithStoredPassword(request, profile, serverContext.credentialMode(), master,
                            serverContext::confirmFingerprint);
                }, outcome -> {
                    output.setText(resultSummary(outcome.result()));
                    if (outcome.status() == DeploymentStatus.SUCCEEDED) {
                        applicationSelection.accept(request.facts().applicationId());
                    }
                }, exception -> output.setText(messages.text("deployment.failed",
                        Map.of("detail", messages.safe(exception)))));
        } catch (Exception exception) {
            output.setText(messages.text("deployment.createFailed", Map.of("detail", messages.safe(exception))));
        }
    }

    private String resultSummary(DeploymentResult result) {
        String events = result.events().stream().map(event -> "- " + messages.text(event.succeeded()
                ? "deployment.event.succeeded" : "deployment.event.failed", Map.of("step", messages.text(
                "deployment.step." + event.step().code()))) + event.failure().map(failure -> " ["
                + failure.code() + " / " + failure.operationIdentity() + "]").orElse("")
                + "\n" + event.evidence()).reduce("", (a, b) -> a + b + "\n");
        String warnings = result.nonFatalFailures().stream().map(failure -> messages.text("failure.warning.summary",
                        Map.of("code", failure.code(), "message", messages.catalog().text(failure.userMessage()),
                                "recovery", messages.text("failure.recovery."
                                        + failure.recoveryDisposition().name().toLowerCase(Locale.ROOT)))))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return messages.text("deployment.result", Map.of("status", messages.text("deployment.status."
                + result.status().name().toLowerCase(Locale.ROOT)), "events", events,
                "handoff", result.finalObservation().map(messages::lifecycle).orElse("")))
                + "\n\n" + messages.text("failure.operation.summary",
                Map.of("operationId", result.operationIdentity().toString()))
                + (warnings.isBlank() ? "" : "\n" + warnings);
    }

    private String databaseReview(Optional<List<ManagedDatabaseBinding>> bindings) {
        List<ManagedDatabaseBinding> reviewed = bindings.orElseThrow();
        if (reviewed.isEmpty()) return messages.text("database.review.none");
        return reviewed.stream().map(this::databaseBindingReview)
                .reduce((left, right) -> left + ", " + right).orElseThrow();
    }

    private String databaseBindingReview(ManagedDatabaseBinding binding) {
        if (binding.connection() instanceof ManagedDatabaseConnection.Sqlite sqlite) {
            return messages.text("database.review.sqlite", Map.of("id", binding.databaseId(),
                    "file", sqlite.fileName()));
        }
        ManagedDatabaseConnection.Server connection = (ManagedDatabaseConnection.Server) binding.connection();
        return messages.text("database.review.server", Map.of("engine", connection.engine().name(),
                "id", binding.databaseId(), "host", connection.host(), "port", connection.port(),
                "database", connection.database()));
    }

    private void saveSecret() {
        try {
            List<SecretReference> references = form.secretReferences();
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

    private String accessReview(Optional<UserAccessUrl> value) {
        return value.map(url -> messages.text("deployment.httpAccess", Map.of("url", url.url().toASCIIString())))
                .orElse(messages.text("deployment.tcpAccess"));
    }
}
