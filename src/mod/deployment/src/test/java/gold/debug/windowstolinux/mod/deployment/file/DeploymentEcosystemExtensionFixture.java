package gold.debug.windowstolinux.mod.deployment.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentEcosystemToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;

/** Copies reviewed repository fixtures and changes only runtime-bound acceptance values. / 复制经审阅的仓库夹具，且只修改运行时绑定的验收值。 */
final class DeploymentEcosystemExtensionFixture {
    private DeploymentEcosystemExtensionFixture() {
    }

    /** Creates a healthy or health-rejected source tree for one exact architecture. / 为一个精确架构创建健康或健康拒绝源码树。 */
    static Path create(Path parent, ArchitectureType architecture, String applicationId, String runtimeVersion,
                       String toolVersion, String marker, boolean healthy) throws IOException {
        Path root = DeploymentStoreServiceFixture.copy(parent, applicationId, architecture.fixturePath() + "/http-service",
            healthy);
        if (architecture.pythonLocked() && runtimeVersion.equals("3.11")) {
            String tool = architecture.fixturePath().substring("python/".length());
            for (String name : switch (architecture) {
                case PYTHON_PIPENV -> new String[]{"pyproject.toml", "Pipfile", "Pipfile.lock"};
                case PYTHON_POETRY -> new String[]{"pyproject.toml", "poetry.lock"};
                case PYTHON_UV -> new String[]{"pyproject.toml", "uv.lock"};
                default -> throw new IllegalStateException("not a locked Python architecture");
            }) {
                try (var input = DeploymentEcosystemExtensionFixture.class
                    .getResourceAsStream("/fixtures/python311/" + tool + "/" + name)) {
                    if (input == null)
                        throw new IOException("missing Python 3.11 fixture metadata: " + tool + "/" + name);
                    Files.copy(input, root.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        var replacements = new java.util.LinkedHashMap<String, String>();
        replacements.put("deployment-smoke-ok", marker);
        if (architecture.projectType() == DeploymentProjectKind.NODE_SERVICE) {
            replacements.put(architecture.templateApplicationId(), applicationId);
            replacements.put("\"node\": \"18\"", "\"node\": \"" + runtimeVersion + "\"");
            if (architecture.toolType() == DeploymentEcosystemToolType.PNPM)
                replacements.put("pnpm@10.15.1", "pnpm@" + toolVersion);
            if (architecture.toolType() == DeploymentEcosystemToolType.YARN)
                replacements.put("yarn@4.9.2", "yarn@" + toolVersion);
        } else if (architecture.projectType() == DeploymentProjectKind.PYTHON_SERVICE) {
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
        DeploymentStoreServiceFixture.replaceText(root, replacements);
        return root;
    }

    /** Identifies every changed build architecture independently. / 独立标识每个发生变更的构建架构。 */
    enum ArchitectureType {
        JAVA_JDK("java-jdk", "java/jdk", DeploymentProjectKind.JAVA_SOURCE, DeploymentBuildToolType.JDK,
            DeploymentEcosystemToolType.JAVAC, ""), NODE_NPM("node-npm", "node/npm", DeploymentProjectKind.NODE_SERVICE,
            DeploymentBuildToolType.NPM, DeploymentEcosystemToolType.NPM, "http-service-npm"), NODE_PNPM("node-pnpm",
            "node/pnpm", DeploymentProjectKind.NODE_SERVICE, DeploymentBuildToolType.PNPM,
            DeploymentEcosystemToolType.PNPM, "http-service-pnpm"), NODE_YARN("node-yarn", "node/yarn",
            DeploymentProjectKind.NODE_SERVICE, DeploymentBuildToolType.YARN,
            DeploymentEcosystemToolType.YARN, "http-service-yarn"), PYTHON_PIP("python-pip",
            "python/pip", DeploymentProjectKind.PYTHON_SERVICE,
            DeploymentBuildToolType.PIP_LOCKED, DeploymentEcosystemToolType.PIP,
            ""), PYTHON_PIPENV("python-pipenv", "python/pipenv",
            DeploymentProjectKind.PYTHON_SERVICE,
            DeploymentBuildToolType.PIPENV_LOCKED, DeploymentEcosystemToolType.PIPENV,
            ""), PYTHON_POETRY("python-poetry", "python/poetry",
            DeploymentProjectKind.PYTHON_SERVICE,
            DeploymentBuildToolType.POETRY_LOCKED,
            DeploymentEcosystemToolType.POETRY, ""), PYTHON_UV("python-uv",
            "python/uv",
            DeploymentProjectKind.PYTHON_SERVICE,
            DeploymentBuildToolType.UV_LOCKED,
            DeploymentEcosystemToolType.UV, ""), KOTLIN_KOTLINC(
            "kotlin-kotlinc", "kotlin/kotlinc",
            DeploymentProjectKind.KOTLIN_SERVICE,
            DeploymentBuildToolType.KOTLINC,
            DeploymentEcosystemToolType.KOTLINC,
            ""), PHP_CLI("php-cli", "php/phpcli",
            DeploymentProjectKind.PHP_SERVICE,
            DeploymentBuildToolType.PHP_CLI,
            DeploymentEcosystemToolType.PHP,
            ""), RUBY_CLI("ruby-cli",
            "ruby/rubycli",
            DeploymentProjectKind.RUBY_SERVICE,
            DeploymentBuildToolType.RUBY_CLI,
            DeploymentEcosystemToolType.RUBY,
            ""), CMAKE("cmake",
            "c/cmake",
            DeploymentProjectKind.CMAKE_SERVICE,
            DeploymentBuildToolType.CMAKE,
            DeploymentEcosystemToolType.CMAKE,
            "");

        private final String key;

        private final String fixturePath;

        private final DeploymentProjectKind projectType;

        private final DeploymentBuildToolType buildTool;

        private final DeploymentEcosystemToolType toolType;

        private final String templateApplicationId;

        ArchitectureType(String key, String fixturePath, DeploymentProjectKind projectType,
                         DeploymentBuildToolType buildTool, DeploymentEcosystemToolType toolType, String templateApplicationId) {
            this.key = key;
            this.fixturePath = fixturePath;
            this.projectType = projectType;
            this.buildTool = buildTool;
            this.toolType = toolType;
            this.templateApplicationId = templateApplicationId;
        }

        String key() {
            return key;
        }

        String fixturePath() {
            return fixturePath;
        }

        DeploymentProjectKind projectType() {
            return projectType;
        }

        DeploymentBuildToolType buildTool() {
            return buildTool;
        }

        DeploymentEcosystemToolType toolType() {
            return toolType;
        }

        String templateApplicationId() {
            return templateApplicationId;
        }

        boolean pythonLocked() {
            return this == PYTHON_PIPENV || this == PYTHON_POETRY || this == PYTHON_UV;
        }
    }
}
