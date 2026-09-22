package gold.debug.windowstolinux.app.service.contract.definition;

import java.util.Objects;

import gold.debug.windowstolinux.shared.model.deployment.DatabaseReviewMode;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification.ContainerEngineType;

/**
 * Typed non-secret controls submitted by the single-component form. / 单组件表单提交的类型化非秘密控件值。
 *
 * @param detectType detect type / 识别类型
 * @param projectType supported project deployment category / 受支持的项目部署类别
 * @param primary primary / 主
 * @param secondary secondary / 次要
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 * @param jvmTarget jvm target / jvm目标
 * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
 * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
 * @param databaseMode the explicit database review mode / 显式数据库审阅模式
 * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
 * @param healthMode health mode / 健康模式
 * @param healthEndpoint health endpoint / 健康端点
 * @param expectedStatus expected status / 预期状态
 * @param timeout timeout / 超时
 * @param stability stability / 稳定性
 * @param accessUrl access url / 访问URL
 * @param jvmArguments jvm arguments / jvm参数
 * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
 * @param ports bound host ports / 绑定的宿主机端口
 * @param volumes volumes / 卷集合
 * @param containerEngine container engine / 容器引擎
 * @param experimentalAdapterRisk experimental adapter risk / 实验性适配器风险
 * @param applicationDeclaration application declaration / 应用声明
 */
public record DeploymentFormInput(boolean detectType, DeploymentProjectType projectType, String primary,
        String secondary, String version, String jvmTarget, String configuration, String secrets,
        DatabaseReviewMode databaseMode, String databaseDetails, String healthMode, String healthEndpoint,
        String expectedStatus, String timeout, String stability, String accessUrl, String jvmArguments,
        String arguments, String ports, String volumes, ContainerEngineType containerEngine,
        boolean experimentalAdapterRisk, String applicationDeclaration) {
    /**
     * Initializes deployment form input through its shared constructor contract.
     * <p>通过共享构造契约初始化部署表单输入。
     *
     * @param detectType detect type / 识别类型
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param primary primary / 主
     * @param secondary secondary / 次要
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param jvmTarget jvm target / jvm目标
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param databaseMode the explicit database review mode / 显式数据库审阅模式
     * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
     * @param healthMode health mode / 健康模式
     * @param healthEndpoint health endpoint / 健康端点
     * @param expectedStatus expected status / 预期状态
     * @param timeout timeout / 超时
     * @param stability stability / 稳定性
     * @param accessUrl access url / 访问URL
     * @param jvmArguments jvm arguments / jvm参数
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @param ports bound host ports / 绑定的宿主机端口
     * @param volumes volumes / 卷集合
     * @param containerEngine container engine / 容器引擎
     * @param experimentalAdapterRisk experimental adapter risk / 实验性适配器风险
     */
    public DeploymentFormInput(boolean detectType, DeploymentProjectType projectType, String primary, String secondary,
            String version, String jvmTarget, String configuration, String secrets, DatabaseReviewMode databaseMode,
            String databaseDetails, String healthMode, String healthEndpoint, String expectedStatus, String timeout,
            String stability, String accessUrl, String jvmArguments, String arguments, String ports, String volumes,
            ContainerEngineType containerEngine, boolean experimentalAdapterRisk) {
        this(detectType, projectType, primary, secondary, version, jvmTarget, configuration, secrets, databaseMode,
                databaseDetails, healthMode, healthEndpoint, expectedStatus, timeout, stability, accessUrl,
                jvmArguments, arguments, ports, volumes, containerEngine, experimentalAdapterRisk, "");
    }

    /**
     * Keeps incomplete controls as data until service-side parsing. / 将未完成控件保留为数据，交由服务解析。
     *
     * @param detectType detect type / 识别类型
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param primary primary / 主
     * @param secondary secondary / 次要
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param jvmTarget jvm target / jvm目标
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param databaseMode the explicit database review mode / 显式数据库审阅模式
     * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
     * @param healthMode health mode / 健康模式
     * @param healthEndpoint health endpoint / 健康端点
     * @param expectedStatus expected status / 预期状态
     * @param timeout timeout / 超时
     * @param stability stability / 稳定性
     * @param accessUrl access url / 访问URL
     * @param jvmArguments jvm arguments / jvm参数
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @param ports bound host ports / 绑定的宿主机端口
     * @param volumes volumes / 卷集合
     * @param containerEngine container engine / 容器引擎
     * @param experimentalAdapterRisk experimental adapter risk / 实验性适配器风险
     * @param applicationDeclaration application declaration / 应用声明
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentFormInput {
        Objects.requireNonNull(projectType, "projectType");
        Objects.requireNonNull(databaseMode, "databaseMode");
        for (String value : new String[]{primary, secondary, version, jvmTarget, configuration, secrets,
                databaseDetails, healthMode, healthEndpoint, expectedStatus, timeout, stability, accessUrl,
                jvmArguments, arguments, ports, volumes}) {
            if (Objects.requireNonNull(value, "form value").length() > 4096)
                throw new IllegalArgumentException("deployment form value exceeds bound");
        }
    }
}
