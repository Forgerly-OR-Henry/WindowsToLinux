package gold.debug.windowstolinux.shared.model.language;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;

/**
 * Deterministic language facts collected without executing source or choosing a primary language.
 *
 *  <p>在不执行源码且不选择主要语言的情况下收集的确定性语言事实。
 *
 * @param ecosystems observed language ecosystems / 观察到的语言生态
 * @param sourceLanguages observed source languages / 观察到的源码语言
 * @param values exact deterministic language values / 精确的确定性语言值
 * @param evidence bounded evidence for the observations / 观测结果的有界证据
 */
public record ProjectLanguageFacts(Set<LanguageEcosystemType> ecosystems, Set<SourceLanguageType> sourceLanguages,
        Map<LanguageFactKind, String> values, List<AnalysisEvidence> evidence) {
    /**
     * Creates immutable language facts. / 创建不可变语言事实。
     *
     * @param ecosystems observed language ecosystems / 观察到的语言生态
     * @param sourceLanguages observed source languages / 观察到的源码语言
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Returns an empty fact set. / 返回空事实集。
     *
     * @return an empty fact set / 空事实集
     */
    public static ProjectLanguageFacts empty() {
        return new ProjectLanguageFacts(Set.of(), Set.of(), Map.of(), List.of());
    }

    /**
     * Merges non-conflicting observations from independent inspectors. / 合并各独立检查器中不冲突的观测。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @return constructed or resolved project language facts / 构造或解析得到的项目语言事实
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Copies enum facts into an immutable set, preserving an empty input as an empty set.
     * <p>将枚举事实复制到不可变集合，并将空输入保留为空集合。
     *
     * @param <E> enum type of the observed facts / 观测事实的枚举类型
     * @param source enum facts to copy / 待复制的枚举事实
     * @param type declared enum type token; the current copy operation does not inspect it / 声明的枚举类型令牌；当前复制操作不读取该令牌
     * @return immutable copy of the enum facts / 枚举事实的不可变副本
     * @throws NullPointerException if the source set or a contained fact is null / 源集合或其中事实为 null 时
     */
    private static <E extends Enum<E>> Set<E> immutableEnumSet(Set<E> source, Class<E> type) {
        Objects.requireNonNull(source, "source");
        return source.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(source));
    }
}
