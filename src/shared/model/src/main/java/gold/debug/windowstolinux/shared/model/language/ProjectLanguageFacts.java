package gold.debug.windowstolinux.shared.model.language;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic language facts collected without executing source or choosing a primary language.
 *
 * <p>在不执行源码且不选择主要语言的情况下收集的确定性语言事实。
 *
 * @param ecosystems observed language ecosystems / 观察到的语言生态
 * @param sourceLanguages observed source languages / 观察到的源码语言
 * @param values exact deterministic language values / 精确的确定性语言值
 * @param evidence bounded evidence for the observations / 观测结果的有界证据
 */
public record ProjectLanguageFacts(
        Set<LanguageEcosystemType> ecosystems,
        Set<SourceLanguageType> sourceLanguages,
        Map<LanguageFactKind, String> values,
        List<AnalysisEvidence> evidence
) {
    /** Creates immutable language facts. / 创建不可变语言事实。 */
    public ProjectLanguageFacts {
        ecosystems = immutableEnumSet(ecosystems, LanguageEcosystemType.class);
        sourceLanguages = immutableEnumSet(sourceLanguages, SourceLanguageType.class);
        EnumMap<LanguageFactKind, String> copiedValues = new EnumMap<>(LanguageFactKind.class);
        Objects.requireNonNull(values, "values").forEach((key, value) -> {
            Objects.requireNonNull(key, "language fact");
            if (value == null || value.isBlank() || value.length() > 255) {
                throw new IllegalArgumentException("language fact values must be bounded nonblank text");
            }
            copiedValues.put(key, value);
        });
        values = Map.copyOf(copiedValues);
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }

    /** Returns an empty fact set. / 返回空事实集。 */
    public static ProjectLanguageFacts empty() {
        return new ProjectLanguageFacts(Set.of(), Set.of(), Map.of(), List.of());
    }

    /** Merges non-conflicting observations from independent inspectors. / 合并各独立检查器中不冲突的观测。 */
    public static ProjectLanguageFacts merge(ProjectLanguageFacts... facts) {
        EnumSet<LanguageEcosystemType> ecosystems = EnumSet.noneOf(LanguageEcosystemType.class);
        EnumSet<SourceLanguageType> languages = EnumSet.noneOf(SourceLanguageType.class);
        EnumMap<LanguageFactKind, String> values = new EnumMap<>(LanguageFactKind.class);
        List<AnalysisEvidence> evidence = new ArrayList<>();
        for (ProjectLanguageFacts fact : facts) {
            fact = Objects.requireNonNull(fact, "fact");
            ecosystems.addAll(fact.ecosystems());
            languages.addAll(fact.sourceLanguages());
            fact.values().forEach((key, value) -> {
                String previous = values.putIfAbsent(key, value);
                if (previous != null && !previous.equals(value)) {
                    values.remove(key);
                }
            });
            evidence.addAll(fact.evidence());
        }
        return new ProjectLanguageFacts(ecosystems, languages, values, evidence);
    }

    private static <E extends Enum<E>> Set<E> immutableEnumSet(Set<E> source, Class<E> type) {
        Objects.requireNonNull(source, "source");
        return source.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(source));
    }
}
