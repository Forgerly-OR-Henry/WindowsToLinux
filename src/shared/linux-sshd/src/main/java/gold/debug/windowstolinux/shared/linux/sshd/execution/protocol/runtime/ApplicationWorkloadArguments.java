package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime;

import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.application.*;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded NUL-separated UTF-8 fields, base64 framed for the root helper. / 以 Base64 封装的有界 UTF-8 字段。 */
public final class ApplicationWorkloadArguments {
    private ApplicationWorkloadArguments() { }
    public static String payload(DeploymentRuntimeSpecification runtime) {
        var workload = runtime.workload(); var fields = new ArrayList<String>();
        fields.add(workload.workers().isEmpty() ? "application-v1" : "application-v2"); fields.add(workload.mode().name()); fields.add(workload.reviewed() ? "1" : "0");
        fields.add(workload.workingDirectory()); fields.add(workload.buildDirectory()); command(fields, workload.command());
        optional(fields, workload.verification()); fields.add(workload.expectedOutput()); optional(fields, workload.client());
        fields.add(Integer.toString(workload.endpoints().size()));
        for (var endpoint : workload.endpoints()) fields.addAll(List.of(endpoint.id(), endpoint.protocol().name(), endpoint.bindAddress(),
                Integer.toString(endpoint.hostPort()), Integer.toString(endpoint.targetPort()), endpoint.exposure().name(), endpoint.accessUrl()));
        fields.add(Integer.toString(workload.inputs().size()));
        for (var input : workload.inputs()) fields.addAll(List.of(input.id(), input.hostPath(), input.accessPath()));
        fields.add(Integer.toString(workload.companions().size()));
        for (var companion : workload.companions()) fields.addAll(List.of(companion.id(), companion.sourcePath(), companion.projectType().name(), companion.artifactPath(), companion.environment()));
        if (!workload.workers().isEmpty()) {
            fields.add(Integer.toString(workload.workers().size()));
            for (var worker : workload.workers()) { fields.add(worker.id()); command(fields, worker.command()); }
        }
        health(fields, runtime.healthCheck());
        return encode(fields);
    }
    public static String healthPayload(HealthCheck check) {
        var fields = new ArrayList<String>(); fields.add("health-v1"); health(fields, check); return encode(fields);
    }
    private static void health(List<String> fields, HealthCheck check) {
        switch (check) {
            case HealthCheck.Http http -> fields.addAll(List.of("HTTP", Integer.toString(http.timeoutSeconds()), http.endpoint().toString(), Integer.toString(http.expectedStatus())));
            case HealthCheck.Tcp tcp -> fields.addAll(List.of("TCP", Integer.toString(tcp.timeoutSeconds()), Integer.toString(tcp.port()), Integer.toString(tcp.stabilitySeconds())));
            case HealthCheck.Process process -> fields.addAll(List.of("PROCESS", Integer.toString(process.timeoutSeconds()), Integer.toString(process.stabilitySeconds())));
            case HealthCheck.Command verification -> {
                fields.addAll(List.of("COMMAND", Integer.toString(verification.timeoutSeconds())));
                command(fields, verification.command()); fields.add(verification.expectedOutput());
            }
            case HealthCheck.Udp udp -> {
                fields.addAll(List.of("UDP", Integer.toString(udp.timeoutSeconds()), Integer.toString(udp.port()), udp.requestHex(), udp.responseHex()));
                optional(fields, udp.probe());
            }
        }
    }
    private static String encode(List<String> fields) {
        byte[] bytes = (String.join("\0", fields) + "\0").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65536) throw new IllegalArgumentException("application helper payload exceeds limit");
        return Base64.getEncoder().encodeToString(bytes);
    }
    private static void command(List<String> fields, ApplicationCommand command) {
        fields.add(command.entrypoint()); fields.add(Integer.toString(command.arguments().size())); fields.addAll(command.arguments());
    }
    private static void optional(List<String> fields, Optional<ApplicationCommand> command) {
        fields.add(command.isPresent() ? "1" : "0"); command.ifPresent(value -> command(fields, value));
    }
}
