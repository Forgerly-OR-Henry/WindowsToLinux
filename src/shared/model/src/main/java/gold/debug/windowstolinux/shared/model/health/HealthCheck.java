package gold.debug.windowstolinux.shared.model.health;

import java.net.URI;
import java.util.Objects;

/**
 * The only two managed-deployment health strategies.
 *
 * <p>受管部署仅有的两种健康检查策略。
 */
public sealed interface HealthCheck permits HealthCheck.Http, HealthCheck.Tcp {
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
