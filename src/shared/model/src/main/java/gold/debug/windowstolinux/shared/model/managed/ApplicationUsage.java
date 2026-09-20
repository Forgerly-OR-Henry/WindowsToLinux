package gold.debug.windowstolinux.shared.model.managed;

import gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload;
import java.util.List;

/** Shared desktop/Web delivery projection; classification never comes from an edited URL. */
public record ApplicationUsage(String category, ApplicationWorkload.ExecutionMode mode, boolean reviewed,
                               boolean lifecycle, String command, List<String> endpoints) {
    public static ApplicationUsage from(ManagedApplication app, ApplicationWorkload workload) {
        if (!workload.reviewed()) return new ApplicationUsage("UNKNOWN", workload.mode(), false, false, "", List.of());
        boolean daemon = workload.mode() == ApplicationWorkload.ExecutionMode.DAEMON;
        String helper = "sudo /usr/local/lib/windowstolinux/managed-helper ";
        String command = daemon && workload.client().isEmpty()
                ? helper + "lifecycle " + app.id() + " start " + app.ownershipManifestSha256()
                : helper + (daemon ? "app-client " : "app-run ") + app.id() + " " + app.ownershipManifestSha256() + " --";
        String host = app.server().host().contains(":") ? "[" + app.server().host() + "]" : app.server().host();
        var endpoints = workload.endpoints().stream()
                .filter(endpoint -> endpoint.exposure() == gold.debug.windowstolinux.shared.model.project.application.ApplicationEndpoint.ExposureType.EXTERNAL)
                .map(endpoint -> endpoint.accessUrl().isEmpty() ?
                        endpoint.protocol().name().toLowerCase(java.util.Locale.ROOT) + "://" + host + ":" + endpoint.hostPort() : endpoint.accessUrl())
                .toList();
        return new ApplicationUsage(workload.category().name(), workload.mode(), true, daemon, command, endpoints);
    }
}
