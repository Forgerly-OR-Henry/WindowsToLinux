package gold.debug.windowstolinux.app.ui.deployment.single;

import gold.debug.windowstolinux.app.ui.deployment.DeploymentConfigurationParser;
import gold.debug.windowstolinux.app.ui.deployment.DeploymentRuntimeParser;

import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

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
    final JTextField jvmArguments = new JTextField(20);
    final JTextField applicationArguments = new JTextField(20);
    final JComboBox<DeploymentRuntimeSpecification.ContainerEngineType> containerEngine =
            new JComboBox<>(DeploymentRuntimeSpecification.ContainerEngineType.values());
    final JTextField containerPorts = new JTextField(20);
    final JTextField containerVolumes = new JTextField(20);
    final JTextField configurationEntries = new JTextField(30);
    final JTextField secretReferences = new JTextField(20);
    final JCheckBox rootBuild;
    final JCheckBox experimentalAdapterRisk;
    private final PageMessagePresenter messages;

    DeploymentForm(PageMessagePresenter messages, Runnable reviewInvalidation) {
        this.messages = messages;
        rootBuild = new JCheckBox(messages.text("rootBuild"));
        experimentalAdapterRisk = new JCheckBox(messages.text("experimentalAdapterRisk"));
        messages.localize(projectType, "project.type.");
        messages.localize(healthMode, "health.mode.");
        messages.localize(containerEngine, "container.engine.");
        projectType.addActionListener(event -> {
            resetRuntimeInputs();
            reviewInvalidation.run();
        });
        containerEngine.setSelectedItem(null);
    }

    DeploymentPageState capture(String output, ReviewedSourcePreparation preparation) {
        return new DeploymentPageState(projectType().name(),
                ((HealthMode) healthMode.getSelectedItem()).name(), healthEndpoint.getText(), expectedStatus.getText(),
                timeout.getText(), stability.getText(), accessUrl.getText(), runtimePrimary.getText(), runtimeSecondary.getText(),
                runtimeVersion.getText(), jvmArguments.getText(), applicationArguments.getText(),
                containerEngine.getSelectedItem() == null ? ""
                        : ((DeploymentRuntimeSpecification.ContainerEngineType) containerEngine.getSelectedItem()).name(),
                containerPorts.getText(), containerVolumes.getText(), configurationEntries.getText(),
                secretReferences.getText(), rootBuild.isSelected(), experimentalAdapterRisk.isSelected(),
                output, preparation);
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
        jvmArguments.setText(state.jvmArguments());
        applicationArguments.setText(state.applicationArguments());
        containerEngine.setSelectedItem(state.containerEngine().isBlank() ? null
                : DeploymentRuntimeSpecification.ContainerEngineType.valueOf(state.containerEngine()));
        containerPorts.setText(state.containerPorts());
        containerVolumes.setText(state.containerVolumes());
        configurationEntries.setText(state.configurationEntries());
        secretReferences.setText(state.secretReferences());
        rootBuild.setSelected(state.rootBuild());
        experimentalAdapterRisk.setSelected(state.experimentalAdapterRisk());
    }

    DeploymentProjectType projectType() {
        return (DeploymentProjectType) projectType.getSelectedItem();
    }

    ConfigurationSnapshot configurationSnapshot(String applicationId) {
        List<ConfigurationEntry> entries;
        try {
            entries = DeploymentConfigurationParser.parse(configurationEntries.getText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(messages.text("validation.configurationEntry"), exception);
        }
        return ConfigurationSnapshot.create(applicationId, Instant.now().toEpochMilli(), "runtime-v1", Instant.now(), entries);
    }

    List<SecretReference> secretReferences() {
        try {
            return DeploymentRuntimeParser.secrets(secretReferences.getText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(messages.text("validation.secretReference"), exception);
        }
    }

    HealthCheck healthCheck() {
        int seconds = Integer.parseInt(timeout.getText().trim());
        return switch ((HealthMode) healthMode.getSelectedItem()) {
            case HTTP -> new HealthCheck.Http(URI.create(healthEndpoint.getText().trim()),
                    Integer.parseInt(expectedStatus.getText().trim()), seconds);
            case TCP -> new HealthCheck.Tcp(Integer.parseInt(healthEndpoint.getText().trim()), seconds,
                    Integer.parseInt(stability.getText().trim()));
        };
    }

    Optional<UserAccessUrl> userAccessUrl(HealthCheck health) {
        String value = accessUrl.getText().trim();
        if (health instanceof HealthCheck.Http) {
            if (value.isBlank()) throw new IllegalArgumentException(messages.text("validation.httpAccessRequired"));
            return Optional.of(new UserAccessUrl(URI.create(value)));
        }
        if (!value.isBlank()) throw new IllegalArgumentException(messages.text("validation.tcpAccessForbidden"));
        return Optional.empty();
    }

    DeploymentRuntimeSpecification runtimeSpecification(HealthCheck health) {
        return switch (projectType()) {
            case SPRING_BOOT -> new DeploymentRuntimeSpecification.SpringBoot(health);
            case JAVA_JAR -> new DeploymentRuntimeSpecification.JavaJar(runtimePrimary.getText(), runtimeSecondary.getText(),
                    runtimeVersion.getText(), DeploymentRuntimeParser.arguments(jvmArguments.getText()),
                    DeploymentRuntimeParser.arguments(applicationArguments.getText()), health);
            case NODE_SERVICE -> new DeploymentRuntimeSpecification.NodeService(
                    Integer.parseInt(runtimeVersion.getText().trim()), health);
            case PYTHON_SERVICE -> new DeploymentRuntimeSpecification.PythonService(
                    runtimePrimary.getText(), runtimeSecondary.getText(), health);
            case STATIC_SITE -> new DeploymentRuntimeSpecification.StaticSite(runtimePrimary.getText(),
                    runtimeVersion.getText().isBlank() ? OptionalInt.empty()
                            : OptionalInt.of(Integer.parseInt(runtimeVersion.getText().trim())), requireHttp(health));
            case DOCKERFILE_CONTAINER -> new DeploymentRuntimeSpecification.Container(
                    (DeploymentRuntimeSpecification.ContainerEngineType) containerEngine.getSelectedItem(),
                    ports(), volumes(), health);
            case GO_SERVICE, RUST_SERVICE, DOTNET_SERVICE, KOTLIN_SERVICE, PHP_SERVICE, RUBY_SERVICE ->
                    DeploymentRuntimeParser.service(projectType(), runtimeVersion.getText(), runtimePrimary.getText(),
                            runtimeSecondary.getText(), health);
            case RECOGNITION_PREVIEW -> throw new IllegalArgumentException(messages.text("analysis.preview.noDeployment"));
        };
    }

    void applyRuntimeSuggestions(ReviewedSourcePreparation preparation) {
        DeploymentRuntimeAssessment suggestion = preparation.assessment().runtimeSuggestion().orElse(null);
        if (suggestion == null) return;
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_JAR_PATH).ifPresent(runtimePrimary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_MAIN_CLASS).ifPresent(runtimeSecondary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_VERSION).ifPresent(runtimeVersion::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.NODE_MAJOR_VERSION).ifPresent(runtimeVersion::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.PYTHON_VERSION).ifPresent(runtimePrimary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.PYTHON_ENTRYPOINT).ifPresent(runtimeSecondary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.STATIC_OUTPUT_DIRECTORY).ifPresent(runtimePrimary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.SERVICE_ARTIFACT).ifPresent(runtimePrimary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.SERVICE_ENTRYPOINT).ifPresent(runtimeSecondary::setText);
        suggestion.value(DeploymentRuntimeAssessment.RuntimeInputType.SERVICE_VERSION).ifPresent(runtimeVersion::setText);
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

    private java.util.Map<Integer, Integer> ports() {
        try {
            return DeploymentRuntimeParser.ports(containerPorts.getText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(messages.text("validation.containerPort"), exception);
        }
    }

    private List<DeploymentRuntimeSpecification.ManagedVolume> volumes() {
        try {
            return DeploymentRuntimeParser.volumes(containerVolumes.getText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(messages.text("validation.containerVolume"), exception);
        }
    }

    private void resetRuntimeInputs() {
        runtimePrimary.setText("");
        runtimeSecondary.setText("");
        runtimeVersion.setText("");
        jvmArguments.setText("");
        applicationArguments.setText("");
        containerPorts.setText("");
        containerVolumes.setText("");
    }

    private HealthCheck.Http requireHttp(HealthCheck health) {
        if (health instanceof HealthCheck.Http http) return http;
        throw new IllegalArgumentException(messages.text("validation.staticHttpRequired"));
    }

    private enum HealthMode {
        /** HTTP health check. / HTTP 健康检查。 */ HTTP,
        /** TCP health check. / TCP 健康检查。 */ TCP
    }
}
