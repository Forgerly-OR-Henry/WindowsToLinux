package gold.debug.windowstolinux.shared.analyze.workload.staticweb;

import gold.debug.windowstolinux.shared.analyze.build.node.NodeProjectInspection;
import gold.debug.windowstolinux.shared.analyze.build.node.NodeProjectInspector;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentTypeInspection;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedProjectMetadata;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSuggestion;
import gold.debug.windowstolinux.shared.model.project.LanguageFact;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;

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
    private final NodeProjectInspector node = new NodeProjectInspector();

    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.STATIC_SITE;
    }

    @Override
    public DeploymentTypeInspection inspect(Path root, SourceInspection source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        boolean hasIndex = BoundedProjectMetadata.regular(root.resolve("index.html"));
        Optional<NodeProjectInspection> nodeProject = node.inspect(root);
        if (!hasIndex && nodeProject.isEmpty()) {
            rejections.add(new RejectionReason("STATIC_SITE_ENTRY_MISSING",
                    LocalizedMessage.of("analysis.deployment.rejection.staticSiteEntryMissing"), "deployment"));
            return null;
        }
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        DeploymentBuildTool tool = DeploymentBuildTool.STATIC_SITE_BUILD;
        String applicationId = BoundedProjectMetadata.rootApplicationId(root);
        if (nodeProject.isPresent()) {
            NodeProjectInspection project = nodeProject.orElseThrow();
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
                List.of(BoundedProjectMetadata.evidence("analysis.deployment.evidence.staticSite",
                        nodeProject.isPresent() ? "package.json" : "index.html", "analysis.deployment.evidence.detected")),
                conflicts, missing);
        Map<DeploymentRuntimeSuggestion.RuntimeInput, String> values = DeploymentRuntimeSuggestion.valuesFor(projectType());
        List<AnalysisEvidence> runtimeEvidence = new ArrayList<>();
        List<LocalizedMessage> required = new ArrayList<>(List.of(BoundedProjectMetadata.required("analysis.deployment.runtime.health")));
        if (nodeProject.isPresent()) {
            String packageJson = BoundedProjectMetadata.read(root.resolve("package.json"));
            if (VITE_BUILD.matcher(packageJson).find()) {
                String output = viteOutput(root).orElse("dist");
                values.put(DeploymentRuntimeSuggestion.RuntimeInput.STATIC_OUTPUT_DIRECTORY, output);
                runtimeEvidence.add(BoundedProjectMetadata.evidence("analysis.deployment.runtime.evidence.staticOutput",
                        output.equals("dist") ? "package.json#scripts.build" : "vite.config#build.outDir",
                        "analysis.deployment.evidence.detected"));
            }
            String nodeVersion = languageFacts.values().get(LanguageFact.NODE_MAJOR_VERSION);
            if (nodeVersion == null) {
                required.add(BoundedProjectMetadata.required("analysis.deployment.runtime.nodeVersion"));
            } else {
                values.put(DeploymentRuntimeSuggestion.RuntimeInput.NODE_MAJOR_VERSION, nodeVersion);
                languageFacts.evidence().stream().filter(item -> item.subject().key().equals(
                        "analysis.deployment.runtime.evidence.nodeVersion")).findFirst().ifPresent(runtimeEvidence::add);
            }
        }
        if (!values.containsKey(DeploymentRuntimeSuggestion.RuntimeInput.STATIC_OUTPUT_DIRECTORY)) {
            required.add(BoundedProjectMetadata.required("analysis.deployment.runtime.staticOutput"));
        }
        DeploymentRuntimeSuggestion suggestion = new DeploymentRuntimeSuggestion(projectType(), values, Optional.empty(), Map.of(),
                List.of(), runtimeEvidence, required);
        return new DeploymentTypeInspection(facts, suggestion);
    }

    private static Optional<String> viteOutput(Path root) throws IOException {
        for (String name : List.of("vite.config.js", "vite.config.mjs", "vite.config.cjs", "vite.config.ts", "vite.config.mts")) {
            Path config = root.resolve(name);
            if (!BoundedProjectMetadata.regular(config)) {
                continue;
            }
            Matcher matcher = VITE_OUTPUT.matcher(BoundedProjectMetadata.read(config));
            if (matcher.find() && !matcher.group(1).equals(".") && !matcher.group(1).contains("..")
                    && !matcher.group(1).startsWith("/")) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }
}
