package gold.debug.windowstolinux.shared.model.project.application;

import java.util.Objects;

/** Transport, publication and application exposure are separate facts. / 传输、发布和应用对外范围是独立事实。 */
public record ApplicationEndpoint(String id, ProtocolType protocol, String bindAddress, int hostPort,
                                  int targetPort, ExposureType exposure, String accessUrl) {
    public enum ProtocolType { HTTP, HTTPS, TCP, UDP;
        public String transport() { return this == UDP ? "udp" : "tcp"; }
    }
    public enum ExposureType { INTERNAL, EXTERNAL }

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

    /** Validates a public browser entry without coupling exposure to health checks. */
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

    public String portKey() { return protocol.transport() + ":" + hostPort; }
    public String publishArgument() {
        return (bindAddress.contains(":") ? "[" + bindAddress + "]" : bindAddress)
                + ":" + hostPort + ":" + targetPort + "/" + protocol.transport();
    }
}
