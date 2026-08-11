package gold.debug.windowstolinux.shared.model.project;

/**
 * A fixed target-host build tool entrypoint, never an arbitrary command line.
 *
 * <p>固定的目标机构建工具入口，绝不是任意命令行。
 */
public enum PhaseTwoBuildTool {
    /** Gradle Wrapper. / Gradle Wrapper。 */
    GRADLE_WRAPPER,
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
    CONTAINER_BUILD
}
