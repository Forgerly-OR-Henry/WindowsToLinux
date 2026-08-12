package gold.debug.windowstolinux.shared.analyze.framework.springboot;

import gold.debug.windowstolinux.shared.analyze.build.gradle.GradleProjectInspection;
import gold.debug.windowstolinux.shared.analyze.build.gradle.GradleProjectInspector;
import gold.debug.windowstolinux.shared.analyze.build.maven.MavenProjectInspection;
import gold.debug.windowstolinux.shared.analyze.build.maven.MavenProjectInspector;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentTypeInspection;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedProjectMetadata;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSuggestion;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Inspects one Maven or Gradle Spring Boot executable-JAR project without invoking its build.
 *
 * <p>在不调用构建的前提下检查一个 Maven 或 Gradle Spring Boot 可执行 JAR 项目。
 */
public final class SpringBootDeploymentInspector implements DeploymentTypeInspector {
    private static final Pattern BOOT_PLUGIN = Pattern.compile("(?i)(org\\.springframework\\.boot|spring-boot)");
    private static final Pattern MIGRATION = Pattern.compile("\\b(flyway|liquibase)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTOMATIC_SCHEMA_MUTATION = Pattern.compile(
            "(?im)(?:spring\\.jpa\\.hibernate\\.ddl-auto|hibernate\\.hbm2ddl\\.auto|ddl-auto)\\s*[:=]\\s*['\\\"]?"
                    + "(?:create|create-drop|update)['\\\"]?"
                    + "|spring\\.jpa\\.generate-ddl\\s*[:=]\\s*(?:true|yes)"
                    + "|spring\\.(?:sql\\.init\\.mode|datasource\\.initialization-mode)\\s*[:=]\\s*(?:always|embedded)");
    private static final Pattern EXTERNAL_CONFIG = Pattern.compile(
            "spring\\.config\\.(import|location|additional-location)|SPRING_CONFIG_(IMPORT|LOCATION|ADDITIONAL_LOCATION)"
                    + "|System\\.getenv\\s*\\(|spring\\.application\\.json", Pattern.CASE_INSENSITIVE);
    private static final Pattern APPLICATION_SECRET = Pattern.compile(
            "(?i)(password|secret|api[_-]?key|access[_-]?key|token)\\s*[:=]"
                    + "|@Value\\s*\\(\\s*\\\"?\\$\\{[^}]*?(password|secret|key|token)[^}]*}");
    private static final Pattern DISABLED_BOOT_JAR = Pattern.compile(
            "(?is)bootJar(?:\\s*\\{[^}]*enabled\\s*=\\s*false|\\.enabled\\s*=\\s*false)"
                    + "|<artifactId>\\s*spring-boot-maven-plugin\\s*</artifactId>.*?<skip>\\s*true\\s*</skip>");

    private final MavenProjectInspector maven = new MavenProjectInspector();
    private final GradleProjectInspector gradle = new GradleProjectInspector();

    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.SPRING_BOOT;
    }

    @Override
    public DeploymentTypeInspection inspect(Path root, SourceInspection source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        boolean hasMaven = BoundedProjectMetadata.regular(root.resolve("pom.xml"));
        boolean hasGradle = BoundedProjectMetadata.regular(root.resolve("build.gradle"))
                || BoundedProjectMetadata.regular(root.resolve("build.gradle.kts"));
        if (hasMaven == hasGradle) {
            rejections.add(rejection(hasMaven ? "SPRING_BOOT_BUILD_AMBIGUOUS" : "SPRING_BOOT_BUILD_MISSING",
                    hasMaven ? "analysis.deployment.rejection.springBootBuildAmbiguous"
                            : "analysis.deployment.rejection.springBootBuildMissing"));
            return null;
        }
        BuildInspection build = hasMaven ? inspectMaven(root, source, rejections) : inspectGradle(root, rejections);
        if (build == null) {
            return null;
        }
        inspectCommonPolicy(build.buildText(), source, rejections);
        if (!rejections.isEmpty()) {
            return null;
        }
        if (!build.applicationId().matches("[a-z0-9][a-z0-9-]{0,62}")) {
            rejections.add(rejection("APPLICATION_ID_INVALID", "analysis.rejection.applicationIdInvalid"));
            return null;
        }
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, build.applicationId(), projectType(),
                build.buildTool(), languageFacts, build.evidence(), List.of(), build.missingInformation());
        DeploymentRuntimeSuggestion suggestion = new DeploymentRuntimeSuggestion(projectType(), Map.of(), Optional.empty(),
                Map.of(), List.of(), List.of(), List.of(BoundedProjectMetadata.required("analysis.deployment.runtime.health")));
        return new DeploymentTypeInspection(facts, suggestion);
    }

    private BuildInspection inspectMaven(Path root, SourceInspection source, List<RejectionReason> rejections) {
        Optional<MavenProjectInspection> inspected = maven.inspect(root, rejections);
        if (inspected.isEmpty()) {
            return null;
        }
        MavenProjectInspection build = inspected.orElseThrow();
        if (!build.springBootPlugin()) {
            rejections.add(rejection("SPRING_BOOT_PLUGIN_MISSING", "analysis.rejection.bootPluginMissing"));
        }
        if (build.warPackaging()) {
            rejections.add(rejection("UNSUPPORTED_WAR", "analysis.rejection.warUnsupported"));
        }
        boolean wrapper = source.hasMavenWrapper()
                && BoundedProjectMetadata.regular(root.resolve(".mvn/wrapper/maven-wrapper.properties"));
        DeploymentBuildTool tool = wrapper ? DeploymentBuildTool.MAVEN_WRAPPER : DeploymentBuildTool.MAVEN;
        List<AnalysisEvidence> evidence = new ArrayList<>();
        evidence.add(BoundedProjectMetadata.evidence("analysis.evidence.mavenEntry", "pom.xml",
                "analysis.evidence.rootPomRead"));
        evidence.add(BoundedProjectMetadata.evidence("analysis.evidence.springBootPlugin", "pom.xml",
                build.springBootPlugin() ? "analysis.evidence.springBootPluginDetected" : "analysis.evidence.notDetected"));
        String wrapperSource = wrapper ? "mvnw + .mvn/wrapper/maven-wrapper.properties"
                : source.hasMavenWrapper() ? "mvnw" : source.hasWindowsMavenWrapper() ? "mvnw.cmd" : "source tree";
        String wrapperConclusion = wrapper ? "analysis.evidence.wrapper.usable"
                : source.hasMavenWrapper() ? "analysis.evidence.wrapper.configurationMissing"
                : source.hasWindowsMavenWrapper() ? "analysis.evidence.wrapper.windowsOnly"
                : "analysis.evidence.notDetected";
        evidence.add(BoundedProjectMetadata.evidence("analysis.evidence.mavenWrapper", wrapperSource, wrapperConclusion));
        return new BuildInspection(build.applicationName(), tool, build.pomText(), evidence, List.of());
    }

    private BuildInspection inspectGradle(Path root, List<RejectionReason> rejections) throws IOException {
        Optional<GradleProjectInspection> inspected = gradle.inspect(root, rejections);
        if (inspected.isEmpty()) {
            return null;
        }
        GradleProjectInspection build = inspected.orElseThrow();
        if (!BOOT_PLUGIN.matcher(build.text()).find()) {
            rejections.add(rejection("SPRING_BOOT_PLUGIN_MISSING",
                    "analysis.deployment.rejection.gradleBootPluginMissing"));
        }
        List<LocalizedMessage> missing = build.usableWrapper() ? List.of()
                : List.of(LocalizedMessage.of("analysis.deployment.missing.gradleWrapper"));
        return new BuildInspection(build.applicationId(), DeploymentBuildTool.GRADLE_WRAPPER, build.text(),
                List.of(BoundedProjectMetadata.evidence("analysis.deployment.evidence.gradleBuild",
                        build.script().getFileName().toString(), "analysis.deployment.evidence.detected")), missing);
    }

    private static void inspectCommonPolicy(String buildText, SourceInspection source,
                                            List<RejectionReason> rejections) {
        String scannedText = source.scannedText();
        if (MIGRATION.matcher(buildText + '\n' + scannedText).find()) {
            rejections.add(rejection("DATABASE_MIGRATION_DETECTED", "analysis.rejection.migrationDetected"));
        }
        if (source.hasDatabaseChangeScript() || AUTOMATIC_SCHEMA_MUTATION.matcher(scannedText).find()) {
            rejections.add(rejection("AUTOMATIC_SCHEMA_MUTATION_DETECTED",
                    "analysis.rejection.schemaMutationDetected"));
        }
        if (EXTERNAL_CONFIG.matcher(scannedText).find()) {
            rejections.add(rejection("EXTERNAL_CONFIGURATION_DETECTED", "analysis.rejection.externalConfig"));
        }
        if (APPLICATION_SECRET.matcher(scannedText).find()) {
            rejections.add(rejection("APPLICATION_SECRET_DETECTED", "analysis.rejection.applicationSecret"));
        }
        if (DISABLED_BOOT_JAR.matcher(buildText).find()) {
            rejections.add(rejection("SPRING_BOOT_EXECUTABLE_JAR_DISABLED",
                    "analysis.deployment.rejection.springBootExecutableJarDisabled"));
        }
    }

    private static RejectionReason rejection(String code, String key) {
        return new RejectionReason(code, LocalizedMessage.of(key), "deployment");
    }

    private record BuildInspection(String applicationId, DeploymentBuildTool buildTool, String buildText,
                                   List<AnalysisEvidence> evidence, List<LocalizedMessage> missingInformation) {
        private BuildInspection {
            evidence = List.copyOf(evidence);
            missingInformation = List.copyOf(missingInformation);
        }
    }
}
