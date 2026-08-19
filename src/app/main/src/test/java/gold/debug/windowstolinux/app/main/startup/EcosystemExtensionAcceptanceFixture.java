package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.shared.model.capability.EcosystemToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/** Copies reviewed repository fixtures and changes only runtime-bound acceptance values. / 复制经审阅的仓库夹具，且只修改运行时绑定的验收值。 */
final class EcosystemExtensionAcceptanceFixture {
    private EcosystemExtensionAcceptanceFixture() { }

    /** Creates a healthy or health-rejected source tree for one exact architecture. / 为一个精确架构创建健康或健康拒绝源码树。 */
    static Path create(Path parent, Architecture architecture, String applicationId, String runtimeVersion,
                       String toolVersion, String marker, boolean healthy) throws IOException {
        Path root = parent.resolve(applicationId);
        reset(root);
        String variant = architecture.pythonLocked() && runtimeVersion.equals("3.11")
                ? "phase3-ready-py311" : "phase3-ready";
        Path template = repositoryRoot().resolve(Path.of("test", architecture.fixturePath(),
                "http-service", variant));
        copy(template, root);
        int status = healthy ? 200 : 503;
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(file);
                String updated = content.replace("phase3-live-ok", marker)
                        .replace("STATUS_CODE = 200", "STATUS_CODE = " + status)
                        .replace("const status = 200", "const status = " + status)
                        .replace("http_response_code(200)", "http_response_code(" + status + ")")
                        .replace("status = 200", "status = " + status)
                        .replace("status_code = 200", "status_code = " + status);
                if (architecture.projectType() == DeploymentProjectType.NODE_SERVICE) {
                    updated = updated.replace(architecture.templateApplicationId(), applicationId)
                            .replace("\"node\": \"18\"", "\"node\": \"" + runtimeVersion + "\"");
                    if (architecture.toolType() == EcosystemToolType.PNPM) {
                        updated = updated.replace("pnpm@10.15.1", "pnpm@" + toolVersion);
                    } else if (architecture.toolType() == EcosystemToolType.YARN) {
                        updated = updated.replace("yarn@4.9.2", "yarn@" + toolVersion);
                    }
                } else if (architecture == Architecture.PYTHON_PIP && runtimeVersion.equals("3.11")) {
                    updated = updated.replace("==3.12.*", "==3.11.*");
                } else if (architecture == Architecture.KOTLIN_KOTLINC) {
                    updated = updated.replace("compilerVersion=2.0.21", "compilerVersion=" + runtimeVersion);
                } else if (architecture == Architecture.PHP_CLI) {
                    updated = updated.replace("phpVersion=8.3", "phpVersion=" + runtimeVersion);
                } else if (architecture == Architecture.RUBY_CLI) {
                    updated = updated.replace("rubyVersion=3.3", "rubyVersion=" + runtimeVersion);
                }
                if (!updated.equals(content)) Files.writeString(file, updated);
            }
        }
        return root;
    }

    private static void copy(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) throw new IOException("missing reviewed fixture: " + source);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void reset(Path root) throws IOException {
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        Files.createDirectories(root);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("test"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("WindowsToLinux repository root was not found");
    }

    /** Identifies every changed build architecture independently. / 独立标识每个发生变更的构建架构。 */
    enum Architecture {
        JAVA_JDK("java-jdk", "java/jdk", DeploymentProjectType.JAVA_SOURCE, DeploymentBuildToolType.JDK,
                EcosystemToolType.JAVAC, ""),
        NODE_NPM("node-npm", "node/npm", DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.NPM,
                EcosystemToolType.NPM, "phase3-node-npm"),
        NODE_PNPM("node-pnpm", "node/pnpm", DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.PNPM,
                EcosystemToolType.PNPM, "phase3-node-pnpm"),
        NODE_YARN("node-yarn", "node/yarn", DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.YARN,
                EcosystemToolType.YARN, "phase3-node-yarn"),
        PYTHON_PIP("python-pip", "python/pip", DeploymentProjectType.PYTHON_SERVICE,
                DeploymentBuildToolType.PIP_LOCKED, EcosystemToolType.PIP, ""),
        PYTHON_PIPENV("python-pipenv", "python/pipenv", DeploymentProjectType.PYTHON_SERVICE,
                DeploymentBuildToolType.PIPENV_LOCKED, EcosystemToolType.PIPENV, ""),
        PYTHON_POETRY("python-poetry", "python/poetry", DeploymentProjectType.PYTHON_SERVICE,
                DeploymentBuildToolType.POETRY_LOCKED, EcosystemToolType.POETRY, ""),
        PYTHON_UV("python-uv", "python/uv", DeploymentProjectType.PYTHON_SERVICE,
                DeploymentBuildToolType.UV_LOCKED, EcosystemToolType.UV, ""),
        KOTLIN_KOTLINC("kotlin-kotlinc", "kotlin/kotlinc", DeploymentProjectType.KOTLIN_SERVICE,
                DeploymentBuildToolType.KOTLINC, EcosystemToolType.KOTLINC, ""),
        PHP_CLI("php-cli", "php/phpcli", DeploymentProjectType.PHP_SERVICE,
                DeploymentBuildToolType.PHP_CLI, EcosystemToolType.PHP, ""),
        RUBY_CLI("ruby-cli", "ruby/rubycli", DeploymentProjectType.RUBY_SERVICE,
                DeploymentBuildToolType.RUBY_CLI, EcosystemToolType.RUBY, ""),
        CMAKE("cmake", "c/cmake", DeploymentProjectType.CMAKE_SERVICE, DeploymentBuildToolType.CMAKE,
                EcosystemToolType.CMAKE, "");

        private final String key;
        private final String fixturePath;
        private final DeploymentProjectType projectType;
        private final DeploymentBuildToolType buildTool;
        private final EcosystemToolType toolType;
        private final String templateApplicationId;

        Architecture(String key, String fixturePath, DeploymentProjectType projectType,
                     DeploymentBuildToolType buildTool, EcosystemToolType toolType, String templateApplicationId) {
            this.key = key;
            this.fixturePath = fixturePath;
            this.projectType = projectType;
            this.buildTool = buildTool;
            this.toolType = toolType;
            this.templateApplicationId = templateApplicationId;
        }

        String key() { return key; }
        String fixturePath() { return fixturePath; }
        DeploymentProjectType projectType() { return projectType; }
        DeploymentBuildToolType buildTool() { return buildTool; }
        EcosystemToolType toolType() { return toolType; }
        String templateApplicationId() { return templateApplicationId; }
        boolean pythonLocked() {
            return this == PYTHON_PIPENV || this == PYTHON_POETRY || this == PYTHON_UV;
        }
    }
}
