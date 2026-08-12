package gold.debug.windowstolinux.shared.analyze.core;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSuggestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Infers only deterministic runtime form values from bounded metadata; it never turns project text into an executable command.
 *
 * <p>仅从有界元数据推导确定性的运行时表单值；绝不将项目文本转换为可执行命令。
 */
final class DeploymentRuntimeInference {
    private static final long MAX_JAR_METADATA_BYTES = 512L * 1024 * 1024;
    private static final Pattern JSON_NODE_ENGINE = Pattern.compile("\\\"node\\\"\\s*:\\s*\\\"([^\\\"\\r\\n]+)\\\"");
    private static final Pattern TOML_PYTHON_VERSION = Pattern.compile(
            "(?m)^\\s*requires-python\\s*=\\s*\\\"([^\\\"\\r\\n]+)\\\"\\s*$");
    private static final Pattern VITE_BUILD_SCRIPT = Pattern.compile(
            "(?s)\\\"build\\\"\\s*:\\s*\\\"[^\\\"\\r\\n]*\\bvite\\b[^\\\"\\r\\n]*\\\"");
    private static final Pattern VITE_OUTPUT_DIRECTORY = Pattern.compile(
            "(?m)\\boutDir\\s*:\\s*['\\\"]([A-Za-z0-9._/-]{1,255})['\\\"]");
    private static final Pattern EXPOSE_LINE = Pattern.compile("(?im)^\\s*EXPOSE\\s+([^\\r\\n#]+)$");
    private static final Pattern VOLUME_LINE = Pattern.compile("(?im)^\\s*VOLUME\\s+(?:\\[\\s*)?['\\\"]?(/[^\\s,'\\\"\\]]+)['\\\"]?(?:\\s*\\])?\\s*$");

    private DeploymentRuntimeInference() {
    }

    /** Returns reviewable source-backed suggestions for exactly one already-admitted type. / 为恰好一个已准入类型返回可审阅的源码依据建议。 */
    static DeploymentRuntimeSuggestion infer(Path root, DeploymentProjectFacts facts) throws IOException {
        return switch (facts.projectType()) {
            case GRADLE_SPRING_BOOT -> suggestion(facts, Map.of(), Optional.empty(), Map.of(), List.of(), List.of(),
                    List.of(required("analysis.deployment.runtime.health")));
            case JAVA_JAR -> inferJavaJar(root, facts);
            case NODE_SERVICE -> inferNode(root, facts);
            case PYTHON_SERVICE -> inferPython(root, facts);
            case STATIC_SITE -> inferStaticSite(root, facts);
            case DOCKERFILE_CONTAINER -> inferContainer(root, facts);
        };
    }

    private static DeploymentRuntimeSuggestion inferJavaJar(Path root, DeploymentProjectFacts facts) throws IOException {
        Map<DeploymentRuntimeSuggestion.RuntimeInput, String> values = DeploymentRuntimeSuggestion.valuesFor(facts.projectType());
        List<AnalysisEvidence> evidence = new ArrayList<>();
        List<LocalizedMessage> required = new ArrayList<>(List.of(required("analysis.deployment.runtime.health")));
        Optional<Path> jar = singleRootJar(root);
        if (jar.isPresent()) {
            String fileName = jar.orElseThrow().getFileName().toString();
            values.put(DeploymentRuntimeSuggestion.RuntimeInput.JAVA_JAR_PATH, fileName);
            evidence.add(evidence("analysis.deployment.runtime.evidence.javaJarPath", fileName));
            readJarManifest(jar.orElseThrow()).ifPresent(manifest -> {
                String mainClass = manifest.getValue(Attributes.Name.MAIN_CLASS);
                if (mainClass != null && mainClass.trim().matches("[A-Za-z_$][A-Za-z0-9_$.]{0,255}")) {
                    values.put(DeploymentRuntimeSuggestion.RuntimeInput.JAVA_MAIN_CLASS, mainClass.trim());
                    evidence.add(evidence("analysis.deployment.runtime.evidence.javaMainClass", fileName + "!META-INF/MANIFEST.MF"));
                }
                String version = firstSupportedJavaVersion(manifest.getValue("Build-Jdk-Spec"), manifest.getValue("Build-Jdk"));
                if (version != null) {
                    values.put(DeploymentRuntimeSuggestion.RuntimeInput.JAVA_VERSION, version);
                    evidence.add(evidence("analysis.deployment.runtime.evidence.javaVersion", fileName + "!META-INF/MANIFEST.MF"));
                }
            });
        }
        if (!values.containsKey(DeploymentRuntimeSuggestion.RuntimeInput.JAVA_MAIN_CLASS)) {
            required.add(required("analysis.deployment.runtime.javaMainClass"));
        }
        if (!values.containsKey(DeploymentRuntimeSuggestion.RuntimeInput.JAVA_VERSION)) {
            required.add(required("analysis.deployment.runtime.javaVersion"));
        }
        return suggestion(facts, values, Optional.empty(), Map.of(), List.of(), evidence, required);
    }

    private static DeploymentRuntimeSuggestion inferNode(Path root, DeploymentProjectFacts facts) throws IOException {
        Map<DeploymentRuntimeSuggestion.RuntimeInput, String> values = DeploymentRuntimeSuggestion.valuesFor(facts.projectType());
        List<AnalysisEvidence> evidence = new ArrayList<>();
        String packageJson = Files.readString(root.resolve("package.json"), StandardCharsets.UTF_8);
        Matcher matcher = JSON_NODE_ENGINE.matcher(packageJson);
        if (matcher.find()) {
            String major = exactNodeMajor(matcher.group(1));
            if (major != null) {
                values.put(DeploymentRuntimeSuggestion.RuntimeInput.NODE_MAJOR_VERSION, major);
                evidence.add(evidence("analysis.deployment.runtime.evidence.nodeVersion", "package.json#engines.node"));
            }
        }
        List<LocalizedMessage> required = new ArrayList<>(List.of(required("analysis.deployment.runtime.health")));
        if (!values.containsKey(DeploymentRuntimeSuggestion.RuntimeInput.NODE_MAJOR_VERSION)) {
            required.add(required("analysis.deployment.runtime.nodeVersion"));
        }
        return suggestion(facts, values, Optional.empty(), Map.of(), List.of(), evidence, required);
    }

    private static DeploymentRuntimeSuggestion inferPython(Path root, DeploymentProjectFacts facts) throws IOException {
        Map<DeploymentRuntimeSuggestion.RuntimeInput, String> values = DeploymentRuntimeSuggestion.valuesFor(facts.projectType());
        List<AnalysisEvidence> evidence = new ArrayList<>();
        String pyproject = Files.readString(root.resolve("pyproject.toml"), StandardCharsets.UTF_8);
        Matcher versionMatcher = TOML_PYTHON_VERSION.matcher(pyproject);
        if (versionMatcher.find()) {
            String version = exactPythonVersion(versionMatcher.group(1));
            if (version != null) {
                values.put(DeploymentRuntimeSuggestion.RuntimeInput.PYTHON_VERSION, version);
                evidence.add(evidence("analysis.deployment.runtime.evidence.pythonVersion", "pyproject.toml#requires-python"));
            }
        }
        Optional<String> entrypoint = uniquePythonMainModule(root);
        if (entrypoint.isPresent()) {
            values.put(DeploymentRuntimeSuggestion.RuntimeInput.PYTHON_ENTRYPOINT, entrypoint.orElseThrow());
            evidence.add(evidence("analysis.deployment.runtime.evidence.pythonEntrypoint", entrypoint.orElseThrow() + ".__main__"));
        }
        List<LocalizedMessage> required = new ArrayList<>(List.of(required("analysis.deployment.runtime.health")));
        if (!values.containsKey(DeploymentRuntimeSuggestion.RuntimeInput.PYTHON_VERSION)) {
            required.add(required("analysis.deployment.runtime.pythonVersion"));
        }
        if (!values.containsKey(DeploymentRuntimeSuggestion.RuntimeInput.PYTHON_ENTRYPOINT)) {
            required.add(required("analysis.deployment.runtime.pythonEntrypoint"));
        }
        return suggestion(facts, values, Optional.empty(), Map.of(), List.of(), evidence, required);
    }

    private static DeploymentRuntimeSuggestion inferStaticSite(Path root, DeploymentProjectFacts facts) throws IOException {
        Map<DeploymentRuntimeSuggestion.RuntimeInput, String> values = DeploymentRuntimeSuggestion.valuesFor(facts.projectType());
        List<AnalysisEvidence> evidence = new ArrayList<>();
        Path packageJson = root.resolve("package.json");
        if (regular(packageJson)) {
            String json = Files.readString(packageJson, StandardCharsets.UTF_8);
            if (VITE_BUILD_SCRIPT.matcher(json).find()) {
                String output = configuredViteOutput(root).orElse("dist");
                values.put(DeploymentRuntimeSuggestion.RuntimeInput.STATIC_OUTPUT_DIRECTORY, output);
                evidence.add(evidence("analysis.deployment.runtime.evidence.staticOutput",
                        output.equals("dist") ? "package.json#scripts.build" : "vite.config#build.outDir"));
            }
        }
        List<LocalizedMessage> required = new ArrayList<>(List.of(required("analysis.deployment.runtime.health")));
        if (!values.containsKey(DeploymentRuntimeSuggestion.RuntimeInput.STATIC_OUTPUT_DIRECTORY)) {
            required.add(required("analysis.deployment.runtime.staticOutput"));
        }
        return suggestion(facts, values, Optional.empty(), Map.of(), List.of(), evidence, required);
    }

    private static DeploymentRuntimeSuggestion inferContainer(Path root, DeploymentProjectFacts facts) throws IOException {
        String dockerfile = Files.readString(root.resolve("Dockerfile"), StandardCharsets.UTF_8);
        Map<Integer, Integer> ports = exposedPorts(dockerfile);
        List<DeploymentRuntimeSpecification.ManagedVolume> volumes = declaredVolumes(dockerfile, facts.applicationId());
        List<AnalysisEvidence> evidence = new ArrayList<>();
        if (!ports.isEmpty()) {
            evidence.add(evidence("analysis.deployment.runtime.evidence.containerPorts", "Dockerfile#EXPOSE"));
        }
        if (!volumes.isEmpty()) {
            evidence.add(evidence("analysis.deployment.runtime.evidence.containerVolumes", "Dockerfile#VOLUME"));
        }
        List<LocalizedMessage> required = new ArrayList<>(List.of(required("analysis.deployment.runtime.containerEngine"),
                required("analysis.deployment.runtime.health")));
        if (ports.isEmpty()) {
            required.add(required("analysis.deployment.runtime.containerPorts"));
        }
        return suggestion(facts, Map.of(), Optional.empty(), ports, volumes, evidence, required);
    }

    private static DeploymentRuntimeSuggestion suggestion(DeploymentProjectFacts facts,
                                                           Map<DeploymentRuntimeSuggestion.RuntimeInput, String> values,
                                                           Optional<Integer> healthPort,
                                                           Map<Integer, Integer> containerPorts,
                                                           List<DeploymentRuntimeSpecification.ManagedVolume> volumes,
                                                           List<AnalysisEvidence> evidence,
                                                           List<LocalizedMessage> required) {
        return new DeploymentRuntimeSuggestion(facts.projectType(), values, healthPort, containerPorts, volumes, evidence, required);
    }

    private static Optional<Path> singleRootJar(Path root) throws IOException {
        try (var files = Files.list(root)) {
            List<Path> jars = files.filter(DeploymentRuntimeInference::regular)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList();
            return jars.size() == 1 ? Optional.of(jars.getFirst()) : Optional.empty();
        }
    }

    private static Optional<Attributes> readJarManifest(Path jar) {
        try {
            if (Files.size(jar) > MAX_JAR_METADATA_BYTES) {
                return Optional.empty();
            }
            try (JarFile file = new JarFile(jar.toFile(), false)) {
                return file.getManifest() == null ? Optional.empty() : Optional.of(file.getManifest().getMainAttributes());
            }
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static String firstSupportedJavaVersion(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Matcher matcher = Pattern.compile("^\\s*(17|21|22)(?:[._].*)?\\s*$").matcher(candidate);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private static String exactNodeMajor(String value) {
        Matcher matcher = Pattern.compile("^\\s*v?(18|19|20|21|22|23|24)(?:\\.0\\.0)?\\s*$").matcher(value);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static String exactPythonVersion(String value) {
        Matcher matcher = Pattern.compile("^\\s*==?3\\.(10|11|12|13)(?:\\.\\*)?\\s*$").matcher(value);
        return matcher.matches() ? "3." + matcher.group(1) : null;
    }

    private static Optional<String> uniquePythonMainModule(Path root) throws IOException {
        try (var paths = Files.walk(root, 3)) {
            List<String> modules = paths.filter(DeploymentRuntimeInference::regular)
                    .filter(path -> path.getFileName().toString().equals("__main__.py"))
                    .map(root::relativize)
                    .map(DeploymentRuntimeInference::pythonModule)
                    .flatMap(Optional::stream)
                    .distinct().toList();
            return modules.size() == 1 ? Optional.of(modules.getFirst()) : Optional.empty();
        }
    }

    private static Optional<String> pythonModule(Path relativeMain) {
        List<String> parts = new ArrayList<>();
        for (Path segment : relativeMain) {
            String value = segment.toString();
            if (value.equals("__main__.py") || value.equals("src") && parts.isEmpty()) {
                continue;
            }
            if (!value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
                return Optional.empty();
            }
            parts.add(value);
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(".", parts));
    }

    private static Optional<String> configuredViteOutput(Path root) throws IOException {
        for (String name : List.of("vite.config.js", "vite.config.mjs", "vite.config.cjs", "vite.config.ts", "vite.config.mts")) {
            Path config = root.resolve(name);
            if (!regular(config)) {
                continue;
            }
            Matcher matcher = VITE_OUTPUT_DIRECTORY.matcher(Files.readString(config, StandardCharsets.UTF_8));
            if (matcher.find()) {
                String output = matcher.group(1);
                if (!output.equals(".") && !output.contains("..") && !output.startsWith("/")) {
                    return Optional.of(output);
                }
            }
        }
        return Optional.empty();
    }

    private static Map<Integer, Integer> exposedPorts(String dockerfile) {
        LinkedHashSet<Integer> ports = new LinkedHashSet<>();
        Matcher lines = EXPOSE_LINE.matcher(dockerfile);
        while (lines.find()) {
            List<Integer> linePorts = new ArrayList<>();
            for (String token : lines.group(1).trim().split("\\s+")) {
                Matcher port = Pattern.compile("^([0-9]{1,5})(?:/(?:tcp|udp))?$").matcher(token);
                if (!port.matches()) {
                    linePorts.clear();
                    break;
                }
                int value = Integer.parseInt(port.group(1));
                if (value < 1 || value > 65535) {
                    linePorts.clear();
                    break;
                }
                linePorts.add(value);
            }
            ports.addAll(linePorts);
        }
        Map<Integer, Integer> samePort = new java.util.LinkedHashMap<>();
        ports.forEach(port -> samePort.put(port, port));
        return Map.copyOf(samePort);
    }

    private static List<DeploymentRuntimeSpecification.ManagedVolume> declaredVolumes(String dockerfile, String applicationId) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        Matcher matcher = VOLUME_LINE.matcher(dockerfile);
        while (matcher.find()) {
            String path = matcher.group(1);
            if (path.matches("/[A-Za-z0-9._/-]{1,255}") && !path.equals("/") && !path.contains("..") && !path.contains("//")) {
                paths.add(path);
            }
        }
        List<DeploymentRuntimeSpecification.ManagedVolume> volumes = new ArrayList<>();
        int ordinal = 1;
        for (String path : paths) {
            String suffix = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
            if (suffix.isBlank()) {
                suffix = "data";
            }
            String base = "windowstolinux-" + applicationId + "-" + suffix;
            String candidateName = base.length() <= 63 ? base : base.substring(0, 63).replaceAll("-+$", "");
            boolean duplicateName = volumes.stream().map(DeploymentRuntimeSpecification.ManagedVolume::name)
                    .anyMatch(existing -> existing.equals(candidateName));
            String name = candidateName;
            if (duplicateName) {
                name = (name.length() > 61 ? name.substring(0, 61) : name) + "-" + ordinal;
            }
            volumes.add(new DeploymentRuntimeSpecification.ManagedVolume(name, path, false));
            ordinal++;
        }
        return List.copyOf(volumes);
    }

    private static AnalysisEvidence evidence(String key, String source) {
        return new AnalysisEvidence(LocalizedMessage.of(key), source,
                LocalizedMessage.of("analysis.deployment.evidence.detected"), EvidenceConfidence.HIGH);
    }

    private static LocalizedMessage required(String key) {
        return LocalizedMessage.of(key);
    }

    private static boolean regular(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }
}
