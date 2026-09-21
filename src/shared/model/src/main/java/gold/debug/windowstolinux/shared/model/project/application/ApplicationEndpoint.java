package gold.debug.windowstolinux.shared.model.project.application;

import java.util.Objects;

/**
 * Transport, publication and application exposure are separate facts. / 传输、发布和应用对外范围是独立事实。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param protocol protocol / 协议
 * @param bindAddress bind address / 绑定地址
 * @param hostPort host port / 主机端口
 * @param targetPort target port / 目标端口
 * @param exposure exposure / 暴露
 * @param accessUrl access url / 访问URL
 */
public record ApplicationEndpoint(String id, ProtocolType protocol, String bindAddress, int hostPort,
                                  int targetPort, ExposureType exposure, String accessUrl) {
    /**
     * Identifies the network protocol of a reviewed application endpoint.
     * <p>标识已审阅应用端点的网络协议。
     */
    public enum ProtocolType {
    /**
     * HTTP protocol classification within protocol type.
     * <p>协议类型中的HTTP 协议分类。
     */
     HTTP,
    /**
     * HTTPS classification within protocol type.
     * <p>协议类型中的HTTPS分类。
     */
     HTTPS,
    /**
     * TCP classification within protocol type.
     * <p>协议类型中的TCP分类。
     */
     TCP,
    /**
     * UDP classification within protocol type.
     * <p>协议类型中的UDP分类。
     */
     UDP;
        /**
         * Returns transport.
         * <p>返回传输。
         *
         * @return transport / 传输
         */
        public String transport() { return this == UDP ? "udp" : "tcp"; }
    }
    /**
     * Identifies whether an application endpoint is public or restricted in scope.
     * <p>标识应用端点是公开还是限制在特定范围内。
     */
    public enum ExposureType {
    /**
     * INTERNAL classification within exposure type.
     * <p>暴露类型中的内部分类。
     */
     INTERNAL,
    /**
     * EXTERNAL classification within exposure type.
     * <p>暴露类型中的外部分类。
     */
     EXTERNAL }

    /**
     * Validates and binds the inputs required by application endpoint.
     * <p>校验并绑定应用端点所需输入。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param protocol protocol / 协议
     * @param bindAddress bind address / 绑定地址
     * @param hostPort host port / 主机端口
     * @param targetPort target port / 目标端口
     * @param exposure exposure / 暴露
     * @param accessUrl access url / 访问URL
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ApplicationEndpoint {
        if (!Objects.requireNonNull(id).matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("invalid endpoint identifier");
        Objects.requireNonNull(protocol); Objects.requireNonNull(exposure); Objects.requireNonNull(accessUrl);
        if (!Objects.requireNonNull(bindAddress).matches("[0-9a-fA-F:.]{2,64}")
                || !(bindAddress.contains(":") || bindAddress.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")))
            throw new IllegalArgumentException("endpoint binding must be a literal IP address");
        try {
            java.net.InetAddress.getByName(bindAddress);
        } catch (java.net.UnknownHostException failure) { throw new IllegalArgumentException("invalid endpoint address", failure); }
        if (hostPort < 1 || hostPort > 65535 || targetPort < 1 || targetPort > 65535)
            throw new IllegalArgumentException("endpoint ports must be between 1 and 65535");
        if (!accessUrl.isEmpty()) {
            if (protocol != ProtocolType.HTTP && protocol != ProtocolType.HTTPS)
                throw new IllegalArgumentException("only HTTP endpoints have browser URLs");
            requireAccessUrl(java.net.URI.create(accessUrl));
        }
    }

    /**
     * Validates and returns access url and rejects inputs outside the declared constraints.
     * <p>校验并返回访问URL并拒绝超出已声明约束的输入。
     *
     * @param url URL address / URL 地址
     * @return constructed or resolved URI / 构造或解析得到的URI
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static java.net.URI requireAccessUrl(java.net.URI url) {
        url = Objects.requireNonNull(url, "url");
        String scheme = url.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("user access URL must use http or https");
        }
        if (url.getHost() == null || url.getUserInfo() != null || url.getFragment() != null) {
            throw new IllegalArgumentException("user access URL must be an absolute credential-free URL");
        }
        if (isLoopbackOrWildcard(url.getHost())) {
            throw new IllegalArgumentException("user access URL must not use a loopback or wildcard host");
        }
        return url;
    }

    /**
     * Reports whether the loopback or wildcard condition holds for this contract.
     * <p>判断当前契约是否满足回环或Wildcard条件。
     *
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @return true when loopback or wildcard condition holds for this contract, false otherwise / 当前契约是否满足回环或Wildcard条件时为 true，否则为 false
     */
    private static boolean isLoopbackOrWildcard(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
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

    /**
     * Returns port key.
     * <p>返回端口键。
     *
     * @return port key / 端口键
     */
    public String portKey() { return protocol.transport() + ":" + hostPort; }
    /**
     * Publishes argument.
     * <p>发布参数。
     *
     * @return publish argument text / 发布参数文本
     */
    public String publishArgument() {
        return (bindAddress.contains(":") ? "[" + bindAddress + "]" : bindAddress)
                + ":" + hostPort + ":" + targetPort + "/" + protocol.transport();
    }
}
