package gold.debug.windowstolinux.shared.backup.manifest;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;

import java.net.URI;
import java.util.Objects;

/** Strict portable form of one reviewed managed health check. / 单个经审阅受管健康检查的严格可移植形式。 */
public record BackupHealthCheck(
        BackupHealthCheckType type,
        String endpoint,
        int expectedStatus,
        int port,
        int timeoutSeconds,
        int stabilitySeconds,
        String payload
) {
    public BackupHealthCheck(BackupHealthCheckType type, String endpoint, int expectedStatus, int port, int timeoutSeconds, int stabilitySeconds) {
        this(type, endpoint, expectedStatus, port, timeoutSeconds, stabilitySeconds, "");
    }
    /** Rejects mixed HTTP/TCP fields and validates through the canonical model. / 拒绝混合的 HTTP/TCP 字段并通过规范模型校验。 */
    public BackupHealthCheck {
        type = Objects.requireNonNull(type, "type");
        endpoint = Objects.requireNonNull(endpoint, "endpoint").trim();
        payload = Objects.requireNonNull(payload);
        if ((type == BackupHealthCheckType.HTTP || type == BackupHealthCheckType.TCP) && !payload.isEmpty())
            throw new IllegalArgumentException("mixed portable health payload");
        switch (type) {
            case HTTP -> {
                if (endpoint.isEmpty() || port != 0 || stabilitySeconds != 0) {
                    throw new IllegalArgumentException("HTTP health fields are incomplete or mixed with TCP fields");
                }
                HealthCheck.Http checked = new HealthCheck.Http(
                        URI.create(endpoint), expectedStatus, timeoutSeconds);
                endpoint = checked.endpoint().toString();
            }
            case PROCESS, COMMAND, UDP -> {
                if (!endpoint.isEmpty() || expectedStatus != 0 || port != 0 || stabilitySeconds != 0)
                    throw new IllegalArgumentException("mixed portable health fields");
                HealthCheck decoded = decode(payload);
                if (!decoded.getClass().getSimpleName().equalsIgnoreCase(type.name()) || decoded.timeoutSeconds() != timeoutSeconds)
                    throw new IllegalArgumentException("portable health discriminator mismatch");
            }
            case TCP -> {
                if (!endpoint.isEmpty() || expectedStatus != 0) {
                    throw new IllegalArgumentException("TCP health fields are incomplete or mixed with HTTP fields");
                }
                new HealthCheck.Tcp(port, timeoutSeconds, stabilitySeconds);
            }
        }
    }

    /** Creates a portable HTTP health check. / 创建可移植 HTTP 健康检查。 */
    public static BackupHealthCheck http(URI endpoint, int expectedStatus, int timeoutSeconds) {
        return new BackupHealthCheck(BackupHealthCheckType.HTTP,
                Objects.requireNonNull(endpoint, "endpoint").toString(), expectedStatus, 0, timeoutSeconds, 0);
    }

    /** Creates a portable TCP health check. / 创建可移植 TCP 健康检查。 */
    public static BackupHealthCheck tcp(int port, int timeoutSeconds, int stabilitySeconds) {
        return new BackupHealthCheck(BackupHealthCheckType.TCP, "", 0, port, timeoutSeconds, stabilitySeconds);
    }

    /** Converts the portable value to the only supported managed health model. / 将可移植值转换为唯一受支持的受管健康模型。 */
    public HealthCheck toHealthCheck() {
        return switch (type) {
            case HTTP -> new HealthCheck.Http(URI.create(endpoint), expectedStatus, timeoutSeconds);
            case TCP -> new HealthCheck.Tcp(port, timeoutSeconds, stabilitySeconds);
            case PROCESS, COMMAND, UDP -> decode(payload);
        };
    }

    /** Copies one canonical managed health check without interpreting text as a probe. / 复制规范受管健康检查且不把文本解释为探针。 */
    public static BackupHealthCheck from(HealthCheck healthCheck) {
        return switch (Objects.requireNonNull(healthCheck, "healthCheck")) {
            case HealthCheck.Http http -> http(http.endpoint(), http.expectedStatus(), http.timeoutSeconds());
            case HealthCheck.Tcp tcp -> tcp(tcp.port(), tcp.timeoutSeconds(), tcp.stabilitySeconds());
            case HealthCheck.Process process -> extended(BackupHealthCheckType.PROCESS, process);
            case HealthCheck.Command command -> extended(BackupHealthCheckType.COMMAND, command);
            case HealthCheck.Udp udp -> extended(BackupHealthCheckType.UDP, udp);
        };
    }

    private static BackupHealthCheck extended(BackupHealthCheckType type, HealthCheck health) {
        try { return new BackupHealthCheck(type, "", 0, 0, health.timeoutSeconds(), 0,
                java.util.Base64.getEncoder().encodeToString(new gold.debug.windowstolinux.shared.config.persistence.serialization.HealthCheckCodec().write(health)));
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("invalid portable health", failure); }
    }
    private static HealthCheck decode(String payload) {
        if (payload.length() > 90000) throw new IllegalArgumentException("portable health payload too large");
        try { return new gold.debug.windowstolinux.shared.config.persistence.serialization.HealthCheckCodec().read(java.util.Base64.getDecoder().decode(payload)); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("unsupported portable health", failure); }
    }
}
