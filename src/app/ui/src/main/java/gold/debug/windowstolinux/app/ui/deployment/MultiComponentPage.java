package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.app.service.DesktopApplicationService;
import gold.debug.windowstolinux.app.service.deployment.MultiComponentReviewInput;
import gold.debug.windowstolinux.app.service.deployment.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.app.ui.component.DesktopComponents;
import gold.debug.windowstolinux.app.ui.server.ServerContext;
import gold.debug.windowstolinux.app.ui.shell.PageMessages;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.result.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.deploy.result.MultiComponentLifecycleResult;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentSupportLevel;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Desktop product page for explicit mixed-project review, whole-application deployment, and lifecycle.
 *
 * <p>用于显式混合项目审阅、整应用部署和生命周期的桌面产品页面。
 */
public final class MultiComponentPage {
    private final JFrame owner;
    private final DesktopApplicationService service;
    private final ServerContext serverContext;
    private final PageMessages messages;
    private final Runnable openServers;
    private final Consumer<String> applicationSelection;
    private final MultiComponentResultPresenter presenter;
    private final JTextField applicationRoot = new JTextField(30);
    private final JTextField applicationId = new JTextField(18);
    private final JTextField healthComponentId = new JTextField(14);
    private final JTextField componentId = new JTextField(14);
    private final JTextField relativeRoot = new JTextField(20);
    private final JComboBox<DeploymentProjectType> projectType = new JComboBox<>(DeploymentProjectType.values());
    private final JTextField runtimePrimary = new JTextField(18);
    private final JTextField runtimeSecondary = new JTextField(18);
    private final JTextField runtimeVersion = new JTextField(10);
    private final JTextField runtimeArguments = new JTextField(18);
    private final JTextField runtimeAdditional = new JTextField(18);
    private final JComboBox<MultiComponentHealthMode> healthMode =
            new JComboBox<>(MultiComponentHealthMode.values());
    private final JTextField healthEndpoint = new JTextField(22);
    private final JTextField expectedStatus = new JTextField("200", 6);
    private final JTextField timeoutSeconds = new JTextField("20", 6);
    private final JTextField stabilitySeconds = new JTextField("1", 6);
    private final JTextField accessUrl = new JTextField(22);
    private final JTextField artifacts = new JTextField(20);
    private final JTextField ports = new JTextField(20);
    private final JTextField dependencies = new JTextField(20);
    private final JTextField configuration = new JTextField(22);
    private final JTextField secrets = new JTextField(20);
    private final JCheckBox required;
    private final JCheckBox rootBuild;
    private final JTextField lifecycleTargets = new JTextField(22);
    private final JComboBox<LifecycleAction> lifecycleAction = new JComboBox<>(LifecycleAction.values());
    private final JTextArea output = DesktopComponents.outputArea();
    private final DefaultListModel<String> draftListModel = new DefaultListModel<>();
    private final JList<String> draftList = new JList<>(draftListModel);
    private final Map<String, MultiComponentDraft> drafts = new LinkedHashMap<>();
    private final JPanel panel;
    private PreparedMultiComponentSource preparation;
    private ReviewedMultiComponentApplication review;

    /** Creates the stateful multi-component controller. / 创建有状态多组件控制器。 */
    public MultiComponentPage(JFrame owner, DesktopApplicationService service, ServerContext serverContext,
                              DesktopComponents components, PageMessages messages, Runnable openServers,
                              Consumer<String> applicationSelection) {
        this.owner = owner;
        this.service = service;
        this.serverContext = serverContext;
        this.messages = messages;
        this.openServers = openServers;
        this.applicationSelection = applicationSelection;
        presenter = new MultiComponentResultPresenter(messages);
        required = new JCheckBox(messages.text("component.required"), true);
        rootBuild = new JCheckBox(messages.text("rootBuild"));
        messages.localize(projectType, "project.type.");
        messages.localize(healthMode, "health.mode.");
        messages.localize(lifecycleAction, "lifecycle.action.");
        draftList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        draftList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) selectedDraft().ifPresent(this::applyDraft);
        });
        panel = createPanel(components);
    }

    /** Returns the page panel. / 返回页面面板。 */
    public JPanel panel() { return panel; }

    /** Captures all non-secret editor and review state. / 捕获全部不含秘密的编辑与审阅状态。 */
    public MultiComponentPageState captureState() {
        return new MultiComponentPageState(applicationRoot.getText(), applicationId.getText(),
                healthComponentId.getText(), lifecycleTargets.getText(),
                (LifecycleAction) lifecycleAction.getSelectedItem(), formState(), orderedDrafts(), output.getText(),
                preparation, review);
    }

    /** Restores all non-secret editor and review state. / 恢复全部不含秘密的编辑与审阅状态。 */
    public void restoreState(MultiComponentPageState state) {
        applicationRoot.setText(state.applicationRoot());
        applicationId.setText(state.applicationId());
        healthComponentId.setText(state.healthComponentId());
        lifecycleTargets.setText(state.lifecycleTargets());
        lifecycleAction.setSelectedItem(state.lifecycleAction());
        applyFormState(state.form());
        drafts.clear();
        state.drafts().forEach(draft -> drafts.put(draft.componentId(), draft));
        refreshDraftList();
        output.setText(state.output());
        preparation = state.preparation();
        review = state.review();
    }

    private JPanel createPanel(DesktopComponents components) {
        JPanel page = components.pagePanel();
        JPanel actions = components.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton root = components.secondaryButton(messages.text("component.button.selectRoot"));
        root.addActionListener(event -> chooseRoot());
        JButton add = components.secondaryButton(messages.text("component.button.add"));
        add.addActionListener(event -> addOrUpdate());
        JButton remove = components.secondaryButton(messages.text("component.button.remove"));
        remove.addActionListener(event -> removeSelected());
        JButton analyze = components.primaryButton(messages.text("component.button.analyze"));
        analyze.addActionListener(event -> analyze());
        JButton servers = components.secondaryButton(messages.text("button.goServer"));
        servers.addActionListener(event -> openServers.run());
        JButton deploy = components.primaryButton(messages.text("component.button.deploy"));
        deploy.addActionListener(event -> deploy());
        actions.add(root); actions.add(add); actions.add(remove); actions.add(analyze); actions.add(servers); actions.add(deploy);
        page.add(actions, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(messages.text("component.tab.graph"), graphPanel(components));
        tabs.addTab(messages.text("component.tab.result"), resultPanel(components));
        page.add(tabs, BorderLayout.CENTER);
        return page;
    }

    private JPanel graphPanel(DesktopComponents components) {
        JPanel graph = components.transparent(new BorderLayout(12, 12));
        JPanel form = components.card(new GridBagLayout());
        int row = 0;
        components.addField(form, row, 0, messages.text("component.field.applicationRoot"), applicationRoot);
        components.addField(form, row++, 1, messages.text("component.field.applicationId"), applicationId);
        components.addField(form, row, 0, messages.text("component.field.id"), componentId);
        components.addField(form, row++, 1, messages.text("component.field.relativeRoot"), relativeRoot);
        components.addField(form, row, 0, messages.text("field.projectType"), projectType);
        components.addField(form, row++, 1, messages.text("component.field.healthOwner"), healthComponentId);
        components.addField(form, row, 0, messages.text("field.runtimePrimary"), runtimePrimary);
        components.addField(form, row++, 1, messages.text("field.runtimeSecondary"), runtimeSecondary);
        components.addField(form, row, 0, messages.text("field.runtimeVersion"), runtimeVersion);
        components.addField(form, row++, 1, messages.text("field.jvmArguments"), runtimeArguments);
        components.addField(form, row, 0, messages.text("component.field.runtimeAdditional"), runtimeAdditional);
        components.addField(form, row++, 1, messages.text("field.healthMode"), healthMode);
        components.addField(form, row, 0, messages.text("field.healthEndpoint"), healthEndpoint);
        components.addField(form, row++, 1, messages.text("field.expectedStatus"), expectedStatus);
        components.addField(form, row, 0, messages.text("field.timeout"), timeoutSeconds);
        components.addField(form, row++, 1, messages.text("field.tcpStability"), stabilitySeconds);
        components.addField(form, row, 0, messages.text("field.userAccessUrl"), accessUrl);
        components.addField(form, row++, 1, messages.text("component.field.artifacts"), artifacts);
        components.addField(form, row, 0, messages.text("component.field.ports"), ports);
        components.addField(form, row++, 1, messages.text("component.field.dependencies"), dependencies);
        components.addField(form, row, 0, messages.text("field.configurationEntries"), configuration);
        components.addField(form, row++, 1, messages.text("field.secretReferences"), secrets);
        JPanel flags = components.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        required.setBorder(BorderFactory.createEmptyBorder());
        rootBuild.setBorder(BorderFactory.createEmptyBorder());
        flags.add(required); flags.add(rootBuild);
        components.addField(form, row, 0, messages.text("component.field.flags"), flags);
        graph.add(new JScrollPane(form), BorderLayout.CENTER);
        draftList.setPreferredSize(new Dimension(290, 0));
        graph.add(new JScrollPane(draftList), BorderLayout.EAST);
        return graph;
    }

    private JPanel resultPanel(DesktopComponents components) {
        JPanel result = components.transparent(new BorderLayout(0, 12));
        result.add(components.outputCard(messages.text("component.result.title"),
                messages.text("component.result.description"), output), BorderLayout.CENTER);
        JPanel lifecycle = components.card(new FlowLayout(FlowLayout.LEFT, 8, 0));
        lifecycle.add(lifecycleAction);
        lifecycle.add(lifecycleTargets);
        JButton execute = components.primaryButton(messages.text("component.button.lifecycle"));
        execute.addActionListener(event -> lifecycle());
        lifecycle.add(execute);
        result.add(lifecycle, BorderLayout.SOUTH);
        return result;
    }

    private void chooseRoot() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            applicationRoot.setText(chooser.getSelectedFile().toPath().toString());
            invalidateReview();
        }
    }

    private void addOrUpdate() {
        try {
            MultiComponentDraft draft = draftFromForm();
            draft.analysisRequest();
            drafts.put(draft.componentId(), draft);
            if (healthComponentId.getText().isBlank()) healthComponentId.setText(draft.componentId());
            refreshDraftList();
            invalidateReview();
            output.setText(messages.text("component.draft.saved", Map.of("component", draft.componentId())));
        } catch (Exception exception) {
            output.setText(messages.text("component.draft.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    private void removeSelected() {
        selectedDraft().ifPresent(draft -> {
            drafts.remove(draft.componentId());
            refreshDraftList();
            invalidateReview();
        });
    }

    private void analyze() {
        try {
            if (drafts.isEmpty()) throw new IllegalArgumentException(messages.text("component.validation.empty"));
            Path root = Path.of(applicationRoot.getText().trim());
            String id = applicationId.getText().trim();
            List<gold.debug.windowstolinux.shared.analyze.component.ComponentAnalysisRequest> requests =
                    orderedDrafts().stream().map(MultiComponentDraft::analysisRequest).toList();
            output.setText(messages.text("component.analysis.running"));
            new SwingWorker<PreparedMultiComponentSource, Void>() {
                /** Runs the background task. / 运行后台任务。 */
                @Override protected PreparedMultiComponentSource doInBackground() throws Exception {
                    return service.prepareReviewedMultiComponentSource(root, id, requests);
                }
                /** Completes the background task on the UI thread. / 在 UI 线程完成后台任务。 */
                @Override protected void done() {
                    try {
                        preparation = get();
                        review = null;
                        output.setText(presenter.analysis(preparation));
                    } catch (Exception exception) {
                        output.setText(messages.text("component.analysis.failed",
                                Map.of("detail", messages.safe(exception))));
                    }
                }
            }.execute();
        } catch (Exception exception) {
            output.setText(messages.text("component.analysis.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    private void deploy() {
        if (preparation == null || preparation.components().isEmpty()) {
            output.setText(messages.text("component.validation.analyzeFirst"));
            return;
        }
        try {
            ensureDraftCoverage();
            ServerProfile profile = serverContext.profile();
            var server = service.findTrustedServer(profile.id()).orElseThrow(
                    () -> new IllegalStateException(messages.text("deployment.serverFirst")));
            List<String> rootComponents = orderedDrafts().stream().filter(MultiComponentDraft::rootBuild)
                    .map(MultiComponentDraft::componentId).toList();
            if (!confirmRisk("component.rootRisk.title", "component.rootRisk", rootComponents)) return;
            List<String> dockerComponents = orderedDrafts().stream().filter(draft ->
                            draft.projectType() == DeploymentProjectType.DOCKERFILE_CONTAINER
                                    && "DOCKER".equalsIgnoreCase(draft.runtimePrimary()))
                    .map(MultiComponentDraft::componentId).toList();
            if (!confirmRisk("deployment.dockerRisk.title", "component.dockerRisk", dockerComponents)) return;
            List<String> experimentalComponents = preparation.assessment().components().stream()
                    .filter(component -> component.facts().support().level() == DeploymentSupportLevel.EXPERIMENTAL_ADAPTER)
                    .map(component -> component.componentId()).toList();
            if (!confirmRisk("component.experimentalRisk.title", "component.experimentalRisk",
                    experimentalComponents)) return;
            List<MultiComponentReviewInput> inputs = new ArrayList<>();
            for (var component : preparation.assessment().components()) {
                MultiComponentDraft draft = drafts.get(component.componentId());
                inputs.add(draft.reviewInput(component.facts().applicationId(),
                        dockerComponents.contains(component.componentId()),
                        experimentalComponents.contains(component.componentId())));
            }
            String healthOwner = healthComponentId.getText().trim();
            MultiComponentDraft ownerDraft = drafts.get(healthOwner);
            if (ownerDraft == null) throw new IllegalArgumentException(messages.text("component.validation.healthOwner"));
            ReviewedMultiComponentApplication candidate = service.createReviewedMultiComponentApplication(
                    preparation, server, inputs, new ApplicationHealthGate(healthOwner, ownerDraft.healthCheck()));
            if (JOptionPane.showConfirmDialog(owner, presenter.review(candidate), messages.text("component.review.title"),
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            char[] masterPassword = serverContext.masterPassword();
            output.setText(messages.text("component.deployment.running"));
            new SwingWorker<MultiComponentDeploymentResult, Void>() {
                /** Runs the background task. / 运行后台任务。 */
                @Override protected MultiComponentDeploymentResult doInBackground() throws Exception {
                    for (MultiComponentReviewInput input : inputs) {
                        service.saveDeploymentConfigurationSnapshot(input.configuration());
                    }
                    return service.deployReviewedMultiComponentWithStoredPassword(candidate, profile,
                            serverContext.credentialMode(), masterPassword, serverContext::confirmFingerprint);
                }
                /** Completes the background task on the UI thread. / 在 UI 线程完成后台任务。 */
                @Override protected void done() {
                    try {
                        MultiComponentDeploymentResult result = get();
                        output.setText(presenter.deployment(result));
                        if (result.status() == DeploymentStatus.SUCCEEDED) {
                            review = candidate;
                            applicationSelection.accept(candidate.components().getFirst().application().id());
                        }
                    } catch (Exception exception) {
                        output.setText(messages.text("component.deployment.failed",
                                Map.of("detail", messages.safe(exception))));
                    }
                }
            }.execute();
        } catch (Exception exception) {
            output.setText(messages.text("component.deployment.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    private void lifecycle() {
        try {
            String managedApplicationId = applicationId.getText().trim();
            if (managedApplicationId.isEmpty()) throw new IllegalArgumentException(
                    messages.text("component.validation.applicationId"));
            LifecycleAction action = (LifecycleAction) lifecycleAction.getSelectedItem();
            Set<String> targets = identifiers(lifecycleTargets.getText());
            ServerProfile profile = serverContext.profile();
            char[] masterPassword = serverContext.masterPassword();
            Set<String> selectedTargets = Set.copyOf(targets);
            output.setText(messages.text("component.lifecycle.running"));
            new SwingWorker<MultiComponentLifecycleResult, Void>() {
                /** Runs the background task. / 运行后台任务。 */
                @Override protected MultiComponentLifecycleResult doInBackground() throws Exception {
                    var managed = service.findManagedMultiComponentApplication(managedApplicationId).orElseThrow(
                            () -> new IllegalStateException(messages.text("component.validation.deployFirst")));
                    Set<String> effectiveTargets = selectedTargets.isEmpty() && action != LifecycleAction.REFRESH_STATUS
                            ? Set.copyOf(managed.plan().startOrder()) : selectedTargets;
                    return service.executeManagedMultiComponentLifecycleWithStoredPassword(managedApplicationId,
                            effectiveTargets, action, profile, serverContext.credentialMode(), masterPassword);
                }
                /** Completes the background task on the UI thread. / 在 UI 线程完成后台任务。 */
                @Override protected void done() {
                    try {
                        output.setText(presenter.lifecycle(get()));
                    } catch (Exception exception) {
                        output.setText(messages.text("component.lifecycle.failed",
                                Map.of("detail", messages.safe(exception))));
                    }
                }
            }.execute();
        } catch (Exception exception) {
            output.setText(messages.text("component.lifecycle.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    private boolean confirmRisk(String titleKey, String messageKey, List<String> componentIds) {
        return componentIds.isEmpty() || JOptionPane.showConfirmDialog(owner,
                messages.text(messageKey, Map.of("components", String.join(", ", componentIds))),
                messages.text(titleKey), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private void ensureDraftCoverage() {
        Set<String> preparedIds = preparation.components().keySet();
        Path currentRoot = Path.of(applicationRoot.getText().trim()).toAbsolutePath().normalize();
        if (!preparedIds.equals(drafts.keySet())
                || !preparation.assessment().applicationRoot().equals(currentRoot)
                || !preparation.assessment().applicationId().equals(applicationId.getText().trim())) {
            throw new IllegalStateException(messages.text("component.validation.graphChanged"));
        }
    }

    private void invalidateReview() {
        preparation = null;
        review = null;
    }

    private MultiComponentDraft draftFromForm() {
        return new MultiComponentDraft(componentId.getText(), relativeRoot.getText(),
                (DeploymentProjectType) projectType.getSelectedItem(), runtimePrimary.getText(),
                runtimeSecondary.getText(), runtimeVersion.getText(), runtimeArguments.getText(),
                runtimeAdditional.getText(), (MultiComponentHealthMode) healthMode.getSelectedItem(),
                healthEndpoint.getText(), expectedStatus.getText(), timeoutSeconds.getText(), stabilitySeconds.getText(),
                accessUrl.getText(), artifacts.getText(), ports.getText(), dependencies.getText(),
                configuration.getText(), secrets.getText(), required.isSelected(), rootBuild.isSelected());
    }

    private MultiComponentFormState formState() {
        return new MultiComponentFormState(componentId.getText(), relativeRoot.getText(),
                ((DeploymentProjectType) projectType.getSelectedItem()).name(), runtimePrimary.getText(),
                runtimeSecondary.getText(), runtimeVersion.getText(), runtimeArguments.getText(),
                runtimeAdditional.getText(), ((MultiComponentHealthMode) healthMode.getSelectedItem()).name(),
                healthEndpoint.getText(), expectedStatus.getText(), timeoutSeconds.getText(), stabilitySeconds.getText(),
                accessUrl.getText(), artifacts.getText(), ports.getText(), dependencies.getText(), configuration.getText(),
                secrets.getText(), required.isSelected(), rootBuild.isSelected());
    }

    private void applyFormState(MultiComponentFormState state) {
        componentId.setText(state.componentId()); relativeRoot.setText(state.relativeRoot());
        projectType.setSelectedItem(DeploymentProjectType.valueOf(state.projectType()));
        runtimePrimary.setText(state.runtimePrimary()); runtimeSecondary.setText(state.runtimeSecondary());
        runtimeVersion.setText(state.runtimeVersion()); runtimeArguments.setText(state.runtimeArguments());
        runtimeAdditional.setText(state.runtimeAdditional());
        healthMode.setSelectedItem(MultiComponentHealthMode.valueOf(state.healthMode()));
        healthEndpoint.setText(state.healthEndpoint()); expectedStatus.setText(state.expectedStatus());
        timeoutSeconds.setText(state.timeoutSeconds()); stabilitySeconds.setText(state.stabilitySeconds());
        accessUrl.setText(state.accessUrl()); artifacts.setText(state.artifacts()); ports.setText(state.ports());
        dependencies.setText(state.dependencies()); configuration.setText(state.configuration());
        secrets.setText(state.secrets()); required.setSelected(state.required()); rootBuild.setSelected(state.rootBuild());
    }

    private void applyDraft(MultiComponentDraft draft) {
        applyFormState(new MultiComponentFormState(draft.componentId(), draft.relativeSourceRoot(),
                draft.projectType().name(), draft.runtimePrimary(), draft.runtimeSecondary(), draft.runtimeVersion(),
                draft.runtimeArguments(), draft.runtimeAdditional(), draft.healthMode().name(), draft.healthEndpoint(),
                draft.expectedStatus(), draft.timeoutSeconds(), draft.stabilitySeconds(), draft.userAccessUrl(),
                draft.artifactPaths(), draft.declaredPorts(), draft.dependencies(), draft.configurationEntries(),
                draft.secretReferences(), draft.required(), draft.rootBuild()));
    }

    private java.util.Optional<MultiComponentDraft> selectedDraft() {
        int index = draftList.getSelectedIndex();
        List<MultiComponentDraft> ordered = orderedDrafts();
        return index < 0 || index >= ordered.size() ? java.util.Optional.empty()
                : java.util.Optional.of(ordered.get(index));
    }

    private void refreshDraftList() {
        draftListModel.clear();
        orderedDrafts().forEach(draft -> draftListModel.addElement(draft.componentId() + " | "
                + messages.text("project.type." + draft.projectType().name().toLowerCase(Locale.ROOT)) + " | "
                + draft.relativeSourceRoot()));
    }

    private List<MultiComponentDraft> orderedDrafts() {
        return drafts.values().stream().sorted(Comparator.comparing(MultiComponentDraft::componentId)).toList();
    }

    private static Set<String> identifiers(String value) {
        if (value.isBlank()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split("[,;]")) {
            String id = token.trim();
            if (!id.matches("[a-z0-9][a-z0-9-]{0,62}")) {
                throw new IllegalArgumentException("lifecycle target component identifier is invalid");
            }
            result.add(id);
        }
        return Set.copyOf(result);
    }
}
