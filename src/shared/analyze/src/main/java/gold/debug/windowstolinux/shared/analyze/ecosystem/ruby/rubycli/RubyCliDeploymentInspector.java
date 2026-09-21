package gold.debug.windowstolinux.shared.analyze.ecosystem.ruby.rubycli;

import gold.debug.windowstolinux.shared.analyze.service.ServiceInspectionAssembler;
import gold.debug.windowstolinux.shared.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.service.ServiceProjectFacts;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
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

/**
 * Inspects one dependency-free Ruby CLI service without executing Ruby. / 在不执行 Ruby 的情况下检查一个无依赖 Ruby CLI 服务。
 */
public final class RubyCliDeploymentInspector {
    /**
     * METADATA.
     * <p>元数据。
     */
    private static final String METADATA = "windowstolinux-ruby.properties";
    /**
     * Pattern recognizing PROPERTY.
     * <p>用于识别属性的匹配模式。
     */
    private static final Pattern PROPERTY = Pattern.compile("(?m)^([A-Za-z][A-Za-z0-9]*)=([^\\r\\n]+)$");
    /**
     * Pattern recognizing REQUIRE.
     * <p>用于识别要求的匹配模式。
     */
    private static final Pattern REQUIRE = Pattern.compile("(?m)^\\s*require\\s+['\"]([^'\"]+)['\"]");

    /**
     * Inspects fixed Ruby CLI metadata and dependency boundaries. / 检查固定 Ruby CLI 元数据与依赖边界。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param languageFacts language facts / 语言事实
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved deployment type assessment / 构造或解析得到的部署类型评估
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        String metadata = ServiceMetadataInspector.readIfPresent(root.resolve(METADATA));
        if (metadata.isEmpty()) missing.add(METADATA);
        Map<String, String> values = properties(metadata, conflicts);
        String version = values.get("rubyVersion");
        if (version == null || !version.matches("[0-9]+(?:\\.[0-9]+){1,2}(?:[-+][A-Za-z0-9._-]+)?")) { version = null; missing.add("rubyVersion=..."); }
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

    /**
     * Extracts literal source properties and records conflicting duplicate declarations.
     * <p>提取字面源码属性并记录冲突的重复声明。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @param conflicts the observed conflicting facts / 观察到的冲突事实
     * @return literal source properties and records conflicting duplicate declarations / 字面源码属性并记录冲突的重复声明
     */
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

    /**
     * Validates a relative path against the enclosing resource boundary.
     * <p>按所属资源边界验证相对路径。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return relative text; null when no matching value is available / 相对文本；没有匹配值时为 null
     */
    private static String relative(String value) {
        if (value == null) return null;
        value = value.replace('\\', '/');
        return value.matches("[A-Za-z0-9._/-]{1,255}") && !value.startsWith("/") && !value.contains("..")
                && !value.contains("//") ? value : null;
    }
}
