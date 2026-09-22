package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.java.jar;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.LanguageFactKind;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.standard.analyze.source.SourceInspectionFacts;

/**
 * Produces Java JAR facts and manifest-backed runtime suggestions without loading the archive.
 *
 *  <p>在不加载归档的情况下生成 Java JAR 事实与清单依据运行时建议。
 */
public final class JavaJarDeploymentInspector implements DeploymentTypeInspector {
    /**
     * Bound java jar manifest inspector collaborator for manifest inspector.
     * <p>处理清单检查器的JavaJar清单检查器协作对象。
     */
    private final JavaJarManifestInspector manifestInspector = new JavaJarManifestInspector();

    /**
     * Returns the supported deployment project type. / 返回支持的部署项目类型。
     *
     * @return the supported deployment project type / 支持的部署项目类型
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.JAVA_JAR;
    }

    /**
     * Inspects source facts for this deployment type. / 检查此部署类型的源码事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param languageFacts language facts / 语言事实
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved deployment type assessment / 构造或解析得到的部署类型评估
     */
    @Override
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
            List<RejectionReason> rejections) {
        List<Path> jars = source.relativeFiles().stream().filter(path -> path.getNameCount() == 1)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList();
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        if (jars.isEmpty()) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.javaJar"));
        } else if (jars.size() > 1) {
            conflicts.add(LocalizedMessage.of("analysis.deployment.conflict.multipleJavaJars"));
        }
        if (jars.size() == 1) {
            languageFacts = ProjectLanguageFacts.merge(languageFacts, manifestInspector.inspect(root, jars.getFirst()));
        }
        String evidenceSource = jars.isEmpty() ? "source root" : jars.getFirst().toString();
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, ProjectIdentityResolver.rootApplicationId(root),
                projectType(), DeploymentBuildToolType.JAVA, languageFacts,
                List.of(evidence("analysis.deployment.evidence.javaJar", evidenceSource,
                        jars.isEmpty()
                                ? "analysis.deployment.evidence.notDetected"
                                : "analysis.deployment.evidence.detected")),
                conflicts, missing);
        Map<DeploymentRuntimeAssessment.RuntimeInputType, String> values = DeploymentRuntimeAssessment
                .valuesFor(projectType());
        List<AnalysisEvidence> runtimeEvidence = new ArrayList<>();
        if (jars.size() == 1) {
            values.put(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_JAR_PATH,
                    jars.getFirst().toString().replace('\\', '/'));
            runtimeEvidence.add(evidence("analysis.deployment.runtime.evidence.javaJarPath", jars.getFirst().toString(),
                    "analysis.deployment.evidence.detected"));
        }
        put(values, DeploymentRuntimeAssessment.RuntimeInputType.JAVA_MAIN_CLASS, languageFacts,
                LanguageFactKind.JAVA_MAIN_CLASS);
        put(values, DeploymentRuntimeAssessment.RuntimeInputType.JAVA_VERSION, languageFacts,
                LanguageFactKind.JAVA_VERSION);
        Set<String> keys = Set.of("analysis.deployment.runtime.evidence.javaMainClass",
                "analysis.deployment.runtime.evidence.javaVersion");
        runtimeEvidence
                .addAll(languageFacts.evidence().stream().filter(item -> keys.contains(item.subject().key())).toList());
        List<LocalizedMessage> required = new ArrayList<>(
                List.of(LocalizedMessage.of("analysis.deployment.runtime.health")));
        if (!values.containsKey(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_MAIN_CLASS)) {
            required.add(LocalizedMessage.of("analysis.deployment.runtime.javaMainClass"));
        }
        if (!values.containsKey(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_VERSION)) {
            required.add(LocalizedMessage.of("analysis.deployment.runtime.javaVersion"));
        }
        DeploymentRuntimeAssessment suggestion = new DeploymentRuntimeAssessment(projectType(), values,
                Optional.empty(), Map.of(), List.of(), runtimeEvidence, required);
        return new DeploymentTypeAssessment(facts, suggestion);
    }

    /**
     * Copies an available language fact into the corresponding runtime input slot.
     * <p>将可用语言事实复制到对应运行输入位置。
     *
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param fact fact / 事实
     */
    private static void put(Map<DeploymentRuntimeAssessment.RuntimeInputType, String> target,
            DeploymentRuntimeAssessment.RuntimeInputType input, ProjectLanguageFacts facts, LanguageFactKind fact) {
        String value = facts.values().get(fact);
        if (value != null) {
            target.put(input, value);
        }
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
