package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.java.jdk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.standard.analyze.source.SourceInspectionFacts;

/**
 * Inspects dependency-free Java source with explicit JDK build metadata. / 使用显式 JDK 构建元数据检查无依赖 Java 源码。
 */
public final class JavaJdkDeploymentInspector implements DeploymentTypeInspector {
    /**
     * METADATA.
     * <p>元数据。
     */
    private static final String METADATA = "windowstolinux-java.properties";

    /**
     * Pattern recognizing PROPERTY.
     * <p>用于识别属性的匹配模式。
     */
    private static final Pattern PROPERTY = Pattern.compile("(?m)^([A-Za-z][A-Za-z0-9]*)=([^\\r\\n]+)$");

    /**
     * Pattern recognizing JAVA NAME.
     * <p>用于识别Java名称的匹配模式。
     */
    private static final Pattern JAVA_NAME = Pattern
            .compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /**
     * Pattern recognizing MAIN METHOD.
     * <p>用于识别主METHOD的匹配模式。
     */
    private static final Pattern MAIN_METHOD = Pattern.compile(
            "\\bpublic\\s+static\\s+void\\s+main\\s*\\(\\s*String\\s*(?:\\[\\]|\\.\\.\\.)\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\)");

    /**
     * Pattern recognizing IMPORT.
     * <p>用于识别导入的匹配模式。
     */
    private static final Pattern IMPORT = Pattern
            .compile("(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z_$][A-Za-z0-9_$.]*)(?:\\.\\*)?\\s*;");

    /**
     * Pattern recognizing PACKAGE.
     * <p>用于识别软件包的匹配模式。
     */
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;");

    /**
     * Returns the Java source project type. / 返回 Java 源码项目类型。
     *
     * @return the Java source project type /  Java 源码项目类型
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.JAVA_SOURCE;
    }

    /**
     * Inspects explicit source-root, main-class, version, and dependency boundaries. / 检查显式源码根、主类、版本与依赖边界。
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
        String javaVersion = gold.debug.windowstolinux.shared.model.toolchain.ToolchainVersion
                .parse(gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.JAVA,
                        values.get("javaVersion"))
                .map(gold.debug.windowstolinux.shared.model.toolchain.ToolchainVersion::branch).orElse(null);
        if (sourceRoot == null)
            missing.add("sourceRoot=...");
        if (mainClass == null)
            missing.add("mainClass=...");
        if (javaVersion == null)
            missing.add("javaVersion=...");

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
        if (source.relativeFiles().stream().map(path -> path.toString().replace('\\', '/')).anyMatch(
                path -> path.equals("pom.xml") || path.equals("build.gradle") || path.equals("build.gradle.kts")
                        || path.equals("module-info.java") || path.endsWith(".jar"))) {
            conflicts.add("java-external-build-or-module");
        }
        Set<String> localTypes = new HashSet<>();
        for (String relative : javaFiles) {
            if (sourceRoot == null || !relative.startsWith(sourceRoot + "/"))
                continue;
            Matcher declaredPackage = PACKAGE.matcher(BoundedMetadataInspector.read(root.resolve(relative)));
            String filename = Path.of(relative).getFileName().toString();
            String name = filename.substring(0, filename.length() - ".java".length());
            localTypes.add(declaredPackage.find() ? declaredPackage.group(1) + "." + name : name);
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
                if (!(imported.startsWith("java.") || imported.startsWith("javax.") || imported.startsWith("jdk.")
                        || imported.startsWith("com.sun.net.httpserver.")
                        || localTypes.stream()
                                .anyMatch(type -> imported.equals(type) || imported.startsWith(type + ".")
                                        || imported.endsWith(".") && type.startsWith(imported)
                                                && type.lastIndexOf('.') == imported.length() - 1))) {
                    conflicts.add("java-external-import:" + imported);
                }
            }
        }
        if (mainMethods != 1) {
            conflicts.add("java-main-count:" + mainMethods);
        }
        JavaJdkFacts architecture = new JavaJdkFacts(value(sourceRoot), value(mainClass), value(javaVersion), javaFiles,
                missing, conflicts);
        List<LocalizedMessage> localizedMissing = architecture.missingItems().stream()
                .map(item -> LocalizedMessage.of("analysis.service.missingFile", "file", item)).toList();
        List<LocalizedMessage> localizedConflicts = architecture.conflicts().stream()
                .map(item -> LocalizedMessage.of("analysis.java.jdk.conflict", "detail", item)).toList();
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, ProjectIdentityResolver.rootApplicationId(root),
                projectType(), DeploymentBuildToolType.JDK, languageFacts, List.of(evidence(METADATA)),
                localizedConflicts, localizedMissing);
        Map<DeploymentRuntimeAssessment.RuntimeInputType, String> runtimeValues = new EnumMap<>(
                DeploymentRuntimeAssessment.RuntimeInputType.class);
        if (sourceRoot != null)
            runtimeValues.put(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_SOURCE_ROOT, sourceRoot);
        if (mainClass != null)
            runtimeValues.put(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_MAIN_CLASS, mainClass);
        if (javaVersion != null)
            runtimeValues.put(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_VERSION, javaVersion);
        List<LocalizedMessage> required = new ArrayList<>(
                List.of(LocalizedMessage.of("analysis.deployment.runtime.health")));
        if (sourceRoot == null)
            required.add(LocalizedMessage.of("analysis.java.jdk.sourceRoot"));
        if (mainClass == null)
            required.add(LocalizedMessage.of("analysis.deployment.runtime.javaMainClass"));
        return new DeploymentTypeAssessment(facts, new DeploymentRuntimeAssessment(projectType(), runtimeValues,
                Optional.empty(), Map.of(), List.of(), facts.evidence(), required));
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

    /**
     * Validates and produces safe path for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的安全路径。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return safe path text; null when no matching value is available / 安全路径文本；没有匹配值时为 null
     */
    private static String safePath(String value) {
        if (value == null)
            return null;
        value = value.trim().replace('\\', '/');
        return value.matches("[A-Za-z0-9._/-]{1,255}") && !value.startsWith("/") && !value.contains("..")
                && !value.contains("//") ? value : null;
    }

    /**
     * Returns a trimmed valid Java name, or null when the input is absent or invalid.
     * <p>返回去除首尾空白的有效 Java 名称；输入缺失或无效时返回 null。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return a trimmed valid Java name, or null when the input is absent or invalid / 去除首尾空白的有效 Java 名称；输入缺失或无效时返回 null
     */
    private static String javaName(String value) {
        return value != null && JAVA_NAME.matcher(value.trim()).matches() ? value.trim() : null;
    }

    /**
     * Replaces a null metadata value with an empty string.
     * <p>将 null 元数据值替换为空字符串。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return value text / 内容文本
     */
    private static String value(String value) {
        return value == null ? "" : value;
    }

    /**
     * Binds a static source observation to its localized conclusion and confidence.
     * <p>将静态源码观测与本地化结论及置信度绑定。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return constructed or resolved analysis evidence / 构造或解析得到的分析证据
     */
    private static AnalysisEvidence evidence(String source) {
        return new AnalysisEvidence(LocalizedMessage.of("analysis.java.jdk.metadata"), source,
                LocalizedMessage.of("analysis.deployment.evidence.detected"), EvidenceConfidenceLevel.HIGH);
    }
}
