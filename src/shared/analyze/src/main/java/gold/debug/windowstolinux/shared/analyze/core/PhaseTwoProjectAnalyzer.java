package gold.debug.windowstolinux.shared.analyze.core;

import gold.debug.windowstolinux.shared.analyze.source.BoundedSourceInspector;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.analysis.PhaseTwoProjectAssessment;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoBuildTool;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectFacts;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectType;

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
 * Reads a selected Phase Two project type through bounded static inspection and never invokes project content.
 *
 * <p>通过有界静态检查读取选定的二期项目类型，绝不调用项目内容。
 */
public final class PhaseTwoProjectAnalyzer {
    private static final int MAX_ROOT_TEXT_BYTES = 2 * 1024 * 1024;
    private static final Pattern GRADLE_BOOT_PLUGIN = Pattern.compile("(?i)(org\\.springframework\\.boot|spring-boot)");
    private static final Pattern JSON_NAME = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([a-z0-9][a-z0-9._-]{0,62})\\\"");
    private static final Pattern JSON_SCRIPT = Pattern.compile("\\\"(build|start)\\\"\\s*:\\s*\\\"([^\\\"\\r\\n]+)\\\"");
    private static final Pattern TOML_NAME = Pattern.compile("(?m)^\\s*name\\s*=\\s*\\\"([a-z0-9][a-z0-9._-]{0,62})\\\"\\s*$");
    private final BoundedSourceInspector sourceInspector;

    /**
     * Creates a {@code PhaseTwoProjectAnalyzer} instance.
     *
     * <p>创建 {@code PhaseTwoProjectAnalyzer} 实例。
     */
    public PhaseTwoProjectAnalyzer() {
        this(new BoundedSourceInspector());
    }

    PhaseTwoProjectAnalyzer(BoundedSourceInspector sourceInspector) {
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
    public PhaseTwoProjectAssessment analyze(Path selectedSourceDirectory, PhaseTwoProjectType projectType) {
        List<RejectionReason> rejections = new ArrayList<>();
        Path root = normalizeRoot(selectedSourceDirectory, rejections);
        if (root == null) {
            return PhaseTwoProjectAssessment.rejected(rejections);
        }
        sourceInspector.inspect(root, rejections);
        if (!rejections.isEmpty()) {
            return PhaseTwoProjectAssessment.rejected(rejections);
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
            return PhaseTwoProjectAssessment.rejected(List.of(rejection("PHASE_TWO_SOURCE_READ_FAILED",
                    "analysis.phase2.rejection.sourceReadFailed")));
        }
    }

    private static PhaseTwoProjectAssessment inspectGradleSpringBoot(Path root, List<RejectionReason> rejections)
            throws IOException {
        Path script = existingOneOf(root, "build.gradle", "build.gradle.kts", rejections, "GRADLE_BUILD_SCRIPT_AMBIGUOUS");
        if (script == null) {
            rejections.add(rejection("GRADLE_BUILD_SCRIPT_MISSING", "analysis.phase2.rejection.gradleBuildMissing"));
            return PhaseTwoProjectAssessment.rejected(rejections);
        }
        String build = readRootText(script);
        if (!GRADLE_BOOT_PLUGIN.matcher(build).find()) {
            rejections.add(rejection("GRADLE_SPRING_BOOT_PLUGIN_MISSING", "analysis.phase2.rejection.gradleBootPluginMissing"));
            return PhaseTwoProjectAssessment.rejected(rejections);
        }
        List<LocalizedMessage> missing = new ArrayList<>();
        if (!regular(root.resolve("gradlew")) || !regular(root.resolve("gradle/wrapper/gradle-wrapper.properties"))) {
            missing.add(LocalizedMessage.of("analysis.phase2.missing.gradleWrapper"));
        }
        PhaseTwoProjectFacts facts = facts(root, applicationName(root, build, JSON_NAME), PhaseTwoProjectType.GRADLE_SPRING_BOOT,
                PhaseTwoBuildTool.GRADLE_WRAPPER, List.of(evidence("analysis.phase2.evidence.gradleBuild", script.getFileName().toString(),
                        "analysis.phase2.evidence.detected")), List.of(), missing);
        return missing.isEmpty() ? PhaseTwoProjectAssessment.ready(facts) : PhaseTwoProjectAssessment.requiresInput(facts);
    }

    private static PhaseTwoProjectAssessment inspectJavaJar(Path root, List<RejectionReason> rejections) throws IOException {
        List<Path> jars;
        try (var files = Files.list(root)) {
            jars = files.filter(PhaseTwoProjectAnalyzer::regular)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList();
        }
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        if (jars.isEmpty()) {
            missing.add(LocalizedMessage.of("analysis.phase2.missing.javaJar"));
        } else if (jars.size() > 1) {
            conflicts.add(LocalizedMessage.of("analysis.phase2.conflict.multipleJavaJars"));
        }
        missing.add(LocalizedMessage.of("analysis.phase2.missing.javaMainClass"));
        missing.add(LocalizedMessage.of("analysis.phase2.missing.javaVersion"));
        PhaseTwoProjectFacts facts = facts(root, rootApplicationName(root), PhaseTwoProjectType.JAVA_JAR, PhaseTwoBuildTool.JAVA,
                List.of(evidence("analysis.phase2.evidence.javaJar", jars.isEmpty() ? "source root" : jars.getFirst().getFileName().toString(),
                        jars.isEmpty() ? "analysis.phase2.evidence.notDetected" : "analysis.phase2.evidence.detected")), conflicts, missing);
        return conflicts.isEmpty() ? PhaseTwoProjectAssessment.requiresInput(facts) : PhaseTwoProjectAssessment.requiresInput(facts);
    }

    private static PhaseTwoProjectAssessment inspectNode(Path root, List<RejectionReason> rejections) throws IOException {
        Path packageJson = root.resolve("package.json");
        if (!regular(packageJson)) {
            rejections.add(rejection("NODE_PACKAGE_JSON_MISSING", "analysis.phase2.rejection.nodePackageMissing"));
            return PhaseTwoProjectAssessment.rejected(rejections);
        }
        String json = readRootText(packageJson);
        List<String> lockFiles = existingNames(root, "package-lock.json", "pnpm-lock.yaml", "yarn.lock");
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        if (lockFiles.isEmpty()) {
            missing.add(LocalizedMessage.of("analysis.phase2.missing.nodeLockfile"));
        } else if (lockFiles.size() > 1) {
            conflicts.add(LocalizedMessage.of("analysis.phase2.conflict.multipleNodeLockfiles"));
        }
        boolean build = hasScript(json, "build");
        boolean start = hasScript(json, "start");
        if (!build) {
            missing.add(LocalizedMessage.of("analysis.phase2.missing.nodeBuildScript"));
        }
        if (!start) {
            missing.add(LocalizedMessage.of("analysis.phase2.missing.nodeStartScript"));
        }
        PhaseTwoBuildTool tool = lockFiles.equals(List.of("package-lock.json")) ? PhaseTwoBuildTool.NPM
                : lockFiles.equals(List.of("pnpm-lock.yaml")) ? PhaseTwoBuildTool.PNPM : PhaseTwoBuildTool.YARN;
        PhaseTwoProjectFacts facts = facts(root, applicationName(root, json, JSON_NAME), PhaseTwoProjectType.NODE_SERVICE, tool,
                List.of(evidence("analysis.phase2.evidence.nodePackage", "package.json", "analysis.phase2.evidence.detected")),
                conflicts, missing);
        return facts.readyForPlanning() ? PhaseTwoProjectAssessment.ready(facts) : PhaseTwoProjectAssessment.requiresInput(facts);
    }

    private static PhaseTwoProjectAssessment inspectPython(Path root, List<RejectionReason> rejections) throws IOException {
        Path pyproject = root.resolve("pyproject.toml");
        if (!regular(pyproject)) {
            rejections.add(rejection("PYTHON_PYPROJECT_MISSING", "analysis.phase2.rejection.pythonPyprojectMissing"));
            return PhaseTwoProjectAssessment.rejected(rejections);
        }
        String toml = readRootText(pyproject);
        List<String> lockFiles = existingNames(root, "requirements.lock", "poetry.lock", "uv.lock", "Pipfile.lock");
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        if (lockFiles.isEmpty()) {
            missing.add(LocalizedMessage.of("analysis.phase2.missing.pythonLockfile"));
        } else if (lockFiles.size() > 1) {
            conflicts.add(LocalizedMessage.of("analysis.phase2.conflict.multiplePythonLockfiles"));
        }
        missing.add(LocalizedMessage.of("analysis.phase2.missing.pythonEntrypoint"));
        missing.add(LocalizedMessage.of("analysis.phase2.missing.pythonVersion"));
        PhaseTwoProjectFacts facts = facts(root, applicationName(root, toml, TOML_NAME), PhaseTwoProjectType.PYTHON_SERVICE,
                PhaseTwoBuildTool.PYTHON_VENV, List.of(evidence("analysis.phase2.evidence.pythonProject", "pyproject.toml",
                        "analysis.phase2.evidence.detected")), conflicts, missing);
        return PhaseTwoProjectAssessment.requiresInput(facts);
    }

    private static PhaseTwoProjectAssessment inspectStaticSite(Path root, List<RejectionReason> rejections) throws IOException {
        if (!regular(root.resolve("index.html")) && !regular(root.resolve("package.json"))) {
            rejections.add(rejection("STATIC_SITE_ENTRY_MISSING", "analysis.phase2.rejection.staticSiteEntryMissing"));
            return PhaseTwoProjectAssessment.rejected(rejections);
        }
        List<LocalizedMessage> missing = new ArrayList<>(List.of(
                LocalizedMessage.of("analysis.phase2.missing.staticOutputDirectory")
        ));
        if (regular(root.resolve("package.json")) && !hasScript(readRootText(root.resolve("package.json")), "build")) {
            missing.add(LocalizedMessage.of("analysis.phase2.missing.staticBuildScript"));
        }
        PhaseTwoProjectFacts facts = facts(root, rootApplicationName(root), PhaseTwoProjectType.STATIC_SITE,
                PhaseTwoBuildTool.STATIC_SITE_BUILD, List.of(evidence("analysis.phase2.evidence.staticSite", "source root",
                        "analysis.phase2.evidence.detected")), List.of(), missing);
        return PhaseTwoProjectAssessment.requiresInput(facts);
    }

    private static PhaseTwoProjectAssessment inspectDockerfile(Path root, List<RejectionReason> rejections) throws IOException {
        Path dockerfile = root.resolve("Dockerfile");
        if (!regular(dockerfile)) {
            rejections.add(rejection("DOCKERFILE_MISSING", "analysis.phase2.rejection.dockerfileMissing"));
            return PhaseTwoProjectAssessment.rejected(rejections);
        }
        List<String> composeFiles = existingNames(root, "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml");
        if (!composeFiles.isEmpty()) {
            rejections.add(rejection("MULTI_CONTAINER_COMPOSE_DETECTED", "analysis.phase2.rejection.composeUnsupported"));
            return PhaseTwoProjectAssessment.rejected(rejections);
        }
        List<LocalizedMessage> missing = List.of(
                LocalizedMessage.of("analysis.phase2.missing.containerPorts"),
                LocalizedMessage.of("analysis.phase2.missing.containerHealth"),
                LocalizedMessage.of("analysis.phase2.missing.containerVolumes")
        );
        PhaseTwoProjectFacts facts = facts(root, rootApplicationName(root), PhaseTwoProjectType.DOCKERFILE_CONTAINER,
                PhaseTwoBuildTool.CONTAINER_BUILD, List.of(evidence("analysis.phase2.evidence.dockerfile", "Dockerfile",
                        "analysis.phase2.evidence.detected")), List.of(), missing);
        return PhaseTwoProjectAssessment.requiresInput(facts);
    }

    private static PhaseTwoProjectFacts facts(Path root, String applicationId, PhaseTwoProjectType type, PhaseTwoBuildTool tool,
                                               List<AnalysisEvidence> evidence, List<LocalizedMessage> conflicts,
                                               List<LocalizedMessage> missing) {
        return new PhaseTwoProjectFacts(root, applicationId, type, tool, evidence, conflicts, missing);
    }

    private static Path normalizeRoot(Path source, List<RejectionReason> rejections) {
        if (source == null) {
            rejections.add(rejection("SOURCE_PATH_MISSING", "analysis.phase2.rejection.sourceMissing"));
            return null;
        }
        Path normalized = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            rejections.add(rejection("SOURCE_PATH_INVALID", "analysis.phase2.rejection.sourceInvalid"));
            return null;
        }
        return normalized;
    }

    private static Path existingOneOf(Path root, String first, String second, List<RejectionReason> rejections, String conflictCode) {
        boolean hasFirst = regular(root.resolve(first));
        boolean hasSecond = regular(root.resolve(second));
        if (hasFirst && hasSecond) {
            rejections.add(rejection(conflictCode, "analysis.phase2.rejection.gradleBuildAmbiguous"));
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

    private static AnalysisEvidence evidence(String subject, String source, String conclusion) {
        return new AnalysisEvidence(LocalizedMessage.of(subject), source, LocalizedMessage.of(conclusion), EvidenceConfidence.HIGH);
    }

    private static RejectionReason rejection(String code, String messageKey) {
        return new RejectionReason(code, LocalizedMessage.of(messageKey), "phase.two");
    }
}
