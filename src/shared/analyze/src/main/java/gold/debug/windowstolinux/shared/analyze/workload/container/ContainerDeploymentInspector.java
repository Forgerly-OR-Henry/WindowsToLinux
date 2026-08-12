package gold.debug.windowstolinux.shared.analyze.workload.container;

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
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSuggestion;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects one Dockerfile workload, declared ports, and managed-volume candidates.
 *
 * <p>检测单一 Dockerfile 工作负载、声明端口与受管卷候选项。
 */
public final class ContainerDeploymentInspector implements DeploymentTypeInspector {
    private static final Pattern EXPOSE = Pattern.compile("(?im)^\\s*EXPOSE\\s+([^\\r\\n#]+)$");
    private static final Pattern VOLUME = Pattern.compile(
            "(?im)^\\s*VOLUME\\s+(?:\\[\\s*)?['\\\"]?(/[^\\s,'\\\"\\]]+)['\\\"]?(?:\\s*\\])?\\s*$");

    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.DOCKERFILE_CONTAINER;
    }

    @Override
    public DeploymentTypeInspection inspect(Path root, SourceInspection source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        Path dockerfile = root.resolve("Dockerfile");
        if (!BoundedProjectMetadata.regular(dockerfile)) {
            rejections.add(rejection("DOCKERFILE_MISSING", "analysis.deployment.rejection.dockerfileMissing"));
            return null;
        }
        if (!BoundedProjectMetadata.existingNames(root, "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml")
                .isEmpty()) {
            rejections.add(rejection("MULTI_CONTAINER_COMPOSE_DETECTED", "analysis.deployment.rejection.composeUnsupported"));
            return null;
        }
        String applicationId = BoundedProjectMetadata.rootApplicationId(root);
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, applicationId, projectType(),
                DeploymentBuildTool.CONTAINER_BUILD, languageFacts, List.of(BoundedProjectMetadata.evidence(
                "analysis.deployment.evidence.dockerfile", "Dockerfile", "analysis.deployment.evidence.detected")),
                List.of(), List.of());
        String text = BoundedProjectMetadata.read(dockerfile);
        Map<Integer, Integer> ports = ports(text);
        List<DeploymentRuntimeSpecification.ManagedVolume> volumes = volumes(text, applicationId);
        List<AnalysisEvidence> evidence = new ArrayList<>();
        if (!ports.isEmpty()) {
            evidence.add(BoundedProjectMetadata.evidence("analysis.deployment.runtime.evidence.containerPorts",
                    "Dockerfile#EXPOSE", "analysis.deployment.evidence.detected"));
        }
        if (!volumes.isEmpty()) {
            evidence.add(BoundedProjectMetadata.evidence("analysis.deployment.runtime.evidence.containerVolumes",
                    "Dockerfile#VOLUME", "analysis.deployment.evidence.detected"));
        }
        List<LocalizedMessage> required = new ArrayList<>(List.of(
                BoundedProjectMetadata.required("analysis.deployment.runtime.containerEngine"),
                BoundedProjectMetadata.required("analysis.deployment.runtime.health")));
        if (ports.isEmpty()) {
            required.add(BoundedProjectMetadata.required("analysis.deployment.runtime.containerPorts"));
        }
        DeploymentRuntimeSuggestion suggestion = new DeploymentRuntimeSuggestion(projectType(), Map.of(), Optional.empty(), ports,
                volumes, evidence, required);
        return new DeploymentTypeInspection(facts, suggestion);
    }

    private static Map<Integer, Integer> ports(String dockerfile) {
        LinkedHashSet<Integer> ports = new LinkedHashSet<>();
        Matcher lines = EXPOSE.matcher(dockerfile);
        while (lines.find()) {
            List<Integer> linePorts = new ArrayList<>();
            for (String token : lines.group(1).trim().split("\\s+")) {
                Matcher port = Pattern.compile("^([0-9]{1,5})(?:/(?:tcp|udp))?$").matcher(token);
                if (!port.matches()) {
                    linePorts.clear();
                    break;
                }
                int value = Integer.parseInt(port.group(1));
                if (value < 1 || value > 65535) {
                    linePorts.clear();
                    break;
                }
                linePorts.add(value);
            }
            ports.addAll(linePorts);
        }
        Map<Integer, Integer> samePort = new LinkedHashMap<>();
        ports.forEach(port -> samePort.put(port, port));
        return Map.copyOf(samePort);
    }

    private static List<DeploymentRuntimeSpecification.ManagedVolume> volumes(String dockerfile, String applicationId) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        Matcher matcher = VOLUME.matcher(dockerfile);
        while (matcher.find()) {
            String path = matcher.group(1);
            if (path.matches("/[A-Za-z0-9._/-]{1,255}") && !path.equals("/") && !path.contains("..")
                    && !path.contains("//")) {
                paths.add(path);
            }
        }
        List<DeploymentRuntimeSpecification.ManagedVolume> volumes = new ArrayList<>();
        int ordinal = 1;
        for (String path : paths) {
            String suffix = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
            if (suffix.isBlank()) {
                suffix = "data";
            }
            String base = "windowstolinux-" + applicationId + "-" + suffix;
            String candidate = base.length() <= 63 ? base : base.substring(0, 63).replaceAll("-+$", "");
            boolean duplicate = volumes.stream().map(DeploymentRuntimeSpecification.ManagedVolume::name)
                    .anyMatch(candidate::equals);
            String name = duplicate
                    ? (candidate.length() > 61 ? candidate.substring(0, 61) : candidate) + "-" + ordinal
                    : candidate;
            volumes.add(new DeploymentRuntimeSpecification.ManagedVolume(name, path, false));
            ordinal++;
        }
        return List.copyOf(volumes);
    }

    private static RejectionReason rejection(String code, String key) {
        return new RejectionReason(code, LocalizedMessage.of(key), "deployment");
    }
}
