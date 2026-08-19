package gold.debug.windowstolinux.app.main.runtime;

import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the application home and its fixed {@code data} directory for the supported development, JAR and jpackage layouts.
 *
 * <p>为受支持的开发、JAR 和 jpackage 布局解析应用主目录及其固定 {@code data} 目录。
 */
public final class RunModeResolver {

    /**
     * Defines the supported {@code RunMode} values.
     *
     * <p>定义受支持的 {@code RunMode} 取值。
     */
    public enum RunMode {
        /**
         * Represents the {@code RUN_CLASS} option.
         *
         * <p>表示 {@code RUN_CLASS} 选项。
         */
        RUN_CLASS,
        /**
         * Represents the {@code RUN_JAR} option.
         *
         * <p>表示 {@code RUN_JAR} 选项。
         */
        RUN_JAR,
        /**
         * Represents the {@code RUN_APP} option.
         *
         * <p>表示 {@code RUN_APP} 选项。
         */
        RUN_APP,
        /**
         * Represents the {@code RUN_UNKNOWN} option.
         *
         * <p>表示 {@code RUN_UNKNOWN} 选项。
         */
        RUN_UNKNOWN
    }

    /**
     * A resolved runtime layout. Unknown layouts are deliberately not representable so callers cannot silently fall back to the working directory.
     *
     * <p>已解析的运行时布局。未知布局被刻意设计为不可表示，从而防止调用方静默回退到工作目录。
     *
     * @param mode the {@code mode} value / {@code mode} 值
     * @param applicationHome the {@code applicationHome} value / {@code applicationHome} 值
     * @param dataDirectory the {@code dataDirectory} value / {@code dataDirectory} 值
     */
    public record RuntimeLayout(
            RunMode mode,
            Path applicationHome,
            Path dataDirectory
    ) {
        /**
         * Creates a {@code RuntimeLayout} instance.
         *
         * <p>创建 {@code RuntimeLayout} 实例。
         *
         * @param mode the {@code mode} value / {@code mode} 值
         * @param applicationHome the {@code applicationHome} value / {@code applicationHome} 值
         * @param dataDirectory the {@code dataDirectory} value / {@code dataDirectory} 值
         * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
         * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
         */
        public RuntimeLayout {
            Objects.requireNonNull(mode, "mode");
            if (mode == RunMode.RUN_UNKNOWN) {
                throw new IllegalArgumentException("RuntimeLayout cannot use RUN_UNKNOWN");
            }
            applicationHome = normalize(applicationHome, "applicationHome");
            dataDirectory = normalize(dataDirectory, "dataDirectory");
        }

        private static Path normalize(Path path, String name) {
            return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
        }
    }

    private RunModeResolver() {
    }

    /**
     * Detects the current runtime mode.
     *
     * <p>检测当前运行模式。
     *
     * @param anchorClass the {@code anchorClass} value / {@code anchorClass} 值
     * @return the operation result / 操作结果
     */
    public static RunMode detect(Class<?> anchorClass) {
        return resolveKnown(anchorClass)
                .map(RuntimeLayout::mode)
                .orElse(RunMode.RUN_UNKNOWN);
    }

    /**
     * Resolves the current runtime layout.
     *
     * <p>解析当前运行时布局。
     *
     * @param anchorClass the {@code anchorClass} value / {@code anchorClass} 值
     * @return the operation result / 操作结果
     */
    public static RuntimeLayout resolve(Class<?> anchorClass) {
        return resolveKnown(anchorClass).orElseThrow(() -> new IllegalStateException(
                "Application runtime layout could not be recognized; ensure anchorClass comes from app/main and "
                        + "launch through Maven, an executable JAR, or a jpackage application"
        ));
    }

    /**
     * Performs the {@code resolveDataDirectory} operation.
     *
     * <p>执行 {@code resolveDataDirectory} 操作。
     *
     * @param anchorClass the {@code anchorClass} value / {@code anchorClass} 值
     * @return the operation result / 操作结果
     */
    public static Path resolveDataDirectory(Class<?> anchorClass) {
        return resolve(anchorClass).dataDirectory();
    }

    private static Optional<RuntimeLayout> resolveKnown(Class<?> anchorClass) {
        Objects.requireNonNull(anchorClass, "anchorClass");
        return resolveFromEvidence(
                currentProcessCommand(),
                codeSourcePath(anchorClass),
                currentWorkingDirectory()
        );
    }

    /**
     * Package-private deterministic seam used by tests without global state changes.
     *
     * <p>供测试使用且不改变全局状态的包级确定性接缝。
     *
     * @param processCommand the {@code processCommand} value / {@code processCommand} 值
     * @param codeSource the {@code codeSource} value / {@code codeSource} 值
     * @return the optional operation result / 可选操作结果
     */
    static Optional<RuntimeLayout> resolveFromEvidence(
            Optional<Path> processCommand,
            Optional<Path> codeSource
    ) {
        return resolveFromEvidence(processCommand, codeSource, Optional.empty());
    }

    /**
     * Package-private overload that allows deterministic IDE layout tests.
     *
     * <p>允许确定性 IDE 布局测试的包级重载。
     *
     * @param processCommand the {@code processCommand} value / {@code processCommand} 值
     * @param codeSource the {@code codeSource} value / {@code codeSource} 值
     * @param workingDirectory the {@code workingDirectory} value / {@code workingDirectory} 值
     * @return the optional operation result / 可选操作结果
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    static Optional<RuntimeLayout> resolveFromEvidence(
            Optional<Path> processCommand,
            Optional<Path> codeSource,
            Optional<Path> workingDirectory
    ) {
        Objects.requireNonNull(processCommand, "processCommand");
        Objects.requireNonNull(codeSource, "codeSource");
        Objects.requireNonNull(workingDirectory, "workingDirectory");

        Optional<RuntimeLayout> launcherLayout = processCommand
                .flatMap(RuntimePathResolver::layoutFromJPackageExecutable);
        if (launcherLayout.isPresent()) {
            return launcherLayout;
        }

        Optional<RuntimeLayout> codeLayout = codeSource
                .flatMap(RuntimePathResolver::layoutFromCodeSource);
        if (codeLayout.isPresent()) {
            return codeLayout;
        }

        boolean classDirectory = codeSource
                .filter(RuntimePathResolver::isDirectory)
                .isPresent();
        if (!classDirectory) {
            return Optional.empty();
        }

        return workingDirectory
                .flatMap(RuntimePathResolver::findValidatedDevelopmentModuleHome)
                .map(home -> RuntimePathResolver.layout(RunMode.RUN_CLASS, home));
    }

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
            return Optional.empty();
        }
    }

    private static Optional<Path> currentProcessCommand() {
        try {
            return ProcessHandle.current().info().command().map(Paths::get);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Path> currentWorkingDirectory() {
        try {
            return Optional.of(Path.of(""));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

}
