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
        int stabilitySeconds
) {
    /** Rejects mixed HTTP/TCP fields and validates through the canonical model. / 拒绝混合的 HTTP/TCP 字段并通过规范模型校验。 */
    public BackupHealthCheck {
        type = Objects.requireNonNull(type, "type");
        endpoint = Objects.requireNonNull(endpoint, "endpoint").trim();
        switch (type) {
            case HTTP -> {
                if (endpoint.isEmpty() || port != 0 || stabilitySeconds != 0) {
                    throw new IllegalArgumentException("HTTP health fields are incomplete or mixed with TCP fields");
                }
                HealthCheck.Http checked = new HealthCheck.Http(
                        URI.create(endpoint), expectedStatus, timeoutSeconds);
                endpoint = checked.endpoint().toString();
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
        };
    }

    /** Copies one canonical managed health check without interpreting text as a probe. / 复制规范受管健康检查且不把文本解释为探针。 */
    public static BackupHealthCheck from(HealthCheck healthCheck) {
        return switch (Objects.requireNonNull(healthCheck, "healthCheck")) {
            case HealthCheck.Http http -> http(http.endpoint(), http.expectedStatus(), http.timeoutSeconds());
            case HealthCheck.Tcp tcp -> tcp(tcp.port(), tcp.timeoutSeconds(), tcp.stabilitySeconds());
        };
    }
}
