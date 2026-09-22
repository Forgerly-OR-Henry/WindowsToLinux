package gold.debug.windowstolinux.app.ui.deployment.multi;

import java.util.Objects;

/**
 * Unsaved editor values preserved across a desktop appearance rebuild. / 桌面外观重建期间保留的未保存编辑值。
 *
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param relativeRoot relative root / 相对根目录
 * @param projectType supported project deployment category / 受支持的项目部署类别
 * @param runtimePrimary runtime primary / 运行时主
 * @param runtimeSecondary runtime secondary / 运行时次要
 * @param runtimeVersion runtime version / 运行时版本
 * @param runtimeArguments runtime arguments / 运行时参数
 * @param runtimeAdditional runtime additional / 运行时额外
 * @param healthMode health mode / 健康模式
 * @param healthEndpoint health endpoint / 健康端点
 * @param expectedStatus expected status / 预期状态
 * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
 * @param stabilitySeconds required continuous healthy interval in seconds / 要求连续健康的时间间隔，单位为秒
 * @param accessUrl access url / 访问URL
 * @param artifacts artifacts / 制品集合
 * @param ports bound host ports / 绑定的宿主机端口
 * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
 * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
 * @param databaseMode the explicit database review mode / 显式数据库审阅模式
 * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
 * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
 * @param required whether the whole application requires this component / 整体应用是否需要此组件
 * @param rootBuild root build / 根目录构建
 * @param kotlinJvmTarget kotlin jvm target / kotlinJvm目标
 * @param applicationDeclaration application declaration / 应用声明
 */
public record MultiComponentFormState(String componentId, String relativeRoot, String projectType,
        String runtimePrimary, String runtimeSecondary, String runtimeVersion, String runtimeArguments,
        String runtimeAdditional, String healthMode, String healthEndpoint, String expectedStatus,
        String timeoutSeconds, String stabilitySeconds, String accessUrl, String artifacts, String ports,
        String dependencies, String configuration, String databaseMode, String databaseDetails, String secrets,
        boolean required, boolean rootBuild, String kotlinJvmTarget, String applicationDeclaration) {
    /**
     * Initializes multi component form state through its shared constructor contract.
     * <p>通过共享构造契约初始化多组件表单状态。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param relativeRoot relative root / 相对根目录
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param runtimePrimary runtime primary / 运行时主
     * @param runtimeSecondary runtime secondary / 运行时次要
     * @param runtimeVersion runtime version / 运行时版本
     * @param runtimeArguments runtime arguments / 运行时参数
     * @param runtimeAdditional runtime additional / 运行时额外
     * @param healthMode health mode / 健康模式
     * @param healthEndpoint health endpoint / 健康端点
     * @param expectedStatus expected status / 预期状态
     * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
     * @param stabilitySeconds required continuous healthy interval in seconds / 要求连续健康的时间间隔，单位为秒
     * @param accessUrl access url / 访问URL
     * @param artifacts artifacts / 制品集合
     * @param ports bound host ports / 绑定的宿主机端口
     * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param databaseMode the explicit database review mode / 显式数据库审阅模式
     * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param required whether the whole application requires this component / 整体应用是否需要此组件
     * @param rootBuild root build / 根目录构建
     * @param kotlinJvmTarget kotlin jvm target / kotlinJvm目标
     */
    public MultiComponentFormState(String componentId, String relativeRoot, String projectType, String runtimePrimary,
            String runtimeSecondary, String runtimeVersion, String runtimeArguments, String runtimeAdditional,
            String healthMode, String healthEndpoint, String expectedStatus, String timeoutSeconds,
            String stabilitySeconds, String accessUrl, String artifacts, String ports, String dependencies,
            String configuration, String databaseMode, String databaseDetails, String secrets, boolean required,
            boolean rootBuild, String kotlinJvmTarget) {
        this(componentId, relativeRoot, projectType, runtimePrimary, runtimeSecondary, runtimeVersion, runtimeArguments,
                runtimeAdditional, healthMode, healthEndpoint, expectedStatus, timeoutSeconds, stabilitySeconds,
                accessUrl, artifacts, ports, dependencies, configuration, databaseMode, databaseDetails, secrets,
                required, rootBuild, kotlinJvmTarget, "");
    }

    /**
     * Initializes multi component form state through its shared constructor contract.
     * <p>通过共享构造契约初始化多组件表单状态。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param relativeRoot relative root / 相对根目录
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param runtimePrimary runtime primary / 运行时主
     * @param runtimeSecondary runtime secondary / 运行时次要
     * @param runtimeVersion runtime version / 运行时版本
     * @param runtimeArguments runtime arguments / 运行时参数
     * @param runtimeAdditional runtime additional / 运行时额外
     * @param healthMode health mode / 健康模式
     * @param healthEndpoint health endpoint / 健康端点
     * @param expectedStatus expected status / 预期状态
     * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
     * @param stabilitySeconds required continuous healthy interval in seconds / 要求连续健康的时间间隔，单位为秒
     * @param accessUrl access url / 访问URL
     * @param artifacts artifacts / 制品集合
     * @param ports bound host ports / 绑定的宿主机端口
     * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param databaseMode the explicit database review mode / 显式数据库审阅模式
     * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param required whether the whole application requires this component / 整体应用是否需要此组件
     * @param rootBuild root build / 根目录构建
     */
    public MultiComponentFormState(String componentId, String relativeRoot, String projectType, String runtimePrimary,
            String runtimeSecondary, String runtimeVersion, String runtimeArguments, String runtimeAdditional,
            String healthMode, String healthEndpoint, String expectedStatus, String timeoutSeconds,
            String stabilitySeconds, String accessUrl, String artifacts, String ports, String dependencies,
            String configuration, String databaseMode, String databaseDetails, String secrets, boolean required,
            boolean rootBuild) {
        this(componentId, relativeRoot, projectType, runtimePrimary, runtimeSecondary, runtimeVersion, runtimeArguments,
                runtimeAdditional, healthMode, healthEndpoint, expectedStatus, timeoutSeconds, stabilitySeconds,
                accessUrl, artifacts, ports, dependencies, configuration, databaseMode, databaseDetails, secrets,
                required, rootBuild, "");
    }

    /**
     * Rejects missing state values. / 拒绝缺失状态值。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param relativeRoot relative root / 相对根目录
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param runtimePrimary runtime primary / 运行时主
     * @param runtimeSecondary runtime secondary / 运行时次要
     * @param runtimeVersion runtime version / 运行时版本
     * @param runtimeArguments runtime arguments / 运行时参数
     * @param runtimeAdditional runtime additional / 运行时额外
     * @param healthMode health mode / 健康模式
     * @param healthEndpoint health endpoint / 健康端点
     * @param expectedStatus expected status / 预期状态
     * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
     * @param stabilitySeconds required continuous healthy interval in seconds / 要求连续健康的时间间隔，单位为秒
     * @param accessUrl access url / 访问URL
     * @param artifacts artifacts / 制品集合
     * @param ports bound host ports / 绑定的宿主机端口
     * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param databaseMode the explicit database review mode / 显式数据库审阅模式
     * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param required whether the whole application requires this component / 整体应用是否需要此组件
     * @param rootBuild root build / 根目录构建
     * @param kotlinJvmTarget kotlin jvm target / kotlinJvm目标
     * @param applicationDeclaration application declaration / 应用声明
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MultiComponentFormState {
        Objects.requireNonNull(applicationDeclaration, "applicationDeclaration");
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
        Objects.requireNonNull(databaseMode, "databaseMode");
        Objects.requireNonNull(databaseDetails, "databaseDetails");
        Objects.requireNonNull(secrets, "secrets");
    }
}
