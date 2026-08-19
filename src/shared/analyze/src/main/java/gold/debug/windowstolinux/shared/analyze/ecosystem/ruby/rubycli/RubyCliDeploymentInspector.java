package gold.debug.windowstolinux.shared.analyze.ecosystem.ruby.rubycli;

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

/** Inspects one dependency-free Ruby CLI service without executing Ruby. / 在不执行 Ruby 的情况下检查一个无依赖 Ruby CLI 服务。 */
public final class RubyCliDeploymentInspector {
    private static final String METADATA = "windowstolinux-ruby.properties";
    private static final Pattern PROPERTY = Pattern.compile("(?m)^([A-Za-z][A-Za-z0-9]*)=([^\\r\\n]+)$");
    private static final Pattern REQUIRE = Pattern.compile("(?m)^\\s*require\\s+['\"]([^'\"]+)['\"]");

    /** Inspects fixed Ruby CLI metadata and dependency boundaries. / 检查固定 Ruby CLI 元数据与依赖边界。 */
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        String metadata = ServiceMetadataInspector.readIfPresent(root.resolve(METADATA));
        if (metadata.isEmpty()) missing.add(METADATA);
        Map<String, String> values = properties(metadata, conflicts);
        String version = values.get("rubyVersion");
        if (version == null || !version.matches("3\\.(?:2|3|4)(?:\\.[0-9]+)?")) { version = null; missing.add("rubyVersion=3.x"); }
        String entrypoint = relative(values.get("entrypoint"));
        if (entrypoint == null || !entrypoint.endsWith(".rb")) { entrypoint = null; missing.add("entrypoint=*.rb"); }
        if (entrypoint != null && !ServiceMetadataInspector.present(root, entrypoint)) missing.add(entrypoint);
        List<String> rubyFiles = source.relativeFiles().stream().map(path -> path.toString().replace('\\', '/'))
                .filter(path -> path.endsWith(".rb")).toList();
        if (rubyFiles.isEmpty()) missing.add("Ruby source files");
        if (source.relativeFiles().stream().map(path -> path.toString().replace('\\', '/')).anyMatch(path ->
                path.equals("Gemfile") || path.equals("Gemfile.lock") || path.startsWith("vendor/bundle/"))) {
            conflicts.add("ruby-bundler-dependency");
        }
        for (String relative : rubyFiles) {
            String ruby = ServiceMetadataInspector.readIfPresent(root.resolve(relative));
            Matcher matcher = REQUIRE.matcher(ruby);
            while (matcher.find()) {
                String required = matcher.group(1);
                if (!(required.equals("socket") || required.equals("webrick") || required.equals("json")
                        || required.equals("uri") || required.equals("net/http"))) {
                    conflicts.add("ruby-external-require:" + required);
                }
            }
        }
        RubyCliFacts facts = new RubyCliFacts(version, entrypoint,
                java.util.stream.Stream.concat(missing.stream(), conflicts.stream()).toList());
        ServiceProjectFacts shape = new ServiceProjectFacts(METADATA, facts.version(), "source",
                facts.entrypoint(), facts.missingItems());
        return ServiceInspectionAssembler.assemble(root, DeploymentProjectType.RUBY_SERVICE,
                DeploymentBuildToolType.RUBY_CLI, languageFacts, shape, true);
    }

    private static Map<String, String> properties(String text, List<String> conflicts) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        Matcher matcher = PROPERTY.matcher(text);
        while (matcher.find()) {
            if (!List.of("rubyVersion", "entrypoint").contains(matcher.group(1))
                    || result.putIfAbsent(matcher.group(1), matcher.group(2).trim()) != null) {
                conflicts.add("ruby-metadata-property:" + matcher.group(1));
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
