package gold.debug.windowstolinux.app.ui.deployment.single;

import gold.debug.windowstolinux.app.service.contract.definition.DatabaseReviewMode;


import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JTextField;

/** Owns deployment controls, non-secret form state, and domain input mapping. / 持有部署控件、非秘密表单状态与领域输入映射。 */
final class DeploymentForm {
    final JComboBox<DeploymentProjectType> projectType = new JComboBox<>(DeploymentProjectType.values());
    final JComboBox<HealthMode> healthMode = new JComboBox<>(HealthMode.values());
    final JTextField healthEndpoint = new JTextField(30);
    final JTextField expectedStatus = new JTextField("200", 4);
    final JTextField timeout = new JTextField("10", 4);
    final JTextField stability = new JTextField("5", 4);
    final JTextField accessUrl = new JTextField(30);
    final JTextField runtimePrimary = new JTextField(20);
    final JTextField runtimeSecondary = new JTextField(20);
    final JTextField runtimeVersion = new JTextField(6);
    final JTextField kotlinJvmTarget = new JTextField(6);
    final JTextField jvmArguments = new JTextField(20);
    final JTextField applicationArguments = new JTextField(20);
    final JComboBox<DeploymentRuntimeSpecification.ContainerEngineType> containerEngine =
            new JComboBox<>(DeploymentRuntimeSpecification.ContainerEngineType.values());
    final JTextField containerPorts = new JTextField(20);
    final JTextField containerVolumes = new JTextField(20);
    final JTextField configurationEntries = new JTextField(30);
    final JComboBox<DatabaseReviewMode> databaseMode =
            new JComboBox<>(DatabaseReviewMode.values());
    final JTextField databaseDetails = new JTextField(30);
    final JTextField secretReferences = new JTextField(20);
    final JCheckBox experimentalAdapterRisk;
    private final PageMessagePresenter messages;

    DeploymentForm(PageMessagePresenter messages, Runnable reviewInvalidation) {
        this.messages = messages;
        experimentalAdapterRisk = new JCheckBox(messages.text("experimentalAdapterRisk"));
        messages.localize(projectType, "project.type.");
        messages.localize(healthMode, "health.mode.");
        messages.localize(containerEngine, "container.engine.");
        messages.localize(databaseMode, "database.review.mode.");
        projectType.addActionListener(event -> {
            resetRuntimeInputs();
            reviewInvalidation.run();
        });
        databaseMode.addActionListener(event -> databaseDetails.setEnabled(
                databaseMode.getSelectedItem() != DatabaseReviewMode.UNREVIEWED
                        && databaseMode.getSelectedItem() != DatabaseReviewMode.NONE));
        containerEngine.setSelectedItem(null);
        databaseMode.setSelectedItem(DatabaseReviewMode.UNREVIEWED);
        databaseDetails.setEnabled(false);
    }

    DeploymentPageState capture(String output, ReviewedSourcePreparation preparation) {
        return new DeploymentPageState(projectType().name(),
                ((HealthMode) healthMode.getSelectedItem()).name(), healthEndpoint.getText(), expectedStatus.getText(),
                timeout.getText(), stability.getText(), accessUrl.getText(), runtimePrimary.getText(), runtimeSecondary.getText(),
                runtimeVersion.getText(), jvmArguments.getText(), applicationArguments.getText(),
                containerEngine.getSelectedItem() == null ? ""
                : ((DeploymentRuntimeSpecification.ContainerEngineType) containerEngine.getSelectedItem()).name(),
                containerPorts.getText(), containerVolumes.getText(), configurationEntries.getText(),
                ((DatabaseReviewMode) databaseMode.getSelectedItem()).name(),
                databaseDetails.getText(), secretReferences.getText(), false, experimentalAdapterRisk.isSelected(),
                output, preparation, kotlinJvmTarget.getText());
    }

    void restore(DeploymentPageState state) {
        projectType.setSelectedItem(DeploymentProjectType.valueOf(state.projectType()));
        healthMode.setSelectedItem(HealthMode.valueOf(state.healthMode()));
        healthEndpoint.setText(state.healthEndpoint());
        expectedStatus.setText(state.expectedHttpStatus());
        timeout.setText(state.healthTimeoutSeconds());
        stability.setText(state.tcpStabilitySeconds());
        accessUrl.setText(state.userAccessUrl());
        runtimePrimary.setText(state.runtimePrimary());
        runtimeSecondary.setText(state.runtimeSecondary());
        runtimeVersion.setText(state.javaVersion());
        kotlinJvmTarget.setText(state.kotlinJvmTarget());
        jvmArguments.setText(state.jvmArguments());
        applicationArguments.setText(state.applicationArguments());
        containerEngine.setSelectedItem(state.containerEngine().isBlank() ? null
                : DeploymentRuntimeSpecification.ContainerEngineType.valueOf(state.containerEngine()));
        containerPorts.setText(state.containerPorts());
        containerVolumes.setText(state.containerVolumes());
        configurationEntries.setText(state.configurationEntries());
        databaseMode.setSelectedItem(DatabaseReviewMode.valueOf(state.databaseMode()));
        databaseDetails.setText(state.databaseDetails());
        secretReferences.setText(state.secretReferences());
        experimentalAdapterRisk.setSelected(state.experimentalAdapterRisk());
    }

    DeploymentProjectType projectType() {
        return (DeploymentProjectType) projectType.getSelectedItem();
    }

    /** Captures controls without parsing configuration, databases or runtime notation. / 捕获控件，不解析配置、数据库或运行时记法。 */
    gold.debug.windowstolinux.app.service.contract.definition.DeploymentFormInput input(boolean detectType) {
        return new gold.debug.windowstolinux.app.service.contract.definition.DeploymentFormInput(detectType, projectType(),
                runtimePrimary.getText(), runtimeSecondary.getText(), runtimeVersion.getText(), kotlinJvmTarget.getText(),
                configurationEntries.getText(), secretReferences.getText(), (DatabaseReviewMode) databaseMode.getSelectedItem(),
                databaseDetails.getText(), ((HealthMode) healthMode.getSelectedItem()).name(), healthEndpoint.getText(),
                expectedStatus.getText(), timeout.getText(), stability.getText(), accessUrl.getText(), jvmArguments.getText(),
                applicationArguments.getText(), containerPorts.getText(), containerVolumes.getText(),
                (DeploymentRuntimeSpecification.ContainerEngineType) containerEngine.getSelectedItem(), experimentalAdapterRisk.isSelected());
    }

    void applyRuntimeSuggestions(ReviewedSourcePreparation preparation) {
        DeploymentRuntimeAssessment suggestion = preparation.assessment().runtimeSuggestion().orElse(null);
        if (suggestion == null) return;
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_JAR_PATH).ifPresent(runtimePrimary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_SOURCE_ROOT).ifPresent(runtimePrimary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_MAIN_CLASS).ifPresent(runtimeSecondary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.KOTLIN_JVM_TARGET).ifPresent(kotlinJvmTarget::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_VERSION).ifPresent(runtimeVersion::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.NODE_MAJOR_VERSION).ifPresent(runtimeVersion::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.PYTHON_VERSION).ifPresent(runtimePrimary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.PYTHON_ENTRYPOINT).ifPresent(runtimeSecondary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.STATIC_OUTPUT_DIRECTORY).ifPresent(runtimePrimary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.SERVICE_ARTIFACT).ifPresent(runtimePrimary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.SERVICE_ENTRYPOINT).ifPresent(runtimeSecondary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.SERVICE_VERSION).ifPresent(runtimeVersion::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.CMAKE_PRESET).ifPresent(runtimeVersion::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.CMAKE_TARGET).ifPresent(runtimeSecondary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.CMAKE_ARTIFACT).ifPresent(runtimePrimary::setText);
        if (!suggestion.suggestedContainerPorts().isEmpty()) {
            containerPorts.setText(suggestion.suggestedContainerPorts().entrySet().stream()
                    .map(entry -> entry.getKey() + ":" + entry.getValue()).reduce((a, b) -> a + ";" + b).orElse(""));
        }
        if (!suggestion.suggestedManagedVolumes().isEmpty()) {
            containerVolumes.setText(suggestion.suggestedManagedVolumes().stream()
                    .map(volume -> volume.name() + ":" + volume.containerPath() + (volume.readOnly() ? ":ro" : ":rw"))
                    .reduce((a, b) -> a + ";" + b).orElse(""));
        }
    }

    private void resetRuntimeInputs() {
        runtimePrimary.setText("");
        runtimeSecondary.setText("");
        runtimeVersion.setText("");
        kotlinJvmTarget.setText("");
        jvmArguments.setText("");
        applicationArguments.setText("");
        containerPorts.setText("");
        containerVolumes.setText("");
    }

    private enum HealthMode {
        /** Source-backed automatic selection. / 依据源码自动选择。 */ AUTOMATIC,
        /** HTTP health check. / HTTP 健康检查。 */ HTTP,
        /** TCP health check. / TCP 健康检查。 */ TCP
    }
}
