package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime;

import java.nio.charset.StandardCharsets;
import java.util.*;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.application.*;

/**
 * Bounded NUL-separated UTF-8 fields, base64 framed for the root helper. / 以 Base64 封装的有界 UTF-8 字段。
 */
public final class ApplicationWorkloadArguments {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ApplicationWorkloadArguments() {
    }

    /**
     * Serializes the reviewed workload into the versioned helper field sequence and encodes its payload.
     * <p>将已审阅工作负载序列化为带版本的 helper 字段序列，并编码其载荷。
     *
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return payload text / 载荷文本
     */
    public static String payload(DeploymentRuntimeSpecification runtime) {
        var workload = runtime.workload();
        var fields = new ArrayList<String>();
        fields.add(workload.workers().isEmpty() ? "application-v1" : "application-v2");
        fields.add(workload.mode().name());
        fields.add(workload.reviewed() ? "1" : "0");
        fields.add(workload.workingDirectory());
        fields.add(workload.buildDirectory());
        command(fields, workload.command());
        optional(fields, workload.verification());
        fields.add(workload.expectedOutput());
        optional(fields, workload.client());
        fields.add(Integer.toString(workload.endpoints().size()));
        for (var endpoint : workload.endpoints())
            fields.addAll(List.of(endpoint.id(), endpoint.protocol().name(), endpoint.bindAddress(),
                    Integer.toString(endpoint.hostPort()), Integer.toString(endpoint.targetPort()),
                    endpoint.exposure().name(), endpoint.accessUrl()));
        fields.add(Integer.toString(workload.inputs().size()));
        for (var input : workload.inputs())
            fields.addAll(List.of(input.id(), input.hostPath(), input.accessPath()));
        fields.add(Integer.toString(workload.companions().size()));
        for (var companion : workload.companions())
            fields.addAll(List.of(companion.id(), companion.sourcePath(), companion.projectType().name(),
                    companion.artifactPath(), companion.environment()));
        if (!workload.workers().isEmpty()) {
            fields.add(Integer.toString(workload.workers().size()));
            for (var worker : workload.workers()) {
                fields.add(worker.id());
                command(fields, worker.command());
            }
        }
        health(fields, runtime.healthCheck());
        return encode(fields);
    }

    /**
     * Encodes a health-v1 payload containing the selected health-check contract.
     * <p>编码包含所选健康检查契约的 health-v1 载荷。
     *
     * @param check check / 检查
     * @return a health-v1 payload containing the selected health-check contract / 包含所选健康检查契约的 health-v1 载荷
     */
    public static String healthPayload(HealthCheck check) {
        var fields = new ArrayList<String>();
        fields.add("health-v1");
        health(fields, check);
        return encode(fields);
    }

    /**
     * Appends the health-check variant and its exact protocol fields to the output sequence.
     * <p>向输出序列追加健康检查变体及其精确协议字段。
     *
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @param check check / 检查
     */
    private static void health(List<String> fields, HealthCheck check) {
        switch (check) {
            case HealthCheck.Http http -> fields.addAll(List.of("HTTP", Integer.toString(http.timeoutSeconds()),
                    http.endpoint().toString(), Integer.toString(http.expectedStatus())));
            case HealthCheck.Tcp tcp -> fields.addAll(List.of("TCP", Integer.toString(tcp.timeoutSeconds()),
                    Integer.toString(tcp.port()), Integer.toString(tcp.stabilitySeconds())));
            case HealthCheck.Process process -> fields.addAll(List.of("PROCESS",
                    Integer.toString(process.timeoutSeconds()), Integer.toString(process.stabilitySeconds())));
            case HealthCheck.Command verification -> {
                fields.addAll(List.of("COMMAND", Integer.toString(verification.timeoutSeconds())));
                command(fields, verification.command());
                fields.add(verification.expectedOutput());
            }
            case HealthCheck.Udp udp -> {
                fields.addAll(List.of("UDP", Integer.toString(udp.timeoutSeconds()), Integer.toString(udp.port()),
                        udp.requestHex(), udp.responseHex()));
                optional(fields, udp.probe());
            }
        }
    }

    /**
     * Encodes application workload arguments.
     * <p>编码应用工作负载参数。
     *
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @return application workload arguments / 应用工作负载参数
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static String encode(List<String> fields) {
        byte[] bytes = (String.join("\0", fields) + "\0").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65536)
            throw new IllegalArgumentException("application helper payload exceeds limit");
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Appends the reviewed entrypoint and literal arguments to the fixed protocol field sequence.
     * <p>将已审阅入口及字面参数追加到固定协议字段序列。
     *
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     */
    private static void command(List<String> fields, ApplicationCommand command) {
        fields.add(command.entrypoint());
        fields.add(Integer.toString(command.arguments().size()));
        fields.addAll(command.arguments());
    }

    /**
     * Appends a presence flag and, when present, the serialized application command.
     * <p>追加存在标记，并在命令存在时追加序列化应用命令。
     *
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     */
    private static void optional(List<String> fields, Optional<ApplicationCommand> command) {
        fields.add(command.isPresent() ? "1" : "0");
        command.ifPresent(value -> command(fields, value));
    }
}
