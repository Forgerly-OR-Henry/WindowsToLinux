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
 * <p>区分纯静态内容与由锁文件支持的 Node 静态构建。
 */
public final class StaticWebDeploymentInspector implements DeploymentTypeInspector {
    private static final Pattern VITE_BUILD = Pattern.compile(
            "(?s)\\\"build\\\"\\s*:\\s*\\\"[^\\\"\\r\\n]*\\bvite\\b[^\\\"\\r\\n]*\\\"");
    private static final Pattern VITE_OUTPUT = Pattern.compile(
            "(?m)\\boutDir\\s*:\\s*['\\\"]([A-Za-z0-9._/-]{1,255})['\\\"]");
    private final NodeBuildInspector node = new NodeBuildInspector();

    /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.STATIC_SITE;
    }

    /** Inspects source facts for this deployment type. / 检查此部署类型的源码事实。 */
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
    private static gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence evidence(String subject, String source, String conclusion) {
        return new gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence(
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(subject), source,
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(conclusion),
                gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel.HIGH);
    }
}
