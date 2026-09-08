package gold.debug.windowstolinux.shared.analyze.ecosystem.java.jar;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel;
import gold.debug.windowstolinux.shared.model.language.LanguageFactKind;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

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

/** Reads bounded JAR runtime metadata without loading classes. / 读取有界 JAR 运行元数据，不加载类。 */
public final class JavaJarManifestInspector {
    private static final long MAX_JAR_METADATA_BYTES = 512L * 1024 * 1024;

    /** Returns only manifest-backed entrypoint and version facts. / 仅返回清单支持的入口类与版本事实。 */
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

    private static Optional<Attributes> readManifest(Path jar) {
        try {
            if (Files.size(jar) > MAX_JAR_METADATA_BYTES) return Optional.empty();
            try (JarFile file = new JarFile(jar.toFile(), false)) {
                var manifest = file.getManifest();
                return manifest == null ? Optional.empty() : Optional.of(manifest.getMainAttributes());
            }
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static String supportedVersion(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null) continue;
            Matcher matcher = Pattern.compile("^\\s*(17|21|22)(?:[._].*)?\\s*$").matcher(candidate);
            if (matcher.matches()) return matcher.group(1);
        }
        return null;
    }

    private static AnalysisEvidence evidence(String key, Path relativeJar) {
        return new AnalysisEvidence(LocalizedMessage.of(key), relativeJar + "!META-INF/MANIFEST.MF",
                LocalizedMessage.of("analysis.deployment.evidence.detected"), EvidenceConfidenceLevel.HIGH);
    }
}
