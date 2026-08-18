package gold.debug.windowstolinux.shared.model.project;

/**
 * A fixed target-host build tool entrypoint, never an arbitrary command line.
 *
 * <p>固定的目标机构建工具入口，绝不是任意命令行。
 */
public enum DeploymentBuildTool {
    /** No build entry exists for a mutation-free recognition preview. / 禁止修改目标机的识别预览没有构建入口。 */
    NONE_PREVIEW,
    /** Gradle Wrapper. / Gradle Wrapper 构建包装器。 */
    GRADLE_WRAPPER,
    /** Maven Wrapper. / Maven Wrapper 构建包装器。 */
    MAVEN_WRAPPER,
    /** Maven installed on the target host. / 目标机上安装的 Maven。 */
    MAVEN,
    /** The Java launcher for a previously produced JAR. / 启动已生成 JAR 的 Java 启动器。 */
    JAVA,
    /** npm with a package lock. / 搭配 package lock 的 npm。 */
    NPM,
    /** pnpm with its lockfile. / 搭配锁文件的 pnpm。 */
    PNPM,
    /** Yarn with its lockfile. / 搭配锁文件的 Yarn。 */
    YARN,
    /** A project-specific Python virtual environment. / 项目专属 Python 虚拟环境。 */
    PYTHON_VENV,
    /** A deterministic static-site build. / 确定性的静态站点构建。 */
    STATIC_SITE_BUILD,
    /** Docker or Podman image construction from one Dockerfile. / 由一个 Dockerfile 构建 Docker 或 Podman 镜像。 */
    CONTAINER_BUILD,
    /** Go module build with readonly dependency metadata. / 使用只读依赖元数据构建 Go 模块。 */
    GO_MODULE,
    /** Cargo build with the checked-in lockfile. / 使用已检入锁文件的 Cargo 构建。 */
    CARGO_LOCKED,
    /** .NET restore and publish in locked mode. / 锁定模式下的 .NET 恢复与发布。 */
    DOTNET_LOCKED,
    /** Kotlin/JVM build through the checked-in Gradle Wrapper and dependency lock. / 通过已检入 Gradle Wrapper 与依赖锁构建 Kotlin/JVM。 */
    GRADLE_KOTLIN_WRAPPER,
    /** Composer install from the checked-in lock without plugins or scripts. / 从已检入锁执行不含插件与脚本的 Composer 安装。 */
    COMPOSER_LOCKED,
    /** Bundler install from the checked-in lock. / 从已检入锁执行 Bundler 安装。 */
    BUNDLER_LOCKED
}
