package gold.debug.windowstolinux.shared.analyze.ecosystem.php.phpcli;

import gold.debug.windowstolinux.shared.analyze.service.ServiceInspectionAssembler;
import gold.debug.windowstolinux.shared.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.service.ServiceProjectFacts;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Inspects one dependency-free PHP CLI service without executing PHP. / 在不执行 PHP 的情况下检查一个无依赖 PHP CLI 服务。 */
public final class PhpCliDeploymentInspector {
    private static final String METADATA = "windowstolinux-php.properties";
    private static final Pattern PROPERTY = Pattern.compile("(?m)^([A-Za-z][A-Za-z0-9]*)=([^\\r\\n]+)$");

    /** Inspects fixed PHP CLI metadata and source boundaries. / 检查固定 PHP CLI 元数据与源码边界。 */
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        String metadata = ServiceMetadataInspector.readIfPresent(root.resolve(METADATA));
        if (metadata.isEmpty()) missing.add(METADATA);
        Map<String, String> values = properties(metadata, conflicts);
        String version = values.get("phpVersion");
        if (version == null || !version.matches("8\\.(?:2|3|4)")) { version = null; missing.add("phpVersion=8.x"); }
        String documentRoot = relative(values.get("documentRoot"));
        if (!"public".equals(documentRoot)) { documentRoot = null; missing.add("documentRoot=public"); }
        String entrypoint = relative(values.get("entrypoint"));
        if (!"public/index.php".equals(entrypoint)) { entrypoint = null; missing.add("entrypoint=public/index.php"); }
        if (entrypoint != null && !ServiceMetadataInspector.present(root, entrypoint)) missing.add(entrypoint);
        boolean anyPhp = source.relativeFiles().stream().anyMatch(path -> path.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".php"));
        if (!anyPhp) missing.add("PHP source files");
        if (source.relativeFiles().stream().map(path -> path.toString().replace('\\', '/')).anyMatch(path ->
                path.equals("composer.json") || path.equals("composer.lock") || path.startsWith("vendor/"))) {
            conflicts.add("php-composer-dependency");
        }
        PhpCliFacts facts = new PhpCliFacts(version, documentRoot, entrypoint,
                java.util.stream.Stream.concat(missing.stream(), conflicts.stream()).toList());
        ServiceProjectFacts shape = new ServiceProjectFacts(METADATA, facts.version(), facts.documentRoot(),
                facts.entrypoint(), facts.missingItems());
        return ServiceInspectionAssembler.assemble(root, DeploymentProjectType.PHP_SERVICE,
                DeploymentBuildToolType.PHP_CLI, languageFacts, shape, true);
    }

    private static Map<String, String> properties(String text, List<String> conflicts) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        Matcher matcher = PROPERTY.matcher(text);
        while (matcher.find()) {
            if (!List.of("phpVersion", "documentRoot", "entrypoint").contains(matcher.group(1))
                    || result.putIfAbsent(matcher.group(1), matcher.group(2).trim()) != null) {
                conflicts.add("php-metadata-property:" + matcher.group(1));
            }
        }
        return Map.copyOf(result);
    }

    private static String relative(String value) {
        if (value == null) return null;
        value = value.replace('\\', '/');
        return value.matches("[A-Za-z0-9._/-]{1,255}") && !value.startsWith("/") && !value.contains("..")
                && !value.contains("//") ? value : null;
    }
}
