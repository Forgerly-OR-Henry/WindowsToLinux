package gold.debug.windowstolinux.shared.analyze.workload;

import gold.debug.windowstolinux.shared.analyze.ecosystem.node.NodeBuildFacts;
import gold.debug.windowstolinux.shared.analyze.ecosystem.node.NodeBuildInspector;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;
import gold.debug.windowstolinux.shared.model.language.LanguageFactKind;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Distinguishes pure static content from a lockfile-backed Node static build.
 *
 *  <p>区分纯静态内容与由锁文件支持的 Node 静态构建。
 */
public final class StaticWebDeploymentInspector implements DeploymentTypeInspector {
    /**
     * Pattern recognizing VITE BUILD.
     * <p>用于识别VITE构建的匹配模式。
     */
    private static final Pattern VITE_BUILD = Pattern.compile(
            "(?s)\\\"build\\\"\\s*:\\s*\\\"[^\\\"\\r\\n]*\\bvite\\b[^\\\"\\r\\n]*\\\"");
    /**
     * Pattern recognizing VITE OUTPUT.
     * <p>用于识别VITE输出的匹配模式。
     */
    private static final Pattern VITE_OUTPUT = Pattern.compile(
            "(?m)\\boutDir\\s*:\\s*['\\\"]([A-Za-z0-9._/-]{1,255})['\\\"]");
    /**
     * Bound node build inspector collaborator for node.
     * <p>处理节点的节点构建检查器协作对象。
     */
    private final NodeBuildInspector node = new NodeBuildInspector();

    /**
     * Returns the supported deployment project type. / 返回支持的部署项目类型。
     *
     * @return the supported deployment project type / 支持的部署项目类型
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.STATIC_SITE;
    }

    /**
     * Inspects source facts for this deployment type. / 检查此部署类型的源码事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param languageFacts language facts / 语言事实
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved deployment type assessment; null when no matching value is available / 构造或解析得到的部署类型评估；没有匹配值时为 null
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    @Override
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        boolean hasIndex = BoundedMetadataInspector.regular(root.resolve("index.html"));
        Optional<NodeBuildFacts> nodeProject = node.inspect(root);
        if (!hasIndex && nodeProject.isEmpty()) {
            rejections.add(new RejectionReason("STATIC_SITE_ENTRY_MISSING",
                    LocalizedMessage.of("analysis.deployment.rejection.staticSiteEntryMissing"), "deployment"));
            return null;
        }
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        DeploymentBuildToolType tool = DeploymentBuildToolType.STATIC_SITE_BUILD;
        String applicationId = ProjectIdentityResolver.rootApplicationId(root);
        if (nodeProject.isPresent()) {
            NodeBuildFacts project = nodeProject.orElseThrow();
            applicationId = project.applicationId();
            tool = project.buildTool();
            if (project.lockFiles().isEmpty()) {
                missing.add(LocalizedMessage.of("analysis.deployment.missing.nodeLockfile"));
            } else if (project.lockFiles().size() > 1) {
                conflicts.add(LocalizedMessage.of("analysis.deployment.conflict.multipleNodeLockfiles"));
            }
            if (!project.hasBuildScript()) {
                missing.add(LocalizedMessage.of("analysis.deployment.missing.staticBuildScript"));
            }
        }
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, applicationId, projectType(), tool, languageFacts,
                List.of(evidence("analysis.deployment.evidence.staticSite",
                        nodeProject.isPresent() ? "package.json" : "index.html", "analysis.deployment.evidence.detected")),
                conflicts, missing);
        Map<DeploymentRuntimeAssessment.RuntimeInputType, String> values = DeploymentRuntimeAssessment.valuesFor(projectType());
        List<AnalysisEvidence> runtimeEvidence = new ArrayList<>();
        List<LocalizedMessage> required = new ArrayList<>(List.of(LocalizedMessage.of("analysis.deployment.runtime.health")));
        if (nodeProject.isPresent()) {
            String packageJson = BoundedMetadataInspector.read(root.resolve("package.json"));
            if (VITE_BUILD.matcher(packageJson).find()) {
                String output = viteOutput(root).orElse("dist");
                values.put(DeploymentRuntimeAssessment.RuntimeInputType.STATIC_OUTPUT_DIRECTORY, output);
                runtimeEvidence.add(evidence("analysis.deployment.runtime.evidence.staticOutput",
                        output.equals("dist") ? "package.json#scripts.build" : "vite.config#build.outDir",
                        "analysis.deployment.evidence.detected"));
            }
            String nodeVersion = languageFacts.values().get(LanguageFactKind.NODE_MAJOR_VERSION);
            if (nodeVersion == null) {
                required.add(LocalizedMessage.of("analysis.deployment.runtime.nodeVersion"));
            } else {
                values.put(DeploymentRuntimeAssessment.RuntimeInputType.NODE_MAJOR_VERSION, nodeVersion);
                languageFacts.evidence().stream().filter(item -> item.subject().key().equals(
                        "analysis.deployment.runtime.evidence.nodeVersion")).findFirst().ifPresent(runtimeEvidence::add);
            }
        }
        if (!values.containsKey(DeploymentRuntimeAssessment.RuntimeInputType.STATIC_OUTPUT_DIRECTORY)) {
            required.add(LocalizedMessage.of("analysis.deployment.runtime.staticOutput"));
        }
        DeploymentRuntimeAssessment suggestion = new DeploymentRuntimeAssessment(projectType(), values, Optional.empty(), Map.of(),
                List.of(), runtimeEvidence, required);
        return new DeploymentTypeAssessment(facts, suggestion);
    }

    /**
     * Reads a supported literal output-directory declaration from bounded Vite configuration files.
     * <p>从有界 Vite 配置文件读取受支持的字面输出目录声明。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static Optional<String> viteOutput(Path root) throws IOException {
        for (String name : List.of("vite.config.js", "vite.config.mjs", "vite.config.cjs", "vite.config.ts", "vite.config.mts")) {
            Path config = root.resolve(name);
            if (!BoundedMetadataInspector.regular(config)) {
                continue;
            }
            Matcher matcher = VITE_OUTPUT.matcher(BoundedMetadataInspector.read(config));
            if (matcher.find() && !matcher.group(1).equals(".") && !matcher.group(1).contains("..")
                    && !matcher.group(1).startsWith("/")) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }
    /**
     * Binds a static source observation to its localized conclusion and confidence.
     * <p>将静态源码观测与本地化结论及置信度绑定。
     *
     * @param subject subject / 对象
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param conclusion conclusion / 结论
     * @return constructed or resolved analysis evidence / 构造或解析得到的分析证据
     */
    private static gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence evidence(String subject, String source, String conclusion) {
        return new gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence(
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(subject), source,
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(conclusion),
                gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel.HIGH);
    }
}
