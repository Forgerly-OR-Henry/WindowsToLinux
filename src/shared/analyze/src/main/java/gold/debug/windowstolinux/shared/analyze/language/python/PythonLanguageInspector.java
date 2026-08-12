package gold.debug.windowstolinux.shared.analyze.language.python;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.LanguageEcosystem;
import gold.debug.windowstolinux.shared.model.project.LanguageFact;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.project.SourceLanguage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Python source, exact version metadata, and a unique module entrypoint.
 *
 * <p>检测 Python 源码、精确版本元数据和唯一模块入口。
 */
public final class PythonLanguageInspector {
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;
    private static final Pattern PYTHON_VERSION = Pattern.compile("(?m)^\\s*requires-python\\s*=\\s*\\\"([^\\\"\\r\\n]+)\\\"\\s*$");
    private static final Pattern EXACT_PYTHON = Pattern.compile("^\\s*==?3\\.(10|11|12|13)(?:\\.\\*)?\\s*$");

    /** Returns deterministic Python ecosystem and source facts. / 返回确定性的 Python 生态与源码事实。 */
    public ProjectLanguageFacts inspect(Path root, SourceInspection source) throws IOException {
        EnumSet<LanguageEcosystem> ecosystems = EnumSet.noneOf(LanguageEcosystem.class);
        EnumSet<SourceLanguage> languages = EnumSet.noneOf(SourceLanguage.class);
        EnumMap<LanguageFact, String> values = new EnumMap<>(LanguageFact.class);
        List<AnalysisEvidence> evidence = new ArrayList<>();
        Optional<Path> pythonSource = source.relativeFiles().stream()
                .filter(path -> path.getFileName().toString().endsWith(".py")).findFirst();
        if (pythonSource.isPresent()) {
            ecosystems.add(LanguageEcosystem.PYTHON);
            languages.add(SourceLanguage.PYTHON);
            evidence.add(evidence("analysis.language.pythonSource", pythonSource.orElseThrow().toString()));
        }
        Path pyproject = root.resolve("pyproject.toml");
        if (Files.isRegularFile(pyproject)) {
            ecosystems.add(LanguageEcosystem.PYTHON);
            evidence.add(evidence("analysis.language.pythonMetadata", "pyproject.toml"));
            if (Files.size(pyproject) > MAX_METADATA_BYTES) {
                throw new IOException("Python metadata exceeds the static inspection bound");
            }
            Matcher version = PYTHON_VERSION.matcher(Files.readString(pyproject, StandardCharsets.UTF_8));
            if (version.find()) {
                Matcher exact = EXACT_PYTHON.matcher(version.group(1));
                if (exact.matches()) {
                    values.put(LanguageFact.PYTHON_VERSION, "3." + exact.group(1));
                    evidence.add(evidence("analysis.deployment.runtime.evidence.pythonVersion", "pyproject.toml#requires-python"));
                }
            }
        }
        List<String> modules = source.relativeFiles().stream()
                .filter(path -> path.getFileName().toString().equals("__main__.py"))
                .map(PythonLanguageInspector::moduleName).flatMap(Optional::stream).distinct().toList();
        if (modules.size() == 1) {
            values.put(LanguageFact.PYTHON_ENTRYPOINT, modules.getFirst());
            evidence.add(evidence("analysis.deployment.runtime.evidence.pythonEntrypoint", modules.getFirst() + ".__main__"));
        }
        return new ProjectLanguageFacts(ecosystems, languages, values, evidence);
    }

    private static Optional<String> moduleName(Path relativeMain) {
        List<String> parts = new ArrayList<>();
        for (Path segment : relativeMain) {
            String value = segment.toString();
            if (value.equals("__main__.py") || value.equals("src") && parts.isEmpty()) {
                continue;
            }
            if (!value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
                return Optional.empty();
            }
            parts.add(value);
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(".", parts));
    }

    private static AnalysisEvidence evidence(String key, String source) {
        return new AnalysisEvidence(LocalizedMessage.of(key), source,
                LocalizedMessage.of("analysis.deployment.evidence.detected"), EvidenceConfidence.HIGH);
    }
}
