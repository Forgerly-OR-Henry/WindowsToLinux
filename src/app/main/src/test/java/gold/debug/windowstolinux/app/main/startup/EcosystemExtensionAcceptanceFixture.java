package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.shared.model.capability.EcosystemToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Copies reviewed repository fixtures and changes only runtime-bound acceptance values. / 复制经审阅的仓库夹具，且只修改运行时绑定的验收值。 */
final class EcosystemExtensionAcceptanceFixture {
    private EcosystemExtensionAcceptanceFixture() { }

    /** Creates a healthy or health-rejected source tree for one exact architecture. / 为一个精确架构创建健康或健康拒绝源码树。 */
    static Path create(Path parent, ArchitectureType architecture, String applicationId, String runtimeVersion,
                       String toolVersion, String marker, boolean healthy) throws IOException {
        Path root = RepositoryServiceFixture.copy(parent, applicationId,
                architecture.fixturePath() + "/http-service", healthy);
        if (architecture.pythonLocked() && runtimeVersion.equals("3.11")) {
            String tool = architecture.fixturePath().substring("python/".length());
            for (String name : switch (architecture) {
                case PYTHON_PIPENV -> new String[]{"pyproject.toml", "Pipfile", "Pipfile.lock"};
                case PYTHON_POETRY -> new String[]{"pyproject.toml", "poetry.lock"};
                case PYTHON_UV -> new String[]{"pyproject.toml", "uv.lock"};
                default -> throw new IllegalStateException("not a locked Python architecture");
            }) {
                try (var input = EcosystemExtensionAcceptanceFixture.class.getResourceAsStream(
                        "/fixtures/python311/" + tool + "/" + name)) {
                    if (input == null) throw new IOException("missing Python 3.11 fixture metadata: " + tool + "/" + name);
                    Files.copy(input, root.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        var replacements = new java.util.LinkedHashMap<String, String>();
        replacements.put("deployment-smoke-ok", marker);
        if (architecture.projectType() == DeploymentProjectType.NODE_SERVICE) {
            replacements.put(architecture.templateApplicationId(), applicationId);
            replacements.put("\"node\": \"18\"", "\"node\": \"" + runtimeVersion + "\"");
            if (architecture.toolType() == EcosystemToolType.PNPM) replacements.put("pnpm@10.15.1", "pnpm@" + toolVersion);
            if (architecture.toolType() == EcosystemToolType.YARN) replacements.put("yarn@4.9.2", "yarn@" + toolVersion);
        } else if (architecture.projectType() == DeploymentProjectType.PYTHON_SERVICE) {
            replacements.put("http-service-fixture", applicationId);
            if (architecture == ArchitectureType.PYTHON_PIP && runtimeVersion.equals("3.11")) {
                replacements.put("==3.12.*", "==3.11.*");
            }
        } else if (architecture == ArchitectureType.KOTLIN_KOTLINC) {
            replacements.put("compilerVersion=2.0.21", "compilerVersion=" + runtimeVersion);
        } else if (architecture == ArchitectureType.PHP_CLI) {
            replacements.put("phpVersion=8.3", "phpVersion=" + runtimeVersion);
        } else if (architecture == ArchitectureType.RUBY_CLI) {
            replacements.put("rubyVersion=3.3", "rubyVersion=" + runtimeVersion);
        }
        RepositoryServiceFixture.replaceText(root, replacements);
        return root;
    }

    /** Identifies every changed build architecture independently. / 独立标识每个发生变更的构建架构。 */
    enum ArchitectureType {
        JAVA_JDK("java-jdk", "java/jdk", DeploymentProjectType.JAVA_SOURCE, DeploymentBuildToolType.JDK,
                EcosystemToolType.JAVAC, ""),
        NODE_NPM("node-npm", "node/npm", DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.NPM,
                EcosystemToolType.NPM, "http-service-npm"),
        NODE_PNPM("node-pnpm", "node/pnpm", DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.PNPM,
                EcosystemToolType.PNPM, "http-service-pnpm"),
        NODE_YARN("node-yarn", "node/yarn", DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.YARN,
                EcosystemToolType.YARN, "http-service-yarn"),
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

        ArchitectureType(String key, String fixturePath, DeploymentProjectType projectType,
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
