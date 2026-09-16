package gold.debug.windowstolinux.app.service.contract.definition;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification.ContainerEngineType;
import java.util.Objects;

/** Typed non-secret controls submitted by the single-component form. / 单组件表单提交的类型化非秘密控件值。 */
public record DeploymentFormInput(boolean detectType, DeploymentProjectType projectType,
        String primary, String secondary, String version, String jvmTarget,
        String configuration, String secrets, DatabaseReviewMode databaseMode, String databaseDetails,
        String healthMode, String healthEndpoint, String expectedStatus, String timeout, String stability,
        String accessUrl, String jvmArguments, String arguments, String ports, String volumes,
        ContainerEngineType containerEngine, boolean experimentalAdapterRisk) {
    /** Keeps incomplete controls as data until service-side parsing. / 将未完成控件保留为数据，交由服务解析。 */
    public DeploymentFormInput {
        Objects.requireNonNull(projectType, "projectType");
        Objects.requireNonNull(databaseMode, "databaseMode");
        for (String value : new String[] { primary, secondary, version, jvmTarget, configuration, secrets,
                databaseDetails, healthMode, healthEndpoint, expectedStatus, timeout, stability, accessUrl,
                jvmArguments, arguments, ports, volumes }) {
            if (Objects.requireNonNull(value, "form value").length() > 4096)
                throw new IllegalArgumentException("deployment form value exceeds bound");
        }
    }
}
