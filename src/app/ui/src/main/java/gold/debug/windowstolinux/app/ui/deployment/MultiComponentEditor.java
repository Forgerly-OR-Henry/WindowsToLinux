package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.app.service.deployment.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.app.ui.i18n.PageMessages;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
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
final class MultiComponentEditor {
    final ApplicationControls application = new ApplicationControls();
    final JTextField componentId = new JTextField(14);
    final JTextField relativeRoot = new JTextField(20);
    final JComboBox<DeploymentProjectType> projectType = new JComboBox<>(DeploymentProjectType.values());
    final JTextField runtimePrimary = new JTextField(18);
    final JTextField runtimeSecondary = new JTextField(18);
    final JTextField runtimeVersion = new JTextField(10);
    final JTextField runtimeArguments = new JTextField(18);
    final JTextField runtimeAdditional = new JTextField(18);
    final JComboBox<MultiComponentHealthMode> healthMode = new JComboBox<>(MultiComponentHealthMode.values());
    final JTextField healthEndpoint = new JTextField(22);
    final JTextField expectedStatus = new JTextField("200", 6);
    final JTextField timeoutSeconds = new JTextField("20", 6);
    final JTextField stabilitySeconds = new JTextField("1", 6);
    final JTextField accessUrl = new JTextField(22);
    final JTextField artifacts = new JTextField(20);
    final JTextField ports = new JTextField(20);
    final JTextField dependencies = new JTextField(20);
    final JTextField configuration = new JTextField(22);
    final JTextField secrets = new JTextField(20);
    final JCheckBox required;
    final JCheckBox rootBuild;
    final LifecycleControls lifecycle = new LifecycleControls();
    final DraftControls draftControls = new DraftControls();
    private final PageMessages messages;

    MultiComponentEditor(PageMessages messages) {
        this.messages = messages;
        required = new JCheckBox(messages.text("component.required"), true);
        rootBuild = new JCheckBox(messages.text("rootBuild"));
        messages.localize(projectType, "project.type.");
        messages.localize(healthMode, "health.mode.");
        messages.localize(lifecycle.action, "lifecycle.action.");
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

    MultiComponentDraft addOrUpdate() {
        MultiComponentDraft draft = draftFromForm();
        draft.analysisRequest();
        draftControls.drafts.put(draft.componentId(), draft);
        if (application.healthComponentId.getText().isBlank()) {
            application.healthComponentId.setText(draft.componentId());
        }
        refreshDraftList();
        return draft;
    }

    Optional<MultiComponentDraft> removeSelected() {
        Optional<MultiComponentDraft> selected = selectedDraft();
        selected.ifPresent(draft -> draftControls.drafts.remove(draft.componentId()));
        if (selected.isPresent()) refreshDraftList();
        return selected;
    }

    List<MultiComponentDraft> orderedDrafts() {
        return draftControls.drafts.values().stream()
                .sorted(Comparator.comparing(MultiComponentDraft::componentId)).toList();
    }

    Set<String> draftIds() {
        return Set.copyOf(draftControls.drafts.keySet());
    }

    boolean isEmpty() {
        return draftControls.drafts.isEmpty();
    }

    MultiComponentDraft draft(String componentId) {
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
        componentId.setText(state.componentId());
        relativeRoot.setText(state.relativeRoot());
        projectType.setSelectedItem(DeploymentProjectType.valueOf(state.projectType()));
        runtimePrimary.setText(state.runtimePrimary());
        runtimeSecondary.setText(state.runtimeSecondary());
        runtimeVersion.setText(state.runtimeVersion());
        runtimeArguments.setText(state.runtimeArguments());
        runtimeAdditional.setText(state.runtimeAdditional());
        healthMode.setSelectedItem(MultiComponentHealthMode.valueOf(state.healthMode()));
        healthEndpoint.setText(state.healthEndpoint());
        expectedStatus.setText(state.expectedStatus());
        timeoutSeconds.setText(state.timeoutSeconds());
        stabilitySeconds.setText(state.stabilitySeconds());
        accessUrl.setText(state.accessUrl());
        artifacts.setText(state.artifacts());
        ports.setText(state.ports());
        dependencies.setText(state.dependencies());
        configuration.setText(state.configuration());
        secrets.setText(state.secrets());
        required.setSelected(state.required());
        rootBuild.setSelected(state.rootBuild());
    }

    private void applyDraft(MultiComponentDraft draft) {
        applyFormState(new MultiComponentFormState(draft.componentId(), draft.relativeSourceRoot(),
                draft.projectType().name(), draft.runtimePrimary(), draft.runtimeSecondary(), draft.runtimeVersion(),
                draft.runtimeArguments(), draft.runtimeAdditional(), draft.healthMode().name(), draft.healthEndpoint(),
                draft.expectedStatus(), draft.timeoutSeconds(), draft.stabilitySeconds(), draft.userAccessUrl(),
                draft.artifactPaths(), draft.declaredPorts(), draft.dependencies(), draft.configurationEntries(),
                draft.secretReferences(), draft.required(), draft.rootBuild()));
    }

    private Optional<MultiComponentDraft> selectedDraft() {
        int index = draftControls.list.getSelectedIndex();
        List<MultiComponentDraft> ordered = orderedDrafts();
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

    static final class DraftControls {
        final DefaultListModel<String> listModel = new DefaultListModel<>();
        final JList<String> list = new JList<>(listModel);
        private final Map<String, MultiComponentDraft> drafts = new LinkedHashMap<>();
    }
}
