package gold.debug.windowstolinux.app.main.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Locates and validates supported development, JAR, and jpackage filesystem layouts. / 定位并验证受支持的开发、JAR 与 jpackage 文件系统布局。
 */
final class RuntimePathResolver {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private RuntimePathResolver() {
    }

    /**
     * Lays out from J package executable.
     * <p>布局来源J软件包可执行文件。
     *
     * @param executable executable / 可执行文件
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    static Optional<RunModeResolver.RuntimeLayout> layoutFromJPackageExecutable(Path executable) {
        executable = normalize(executable);
        Path fileName = executable.getFileName();
        Path appImageRoot = executable.getParent();
        if (fileName == null
                || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".exe")
                || !isJPackageWindowsRoot(appImageRoot)) {
            return Optional.empty();
        }
        return Optional.of(layout(RunModeResolver.RunMode.RUN_APP, appImageRoot));
    }

    /**
     * Lays out from code source.
     * <p>布局来源代码源码。
     *
     * @param sourcePath source path / 源码路径
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    static Optional<RunModeResolver.RuntimeLayout> layoutFromCodeSource(Path sourcePath) {
        sourcePath = normalize(sourcePath);
        if (Files.isDirectory(sourcePath)) {
            return findMavenModuleHome(sourcePath)
                    .map(home -> layout(RunModeResolver.RunMode.RUN_CLASS, home));
        }
        if (!isJar(sourcePath)) return Optional.empty();
        Optional<Path> appImageRoot = findJPackageHomeFromJar(sourcePath);
        if (appImageRoot.isPresent()) return Optional.of(layout(RunModeResolver.RunMode.RUN_APP, appImageRoot.get()));
        Path jarDirectory = sourcePath.getParent();
        return jarDirectory == null ? Optional.empty()
                : Optional.of(layout(RunModeResolver.RunMode.RUN_JAR, jarDirectory));
    }

    /**
     * Finds validated development module home.
     * <p>查找已验证Development模块Home。
     *
     * @param start start / 启动
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    static Optional<Path> findValidatedDevelopmentModuleHome(Path start) {
        Path current = normalize(start);
        while (current != null) {
            Optional<Path> directModule = validatedModuleHome(current);
            if (directModule.isPresent()) return directModule;
            Optional<Path> repositoryModule = validatedModuleHome(current.resolve("src/app/db"));
            if (repositoryModule.isPresent()) return repositoryModule;
            if (Files.isRegularFile(current.resolve("src/app/main/pom.xml"))) return Optional.empty();
            current = current.getParent();
        }
        return Optional.empty();
    }

    /**
     * Reports whether the directory within the caller's controlled storage boundary condition holds for this contract.
     * <p>判断当前契约是否满足调用方受控存储边界内的目录条件。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return true when directory within the caller's controlled storage boundary condition holds for this contract, false otherwise / 当前契约是否满足调用方受控存储边界内的目录条件时为 true，否则为 false
     */
    static boolean isDirectory(Path path) {
        return Files.isDirectory(normalize(path));
    }

    /**
     * Lays out runtime layout.
     * <p>布局运行时布局。
     *
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param applicationHome application home / 应用Home
     * @return constructed or resolved runtime layout / 构造或解析得到的运行时布局
     */
    static RunModeResolver.RuntimeLayout layout(RunModeResolver.RunMode mode, Path applicationHome) {
        Path normalizedHome = normalize(applicationHome);
        return new RunModeResolver.RuntimeLayout(mode, normalizedHome, normalizedHome.resolve("data"));
    }

    /**
     * Normalizes path.
     * <p>规范化路径。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return constructed or resolved path / 构造或解析得到的路径
     */
    static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /**
     * Validates and produces validated module home for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的已验证模块Home。
     *
     * @param candidate candidate / 候选
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private static Optional<Path> validatedModuleHome(Path candidate) {
        Path normalized = normalize(candidate);
        Path pom = normalized.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) return Optional.empty();
        try {
            String pomContent = Files.readString(pom);
            return pomContent.contains("<artifactId>windowstolinux-app-db</artifactId>")
                    ? Optional.of(normalized) : Optional.empty();
        } catch (Exception ignored) {
            // An unreadable candidate is rejected and other bounded layout candidates remain available. / 不可读候选会被拒绝，其他有界布局候选仍可继续检查。
            return Optional.empty();
        }
    }

    /**
     * Finds maven module home.
     * <p>查找maven模块Home。
     *
     * @param classesDirectory classes directory / 类文件目录
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private static Optional<Path> findMavenModuleHome(Path classesDirectory) {
        Path outputName = classesDirectory.getFileName();
        Path targetDirectory = classesDirectory.getParent();
        if (outputName == null || targetDirectory == null) return Optional.empty();
        boolean knownOutput = outputName.toString().equals("classes")
                || outputName.toString().equals("test-classes");
        Path targetName = targetDirectory.getFileName();
        Path moduleHome = targetDirectory.getParent();
        if (!knownOutput || targetName == null || !targetName.toString().equals("target") || moduleHome == null) {
            return Optional.empty();
        }
        return Optional.of(moduleHome);
    }

    /**
     * Finds j package home from jar.
     * <p>查找j软件包Home来源Jar。
     *
     * @param jarPath jar path / jar路径
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private static Optional<Path> findJPackageHomeFromJar(Path jarPath) {
        Path appDirectory = jarPath.getParent();
        if (appDirectory == null || appDirectory.getFileName() == null
                || !appDirectory.getFileName().toString().equalsIgnoreCase("app")) {
            return Optional.empty();
        }
        Path appImageRoot = appDirectory.getParent();
        return isJPackageWindowsRoot(appImageRoot) ? Optional.of(appImageRoot) : Optional.empty();
    }

    /**
     * Reports whether the j package windows root condition holds for this contract.
     * <p>判断当前契约是否满足j软件包Windows根目录条件。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return true when j package windows root condition holds for this contract, false otherwise / 当前契约是否满足j软件包Windows根目录条件时为 true，否则为 false
     */
    private static boolean isJPackageWindowsRoot(Path root) {
        return root != null && Files.isDirectory(root.resolve("app")) && Files.isDirectory(root.resolve("runtime"));
    }

    /**
     * Reports whether the jar condition holds for this contract.
     * <p>判断当前契约是否满足JAR 制品条件。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return true when jar condition holds for this contract, false otherwise / 当前契约是否满足JAR 制品条件时为 true，否则为 false
     */
    private static boolean isJar(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && fileName.toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }
}
