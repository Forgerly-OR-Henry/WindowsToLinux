package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.shared.deploy.input.DeploymentRuntimeParser;

import gold.debug.windowstolinux.shared.model.deployment.DatabaseReviewMode;

import gold.debug.windowstolinux.app.service.contract.definition.*;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.git.GitReference;
import gold.debug.windowstolinux.shared.git.GitRemote;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Converts typed form controls inside the automatic deployment use case. / 在自动部署用例内转换类型化表单控件。 */
public final class DeploymentFormUseCase {
    private DeploymentFormUseCase() { }

    /** Parses source references without moving Git policy into the UI. / 解析源码引用，不将 Git 策略移入 UI。 */
    public static AutomaticDeploymentRequest request(DeploymentSourceInput source, ServerProfile server, DeploymentFormInput input) {
        Optional<GitSourceRequest> git = Optional.empty();
        if (source.directory().isEmpty()) {
            GitRemote remote = GitRemote.parse(source.gitAddress().trim());
            GitReference reference = source.reference().isBlank() ? new GitReference.DefaultBranch()
                    : DeploymentRuntimeParser.gitReference(source.referenceKind(), source.reference().trim());
            git = Optional.of(new GitSourceRequest(remote, reference, Set.of(remote.host().orElseThrow()),
                    4L * 1024 * 1024 * 1024, false));
        }
        return new AutomaticDeploymentRequest(source.directory(), git, server, overrides(input));
    }

    private static Map<String, String> overrides(DeploymentFormInput input) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!input.detectType()) values.put("type", input.projectType().name());
        values.put("primary", input.primary()); values.put("secondary", input.secondary());
        values.put("version", input.version()); values.put("jvmTarget", input.jvmTarget());
        values.put("configuration", input.configuration()); values.put("secrets", input.secrets());
        values.put("databaseDetails", input.databaseDetails());
        if (input.databaseMode() != DatabaseReviewMode.UNREVIEWED) values.put("databaseMode", input.databaseMode().name());
        values.put("expectedStatus", input.expectedStatus()); values.put("timeout", input.timeout());
        values.put("stability", input.stability()); values.put("accessUrl", input.accessUrl());
        values.put("experimentalAdapterRisk", Boolean.toString(input.experimentalAdapterRisk()));
        values.put("jvmArguments", input.jvmArguments()); values.put("arguments", input.arguments());
        values.put("ports", input.ports()); values.put("volumes", input.volumes());
        if (input.containerEngine() != null) values.put("containerEngine", input.containerEngine().name());
        health(input, values);
        values.put("applicationDeclaration", input.applicationDeclaration());
        return Map.copyOf(values);
    }

    private static void health(DeploymentFormInput input, Map<String, String> values) {
        String selected = input.healthMode();
        if (!Set.of("AUTOMATIC", "HTTP", "TCP", "UDP", "PROCESS", "COMMAND").contains(selected)) throw new IllegalArgumentException("invalid health mode");
        String endpoint = input.healthEndpoint().trim();
        if (!selected.equals("AUTOMATIC")) values.put("healthMode", selected);
        if (endpoint.isEmpty() || Set.of("PROCESS", "COMMAND").contains(selected)) return;
        String effective = selected.equals("AUTOMATIC") ? (endpoint.matches("[0-9]+") ? "TCP" : "HTTP") : selected;
        values.put("healthMode", effective);
        if (effective.equals("TCP") || effective.equals("UDP")) values.put("port", endpoint);
        else {
            URI uri = URI.create(endpoint);
            int defaultPort = switch (java.util.Objects.toString(uri.getScheme(), "")) {
                case "http" -> 80;
                case "https" -> 443;
                default -> throw new IllegalArgumentException("health endpoint must use HTTP or HTTPS");
            };
            values.put("healthEndpoint", uri.toString());
            values.put("port", Integer.toString(uri.getPort() > 0 ? uri.getPort() : defaultPort));
        }
    }
}
