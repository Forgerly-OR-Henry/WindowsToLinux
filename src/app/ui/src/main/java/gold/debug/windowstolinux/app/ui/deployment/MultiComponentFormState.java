package gold.debug.windowstolinux.app.ui.deployment;

import java.util.Objects;

/** Unsaved editor values preserved across a desktop appearance rebuild. / 桌面外观重建期间保留的未保存编辑值。 */
public record MultiComponentFormState(
        String componentId,
        String relativeRoot,
        String projectType,
        String runtimePrimary,
        String runtimeSecondary,
        String runtimeVersion,
        String runtimeArguments,
        String runtimeAdditional,
        String healthMode,
        String healthEndpoint,
        String expectedStatus,
        String timeoutSeconds,
        String stabilitySeconds,
        String accessUrl,
        String artifacts,
        String ports,
        String dependencies,
        String configuration,
        String secrets,
        boolean required,
        boolean rootBuild
) {
    /** Rejects missing state values. / 拒绝缺失状态值。 */
    public MultiComponentFormState {
        Objects.requireNonNull(componentId, "componentId");
        Objects.requireNonNull(relativeRoot, "relativeRoot");
        Objects.requireNonNull(projectType, "projectType");
        Objects.requireNonNull(runtimePrimary, "runtimePrimary");
        Objects.requireNonNull(runtimeSecondary, "runtimeSecondary");
        Objects.requireNonNull(runtimeVersion, "runtimeVersion");
        Objects.requireNonNull(runtimeArguments, "runtimeArguments");
        Objects.requireNonNull(runtimeAdditional, "runtimeAdditional");
        Objects.requireNonNull(healthMode, "healthMode");
        Objects.requireNonNull(healthEndpoint, "healthEndpoint");
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        Objects.requireNonNull(timeoutSeconds, "timeoutSeconds");
        Objects.requireNonNull(stabilitySeconds, "stabilitySeconds");
        Objects.requireNonNull(accessUrl, "accessUrl");
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(ports, "ports");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(secrets, "secrets");
    }
}
