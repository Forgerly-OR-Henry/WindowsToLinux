package gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin.gradle;

import gold.debug.windowstolinux.shared.analyze.service.ServiceInspectionAssembler;
import gold.debug.windowstolinux.shared.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.service.ServiceProjectFacts;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeInspector;
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

/**
 * Inspects one locked Kotlin Gradle application without executing Gradle. / 在不执行 Gradle 的情况下检查一个锁定的 Kotlin Gradle 应用。
 */
public final class KotlinGradleDeploymentInspector implements DeploymentTypeInspector {
    /**
     * Pattern recognizing KOTLIN MAIN.
     * <p>用于识别KOTLIN主的匹配模式。
     */
    private static final Pattern KOTLIN_MAIN = Pattern.compile(
            "mainClass(?:\\.set)?\\s*\\(?[\"']([A-Za-z_$][A-Za-z0-9_$.]{0,255})[\"']\\)?");
    /**
     * Pattern recognizing KOTLIN PLUGIN VERSION.
     * <p>用于识别KOTLINPLUGIN版本的匹配模式。
     */
    private static final Pattern KOTLIN_PLUGIN_VERSION = Pattern.compile(
            "kotlin\\s*\\(\\s*[\"']jvm[\"']\\s*\\)\\s*version\\s*[\"']([0-9]+(?:\\.[0-9]+){1,2}(?:[-+][A-Za-z0-9._-]+)?)[\"']");
    /**
     * Pattern recognizing KOTLIN APPLICATION PLUGIN.
     * <p>用于识别KOTLIN应用PLUGIN的匹配模式。
     */
    private static final Pattern KOTLIN_APPLICATION_PLUGIN = Pattern.compile(
            "(?s)plugins\\s*\\{[^}]*\\bapplication\\b");
    /**
     * Pattern recognizing Kotlin dependency-locking configuration.
     * <p>用于识别Kotlin 依赖锁定配置的匹配模式。
     */
    private static final Pattern KOTLIN_DEPENDENCY_LOCKING = Pattern.compile(
            "(?s)dependencyLocking\\s*\\{[^}]*lockAllConfigurations\\s*\\(\\s*\\)");
    /**
     * Pattern recognizing KOTLIN JAVA TARGET.
     * <p>用于识别KOTLINJava目标的匹配模式。
     */
    private static final Pattern KOTLIN_JAVA_TARGET = Pattern.compile(
            "(?:JavaLanguageVersion\\.of\\(\\s*[0-9]+\\s*\\)|jvmToolchain\\(\\s*[0-9]+\\s*\\))");

    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.KOTLIN_SERVICE;
    }

    /**
     * Inspects deployment type assessment.
     * <p>检查部署类型评估。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param languageFacts language facts / 语言事实
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved deployment type assessment / 构造或解析得到的部署类型评估
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
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
        if (!KOTLIN_JAVA_TARGET.matcher(build).find()) {
            missing = ServiceMetadataInspector.append(missing, "JVM-toolchain-version");
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
