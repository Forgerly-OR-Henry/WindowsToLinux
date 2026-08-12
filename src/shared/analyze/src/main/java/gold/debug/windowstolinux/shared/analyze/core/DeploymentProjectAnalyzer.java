package gold.debug.windowstolinux.shared.analyze.core;

import gold.debug.windowstolinux.shared.analyze.source.BoundedSourceInspector;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a selected typed deployment project type through bounded static inspection and never invokes project content.
 *
 * <p>通过有界静态检查读取选定的部署项目类型，绝不调用项目内容。
 */
public final class DeploymentProjectAnalyzer {
    private static final int MAX_ROOT_TEXT_BYTES = 2 * 1024 * 1024;
    private static final Pattern GRADLE_BOOT_PLUGIN = Pattern.compile("(?i)(org\\.springframework\\.boot|spring-boot)");
    private static final Pattern JSON_NAME = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([a-z0-9][a-z0-9._-]{0,62})\\\"");
    private static final Pattern JSON_SCRIPT = Pattern.compile("\\\"(build|start)\\\"\\s*:\\s*\\\"([^\\\"\\r\\n]+)\\\"");
    private static final Pattern TOML_NAME = Pattern.compile("(?m)^\\s*name\\s*=\\s*\\\"([a-z0-9][a-z0-9._-]{0,62})\\\"\\s*$");
    private final BoundedSourceInspector sourceInspector;

    /**
     * Creates a {@code DeploymentProjectAnalyzer} instance.
     *
     * <p>创建 {@code DeploymentProjectAnalyzer} 实例。
     */
    public DeploymentProjectAnalyzer() {
        this(new BoundedSourceInspector());
    }

    DeploymentProjectAnalyzer(BoundedSourceInspector sourceInspector) {
        this.sourceInspector = java.util.Objects.requireNonNull(sourceInspector, "sourceInspector");
    }

    /**
     * Analyzes one explicitly selected project type without selecting or running a build command.
     *
     * <p>分析一个显式选定的项目类型，不选择或运行构建命令。
     *
     * @param selectedSourceDirectory the source root / 源码根目录
     * @param projectType the user-selected type / 用户选定类型
     * @return the static assessment / 静态评估
     */
    public DeploymentProjectAssessment analyze(Path selectedSourceDirectory, DeploymentProjectType projectType) {
        List<RejectionReason> rejections = new ArrayList<>();
        Path root = normalizeRoot(selectedSourceDirectory, rejections);
        if (root == null) {
            return DeploymentProjectAssessment.rejected(rejections);
        }
        sourceInspector.inspect(root, rejections);
        if (!rejections.isEmpty()) {
            return DeploymentProjectAssessment.rejected(rejections);
        }
        try {
            return switch (java.util.Objects.requireNonNull(projectType, "projectType")) {
                case GRADLE_SPRING_BOOT -> inspectGradleSpringBoot(root, rejections);
                case JAVA_JAR -> inspectJavaJar(root, rejections);
                case NODE_SERVICE -> inspectNode(root, rejections);
                case PYTHON_SERVICE -> inspectPython(root, rejections);
                case STATIC_SITE -> inspectStaticSite(root, rejections);
                case DOCKERFILE_CONTAINER -> inspectDockerfile(root, rejections);
            };
        } catch (IOException exception) {
            return DeploymentProjectAssessment.rejected(List.of(rejection("DEPLOYMENT_SOURCE_READ_FAILED",
                    "analysis.deployment.rejection.sourceReadFailed")));
        }
    }

    private static DeploymentProjectAssessment inspectGradleSpringBoot(Path root, List<RejectionReason> rejections)
            throws IOException {
        Path script = existingOneOf(root, "build.gradle", "build.gradle.kts", rejections, "GRADLE_BUILD_SCRIPT_AMBIGUOUS");
        if (script == null) {
            rejections.add(rejection("GRADLE_BUILD_SCRIPT_MISSING", "analysis.deployment.rejection.gradleBuildMissing"));
            return DeploymentProjectAssessment.rejected(rejections);
        }
        String build = readRootText(script);
        if (!GRADLE_BOOT_PLUGIN.matcher(build).find()) {
            rejections.add(rejection("GRADLE_SPRING_BOOT_PLUGIN_MISSING", "analysis.deployment.rejection.gradleBootPluginMissing"));
            return DeploymentProjectAssessment.rejected(rejections);
        }
        List<LocalizedMessage> missing = new ArrayList<>();
        if (!regular(root.resolve("gradlew")) || !regular(root.resolve("gradle/wrapper/gradle-wrapper.properties"))) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.gradleWrapper"));
        }
        DeploymentProjectFacts facts = facts(root, applicationName(root, build, JSON_NAME), DeploymentProjectType.GRADLE_SPRING_BOOT,
                DeploymentBuildTool.GRADLE_WRAPPER, List.of(evidence("analysis.deployment.evidence.gradleBuild", script.getFileName().toString(),
                        "analysis.deployment.evidence.detected")), List.of(), missing);
        return missing.isEmpty() ? DeploymentProjectAssessment.ready(facts) : DeploymentProjectAssessment.requiresInput(facts);
    }

    private static DeploymentProjectAssessment inspectJavaJar(Path root, List<RejectionReason> rejections) throws IOException {
        List<Path> jars;
        try (var files = Files.list(root)) {
            jars = files.filter(DeploymentProjectAnalyzer::regular)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList();
        }
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        if (jars.isEmpty()) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.javaJar"));
        } else if (jars.size() > 1) {
            conflicts.add(LocalizedMessage.of("analysis.deployment.conflict.multipleJavaJars"));
        }
        DeploymentProjectFacts facts = facts(root, rootApplicationName(root), DeploymentProjectType.JAVA_JAR, DeploymentBuildTool.JAVA,
                List.of(evidence("analysis.deployment.evidence.javaJar", jars.isEmpty() ? "source root" : jars.getFirst().getFileName().toString(),
                        jars.isEmpty() ? "analysis.deployment.evidence.notDetected" : "analysis.deployment.evidence.detected")), conflicts, missing);
        return facts.readyForPlanning() ? DeploymentProjectAssessment.ready(facts) : DeploymentProjectAssessment.requiresInput(facts);
    }

    private static DeploymentProjectAssessment inspectNode(Path root, List<RejectionReason> rejections) throws IOException {
        Path packageJson = root.resolve("package.json");
        if (!regular(packageJson)) {
            rejections.add(rejection("NODE_PACKAGE_JSON_MISSING", "analysis.deployment.rejection.nodePackageMissing"));
            return DeploymentProjectAssessment.rejected(rejections);
        }
        String json = readRootText(packageJson);
        List<String> lockFiles = existingNames(root, "package-lock.json", "pnpm-lock.yaml", "yarn.lock");
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        if (lockFiles.isEmpty()) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.nodeLockfile"));
        } else if (lockFiles.size() > 1) {
            conflicts.add(LocalizedMessage.of("analysis.deployment.conflict.multipleNodeLockfiles"));
        }
        boolean build = hasScript(json, "build");
        boolean start = hasScript(json, "start");
        if (!build) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.nodeBuildScript"));
        }
        if (!start) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.nodeStartScript"));
        }
        DeploymentBuildTool tool = nodeBuildTool(lockFiles);
        DeploymentProjectFacts facts = facts(root, applicationName(root, json, JSON_NAME), DeploymentProjectType.NODE_SERVICE, tool,
                List.of(evidence("analysis.deployment.evidence.nodePackage", "package.json", "analysis.deployment.evidence.detected")),
                conflicts, missing);
        return facts.readyForPlanning() ? DeploymentProjectAssessment.ready(facts) : DeploymentProjectAssessment.requiresInput(facts);
    }

    private static DeploymentProjectAssessment inspectPython(Path root, List<RejectionReason> rejections) throws IOException {
        Path pyproject = root.resolve("pyproject.toml");
        if (!regular(pyproject)) {
            rejections.add(rejection("PYTHON_PYPROJECT_MISSING", "analysis.deployment.rejection.pythonPyprojectMissing"));
            return DeploymentProjectAssessment.rejected(rejections);
        }
        String toml = readRootText(pyproject);
        List<String> lockFiles = existingNames(root, "requirements.lock", "poetry.lock", "uv.lock", "Pipfile.lock");
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        if (lockFiles.isEmpty()) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.pythonLockfile"));
        } else if (lockFiles.size() > 1) {
            conflicts.add(LocalizedMessage.of("analysis.deployment.conflict.multiplePythonLockfiles"));
        }
        DeploymentProjectFacts facts = facts(root, applicationName(root, toml, TOML_NAME), DeploymentProjectType.PYTHON_SERVICE,
                DeploymentBuildTool.PYTHON_VENV, List.of(evidence("analysis.deployment.evidence.pythonProject", "pyproject.toml",
                        "analysis.deployment.evidence.detected")), conflicts, missing);
        return facts.readyForPlanning() ? DeploymentProjectAssessment.ready(facts) : DeploymentProjectAssessment.requiresInput(facts);
    }

    private static DeploymentProjectAssessment inspectStaticSite(Path root, List<RejectionReason> rejections) throws IOException {
        if (!regular(root.resolve("index.html")) && !regular(root.resolve("package.json"))) {
            rejections.add(rejection("STATIC_SITE_ENTRY_MISSING", "analysis.deployment.rejection.staticSiteEntryMissing"));
            return DeploymentProjectAssessment.rejected(rejections);
        }
        boolean packageBased = regular(root.resolve("package.json"));
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        List<String> lockFiles = packageBased
                ? existingNames(root, "package-lock.json", "pnpm-lock.yaml", "yarn.lock") : List.of();
        if (packageBased && lockFiles.isEmpty()) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.nodeLockfile"));
        } else if (lockFiles.size() > 1) {
            conflicts.add(LocalizedMessage.of("analysis.deployment.conflict.multipleNodeLockfiles"));
        }
        if (packageBased && !hasScript(readRootText(root.resolve("package.json")), "build")) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.staticBuildScript"));
        }
        DeploymentProjectFacts facts = facts(root, rootApplicationName(root), DeploymentProjectType.STATIC_SITE,
                packageBased ? nodeBuildTool(lockFiles) : DeploymentBuildTool.STATIC_SITE_BUILD,
                List.of(evidence("analysis.deployment.evidence.staticSite", packageBased ? "package.json" : "index.html",
                        "analysis.deployment.evidence.detected")), conflicts, missing);
        return facts.readyForPlanning() ? DeploymentProjectAssessment.ready(facts) : DeploymentProjectAssessment.requiresInput(facts);
    }

    private static DeploymentProjectAssessment inspectDockerfile(Path root, List<RejectionReason> rejections) throws IOException {
        Path dockerfile = root.resolve("Dockerfile");
        if (!regular(dockerfile)) {
            rejections.add(rejection("DOCKERFILE_MISSING", "analysis.deployment.rejection.dockerfileMissing"));
            return DeploymentProjectAssessment.rejected(rejections);
        }
        List<String> composeFiles = existingNames(root, "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml");
        if (!composeFiles.isEmpty()) {
            rejections.add(rejection("MULTI_CONTAINER_COMPOSE_DETECTED", "analysis.deployment.rejection.composeUnsupported"));
            return DeploymentProjectAssessment.rejected(rejections);
        }
        DeploymentProjectFacts facts = facts(root, rootApplicationName(root), DeploymentProjectType.DOCKERFILE_CONTAINER,
                DeploymentBuildTool.CONTAINER_BUILD, List.of(evidence("analysis.deployment.evidence.dockerfile", "Dockerfile",
                        "analysis.deployment.evidence.detected")), List.of(), List.of());
        return DeploymentProjectAssessment.ready(facts);
    }

    private static DeploymentProjectFacts facts(Path root, String applicationId, DeploymentProjectType type, DeploymentBuildTool tool,
                                               List<AnalysisEvidence> evidence, List<LocalizedMessage> conflicts,
                                               List<LocalizedMessage> missing) {
        return new DeploymentProjectFacts(root, applicationId, type, tool, evidence, conflicts, missing);
    }

    private static Path normalizeRoot(Path source, List<RejectionReason> rejections) {
        if (source == null) {
            rejections.add(rejection("SOURCE_PATH_MISSING", "analysis.deployment.rejection.sourceMissing"));
            return null;
        }
        Path normalized = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            rejections.add(rejection("SOURCE_PATH_INVALID", "analysis.deployment.rejection.sourceInvalid"));
            return null;
        }
        return normalized;
    }

    private static Path existingOneOf(Path root, String first, String second, List<RejectionReason> rejections, String conflictCode) {
        boolean hasFirst = regular(root.resolve(first));
        boolean hasSecond = regular(root.resolve(second));
        if (hasFirst && hasSecond) {
            rejections.add(rejection(conflictCode, "analysis.deployment.rejection.gradleBuildAmbiguous"));
            return null;
        }
        return hasFirst ? root.resolve(first) : hasSecond ? root.resolve(second) : null;
    }

    private static List<String> existingNames(Path root, String... names) {
        List<String> present = new ArrayList<>();
        for (String name : names) {
            if (regular(root.resolve(name))) {
                present.add(name);
            }
        }
        return List.copyOf(present);
    }

    private static boolean regular(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static String readRootText(Path file) throws IOException {
        if (Files.size(file) > MAX_ROOT_TEXT_BYTES) {
            throw new IOException("root project metadata exceeds the static inspection bound");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String applicationName(Path root, String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? normalizeApplicationName(matcher.group(1)) : rootApplicationName(root);
    }

    private static String rootApplicationName(Path root) {
        Path name = root.getFileName();
        return normalizeApplicationName(name == null ? "application" : name.toString());
    }

    private static String normalizeApplicationName(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            return "application";
        }
        return normalized.length() > 63 ? normalized.substring(0, 63).replaceAll("-+$", "") : normalized;
    }

    private static boolean hasScript(String packageJson, String scriptName) {
        Matcher matcher = JSON_SCRIPT.matcher(packageJson);
        while (matcher.find()) {
            if (scriptName.equals(matcher.group(1)) && !matcher.group(2).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static DeploymentBuildTool nodeBuildTool(List<String> lockFiles) {
        if (lockFiles.equals(List.of("pnpm-lock.yaml"))) {
            return DeploymentBuildTool.PNPM;
        }
        if (lockFiles.equals(List.of("yarn.lock"))) {
            return DeploymentBuildTool.YARN;
        }
        return DeploymentBuildTool.NPM;
    }

    private static AnalysisEvidence evidence(String subject, String source, String conclusion) {
        return new AnalysisEvidence(LocalizedMessage.of(subject), source, LocalizedMessage.of(conclusion), EvidenceConfidence.HIGH);
    }

    private static RejectionReason rejection(String code, String messageKey) {
        return new RejectionReason(code, LocalizedMessage.of(messageKey), "deployment");
    }
}
