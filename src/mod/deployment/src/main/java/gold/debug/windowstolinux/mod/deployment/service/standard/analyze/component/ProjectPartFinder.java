package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.ApplicationBundleChecker;

/**
 * Bounded static discovery; directories and declarations are evidence, never executable instructions. / 有界静态发现，目录和声明仅作为证据，不作为可执行指令。
 */
public final class ProjectPartFinder {
    /**
     * EXCLUDED.
     * <p>排除。
     */
    private static final Set<String> EXCLUDED = Set.of(".git", ".idea", ".ai-workspace", "node_modules", "target",
        "build", "dist", ".venv", "venv", "vendor", "test", "tests", "examples", "docs");

    /**
     * Discovers at most 64 component roots without executing project content or following links. / 最多发现 64 个组件根目录，不执行项目内容或跟随链接。
     *
     * @param selected explicitly selected item or state / 显式选择的项目或状态
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public List<DiscoveredProjectPart> discover(Path selected) throws IOException {
        Path root = selected.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
            throw new IOException("source must be a regular directory");
        var bundle = ApplicationBundleChecker.declaration(root);
        var declared = ApplicationBundleChecker.type(bundle);
        if (declared.isPresent()) {
            ApplicationBundleChecker.mainRoot(root, bundle);
            ApplicationBundleChecker.companions(root, bundle);
            return List.of(new DiscoveredProjectPart("app", Path.of(""), List.of(declared.orElseThrow())));
        }
        Map<Path, Set<DeploymentProjectKind>> candidates = new TreeMap<>();
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 12, new SimpleFileVisitor<>() {
            /**
             * Count.
             * <p>数量。
             */
            private int count;

            /**
             * Checks the directory boundary before visiting its contents.
             * <p>在访问目录内容前检查目录边界。
             *
             * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
             * @param attributes attributes / 属性
             * @return constructed or resolved file visit result / 构造或解析得到的文件Visit结果
             */
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return !directory.equals(root) && EXCLUDED.contains(directory.getFileName().toString())
                    ? FileVisitResult.SKIP_SUBTREE
                    : FileVisitResult.CONTINUE;
            }

            /**
             * Visits file.
             * <p>遍历文件。
             *
             * @param file file / 文件
             * @param attributes attributes / 属性
             * @return constructed or resolved file visit result / 构造或解析得到的文件Visit结果
             * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
             */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (++count > 100_000 || candidates.size() > 64)
                    throw new IOException("source discovery limit exceeded");
                if (Files.isSymbolicLink(file))
                    throw new IOException("source contains a symbolic link");
                String name = file.getFileName().toString();
                Set<DeploymentProjectKind> types = classify(name, file);
                if (!types.isEmpty())
                    candidates.computeIfAbsent(file.getParent(), ignored -> new LinkedHashSet<>()).addAll(types);
                return FileVisitResult.CONTINUE;
            }
        });
        Set<Path> nestedStatic = new HashSet<>();
        candidates.forEach((directory, types) -> {
            if (types.equals(Set.of(DeploymentProjectKind.STATIC_SITE)) && candidates.keySet().stream()
                .anyMatch(parent -> !parent.equals(directory) && directory.startsWith(parent)))
                nestedStatic.add(directory);
        });
        nestedStatic.forEach(candidates::remove);
        List<DiscoveredProjectPart> result = new ArrayList<>();
        for (var entry : candidates.entrySet()) {
            Path relative = root.relativize(entry.getKey());
            String path = relative.toString().replace('\\', '/');
            String id = path.isEmpty() ? "app" : path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
            if (id.length() > 50)
                id = id.substring(0, 41) + "-" + Integer.toUnsignedString(path.hashCode(), 16);
            result.add(new DiscoveredProjectPart(id, relative, List.copyOf(entry.getValue())));
        }
        if (result.stream().map(DiscoveredProjectPart::id).distinct().count() != result.size())
            throw new IOException("component names collide after normalization; rename their source directories");
        if (result.isEmpty())
            result.add(new DiscoveredProjectPart("app", Path.of(""),
                Arrays.stream(DeploymentProjectKind.values()).filter(DeploymentProjectKind::deployable).toList()));
        return List.copyOf(result);
    }

    /**
     * Classifies recognized project marker files using bounded static metadata.
     * <p>使用有界静态元数据对已识别项目标记文件分类。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param file file / 文件
     * @return constructed or resolved set / 构造或解析得到的集合
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static Set<DeploymentProjectKind> classify(String name, Path file) throws IOException {
        return switch (name) {
            case "pom.xml", "build.gradle", "build.gradle.kts" -> {
                String declared = text(file);
                if (declared.matches("(?s).*<packaging>\\s*pom\\s*</packaging>.*"))
                    yield Set.of();
                if (declared.contains("spring-boot") || declared.contains("org.springframework.boot"))
                    yield Set.of(DeploymentProjectKind.SPRING_BOOT);
                if (name.equals("build.gradle.kts")
                    && declared.matches("(?s).*kotlin\\s*\\(\\s*[\"']jvm[\"']\\s*\\).*"))
                    yield Set.of(DeploymentProjectKind.KOTLIN_SERVICE);
                yield Set.of(DeploymentProjectKind.JAVA_JAR);
            }
            case "package.json" -> {
                String value = text(file);
                if (value.contains("\"workspaces\"") && !value.contains("\"start\"") && !value.contains("\"build\""))
                    yield Set.of();
                boolean web = value.contains("\"vite\"") || value.contains("\"vue-cli-service\"")
                    || value.contains("\"react-scripts\"");
                yield Set.of(web ? DeploymentProjectKind.STATIC_SITE : DeploymentProjectKind.NODE_SERVICE);
            }
            case "pyproject.toml", "Pipfile" -> Set.of(DeploymentProjectKind.PYTHON_SERVICE);
            case "Dockerfile" -> Set.of(DeploymentProjectKind.DOCKERFILE_CONTAINER);
            case "go.mod" -> Set.of(DeploymentProjectKind.GO_SERVICE);
            case "Cargo.toml" -> Set.of(DeploymentProjectKind.RUST_SERVICE);
            case "CMakeLists.txt" -> Set.of(DeploymentProjectKind.CMAKE_SERVICE);
            case "windowstolinux-java.properties" -> Set.of(DeploymentProjectKind.JAVA_SOURCE);
            case "windowstolinux-kotlin.properties" -> Set.of(DeploymentProjectKind.KOTLIN_SERVICE);
            case "windowstolinux-php.properties", "composer.json" -> Set.of(DeploymentProjectKind.PHP_SERVICE);
            case "windowstolinux-ruby.properties", "Gemfile" -> Set.of(DeploymentProjectKind.RUBY_SERVICE);
            case "index.html" -> file.getParent().getFileName().toString().equals("public")
                || Files.exists(file.getParent().resolve("package.json"))
                ? Set.of()
                : Set.of(DeploymentProjectKind.STATIC_SITE);
            default -> name.endsWith(".csproj") ? Set.of(DeploymentProjectKind.DOTNET_SERVICE) : Set.of();
        };
    }

    /**
     * Reads bounded source metadata text from a regular file.
     * <p>从常规文件读取有界源码元数据文本。
     *
     * @param file file / 文件
     * @return bounded source metadata text from a regular file / 从常规文件读取有界源码元数据文本
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static String text(Path file) throws IOException {
        if (Files.size(file) > 2 * 1024 * 1024)
            throw new IOException("project declaration exceeds size limit");
        return Files.readString(file);
    }
}
