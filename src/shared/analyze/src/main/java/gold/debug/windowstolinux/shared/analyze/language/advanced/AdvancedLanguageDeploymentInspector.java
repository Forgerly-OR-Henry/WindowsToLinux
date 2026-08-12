package gold.debug.windowstolinux.shared.analyze.language.advanced;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentTypeInspection;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedProjectMetadata;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.AdvancedRuntimeKind;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSuggestion;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Statically validates one of the six fixed advanced experimental project shapes.
 *
 * <p>静态验证六种固定高级试验项目形态之一。
 */
public final class AdvancedLanguageDeploymentInspector implements DeploymentTypeInspector {
    private static final Pattern GO_VERSION = Pattern.compile("(?m)^go\\s+(1\\.[0-9]+)(?:\\.[0-9]+)?\\s*$");
    private static final Pattern RUST_VERSION = Pattern.compile(
            "(?:channel\\s*=\\s*[\"']|^)(1\\.[0-9]+(?:\\.[0-9]+)?)[\"']?", Pattern.MULTILINE);
    private static final Pattern DOTNET_VERSION = Pattern.compile(
            "[\"']version[\"']\\s*:\\s*[\"']((?:8|9)\\.0(?:\\.[0-9]+)?)[\"']");
    private static final Pattern DOTNET_WEB_SDK = Pattern.compile(
            "<Project\\s+Sdk\\s*=\\s*[\"']Microsoft\\.NET\\.Sdk\\.Web[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern KOTLIN_MAIN = Pattern.compile(
            "mainClass(?:\\.set)?\\s*\\(?[\"']([A-Za-z_$][A-Za-z0-9_$.]{0,255})[\"']\\)?");
    private static final Pattern KOTLIN_APPLICATION_PLUGIN = Pattern.compile(
            "(?s)plugins\\s*\\{[^}]*\\bapplication\\b");
    private static final Pattern KOTLIN_DEPENDENCY_LOCKING = Pattern.compile(
            "(?s)dependencyLocking\\s*\\{[^}]*lockAllConfigurations\\s*\\(\\s*\\)");
    private static final Pattern KOTLIN_JAVA_21 = Pattern.compile(
            "(?:JavaLanguageVersion\\.of\\(\\s*21\\s*\\)|jvmToolchain\\(\\s*21\\s*\\))");
    private static final Pattern PHP_VERSION = Pattern.compile(
            "(?s)[\"']platform[\"']\\s*:\\s*\\{[^}]*[\"']php[\"']\\s*:\\s*[\"']((?:8)\\.(?:2|3|4))[\"']");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final AdvancedRuntimeKind kind;

    /** Creates a bounded inspector for exactly one advanced runtime kind. / 为恰好一个高级运行时种类创建有界检查器。 */
    public AdvancedLanguageDeploymentInspector(AdvancedRuntimeKind kind) {
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
    }

    @Override public DeploymentProjectType projectType() {
        return kind.projectType();
    }

    @Override
    public DeploymentTypeInspection inspect(Path root, SourceInspection source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        Shape shape = inspectShape(root);
        List<LocalizedMessage> missing = new ArrayList<>();
        shape.missingFiles().forEach(file -> missing.add(LocalizedMessage.of(
                "analysis.advanced.missingFile", "file", file)));
        if (shape.version() == null) {
            missing.add(LocalizedMessage.of("analysis.advanced.missingVersion"));
        }
        if (shape.artifactName() == null) {
            missing.add(LocalizedMessage.of("analysis.advanced.missingArtifact"));
        }
        if (shape.entrypoint() == null) {
            missing.add(LocalizedMessage.of("analysis.advanced.missingEntrypoint"));
        }
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, BoundedProjectMetadata.rootApplicationId(root),
                projectType(), buildTool(), languageFacts, List.of(BoundedProjectMetadata.evidence(
                "analysis.advanced.metadata", shape.primaryMetadata(), "analysis.deployment.evidence.detected")),
                List.of(), missing);
        Map<DeploymentRuntimeSuggestion.RuntimeInput, String> values =
                new EnumMap<>(DeploymentRuntimeSuggestion.RuntimeInput.class);
        if (shape.version() != null) {
            values.put(DeploymentRuntimeSuggestion.RuntimeInput.ADVANCED_VERSION, shape.version());
        }
        if (shape.artifactName() != null) {
            values.put(DeploymentRuntimeSuggestion.RuntimeInput.ADVANCED_ARTIFACT, shape.artifactName());
        }
        if (shape.entrypoint() != null) {
            values.put(DeploymentRuntimeSuggestion.RuntimeInput.ADVANCED_ENTRYPOINT, shape.entrypoint());
        }
        List<LocalizedMessage> required = new ArrayList<>();
        required.add(LocalizedMessage.of("analysis.deployment.runtime.health"));
        if (kind.requiresServicePort()) {
            required.add(LocalizedMessage.of("analysis.advanced.servicePort"));
        }
        if (shape.version() == null) required.add(LocalizedMessage.of("analysis.advanced.missingVersion"));
        if (shape.artifactName() == null) required.add(LocalizedMessage.of("analysis.advanced.missingArtifact"));
        if (shape.entrypoint() == null) required.add(LocalizedMessage.of("analysis.advanced.missingEntrypoint"));
        return new DeploymentTypeInspection(facts, new DeploymentRuntimeSuggestion(projectType(), values, Optional.empty(),
                Map.of(), List.of(), facts.evidence(), required));
    }

    private Shape inspectShape(Path root) throws IOException {
        return switch (kind) {
            case GO -> {
                List<String> missing = missing(root, "go.mod", "go.sum", "main.go");
                String text = readIfPresent(root.resolve("go.mod"));
                yield new Shape("go.mod", match(text, GO_VERSION), "w2l-app", present(root, "main.go") ? "main.go" : null,
                        missing);
            }
            case RUST -> {
                List<String> missing = missing(root, "Cargo.toml", "Cargo.lock", "src/main.rs", "rust-toolchain.toml");
                String cargo = readIfPresent(root.resolve("Cargo.toml"));
                String toolchain = readIfPresent(root.resolve("rust-toolchain.toml"));
                String name = match(cargo, Pattern.compile(
                        "(?m)^name\\s*=\\s*[\"']([A-Za-z0-9][A-Za-z0-9._-]{0,127})[\"']"));
                yield new Shape("Cargo.toml", match(toolchain, RUST_VERSION), name,
                        present(root, "src/main.rs") ? "src/main.rs" : null, missing);
            }
            case DOTNET -> {
                List<String> missing = missing(root, "global.json", "packages.lock.json");
                List<Path> projects;
                try (var stream = java.nio.file.Files.list(root)) {
                    projects = stream.filter(path -> path.getFileName().toString().endsWith(".csproj"))
                            .filter(BoundedProjectMetadata::regular).limit(2).toList();
                }
                if (projects.size() != 1) missing = append(missing, "exactly-one-root-csproj");
                String artifact = projects.size() == 1
                        ? projects.getFirst().getFileName().toString().replaceFirst("\\.csproj$", "") : null;
                if (projects.size() == 1
                        && !DOTNET_WEB_SDK.matcher(readIfPresent(projects.getFirst())).find()) {
                    missing = append(missing, "Microsoft.NET.Sdk.Web");
                }
                if (!present(root, "Program.cs")) missing = append(missing, "Program.cs");
                yield new Shape("global.json", match(readIfPresent(root.resolve("global.json")), DOTNET_VERSION), artifact,
                        artifact == null || !present(root, "Program.cs") ? null : artifact + ".dll", missing);
            }
            case KOTLIN -> {
                List<String> missing = missing(root, "build.gradle.kts", "settings.gradle.kts", "gradlew",
                        "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties", "gradle.lockfile");
                String build = readIfPresent(root.resolve("build.gradle.kts"));
                if (!KOTLIN_APPLICATION_PLUGIN.matcher(build).find()) missing = append(missing, "application-plugin");
                if (!KOTLIN_DEPENDENCY_LOCKING.matcher(build).find()) missing = append(missing, "dependency-locking");
                if (!KOTLIN_JAVA_21.matcher(build).find()) missing = append(missing, "JVM-toolchain-21");
                String main = match(build, KOTLIN_MAIN);
                yield new Shape("build.gradle.kts", "21", BoundedProjectMetadata.rootApplicationId(root), main, missing);
            }
            case PHP -> {
                List<String> missing = missing(root, "composer.json", "composer.lock", "public/index.php");
                String composer = readIfPresent(root.resolve("composer.json"));
                yield new Shape("composer.json", match(composer, PHP_VERSION), "public",
                        present(root, "public/index.php") ? "public/index.php" : null, missing);
            }
            case RUBY -> {
                List<String> missing = missing(root, "Gemfile", "Gemfile.lock", ".ruby-version", "config.ru");
                String version = readIfPresent(root.resolve(".ruby-version")).trim();
                if (!kind.acceptsVersion(version)) version = null;
                yield new Shape(".ruby-version", version, "bundle",
                        present(root, "config.ru") ? "config.ru" : null, missing);
            }
        };
    }

    private DeploymentBuildTool buildTool() {
        return switch (kind) {
            case GO -> DeploymentBuildTool.GO_MODULE;
            case RUST -> DeploymentBuildTool.CARGO_LOCKED;
            case DOTNET -> DeploymentBuildTool.DOTNET_LOCKED;
            case KOTLIN -> DeploymentBuildTool.GRADLE_KOTLIN_WRAPPER;
            case PHP -> DeploymentBuildTool.COMPOSER_LOCKED;
            case RUBY -> DeploymentBuildTool.BUNDLER_LOCKED;
        };
    }

    private static String match(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return null;
        String value = matcher.group(1);
        return value != null && value.length() <= 255 ? value : null;
    }

    private static List<String> missing(Path root, String... files) {
        List<String> missing = new ArrayList<>();
        for (String file : files) if (!present(root, file)) missing.add(file);
        return List.copyOf(missing);
    }

    private static boolean present(Path root, String relative) {
        return BoundedProjectMetadata.regular(root.resolve(relative));
    }

    private static String readIfPresent(Path path) throws IOException {
        return BoundedProjectMetadata.regular(path) ? BoundedProjectMetadata.read(path) : "";
    }

    private static List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    private record Shape(String primaryMetadata, String version, String artifactName, String entrypoint,
                         List<String> missingFiles) {
        private Shape {
            if (artifactName != null && !SAFE_NAME.matcher(artifactName).matches()) artifactName = null;
            missingFiles = List.copyOf(missingFiles);
        }
    }
}
