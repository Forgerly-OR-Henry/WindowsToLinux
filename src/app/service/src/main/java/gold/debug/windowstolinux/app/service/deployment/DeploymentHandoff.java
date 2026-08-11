package gold.debug.windowstolinux.app.service.deployment;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * A secret-free, structured next step that is available only after a successful deployment.
 *
 * <p>仅在部署成功后可用的无秘密结构化后续步骤。
 */
public sealed interface DeploymentHandoff permits DeploymentHandoff.HttpAccessUrl,
        DeploymentHandoff.SystemdStartCommand {

    /**
     * Performs the {@code kind} operation.
     *
     * <p>执行 {@code kind} 操作。
     *
     * @return the operation result / 操作结果
     */
    Kind kind();

    /**
     * Defines the supported {@code Kind} values.
     *
     * <p>定义受支持的 {@code Kind} 取值。
     */
    enum Kind {
        /**
         * Represents the {@code HTTP_ACCESS_URL} option.
         *
         * <p>表示 {@code HTTP_ACCESS_URL} 选项。
         */
        HTTP_ACCESS_URL,
        /**
         * Represents the {@code SYSTEMD_START_COMMAND} option.
         *
         * <p>表示 {@code SYSTEMD_START_COMMAND} 选项。
         */
        SYSTEMD_START_COMMAND
    }

    /**
     * The explicit non-loopback business URL declared for an HTTP deployment.
     *
     * <p>为 HTTP 部署声明的明确非回环业务 URL。
     *
     * @param url the {@code url} value / {@code url} 值
     */
    record HttpAccessUrl(URI url) implements DeploymentHandoff {
        /**
         * Creates a {@code HttpAccessUrl} instance.
         *
         * <p>创建 {@code HttpAccessUrl} 实例。
         *
         * @param url the {@code url} value / {@code url} 值
         * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
         * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
         */
        public HttpAccessUrl {
            url = Objects.requireNonNull(url, "url");
            String scheme = url.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("HTTP access URL must use http or https");
            }
            if (url.getHost() == null || isLoopbackOrWildcard(url.getHost())) {
                throw new IllegalArgumentException("HTTP access URL must use a non-loopback target host");
            }
        }

        @Override
        public Kind kind() {
            return Kind.HTTP_ACCESS_URL;
        }

        private static boolean isLoopbackOrWildcard(String host) {
            String normalized = host.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("[") && normalized.endsWith("]")) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
            return normalized.equals("localhost")
                    || normalized.equals("0.0.0.0")
                    || normalized.equals("::")
                    || normalized.equals("::1")
                    || normalized.equals("0:0:0:0:0:0:0:1")
                    || normalized.startsWith("127.");
        }
    }

    /**
     * A bounded start command for the exact WindowsToLinux-managed application.
     *
     * <p>用于精确启动 WindowsToLinux 受管应用的受限命令。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @param systemdUnit the {@code systemdUnit} value / {@code systemdUnit} 值
     * @param ownershipManifestSha256 the {@code ownershipManifestSha256} value / {@code ownershipManifestSha256} 值
     */
    record SystemdStartCommand(String applicationId, String systemdUnit, String ownershipManifestSha256)
            implements DeploymentHandoff {
        /**
         * Creates a {@code SystemdStartCommand} instance.
         *
         * <p>创建 {@code SystemdStartCommand} 实例。
         *
         * @param applicationId the {@code applicationId} value / {@code applicationId} 值
         * @param systemdUnit the {@code systemdUnit} value / {@code systemdUnit} 值
         * @param ownershipManifestSha256 the {@code ownershipManifestSha256} value / {@code ownershipManifestSha256} 值
         * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
         * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
         */
        public SystemdStartCommand {
            applicationId = Objects.requireNonNull(applicationId, "applicationId");
            if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
                throw new IllegalArgumentException("applicationId must be a WindowsToLinux managed application");
            }
            systemdUnit = Objects.requireNonNull(systemdUnit, "systemdUnit");
            if (!systemdUnit.equals("windowstolinux-" + applicationId + ".service")) {
                throw new IllegalArgumentException("systemdUnit must be a WindowsToLinux managed service");
            }
            ownershipManifestSha256 = Objects.requireNonNull(ownershipManifestSha256, "ownershipManifestSha256");
            if (!ownershipManifestSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("ownershipManifestSha256 must be a lowercase SHA-256");
            }
        }

        @Override
        public Kind kind() {
            return Kind.SYSTEMD_START_COMMAND;
        }

        /**
         * Performs the {@code command} operation.
         *
         * <p>执行 {@code command} 操作。
         *
         * @return the operation result / 操作结果
         */
        public String command() {
            return "sudo /usr/local/lib/windowstolinux/phase1-helper lifecycle "
                    + applicationId + " start " + ownershipManifestSha256;
        }
    }
}
