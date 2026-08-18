package gold.debug.windowstolinux.shared.analyze.workload;

import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspection;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataReader;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
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
    private static final Pattern FROM = Pattern.compile(
            "(?im)^\\s*FROM\\s+(?:--platform=\\S+\\s+)?(\\S+)(?:\\s+AS\\s+(\\S+))?\\s*(?:#.*)?$");

    /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.DOCKERFILE_CONTAINER;
    }

    /** Inspects source facts for this deployment type. / 检查此部署类型的源码事实。 */
    @Override
    public DeploymentTypeInspection inspect(Path root, SourceInspection source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        Path dockerfile = root.resolve("Dockerfile");
        if (!BoundedMetadataReader.regular(dockerfile)) {
            rejections.add(rejection("DOCKERFILE_MISSING", "analysis.deployment.rejection.dockerfileMissing"));
            return null;
        }
        if (!BoundedMetadataReader.existingNames(root, "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml")
                .isEmpty()) {
            rejections.add(rejection("MULTI_CONTAINER_COMPOSE_DETECTED", "analysis.deployment.rejection.composeUnsupported"));
            return null;
        }
        String applicationId = ProjectIdentityResolver.rootApplicationId(root);
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, applicationId, projectType(),
                DeploymentBuildTool.CONTAINER_BUILD, languageFacts, List.of(evidence(
                "analysis.deployment.evidence.dockerfile", "Dockerfile", "analysis.deployment.evidence.detected")),
                List.of(), List.of());
        String text = BoundedMetadataReader.read(dockerfile);
        if (!baseImagesPinned(text)) {
            rejections.add(rejection("CONTAINER_BASE_IMAGE_UNPINNED",
                    "analysis.deployment.rejection.containerBaseImageUnpinned"));
            return null;
        }
        Map<Integer, Integer> ports = ports(text);
        List<DeploymentRuntimeSpecification.ManagedVolume> volumes = volumes(text, applicationId);
        List<AnalysisEvidence> evidence = new ArrayList<>();
        if (!ports.isEmpty()) {
            evidence.add(evidence("analysis.deployment.runtime.evidence.containerPorts",
                    "Dockerfile#EXPOSE", "analysis.deployment.evidence.detected"));
        }
        if (!volumes.isEmpty()) {
            evidence.add(evidence("analysis.deployment.runtime.evidence.containerVolumes",
                    "Dockerfile#VOLUME", "analysis.deployment.evidence.detected"));
        }
        List<LocalizedMessage> required = new ArrayList<>(List.of(
                LocalizedMessage.of("analysis.deployment.runtime.containerEngine"),
                LocalizedMessage.of("analysis.deployment.runtime.health")));
        if (ports.isEmpty()) {
            required.add(LocalizedMessage.of("analysis.deployment.runtime.containerPorts"));
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

    private static boolean baseImagesPinned(String dockerfile) {
        Matcher matcher = FROM.matcher(dockerfile);
        LinkedHashSet<String> stageAliases = new LinkedHashSet<>();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String normalized = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!"scratch".equals(normalized) && !stageAliases.contains(normalized)
                    && !normalized.matches("[^@\\s]+@sha256:[0-9a-f]{64}")) {
                return false;
            }
            if (matcher.group(2) != null && !stageAliases.add(matcher.group(2).toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return found;
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
    private static gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence evidence(String subject, String source, String conclusion) {
        return new gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence(
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(subject), source,
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(conclusion),
                gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence.HIGH);
    }
}
