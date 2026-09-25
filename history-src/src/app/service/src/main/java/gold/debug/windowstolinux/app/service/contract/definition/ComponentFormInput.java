package gold.debug.windowstolinux.app.service.contract.definition;

import java.util.Objects;

import gold.debug.windowstolinux.shared.model.deployment.DatabaseReviewMode;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

/**
 * Secret-value-free typed form state for one explicitly reviewed component.
 *
 *  <p>一个显式审阅组件不含秘密值的类型化表单状态。
 *
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param relativeSourceRoot relative source root / 相对源码根目录
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
 * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
 * @param artifactPaths application-root-relative artifact paths / 相对应用根的产物路径
 * @param declaredPorts declared ports / 已声明端口集合
 * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
 * @param configurationEntries configuration entries / 配置条目
 * @param databaseMode the explicit database review mode / 显式数据库审阅模式
 * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
 * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
 * @param required whether the whole application requires this component / 整体应用是否需要此组件
 * @param rootBuild root build / 根目录构建
 * @param kotlinJvmTarget kotlin jvm target / kotlinJvm目标
 * @param applicationDeclaration application declaration / 应用声明
 */
public record ComponentFormInput(String componentId, String relativeSourceRoot, DeploymentProjectType projectType,
        String runtimePrimary, String runtimeSecondary, String runtimeVersion, String runtimeArguments,
        String runtimeAdditional, ComponentHealthMode healthMode, String healthEndpoint, String expectedStatus,
        String timeoutSeconds, String stabilitySeconds, String userAccessUrl, String artifactPaths,
        String declaredPorts, String dependencies, String configurationEntries, DatabaseReviewMode databaseMode,
        String databaseDetails, String secretReferences, boolean required, boolean rootBuild, String kotlinJvmTarget,
        String applicationDeclaration) {
    /**
     * Initializes component form input through its shared constructor contract.
     * <p>通过共享构造契约初始化组件表单输入。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param relativeSourceRoot relative source root / 相对源码根目录
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
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param artifactPaths application-root-relative artifact paths / 相对应用根的产物路径
     * @param declaredPorts declared ports / 已声明端口集合
     * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
     * @param configurationEntries configuration entries / 配置条目
     * @param databaseMode the explicit database review mode / 显式数据库审阅模式
     * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param required whether the whole application requires this component / 整体应用是否需要此组件
     * @param rootBuild root build / 根目录构建
     * @param kotlinJvmTarget kotlin jvm target / kotlinJvm目标
     */
    public ComponentFormInput(String componentId, String relativeSourceRoot, DeploymentProjectType projectType,
            String runtimePrimary, String runtimeSecondary, String runtimeVersion, String runtimeArguments,
            String runtimeAdditional, ComponentHealthMode healthMode, String healthEndpoint, String expectedStatus,
            String timeoutSeconds, String stabilitySeconds, String userAccessUrl, String artifactPaths,
            String declaredPorts, String dependencies, String configurationEntries, DatabaseReviewMode databaseMode,
            String databaseDetails, String secretReferences, boolean required, boolean rootBuild,
            String kotlinJvmTarget) {
        this(componentId, relativeSourceRoot, projectType, runtimePrimary, runtimeSecondary, runtimeVersion,
                runtimeArguments, runtimeAdditional, healthMode, healthEndpoint, expectedStatus, timeoutSeconds,
                stabilitySeconds, userAccessUrl, artifactPaths, declaredPorts, dependencies, configurationEntries,
                databaseMode, databaseDetails, secretReferences, required, rootBuild, kotlinJvmTarget, "");
    }

    /**
     * Initializes component form input through its shared constructor contract.
     * <p>通过共享构造契约初始化组件表单输入。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param relativeSourceRoot relative source root / 相对源码根目录
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
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param artifactPaths application-root-relative artifact paths / 相对应用根的产物路径
     * @param declaredPorts declared ports / 已声明端口集合
     * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
     * @param configurationEntries configuration entries / 配置条目
     * @param databaseMode the explicit database review mode / 显式数据库审阅模式
     * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param required whether the whole application requires this component / 整体应用是否需要此组件
     * @param rootBuild root build / 根目录构建
     */
    public ComponentFormInput(String componentId, String relativeSourceRoot, DeploymentProjectType projectType,
            String runtimePrimary, String runtimeSecondary, String runtimeVersion, String runtimeArguments,
            String runtimeAdditional, ComponentHealthMode healthMode, String healthEndpoint, String expectedStatus,
            String timeoutSeconds, String stabilitySeconds, String userAccessUrl, String artifactPaths,
            String declaredPorts, String dependencies, String configurationEntries, DatabaseReviewMode databaseMode,
            String databaseDetails, String secretReferences, boolean required, boolean rootBuild) {
        this(componentId, relativeSourceRoot, projectType, runtimePrimary, runtimeSecondary, runtimeVersion,
                runtimeArguments, runtimeAdditional, healthMode, healthEndpoint, expectedStatus, timeoutSeconds,
                stabilitySeconds, userAccessUrl, artifactPaths, declaredPorts, dependencies, configurationEntries,
                databaseMode, databaseDetails, secretReferences, required, rootBuild, "");
    }

    /**
     * Preserves only bounded text and typed selections. / 仅保留有界文本与类型化选择。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param relativeSourceRoot relative source root / 相对源码根目录
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
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param artifactPaths application-root-relative artifact paths / 相对应用根的产物路径
     * @param declaredPorts declared ports / 已声明端口集合
     * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
     * @param configurationEntries configuration entries / 配置条目
     * @param databaseMode the explicit database review mode / 显式数据库审阅模式
     * @param databaseDetails the non-secret server database details / 不含秘密的服务器数据库详情
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param required whether the whole application requires this component / 整体应用是否需要此组件
     * @param rootBuild root build / 根目录构建
     * @param kotlinJvmTarget kotlin jvm target / kotlinJvm目标
     * @param applicationDeclaration application declaration / 应用声明
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
        if (applicationDeclaration.length() > 65536)
            throw new IllegalArgumentException("application declaration too long");
    }

    /**
     * Validates textual content against the declared length and character constraints.
     * <p>按声明的长度及字符约束校验文本内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return text text / 文本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String text(String value) {
        value = Objects.requireNonNull(value, "form value").trim();
        if (value.length() > 4096)
            throw new IllegalArgumentException("component form value is too long");
        return value;
    }
}
