package gold.debug.windowstolinux.shared.analyze.ecosystem.python;

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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Python source, exact version metadata, and a unique module entrypoint.
 *
 *  <p>检测 Python 源码、精确版本元数据和唯一模块入口。
 */
public final class PythonLanguageInspector {
    /**
     * MAX METADATA BYTES.
     * <p>最大元数据字节。
     */
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;
    /**
     * Pattern recognizing PYTHON VERSION.
     * <p>用于识别PYTHON版本的匹配模式。
     */
    private static final Pattern PYTHON_VERSION = Pattern.compile("(?m)^\\s*requires-python\\s*=\\s*\\\"([^\\\"\\r\\n]+)\\\"\\s*$");
    /**
     * Pattern recognizing exact Python version constraint.
     * <p>用于识别精确 Python 版本约束的匹配模式。
     */
    private static final Pattern EXACT_PYTHON = Pattern.compile("^\\s*(?:==?)?([0-9]{1,9}\\.[0-9]{1,9})(?:\\.[0-9*]+)?(?:[-+][A-Za-z0-9._-]+)?\\s*$");

    /**
     * Returns deterministic Python ecosystem and source facts. / 返回确定性的 Python 生态与源码事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return deterministic Python ecosystem and source facts / 确定性的 Python 生态与源码事实
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public ProjectLanguageFacts inspect(Path root, SourceInspectionFacts source) throws IOException {
        EnumSet<LanguageEcosystemType> ecosystems = EnumSet.noneOf(LanguageEcosystemType.class);
        EnumSet<SourceLanguageType> languages = EnumSet.noneOf(SourceLanguageType.class);
        EnumMap<LanguageFactKind, String> values = new EnumMap<>(LanguageFactKind.class);
        List<AnalysisEvidence> evidence = new ArrayList<>();
        Optional<Path> pythonSource = source.relativeFiles().stream()
                .filter(path -> path.getFileName().toString().endsWith(".py")).findFirst();
        if (pythonSource.isPresent()) {
            ecosystems.add(LanguageEcosystemType.PYTHON);
            languages.add(SourceLanguageType.PYTHON);
            evidence.add(evidence("analysis.language.pythonSource", pythonSource.orElseThrow().toString()));
        }
        Path pyproject = root.resolve("pyproject.toml");
        if (Files.isRegularFile(pyproject)) {
            ecosystems.add(LanguageEcosystemType.PYTHON);
            evidence.add(evidence("analysis.language.pythonMetadata", "pyproject.toml"));
            if (Files.size(pyproject) > MAX_METADATA_BYTES) {
                throw new IOException("Python metadata exceeds the static inspection bound");
            }
            Matcher version = PYTHON_VERSION.matcher(Files.readString(pyproject, StandardCharsets.UTF_8));
            if (version.find()) {
                Matcher exact = EXACT_PYTHON.matcher(version.group(1));
                if (exact.matches()) {
                    values.put(LanguageFactKind.PYTHON_VERSION, exact.group(1));
                    evidence.add(evidence("analysis.deployment.runtime.evidence.pythonVersion", "pyproject.toml#requires-python"));
                }
            }
        }
        List<String> modules = source.relativeFiles().stream()
                .filter(path -> path.getFileName().toString().equals("__main__.py"))
                .map(PythonLanguageInspector::moduleName).flatMap(Optional::stream).distinct().toList();
        if (modules.size() == 1) {
            values.put(LanguageFactKind.PYTHON_ENTRYPOINT, modules.getFirst());
            evidence.add(evidence("analysis.deployment.runtime.evidence.pythonEntrypoint", modules.getFirst() + ".__main__"));
        }
        return new ProjectLanguageFacts(ecosystems, languages, values, evidence);
    }

    /**
     * Converts a relative Python entry path into an importable module name when its segments are valid.
     * <p>相对 Python 入口路径各段有效时，将其转换为可导入模块名。
     *
     * @param relativeMain relative main / 相对主
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
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

    /**
     * Binds a static source observation to its localized conclusion and confidence.
     * <p>将静态源码观测与本地化结论及置信度绑定。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return constructed or resolved analysis evidence / 构造或解析得到的分析证据
     */
    private static AnalysisEvidence evidence(String key, String source) {
        return new AnalysisEvidence(LocalizedMessage.of(key), source,
                LocalizedMessage.of("analysis.deployment.evidence.detected"), EvidenceConfidenceLevel.HIGH);
    }
}
