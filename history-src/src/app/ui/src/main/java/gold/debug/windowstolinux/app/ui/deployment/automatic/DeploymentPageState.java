package gold.debug.windowstolinux.app.ui.deployment.automatic;

import java.util.Objects;

import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;

/**
 * Represents an immutable {@code DeploymentPageState} value.
 *
 *  <p>表示不可变的 {@code DeploymentPageState} 值。
 *
 * @param projectType supported project deployment category / 受支持的项目部署类别
 * @param healthMode health mode / 健康模式
 * @param healthEndpoint health endpoint / 健康端点
 * @param expectedHttpStatus expected http status / 预期HTTP状态
 * @param healthTimeoutSeconds health timeout seconds / 健康超时秒
 * @param tcpStabilitySeconds tcp stability seconds / tcp稳定性秒
 * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
 * @param runtimePrimary runtime primary / 运行时主
 * @param runtimeSecondary runtime secondary / 运行时次要
 * @param javaVersion java version / Java版本
 * @param jvmArguments jvm arguments / jvm参数
 * @param applicationArguments application arguments / 应用参数
 * @param containerEngine container engine / 容器引擎
 * @param containerPorts container ports / 容器端口集合
 * @param containerVolumes container volumes / 容器卷集合
 * @param configurationEntries configuration entries / 配置条目
 * @param databaseMode the explicit database review mode / 显式数据库审阅模式
 * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
 * @param secretReferences the exact reviewed secret references / 精确的已审阅秘密引用
 * @param rootBuild root build / 根目录构建
 * @param experimentalAdapterRisk experimental adapter risk / 实验性适配器风险
 * @param output destination receiving the produced content / 接收所生成内容的目标
 * @param preparation preparation / 准备
 * @param kotlinJvmTarget kotlin jvm target / kotlinJvm目标
 * @param applicationDeclaration application declaration / 应用声明
 */
public record DeploymentPageState(String projectType, String healthMode, String healthEndpoint,
        String expectedHttpStatus, String healthTimeoutSeconds, String tcpStabilitySeconds, String userAccessUrl,
        String runtimePrimary, String runtimeSecondary, String javaVersion, String jvmArguments,
        String applicationArguments, String containerEngine, String containerPorts, String containerVolumes,
        String configurationEntries, String databaseMode, String databaseDetails, String secretReferences,
        boolean rootBuild, boolean experimentalAdapterRisk, String output, ReviewedSourcePreparation preparation,
        String kotlinJvmTarget, String applicationDeclaration) {
    /**
     * Initializes deployment page state through its shared constructor contract.
     * <p>通过共享构造契约初始化部署页面状态。
     *
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param healthMode health mode / 健康模式
     * @param healthEndpoint health endpoint / 健康端点
     * @param expectedHttpStatus expected http status / 预期HTTP状态
     * @param healthTimeoutSeconds health timeout seconds / 健康超时秒
     * @param tcpStabilitySeconds tcp stability seconds / tcp稳定性秒
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param runtimePrimary runtime primary / 运行时主
     * @param runtimeSecondary runtime secondary / 运行时次要
     * @param javaVersion java version / Java版本
     * @param jvmArguments jvm arguments / jvm参数
     * @param applicationArguments application arguments / 应用参数
     * @param containerEngine container engine / 容器引擎
     * @param containerPorts container ports / 容器端口集合
     * @param containerVolumes container volumes / 容器卷集合
     * @param configurationEntries configuration entries / 配置条目
     * @param databaseMode the explicit database review mode / 显式数据库审阅模式
     * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param rootBuild root build / 根目录构建
     * @param experimentalAdapterRisk experimental adapter risk / 实验性适配器风险
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param preparation preparation / 准备
     * @param kotlinJvmTarget kotlin jvm target / kotlinJvm目标
     */
    public DeploymentPageState(String projectType, String healthMode, String healthEndpoint, String expectedHttpStatus,
            String healthTimeoutSeconds, String tcpStabilitySeconds, String userAccessUrl, String runtimePrimary,
            String runtimeSecondary, String javaVersion, String jvmArguments, String applicationArguments,
            String containerEngine, String containerPorts, String containerVolumes, String configurationEntries,
            String databaseMode, String databaseDetails, String secretReferences, boolean rootBuild,
            boolean experimentalAdapterRisk, String output, ReviewedSourcePreparation preparation,
            String kotlinJvmTarget) {
        this(projectType, healthMode, healthEndpoint, expectedHttpStatus, healthTimeoutSeconds, tcpStabilitySeconds,
                userAccessUrl, runtimePrimary, runtimeSecondary, javaVersion, jvmArguments, applicationArguments,
                containerEngine, containerPorts, containerVolumes, configurationEntries, databaseMode, databaseDetails,
                secretReferences, rootBuild, experimentalAdapterRisk, output, preparation, kotlinJvmTarget, "");
    }

    /**
     * Initializes deployment page state through its shared constructor contract.
     * <p>通过共享构造契约初始化部署页面状态。
     *
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param healthMode health mode / 健康模式
     * @param healthEndpoint health endpoint / 健康端点
     * @param expectedHttpStatus expected http status / 预期HTTP状态
     * @param healthTimeoutSeconds health timeout seconds / 健康超时秒
     * @param tcpStabilitySeconds tcp stability seconds / tcp稳定性秒
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param runtimePrimary runtime primary / 运行时主
     * @param runtimeSecondary runtime secondary / 运行时次要
     * @param javaVersion java version / Java版本
     * @param jvmArguments jvm arguments / jvm参数
     * @param applicationArguments application arguments / 应用参数
     * @param containerEngine container engine / 容器引擎
     * @param containerPorts container ports / 容器端口集合
     * @param containerVolumes container volumes / 容器卷集合
     * @param configurationEntries configuration entries / 配置条目
     * @param databaseMode the explicit database review mode / 显式数据库审阅模式
     * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param rootBuild root build / 根目录构建
     * @param experimentalAdapterRisk experimental adapter risk / 实验性适配器风险
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param preparation preparation / 准备
     */
    public DeploymentPageState(String projectType, String healthMode, String healthEndpoint, String expectedHttpStatus,
            String healthTimeoutSeconds, String tcpStabilitySeconds, String userAccessUrl, String runtimePrimary,
            String runtimeSecondary, String javaVersion, String jvmArguments, String applicationArguments,
            String containerEngine, String containerPorts, String containerVolumes, String configurationEntries,
            String databaseMode, String databaseDetails, String secretReferences, boolean rootBuild,
            boolean experimentalAdapterRisk, String output, ReviewedSourcePreparation preparation) {
        this(projectType, healthMode, healthEndpoint, expectedHttpStatus, healthTimeoutSeconds, tcpStabilitySeconds,
                userAccessUrl, runtimePrimary, runtimeSecondary, javaVersion, jvmArguments, applicationArguments,
                containerEngine, containerPorts, containerVolumes, configurationEntries, databaseMode, databaseDetails,
                secretReferences, rootBuild, experimentalAdapterRisk, output, preparation, "");
    }

    /**
     * Validates and binds the inputs required by deployment page state.
     * <p>校验并绑定部署页面状态所需输入。
     *
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param healthMode health mode / 健康模式
     * @param healthEndpoint health endpoint / 健康端点
     * @param expectedHttpStatus expected http status / 预期HTTP状态
     * @param healthTimeoutSeconds health timeout seconds / 健康超时秒
     * @param tcpStabilitySeconds tcp stability seconds / tcp稳定性秒
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param runtimePrimary runtime primary / 运行时主
     * @param runtimeSecondary runtime secondary / 运行时次要
     * @param javaVersion java version / Java版本
     * @param jvmArguments jvm arguments / jvm参数
     * @param applicationArguments application arguments / 应用参数
     * @param containerEngine container engine / 容器引擎
     * @param containerPorts container ports / 容器端口集合
     * @param containerVolumes container volumes / 容器卷集合
     * @param configurationEntries configuration entries / 配置条目
     * @param databaseMode the explicit database review mode / 显式数据库审阅模式
     * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
     * @param secretReferences the exact reviewed secret references / 精确的已审阅秘密引用
     * @param rootBuild root build / 根目录构建
     * @param experimentalAdapterRisk experimental adapter risk / 实验性适配器风险
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param preparation preparation / 准备
     * @param kotlinJvmTarget kotlin jvm target / kotlinJvm目标
     * @param applicationDeclaration application declaration / 应用声明
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
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
