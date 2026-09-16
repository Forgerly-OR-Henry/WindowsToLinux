package gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin.kotlinc;

import gold.debug.windowstolinux.shared.analyze.service.ServiceInspectionAssembler;
import gold.debug.windowstolinux.shared.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.service.ServiceProjectFacts;
import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Inspects dependency-free Kotlin/JVM source with explicit compiler metadata. / 使用显式编译器元数据检查无依赖 Kotlin/JVM 源码。 */
public final class KotlinCompilerDeploymentInspector {
    private static final String METADATA = "windowstolinux-kotlin.properties";
    private static final Pattern PROPERTY = Pattern.compile("(?m)^([A-Za-z][A-Za-z0-9]*)=([^\\r\\n]+)$");
    private static final Pattern MAIN = Pattern.compile("(?m)^\\s*fun\\s+main\\s*\\(");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_$][A-Za-z0-9_$.]*)");
    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)\\s*$");
    private static final Pattern DECLARATION = Pattern.compile(
            "\\b(?:class|interface|object|fun|val|var|typealias)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    /** Inspects one native Kotlin compiler architecture. / 检查一个原生 Kotlin 编译器架构。 */
    public DeploymentTypeAssessment inspect(
            Path root,
            SourceInspectionFacts source,
            ProjectLanguageFacts languageFacts,
            List<RejectionReason> rejections
    ) throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        String metadata = ServiceMetadataInspector.readIfPresent(root.resolve(METADATA));
        if (metadata.isEmpty()) missing.add(METADATA);
        Map<String, String> values = properties(metadata, conflicts);
        String compilerVersion = version(values.get("compilerVersion"));
        String sourceRoot = relative(values.get("sourceRoot"));
        String mainClass = javaName(values.get("mainClass"));
        if (compilerVersion == null) missing.add("compilerVersion=...");
        if (sourceRoot == null) missing.add("sourceRoot=...");
        if (mainClass == null) missing.add("mainClass=...");
        if (gold.debug.windowstolinux.shared.model.toolchain.ToolchainVersion.parse(
                gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.JAVA, values.get("jvmTarget")).isEmpty())
            missing.add("jvmTarget=...");
        List<String> sources = source.relativeFiles().stream().map(path -> path.toString().replace('\\', '/'))
                .filter(path -> path.endsWith(".kt")).sorted().toList();
        if (sources.isEmpty()) missing.add("Kotlin source files");
        if (sourceRoot != null) {
            String prefix = sourceRoot + "/";
            if (sources.stream().anyMatch(path -> !path.startsWith(prefix))) conflicts.add("kotlin-source-outside-root");
        }
        if (source.relativeFiles().stream().map(path -> path.toString().replace('\\', '/')).anyMatch(path ->
                path.equals("build.gradle") || path.equals("build.gradle.kts") || path.equals("gradlew")
                        || path.endsWith(".jar"))) {
            conflicts.add("kotlin-external-build");
        }
        Set<String> localDeclarations = new HashSet<>();
        for (String relative : sources) {
            if (sourceRoot == null || !relative.startsWith(sourceRoot + "/")) continue;
            String kotlin = BoundedMetadataInspector.read(root.resolve(relative));
            Matcher declaredPackage = PACKAGE.matcher(kotlin);
            String prefix = declaredPackage.find() ? declaredPackage.group(1) + "." : "";
            Matcher declarations = DECLARATION.matcher(kotlin);
            while (declarations.find()) localDeclarations.add(prefix + declarations.group(1));
        }
        int mainCount = 0;
        String detectedMainClass = null;
        for (String relative : sources) {
            String kotlin = BoundedMetadataInspector.read(root.resolve(relative));
            if (MAIN.matcher(kotlin).find()) {
                mainCount++;
                detectedMainClass = generatedMainClass(relative, kotlin);
                if (detectedMainClass == null) conflicts.add("kotlin-main-source-name:" + relative);
            }
            Matcher imports = IMPORT.matcher(kotlin);
            while (imports.find()) {
                String imported = imports.group(1);
                if (!(imported.startsWith("kotlin.") || imported.startsWith("java.") || imported.startsWith("javax.")
                        || localDeclarations.stream().anyMatch(declaration -> imported.equals(declaration)
                                || imported.startsWith(declaration + ".") || imported.endsWith(".")
                                && declaration.startsWith(imported) && declaration.lastIndexOf('.') == imported.length() - 1))) {
                    conflicts.add("kotlin-external-import:" + imported);
                }
            }
        }
        if (mainCount != 1) conflicts.add("kotlin-main-count:" + mainCount);
        if (mainCount == 1 && mainClass != null && !mainClass.equals(detectedMainClass)) {
            conflicts.add("kotlin-main-class:" + detectedMainClass);
        }
        KotlinCompilerFacts architecture = new KotlinCompilerFacts(compilerVersion, sourceRoot, mainClass,
                sources, missing, conflicts);
        List<String> shapeMissing = new ArrayList<>(architecture.missingItems());
        shapeMissing.addAll(architecture.conflicts());
        ServiceProjectFacts shape = new ServiceProjectFacts(METADATA, architecture.compilerVersion(),
                ProjectIdentityResolver.rootApplicationId(root), architecture.mainClass(), shapeMissing);
        return ServiceInspectionAssembler.assemble(root, DeploymentProjectType.KOTLIN_SERVICE,
                DeploymentBuildToolType.KOTLINC, languageFacts, shape, false);
    }

    private static Map<String, String> properties(String text, List<String> conflicts) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        Matcher matcher = PROPERTY.matcher(text);
        while (matcher.find()) {
            if (!List.of("compilerVersion", "sourceRoot", "mainClass", "jvmTarget").contains(matcher.group(1))
                    || result.putIfAbsent(matcher.group(1), matcher.group(2).trim()) != null) {
                conflicts.add("kotlin-metadata-property:" + matcher.group(1));
            }
        }
        return Map.copyOf(result);
    }

    private static String version(String value) {
        return value != null && value.matches("[0-9]+(?:\\.[0-9]+){1,2}(?:[-+][A-Za-z0-9._-]+)?") ? value : null;
    }

    private static String relative(String value) {
        if (value == null) return null;
        value = value.replace('\\', '/');
        return value.matches("[A-Za-z0-9._/-]{1,255}") && !value.startsWith("/") && !value.contains("..")
                && !value.contains("//") ? value : null;
    }

    private static String javaName(String value) {
        return value != null && value.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*") ? value : null;
    }

    private static String generatedMainClass(String relative, String source) {
        String file = Path.of(relative).getFileName().toString();
        String stem = file.substring(0, file.length() - ".kt".length());
        if (!stem.matches("[A-Za-z_$][A-Za-z0-9_$]*")) return null;
        Matcher packageName = PACKAGE.matcher(source);
        return packageName.find() ? packageName.group(1) + "." + stem + "Kt" : stem + "Kt";
    }
}
