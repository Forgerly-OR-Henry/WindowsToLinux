package gold.debug.windowstolinux.app.ui.deployment.multi;

import gold.debug.windowstolinux.app.service.contract.definition.ComponentHealthMode;

import gold.debug.windowstolinux.app.service.contract.definition.ComponentFormInput;

import gold.debug.windowstolinux.shared.model.deployment.DatabaseReviewMode;

import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.app.service.contract.MultiComponentApplicationFacade;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import javax.swing.DefaultListModel;
import gold.debug.windowstolinux.app.ui.component.ToggleSwitch;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Owns multi-component controls, draft state, selection, and domain input mapping. / 持有多组件控件、草稿状态、选择与领域输入映射。 */
final class MultiComponentDraftController {
    final ApplicationControls application = new ApplicationControls();
    final JTextField componentId = new JTextField(14);
    final JTextField relativeRoot = new JTextField(20);
    final JComboBox<DeploymentProjectType> projectType = new JComboBox<>(DeploymentProjectType.values());
    final JTextField runtimePrimary = new JTextField(18);
    final JTextField runtimeSecondary = new JTextField(18);
    final JTextField kotlinJvmTarget = new JTextField(6);
    final JTextField runtimeVersion = new JTextField(10);
    final JTextField runtimeArguments = new JTextField(18);
    final JTextField runtimeAdditional = new JTextField(18);
    final JComboBox<ComponentHealthMode> healthMode = new JComboBox<>(ComponentHealthMode.values());
    final JTextField healthEndpoint = new JTextField(22);
    final JTextField expectedStatus = new JTextField("200", 6);
    final JTextField timeoutSeconds = new JTextField("20", 6);
    final JTextField stabilitySeconds = new JTextField("5", 6);
    final JTextField accessUrl = new JTextField(22);
    final JTextField artifacts = new JTextField(20);
    final JTextField ports = new JTextField(20);
    final JTextField dependencies = new JTextField(20);
    final ResourceControls resources = new ResourceControls();
    final ToggleSwitch required;
    final LifecycleControls lifecycle = new LifecycleControls();
    final DraftControls draftControls = new DraftControls();
    private final PageMessagePresenter messages;
    private final MultiComponentApplicationFacade service;

    MultiComponentDraftController(PageMessagePresenter messages, MultiComponentApplicationFacade service) {
        this.messages = messages;
        this.service = service;
        required = new ToggleSwitch(null, true);
        messages.localize(projectType, "project.type.");
        messages.localize(healthMode, "health.mode.");
        messages.localize(resources.databaseMode, "database.review.mode.");
        messages.localize(lifecycle.action, "lifecycle.action.");
        resources.databaseMode.addActionListener(event -> resources.databaseDetails.setEnabled(
                resources.databaseMode.getSelectedItem() != DatabaseReviewMode.UNREVIEWED
                        && resources.databaseMode.getSelectedItem() != DatabaseReviewMode.NONE));
        resources.databaseMode.setSelectedItem(DatabaseReviewMode.UNREVIEWED);
        resources.databaseDetails.setEnabled(false);
        draftControls.list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        draftControls.list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) selectedDraft().ifPresent(this::applyDraft);
        });
    }

    MultiComponentPageState capture(String output, PreparedMultiComponentSource preparation,
                                    ReviewedMultiComponentApplication review) {
        return new MultiComponentPageState(application.root.getText(), application.id.getText(),
                application.healthComponentId.getText(), lifecycle.targets.getText(), lifecycleAction(), formState(),
                orderedDrafts(), output, preparation, review);
    }

    void restore(MultiComponentPageState state) {
        application.root.setText(state.applicationRoot());
        application.id.setText(state.applicationId());
        application.healthComponentId.setText(state.healthComponentId());
        lifecycle.targets.setText(state.lifecycleTargets());
        lifecycle.action.setSelectedItem(state.lifecycleAction());
        applyFormState(state.form());
        draftControls.drafts.clear();
        state.drafts().forEach(draft -> draftControls.drafts.put(draft.componentId(), draft));
        refreshDraftList();
    }

    ComponentFormInput addOrUpdate() {
        ComponentFormInput draft = draftFromForm();
        service.parseComponentAnalysis(draft);
        draftControls.drafts.put(draft.componentId(), draft);
        if (application.healthComponentId.getText().isBlank()) {
            application.healthComponentId.setText(draft.componentId());
        }
        refreshDraftList();
        return draft;
    }

    Optional<ComponentFormInput> removeSelected() {
        Optional<ComponentFormInput> selected = selectedDraft();
        selected.ifPresent(draft -> draftControls.drafts.remove(draft.componentId()));
        if (selected.isPresent()) refreshDraftList();
        return selected;
    }

    List<ComponentFormInput> orderedDrafts() {
        return draftControls.drafts.values().stream()
                .sorted(Comparator.comparing(ComponentFormInput::componentId)).toList();
    }

    Set<String> draftIds() {
        return Set.copyOf(draftControls.drafts.keySet());
    }

    boolean isEmpty() {
        return draftControls.drafts.isEmpty();
    }

    ComponentFormInput draft(String componentId) {
        return draftControls.drafts.get(componentId);
    }

    String applicationRoot() {
        return application.root.getText().trim();
    }

    String applicationId() {
        return application.id.getText().trim();
    }

    String healthComponentId() {
        return application.healthComponentId.getText().trim();
    }

    LifecycleAction lifecycleAction() {
        return (LifecycleAction) lifecycle.action.getSelectedItem();
    }

    Set<String> lifecycleTargets() {
        return identifiers(lifecycle.targets.getText());
    }

    private ComponentFormInput draftFromForm() {
        return new ComponentFormInput(componentId.getText(), relativeRoot.getText(),
                (DeploymentProjectType) projectType.getSelectedItem(), runtimePrimary.getText(),
                runtimeSecondary.getText(), runtimeVersion.getText(), runtimeArguments.getText(),
                runtimeAdditional.getText(), (ComponentHealthMode) healthMode.getSelectedItem(),
                healthEndpoint.getText(), expectedStatus.getText(), timeoutSeconds.getText(), stabilitySeconds.getText(),
                accessUrl.getText(), artifacts.getText(), ports.getText(), dependencies.getText(),
                resources.configuration.getText(),
                (DatabaseReviewMode) resources.databaseMode.getSelectedItem(),
                resources.databaseDetails.getText(), resources.secrets.getText(), required.isSelected(), false, kotlinJvmTarget.getText(), resources.applicationDeclaration.getText());
    }

    private MultiComponentFormState formState() {
        return new MultiComponentFormState(componentId.getText(), relativeRoot.getText(),
                ((DeploymentProjectType) projectType.getSelectedItem()).name(), runtimePrimary.getText(),
                runtimeSecondary.getText(), runtimeVersion.getText(), runtimeArguments.getText(),
                runtimeAdditional.getText(), ((ComponentHealthMode) healthMode.getSelectedItem()).name(),
                healthEndpoint.getText(), expectedStatus.getText(), timeoutSeconds.getText(), stabilitySeconds.getText(),
                accessUrl.getText(), artifacts.getText(), ports.getText(), dependencies.getText(),
                resources.configuration.getText(),
                ((DatabaseReviewMode) resources.databaseMode.getSelectedItem()).name(),
                resources.databaseDetails.getText(), resources.secrets.getText(), required.isSelected(), false, kotlinJvmTarget.getText(), resources.applicationDeclaration.getText());
    }

    private void applyFormState(MultiComponentFormState state) {
        resources.applicationDeclaration.setText(state.applicationDeclaration());
        componentId.setText(state.componentId());
        relativeRoot.setText(state.relativeRoot());
        projectType.setSelectedItem(DeploymentProjectType.valueOf(state.projectType()));
        runtimePrimary.setText(state.runtimePrimary());
        runtimeSecondary.setText(state.runtimeSecondary());
        runtimeVersion.setText(state.runtimeVersion());
        kotlinJvmTarget.setText(state.kotlinJvmTarget());
        runtimeArguments.setText(state.runtimeArguments());
        runtimeAdditional.setText(state.runtimeAdditional());
        healthMode.setSelectedItem(ComponentHealthMode.valueOf(state.healthMode()));
        healthEndpoint.setText(state.healthEndpoint());
        expectedStatus.setText(state.expectedStatus());
        timeoutSeconds.setText(state.timeoutSeconds());
        stabilitySeconds.setText(state.stabilitySeconds());
        accessUrl.setText(state.accessUrl());
        artifacts.setText(state.artifacts());
        ports.setText(state.ports());
        dependencies.setText(state.dependencies());
        resources.configuration.setText(state.configuration());
        resources.databaseMode.setSelectedItem(DatabaseReviewMode.valueOf(state.databaseMode()));
        resources.databaseDetails.setText(state.databaseDetails());
        resources.secrets.setText(state.secrets());
        required.setSelected(state.required());
    }

    private void applyDraft(ComponentFormInput draft) {
        applyFormState(new MultiComponentFormState(draft.componentId(), draft.relativeSourceRoot(),
                draft.projectType().name(), draft.runtimePrimary(), draft.runtimeSecondary(), draft.runtimeVersion(),
                draft.runtimeArguments(), draft.runtimeAdditional(), draft.healthMode().name(), draft.healthEndpoint(),
                draft.expectedStatus(), draft.timeoutSeconds(), draft.stabilitySeconds(), draft.userAccessUrl(),
                draft.artifactPaths(), draft.declaredPorts(), draft.dependencies(), draft.configurationEntries(),
                draft.databaseMode().name(), draft.databaseDetails(), draft.secretReferences(), draft.required(),
                draft.rootBuild(), draft.kotlinJvmTarget(), draft.applicationDeclaration()));
    }

    private Optional<ComponentFormInput> selectedDraft() {
        int index = draftControls.list.getSelectedIndex();
        List<ComponentFormInput> ordered = orderedDrafts();
        return index < 0 || index >= ordered.size() ? Optional.empty() : Optional.of(ordered.get(index));
    }

    private void refreshDraftList() {
        draftControls.listModel.clear();
        orderedDrafts().forEach(draft -> draftControls.listModel.addElement(draft.componentId() + " | "
                + messages.text("project.type." + draft.projectType().name().toLowerCase(Locale.ROOT)) + " | "
                + draft.relativeSourceRoot()));
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

    static final class ApplicationControls {
        final JTextField root = new JTextField(30);
        final JTextField id = new JTextField(18);
        final JTextField healthComponentId = new JTextField(14);
    }

    static final class LifecycleControls {
        final JTextField targets = new JTextField(22);
        final JComboBox<LifecycleAction> action = new JComboBox<>(LifecycleAction.values());
    }

    /** Groups the non-secret resource review controls for one component. / 组合一个组件不含秘密值的资源审阅控件。 */
    static final class ResourceControls {
        final javax.swing.JTextArea applicationDeclaration = new javax.swing.JTextArea(6, 30);
        final JTextField configuration = new JTextField(22);
        final JComboBox<DatabaseReviewMode> databaseMode =
                new JComboBox<>(DatabaseReviewMode.values());
        final JTextField databaseDetails = new JTextField(22);
        final JTextField secrets = new JTextField(20);
    }

    static final class DraftControls {
        final DefaultListModel<String> listModel = new DefaultListModel<>();
        final JList<String> list = new JList<>(listModel);
        private final Map<String, ComponentFormInput> drafts = new LinkedHashMap<>();
    }
}
