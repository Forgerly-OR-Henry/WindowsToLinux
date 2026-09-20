package gold.debug.windowstolinux.app.ui.deployment.single;

import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;

import java.util.Objects;

/**
 * Represents an immutable {@code DeploymentPageState} value.
 *
 * <p>表示不可变的 {@code DeploymentPageState} 值。
 *
 * @param projectType the {@code projectType} value / {@code projectType} 值
 * @param healthMode the {@code healthMode} value / {@code healthMode} 值
 * @param healthEndpoint the {@code healthEndpoint} value / {@code healthEndpoint} 值
 * @param expectedHttpStatus the {@code expectedHttpStatus} value / {@code expectedHttpStatus} 值
 * @param healthTimeoutSeconds the {@code healthTimeoutSeconds} value / {@code healthTimeoutSeconds} 值
 * @param tcpStabilitySeconds the {@code tcpStabilitySeconds} value / {@code tcpStabilitySeconds} 值
 * @param userAccessUrl the {@code userAccessUrl} value / {@code userAccessUrl} 值
 * @param runtimePrimary the {@code runtimePrimary} value / {@code runtimePrimary} 值
 * @param runtimeSecondary the {@code runtimeSecondary} value / {@code runtimeSecondary} 值
 * @param jvmArguments the {@code jvmArguments} value / {@code jvmArguments} 值
 * @param applicationArguments the {@code applicationArguments} value / {@code applicationArguments} 值
 * @param containerEngine the {@code containerEngine} value / {@code containerEngine} 值
 * @param containerPorts the {@code containerPorts} value / {@code containerPorts} 值
 * @param containerVolumes the {@code containerVolumes} value / {@code containerVolumes} 值
 * @param configurationEntries the {@code configurationEntries} value / {@code configurationEntries} 值
 * @param databaseMode the explicit database review mode / 显式数据库审阅模式
 * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
 * @param secretReferences the exact reviewed secret references / 精确的已审阅秘密引用
 * @param rootBuild the {@code rootBuild} value / {@code rootBuild} 值
 * @param experimentalAdapterRisk the {@code experimentalAdapterRisk} value / {@code experimentalAdapterRisk} 值
 * @param output the {@code output} value / {@code output} 值
 * @param preparation the {@code preparation} value / {@code preparation} 值
 */
public record DeploymentPageState(
        String projectType,
        String healthMode,
        String healthEndpoint,
        String expectedHttpStatus,
        String healthTimeoutSeconds,
        String tcpStabilitySeconds,
        String userAccessUrl,
        String runtimePrimary,
        String runtimeSecondary,
        String javaVersion,
        String jvmArguments,
        String applicationArguments,
        String containerEngine,
        String containerPorts,
        String containerVolumes,
        String configurationEntries,
        String databaseMode,
        String databaseDetails,
        String secretReferences,
        boolean rootBuild,
        boolean experimentalAdapterRisk,
        String output,
        ReviewedSourcePreparation preparation,
        String kotlinJvmTarget
, String applicationDeclaration) {
    public DeploymentPageState(
        String projectType,
        String healthMode,
        String healthEndpoint,
        String expectedHttpStatus,
        String healthTimeoutSeconds,
        String tcpStabilitySeconds,
        String userAccessUrl,
        String runtimePrimary,
        String runtimeSecondary,
        String javaVersion,
        String jvmArguments,
        String applicationArguments,
        String containerEngine,
        String containerPorts,
        String containerVolumes,
        String configurationEntries,
        String databaseMode,
        String databaseDetails,
        String secretReferences,
        boolean rootBuild,
        boolean experimentalAdapterRisk,
        String output,
        ReviewedSourcePreparation preparation,
        String kotlinJvmTarget
) {
        this(projectType, healthMode, healthEndpoint, expectedHttpStatus, healthTimeoutSeconds, tcpStabilitySeconds, userAccessUrl, runtimePrimary, runtimeSecondary, javaVersion, jvmArguments, applicationArguments, containerEngine, containerPorts, containerVolumes, configurationEntries, databaseMode, databaseDetails, secretReferences, rootBuild, experimentalAdapterRisk, output, preparation, kotlinJvmTarget, "");
    }

    public DeploymentPageState(String projectType,
        String healthMode,
        String healthEndpoint,
        String expectedHttpStatus,
        String healthTimeoutSeconds,
        String tcpStabilitySeconds,
        String userAccessUrl,
        String runtimePrimary,
        String runtimeSecondary,
        String javaVersion,
        String jvmArguments,
        String applicationArguments,
        String containerEngine,
        String containerPorts,
        String containerVolumes,
        String configurationEntries,
        String databaseMode,
        String databaseDetails,
        String secretReferences,
        boolean rootBuild,
        boolean experimentalAdapterRisk,
        String output,
        ReviewedSourcePreparation preparation) {
        this(projectType, healthMode, healthEndpoint, expectedHttpStatus, healthTimeoutSeconds, tcpStabilitySeconds, userAccessUrl, runtimePrimary, runtimeSecondary, javaVersion, jvmArguments, applicationArguments, containerEngine, containerPorts, containerVolumes, configurationEntries, databaseMode, databaseDetails, secretReferences, rootBuild, experimentalAdapterRisk, output, preparation, "");
    }

    /**
     * Creates a {@code DeploymentPageState} instance.
     *
     * <p>创建 {@code DeploymentPageState} 实例。
     *
     * @param projectType the {@code projectType} value / {@code projectType} 值
     * @param healthMode the {@code healthMode} value / {@code healthMode} 值
     * @param healthEndpoint the {@code healthEndpoint} value / {@code healthEndpoint} 值
     * @param expectedHttpStatus the {@code expectedHttpStatus} value / {@code expectedHttpStatus} 值
     * @param healthTimeoutSeconds the {@code healthTimeoutSeconds} value / {@code healthTimeoutSeconds} 值
     * @param tcpStabilitySeconds the {@code tcpStabilitySeconds} value / {@code tcpStabilitySeconds} 值
     * @param userAccessUrl the {@code userAccessUrl} value / {@code userAccessUrl} 值
     * @param runtimePrimary the {@code runtimePrimary} value / {@code runtimePrimary} 值
     * @param runtimeSecondary the {@code runtimeSecondary} value / {@code runtimeSecondary} 值
     * @param jvmArguments the {@code jvmArguments} value / {@code jvmArguments} 值
     * @param applicationArguments the {@code applicationArguments} value / {@code applicationArguments} 值
     * @param containerEngine the {@code containerEngine} value / {@code containerEngine} 值
     * @param containerPorts the {@code containerPorts} value / {@code containerPorts} 值
     * @param containerVolumes the {@code containerVolumes} value / {@code containerVolumes} 值
     * @param configurationEntries the {@code configurationEntries} value / {@code configurationEntries} 值
     * @param databaseMode the explicit database review mode / 显式数据库审阅模式
     * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
     * @param secretReferences the exact reviewed secret references / 精确的已审阅秘密引用
     * @param rootBuild the {@code rootBuild} value / {@code rootBuild} 值
     * @param experimentalAdapterRisk the {@code experimentalAdapterRisk} value / {@code experimentalAdapterRisk} 值
     * @param output the {@code output} value / {@code output} 值
     * @param preparation the {@code preparation} value / {@code preparation} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DeploymentPageState {
        Objects.requireNonNull(projectType, "projectType");
        Objects.requireNonNull(healthMode, "healthMode");
        Objects.requireNonNull(healthEndpoint, "healthEndpoint");
        Objects.requireNonNull(expectedHttpStatus, "expectedHttpStatus");
        Objects.requireNonNull(healthTimeoutSeconds, "healthTimeoutSeconds");
        Objects.requireNonNull(tcpStabilitySeconds, "tcpStabilitySeconds");
        Objects.requireNonNull(userAccessUrl, "userAccessUrl");
        Objects.requireNonNull(runtimePrimary, "runtimePrimary");
        Objects.requireNonNull(runtimeSecondary, "runtimeSecondary");
        Objects.requireNonNull(javaVersion, "javaVersion");
        Objects.requireNonNull(jvmArguments, "jvmArguments");
        Objects.requireNonNull(applicationArguments, "applicationArguments");
        Objects.requireNonNull(containerEngine, "containerEngine");
        Objects.requireNonNull(containerPorts, "containerPorts");
        Objects.requireNonNull(containerVolumes, "containerVolumes");
        Objects.requireNonNull(configurationEntries, "configurationEntries");
        Objects.requireNonNull(databaseMode, "databaseMode");
        Objects.requireNonNull(databaseDetails, "databaseDetails");
        Objects.requireNonNull(secretReferences, "secretReferences");
        Objects.requireNonNull(output, "output");
    }
}
