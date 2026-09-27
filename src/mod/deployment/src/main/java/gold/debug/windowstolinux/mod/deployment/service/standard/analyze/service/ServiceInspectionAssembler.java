package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.EvidenceConfidenceLevel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.message.LocalizedMessage;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentRuntimeAssessment;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.ProjectIdentityResolver;

/**
 * Assembles language-neutral service facts and runtime suggestions. / 组装语言无关的服务事实与运行时建议。
 */
public final class ServiceInspectionAssembler {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ServiceInspectionAssembler() {
    }

    /**
     * Assembles one service inspection without interpreting language metadata. / 在不解释语言元数据的情况下组装一次服务检查。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param buildTool the fixed build entrypoint / 固定构建入口
     * @param languageFacts language facts / 语言事实
     * @param shape shape / 形态
     * @param requiresServicePort requires service port / 要求集合服务端口
     * @return constructed or resolved deployment type assessment / 构造或解析得到的部署类型评估
     */
    public static DeploymentTypeAssessment assemble(Path root, DeploymentProjectKind projectType,
            DeploymentBuildToolType buildTool, ProjectLanguageFacts languageFacts, ServiceProjectFacts shape,
            boolean requiresServicePort) {
        return assemble(root, ProjectIdentityResolver.rootName(root), projectType, buildTool, languageFacts, shape,
                requiresServicePort);
    }

    /** Assembles service facts with an explicit reviewed root-name fallback. / 使用明确审阅的根名称回退组装服务事实。
     * @param root physical source root / 物理源码根
     * @param logicalRootName reviewed source name / 经审阅源码名称
     * @param projectType reviewed project type / 经审阅项目类型
     * @param buildTool fixed build tool / 固定构建工具
     * @param languageFacts language facts / 语言事实
     * @param shape service shape / 服务形态
     * @param requiresServicePort whether a service port is required / 是否要求服务端口
     * @return typed service assessment / 类型化服务评估 */
    public static DeploymentTypeAssessment assemble(Path root, String logicalRootName,
            DeploymentProjectKind projectType, DeploymentBuildToolType buildTool, ProjectLanguageFacts languageFacts,
            ServiceProjectFacts shape, boolean requiresServicePort) {
        List<LocalizedMessage> missing = new ArrayList<>();
        shape.missingFiles()
                .forEach(file -> missing.add(LocalizedMessage.of("analysis.service.missingFile", "file", file)));
        if (shape.version() == null) {
            missing.add(LocalizedMessage.of("analysis.service.missingVersion"));
        }
        if (shape.artifactName() == null) {
            missing.add(LocalizedMessage.of("analysis.service.missingArtifact"));
        }
        if (shape.entrypoint() == null) {
            missing.add(LocalizedMessage.of("analysis.service.missingEntrypoint"));
        }
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root,
                ProjectIdentityResolver.rootApplicationId(logicalRootName), projectType, buildTool, languageFacts,
                List.of(evidence("analysis.service.metadata", shape.primaryMetadata(),
                        "analysis.deployment.evidence.detected")),
                List.of(), missing);
        Map<DeploymentRuntimeAssessment.RuntimeInputType, String> values = new EnumMap<>(
                DeploymentRuntimeAssessment.RuntimeInputType.class);
        if (shape.version() != null) {
            values.put(DeploymentRuntimeAssessment.RuntimeInputType.SERVICE_VERSION, shape.version());
        }
        if (shape.artifactName() != null) {
            values.put(DeploymentRuntimeAssessment.RuntimeInputType.SERVICE_ARTIFACT, shape.artifactName());
        }
        if (shape.entrypoint() != null) {
            values.put(DeploymentRuntimeAssessment.RuntimeInputType.SERVICE_ENTRYPOINT, shape.entrypoint());
        }
        List<LocalizedMessage> required = new ArrayList<>();
        required.add(LocalizedMessage.of("analysis.deployment.runtime.health"));
        if (requiresServicePort) {
            required.add(LocalizedMessage.of("analysis.service.servicePort"));
        }
        if (shape.version() == null) {
            required.add(LocalizedMessage.of("analysis.service.missingVersion"));
        }
        if (shape.artifactName() == null) {
            required.add(LocalizedMessage.of("analysis.service.missingArtifact"));
        }
        if (shape.entrypoint() == null) {
            required.add(LocalizedMessage.of("analysis.service.missingEntrypoint"));
        }
        return new DeploymentTypeAssessment(facts, new DeploymentRuntimeAssessment(projectType, values,
                Optional.empty(), Map.of(), List.of(), facts.evidence(), required));
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
    private static AnalysisEvidence evidence(String subject, String source, String conclusion) {
        return new AnalysisEvidence(LocalizedMessage.of(subject), source, LocalizedMessage.of(conclusion),
                EvidenceConfidenceLevel.HIGH);
    }
}
