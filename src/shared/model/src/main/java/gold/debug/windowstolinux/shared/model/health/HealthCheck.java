package gold.debug.windowstolinux.shared.model.health;

import java.net.URI;
import java.util.Objects;

/**
 * Reviewed network, process and isolated installation validation strategies.
 *
 *  <p>经审阅的网络、进程与隔离安装验证策略。
 */
public sealed interface HealthCheck permits HealthCheck.Http, HealthCheck.Tcp, HealthCheck.Process,
        HealthCheck.Command, HealthCheck.Udp {
    /**
     * Process readiness without a network endpoint. / 无网络端口的进程就绪检查。
     *
     * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
     * @param stabilitySeconds required continuous healthy interval in seconds / 要求连续健康的时间间隔，单位为秒
     */
    record Process(int timeoutSeconds, int stabilitySeconds) implements HealthCheck {
        /**
         * Validates and binds the inputs required by process.
         * <p>校验并绑定进程所需输入。
         *
         * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
         * @param stabilitySeconds required continuous healthy interval in seconds / 要求连续健康的时间间隔，单位为秒
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        public Process {
            validateTimeout(timeoutSeconds);
            if (stabilitySeconds < 1 || stabilitySeconds > timeoutSeconds)
                throw new IllegalArgumentException("process stability must fit inside its timeout");
        }
    }

    /**
     * Bounded program verification, executed with the application identity. / 使用应用身份执行的有界程序验证。
     *
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     * @param expectedOutput expected output / 预期输出
     * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
     */
    record Command(gold.debug.windowstolinux.shared.model.project.application.ApplicationCommand command,
                   String expectedOutput, int timeoutSeconds) implements HealthCheck {
        /**
         * Validates and binds the inputs required by command.
         * <p>校验并绑定命令所需输入。
         *
         * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
         * @param expectedOutput expected output / 预期输出
         * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Command {
            Objects.requireNonNull(command); Objects.requireNonNull(expectedOutput); validateTimeout(timeoutSeconds);
            if (expectedOutput.length() > 4096 || expectedOutput.indexOf(0) >= 0)
                throw new IllegalArgumentException("verification output exceeds its bounds");
        }
    }

    /**
     * UDP requires an actual reply or an explicit protocol probe, never send success. / UDP 必须有响应或明确协议探针。
     *
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param requestHex request hex / 请求Hex
     * @param responseHex response hex / 响应Hex
     * @param probe probe / 探测
     * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
     */
    record Udp(int port, String requestHex, String responseHex,
               java.util.Optional<gold.debug.windowstolinux.shared.model.project.application.ApplicationCommand> probe,
               int timeoutSeconds) implements HealthCheck {
        /**
         * Validates and binds the inputs required by udp.
         * <p>校验并绑定Udp所需输入。
         *
         * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
         * @param requestHex request hex / 请求Hex
         * @param responseHex response hex / 响应Hex
         * @param probe probe / 探测
         * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Udp {
            validateTimeout(timeoutSeconds); Objects.requireNonNull(probe);
            Objects.requireNonNull(requestHex); Objects.requireNonNull(responseHex);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid UDP port");
            if (probe.isPresent()) {
                if (!requestHex.isEmpty() || !responseHex.isEmpty()) throw new IllegalArgumentException("mixed UDP probe modes");
            } else if (!requestHex.matches("(?:[0-9a-fA-F]{2}){1,4096}")
                    || !responseHex.matches("(?:[0-9a-fA-F]{2}){1,4096}"))
                throw new IllegalArgumentException("UDP health requires bounded request and response bytes");
            requestHex = requestHex.toLowerCase(java.util.Locale.ROOT);
            responseHex = responseHex.toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * The optional transport port, independent of exposure. / 独立于对外范围的可选传输端口。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    default java.util.OptionalInt portNumber() {
        return switch (this) {
            case Http http -> java.util.OptionalInt.of(http.endpoint().getPort() > 0 ? http.endpoint().getPort()
                    : "https".equalsIgnoreCase(http.endpoint().getScheme()) ? 443 : 80);
            case Tcp tcp -> java.util.OptionalInt.of(tcp.port());
            case Udp udp -> java.util.OptionalInt.of(udp.port());
            case Process ignored -> java.util.OptionalInt.empty();
            case Command ignored -> java.util.OptionalInt.empty();
        };
    }
    /**
     * Returns maximum waiting time in seconds.
     * <p>返回最长等待时间，单位为秒。
     *
     * @return the operation result / 操作结果
     */
    int timeoutSeconds();

    /**
     * Represents an immutable {@code Http} value.
     *
     *  <p>表示不可变的 {@code Http} 值。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param expectedStatus expected status / 预期状态
     * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
     */
    record Http(URI endpoint, int expectedStatus, int timeoutSeconds) implements HealthCheck {
        /**
         * Validates and binds the inputs required by http.
         * <p>校验并绑定HTTP所需输入。
         *
         * @param endpoint reviewed network endpoint / 已审阅网络端点
         * @param expectedStatus expected status / 预期状态
         * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
         * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Http {
            endpoint = Objects.requireNonNull(endpoint, "endpoint");
            if (!"http".equalsIgnoreCase(endpoint.getScheme()) && !"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("HTTP health check must use http or https");
            }
            if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
                throw new IllegalArgumentException("HTTP health endpoint must be a plain host URL");
            }
            String host = endpoint.getHost().toLowerCase(java.util.Locale.ROOT);
            if (!host.equals("127.0.0.1") && !host.equals("localhost") && !host.equals("::1")) {
                throw new IllegalArgumentException("HTTP health endpoint must target the candidate host loopback address");
            }
            if (expectedStatus < 200 || expectedStatus > 399) {
                throw new IllegalArgumentException("expectedStatus must be a successful HTTP status");
            }
            validateTimeout(timeoutSeconds);
        }
    }

    /**
     * Represents an immutable {@code Tcp} value.
     *
     *  <p>表示不可变的 {@code Tcp} 值。
     *
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
     * @param stabilitySeconds required continuous healthy interval in seconds / 要求连续健康的时间间隔，单位为秒
     */
    record Tcp(int port, int timeoutSeconds, int stabilitySeconds) implements HealthCheck {
        /**
         * Validates and binds the inputs required by tcp.
         * <p>校验并绑定Tcp所需输入。
         *
         * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
         * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
         * @param stabilitySeconds required continuous healthy interval in seconds / 要求连续健康的时间间隔，单位为秒
         * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
         */
        public Tcp {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
            validateTimeout(timeoutSeconds);
            if (stabilitySeconds < 1 || stabilitySeconds > 300) {
                throw new IllegalArgumentException("stabilitySeconds must be between 1 and 300");
            }
        }
    }

    /**
     * Validates timeout and rejects inputs outside the declared constraints.
     * <p>校验超时并拒绝超出已声明约束的输入。
     *
     * @param timeoutSeconds maximum waiting time in seconds / 最长等待时间，单位为秒
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void validateTimeout(int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 300");
        }
    }
}
