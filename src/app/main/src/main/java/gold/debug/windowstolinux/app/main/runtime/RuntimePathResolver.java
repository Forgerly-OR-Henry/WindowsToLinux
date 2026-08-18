package gold.debug.windowstolinux.app.main.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Locates and validates supported development, JAR, and jpackage filesystem layouts. / 定位并验证受支持的开发、JAR 与 jpackage 文件系统布局。 */
final class RuntimePathResolver {
    private RuntimePathResolver() {
    }

    static Optional<RunModeDetector.RuntimeLayout> layoutFromJPackageExecutable(Path executable) {
        executable = normalize(executable);
        Path fileName = executable.getFileName();
        Path appImageRoot = executable.getParent();
        if (fileName == null
                || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".exe")
                || !isJPackageWindowsRoot(appImageRoot)) {
            return Optional.empty();
        }
        return Optional.of(layout(RunModeDetector.RunMode.RUN_APP, appImageRoot));
    }

    static Optional<RunModeDetector.RuntimeLayout> layoutFromCodeSource(Path sourcePath) {
        sourcePath = normalize(sourcePath);
        if (Files.isDirectory(sourcePath)) {
            return findMavenModuleHome(sourcePath)
                    .map(home -> layout(RunModeDetector.RunMode.RUN_CLASS, home));
        }
        if (!isJar(sourcePath)) return Optional.empty();
        Optional<Path> appImageRoot = findJPackageHomeFromJar(sourcePath);
        if (appImageRoot.isPresent()) return Optional.of(layout(RunModeDetector.RunMode.RUN_APP, appImageRoot.get()));
        Path jarDirectory = sourcePath.getParent();
        return jarDirectory == null ? Optional.empty()
                : Optional.of(layout(RunModeDetector.RunMode.RUN_JAR, jarDirectory));
    }

    static Optional<Path> findValidatedDevelopmentModuleHome(Path start) {
        Path current = normalize(start);
        while (current != null) {
            Optional<Path> directModule = validatedModuleHome(current);
            if (directModule.isPresent()) return directModule;
            Optional<Path> repositoryModule = validatedModuleHome(current.resolve("src/app/main"));
            if (repositoryModule.isPresent()) return repositoryModule;
            current = current.getParent();
        }
        return Optional.empty();
    }

    static boolean isDirectory(Path path) {
        return Files.isDirectory(normalize(path));
    }

    static RunModeDetector.RuntimeLayout layout(RunModeDetector.RunMode mode, Path applicationHome) {
        Path normalizedHome = normalize(applicationHome);
        return new RunModeDetector.RuntimeLayout(mode, normalizedHome, normalizedHome.resolve("data"));
    }

    static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static Optional<Path> validatedModuleHome(Path candidate) {
        Path normalized = normalize(candidate);
        Path pom = normalized.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) return Optional.empty();
        try {
            String pomContent = Files.readString(pom);
            return pomContent.contains("<artifactId>windowstolinux-app-main</artifactId>")
                    ? Optional.of(normalized) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

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

    private static Optional<Path> findJPackageHomeFromJar(Path jarPath) {
        Path appDirectory = jarPath.getParent();
        if (appDirectory == null || appDirectory.getFileName() == null
                || !appDirectory.getFileName().toString().equalsIgnoreCase("app")) {
            return Optional.empty();
        }
        Path appImageRoot = appDirectory.getParent();
        return isJPackageWindowsRoot(appImageRoot) ? Optional.of(appImageRoot) : Optional.empty();
    }

    private static boolean isJPackageWindowsRoot(Path root) {
        return root != null && Files.isDirectory(root.resolve("app")) && Files.isDirectory(root.resolve("runtime"));
    }

    private static boolean isJar(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && fileName.toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }
}
