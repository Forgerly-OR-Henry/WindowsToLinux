package gold.debug.windowstolinux.shared.analyze.component;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Bounded static discovery; directories and declarations are evidence, never executable instructions. / 有界静态发现，目录和声明仅作为证据，不作为可执行指令。 */
public final class ProjectComponentDiscovery {
    private static final Set<String> EXCLUDED = Set.of(".git", ".idea", ".ai-workspace", "node_modules", "target",
            "build", "dist", ".venv", "venv", "vendor", "test", "tests", "examples", "docs");

    /** Discovers at most 64 component roots without executing project content or following links. / 最多发现 64 个组件根目录，不执行项目内容或跟随链接。 */
    public List<DiscoveredProjectComponent> discover(Path selected) throws IOException {
        Path root = selected.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
            throw new IOException("source must be a regular directory");
        Map<Path, Set<DeploymentProjectType>> candidates = new TreeMap<>();
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 12, new SimpleFileVisitor<>() {
            private int count;
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return !directory.equals(root) && EXCLUDED.contains(directory.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (++count > 100_000 || candidates.size() > 64) throw new IOException("source discovery limit exceeded");
                if (Files.isSymbolicLink(file)) throw new IOException("source contains a symbolic link");
                String name = file.getFileName().toString();
                Set<DeploymentProjectType> types = classify(name, file);
                if (!types.isEmpty()) candidates.computeIfAbsent(file.getParent(), ignored -> new LinkedHashSet<>()).addAll(types);
                return FileVisitResult.CONTINUE;
            }
        });
        Set<Path> nestedStatic = new HashSet<>();
        candidates.forEach((directory, types) -> {
            if (types.equals(Set.of(DeploymentProjectType.STATIC_SITE)) && candidates.keySet().stream()
                    .anyMatch(parent -> !parent.equals(directory) && directory.startsWith(parent))) nestedStatic.add(directory);
        });
        nestedStatic.forEach(candidates::remove);
        List<DiscoveredProjectComponent> result = new ArrayList<>();
        for (var entry : candidates.entrySet()) {
            Path relative = root.relativize(entry.getKey());
            String path = relative.toString().replace('\\', '/');
            String id = path.isEmpty() ? "app" : path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
            if (id.length() > 50) id = id.substring(0, 41) + "-" + Integer.toUnsignedString(path.hashCode(), 16);
            result.add(new DiscoveredProjectComponent(id, relative, List.copyOf(entry.getValue())));
        }
        if (result.stream().map(DiscoveredProjectComponent::id).distinct().count() != result.size())
            throw new IOException("component names collide after normalization; rename their source directories");
        if (result.isEmpty()) result.add(new DiscoveredProjectComponent("app", Path.of(""),
                Arrays.stream(DeploymentProjectType.values()).filter(DeploymentProjectType::deployable).toList()));
        return List.copyOf(result);
    }

    private static Set<DeploymentProjectType> classify(String name, Path file) throws IOException {
        return switch (name) {
            case "pom.xml", "build.gradle", "build.gradle.kts" -> {
                String declared = text(file);
                yield declared.matches("(?s).*<packaging>\\s*pom\\s*</packaging>.*") ? Set.of()
                    : Set.of(declared.contains("spring-boot") ? DeploymentProjectType.SPRING_BOOT : DeploymentProjectType.JAVA_JAR);
            }
            case "package.json" -> {
                String value = text(file);
                if (value.contains("\"workspaces\"") && !value.contains("\"start\"") && !value.contains("\"build\"")) yield Set.of();
                boolean web = value.contains("\"vite\"") || value.contains("\"vue-cli-service\"")
                        || value.contains("\"react-scripts\"");
                yield Set.of(web ? DeploymentProjectType.STATIC_SITE : DeploymentProjectType.NODE_SERVICE);
            }
            case "pyproject.toml", "Pipfile" -> Set.of(DeploymentProjectType.PYTHON_SERVICE);
            case "Dockerfile" -> Set.of(DeploymentProjectType.DOCKERFILE_CONTAINER);
            case "go.mod" -> Set.of(DeploymentProjectType.GO_SERVICE);
            case "Cargo.toml" -> Set.of(DeploymentProjectType.RUST_SERVICE);
            case "CMakeLists.txt" -> Set.of(DeploymentProjectType.CMAKE_SERVICE);
            case "windowstolinux-java.properties" -> Set.of(DeploymentProjectType.JAVA_SOURCE);
            case "windowstolinux-kotlin.properties" -> Set.of(DeploymentProjectType.KOTLIN_SERVICE);
            case "windowstolinux-php.properties", "composer.json" -> Set.of(DeploymentProjectType.PHP_SERVICE);
            case "windowstolinux-ruby.properties", "Gemfile" -> Set.of(DeploymentProjectType.RUBY_SERVICE);
            case "index.html" -> file.getParent().getFileName().toString().equals("public")
                    || Files.exists(file.getParent().resolve("package.json")) ? Set.of() : Set.of(DeploymentProjectType.STATIC_SITE);
            default -> name.endsWith(".csproj") ? Set.of(DeploymentProjectType.DOTNET_SERVICE) : Set.of();
        };
    }

    private static String text(Path file) throws IOException {
        if (Files.size(file) > 2 * 1024 * 1024) throw new IOException("project declaration exceeds size limit");
        return Files.readString(file);
    }
}
