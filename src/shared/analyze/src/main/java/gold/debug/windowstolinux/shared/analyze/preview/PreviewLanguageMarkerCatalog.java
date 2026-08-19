package gold.debug.windowstolinux.shared.analyze.preview;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Collects declared language markers that remain available to recognition. from bounded paths and fixed metadata names without evaluating content.
 *
 * <p>通过有界路径与固定元数据名称收集仍可用于识别的声明式语言标记，不求值其内容。
 */
public final class PreviewLanguageMarkerCatalog {
    /** Returns all declared language markers without selecting a primary language or executable. / 返回全部附加语言标记，不选择主要语言或可执行文件。 */
    public ProjectLanguageFacts inspect(SourceInspectionFacts source) {
        EnumSet<LanguageEcosystemType> ecosystems = EnumSet.noneOf(LanguageEcosystemType.class);
        EnumSet<SourceLanguageType> languages = EnumSet.noneOf(SourceLanguageType.class);
        EnumMap<SourceLanguageType, Path> firstEvidence = new EnumMap<>(SourceLanguageType.class);
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
                LocalizedMessage.of("analysis.language.previewSource", "language", language.name()),
                path.toString(), LocalizedMessage.of("analysis.deployment.evidence.detected"), EvidenceConfidenceLevel.HIGH)));
        return new ProjectLanguageFacts(ecosystems, languages, java.util.Map.of(), evidence);
    }

    private static Marker marker(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".go") || name.equals("go.mod")) return marker(LanguageEcosystemType.GO, SourceLanguageType.GO);
        if (name.endsWith(".rs") || name.equals("cargo.toml")) return marker(LanguageEcosystemType.RUST, SourceLanguageType.RUST);
        if (name.endsWith(".cs") || name.endsWith(".csproj")) return marker(LanguageEcosystemType.DOTNET, SourceLanguageType.CSHARP);
        if (name.endsWith(".kt") || name.endsWith(".kts")) return marker(LanguageEcosystemType.KOTLIN, SourceLanguageType.KOTLIN);
        if (name.endsWith(".php") || name.equals("composer.json")) return marker(LanguageEcosystemType.PHP, SourceLanguageType.PHP);
        if (name.endsWith(".rb") || name.equals("gemfile")) return marker(LanguageEcosystemType.RUBY, SourceLanguageType.RUBY);
        if (name.endsWith(".c") || name.endsWith(".h")) return marker(LanguageEcosystemType.NATIVE, SourceLanguageType.C);
        if (name.endsWith(".cc") || name.endsWith(".cpp") || name.endsWith(".cxx") || name.endsWith(".hpp"))
            return marker(LanguageEcosystemType.NATIVE, SourceLanguageType.CPP);
        if (name.endsWith(".scala") || name.equals("build.sbt")) return marker(LanguageEcosystemType.ALTERNATIVE_VM, SourceLanguageType.SCALA);
        if (name.endsWith(".clj") || name.endsWith(".cljs") || name.endsWith(".cljc") || name.equals("deps.edn"))
            return marker(LanguageEcosystemType.ALTERNATIVE_VM, SourceLanguageType.CLOJURE);
        if (name.endsWith(".ex") || name.endsWith(".exs") || name.equals("mix.exs"))
            return marker(LanguageEcosystemType.ALTERNATIVE_VM, SourceLanguageType.ELIXIR);
        if (name.endsWith(".dart") || name.equals("pubspec.yaml")) return marker(LanguageEcosystemType.ALTERNATIVE_VM, SourceLanguageType.DART);
        if (name.endsWith(".lua")) return marker(LanguageEcosystemType.SCRIPT, SourceLanguageType.LUA);
        if (name.endsWith(".pl") || name.endsWith(".pm")) return marker(LanguageEcosystemType.SCRIPT, SourceLanguageType.PERL);
        if (name.endsWith(".swift") || name.equals("package.swift")) return marker(LanguageEcosystemType.SWIFT, SourceLanguageType.SWIFT);
        if (name.endsWith(".sh")) return marker(LanguageEcosystemType.SCRIPT, SourceLanguageType.SHELL);
        if (name.endsWith(".html") || name.endsWith(".htm")) return marker(LanguageEcosystemType.SCRIPT, SourceLanguageType.HTML);
        if (name.equals("dockerfile") || name.equals("containerfile")) return marker(LanguageEcosystemType.SCRIPT, SourceLanguageType.CONTAINERFILE);
        return null;
    }

    private static Marker marker(LanguageEcosystemType ecosystem, SourceLanguageType language) {
        return new Marker(ecosystem, language);
    }

    private record Marker(LanguageEcosystemType ecosystem, SourceLanguageType language) {
    }
}
