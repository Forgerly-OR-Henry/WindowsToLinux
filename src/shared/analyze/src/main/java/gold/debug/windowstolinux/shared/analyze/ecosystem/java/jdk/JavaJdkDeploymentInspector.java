package gold.debug.windowstolinux.shared.analyze.ecosystem.java.jdk;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Inspects dependency-free Java source with explicit JDK build metadata. / 使用显式 JDK 构建元数据检查无依赖 Java 源码。 */
public final class JavaJdkDeploymentInspector implements DeploymentTypeInspector {
    private static final String METADATA = "windowstolinux-java.properties";
    private static final Pattern PROPERTY = Pattern.compile("(?m)^([A-Za-z][A-Za-z0-9]*)=([^\\r\\n]+)$");
    private static final Pattern JAVA_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern MAIN_METHOD = Pattern.compile(
            "\\bpublic\\s+static\\s+void\\s+main\\s*\\(\\s*String\\s*(?:\\[\\]|\\.\\.\\.)\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\)");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z_$][A-Za-z0-9_$.]*)(?:\\.\\*)?\\s*;");

    /** Returns the Java source project type. / 返回 Java 源码项目类型。 */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.JAVA_SOURCE;
    }

    /** Inspects explicit source-root, main-class, version, and dependency boundaries. / 检查显式源码根、主类、版本与依赖边界。 */
    @Override
    public DeploymentTypeAssessment inspect(
            Path root,
            SourceInspectionFacts source,
            ProjectLanguageFacts languageFacts,
            List<RejectionReason> rejections
    ) throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Path metadata = root.resolve(METADATA);
        String text = BoundedMetadataInspector.regular(metadata) ? BoundedMetadataInspector.read(metadata) : "";
        if (text.isEmpty()) {
            missing.add(METADATA);
        }
        Map<String, String> values = properties(text, conflicts);
        String sourceRoot = safePath(values.get("sourceRoot"));
        String mainClass = javaName(values.get("mainClass"));
        String javaVersion = "21".equals(values.get("javaVersion")) ? "21" : null;
        if (sourceRoot == null) missing.add("sourceRoot=...");
        if (mainClass == null) missing.add("mainClass=...");
        if (javaVersion == null) missing.add("javaVersion=21");

        List<String> javaFiles = source.relativeFiles().stream().map(path -> path.toString().replace('\\', '/'))
                .filter(path -> path.endsWith(".java")).sorted().toList();
        if (javaFiles.isEmpty()) {
            missing.add("Java source files");
        }
        if (sourceRoot != null) {
            String prefix = sourceRoot.endsWith("/") ? sourceRoot : sourceRoot + "/";
            if (javaFiles.stream().anyMatch(path -> !path.startsWith(prefix))) {
                conflicts.add("java-source-outside-root");
            }
            if (mainClass != null) {
                String expectedMain = prefix + mainClass.replace('.', '/') + ".java";
                if (!javaFiles.contains(expectedMain)) {
                    missing.add(expectedMain);
                }
            }
        }
        if (source.relativeFiles().stream().map(path -> path.toString().replace('\\', '/')).anyMatch(path ->
                path.equals("pom.xml") || path.equals("build.gradle") || path.equals("build.gradle.kts")
                        || path.equals("module-info.java") || path.endsWith(".jar"))) {
            conflicts.add("java-external-build-or-module");
        }
        int mainMethods = 0;
        for (String relative : javaFiles) {
            String java = BoundedMetadataInspector.read(root.resolve(relative));
            if (MAIN_METHOD.matcher(java).find()) {
                mainMethods++;
            }
            Matcher imports = IMPORT.matcher(java);
            while (imports.find()) {
                String imported = imports.group(1);
                if (!(imported.startsWith("java.") || imported.startsWith("javax.") || imported.startsWith("jdk."))) {
                    conflicts.add("java-external-import:" + imported);
                }
            }
        }
        if (mainMethods != 1) {
            conflicts.add("java-main-count:" + mainMethods);
        }
        JavaJdkFacts architecture = new JavaJdkFacts(value(sourceRoot), value(mainClass), value(javaVersion),
                javaFiles, missing, conflicts);
        List<LocalizedMessage> localizedMissing = architecture.missingItems().stream()
                .map(item -> LocalizedMessage.of("analysis.service.missingFile", "file", item)).toList();
        List<LocalizedMessage> localizedConflicts = architecture.conflicts().stream()
                .map(item -> LocalizedMessage.of("analysis.java.jdk.conflict", "detail", item)).toList();
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, ProjectIdentityResolver.rootApplicationId(root),
                projectType(), DeploymentBuildToolType.JDK, languageFacts,
                List.of(evidence(METADATA)), localizedConflicts, localizedMissing);
        Map<DeploymentRuntimeAssessment.RuntimeInputType, String> runtimeValues =
                new EnumMap<>(DeploymentRuntimeAssessment.RuntimeInputType.class);
        if (sourceRoot != null) runtimeValues.put(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_SOURCE_ROOT, sourceRoot);
        if (mainClass != null) runtimeValues.put(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_MAIN_CLASS, mainClass);
        if (javaVersion != null) runtimeValues.put(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_VERSION, javaVersion);
        List<LocalizedMessage> required = new ArrayList<>(List.of(LocalizedMessage.of("analysis.deployment.runtime.health")));
        if (sourceRoot == null) required.add(LocalizedMessage.of("analysis.java.jdk.sourceRoot"));
        if (mainClass == null) required.add(LocalizedMessage.of("analysis.deployment.runtime.javaMainClass"));
        return new DeploymentTypeAssessment(facts, new DeploymentRuntimeAssessment(projectType(), runtimeValues,
                Optional.empty(), Map.of(), List.of(), facts.evidence(), required));
    }

    private static Map<String, String> properties(String text, List<String> conflicts) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        Matcher matcher = PROPERTY.matcher(text);
        while (matcher.find()) {
            if (!List.of("sourceRoot", "mainClass", "javaVersion").contains(matcher.group(1))
                    || values.putIfAbsent(matcher.group(1), matcher.group(2).trim()) != null) {
                conflicts.add("java-metadata-property:" + matcher.group(1));
            }
        }
        return Map.copyOf(values);
    }

    private static String safePath(String value) {
        if (value == null) return null;
        value = value.trim().replace('\\', '/');
        return value.matches("[A-Za-z0-9._/-]{1,255}") && !value.startsWith("/") && !value.contains("..")
                && !value.contains("//") ? value : null;
    }

    private static String javaName(String value) {
        return value != null && JAVA_NAME.matcher(value.trim()).matches() ? value.trim() : null;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static AnalysisEvidence evidence(String source) {
        return new AnalysisEvidence(LocalizedMessage.of("analysis.java.jdk.metadata"), source,
                LocalizedMessage.of("analysis.deployment.evidence.detected"), EvidenceConfidenceLevel.HIGH);
    }
}
