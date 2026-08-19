package gold.debug.windowstolinux.shared.analyze.ecosystem.node;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.shared.model.language.LanguageFactKind;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Node.js, JavaScript, and TypeScript facts from paths and package metadata.
 *
 * <p>从路径与包元数据检测 Node.js、JavaScript 和 TypeScript 事实。
 */
public final class NodeLanguageInspector {
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;
    private static final Pattern NODE_ENGINE = Pattern.compile("\\\"node\\\"\\s*:\\s*\\\"([^\\\"\\r\\n]+)\\\"");
    private static final Pattern EXACT_NODE = Pattern.compile("^\\s*v?(18|19|20|21|22|23|24)(?:\\.0\\.0)?\\s*$");

    /** Returns deterministic Node.js ecosystem and source facts. / 返回确定性的 Node.js 生态与源码事实。 */
    public ProjectLanguageFacts inspect(Path root, SourceInspectionFacts source) throws IOException {
        EnumSet<LanguageEcosystemType> ecosystems = EnumSet.noneOf(LanguageEcosystemType.class);
        EnumSet<SourceLanguageType> languages = EnumSet.noneOf(SourceLanguageType.class);
        EnumMap<LanguageFactKind, String> values = new EnumMap<>(LanguageFactKind.class);
        List<AnalysisEvidence> evidence = new ArrayList<>();
        source.relativeFiles().forEach(path -> {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (isJavaScript(name)) {
                ecosystems.add(LanguageEcosystemType.NODE_JS);
                languages.add(SourceLanguageType.JAVASCRIPT);
            }
            if (isTypeScript(name)) {
                ecosystems.add(LanguageEcosystemType.NODE_JS);
                languages.add(SourceLanguageType.TYPESCRIPT);
            }
        });
        Path packageJson = root.resolve("package.json");
        if (Files.isRegularFile(packageJson)) {
            ecosystems.add(LanguageEcosystemType.NODE_JS);
            evidence.add(evidence("analysis.language.nodeMetadata", "package.json"));
            String json = readBounded(packageJson);
            Matcher engine = NODE_ENGINE.matcher(json);
            if (engine.find()) {
                Matcher exact = EXACT_NODE.matcher(engine.group(1));
                if (exact.matches()) {
                    values.put(LanguageFactKind.NODE_MAJOR_VERSION, exact.group(1));
                    evidence.add(evidence("analysis.deployment.runtime.evidence.nodeVersion", "package.json#engines.node"));
                }
            }
        }
        source.relativeFiles().stream().filter(path -> path.getFileName().toString().startsWith("tsconfig")
                && path.getFileName().toString().endsWith(".json")).findFirst()
                .ifPresent(path -> evidence.add(evidence("analysis.language.typeScriptMetadata", path.toString())));
        source.relativeFiles().stream().filter(path -> isJavaScript(path.getFileName().toString().toLowerCase(Locale.ROOT)))
                .findFirst().ifPresent(path -> evidence.add(evidence("analysis.language.javaScriptSource", path.toString())));
        source.relativeFiles().stream().filter(path -> isTypeScript(path.getFileName().toString().toLowerCase(Locale.ROOT)))
                .findFirst().ifPresent(path -> evidence.add(evidence("analysis.language.typeScriptSource", path.toString())));
        return new ProjectLanguageFacts(ecosystems, languages, values, evidence);
    }

    private static String readBounded(Path path) throws IOException {
        if (Files.size(path) > MAX_METADATA_BYTES) {
            throw new IOException("package metadata exceeds the static inspection bound");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static boolean isJavaScript(String name) {
        return name.endsWith(".js") || name.endsWith(".jsx") || name.endsWith(".mjs") || name.endsWith(".cjs");
    }

    private static boolean isTypeScript(String name) {
        return name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".mts") || name.endsWith(".cts");
    }

    private static AnalysisEvidence evidence(String key, String source) {
        return new AnalysisEvidence(LocalizedMessage.of(key), source,
                LocalizedMessage.of("analysis.deployment.evidence.detected"), EvidenceConfidenceLevel.HIGH);
    }
}
