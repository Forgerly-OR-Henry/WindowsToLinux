package gold.debug.windowstolinux.shared.analyze.language.java;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.LanguageEcosystem;
import gold.debug.windowstolinux.shared.model.project.LanguageFact;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.project.SourceLanguage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Java source and bounded JAR manifest facts without loading a class.
 *
 * <p>在不加载类的情况下检测 Java 源码与有界 JAR 清单事实。
 */
public final class JavaLanguageInspector {
    private static final long MAX_JAR_METADATA_BYTES = 512L * 1024 * 1024;

    /** Returns deterministic Java facts. / 返回确定性的 Java 事实。 */
    public ProjectLanguageFacts inspect(Path root, SourceInspection source) {
        EnumSet<LanguageEcosystem> ecosystems = EnumSet.noneOf(LanguageEcosystem.class);
        EnumSet<SourceLanguage> languages = EnumSet.noneOf(SourceLanguage.class);
        EnumMap<LanguageFact, String> values = new EnumMap<>(LanguageFact.class);
        List<AnalysisEvidence> evidence = new ArrayList<>();
        Optional<Path> javaSource = source.relativeFiles().stream()
                .filter(path -> lower(path).endsWith(".java")).findFirst();
        if (javaSource.isPresent()) {
            ecosystems.add(LanguageEcosystem.JAVA);
            languages.add(SourceLanguage.JAVA);
            evidence.add(evidence("analysis.language.javaSource", javaSource.orElseThrow().toString()));
        }
        List<Path> rootJars = source.relativeFiles().stream()
                .filter(path -> path.getNameCount() == 1 && lower(path).endsWith(".jar")).toList();
        if (rootJars.size() == 1) {
            ecosystems.add(LanguageEcosystem.JAVA);
            Path relativeJar = rootJars.getFirst();
            readManifest(root.resolve(relativeJar)).ifPresent(attributes -> {
                String mainClass = attributes.getValue(Attributes.Name.MAIN_CLASS);
                if (mainClass != null && mainClass.trim().matches("[A-Za-z_$][A-Za-z0-9_$.]{0,255}")) {
                    values.put(LanguageFact.JAVA_MAIN_CLASS, mainClass.trim());
                    evidence.add(evidence("analysis.deployment.runtime.evidence.javaMainClass",
                            relativeJar + "!META-INF/MANIFEST.MF"));
                }
                String version = supportedVersion(attributes.getValue("Build-Jdk-Spec"), attributes.getValue("Build-Jdk"));
                if (version != null) {
                    values.put(LanguageFact.JAVA_VERSION, version);
                    evidence.add(evidence("analysis.deployment.runtime.evidence.javaVersion",
                            relativeJar + "!META-INF/MANIFEST.MF"));
                }
            });
        }
        return new ProjectLanguageFacts(ecosystems, languages, values, evidence);
    }

    private static Optional<Attributes> readManifest(Path jar) {
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

    private static String supportedVersion(String... candidates) {
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

    private static String lower(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static AnalysisEvidence evidence(String key, String source) {
        return new AnalysisEvidence(LocalizedMessage.of(key), source,
                LocalizedMessage.of("analysis.deployment.evidence.detected"), EvidenceConfidence.HIGH);
    }
}
