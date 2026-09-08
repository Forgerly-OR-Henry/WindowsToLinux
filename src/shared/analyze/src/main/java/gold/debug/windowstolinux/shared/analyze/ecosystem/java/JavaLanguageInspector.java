package gold.debug.windowstolinux.shared.analyze.ecosystem.java;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Detects Java source and JAR ecosystem markers without opening an archive.
 *
 * <p>识别 Java 源码与 JAR 生态标记，不打开归档。
 */
public final class JavaLanguageInspector {
    /** Returns deterministic Java facts. / 返回确定性的 Java 事实。 */
    public ProjectLanguageFacts inspect(SourceInspectionFacts source) {
        EnumSet<LanguageEcosystemType> ecosystems = EnumSet.noneOf(LanguageEcosystemType.class);
        EnumSet<SourceLanguageType> languages = EnumSet.noneOf(SourceLanguageType.class);
        List<AnalysisEvidence> evidence = new ArrayList<>();
        Optional<Path> javaSource = source.relativeFiles().stream()
                .filter(path -> lower(path).endsWith(".java")).findFirst();
        if (javaSource.isPresent()) {
            ecosystems.add(LanguageEcosystemType.JAVA);
            languages.add(SourceLanguageType.JAVA);
            evidence.add(evidence("analysis.language.javaSource", javaSource.orElseThrow().toString()));
        }
        List<Path> rootJars = source.relativeFiles().stream()
                .filter(path -> path.getNameCount() == 1 && lower(path).endsWith(".jar")).toList();
        if (rootJars.size() == 1) {
            ecosystems.add(LanguageEcosystemType.JAVA);
        }
        return new ProjectLanguageFacts(ecosystems, languages, Map.of(), evidence);
    }

    private static String lower(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static AnalysisEvidence evidence(String key, String source) {
        return new AnalysisEvidence(LocalizedMessage.of(key), source,
                LocalizedMessage.of("analysis.deployment.evidence.detected"), EvidenceConfidenceLevel.HIGH);
    }
}
