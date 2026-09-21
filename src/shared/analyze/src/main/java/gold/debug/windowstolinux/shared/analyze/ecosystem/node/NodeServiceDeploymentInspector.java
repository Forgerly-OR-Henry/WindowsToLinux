package gold.debug.windowstolinux.shared.analyze.ecosystem.node;

import gold.debug.windowstolinux.shared.analyze.ecosystem.node.NodeBuildFacts;
import gold.debug.windowstolinux.shared.analyze.ecosystem.node.NodeBuildInspector;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
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

/**
 * Produces Node service build facts and exact-version runtime suggestions.
 *
 *  <p>生成 Node 服务构建事实与精确版本运行时建议。
 */
public final class NodeServiceDeploymentInspector implements DeploymentTypeInspector {
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
        return DeploymentProjectType.NODE_SERVICE;
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
        Optional<NodeBuildFacts> inspected = node.inspect(root);
        if (inspected.isEmpty()) {
            rejections.add(new RejectionReason("NODE_PACKAGE_JSON_MISSING",
                    LocalizedMessage.of("analysis.deployment.rejection.nodePackageMissing"), "deployment"));
            return null;
        }
        NodeBuildFacts project = inspected.orElseThrow();
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        if (project.lockFiles().isEmpty()) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.nodeLockfile"));
        } else if (project.lockFiles().size() > 1) {
            conflicts.add(LocalizedMessage.of("analysis.deployment.conflict.multipleNodeLockfiles"));
        }
        if (!project.hasBuildScript()) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.nodeBuildScript"));
        }
        if (!project.hasStartScript()) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.nodeStartScript"));
        }
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, project.applicationId(), projectType(),
                project.buildTool(), languageFacts, List.of(evidence(
                "analysis.deployment.evidence.nodePackage", "package.json", "analysis.deployment.evidence.detected")),
                conflicts, missing);
        Map<DeploymentRuntimeAssessment.RuntimeInputType, String> values = DeploymentRuntimeAssessment.valuesFor(projectType());
        String nodeVersion = languageFacts.values().get(LanguageFactKind.NODE_MAJOR_VERSION);
        if (nodeVersion != null) {
            values.put(DeploymentRuntimeAssessment.RuntimeInputType.NODE_MAJOR_VERSION, nodeVersion);
        }
        List<LocalizedMessage> required = new ArrayList<>();
        required.add(LocalizedMessage.of("analysis.deployment.runtime.health"));
        if (nodeVersion == null) {
            required.add(LocalizedMessage.of("analysis.deployment.runtime.nodeVersion"));
        }
        DeploymentRuntimeAssessment suggestion = new DeploymentRuntimeAssessment(projectType(), values, Optional.empty(), Map.of(),
                List.of(), languageFacts.evidence().stream().filter(evidence -> evidence.subject().key().equals(
                "analysis.deployment.runtime.evidence.nodeVersion")).toList(), required);
        return new DeploymentTypeAssessment(facts, suggestion);
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
