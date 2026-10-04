package gold.debug.windowstolinux.app.main.runtime;

import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;

/**
 * Validates mode anchors and resolves configured paths against the workspace, main JAR or EXE directory.
 *
 *  <p>验证模式锚点，并以工作区、主入口 JAR 或 EXE 所在目录解析配置路径。
 */
public final class RunModeResolver {

    /**
     * Defines the supported {@code RunMode} values.
     *
     *  <p>定义受支持的 {@code RunMode} 取值。
     */
    public enum RunMode {
        /**
         * Represents the {@code RUN_CLASS} option.
         *
         *  <p>表示 {@code RUN_CLASS} 选项。
         */
        RUN_CLASS,
        /**
         * Represents the {@code RUN_JAR} option.
         *
         *  <p>表示 {@code RUN_JAR} 选项。
         */
        RUN_JAR,
        /**
         * Represents the {@code RUN_APP} option.
         *
         *  <p>表示 {@code RUN_APP} 选项。
         */
        RUN_APP,
        /**
         * Represents the {@code RUN_UNKNOWN} option.
         *
         *  <p>表示 {@code RUN_UNKNOWN} 选项。
         */
        RUN_UNKNOWN
    }

    /**
     * A resolved runtime layout. Unknown layouts are deliberately not representable so callers cannot silently fall back to the working directory.
     *
     *  <p>已解析的运行时布局。未知布局被刻意设计为不可表示，从而防止调用方静默回退到工作目录。
     *
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param applicationHome application home / 应用Home
     * @param dataDirectory data directory / 数据目录
     */
    public record RuntimeLayout(RunMode mode, Path applicationHome, Path dataDirectory) {
        /**
         * Validates and binds the inputs required by runtime layout.
         * <p>校验并绑定运行时布局所需输入。
         *
         * @param mode selected operating or storage mode / 所选运行或存储模式
         * @param applicationHome application home / 应用Home
         * @param dataDirectory data directory / 数据目录
         * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public RuntimeLayout {
            Objects.requireNonNull(mode, "mode");
            if (mode == RunMode.RUN_UNKNOWN) {
                throw new IllegalArgumentException("RuntimeLayout cannot use RUN_UNKNOWN");
            }
            applicationHome = normalize(applicationHome, "applicationHome");
            dataDirectory = normalize(dataDirectory, "dataDirectory");
        }

        /**
         * Normalizes path.
         * <p>规范化路径。
         *
         * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
         * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
         * @return constructed or resolved path / 构造或解析得到的路径
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        private static Path normalize(Path path, String name) {
            return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
        }
    }

    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private RunModeResolver() {
    }

    /**
     * Detects the current runtime mode.
     *
     *  <p>检测当前运行模式。
     *
     * @return the operation result / 操作结果
     */
    public static RunMode detect() {
        return resolveKnown().map(RuntimeLayout::mode).orElse(RunMode.RUN_UNKNOWN);
    }

    /**
     * Resolves the current runtime layout.
     *
     *  <p>解析当前运行时布局。
     *
     * @return the operation result / 操作结果
     */
    public static RuntimeLayout resolve() {
        return resolveKnown().orElseThrow(() -> new IllegalStateException(
            "Application runtime layout could not be recognized; ensure app/db is loaded from "
                + "Maven output, its module JAR, or a jpackage application"));
    }

    /** Applies the configured path to verified mode anchors. / 对已验证模式锚点应用配置路径。
     * @param anchors detected anchors / 已检测锚点
     * @param config immutable values / 不可变配置
     * @return configured layout / 已配置布局 */
    public static RuntimeLayout configured(RuntimeLayout anchors, gold.debug.windowstolinux.app.config.AppConfig config) {
        Path selected = Path.of(config.dataDirectory());
        return new RuntimeLayout(anchors.mode(), anchors.applicationHome(), selected.isAbsolute() ? selected : anchors.applicationHome().resolve(selected));
    }

    /** Returns the default data anchor before external configuration. / 返回外置配置前的默认数据锚点。
     * @return default directory / 默认目录 */
    public static Path resolveDataDirectory() {
        return resolve().dataDirectory();
    }

    /**
     * Resolves known.
     * <p>解析已知。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private static Optional<RuntimeLayout> resolveKnown() {
        return resolveStartupFromEvidence(currentProcessCommand(), codeSourcePath(PlatformDatabase.class),
            codeSourcePath(RunModeResolver.class), currentWorkingDirectory());
    }

    /**
     * Resolves program and data anchors independently without using the current directory as a program root.
     * / 独立解析程序与数据锚点，不把当前目录作为程序根。
     * @param processCommand actual executable metadata / 实际可执行程序元数据
     * @param databaseSource database-module code source / 数据库模块代码来源
     * @param mainSource main-module code source / 主模块代码来源
     * @param workingDirectory optional validated IDE workspace evidence / 可选的已验证 IDE 工作区证据
     * @return recognized layout or no result / 已识别布局或无结果
     */
    static Optional<RuntimeLayout> resolveStartupFromEvidence(Optional<Path> processCommand,
                                                              Optional<Path> databaseSource, Optional<Path> mainSource, Optional<Path> workingDirectory) {
        var data = resolveFromEvidence(processCommand, databaseSource, workingDirectory);
        if (data.isEmpty())
            return Optional.empty();
        var layout = data.get();
        if (layout.mode() == RunMode.RUN_APP)
            return data;
        if (layout.mode() == RunMode.RUN_JAR)
            return mainSource.filter(java.nio.file.Files::isRegularFile)
                .flatMap(RuntimePathResolver::layoutFromCodeSource).filter(main -> main.mode() == RunMode.RUN_JAR)
                .map(main -> new RuntimeLayout(layout.mode(), main.applicationHome(), main.dataDirectory()));
        Path dbModule = layout.dataDirectory().getParent();
        Path root = dbModule == null || dbModule.getParent() == null || dbModule.getParent().getParent() == null
            ? null
            : dbModule.getParent().getParent().getParent();
        if (root == null || !dbModule.equals(root.resolve("src/app/db"))
            || mainSource.filter(java.nio.file.Files::isDirectory).isEmpty()
            || !mainSource.orElseThrow().toAbsolutePath().normalize().startsWith(root)
            || RuntimePathResolver.findValidatedDevelopmentModuleHome(root).filter(dbModule::equals).isEmpty()
            || !java.nio.file.Files.isRegularFile(root.resolve("src/app/main/pom.xml"))
            || !java.nio.file.Files.isRegularFile(root.resolve("pom.xml")))
            return Optional.empty();
        return Optional.of(new RuntimeLayout(layout.mode(), root, layout.dataDirectory()));
    }

    /**
     * Package-private deterministic seam used by tests without global state changes.
     *
     *  <p>供测试使用且不改变全局状态的包级确定性接缝。
     *
     * @param processCommand process command / 进程命令
     * @param codeSource code source / 代码源码
     * @return the optional operation result / 可选操作结果
     */
    static Optional<RuntimeLayout> resolveFromEvidence(Optional<Path> processCommand, Optional<Path> codeSource) {
        return resolveFromEvidence(processCommand, codeSource, Optional.empty());
    }

    /**
     * Package-private overload that allows deterministic IDE layout tests.
     *
     *  <p>允许确定性 IDE 布局测试的包级重载。
     *
     * @param processCommand process command / 进程命令
     * @param codeSource code source / 代码源码
     * @param workingDirectory working directory / 工作目录
     * @return the optional operation result / 可选操作结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static Optional<RuntimeLayout> resolveFromEvidence(Optional<Path> processCommand, Optional<Path> codeSource,
                                                       Optional<Path> workingDirectory) {
        Objects.requireNonNull(processCommand, "processCommand");
        Objects.requireNonNull(codeSource, "codeSource");
        Objects.requireNonNull(workingDirectory, "workingDirectory");

        Optional<RuntimeLayout> launcherLayout = processCommand
            .flatMap(RuntimePathResolver::layoutFromJPackageExecutable);
        if (launcherLayout.isPresent()) {
            return launcherLayout;
        }

        Optional<RuntimeLayout> codeLayout = codeSource.flatMap(RuntimePathResolver::layoutFromCodeSource);
        if (codeLayout.isPresent()) {
            return codeLayout;
        }

        boolean classDirectory = codeSource.filter(RuntimePathResolver::isDirectory).isPresent();
        if (!classDirectory) {
            return Optional.empty();
        }

        return workingDirectory.flatMap(RuntimePathResolver::findValidatedDevelopmentModuleHome)
            .map(home -> RuntimePathResolver.layout(RunMode.RUN_CLASS, home));
    }

    /**
     * Resolves the anchor class's protection-domain location when it is available as a local path.
     * <p>在锚点类的保护域位置可表示为本地路径时解析该位置。
     *
     * @param anchorClass anchor class / 锚点类
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private static Optional<Path> codeSourcePath(Class<?> anchorClass) {
        try {
            ProtectionDomain protectionDomain = anchorClass.getProtectionDomain();
            if (protectionDomain == null) {
                return Optional.empty();
            }
            CodeSource codeSource = protectionDomain.getCodeSource();
            if (codeSource == null) {
                return Optional.empty();
            }
            URL location = codeSource.getLocation();
            if (location == null) {
                return Optional.empty();
            }
            URI uri = location.toURI();
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return Optional.empty();
            }
            return Optional.of(Paths.get(uri));
        } catch (Exception ignored) {
            // Unavailable code-source metadata is not authoritative; other bounded signals remain. / 不可用的代码源元数据不具权威性，仍可检查其他有界信号。
            return Optional.empty();
        }
    }

    /**
     * Returns current process command.
     * <p>返回当前进程命令。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private static Optional<Path> currentProcessCommand() {
        try {
            return ProcessHandle.current().info().command().map(Paths::get);
        } catch (Exception ignored) {
            // Process command metadata is optional and has a deterministic empty fallback. / 进程命令元数据可选，并具有确定性的空回退。
            return Optional.empty();
        }
    }

    /**
     * Returns current working directory.
     * <p>返回当前工作目录。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private static Optional<Path> currentWorkingDirectory() {
        try {
            return Optional.of(Path.of(""));
        } catch (Exception ignored) {
            // An inaccessible working directory is represented as absent, never guessed. / 无法访问的工作目录表示为缺失，绝不猜测。
            return Optional.empty();
        }
    }

}
