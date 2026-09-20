package gold.debug.windowstolinux.shared.model.health;

import java.net.URI;
import java.util.Objects;

/**
 * Reviewed network, process and isolated installation validation strategies.
 *
 * <p>经审阅的网络、进程与隔离安装验证策略。
 */
public sealed interface HealthCheck permits HealthCheck.Http, HealthCheck.Tcp, HealthCheck.Process,
        HealthCheck.Command, HealthCheck.Udp {
    /** Process readiness without a network endpoint. / 无网络端口的进程就绪检查。 */
    record Process(int timeoutSeconds, int stabilitySeconds) implements HealthCheck {
        public Process {
            validateTimeout(timeoutSeconds);
            if (stabilitySeconds < 1 || stabilitySeconds > timeoutSeconds)
                throw new IllegalArgumentException("process stability must fit inside its timeout");
        }
    }

    /** Bounded program verification, executed with the application identity. / 使用应用身份执行的有界程序验证。 */
    record Command(gold.debug.windowstolinux.shared.model.project.application.ApplicationCommand command,
                   String expectedOutput, int timeoutSeconds) implements HealthCheck {
        public Command {
            Objects.requireNonNull(command); Objects.requireNonNull(expectedOutput); validateTimeout(timeoutSeconds);
            if (expectedOutput.length() > 4096 || expectedOutput.indexOf(0) >= 0)
                throw new IllegalArgumentException("verification output exceeds its bounds");
        }
    }

    /** UDP requires an actual reply or an explicit protocol probe, never send success. / UDP 必须有响应或明确协议探针。 */
    record Udp(int port, String requestHex, String responseHex,
               java.util.Optional<gold.debug.windowstolinux.shared.model.project.application.ApplicationCommand> probe,
               int timeoutSeconds) implements HealthCheck {
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

    /** The optional transport port, independent of exposure. / 独立于对外范围的可选传输端口。 */
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
     * Performs the {@code timeoutSeconds} operation.
     *
     * <p>执行 {@code timeoutSeconds} 操作。
     *
     * @return the operation result / 操作结果
     */
    int timeoutSeconds();

    /**
     * Represents an immutable {@code Http} value.
     *
     * <p>表示不可变的 {@code Http} 值。
     *
     * @param endpoint the {@code endpoint} value / {@code endpoint} 值
     * @param expectedStatus the {@code expectedStatus} value / {@code expectedStatus} 值
     * @param timeoutSeconds the {@code timeoutSeconds} value / {@code timeoutSeconds} 值
     */
    record Http(URI endpoint, int expectedStatus, int timeoutSeconds) implements HealthCheck {
        /**
         * Creates a {@code Http} instance.
         *
         * <p>创建 {@code Http} 实例。
         *
         * @param endpoint the {@code endpoint} value / {@code endpoint} 值
         * @param expectedStatus the {@code expectedStatus} value / {@code expectedStatus} 值
         * @param timeoutSeconds the {@code timeoutSeconds} value / {@code timeoutSeconds} 值
         * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
         * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
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
     * <p>表示不可变的 {@code Tcp} 值。
     *
     * @param port the {@code port} value / {@code port} 值
     * @param timeoutSeconds the {@code timeoutSeconds} value / {@code timeoutSeconds} 值
     * @param stabilitySeconds the {@code stabilitySeconds} value / {@code stabilitySeconds} 值
     */
    record Tcp(int port, int timeoutSeconds, int stabilitySeconds) implements HealthCheck {
        /**
         * Creates a {@code Tcp} instance.
         *
         * <p>创建 {@code Tcp} 实例。
         *
         * @param port the {@code port} value / {@code port} 值
         * @param timeoutSeconds the {@code timeoutSeconds} value / {@code timeoutSeconds} 值
         * @param stabilitySeconds the {@code stabilitySeconds} value / {@code stabilitySeconds} 值
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

    private static void validateTimeout(int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 300");
        }
    }
}
