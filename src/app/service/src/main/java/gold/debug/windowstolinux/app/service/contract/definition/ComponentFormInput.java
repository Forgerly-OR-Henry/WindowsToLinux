package gold.debug.windowstolinux.app.service.contract.definition;

import gold.debug.windowstolinux.shared.model.deployment.DatabaseReviewMode;


import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.util.Objects;

/**
 * Secret-value-free typed form state for one explicitly reviewed component.
 *
 * <p>一个显式审阅组件不含秘密值的类型化表单状态。
 */
public record ComponentFormInput(
        String componentId,
        String relativeSourceRoot,
        DeploymentProjectType projectType,
        String runtimePrimary,
        String runtimeSecondary,
        String runtimeVersion,
        String runtimeArguments,
        String runtimeAdditional,
        ComponentHealthMode healthMode,
        String healthEndpoint,
        String expectedStatus,
        String timeoutSeconds,
        String stabilitySeconds,
        String userAccessUrl,
        String artifactPaths,
        String declaredPorts,
        String dependencies,
        String configurationEntries,
        DatabaseReviewMode databaseMode,
        String databaseDetails,
        String secretReferences,
        boolean required,
        boolean rootBuild,
        String kotlinJvmTarget,
        String applicationDeclaration
) {
    public ComponentFormInput(
        String componentId,
        String relativeSourceRoot,
        DeploymentProjectType projectType,
        String runtimePrimary,
        String runtimeSecondary,
        String runtimeVersion,
        String runtimeArguments,
        String runtimeAdditional,
        ComponentHealthMode healthMode,
        String healthEndpoint,
        String expectedStatus,
        String timeoutSeconds,
        String stabilitySeconds,
        String userAccessUrl,
        String artifactPaths,
        String declaredPorts,
        String dependencies,
        String configurationEntries,
        DatabaseReviewMode databaseMode,
        String databaseDetails,
        String secretReferences,
        boolean required,
        boolean rootBuild,
        String kotlinJvmTarget) {
        this(componentId, relativeSourceRoot, projectType, runtimePrimary, runtimeSecondary, runtimeVersion, runtimeArguments, runtimeAdditional, healthMode, healthEndpoint, expectedStatus, timeoutSeconds, stabilitySeconds, userAccessUrl, artifactPaths, declaredPorts, dependencies, configurationEntries, databaseMode, databaseDetails, secretReferences, required, rootBuild, kotlinJvmTarget, "");
    }

    public ComponentFormInput(String componentId,
        String relativeSourceRoot,
        DeploymentProjectType projectType,
        String runtimePrimary,
        String runtimeSecondary,
        String runtimeVersion,
        String runtimeArguments,
        String runtimeAdditional,
        ComponentHealthMode healthMode,
        String healthEndpoint,
        String expectedStatus,
        String timeoutSeconds,
        String stabilitySeconds,
        String userAccessUrl,
        String artifactPaths,
        String declaredPorts,
        String dependencies,
        String configurationEntries,
        DatabaseReviewMode databaseMode,
        String databaseDetails,
        String secretReferences,
        boolean required,
        boolean rootBuild) {
        this(componentId, relativeSourceRoot, projectType, runtimePrimary, runtimeSecondary, runtimeVersion, runtimeArguments, runtimeAdditional, healthMode, healthEndpoint, expectedStatus, timeoutSeconds, stabilitySeconds, userAccessUrl, artifactPaths, declaredPorts, dependencies, configurationEntries, databaseMode, databaseDetails, secretReferences, required, rootBuild, "");
    }

    /** Preserves only bounded text and typed selections. / 仅保留有界文本与类型化选择。 */
    public ComponentFormInput {
        componentId = text(componentId);
        relativeSourceRoot = text(relativeSourceRoot);
        projectType = Objects.requireNonNull(projectType, "projectType");
        runtimePrimary = text(runtimePrimary);
        runtimeSecondary = text(runtimeSecondary);
        runtimeVersion = text(runtimeVersion);
        runtimeArguments = text(runtimeArguments);
        runtimeAdditional = text(runtimeAdditional);
        healthMode = Objects.requireNonNull(healthMode, "healthMode");
        healthEndpoint = text(healthEndpoint);
        expectedStatus = text(expectedStatus);
        timeoutSeconds = text(timeoutSeconds);
        stabilitySeconds = text(stabilitySeconds);
        userAccessUrl = text(userAccessUrl);
        artifactPaths = text(artifactPaths);
        declaredPorts = text(declaredPorts);
        dependencies = text(dependencies);
        configurationEntries = text(configurationEntries);
        databaseMode = Objects.requireNonNull(databaseMode, "databaseMode");
        databaseDetails = text(databaseDetails);
        secretReferences = text(secretReferences);
        kotlinJvmTarget = text(kotlinJvmTarget);
        applicationDeclaration = Objects.requireNonNull(applicationDeclaration).trim();
        if (applicationDeclaration.length()>65536) throw new IllegalArgumentException("application declaration too long");
    }

    private static String text(String value) {
        value = Objects.requireNonNull(value, "form value").trim();
        if (value.length() > 4096) throw new IllegalArgumentException("component form value is too long");
        return value;
    }
}
