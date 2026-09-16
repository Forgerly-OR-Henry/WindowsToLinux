package gold.debug.windowstolinux.app.main.startup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

/** Materializes complete independent repository fixtures, preserving binary dependencies. / 实例化完整独立仓库夹具并保留二进制依赖。 */
final class RepositoryServiceFixture {
    private static final Set<String> GENERATED = Set.of("target", "node_modules", ".venv", ".gradle", "build", "dist", ".w2l", "__pycache__", "obj", "vendor");
    private static final Set<String> TEXT_SUFFIXES = Set.of("java", "kt", "kts", "js", "ts", "py", "go", "rs", "cs", "php", "rb",
            "json", "yaml", "yml", "toml", "xml", "properties", "gradle", "csproj", "lock", "sum", "mod", "txt", "md", "ru", "c", "cpp", "h", "hpp");

    private RepositoryServiceFixture() { }

    static Path copy(Path parent, String applicationId, String combination, boolean healthy) throws IOException {
        Path base = parent.toAbsolutePath().normalize();
        Path target = base.resolve(applicationId).normalize();
        if (!target.getParent().equals(base)) throw new IOException("fixture application must be a direct child");
        if (Files.exists(target)) {
            try (var files = Files.walk(target)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        String scenario = healthy ? "success-deployment-smoke" : "failure-health-rollback";
        Path source = repositoryRoot().resolve("test").resolve(combination).resolve(scenario);
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path relative = source.relativize(file);
                boolean generated = false;
                for (Path segment : relative) generated |= GENERATED.contains(segment.toString());
                if (generated) continue;
                if (Files.isSymbolicLink(file)) throw new IOException("fixture must not contain symbolic links");
                Path destination = target.resolve(relative);
                if (Files.isDirectory(file)) Files.createDirectories(destination);
                else Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        return target;
    }

    static void replaceText(Path root, Map<String, String> replacements) throws IOException {
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                String suffix = name.substring(name.lastIndexOf('.') + 1);
                if (!TEXT_SUFFIXES.contains(suffix) && !Set.of("Gemfile", "Pipfile", ".ruby-version").contains(name)) continue;
                String original = Files.readString(file);
                String updated = original;
                for (var replacement : replacements.entrySet()) updated = updated.replace(replacement.getKey(), replacement.getValue());
                if (!original.equals(updated)) Files.writeString(file, updated);
            }
        }
    }

    static Path repositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath().normalize(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("test/matrix.json"))) return path;
        }
        throw new IllegalStateException("fixture repository root was not found");
    }
}
