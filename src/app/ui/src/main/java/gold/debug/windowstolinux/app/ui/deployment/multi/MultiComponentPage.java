package gold.debug.windowstolinux.app.ui.deployment.multi;

import gold.debug.windowstolinux.app.service.contract.MultiComponentApplicationFacade;
import gold.debug.windowstolinux.app.service.deployment.multi.MultiComponentReviewInput;
import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.server.ServerContext;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.MultiComponentLifecycleResult;
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
    private final MultiComponentApplicationFacade service;
    private final ServerContext serverContext;
    private final PageMessagePresenter messages;
    private final Runnable openServers;
    private final Consumer<String> applicationSelection;
    private final MultiComponentResultPresenter presenter;
    private final MultiComponentDraftController editor;
    private final JTextArea output = DesktopComponentFactory.outputArea();
    private final JPanel panel;
    private PreparedMultiComponentSource preparation;
    private ReviewedMultiComponentApplication review;

    /** Creates the stateful multi-component controller. / 创建有状态多组件控制器。 */
    public MultiComponentPage(JFrame owner, MultiComponentApplicationFacade service, ServerContext serverContext,
                              DesktopComponentFactory components, PageMessagePresenter messages, Runnable openServers,
                              Consumer<String> applicationSelection) {
        this.owner = owner;
        this.service = service;
        this.serverContext = serverContext;
        this.messages = messages;
        this.openServers = openServers;
        this.applicationSelection = applicationSelection;
        presenter = new MultiComponentResultPresenter(messages);
        editor = new MultiComponentDraftController(messages);
        panel = createPanel(components);
    }

    /** Returns the page panel. / 返回页面面板。 */
    public JPanel panel() { return panel; }

    /** Captures all non-secret editor and review state. / 捕获全部不含秘密的编辑与审阅状态。 */
    public MultiComponentPageState captureState() {
        return editor.capture(output.getText(), preparation, review);
    }

    /** Restores all non-secret editor and review state. / 恢复全部不含秘密的编辑与审阅状态。 */
    public void restoreState(MultiComponentPageState state) {
        editor.restore(state);
        output.setText(state.output());
        preparation = state.preparation();
        review = state.review();
    }

    private JPanel createPanel(DesktopComponentFactory components) {
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

    private JPanel graphPanel(DesktopComponentFactory components) {
        JPanel graph = components.transparent(new BorderLayout(12, 12));
        JPanel form = components.card(new GridBagLayout());
        int row = 0;
        components.addField(form, row, 0, messages.text("component.field.applicationRoot"), editor.application.root);
        components.addField(form, row++, 1, messages.text("component.field.applicationId"), editor.application.id);
        components.addField(form, row, 0, messages.text("component.field.id"), editor.componentId);
        components.addField(form, row++, 1, messages.text("component.field.relativeRoot"), editor.relativeRoot);
        components.addField(form, row, 0, messages.text("field.projectType"), editor.projectType);
        components.addField(form, row++, 1, messages.text("component.field.healthOwner"), editor.application.healthComponentId);
        components.addField(form, row, 0, messages.text("field.runtimePrimary"), editor.runtimePrimary);
        components.addField(form, row++, 1, messages.text("field.runtimeSecondary"), editor.runtimeSecondary);
        components.addField(form, row, 0, messages.text("field.runtimeVersion"), editor.runtimeVersion);
        components.addField(form, row++, 1, messages.text("field.jvmArguments"), editor.runtimeArguments);
        components.addField(form, row, 0, messages.text("component.field.runtimeAdditional"), editor.runtimeAdditional);
        components.addField(form, row++, 1, messages.text("field.healthMode"), editor.healthMode);
        components.addField(form, row, 0, messages.text("field.healthEndpoint"), editor.healthEndpoint);
        components.addField(form, row++, 1, messages.text("field.expectedStatus"), editor.expectedStatus);
        components.addField(form, row, 0, messages.text("field.timeout"), editor.timeoutSeconds);
        components.addField(form, row++, 1, messages.text("field.tcpStability"), editor.stabilitySeconds);
        components.addField(form, row, 0, messages.text("field.userAccessUrl"), editor.accessUrl);
        components.addField(form, row++, 1, messages.text("component.field.artifacts"), editor.artifacts);
        components.addField(form, row, 0, messages.text("component.field.ports"), editor.ports);
        components.addField(form, row++, 1, messages.text("component.field.dependencies"), editor.dependencies);
        components.addField(form, row, 0, messages.text("field.configurationEntries"), editor.resources.configuration);
        components.addField(form, row++, 1, messages.text("field.secretReferences"), editor.resources.secrets);
        components.addField(form, row, 0, messages.text("field.databaseReviewMode"), editor.resources.databaseMode);
        components.addField(form, row++, 1, messages.text("field.databaseDetails"), editor.resources.databaseDetails);
        JPanel flags = components.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        editor.required.setBorder(BorderFactory.createEmptyBorder());
        editor.rootBuild.setBorder(BorderFactory.createEmptyBorder());
        flags.add(editor.required); flags.add(editor.rootBuild);
        components.addField(form, row, 0, messages.text("component.field.flags"), flags);
        graph.add(new JScrollPane(form), BorderLayout.CENTER);
        editor.draftControls.list.setPreferredSize(new Dimension(290, 0));
        graph.add(new JScrollPane(editor.draftControls.list), BorderLayout.EAST);
        return graph;
    }

    private JPanel resultPanel(DesktopComponentFactory components) {
        JPanel result = components.transparent(new BorderLayout(0, 12));
        result.add(components.outputCard(messages.text("component.result.title"),
                messages.text("component.result.description"), output), BorderLayout.CENTER);
        JPanel lifecycle = components.card(new FlowLayout(FlowLayout.LEFT, 8, 0));
        lifecycle.add(editor.lifecycle.action);
        lifecycle.add(editor.lifecycle.targets);
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
            editor.application.root.setText(chooser.getSelectedFile().toPath().toString());
            invalidateReview();
        }
    }

    private void addOrUpdate() {
        try {
            MultiComponentDraft draft = editor.addOrUpdate();
            invalidateReview();
            output.setText(messages.text("component.draft.saved", Map.of("component", draft.componentId())));
        } catch (Exception exception) {
            output.setText(messages.text("component.draft.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    private void removeSelected() {
        editor.removeSelected().ifPresent(draft -> {
            invalidateReview();
        });
    }

    private void analyze() {
        try {
            if (editor.isEmpty()) throw new IllegalArgumentException(messages.text("component.validation.empty"));
            Path root = Path.of(editor.applicationRoot());
            String id = editor.applicationId();
            List<gold.debug.windowstolinux.shared.analyze.component.ComponentAnalysisRequest> requests =
                    editor.orderedDrafts().stream().map(MultiComponentDraft::analysisRequest).toList();
            output.setText(messages.text("component.analysis.running"));
            DesktopTaskExecutor.run(
                    () -> service.prepareReviewedMultiComponentSource(root, id, requests),
                    result -> {
                        preparation = result;
                        review = null;
                        output.setText(presenter.analysis(preparation));
                    },
                    exception -> output.setText(messages.text("component.analysis.failed",
                            Map.of("detail", messages.safe(exception)))));
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
            List<String> rootComponents = editor.orderedDrafts().stream().filter(MultiComponentDraft::rootBuild)
                    .map(MultiComponentDraft::componentId).toList();
            if (!confirmRisk("component.rootRisk.title", "component.rootRisk", rootComponents)) return;
            List<String> dockerComponents = editor.orderedDrafts().stream().filter(draft ->
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
                MultiComponentDraft draft = editor.draft(component.componentId());
                inputs.add(draft.reviewInput(component.facts().applicationId(),
                        dockerComponents.contains(component.componentId()),
                        experimentalComponents.contains(component.componentId())));
            }
            String healthOwner = editor.healthComponentId();
            MultiComponentDraft ownerDraft = editor.draft(healthOwner);
            if (ownerDraft == null) throw new IllegalArgumentException(messages.text("component.validation.healthOwner"));
            ReviewedMultiComponentApplication candidate = service.createReviewedMultiComponentApplication(
                    preparation, server, inputs, new ApplicationHealthGate(healthOwner, ownerDraft.healthCheck()));
            if (JOptionPane.showConfirmDialog(owner, presenter.review(candidate), messages.text("component.review.title"),
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            char[] masterPassword = serverContext.masterPassword();
            output.setText(messages.text("component.deployment.running"));
            DesktopTaskExecutor.run(() -> {
                    for (MultiComponentReviewInput input : inputs) {
                        service.saveDeploymentConfigurationSnapshot(input.configuration());
                    }
                    return service.deployReviewedMultiComponentWithStoredPassword(candidate, profile,
                            serverContext.credentialMode(), masterPassword, serverContext::confirmFingerprint);
                }, result -> {
                    output.setText(presenter.deployment(result));
                    if (result.status() == DeploymentStatus.SUCCEEDED) {
                        review = candidate;
                        applicationSelection.accept(candidate.components().getFirst().application().id());
                    }
                }, exception -> output.setText(messages.text("component.deployment.failed",
                        Map.of("detail", messages.safe(exception)))));
        } catch (Exception exception) {
            output.setText(messages.text("component.deployment.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    private void lifecycle() {
        try {
            String managedApplicationId = editor.applicationId();
            if (managedApplicationId.isEmpty()) throw new IllegalArgumentException(
                    messages.text("component.validation.applicationId"));
            LifecycleAction action = editor.lifecycleAction();
            Set<String> targets = editor.lifecycleTargets();
            ServerProfile profile = serverContext.profile();
            char[] masterPassword = serverContext.masterPassword();
            Set<String> selectedTargets = Set.copyOf(targets);
            output.setText(messages.text("component.lifecycle.running"));
            DesktopTaskExecutor.run(() -> {
                    var managed = service.findManagedMultiComponentApplication(managedApplicationId).orElseThrow(
                            () -> new IllegalStateException(messages.text("component.validation.deployFirst")));
                    Set<String> effectiveTargets = selectedTargets.isEmpty() && action != LifecycleAction.REFRESH_STATUS
                            ? Set.copyOf(managed.plan().startOrder()) : selectedTargets;
                    return service.executeManagedMultiComponentLifecycleWithStoredPassword(managedApplicationId,
                            effectiveTargets, action, profile, serverContext.credentialMode(), masterPassword);
                }, result -> output.setText(presenter.lifecycle(result)),
                    exception -> output.setText(messages.text("component.lifecycle.failed",
                            Map.of("detail", messages.safe(exception)))));
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
        Path currentRoot = Path.of(editor.applicationRoot()).toAbsolutePath().normalize();
        if (!preparedIds.equals(editor.draftIds())
                || !preparation.assessment().applicationRoot().equals(currentRoot)
                || !preparation.assessment().applicationId().equals(editor.applicationId())) {
            throw new IllegalStateException(messages.text("component.validation.graphChanged"));
        }
    }

    private void invalidateReview() {
        preparation = null;
        review = null;
    }

}
