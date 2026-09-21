package gold.debug.windowstolinux.app.service.deployment.single;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * A secret-free, structured next step that is available only after a successful deployment.
 *
 *  <p>仅在部署成功后可用的无秘密结构化后续步骤。
 */
public sealed interface DeploymentHandoff permits DeploymentHandoff.HttpAccessUrl,
        DeploymentHandoff.SystemdStartCommand, DeploymentHandoff.ApplicationEntry {

    /**
     * Returns selected member of the supported kind set.
     * <p>返回受支持种类集合中的所选项。
     *
     * @return the operation result / 操作结果
     */
    Kind kind();

    /**
     * Defines the supported {@code Kind} values.
     *
     *  <p>定义受支持的 {@code Kind} 取值。
     */
    enum Kind {
        /**
         * Represents the {@code HTTP_ACCESS_URL} option.
         *
         *  <p>表示 {@code HTTP_ACCESS_URL} 选项。
         */
        HTTP_ACCESS_URL,
        /**
         * Represents the {@code SYSTEMD_START_COMMAND} option.
         *
         *  <p>表示 {@code SYSTEMD_START_COMMAND} 选项。
         */
        APPLICATION_ENTRY,
        /**
         * SYSTEMD START COMMAND classification within kind.
         * <p>种类中的SYSTEMD启动命令分类。
         */
        SYSTEMD_START_COMMAND
    }

    /**
     * The explicit non-loopback business URL declared for an HTTP deployment.
     *
     *  <p>为 HTTP 部署声明的明确非回环业务 URL。
     *
     * @param url URL address / URL 地址
     */
    record HttpAccessUrl(URI url) implements DeploymentHandoff {
        /**
         * Validates and binds the inputs required by http access url.
         * <p>校验并绑定HTTP访问URL所需输入。
         *
         * @param url URL address / URL 地址
         * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
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

        /**
         * Returns selected member of the supported kind set.
         * <p>返回受支持种类集合中的所选项。
         *
         * @return selected member of the supported kind set / 受支持种类集合中的所选项
         */
        @Override
        public Kind kind() {
            return Kind.HTTP_ACCESS_URL;
        }

        /**
         * Reports whether the loopback or wildcard condition holds for this contract.
         * <p>判断当前契约是否满足回环或Wildcard条件。
         *
         * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
         * @return true when loopback or wildcard condition holds for this contract, false otherwise / 当前契约是否满足回环或Wildcard条件时为 true，否则为 false
         */
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
     *  <p>用于精确启动 WindowsToLinux 受管应用的受限命令。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param systemdUnit systemd unit / systemd单元
     * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
     */
    record SystemdStartCommand(String applicationId, String systemdUnit, String ownershipManifestSha256)
            implements DeploymentHandoff {
        /**
         * Validates and binds the inputs required by systemd start command.
         * <p>校验并绑定Systemd启动命令所需输入。
         *
         * @param applicationId managed application identifier / 受管应用标识
         * @param systemdUnit systemd unit / systemd单元
         * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
         * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
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

        /**
         * Returns selected member of the supported kind set.
         * <p>返回受支持种类集合中的所选项。
         *
         * @return selected member of the supported kind set / 受支持种类集合中的所选项
         */
        @Override
        public Kind kind() {
            return Kind.SYSTEMD_START_COMMAND;
        }

        /**
         * Returns fixed or explicitly reviewed command text.
         * <p>返回固定或显式审阅的命令文本。
         *
         * @return the operation result / 操作结果
         */
        public String command() {
            return "sudo /usr/local/lib/windowstolinux/managed-helper lifecycle "
                    + applicationId + " start " + ownershipManifestSha256;
        }
    }
    /**
     * Associates a successfully deployed application with its local handoff metadata.
     * <p>将成功部署的应用与其本地交接元数据关联。
     *
     * @param usage usage / 用法
     */
    record ApplicationEntry(gold.debug.windowstolinux.shared.model.managed.ApplicationUsage usage) implements DeploymentHandoff {
        /**
         * Returns selected member of the supported kind set.
         * <p>返回受支持种类集合中的所选项。
         *
         * @return selected member of the supported kind set / 受支持种类集合中的所选项
         */
        public Kind kind() { return Kind.APPLICATION_ENTRY; }
        /**
         * Returns fixed or explicitly reviewed command text.
         * <p>返回固定或显式审阅的命令文本。
         *
         * @return fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
         */
        public String command() { return usage.endpoints().isEmpty() ? usage.command() : String.join("\n", usage.endpoints()); }
    }

}
