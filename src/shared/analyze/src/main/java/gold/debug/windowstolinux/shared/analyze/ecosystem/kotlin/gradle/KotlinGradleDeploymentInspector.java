package gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin.gradle;

import gold.debug.windowstolinux.shared.analyze.service.ServiceInspectionAssembler;
import gold.debug.windowstolinux.shared.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.service.ServiceProjectFacts;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Inspects one locked Kotlin Gradle application without executing Gradle. / 在不执行 Gradle 的情况下检查一个锁定的 Kotlin Gradle 应用。 */
public final class KotlinGradleDeploymentInspector implements DeploymentTypeInspector {
    private static final Pattern KOTLIN_MAIN = Pattern.compile(
            "mainClass(?:\\.set)?\\s*\\(?[\"']([A-Za-z_$][A-Za-z0-9_$.]{0,255})[\"']\\)?");
    private static final Pattern KOTLIN_PLUGIN_VERSION = Pattern.compile(
            "kotlin\\s*\\(\\s*[\"']jvm[\"']\\s*\\)\\s*version\\s*[\"']((?:1\\.9|2\\.[0-9]+)\\.[0-9]+)[\"']");
    private static final Pattern KOTLIN_APPLICATION_PLUGIN = Pattern.compile(
            "(?s)plugins\\s*\\{[^}]*\\bapplication\\b");
    private static final Pattern KOTLIN_DEPENDENCY_LOCKING = Pattern.compile(
            "(?s)dependencyLocking\\s*\\{[^}]*lockAllConfigurations\\s*\\(\\s*\\)");
    private static final Pattern KOTLIN_JAVA_21 = Pattern.compile(
            "(?:JavaLanguageVersion\\.of\\(\\s*21\\s*\\)|jvmToolchain\\(\\s*21\\s*\\))");

    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.KOTLIN_SERVICE;
    }

    @Override
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        List<String> missing = ServiceMetadataInspector.missing(root, "build.gradle.kts", "settings.gradle.kts",
                "gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
                "gradle.lockfile");
        String build = ServiceMetadataInspector.readIfPresent(root.resolve("build.gradle.kts"));
        if (!KOTLIN_APPLICATION_PLUGIN.matcher(build).find()) {
            missing = ServiceMetadataInspector.append(missing, "application-plugin");
        }
        if (!KOTLIN_DEPENDENCY_LOCKING.matcher(build).find()) {
            missing = ServiceMetadataInspector.append(missing, "dependency-locking");
        }
        if (!KOTLIN_JAVA_21.matcher(build).find()) {
            missing = ServiceMetadataInspector.append(missing, "JVM-toolchain-21");
        }
        String compilerVersion = ServiceMetadataInspector.match(build, KOTLIN_PLUGIN_VERSION);
        if (compilerVersion == null) {
            missing = ServiceMetadataInspector.append(missing, "exact-kotlin-jvm-plugin-version");
        }
        KotlinGradleFacts facts = new KotlinGradleFacts(compilerVersion,
                ServiceMetadataInspector.match(build, KOTLIN_MAIN), missing);
        ServiceProjectFacts shape = new ServiceProjectFacts("build.gradle.kts", facts.compilerVersion(),
                ProjectIdentityResolver.rootApplicationId(root), facts.mainClass(), facts.missingFiles());
        return ServiceInspectionAssembler.assemble(root, projectType(), DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER,
                languageFacts, shape, false);
    }
}
