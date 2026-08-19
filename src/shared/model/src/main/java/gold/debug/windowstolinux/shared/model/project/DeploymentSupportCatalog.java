package gold.debug.windowstolinux.shared.model.project;

import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;

import java.time.LocalDate;
import java.util.List;

/** Provides the checked-in support claims used by every interface. / 提供每个界面共同使用的已检入支持声明。 */
public final class DeploymentSupportCatalog {
    private static final ValidatedDeploymentTarget UBUNTU_2404 = new ValidatedDeploymentTarget(
            LinuxDistroType.UBUNTU, "24.04", "x86_64", LocalDate.of(2026, 8, 12));

    private DeploymentSupportCatalog() {
    }

    /** Returns the exact current claim for a selected project type. / 返回选定项目类型的精确当前声明。 */
    public static DeploymentSupportProfile forType(DeploymentProjectType type) {
        return switch (type) {
            case SPRING_BOOT -> experimental(SourceLanguageType.JAVA, "Spring Boot executable JAR",
                    "support.limitation.runtimePending");
            case JAVA_JAR -> formal(SourceLanguageType.JAVA, "Java executable JAR");
            case NODE_SERVICE -> formal(SourceLanguageType.JAVASCRIPT, "Node.js lockfile service");
            case PYTHON_SERVICE -> formal(SourceLanguageType.PYTHON, "Python virtual-environment service");
            case STATIC_SITE -> formal(SourceLanguageType.HTML, "Managed static site");
            case DOCKERFILE_CONTAINER -> formal(SourceLanguageType.CONTAINERFILE, "Single Dockerfile container");
            case GO_SERVICE -> experimental(SourceLanguageType.GO, "Go module service",
                    "support.limitation.runtimePending");
            case RUST_SERVICE -> experimental(SourceLanguageType.RUST, "Rust Cargo service",
                    "support.limitation.runtimePending");
            case DOTNET_SERVICE -> experimental(SourceLanguageType.CSHARP, ".NET SDK service",
                    "support.limitation.runtimePending");
            case KOTLIN_SERVICE -> experimental(SourceLanguageType.KOTLIN, "Kotlin/JVM Gradle application",
                    "support.limitation.runtimePending");
            case PHP_SERVICE -> experimental(SourceLanguageType.PHP, "PHP Composer service",
                    "support.limitation.runtimePending");
            case RUBY_SERVICE -> experimental(SourceLanguageType.RUBY, "Ruby Rack service",
                    "support.limitation.runtimePending");
            case RECOGNITION_PREVIEW -> preview(SourceLanguageType.UNKNOWN);
        };
    }

    /** Returns a mutation-free recognition-only profile. / 返回禁止修改目标机的仅识别配置。 */
    public static DeploymentSupportProfile preview(SourceLanguageType language) {
        return new DeploymentSupportProfile(DeploymentSupportLevel.RECOGNITION_PREVIEW, language, "Unclassified project",
                List.of(), List.of(LocalizedMessage.of("support.limitation.previewOnly")));
    }

    /** Returns an honest result when no bounded marker identified a language. / 在没有有界标记识别语言时返回真实结果。 */
    public static DeploymentSupportProfile unrecognized() {
        return new DeploymentSupportProfile(DeploymentSupportLevel.UNRECOGNIZED, SourceLanguageType.UNKNOWN,
                "Unrecognized project", List.of(), List.of(LocalizedMessage.of("support.limitation.unrecognized")));
    }

    private static DeploymentSupportProfile experimental(SourceLanguageType language, String framework, String limitationKey) {
        return new DeploymentSupportProfile(DeploymentSupportLevel.EXPERIMENTAL_ADAPTER, language, framework,
                List.of(), List.of(LocalizedMessage.of(limitationKey)));
    }

    private static DeploymentSupportProfile formal(SourceLanguageType language, String framework) {
        return new DeploymentSupportProfile(DeploymentSupportLevel.FORMALLY_SUPPORTED, language, framework,
                List.of(UBUNTU_2404), List.of(LocalizedMessage.of("support.limitation.listedMatrixOnly")));
    }
}
