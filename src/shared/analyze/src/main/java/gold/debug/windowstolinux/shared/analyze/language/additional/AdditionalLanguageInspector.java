package gold.debug.windowstolinux.shared.analyze.language.additional;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.LanguageEcosystem;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.project.SourceLanguage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Recognizes additional language candidates from bounded paths and fixed metadata names without evaluating content.
 *
 * <p>通过有界路径与固定元数据名称识别附加语言候选，不求值其内容。
 */
public final class AdditionalLanguageInspector {
    /** Returns all additional language markers without selecting a primary language or executable. / 返回全部附加语言标记，不选择主要语言或可执行文件。 */
    public ProjectLanguageFacts inspect(SourceInspection source) {
        EnumSet<LanguageEcosystem> ecosystems = EnumSet.noneOf(LanguageEcosystem.class);
        EnumSet<SourceLanguage> languages = EnumSet.noneOf(SourceLanguage.class);
        EnumMap<SourceLanguage, Path> firstEvidence = new EnumMap<>(SourceLanguage.class);
        for (Path path : source.relativeFiles()) {
            Marker marker = marker(path);
            if (marker != null) {
                ecosystems.add(marker.ecosystem());
                languages.add(marker.language());
                firstEvidence.putIfAbsent(marker.language(), path);
            }
        }
        List<AnalysisEvidence> evidence = new ArrayList<>();
        firstEvidence.forEach((language, path) -> evidence.add(new AnalysisEvidence(
                LocalizedMessage.of("analysis.language.additionalSource", "language", language.name()),
                path.toString(), LocalizedMessage.of("analysis.deployment.evidence.detected"), EvidenceConfidence.HIGH)));
        return new ProjectLanguageFacts(ecosystems, languages, java.util.Map.of(), evidence);
    }

    private static Marker marker(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".go") || name.equals("go.mod")) return marker(LanguageEcosystem.GO, SourceLanguage.GO);
        if (name.endsWith(".rs") || name.equals("cargo.toml")) return marker(LanguageEcosystem.RUST, SourceLanguage.RUST);
        if (name.endsWith(".cs") || name.endsWith(".csproj")) return marker(LanguageEcosystem.DOTNET, SourceLanguage.CSHARP);
        if (name.endsWith(".kt") || name.endsWith(".kts")) return marker(LanguageEcosystem.KOTLIN, SourceLanguage.KOTLIN);
        if (name.endsWith(".php") || name.equals("composer.json")) return marker(LanguageEcosystem.PHP, SourceLanguage.PHP);
        if (name.endsWith(".rb") || name.equals("gemfile")) return marker(LanguageEcosystem.RUBY, SourceLanguage.RUBY);
        if (name.endsWith(".c") || name.endsWith(".h")) return marker(LanguageEcosystem.NATIVE, SourceLanguage.C);
        if (name.endsWith(".cc") || name.endsWith(".cpp") || name.endsWith(".cxx") || name.endsWith(".hpp"))
            return marker(LanguageEcosystem.NATIVE, SourceLanguage.CPP);
        if (name.endsWith(".scala") || name.equals("build.sbt")) return marker(LanguageEcosystem.ALTERNATIVE_VM, SourceLanguage.SCALA);
        if (name.endsWith(".clj") || name.endsWith(".cljs") || name.endsWith(".cljc") || name.equals("deps.edn"))
            return marker(LanguageEcosystem.ALTERNATIVE_VM, SourceLanguage.CLOJURE);
        if (name.endsWith(".ex") || name.endsWith(".exs") || name.equals("mix.exs"))
            return marker(LanguageEcosystem.ALTERNATIVE_VM, SourceLanguage.ELIXIR);
        if (name.endsWith(".dart") || name.equals("pubspec.yaml")) return marker(LanguageEcosystem.ALTERNATIVE_VM, SourceLanguage.DART);
        if (name.endsWith(".lua")) return marker(LanguageEcosystem.SCRIPT, SourceLanguage.LUA);
        if (name.endsWith(".pl") || name.endsWith(".pm")) return marker(LanguageEcosystem.SCRIPT, SourceLanguage.PERL);
        if (name.endsWith(".swift") || name.equals("package.swift")) return marker(LanguageEcosystem.SWIFT, SourceLanguage.SWIFT);
        if (name.endsWith(".sh")) return marker(LanguageEcosystem.SCRIPT, SourceLanguage.SHELL);
        if (name.endsWith(".html") || name.endsWith(".htm")) return marker(LanguageEcosystem.SCRIPT, SourceLanguage.HTML);
        if (name.equals("dockerfile") || name.equals("containerfile")) return marker(LanguageEcosystem.SCRIPT, SourceLanguage.CONTAINERFILE);
        return null;
    }

    private static Marker marker(LanguageEcosystem ecosystem, SourceLanguage language) {
        return new Marker(ecosystem, language);
    }

    private record Marker(LanguageEcosystem ecosystem, SourceLanguage language) {
    }
}
