package gold.debug.windowstolinux.app.main.startup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

/** Copies complete repository services for live ecosystem acceptance. / 为生态实机验收复制完整仓库服务。 */
final class EcosystemServiceAcceptanceFixture {
    private EcosystemServiceAcceptanceFixture() {
    }

    static Path create(Path parent, DeploymentProjectType projectType, String applicationId, String version,
            String marker, boolean healthy, Path gradleWrapperJar) throws IOException {
        String combination = switch (projectType) {
            case GO_SERVICE -> "go/gomodule/http-service";
            case RUST_SERVICE -> "rust/cargo/http-service";
            case DOTNET_SERVICE -> "csharp/dotnetsdk/http-service";
            case KOTLIN_SERVICE -> "kotlin/gradle/http-service";
            case PHP_SERVICE -> "php/composer/http-service";
            case RUBY_SERVICE -> "ruby/bundler/http-service";
            default -> throw new IllegalArgumentException("fixture requires an ecosystem service project type");
        };
        if (projectType == DeploymentProjectType.KOTLIN_SERVICE
                && (gradleWrapperJar == null || !Files.isRegularFile(gradleWrapperJar))) {
            throw new IOException("managed.gradle.wrapper.jar must identify a real verified Gradle wrapper JAR");
        }
        Path root = RepositoryServiceFixture.copy(parent, applicationId, combination, healthy);
        var replacements = new LinkedHashMap<String, String>();
        replacements.put("deployment-smoke-ok", marker);
        switch (projectType) {
            case GO_SERVICE -> replacements.put("go 1.24", "go " + version);
            case RUST_SERVICE -> {
                replacements.put("http_fixture", "w2l_rust");
                replacements.put("channel = \"1.89.0\"", "channel = \"" + version + "\"");
            }
            case DOTNET_SERVICE -> {
                String major = version.substring(0, version.indexOf('.'));
                replacements.put("8.0.408", version);
                replacements.put("net8.0", "net" + major + ".0");
                Files.move(root.resolve("HttpFixture.csproj"), root.resolve("W2lDotnet.csproj"));
            }
            case KOTLIN_SERVICE -> {
                replacements.put("fixture-http-service", applicationId);
                Files.copy(gradleWrapperJar, root.resolve("gradle/wrapper/gradle-wrapper.jar"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            case PHP_SERVICE -> replacements.put("\"8.3\"", "\"" + version + "\"");
            case RUBY_SERVICE -> Files.writeString(root.resolve(".ruby-version"), version + "\n");
            default -> throw new IllegalStateException("unsupported fixture");
        }
        RepositoryServiceFixture.replaceText(root, replacements);
        return root;
    }
}
