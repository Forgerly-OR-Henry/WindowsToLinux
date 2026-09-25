package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.kotlin.kotlinc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.standard.analyze.service.ServiceInspectionAssembler;
import gold.debug.windowstolinux.shared.standard.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.standard.analyze.service.ServiceProjectFacts;
import gold.debug.windowstolinux.shared.standard.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.standard.analyze.source.SourceInspectionFacts;

/**
 * Inspects dependency-free Kotlin/JVM source with explicit compiler metadata. / 使用显式编译器元数据检查无依赖 Kotlin/JVM 源码。
 */
public final class KotlinCompilerDeploymentInspector {
    /**
     * METADATA.
     * <p>元数据。
     */
    private static final String METADATA = "windowstolinux-kotlin.properties";

    /**
     * Pattern recognizing PROPERTY.
     * <p>用于识别属性的匹配模式。
     */
    private static final Pattern PROPERTY = Pattern.compile("(?m)^([A-Za-z][A-Za-z0-9]*)=([^\\r\\n]+)$");

    /**
     * Pattern recognizing MAIN.
     * <p>用于识别主的匹配模式。
     */
    private static final Pattern MAIN = Pattern.compile("(?m)^\\s*fun\\s+main\\s*\\(");

    /**
     * Pattern recognizing IMPORT.
     * <p>用于识别导入的匹配模式。
     */
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_$][A-Za-z0-9_$.]*)");

    /**
     * Pattern recognizing PACKAGE.
     * <p>用于识别软件包的匹配模式。
     */
    private static final Pattern PACKAGE = Pattern
            .compile("(?m)^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)\\s*$");

    /**
     * Pattern recognizing DECLARATION.
     * <p>用于识别声明的匹配模式。
     */
    private static final Pattern DECLARATION = Pattern
            .compile("\\b(?:class|interface|object|fun|val|var|typealias)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    /**
     * Inspects one native Kotlin compiler architecture. / 检查一个原生 Kotlin 编译器架构。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param languageFacts language facts / 语言事实
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved deployment type assessment / 构造或解析得到的部署类型评估
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
            List<RejectionReason> rejections) throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        String metadata = ServiceMetadataInspector.readIfPresent(root.resolve(METADATA));
        if (metadata.isEmpty())
            missing.add(METADATA);
        Map<String, String> values = properties(metadata, conflicts);
        String compilerVersion = version(values.get("compilerVersion"));
        String sourceRoot = relative(values.get("sourceRoot"));
        String mainClass = javaName(values.get("mainClass"));
        if (compilerVersion == null)
            missing.add("compilerVersion=...");
        if (sourceRoot == null)
            missing.add("sourceRoot=...");
        if (mainClass == null)
            missing.add("mainClass=...");
        if (gold.debug.windowstolinux.shared.model.toolchain.ToolchainVersion
                .parse(gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.JAVA,
                        values.get("jvmTarget"))
                .isEmpty())
            missing.add("jvmTarget=...");
        List<String> sources = source.relativeFiles().stream().map(path -> path.toString().replace('\\', '/'))
                .filter(path -> path.endsWith(".kt")).sorted().toList();
        if (sources.isEmpty())
            missing.add("Kotlin source files");
        if (sourceRoot != null) {
            String prefix = sourceRoot + "/";
            if (sources.stream().anyMatch(path -> !path.startsWith(prefix)))
                conflicts.add("kotlin-source-outside-root");
        }
        if (source.relativeFiles().stream().map(path -> path.toString().replace('\\', '/'))
                .anyMatch(path -> path.equals("build.gradle") || path.equals("build.gradle.kts")
                        || path.equals("gradlew") || path.endsWith(".jar"))) {
            conflicts.add("kotlin-external-build");
        }
        Set<String> localDeclarations = new HashSet<>();
        for (String relative : sources) {
            if (sourceRoot == null || !relative.startsWith(sourceRoot + "/"))
                continue;
            String kotlin = BoundedMetadataInspector.read(root.resolve(relative));
            Matcher declaredPackage = PACKAGE.matcher(kotlin);
            String prefix = declaredPackage.find() ? declaredPackage.group(1) + "." : "";
            Matcher declarations = DECLARATION.matcher(kotlin);
            while (declarations.find())
                localDeclarations.add(prefix + declarations.group(1));
        }
        int mainCount = 0;
        String detectedMainClass = null;
        for (String relative : sources) {
            String kotlin = BoundedMetadataInspector.read(root.resolve(relative));
            if (MAIN.matcher(kotlin).find()) {
                mainCount++;
                detectedMainClass = generatedMainClass(relative, kotlin);
                if (detectedMainClass == null)
                    conflicts.add("kotlin-main-source-name:" + relative);
            }
            Matcher imports = IMPORT.matcher(kotlin);
            while (imports.find()) {
                String imported = imports.group(1);
                if (!(imported.startsWith("kotlin.") || imported.startsWith("java.") || imported.startsWith("javax.")
                        || localDeclarations.stream()
                                .anyMatch(declaration -> imported.equals(declaration)
                                        || imported.startsWith(declaration + ".")
                                        || imported.endsWith(".") && declaration.startsWith(imported)
                                                && declaration.lastIndexOf('.') == imported.length() - 1))) {
                    conflicts.add("kotlin-external-import:" + imported);
                }
            }
        }
        if (mainCount != 1)
            conflicts.add("kotlin-main-count:" + mainCount);
        if (mainCount == 1 && mainClass != null && !mainClass.equals(detectedMainClass)) {
            conflicts.add("kotlin-main-class:" + detectedMainClass);
        }
        KotlinCompilerFacts architecture = new KotlinCompilerFacts(compilerVersion, sourceRoot, mainClass, sources,
                missing, conflicts);
        List<String> shapeMissing = new ArrayList<>(architecture.missingItems());
        shapeMissing.addAll(architecture.conflicts());
        ServiceProjectFacts shape = new ServiceProjectFacts(METADATA, architecture.compilerVersion(),
                ProjectIdentityResolver.rootApplicationId(root), architecture.mainClass(), shapeMissing);
        return ServiceInspectionAssembler.assemble(root, DeploymentProjectType.KOTLIN_SERVICE,
                DeploymentBuildToolType.KOTLINC, languageFacts, shape, false);
    }

    /**
     * Extracts literal source properties and records conflicting duplicate declarations.
     * <p>提取字面源码属性并记录冲突的重复声明。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @param conflicts the observed conflicting facts / 观察到的冲突事实
     * @return literal source properties and records conflicting duplicate declarations / 字面源码属性并记录冲突的重复声明
     */
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

    /**
     * Accepts the supported numeric compiler-version syntax and returns null for invalid input.
     * <p>接受受支持的数字编译器版本语法，并对无效输入返回 null。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return version text / 版本文本
     */
    private static String version(String value) {
        return value != null && value.matches("[0-9]+(?:\\.[0-9]+){1,2}(?:[-+][A-Za-z0-9._-]+)?") ? value : null;
    }

    /**
     * Validates a relative path against the enclosing resource boundary.
     * <p>按所属资源边界验证相对路径。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return relative text; null when no matching value is available / 相对文本；没有匹配值时为 null
     */
    private static String relative(String value) {
        if (value == null)
            return null;
        value = value.replace('\\', '/');
        return value.matches("[A-Za-z0-9._/-]{1,255}") && !value.startsWith("/") && !value.contains("..")
                && !value.contains("//") ? value : null;
    }

    /**
     * Returns a syntactically valid Java qualified name, or null otherwise.
     * <p>返回语法有效的 Java 限定名，否则返回 null。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return a syntactically valid Java qualified name, or null otherwise / 语法有效的 Java 限定名，否则返回 null
     */
    private static String javaName(String value) {
        return value != null && value.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
                ? value
                : null;
    }

    /**
     * Derives the Kotlin file's generated JVM main-class name from its file stem and package declaration.
     * <p>根据 Kotlin 文件名主体及包声明派生生成的 JVM 主类名。
     *
     * @param relative relative / 相对
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return generated main class text; null when no matching value is available / 已生成主类文本；没有匹配值时为 null
     */
    private static String generatedMainClass(String relative, String source) {
        String file = Path.of(relative).getFileName().toString();
        String stem = file.substring(0, file.length() - ".kt".length());
        if (!stem.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
            return null;
        Matcher packageName = PACKAGE.matcher(source);
        return packageName.find() ? packageName.group(1) + "." + stem + "Kt" : stem + "Kt";
    }
}
