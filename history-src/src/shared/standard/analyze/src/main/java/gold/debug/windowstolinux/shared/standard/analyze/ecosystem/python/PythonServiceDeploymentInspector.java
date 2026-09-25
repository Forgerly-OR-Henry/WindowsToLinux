package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.python;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.LanguageFactKind;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.python.PythonBuildFacts;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.python.PythonBuildInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.standard.analyze.source.SourceInspectionFacts;

/**
 * Produces Python build facts and exact-version/module runtime suggestions.
 *
 *  <p>生成 Python 构建事实与精确版本/模块运行时建议。
 */
public final class PythonServiceDeploymentInspector implements DeploymentTypeInspector {
    /**
     * Bound python build inspector collaborator for python.
     * <p>处理Python 语言的Python构建检查器协作对象。
     */
    private final PythonBuildInspector python = new PythonBuildInspector();

    /**
     * Returns the supported deployment project type. / 返回支持的部署项目类型。
     *
     * @return the supported deployment project type / 支持的部署项目类型
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.PYTHON_SERVICE;
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
        Optional<PythonBuildFacts> inspected = python.inspect(root);
        if (inspected.isEmpty()) {
            rejections.add(new RejectionReason("PYTHON_PYPROJECT_MISSING",
                    LocalizedMessage.of("analysis.deployment.rejection.pythonPyprojectMissing"), "deployment"));
            return null;
        }
        PythonBuildFacts project = inspected.orElseThrow();
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        if (project.lockFiles().isEmpty() && project
                .buildTool() != gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType.PYTHON_STDLIB) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.pythonLockfile"));
        } else if (project.lockFiles().size() > 1) {
            conflicts.add(LocalizedMessage.of("analysis.deployment.conflict.multiplePythonLockfiles"));
        }
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, project.applicationId(), projectType(),
                project.buildTool(), languageFacts, List.of(evidence("analysis.deployment.evidence.pythonProject",
                        "pyproject.toml", "analysis.deployment.evidence.detected")),
                conflicts, missing);
        Map<DeploymentRuntimeAssessment.RuntimeInputType, String> values = DeploymentRuntimeAssessment
                .valuesFor(projectType());
        String version = languageFacts.values().get(LanguageFactKind.PYTHON_VERSION);
        String entrypoint = languageFacts.values().get(LanguageFactKind.PYTHON_ENTRYPOINT);
        if (version != null) {
            values.put(DeploymentRuntimeAssessment.RuntimeInputType.PYTHON_VERSION, version);
        }
        if (entrypoint != null) {
            values.put(DeploymentRuntimeAssessment.RuntimeInputType.PYTHON_ENTRYPOINT, entrypoint);
        }
        List<LocalizedMessage> required = new ArrayList<>(
                List.of(LocalizedMessage.of("analysis.deployment.runtime.health")));
        if (version == null) {
            required.add(LocalizedMessage.of("analysis.deployment.runtime.pythonVersion"));
        }
        if (entrypoint == null) {
            required.add(LocalizedMessage.of("analysis.deployment.runtime.pythonEntrypoint"));
        }
        Set<String> runtimeKeys = Set.of("analysis.deployment.runtime.evidence.pythonVersion",
                "analysis.deployment.runtime.evidence.pythonEntrypoint");
        DeploymentRuntimeAssessment suggestion = new DeploymentRuntimeAssessment(projectType(), values,
                Optional.empty(), Map.of(), List.of(),
                languageFacts.evidence().stream().filter(item -> runtimeKeys.contains(item.subject().key())).toList(),
                required);
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
    private static gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence evidence(String subject,
            String source, String conclusion) {
        return new gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence(
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(subject), source,
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(conclusion),
                gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel.HIGH);
    }
}
