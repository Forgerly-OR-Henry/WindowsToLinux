package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.java.jar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel;
import gold.debug.windowstolinux.shared.model.language.LanguageFactKind;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

/**
 * Reads bounded JAR runtime metadata without loading classes. / 读取有界 JAR 运行元数据，不加载类。
 */
public final class JavaJarManifestInspector {
    /**
     * MAX JAR METADATA BYTES.
     * <p>最大JAR元数据字节。
     */
    private static final long MAX_JAR_METADATA_BYTES = 512L * 1024 * 1024;

    /**
     * Returns only manifest-backed entrypoint and version facts. / 仅返回清单支持的入口类与版本事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param relativeJar relative jar / 相对Jar
     * @return only manifest-backed entrypoint and version facts / 仅返回清单支持的入口类与版本事实
     */
    public ProjectLanguageFacts inspect(Path root, Path relativeJar) {
        EnumMap<LanguageFactKind, String> values = new EnumMap<>(LanguageFactKind.class);
        var evidence = new ArrayList<AnalysisEvidence>();
        readManifest(root.resolve(relativeJar)).ifPresent(attributes -> {
            String mainClass = attributes.getValue(Attributes.Name.MAIN_CLASS);
            if (mainClass != null && mainClass.trim().matches("[A-Za-z_$][A-Za-z0-9_$.]{0,255}")) {
                values.put(LanguageFactKind.JAVA_MAIN_CLASS, mainClass.trim());
                evidence.add(evidence("analysis.deployment.runtime.evidence.javaMainClass", relativeJar));
            }
            String version = supportedVersion(attributes.getValue("Build-Jdk-Spec"), attributes.getValue("Build-Jdk"));
            if (version != null) {
                values.put(LanguageFactKind.JAVA_VERSION, version);
                evidence.add(evidence("analysis.deployment.runtime.evidence.javaVersion", relativeJar));
            }
        });
        return new ProjectLanguageFacts(Set.of(), Set.of(), values, evidence);
    }

    /**
     * Reads validated ownership or backup inventory document.
     * <p>读取已验证归属或备份资源清单文档。
     *
     * @param jar jar / JAR 制品
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private static Optional<Attributes> readManifest(Path jar) {
        try {
            if (Files.size(jar) > MAX_JAR_METADATA_BYTES)
                return Optional.empty();
            try (JarFile file = new JarFile(jar.toFile(), false)) {
                var manifest = file.getManifest();
                return manifest == null ? Optional.empty() : Optional.of(manifest.getMainAttributes());
            }
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    /**
     * Selects the first candidate that parses as a supported Java toolchain version.
     * <p>选择第一个可解析为受支持 Java 工具链版本的候选值。
     *
     * @param candidates candidates / 候选集合
     * @return the first candidate that parses as a supported Java toolchain version; null when no matching value is available / 第一个可解析为受支持 Java 工具链版本的候选值；没有匹配值时为 null
     */
    private static String supportedVersion(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null)
                continue;
            var version = gold.debug.windowstolinux.shared.model.toolchain.ToolchainVersion
                    .parse(gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.JAVA, candidate);
            if (version.isPresent())
                return version.orElseThrow().branch();
        }
        return null;
    }

    /**
     * Binds a static source observation to its localized conclusion and confidence.
     * <p>将静态源码观测与本地化结论及置信度绑定。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param relativeJar relative jar / 相对Jar
     * @return constructed or resolved analysis evidence / 构造或解析得到的分析证据
     */
    private static AnalysisEvidence evidence(String key, Path relativeJar) {
        return new AnalysisEvidence(LocalizedMessage.of(key), relativeJar + "!META-INF/MANIFEST.MF",
                LocalizedMessage.of("analysis.deployment.evidence.detected"), EvidenceConfidenceLevel.HIGH);
    }
}
