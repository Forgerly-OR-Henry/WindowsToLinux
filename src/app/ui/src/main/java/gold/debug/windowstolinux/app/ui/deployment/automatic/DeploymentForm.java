package gold.debug.windowstolinux.app.ui.deployment.automatic;

import gold.debug.windowstolinux.shared.model.deployment.DatabaseReviewMode;


import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;

import gold.debug.windowstolinux.app.ui.component.ToggleSwitch;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.JTextArea;

/**
 * Owns deployment controls, non-secret form state, and domain input mapping. / 持有部署控件、非秘密表单状态与领域输入映射。
 */
final class DeploymentForm {
    /**
     * Supported project deployment category.
     * <p>受支持的项目部署类别。
     */
    final JComboBox<DeploymentProjectType> projectType = new JComboBox<>(DeploymentProjectType.values());
    /**
     * Health mode.
     * <p>健康模式。
     */
    final JComboBox<HealthMode> healthMode = new JComboBox<>(HealthMode.values());
    /**
     * Swing control for health endpoint.
     * <p>健康端点对应的 Swing 控件。
     */
    final JTextField healthEndpoint = new JTextField(30);
    /**
     * Swing control for expected status.
     * <p>预期状态对应的 Swing 控件。
     */
    final JTextField expectedStatus = new JTextField("200", 4);
    /**
     * Swing control for timeout.
     * <p>超时对应的 Swing 控件。
     */
    final JTextField timeout = new JTextField("10", 4);
    /**
     * Swing control for stability.
     * <p>稳定性对应的 Swing 控件。
     */
    final JTextField stability = new JTextField("5", 4);
    /**
     * Swing control for access url.
     * <p>访问URL对应的 Swing 控件。
     */
    final JTextField accessUrl = new JTextField(30);
    /**
     * Swing control for runtime primary.
     * <p>运行时主对应的 Swing 控件。
     */
    final JTextField runtimePrimary = new JTextField(20);
    /**
     * Swing control for runtime secondary.
     * <p>运行时次要对应的 Swing 控件。
     */
    final JTextField runtimeSecondary = new JTextField(20);
    /**
     * Swing control for runtime version.
     * <p>运行时版本对应的 Swing 控件。
     */
    final JTextField runtimeVersion = new JTextField(6);
    /**
     * Swing control for kotlin jvm target.
     * <p>kotlinJvm目标对应的 Swing 控件。
     */
    final JTextField kotlinJvmTarget = new JTextField(6);
    /**
     * Swing control for jvm arguments.
     * <p>jvm参数对应的 Swing 控件。
     */
    final JTextField jvmArguments = new JTextField(20);
    /**
     * Swing control for application arguments.
     * <p>应用参数对应的 Swing 控件。
     */
    final JTextField applicationArguments = new JTextField(20);
    /**
     * Container engine.
     * <p>容器引擎。
     */
    final JComboBox<DeploymentRuntimeSpecification.ContainerEngineType> containerEngine =
            new JComboBox<>(DeploymentRuntimeSpecification.ContainerEngineType.values());
    /**
     * Swing control for container ports.
     * <p>容器端口集合对应的 Swing 控件。
     */
    final JTextField containerPorts = new JTextField(20);
    /**
     * Swing control for container volumes.
     * <p>容器卷集合对应的 Swing 控件。
     */
    final JTextField containerVolumes = new JTextField(20);
    /**
     * Swing control for application declaration.
     * <p>应用声明对应的 Swing 控件。
     */
    final JTextArea applicationDeclaration = new JTextArea(6, 30);
    /**
     * Swing control for configuration entries.
     * <p>配置条目对应的 Swing 控件。
     */
    final JTextField configurationEntries = new JTextField(30);
    /**
     * The explicit database review mode.
     * <p>显式数据库审阅模式。
     */
    final JComboBox<DatabaseReviewMode> databaseMode =
            new JComboBox<>(DatabaseReviewMode.values());
    /**
     * Swing control for database details.
     * <p>数据库详情对应的 Swing 控件。
     */
    final JTextField databaseDetails = new JTextField(30);
    /**
     * Swing control for secret references.
     * <p>秘密引用集合对应的 Swing 控件。
     */
    final JTextField secretReferences = new JTextField(20);
    /**
     * Experimental adapter risk.
     * <p>实验性适配器风险。
     */
    final ToggleSwitch experimentalAdapterRisk;
    /**
     * Bound page message presenter collaborator for localized message resolver.
     * <p>处理本地化消息解析器的页面消息展示器协作对象。
     */
    private final PageMessagePresenter messages;

    /**
     * Binds the supplied dependencies and state for deployment form.
     * <p>为部署表单绑定传入的依赖及状态。
     *
     * @param messages localized message resolver / 本地化消息解析器
     * @param reviewInvalidation review invalidation / 审阅Invalidation
     */
    DeploymentForm(PageMessagePresenter messages, Runnable reviewInvalidation) {
        this.messages = messages;
        experimentalAdapterRisk = new ToggleSwitch();
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

    /**
     * Builds deployment page state from the supplied capture inputs.
     * <p>根据所提供捕获输入构建部署页面状态。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param preparation preparation / 准备
     * @return deployment page state from the supplied capture inputs / 根据所提供捕获输入构建部署页面状态
     */
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
                output, preparation, kotlinJvmTarget.getText(), applicationDeclaration.getText());
    }

    /**
     * Restores the saved runtime form values and dependent control selections.
     * <p>恢复保存的运行表单值及依赖控件选择。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     */
    void restore(DeploymentPageState state) {
        projectType.setSelectedItem(DeploymentProjectType.valueOf(state.projectType()));
        healthMode.setSelectedItem(HealthMode.valueOf(state.healthMode()));
        healthEndpoint.setText(state.healthEndpoint());
        applicationDeclaration.setText(state.applicationDeclaration());
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

    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    DeploymentProjectType projectType() {
        return (DeploymentProjectType) projectType.getSelectedItem();
    }

    /**
     * Captures controls without parsing configuration, databases or runtime notation. / 捕获控件，不解析配置、数据库或运行时记法。
     *
     * @param detectType detect type / 识别类型
     * @return constructed or resolved deployment form input / 构造或解析得到的部署表单输入
     */
    gold.debug.windowstolinux.app.service.contract.definition.DeploymentFormInput input(boolean detectType) {
        return new gold.debug.windowstolinux.app.service.contract.definition.DeploymentFormInput(detectType, projectType(),
                runtimePrimary.getText(), runtimeSecondary.getText(), runtimeVersion.getText(), kotlinJvmTarget.getText(),
                configurationEntries.getText(), secretReferences.getText(), (DatabaseReviewMode) databaseMode.getSelectedItem(),
                databaseDetails.getText(), ((HealthMode) healthMode.getSelectedItem()).name(), healthEndpoint.getText(),
                expectedStatus.getText(), timeout.getText(), stability.getText(), accessUrl.getText(), jvmArguments.getText(),
                applicationArguments.getText(), containerPorts.getText(), containerVolumes.getText(),
                (DeploymentRuntimeSpecification.ContainerEngineType) containerEngine.getSelectedItem(), experimentalAdapterRisk.isSelected(), applicationDeclaration.getText());
    }

    /**
     * Applies evidenced runtime suggestions to form controls while retaining unresolved choices for review.
     * <p>将有证据的运行建议应用到表单控件，并保留未解决选择供审阅。
     *
     * @param preparation preparation / 准备
     */
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

    /**
     * Resets runtime inputs.
     * <p>重置运行时输入集合。
     */
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

    /**
     * Selects the health-probe form whose fields are submitted for review.
     * <p>选择待提交审阅的健康探测表单类型。
     */
    private enum HealthMode {
        /**
         * Source-backed automatic selection. / 依据源码自动选择。
         */ AUTOMATIC,
        /**
         * HTTP health check. / HTTP 健康检查。
         */ HTTP,
        /**
         * TCP health check. / TCP 健康检查。
         */ TCP,
        /**
         * PROCESS classification within health mode.
         * <p>健康模式中的进程分类。
         */
         PROCESS,
        /**
         * COMMAND classification within health mode.
         * <p>健康模式中的命令分类。
         */
         COMMAND,
        /**
         * UDP classification within health mode.
         * <p>健康模式中的UDP分类。
         */
         UDP
    }
}
