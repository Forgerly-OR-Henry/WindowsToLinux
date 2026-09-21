package gold.debug.windowstolinux.shared.analyze.source;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel;
import gold.debug.windowstolinux.shared.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Collects first-path evidence using caller-owned language rules. / 按调用方提供的语言规则收集首个路径证据。
 */
public final class SourceLanguageEvidence {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private SourceLanguageEvidence() { }

    /**
     * Applies a marker rule to bounded filenames without reading or executing source. / 对有界文件名应用标记规则，不读取或执行源码。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param ecosystem ecosystem / 生态
     * @param marker marker / 标记
     * @return constructed or resolved project language facts / 构造或解析得到的项目语言事实
     */
    public static ProjectLanguageFacts collect(SourceInspectionFacts source, LanguageEcosystemType ecosystem,
                                              Function<String, SourceLanguageType> marker) {
        EnumMap<SourceLanguageType, Path> firstEvidence = new EnumMap<>(SourceLanguageType.class);
        for (Path path : source.relativeFiles()) {
            SourceLanguageType language = marker.apply(path.getFileName().toString().toLowerCase(Locale.ROOT));
            if (language != null) firstEvidence.putIfAbsent(language, path);
        }
        if (firstEvidence.isEmpty()) return ProjectLanguageFacts.empty();
        List<AnalysisEvidence> evidence = firstEvidence.entrySet().stream().map(entry -> new AnalysisEvidence(
                LocalizedMessage.of("analysis.language.sourceMarker", "language", entry.getKey().name()),
                entry.getValue().toString(), LocalizedMessage.of("analysis.deployment.evidence.detected"),
                EvidenceConfidenceLevel.HIGH)).toList();
        return new ProjectLanguageFacts(Set.of(ecosystem), firstEvidence.keySet(), Map.of(), evidence);
    }
}
