package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.java;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.java.gradle.GradleBuildFacts;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.java.gradle.GradleBuildInspector;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.java.maven.MavenBuildFacts;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.java.maven.MavenBuildInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.standard.analyze.source.SourceInspectionFacts;

/**
 * Inspects one Maven or Gradle Spring Boot executable-JAR project without invoking its build.
 *
 *  <p>在不调用构建的前提下检查一个 Maven 或 Gradle Spring Boot 可执行 JAR 项目。
 */
public final class SpringBootDeploymentInspector implements DeploymentTypeInspector {
    /**
     * Pattern recognizing Spring Boot build plugin.
     * <p>用于识别Spring Boot 构建插件的匹配模式。
     */
    private static final Pattern BOOT_PLUGIN = Pattern.compile("(?i)(org\\.springframework\\.boot|spring-boot)");

    /**
     * Pattern recognizing EXTERNAL CONFIG.
     * <p>用于识别外部配置的匹配模式。
     */
    private static final Pattern EXTERNAL_CONFIG = Pattern.compile(
            "spring\\.config\\.(import|location|additional-location)|SPRING_CONFIG_(IMPORT|LOCATION|ADDITIONAL_LOCATION)"
                    + "|spring\\.application\\.json",
            Pattern.CASE_INSENSITIVE);

    /**
     * Pattern recognizing APPLICATION SECRET.
     * <p>用于识别应用秘密的匹配模式。
     */
    private static final Pattern APPLICATION_SECRET = Pattern
            .compile("(?i)(password|secret|api[_-]?key|access[_-]?key|token)\\s*[:=]"
                    + "|@Value\\s*\\(\\s*\\\"?\\$\\{[^}]*?(password|secret|key|token)[^}]*}");

    /**
     * Pattern recognizing disabled Spring Boot JAR packaging.
     * <p>用于识别已禁用 Spring Boot JAR 打包的匹配模式。
     */
    private static final Pattern DISABLED_BOOT_JAR = Pattern
            .compile("(?is)bootJar(?:\\s*\\{[^}]*enabled\\s*=\\s*false|\\.enabled\\s*=\\s*false)"
                    + "|<artifactId>\\s*spring-boot-maven-plugin\\s*</artifactId>.*?<skip>\\s*true\\s*</skip>");

    /**
     * Bound maven build inspector collaborator for maven.
     * <p>处理Maven 构建的Maven构建检查器协作对象。
     */
    private final MavenBuildInspector maven = new MavenBuildInspector();

    /**
     * Bound gradle build inspector collaborator for gradle.
     * <p>处理Gradle 构建的Gradle构建检查器协作对象。
     */
    private final GradleBuildInspector gradle = new GradleBuildInspector();

    /**
     * Returns the supported deployment project type. / 返回支持的部署项目类型。
     *
     * @return the supported deployment project type / 支持的部署项目类型
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.SPRING_BOOT;
    }

    /**
     * Inspects source facts for this deployment type. / 检查此部署类型的源码事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param languageFacts language facts / 语言事实
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved deployment type assessment; null when no matching value is available / 构造或解析得到的部署类型评估；没有匹配值时为 null
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    @Override
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
            List<RejectionReason> rejections) throws IOException {
        boolean hasMaven = BoundedMetadataInspector.regular(root.resolve("pom.xml"));
        boolean hasGradle = BoundedMetadataInspector.regular(root.resolve("build.gradle"))
                || BoundedMetadataInspector.regular(root.resolve("build.gradle.kts"));
        if (hasMaven == hasGradle) {
            rejections.add(rejection(hasMaven ? "SPRING_BOOT_BUILD_AMBIGUOUS" : "SPRING_BOOT_BUILD_MISSING",
                    hasMaven
                            ? "analysis.deployment.rejection.springBootBuildAmbiguous"
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
        DeploymentRuntimeAssessment suggestion = new DeploymentRuntimeAssessment(projectType(), Map.of(),
                Optional.empty(), Map.of(), List.of(), List.of(),
                List.of(LocalizedMessage.of("analysis.deployment.runtime.health")));
        return new DeploymentTypeAssessment(facts, suggestion);
    }

    /**
     * Inspects Maven metadata for a supported Spring Boot deployment and returns no assessment when that architecture does not match.
     * <p>检查 Maven 元数据是否构成受支持 Spring Boot 部署；架构不匹配时不返回评估。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved build inspection; null when no matching value is available / 构造或解析得到的构建检查；没有匹配值时为 null
     */
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
        evidence.add(evidence("analysis.evidence.mavenEntry", "pom.xml", "analysis.evidence.rootPomRead"));
        evidence.add(evidence("analysis.evidence.springBootPlugin", "pom.xml",
                build.springBootPlugin()
                        ? "analysis.evidence.springBootPluginDetected"
                        : "analysis.evidence.notDetected"));
        String wrapperSource = wrapper
                ? "mvnw + .mvn/wrapper/maven-wrapper.properties"
                : hasMavenWrapper ? "mvnw" : hasWindowsMavenWrapper ? "mvnw.cmd" : "source tree";
        String wrapperConclusion = wrapper
                ? "analysis.evidence.wrapper.usable"
                : hasMavenWrapper
                        ? "analysis.evidence.wrapper.configurationMissing"
                        : hasWindowsMavenWrapper
                                ? "analysis.evidence.wrapper.windowsOnly"
                                : "analysis.evidence.notDetected";
        evidence.add(evidence("analysis.evidence.mavenWrapper", wrapperSource, wrapperConclusion));
        return new BuildInspection(build.applicationName(), tool, build.pomText(), evidence, List.of());
    }

    /**
     * Inspects gradle.
     * <p>检查Gradle 构建。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved build inspection; null when no matching value is available / 构造或解析得到的构建检查；没有匹配值时为 null
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private BuildInspection inspectGradle(Path root, List<RejectionReason> rejections) throws IOException {
        Optional<GradleBuildFacts> inspected = gradle.inspect(root, rejections);
        if (inspected.isEmpty()) {
            return null;
        }
        GradleBuildFacts build = inspected.orElseThrow();
        if (!BOOT_PLUGIN.matcher(build.text()).find()) {
            rejections.add(
                    rejection("SPRING_BOOT_PLUGIN_MISSING", "analysis.deployment.rejection.gradleBootPluginMissing"));
        }
        List<LocalizedMessage> missing = build.usableWrapper()
                ? List.of()
                : List.of(LocalizedMessage.of("analysis.deployment.missing.gradleWrapper"));
        return new BuildInspection(build.applicationId(), DeploymentBuildToolType.GRADLE_WRAPPER, build.text(),
                List.of(evidence("analysis.deployment.evidence.gradleBuild", build.script().getFileName().toString(),
                        "analysis.deployment.evidence.detected")),
                missing);
    }

    /**
     * Inspects common policy.
     * <p>检查共享策略。
     *
     * @param buildText build text / 构建文本
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     */
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

    /**
     * Builds the admission rejection associated with the supplied reason.
     * <p>构建与所提供原因关联的准入拒绝。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return the admission rejection associated with the supplied reason / 与所提供原因关联的准入拒绝
     */
    private static RejectionReason rejection(String code, String key) {
        return new RejectionReason(code, LocalizedMessage.of(key), "deployment");
    }

    /**
     * Collects build-system metadata and the evidence used for deployment admission.
     * <p>汇总构建系统元数据及部署准入所用证据。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param buildTool the fixed build entrypoint / 固定构建入口
     * @param buildText build text / 构建文本
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @param missingInformation required explicit user input / 所需的显式用户输入
     */
    private record BuildInspection(String applicationId, DeploymentBuildToolType buildTool, String buildText,
            List<AnalysisEvidence> evidence, List<LocalizedMessage> missingInformation) {
        /**
         * Binds the supplied dependencies and state for build inspection.
         * <p>为构建检查绑定传入的依赖及状态。
         *
         * @param applicationId managed application identifier / 受管应用标识
         * @param buildTool the fixed build entrypoint / 固定构建入口
         * @param buildText build text / 构建文本
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         * @param missingInformation required explicit user input / 所需的显式用户输入
         */
        private BuildInspection {
            evidence = List.copyOf(evidence);
            missingInformation = List.copyOf(missingInformation);
        }
    }
    /**
     * Binds a static source observation to its localized conclusion and confidence.
     * <p>将静态源码观测与本地化结论及置信度绑定。
     *
     * @param subject subject / 对象
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param conclusion conclusion / 结论
     * @return constructed or resolved analysis evidence / 构造或解析得到的分析证据
     */
    private static gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence evidence(String subject,
            String source, String conclusion) {
        return new gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence(
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(subject), source,
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(conclusion),
                gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel.HIGH);
    }
}
