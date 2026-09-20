package gold.debug.windowstolinux.shared.analyze.ecosystem.java;

import gold.debug.windowstolinux.shared.analyze.ecosystem.java.gradle.GradleBuildFacts;
import gold.debug.windowstolinux.shared.analyze.ecosystem.java.gradle.GradleBuildInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.java.maven.MavenBuildFacts;
import gold.debug.windowstolinux.shared.analyze.ecosystem.java.maven.MavenBuildInspector;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;

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
    private static final Pattern EXTERNAL_CONFIG = Pattern.compile(
            "spring\\.config\\.(import|location|additional-location)|SPRING_CONFIG_(IMPORT|LOCATION|ADDITIONAL_LOCATION)"
                    + "|spring\\.application\\.json", Pattern.CASE_INSENSITIVE);
    private static final Pattern APPLICATION_SECRET = Pattern.compile(
            "(?i)(password|secret|api[_-]?key|access[_-]?key|token)\\s*[:=]"
                    + "|@Value\\s*\\(\\s*\\\"?\\$\\{[^}]*?(password|secret|key|token)[^}]*}");
    private static final Pattern DISABLED_BOOT_JAR = Pattern.compile(
            "(?is)bootJar(?:\\s*\\{[^}]*enabled\\s*=\\s*false|\\.enabled\\s*=\\s*false)"
                    + "|<artifactId>\\s*spring-boot-maven-plugin\\s*</artifactId>.*?<skip>\\s*true\\s*</skip>");

    private final MavenBuildInspector maven = new MavenBuildInspector();
    private final GradleBuildInspector gradle = new GradleBuildInspector();

    /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.SPRING_BOOT;
    }

    /** Inspects source facts for this deployment type. / 检查此部署类型的源码事实。 */
    @Override
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        boolean hasMaven = BoundedMetadataInspector.regular(root.resolve("pom.xml"));
        boolean hasGradle = BoundedMetadataInspector.regular(root.resolve("build.gradle"))
                || BoundedMetadataInspector.regular(root.resolve("build.gradle.kts"));
        if (hasMaven == hasGradle) {
            rejections.add(rejection(hasMaven ? "SPRING_BOOT_BUILD_AMBIGUOUS" : "SPRING_BOOT_BUILD_MISSING",
                    hasMaven ? "analysis.deployment.rejection.springBootBuildAmbiguous"
                            : "analysis.deployment.rejection.springBootBuildMissing"));
            return null;
        }
        BuildInspection build = hasMaven ? inspectMaven(root, rejections) : inspectGradle(root, rejections);
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
        DeploymentRuntimeAssessment suggestion = new DeploymentRuntimeAssessment(projectType(), Map.of(), Optional.empty(),
                Map.of(), List.of(), List.of(), List.of(LocalizedMessage.of("analysis.deployment.runtime.health")));
        return new DeploymentTypeAssessment(facts, suggestion);
    }

    private BuildInspection inspectMaven(Path root, List<RejectionReason> rejections) {
        Optional<MavenBuildFacts> inspected = maven.inspect(root, rejections);
        if (inspected.isEmpty()) {
            return null;
        }
        MavenBuildFacts build = inspected.orElseThrow();
        if (!build.springBootPlugin()) {
            rejections.add(rejection("SPRING_BOOT_PLUGIN_MISSING", "analysis.rejection.bootPluginMissing"));
        }
        if (build.warPackaging()) {
            rejections.add(rejection("UNSUPPORTED_WAR", "analysis.rejection.warUnsupported"));
        }
        boolean hasMavenWrapper = BoundedMetadataInspector.regular(root.resolve("mvnw"));
        boolean hasWindowsMavenWrapper = BoundedMetadataInspector.regular(root.resolve("mvnw.cmd"));
        boolean wrapper = hasMavenWrapper
                && BoundedMetadataInspector.regular(root.resolve(".mvn/wrapper/maven-wrapper.properties"));
        DeploymentBuildToolType tool = wrapper ? DeploymentBuildToolType.MAVEN_WRAPPER : DeploymentBuildToolType.MAVEN;
        List<AnalysisEvidence> evidence = new ArrayList<>();
        evidence.add(evidence("analysis.evidence.mavenEntry", "pom.xml",
                "analysis.evidence.rootPomRead"));
        evidence.add(evidence("analysis.evidence.springBootPlugin", "pom.xml",
                build.springBootPlugin() ? "analysis.evidence.springBootPluginDetected" : "analysis.evidence.notDetected"));
        String wrapperSource = wrapper ? "mvnw + .mvn/wrapper/maven-wrapper.properties"
                : hasMavenWrapper ? "mvnw" : hasWindowsMavenWrapper ? "mvnw.cmd" : "source tree";
        String wrapperConclusion = wrapper ? "analysis.evidence.wrapper.usable"
                : hasMavenWrapper ? "analysis.evidence.wrapper.configurationMissing"
                : hasWindowsMavenWrapper ? "analysis.evidence.wrapper.windowsOnly"
                : "analysis.evidence.notDetected";
        evidence.add(evidence("analysis.evidence.mavenWrapper", wrapperSource, wrapperConclusion));
        return new BuildInspection(build.applicationName(), tool, build.pomText(), evidence, List.of());
    }

    private BuildInspection inspectGradle(Path root, List<RejectionReason> rejections) throws IOException {
        Optional<GradleBuildFacts> inspected = gradle.inspect(root, rejections);
        if (inspected.isEmpty()) {
            return null;
        }
        GradleBuildFacts build = inspected.orElseThrow();
        if (!BOOT_PLUGIN.matcher(build.text()).find()) {
            rejections.add(rejection("SPRING_BOOT_PLUGIN_MISSING",
                    "analysis.deployment.rejection.gradleBootPluginMissing"));
        }
        List<LocalizedMessage> missing = build.usableWrapper() ? List.of()
                : List.of(LocalizedMessage.of("analysis.deployment.missing.gradleWrapper"));
        return new BuildInspection(build.applicationId(), DeploymentBuildToolType.GRADLE_WRAPPER, build.text(),
                List.of(evidence("analysis.deployment.evidence.gradleBuild",
                        build.script().getFileName().toString(), "analysis.deployment.evidence.detected")), missing);
    }

    private static void inspectCommonPolicy(String buildText, SourceInspectionFacts source,
                                            List<RejectionReason> rejections) {
        String scannedText = source.scannedText();
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

    private record BuildInspection(String applicationId, DeploymentBuildToolType buildTool, String buildText,
                                   List<AnalysisEvidence> evidence, List<LocalizedMessage> missingInformation) {
        private BuildInspection {
            evidence = List.copyOf(evidence);
            missingInformation = List.copyOf(missingInformation);
        }
    }
    private static gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence evidence(String subject, String source, String conclusion) {
        return new gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence(
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(subject), source,
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(conclusion),
                gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel.HIGH);
    }
}
