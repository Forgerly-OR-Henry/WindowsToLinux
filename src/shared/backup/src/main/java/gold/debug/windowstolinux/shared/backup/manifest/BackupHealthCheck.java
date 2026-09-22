package gold.debug.windowstolinux.shared.backup.manifest;

import java.net.URI;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;

/**
 * Strict portable form of one reviewed managed health check. / 单个经审阅受管健康检查的严格可移植形式。
 *
 * @param type selected member of the supported type set / 受支持类型集合中的所选项
 * @param endpoint reviewed network endpoint / 已审阅网络端点
 * @param expectedStatus expected status / 预期状态
 * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
 * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
 * @param stabilitySeconds required continuous healthy interval in seconds / 要求连续健康的时间间隔，单位为秒
 * @param payload payload / 载荷
 */
public record BackupHealthCheck(BackupHealthCheckType type, String endpoint, int expectedStatus, int port,
        int timeoutSeconds, int stabilitySeconds, String payload) {
    /**
     * Initializes backup health check through its shared constructor contract.
     * <p>通过共享构造契约初始化备份健康检查。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param expectedStatus expected status / 预期状态
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
     * @param stabilitySeconds required continuous healthy interval in seconds / 要求连续健康的时间间隔，单位为秒
     */
    public BackupHealthCheck(BackupHealthCheckType type, String endpoint, int expectedStatus, int port,
            int timeoutSeconds, int stabilitySeconds) {
        this(type, endpoint, expectedStatus, port, timeoutSeconds, stabilitySeconds, "");
    }

    /**
     * Rejects mixed HTTP/TCP fields and validates through the canonical model. / 拒绝混合的 HTTP/TCP 字段并通过规范模型校验。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param expectedStatus expected status / 预期状态
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
     * @param stabilitySeconds required continuous healthy interval in seconds / 要求连续健康的时间间隔，单位为秒
     * @param payload payload / 载荷
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
                HealthCheck.Http checked = new HealthCheck.Http(URI.create(endpoint), expectedStatus, timeoutSeconds);
                endpoint = checked.endpoint().toString();
            }
            case PROCESS, COMMAND, UDP -> {
                if (!endpoint.isEmpty() || expectedStatus != 0 || port != 0 || stabilitySeconds != 0)
                    throw new IllegalArgumentException("mixed portable health fields");
                HealthCheck decoded = decode(payload);
                if (!decoded.getClass().getSimpleName().equalsIgnoreCase(type.name())
                        || decoded.timeoutSeconds() != timeoutSeconds)
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

    /**
     * Creates a portable HTTP health check. / 创建可移植 HTTP 健康检查。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param expectedStatus expected status / 预期状态
     * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
     * @return a portable HTTP health check / 可移植 HTTP 健康检查
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static BackupHealthCheck http(URI endpoint, int expectedStatus, int timeoutSeconds) {
        return new BackupHealthCheck(BackupHealthCheckType.HTTP,
                Objects.requireNonNull(endpoint, "endpoint").toString(), expectedStatus, 0, timeoutSeconds, 0);
    }

    /**
     * Creates a portable TCP health check. / 创建可移植 TCP 健康检查。
     *
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
     * @param stabilitySeconds required continuous healthy interval in seconds / 要求连续健康的时间间隔，单位为秒
     * @return a portable TCP health check / 可移植 TCP 健康检查
     */
    public static BackupHealthCheck tcp(int port, int timeoutSeconds, int stabilitySeconds) {
        return new BackupHealthCheck(BackupHealthCheckType.TCP, "", 0, port, timeoutSeconds, stabilitySeconds);
    }

    /**
     * Converts the portable value to the only supported managed health model. / 将可移植值转换为唯一受支持的受管健康模型。
     *
     * @return constructed or resolved health check / 构造或解析得到的健康检查
     */
    public HealthCheck toHealthCheck() {
        return switch (type) {
            case HTTP -> new HealthCheck.Http(URI.create(endpoint), expectedStatus, timeoutSeconds);
            case TCP -> new HealthCheck.Tcp(port, timeoutSeconds, stabilitySeconds);
            case PROCESS, COMMAND, UDP -> decode(payload);
        };
    }

    /**
     * Copies one canonical managed health check without interpreting text as a probe. / 复制规范受管健康检查且不把文本解释为探针。
     *
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return constructed or resolved backup health check / 构造或解析得到的备份健康检查
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static BackupHealthCheck from(HealthCheck healthCheck) {
        return switch (Objects.requireNonNull(healthCheck, "healthCheck")) {
            case HealthCheck.Http http -> http(http.endpoint(), http.expectedStatus(), http.timeoutSeconds());
            case HealthCheck.Tcp tcp -> tcp(tcp.port(), tcp.timeoutSeconds(), tcp.stabilitySeconds());
            case HealthCheck.Process process -> extended(BackupHealthCheckType.PROCESS, process);
            case HealthCheck.Command command -> extended(BackupHealthCheckType.COMMAND, command);
            case HealthCheck.Udp udp -> extended(BackupHealthCheckType.UDP, udp);
        };
    }

    /**
     * Builds backup health check from the supplied extended inputs.
     * <p>根据所提供扩展输入构建备份健康检查。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param health health / 健康
     * @return backup health check from the supplied extended inputs / 根据所提供扩展输入构建备份健康检查
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static BackupHealthCheck extended(BackupHealthCheckType type, HealthCheck health) {
        try {
            return new BackupHealthCheck(type, "", 0, 0, health.timeoutSeconds(), 0,
                    java.util.Base64.getEncoder().encodeToString(
                            new gold.debug.windowstolinux.shared.config.persistence.serialization.HealthCheckCodec()
                                    .write(health)));
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("invalid portable health", failure);
        }
    }

    /**
     * Decodes health check.
     * <p>解码健康检查。
     *
     * @param payload payload / 载荷
     * @return health check / 健康检查
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static HealthCheck decode(String payload) {
        if (payload.length() > 90000)
            throw new IllegalArgumentException("portable health payload too large");
        try {
            return new gold.debug.windowstolinux.shared.config.persistence.serialization.HealthCheckCodec()
                    .read(java.util.Base64.getDecoder().decode(payload));
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("unsupported portable health", failure);
        }
    }
}
